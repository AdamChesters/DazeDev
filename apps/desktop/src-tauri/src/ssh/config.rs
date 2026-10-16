//! Resolve an SSH destination the way OpenSSH would.
//!
//! `ssh -G` prints the final configuration after Include, Match and Host
//! blocks are applied, so DazeDev follows the user's ssh_config without
//! reimplementing its grammar. Without an OpenSSH client, plain defaults apply.
use std::{path::PathBuf, time::Duration};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Strict {
    Ask,
    Yes,
    AcceptNew,
    No,
}

#[derive(Clone, Debug, Default)]
pub struct Request {
    pub host: String,
    pub user: Option<String>,
    pub port: Option<u16>,
    pub identity: Option<String>,
    pub jump: Option<String>,
}

#[derive(Clone, Debug)]
pub struct Resolved {
    /// The name the user typed, used for display and the `%n` token.
    pub host: String,
    pub hostname: String,
    pub user: String,
    pub port: u16,
    pub identity_files: Vec<PathBuf>,
    pub certificate_files: Vec<PathBuf>,
    pub identities_only: bool,
    /// `None` uses the platform default agent; `Some("none")` disables it.
    pub identity_agent: Option<String>,
    pub proxy_jump: Vec<String>,
    pub proxy_command: Option<String>,
    pub user_known_hosts: Vec<PathBuf>,
    pub global_known_hosts: Vec<PathBuf>,
    pub host_key_alias: Option<String>,
    pub strict: Strict,
    pub hash_known_hosts: bool,
    pub batch_mode: bool,
    pub connect_timeout: Option<Duration>,
    pub server_alive_interval: Option<Duration>,
    pub server_alive_count_max: usize,
    pub preferred_auth: Vec<String>,
    pub pubkey: bool,
    pub password: bool,
    pub kbd_interactive: bool,
    pub password_prompts: u32,
    /// Set when the configuration needs a feature only OpenSSH provides.
    pub unsupported: Option<String>,
}

fn home() -> PathBuf {
    dirs::home_dir().unwrap_or_default()
}

fn local_user() -> String {
    std::env::var("USER")
        .or_else(|_| std::env::var("USERNAME"))
        .unwrap_or_default()
}

impl Resolved {
    fn defaults(request: &Request) -> Self {
        let ssh = home().join(".ssh");
        let mut identity_files: Vec<PathBuf> = request
            .identity
            .iter()
            .filter(|i| !i.is_empty())
            .map(PathBuf::from)
            .collect();
        for name in ["id_rsa", "id_ecdsa", "id_ed25519"] {
            identity_files.push(ssh.join(name));
        }
        Self {
            host: request.host.clone(),
            hostname: request.host.clone(),
            user: request
                .user
                .clone()
                .filter(|u| !u.is_empty())
                .unwrap_or_else(local_user),
            port: request.port.unwrap_or(22),
            identity_files,
            certificate_files: Vec::new(),
            identities_only: false,
            identity_agent: None,
            proxy_jump: request
                .jump
                .iter()
                .flat_map(|j| j.split(','))
                .map(str::trim)
                .filter(|j| !j.is_empty())
                .map(str::to_owned)
                .collect(),
            proxy_command: None,
            user_known_hosts: vec![ssh.join("known_hosts"), ssh.join("known_hosts2")],
            global_known_hosts: Vec::new(),
            host_key_alias: None,
            strict: Strict::Ask,
            hash_known_hosts: false,
            batch_mode: false,
            connect_timeout: None,
            server_alive_interval: None,
            server_alive_count_max: 3,
            preferred_auth: ["publickey", "keyboard-interactive", "password"]
                .map(str::to_owned)
                .to_vec(),
            pubkey: true,
            password: true,
            kbd_interactive: true,
            password_prompts: 3,
            unsupported: None,
        }
    }

    /// The name OpenSSH records in known_hosts for this destination.
    pub fn known_hosts_name(&self) -> String {
        let name = self.host_key_alias.as_deref().unwrap_or(&self.hostname);
        if self.port == 22 {
            name.to_owned()
        } else {
            format!("[{name}]:{}", self.port)
        }
    }

    pub fn known_hosts_files(&self) -> Vec<PathBuf> {
        self.user_known_hosts
            .iter()
            .chain(self.global_known_hosts.iter())
            .cloned()
            .collect()
    }
}

fn yes(value: &str) -> bool {
    matches!(value, "yes" | "true")
}

/// Expand the `%` tokens and `~` that OpenSSH allows in file paths.
pub fn expand(value: &str, resolved: &Resolved) -> String {
    let home = home().to_string_lossy().into_owned();
    let mut out = String::new();
    let mut chars = value.chars().peekable();
    if value == "~" || value.starts_with("~/") || value.starts_with("~\\") {
        out.push_str(&home);
        chars.next();
    }
    while let Some(c) = chars.next() {
        if c != '%' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('%') => out.push('%'),
            Some('d') => out.push_str(&home),
            Some('u') => out.push_str(&local_user()),
            Some('h') => out.push_str(&resolved.hostname),
            Some('n') => out.push_str(&resolved.host),
            Some('r') => out.push_str(&resolved.user),
            Some('p') => out.push_str(&resolved.port.to_string()),
            Some('k') => out.push_str(
                resolved
                    .host_key_alias
                    .as_deref()
                    .unwrap_or(&resolved.hostname),
            ),
            Some(other) => {
                out.push('%');
                out.push(other);
            }
            None => out.push('%'),
        }
    }
    // Win32-OpenSSH prints its system folder as a placeholder.
    if let Some(rest) = out.strip_prefix("__PROGRAMDATA__") {
        let data = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".into());
        return format!("{data}{rest}");
    }
    out
}

/// Parse `ssh -G` output. Unknown keys are ignored.
pub fn parse(output: &str, request: &Request) -> Resolved {
    let mut r = Resolved::defaults(request);
    let mut identity = Vec::new();
    let mut certificates = Vec::new();
    let mut user_known = None;
    let mut global_known = None;
    let mut agent = None;
    for line in output.lines() {
        let Some((key, value)) = line.trim().split_once(' ') else {
            continue;
        };
        let value = value.trim();
        match key.to_ascii_lowercase().as_str() {
            "hostname" => r.hostname = value.to_owned(),
            "user" => r.user = value.to_owned(),
            "port" => r.port = value.parse().unwrap_or(22),
            "identityfile" => identity.push(value.to_owned()),
            "certificatefile" => certificates.push(value.to_owned()),
            "identitiesonly" => r.identities_only = yes(value),
            "identityagent" => agent = Some(value.to_owned()),
            "proxyjump" if value != "none" => {
                r.proxy_jump = value
                    .split(',')
                    .map(str::trim)
                    .filter(|j| !j.is_empty())
                    .map(str::to_owned)
                    .collect()
            }
            "proxycommand" if value != "none" => r.proxy_command = Some(value.to_owned()),
            "userknownhostsfile" => user_known = Some(value.to_owned()),
            "globalknownhostsfile" => global_known = Some(value.to_owned()),
            "hostkeyalias" => r.host_key_alias = Some(value.to_owned()),
            "stricthostkeychecking" => {
                r.strict = match value {
                    "yes" | "true" => Strict::Yes,
                    "accept-new" => Strict::AcceptNew,
                    "no" | "false" | "off" => Strict::No,
                    _ => Strict::Ask,
                }
            }
            "hashknownhosts" => r.hash_known_hosts = yes(value),
            "batchmode" => r.batch_mode = yes(value),
            "connecttimeout" => {
                r.connect_timeout = value.parse::<u64>().ok().map(Duration::from_secs)
            }
            "serveraliveinterval" => {
                r.server_alive_interval = value
                    .parse::<u64>()
                    .ok()
                    .filter(|s| *s > 0)
                    .map(Duration::from_secs)
            }
            "serveralivecountmax" => r.server_alive_count_max = value.parse().unwrap_or(3),
            "preferredauthentications" => {
                r.preferred_auth = value.split(',').map(str::to_owned).collect()
            }
            "pubkeyauthentication" => r.pubkey = yes(value) || value == "unbound",
            "passwordauthentication" => r.password = yes(value),
            "kbdinteractiveauthentication" | "challengeresponseauthentication" => {
                r.kbd_interactive = yes(value)
            }
            "numberofpasswordprompts" => r.password_prompts = value.parse().unwrap_or(3),
            "pkcs11provider" if value != "none" => {
                r.unsupported = Some("PKCS11Provider is set".into())
            }
            "localcommand" | "permitlocalcommand" | "remotecommand" | "requesttty" => {}
            _ => {}
        }
    }
    if !identity.is_empty() {
        r.identity_files = identity
            .iter()
            .map(|i| PathBuf::from(expand(i, &r)))
            .collect();
    }
    r.certificate_files = certificates
        .iter()
        .map(|c| PathBuf::from(expand(c, &r)))
        .collect();
    if let Some(files) = user_known {
        r.user_known_hosts = files
            .split_whitespace()
            .filter(|f| *f != "none")
            .map(|f| PathBuf::from(expand(f, &r)))
            .collect();
    }
    if let Some(files) = global_known {
        r.global_known_hosts = files
            .split_whitespace()
            .filter(|f| *f != "none")
            .map(|f| PathBuf::from(expand(f, &r)))
            .collect();
    }
    r.identity_agent = agent.map(|a| {
        if a == "none" || a == "SSH_AUTH_SOCK" {
            a
        } else {
            expand(&a, &r)
        }
    });
    r
}

/// Split one ProxyJump hop: `[user@]host[:port]` or `ssh://[user@]host[:port]`.
pub fn parse_hop(hop: &str) -> Request {
    let hop = hop.strip_prefix("ssh://").unwrap_or(hop);
    let (user, rest) = match hop.rsplit_once('@') {
        Some((user, rest)) => (Some(user.to_owned()), rest),
        None => (None, hop),
    };
    let (host, port) = if let Some(inner) = rest.strip_prefix('[') {
        match inner.split_once(']') {
            Some((host, tail)) => (host, tail.strip_prefix(':').and_then(|p| p.parse().ok())),
            None => (rest, None),
        }
    } else {
        match rest.rsplit_once(':') {
            Some((host, port)) if !host.contains(':') => (host, port.parse().ok()),
            _ => (rest, None),
        }
    };
    Request {
        host: host.to_owned(),
        user,
        port,
        identity: None,
        jump: None,
    }
}

fn ssh_program() -> std::path::PathBuf {
    #[cfg(windows)]
    if let Some(windir) = std::env::var_os("WINDIR") {
        let path = std::path::PathBuf::from(windir).join("System32/OpenSSH/ssh.exe");
        if path.is_file() {
            return path;
        }
    }
    "ssh".into()
}

#[cfg(test)]
static TEST_CONFIG: std::sync::Mutex<Option<PathBuf>> = std::sync::Mutex::new(None);

/// Tests point `ssh -G` at their own config so they never read or write the
/// user's real files.
#[cfg(test)]
pub fn set_test_config(path: Option<PathBuf>) {
    *TEST_CONFIG.lock().unwrap() = path;
}

/// Run `ssh -G`; fall back to defaults when OpenSSH is missing or slow.
pub fn resolve(request: &Request) -> Resolved {
    use std::io::Read;
    let mut command = std::process::Command::new(ssh_program());
    command.arg("-G");
    #[cfg(test)]
    if let Some(path) = TEST_CONFIG.lock().unwrap().clone() {
        command.arg("-F").arg(path);
    }
    if let Some(port) = request.port {
        command.args(["-p", &port.to_string()]);
    }
    if let Some(user) = request.user.as_deref().filter(|u| !u.is_empty()) {
        command.args(["-l", user]);
    }
    if let Some(key) = request.identity.as_deref().filter(|k| !k.is_empty()) {
        command.args(["-i", key]);
    }
    if let Some(jump) = request.jump.as_deref().filter(|j| !j.is_empty()) {
        command.args(["-J", jump]);
    }
    command
        .arg("--")
        .arg(&request.host)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let Ok(mut child) = command.spawn() else {
        return Resolved::defaults(request);
    };
    let mut stdout = child.stdout.take().expect("piped stdout");
    let reader = std::thread::spawn(move || {
        let mut text = String::new();
        let _ = (&mut stdout).take(1 << 20).read_to_string(&mut text);
        text
    });
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(20))
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
        }
    };
    let text = reader.join().unwrap_or_default();
    match status {
        Some(status) if status.success() && !text.is_empty() => parse(&text, request),
        _ => Resolved::defaults(request),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_openssh_output() {
        let output = "user dev\nhostname 10.0.0.5\nport 2222\nidentityfile ~/.ssh/id_ed25519\nidentityfile %d/.ssh/work_%r\nidentitiesonly yes\nproxyjump gate,admin@edge:2200\nuserknownhostsfile ~/.ssh/known_hosts ~/.ssh/known_hosts2\nglobalknownhostsfile /etc/ssh/ssh_known_hosts\nstricthostkeychecking accept-new\nhashknownhosts yes\nconnecttimeout 7\nserveraliveinterval 0\npreferredauthentications publickey,password\npasswordauthentication no\nidentityagent none\n";
        let request = Request {
            host: "work".into(),
            ..Default::default()
        };
        let r = parse(output, &request);
        assert_eq!(r.hostname, "10.0.0.5");
        assert_eq!(r.user, "dev");
        assert_eq!(r.port, 2222);
        let home = home();
        assert_eq!(
            r.identity_files,
            vec![
                home.join(".ssh/id_ed25519"),
                PathBuf::from(format!("{}/.ssh/work_dev", home.display()))
            ]
        );
        assert!(r.identities_only);
        assert_eq!(r.proxy_jump, vec!["gate", "admin@edge:2200"]);
        assert_eq!(r.user_known_hosts.len(), 2);
        assert_eq!(r.strict, Strict::AcceptNew);
        assert!(r.hash_known_hosts);
        assert_eq!(r.connect_timeout, Some(Duration::from_secs(7)));
        assert_eq!(r.server_alive_interval, None);
        assert_eq!(r.preferred_auth, vec!["publickey", "password"]);
        assert!(!r.password);
        assert_eq!(r.identity_agent.as_deref(), Some("none"));
        assert_eq!(r.known_hosts_name(), "[10.0.0.5]:2222");
        assert!(r.unsupported.is_none());
    }

    #[test]
    fn pkcs11_needs_openssh() {
        let r = parse(
            "pkcs11provider /usr/lib/opensc-pkcs11.so\n",
            &Request::default(),
        );
        assert!(r.unsupported.is_some());
        assert!(parse("pkcs11provider none\n", &Request::default())
            .unsupported
            .is_none());
    }

    #[test]
    fn host_key_alias_names_known_hosts_entry() {
        let mut r = parse("hostname 10.1.1.1\nport 22\n", &Request::default());
        r.host_key_alias = Some("build".into());
        assert_eq!(r.known_hosts_name(), "build");
    }

    #[test]
    fn hops_split_user_host_and_port() {
        let hop = parse_hop("admin@edge:2200");
        assert_eq!(
            (hop.user.as_deref(), hop.host.as_str(), hop.port),
            (Some("admin"), "edge", Some(2200))
        );
        let hop = parse_hop("[fe80::1]:22");
        assert_eq!((hop.host.as_str(), hop.port), ("fe80::1", Some(22)));
        let hop = parse_hop("ssh://gate");
        assert_eq!(
            (hop.user, hop.host.as_str(), hop.port),
            (None, "gate", None)
        );
        let hop = parse_hop("fe80::1");
        assert_eq!((hop.host.as_str(), hop.port), ("fe80::1", None));
    }

    #[test]
    fn expands_tokens_and_keeps_unknown_ones() {
        let r = parse("hostname h.example\nuser u\nport 22\n", &Request::default());
        assert_eq!(expand("%h-%r-%p-%%-%z", &r), "h.example-u-22-%-%z");
    }
}
