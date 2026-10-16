//! Commands that start host programs: SSH, Tailscale, WSL and local shells.
use super::*;
pub fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\"'\"'"))
}
pub(super) fn checked(s: &str) -> Result<(), String> {
    if s.contains('\0') || s.contains('\n') || s.contains('\r') {
        Err("Invalid connection setting".into())
    } else {
        Ok(())
    }
}
pub(super) fn tailscale_executable() -> std::path::PathBuf {
    #[cfg(windows)]
    if let Some(program_files) = std::env::var_os("ProgramFiles") {
        let path = std::path::PathBuf::from(program_files)
            .join("Tailscale")
            .join("tailscale.exe");
        if path.is_file() {
            return path;
        }
    }
    "tailscale".into()
}
pub(super) fn tailscale_target_is_windows(status: &Value, address: &str) -> bool {
    let address = address.trim_end_matches('.').to_ascii_lowercase();
    let Some(peers) = status.get("Peer").and_then(Value::as_object) else {
        return false;
    };
    let matches: Vec<_> = peers
        .values()
        .filter(|peer| {
            let dns = peer["DNSName"]
                .as_str()
                .unwrap_or("")
                .trim_end_matches('.')
                .to_ascii_lowercase();
            dns == address
                || (!address.contains('.') && dns.split('.').next() == Some(address.as_str()))
                || peer["TailscaleIPs"]
                    .as_array()
                    .is_some_and(|ips| ips.iter().any(|ip| ip.as_str() == Some(&address)))
        })
        .collect();
    matches.len() == 1
        && matches[0]["OS"]
            .as_str()
            .is_some_and(|os| os.eq_ignore_ascii_case("windows"))
}

pub(super) const TAILSCALE_WINDOWS_TARGET: &str = "TAILSCALE_WINDOWS_TARGET: This Tailscale device runs Windows, which cannot serve Tailscale SSH. Connect to a WSL/Linux device with Tailscale SSH enabled, using its own tailnet name and Linux username. Ordinary SSH over Tailscale uses separate SSH credentials; DazeDev's remote host also needs Python 3 and tmux.";

// Status is an optional local hint. Bound the probe and keep normal SSH errors if it is unavailable.
pub(super) fn tailscale_status() -> Option<Value> {
    let mut command = std::process::Command::new(tailscale_executable());
    command
        .args(["status", "--json"])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let Ok(mut child) = command.spawn() else {
        return None;
    };
    let stdout = child.stdout.take().unwrap();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        let result = stdout.take(4 * 1024 * 1024 + 1).read_to_end(&mut bytes);
        let _ = tx.send((result, bytes));
    });
    let deadline = std::time::Instant::now() + Duration::from_secs(4);
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(25))
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
        }
    };
    if !status.is_some_and(|s| s.success()) {
        return None;
    }
    match rx.recv_timeout(Duration::from_millis(100)) {
        Ok((Ok(_), bytes)) if bytes.len() <= 4 * 1024 * 1024 => serde_json::from_slice(&bytes).ok(),
        _ => None,
    }
}
pub fn command(host: &Host, remote: &str) -> Result<CommandBuilder, String> {
    for s in [
        &host.address,
        &host.user,
        &host.key,
        &host.jump,
        &host.distro,
        &host.socket,
    ] {
        checked(s)?;
    }
    match host.kind.as_str() {
        "ssh" => {
            if host.address.is_empty() || host.address.starts_with('-') {
                return Err("Enter an SSH alias or hostname".into());
            }
            let mut c = CommandBuilder::new("ssh");
            // `-e none`: a pasted "\n~." must not end the session.
            c.args([
                "-tt",
                "-e",
                "none",
                "-o",
                "ServerAliveInterval=15",
                "-o",
                "ServerAliveCountMax=3",
            ]);
            if let Some(port) = host.port {
                c.args(["-p", &port.to_string()]);
            }
            if !host.user.is_empty() {
                c.args(["-l", &host.user]);
            }
            if !host.key.is_empty() {
                c.args(["-i", &host.key]);
            }
            if !host.jump.is_empty() {
                c.args(["-J", &host.jump]);
            }
            c.arg(&host.address);
            c.arg(remote);
            Ok(c)
        }
        "tailscale" => {
            if host.address.is_empty()
                || host.address.starts_with('-')
                || host.address.contains(char::is_whitespace)
                || host.address.contains('@')
            {
                return Err("Enter a Tailscale hostname or IP without a username".into());
            }
            if host.user.is_empty()
                || host.user.starts_with('-')
                || host.user.contains(char::is_whitespace)
                || host.user.contains('@')
            {
                return Err("Enter the Linux username on the Tailscale host".into());
            }
            // Tailscale verifies the host key through its control plane and supplies
            // the authenticated device identity. Never disable host-key verification.
            let mut c = CommandBuilder::new(tailscale_executable());
            c.arg("ssh");
            c.arg(format!("{}@{}", host.user, host.address));
            c.args([
                "-tt",
                "-e",
                "none",
                "-o",
                "ServerAliveInterval=15",
                "-o",
                "ServerAliveCountMax=3",
            ]);
            c.arg(remote);
            Ok(c)
        }
        "wsl" => {
            let mut c = CommandBuilder::new("wsl.exe");
            if !host.distro.is_empty() {
                c.args(["-d", &host.distro]);
            }
            // `-e` runs sh directly; `--` would hand the line to the default shell.
            c.args(["-e", "sh", "-lc", remote]);
            Ok(c)
        }
        "local" => {
            if cfg!(windows) {
                return Err("Local tmux on Windows requires a WSL connection".into());
            }
            let mut c = CommandBuilder::new("sh");
            c.args(["-lc", remote]);
            Ok(c)
        }
        _ => Err("Unknown connection type".into()),
    }
}
/// A WSL or local shell started with plain pipes, for the host helper.
pub(super) fn pipe_command(host: &Host, remote: &str) -> Result<std::process::Command, String> {
    checked(&host.distro)?;
    let mut c = match host.kind.as_str() {
        "wsl" => {
            let mut c = std::process::Command::new("wsl.exe");
            if !host.distro.is_empty() {
                c.args(["-d", &host.distro]);
            }
            c.args(["-e", "sh", "-lc", remote]);
            c
        }
        "local" if !cfg!(windows) => {
            let mut c = std::process::Command::new("sh");
            c.args(["-lc", remote]);
            c
        }
        "local" => return Err("Local tmux on Windows requires a WSL connection".into()),
        _ => return Err("Unknown connection type".into()),
    };
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x08000000);
    }
    c.stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    Ok(c)
}
