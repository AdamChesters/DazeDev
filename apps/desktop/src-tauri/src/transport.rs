use base64::{engine::general_purpose::STANDARD, Engine};
use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    io::{Read, Write},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc, Arc, Mutex, OnceLock,
    },
    time::Duration,
};
pub type EventSink = Arc<dyn Fn(&str, Value) + Send + Sync>;
/// Receives a terminal's raw output bytes, in order.
pub type OutputSink = Arc<dyn Fn(&[u8]) + Send + Sync>;
/// Output as `terminal-output` events with base64 data, for callers that
/// have no byte channel.
pub fn event_output(sink: EventSink, id: String) -> OutputSink {
    Arc::new(move |bytes| {
        sink(
            "terminal-output",
            json!({"id":id,"data":STANDARD.encode(bytes)}),
        )
    })
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Host {
    pub id: String,
    pub name: String,
    pub kind: String,
    #[serde(default)]
    pub address: String,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub port: Option<u16>,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub jump: String,
    #[serde(default)]
    pub distro: String,
    #[serde(default)]
    pub socket: String,
    #[serde(default)]
    pub native_shell: Option<String>,
    /// Use the system OpenSSH client instead of the built-in one.
    #[serde(default)]
    pub system_ssh: bool,
}
pub fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\"'\"'"))
}
fn checked(s: &str) -> Result<(), String> {
    if s.contains('\0') || s.contains('\n') || s.contains('\r') {
        Err("Invalid connection setting".into())
    } else {
        Ok(())
    }
}
fn tailscale_executable() -> std::path::PathBuf {
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
fn tailscale_target_is_windows(status: &Value, address: &str) -> bool {
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

const TAILSCALE_WINDOWS_TARGET: &str = "TAILSCALE_WINDOWS_TARGET: This Tailscale device runs Windows, which cannot serve Tailscale SSH. Connect to a WSL/Linux device with Tailscale SSH enabled, using its own tailnet name and Linux username. Ordinary SSH over Tailscale uses separate SSH credentials; DazeDev's remote host also needs Python 3 and tmux.";

// Status is an optional local hint. Bound the probe and keep normal SSH errors if it is unavailable.
fn tailscale_status() -> Option<Value> {
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
// Keep the host program out of argv: Windows limits the full command line,
// including the arguments added by `tailscale ssh`, to 32,767 UTF-16 units.
// The loader refuses Python older than 3.9 with a line the client can explain.
const HOST_LOADER: &str = "import sys;v=sys.version_info;v<(3,9) and (print('DAZED:'+sys.argv[1]+':python:%d.%d'%v[:2],flush=True),sys.exit(3));import base64;print();print('DAZED:'+sys.argv[1]+':bootstrap',flush=True);exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))";
/// The script that starts the host helper. It is sent as one line of input,
/// never as an argument, so it is always run by /bin/sh.
fn control_script(
    socket: &str,
    nonce: &str,
    endpoint: &str,
    desktop: &str,
    binary: bool,
) -> String {
    format!(
        "export DAZED_BROWSER_ENDPOINT={} DAZED_BROWSER_DESKTOP={}; export DAZED_TMUX_SOCKET={}; exec python3 -I -u -c {} {}{}",
        quote(endpoint),
        quote(desktop),
        quote(socket),
        quote(HOST_LOADER),
        quote(nonce),
        if binary { " 2" } else { "" }
    )
}
#[derive(Clone, Copy)]
enum Boot {
    /// Management channel without a terminal (built-in SSH).
    Pipe,
    /// Management channel inside a terminal (system OpenSSH).
    TerminalControl,
    /// Interactive terminal.
    Pane,
}
/// A fixed command for the remote login shell. SSH servers run commands
/// through the user's shell, which may be fish, nu or tcsh, so this only
/// starts /bin/sh with a quoted body free of backslashes and inner quotes.
/// That shell then reads the real POSIX script as its next input line.
/// With a terminal, it first turns echo off and prints a marker, so the
/// client sends the script only once it will not be echoed.
fn boot_command(boot: Boot, nonce: &str) -> String {
    match boot {
        Boot::Pipe => "exec /bin/sh -c 'IFS= read -r l; eval \"$l\"'".into(),
        Boot::TerminalControl => format!("exec /bin/sh -c 'stty -echo -icanon min 1 time 0 2>/dev/null; echo DZBOOT:{nonce}; IFS= read -r l; eval \"$l\"'"),
        Boot::Pane => format!("exec /bin/sh -c 'stty -echo -icanon min 1 time 0 2>/dev/null; echo DZBOOT:{nonce}; IFS= read -r l; stty echo icanon 2>/dev/null; eval \"$l\"'"),
    }
}
fn boot_marker(nonce: &str) -> String {
    format!("DZBOOT:{nonce}")
}
/// Input to send when the remote side prints each marker line, in order.
struct Bootstrap {
    stages: std::collections::VecDeque<(String, Vec<u8>)>,
}
impl Bootstrap {
    fn new(nonce: &str, source: &[u8]) -> Self {
        Self {
            stages: [(
                format!("DAZED:{nonce}:bootstrap"),
                (STANDARD.encode(source) + "\n").into_bytes(),
            )]
            .into(),
        }
    }
    /// First wait for the shell marker and send the script, then the source.
    fn after_script(mut self, nonce: &str, script: &str) -> Self {
        self.stages
            .push_front((boot_marker(nonce), format!("{script}\n").into_bytes()));
        self
    }
    fn is_done(&self) -> bool {
        self.stages.is_empty()
    }
    fn line(&mut self, line: &str) -> Option<Vec<u8>> {
        if self
            .stages
            .front()
            .is_some_and(|(marker, _)| line == marker)
        {
            self.stages.pop_front().map(|(_, payload)| payload)
        } else {
            None
        }
    }
}
/// Input side of a terminal or management channel.
trait Io: Send + Sync {
    /// Write within a bounded time; a stalled peer returns an error.
    fn write(&self, data: &[u8]) -> Result<(), String>;
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String>;
    fn kill(&self);
}
impl<T: Io> Io for Arc<T> {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        (**self).write(data)
    }
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        (**self).resize(cols, rows)
    }
    fn kill(&self) {
        (**self).kill()
    }
}
/// A local process in a pseudo-terminal (system OpenSSH, WSL or a local shell).
struct ProcessIo {
    writer: mpsc::SyncSender<(Vec<u8>, mpsc::Sender<Result<(), String>>)>,
    master: Mutex<Box<dyn MasterPty + Send>>,
    killer: Mutex<Box<dyn portable_pty::ChildKiller + Send + Sync>>,
}
impl Io for ProcessIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        let (tx, rx) = mpsc::channel();
        self.writer
            .try_send((data.to_vec(), tx))
            .map_err(|_| "Terminal input is unavailable".to_string())?;
        rx.recv_timeout(Duration::from_secs(5))
            .map_err(|_| "Terminal input timed out; reconnect this terminal".to_string())?
    }
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        lock(&self.master)
            .resize(PtySize {
                rows: rows.max(1),
                cols: cols.max(1),
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())
    }
    fn kill(&self) {
        let _ = lock(&self.killer).kill();
    }
}
/// A terminal channel on a built-in SSH connection.
struct SshPaneIo(crate::ssh::ChannelIo);
impl Io for SshPaneIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        self.0.write(data, Duration::from_secs(5))
    }
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        self.0.resize(cols, rows)
    }
    fn kill(&self) {
        self.0.close()
    }
}
/// The management channel of a built-in SSH connection. Input is refused
/// until authentication completes and the channel opens.
struct SshControlIo {
    prompts: Arc<crate::ssh::Prompts>,
    channel: OnceLock<crate::ssh::ChannelIo>,
    session: OnceLock<Arc<crate::ssh::Session>>,
    killed: AtomicBool,
}
impl Io for SshControlIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        match self.channel.get() {
            Some(channel) => channel.write(data, Duration::from_secs(5)),
            None => Err("Host is still connecting".into()),
        }
    }
    fn resize(&self, _cols: u16, _rows: u16) -> Result<(), String> {
        Ok(())
    }
    fn kill(&self) {
        self.killed.store(true, Ordering::Release);
        self.prompts.close();
        if let Some(channel) = self.channel.get() {
            channel.close();
        }
    }
}
/// Lock a mutex even if another thread panicked while holding it.
fn lock<T: ?Sized>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}
struct Pty {
    pending: AtomicUsize,
    active: AtomicBool,
    writable: Arc<AtomicBool>,
    io: Box<dyn Io>,
    exited: Mutex<Option<mpsc::Receiver<()>>>,
}
impl Pty {
    fn new(io: Box<dyn Io>, writable: Arc<AtomicBool>) -> Arc<Self> {
        Arc::new(Self {
            pending: AtomicUsize::new(0),
            active: AtomicBool::new(true),
            writable,
            io,
            exited: Mutex::new(None),
        })
    }
}
struct Connection {
    pty: Arc<Pty>,
    pending: Mutex<HashMap<String, mpsc::Sender<Result<Value, String>>>>,
    ready: Mutex<bool>,
    browser: crate::browser::Requests,
    /// Set for built-in SSH: authentication prompts are answered here.
    prompts: Option<Arc<crate::ssh::Prompts>>,
    /// Protocol 2 binary frames (built-in SSH); otherwise protocol 1 lines.
    binary: bool,
}
struct NativeView {
    client: crate::local_sessions::Client,
    session: String,
    stamp: Option<String>,
    viewer: String,
    active: AtomicBool,
    pending: AtomicUsize,
    /// One persistent input pipe, opened on first use.
    input: Mutex<Option<crate::local_sessions::InputChannel>>,
}
impl NativeView {
    fn request(&self, method: &str, mut args: Value) -> Result<Value, String> {
        args["id"] = json!(self.session);
        args["stamp"] = json!(self.stamp);
        args["viewer"] = json!(self.viewer);
        self.client.request(method, args)
    }
    fn with_input(
        &self,
        send: impl FnOnce(
            &mut crate::local_sessions::InputChannel,
        ) -> Result<(), crate::local_sessions::SessionError>,
    ) -> Result<(), String> {
        let mut input = lock(&self.input);
        if input.is_none() {
            *input = Some(
                self.client
                    .input_channel(&self.session, self.stamp.as_deref())
                    .map_err(String::from)?,
            );
        }
        let result = send(input.as_mut().expect("input channel")).map_err(String::from);
        if result.is_err() {
            // Reopen on the next call; never resend uncertain input.
            *input = None;
        }
        result
    }
}
pub struct Transport {
    terminals: Mutex<HashMap<String, Arc<Pty>>>,
    connections: Mutex<HashMap<String, Arc<Connection>>>,
    native_root: Mutex<Option<std::path::PathBuf>>,
    native_hosts: Mutex<HashMap<String, crate::local_sessions::Client>>,
    native_views: Mutex<HashMap<String, Arc<NativeView>>>,
    browser_desktop: String,
    browser_endpoints: Mutex<HashMap<String, String>>,
    /// Built-in SSH connections by host ID, with the settings they were made
    /// with. Terminals and reconnects reuse a live one instead of logging in again.
    sessions: Arc<Mutex<HashMap<String, (String, std::sync::Weak<crate::ssh::Session>)>>>,
}
impl Default for Transport {
    fn default() -> Self {
        Self {
            terminals: Mutex::new(HashMap::new()),
            connections: Mutex::new(HashMap::new()),
            native_root: Mutex::new(None),
            native_hosts: Mutex::new(HashMap::new()),
            native_views: Mutex::new(HashMap::new()),
            browser_desktop: uuid::Uuid::new_v4().simple().to_string(),
            browser_endpoints: Mutex::new(HashMap::new()),
            sessions: Arc::new(Mutex::new(HashMap::new())),
        }
    }
}
fn spawn(mut cmd: CommandBuilder) -> Result<(Arc<Pty>, Box<dyn Read + Send>), String> {
    cmd.env("TERM", "xterm-256color");
    cmd.env("COLORTERM", "truecolor");
    let pair = native_pty_system()
        .openpty(PtySize {
            rows: 30,
            cols: 120,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| e.to_string())?;
    let mut child = pair.slave.spawn_command(cmd).map_err(|e| e.to_string())?;
    drop(pair.slave);
    let reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
    let mut writer = pair.master.take_writer().map_err(|e| e.to_string())?;
    let (write_tx, write_rx) =
        mpsc::sync_channel::<(Vec<u8>, mpsc::Sender<Result<(), String>>)>(32);
    let writable = Arc::new(AtomicBool::new(true));
    let worker_writable = writable.clone();
    std::thread::spawn(move || {
        while let Ok((bytes, reply)) = write_rx.recv() {
            if !worker_writable.load(Ordering::Acquire) {
                let _ = reply.send(Err("Terminal is disconnected".into()));
                continue;
            }
            let result = writer
                .write_all(&bytes)
                .and_then(|_| writer.flush())
                .map_err(|e| e.to_string());
            if result.is_err() {
                worker_writable.store(false, Ordering::Release);
            }
            let _ = reply.send(result);
        }
    });
    let killer = child.clone_killer();
    let (exit_tx, exit_rx) = mpsc::channel();
    std::thread::spawn(move || {
        let _ = child.wait();
        // ConPTY can retain its read pipe after the child exits. Do not wait for EOF.
        let _ = exit_tx.send(());
    });
    let pty = Pty::new(
        Box::new(ProcessIo {
            writer: write_tx,
            master: Mutex::new(pair.master),
            killer: Mutex::new(killer),
        }),
        writable,
    );
    *lock(&pty.exited) = Some(exit_rx);
    Ok((pty, reader))
}
// The channel retains an early exit until the caller has installed its connection
// and callback. EOF and child completion share the same active flag below.
fn observe_exit(pty: &Pty, finished: impl FnOnce() + Send + 'static) {
    if let Some(exited) = pty.exited.lock().unwrap().take() {
        std::thread::spawn(move || {
            let _ = exited.recv();
            finished();
        });
    }
}
fn terminal_finished(pty: &Pty, id: &str, sink: &EventSink) {
    let notify = pty.active.swap(false, Ordering::AcqRel);
    pty.writable.store(false, Ordering::Release);
    if notify {
        sink("terminal-exit", json!({"id":id}));
    }
}
fn host_finished(conn: &Connection, id: &str, sink: &EventSink) {
    let notify = conn.pty.active.swap(false, Ordering::AcqRel);
    conn.pty.writable.store(false, Ordering::Release);
    // Serialize the ready/closed notifications too, so buffered output cannot
    // mark an exited host ready after its closed event has been delivered.
    let mut ready = conn.ready.lock().unwrap();
    *ready = false;
    for (_, tx) in conn.pending.lock().unwrap().drain() {
        let _ = tx.send(Err("Host disconnected".into()));
    }
    if notify {
        sink("host-closed", json!({"hostId":id}));
    }
}
fn host_ready(conn: &Connection, id: &str, identity: Value, sink: &EventSink) -> bool {
    let mut ready = conn.ready.lock().unwrap();
    if !conn.pty.active.load(Ordering::Acquire) {
        return false;
    }
    *ready = true;
    sink("host-ready", json!({"hostId":id,"identity":identity}));
    true
}
fn write(pty: &Pty, data: &[u8]) -> Result<(), String> {
    if !pty.active.load(Ordering::Acquire) || !pty.writable.load(Ordering::Acquire) {
        return Err("Terminal is disconnected".into());
    }
    let result = pty.io.write(data);
    if result.is_err() {
        pty.writable.store(false, Ordering::Release);
        pty.io.kill();
    }
    result
}
// The management PTY has no xterm renderer. Windows ConPTY asks its terminal
// for a cursor position before forwarding input; answer queries, never display
// them as authentication instructions. Keep partial escape sequences across reads.
#[derive(Default)]
struct ControlTerminal {
    escape: Vec<u8>,
}
impl ControlTerminal {
    fn feed(&mut self, bytes: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let mut text = Vec::new();
        let mut replies = Vec::new();
        for &b in bytes {
            if self.escape.is_empty() {
                if b == 27 {
                    self.escape.push(b);
                } else if b >= 32 || b == b'\n' || b == b'\r' || b == b'\t' {
                    text.push(b);
                }
                continue;
            }
            self.escape.push(b);
            let complete = match self.escape.get(1) {
                Some(b'[') => self.escape.len() > 2 && (0x40..=0x7e).contains(&b),
                Some(b']') => b == 7 || self.escape.ends_with(b"\x1b\\"),
                _ => true,
            };
            if complete {
                match self.escape.as_slice() {
                    b"\x1b[6n" => replies.extend_from_slice(b"\x1b[1;1R"),
                    b"\x1b[?6n" => replies.extend_from_slice(b"\x1b[?1;1R"),
                    b"\x1b[5n" => replies.extend_from_slice(b"\x1b[0n"),
                    b"\x1b[c" | b"\x1b[0c" => replies.extend_from_slice(b"\x1b[?1;2c"),
                    _ => {}
                }
                self.escape.clear();
            } else if self.escape.len() > 4096 {
                self.escape.clear();
            }
        }
        (text, replies)
    }
}
#[derive(Default)]
struct ReplyFrames {
    data: String,
    next: usize,
    total: usize,
}
impl ReplyFrames {
    fn line(&mut self, line: &str, prefix: &str) -> Result<Option<Value>, String> {
        let Some((_, frame)) = line.split_once(prefix) else {
            return Ok(None);
        };
        let Some(frame) = frame.strip_prefix('@') else {
            return Ok(None);
        };
        let (header, data) = frame.split_once(':').ok_or("Invalid host response frame")?;
        let (index, total) = header
            .split_once('/')
            .ok_or("Invalid host response sequence")?;
        let index = index
            .parse::<usize>()
            .map_err(|_| "Invalid host response index")?;
        let total = total
            .parse::<usize>()
            .map_err(|_| "Invalid host response length")?;
        if total == 0 || total > 350000 || data.len() > 48 {
            return Err("Host response exceeds limits".into());
        }
        if index == 0 && self.next == 0 {
            self.total = total;
            self.data.clear();
        }
        if index != self.next || total != self.total {
            return Err("Host response was interrupted. Reconnect before retrying.".into());
        }
        self.data.push_str(data);
        self.next += 1;
        if self.next != total {
            return Ok(None);
        }
        self.next = 0;
        let decoded = STANDARD
            .decode(&self.data)
            .map_err(|_| "Invalid host response encoding")?;
        self.data.clear();
        serde_json::from_slice(&decoded)
            .map(Some)
            .map_err(|e| format!("Invalid host response: {e}"))
    }
}
/// Look for the boot marker in held pane output. When found, return the
/// output with the marker line removed and clear the buffer.
fn pane_boot(held: &mut Vec<u8>, marker: &[u8]) -> Option<Vec<u8>> {
    let start = held.windows(marker.len()).position(|w| w == marker)?;
    let mut end = start + marker.len();
    while end < held.len() && matches!(held[end], b'\r' | b'\n') {
        end += 1;
    }
    let mut output = held[..start].to_vec();
    output.extend_from_slice(&held[end..]);
    held.clear();
    Some(output)
}
/// Host helper protocol on clean channels (see shared/protocol/host-v2.json).
const HOST_PROTOCOL: u64 = 2;
const MAX_HOST_MESSAGE: usize = 64 * 1024 * 1024;
/// Reassembles protocol 2 frames: u32 length, u8 flags (1 = last part),
/// u16 id length, id, data. Parts of different messages may interleave.
#[derive(Default)]
struct MessageFrames {
    buffer: Vec<u8>,
    parts: HashMap<Vec<u8>, Vec<u8>>,
}
impl MessageFrames {
    fn push(&mut self, bytes: &[u8]) -> Result<Vec<Value>, String> {
        self.buffer.extend_from_slice(bytes);
        let mut messages = Vec::new();
        let mut start = 0;
        while self.buffer.len() - start >= 4 {
            let size = u32::from_be_bytes([
                self.buffer[start],
                self.buffer[start + 1],
                self.buffer[start + 2],
                self.buffer[start + 3],
            ]) as usize;
            if !(3..=MAX_HOST_MESSAGE + 1024).contains(&size) {
                return Err("Invalid host message frame".into());
            }
            if self.buffer.len() - start - 4 < size {
                break;
            }
            let body = &self.buffer[start + 4..start + 4 + size];
            let id_length = u16::from_be_bytes([body[1], body[2]]) as usize;
            if 3 + id_length > body.len() {
                return Err("Invalid host message frame".into());
            }
            let id = body[3..3 + id_length].to_vec();
            let part = self.parts.entry(id.clone()).or_default();
            part.extend_from_slice(&body[3 + id_length..]);
            if part.len() > MAX_HOST_MESSAGE {
                return Err("Host message exceeds limits".into());
            }
            if body[0] & 1 == 1 {
                let data = self.parts.remove(&id).unwrap_or_default();
                messages.push(
                    serde_json::from_slice(&data)
                        .map_err(|e| format!("Invalid host message: {e}"))?,
                );
            }
            start += 4 + size;
        }
        self.buffer.drain(..start);
        Ok(messages)
    }
}
fn request_frame(request: &Value) -> Result<Vec<u8>, String> {
    let body = serde_json::to_vec(request).map_err(|e| e.to_string())?;
    let mut frame = (body.len() as u32).to_be_bytes().to_vec();
    frame.extend(body);
    Ok(frame)
}
/// Error text for the frontend. Protocol 2 sends `{code, message}`; a
/// conflict keeps the `CONFLICT:` prefix the frontend looks for.
fn host_error(error: &Value) -> Option<String> {
    match error {
        Value::String(text) => Some(text.clone()),
        Value::Object(fields) => {
            let message = fields
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("Host error");
            Some(match fields.get("code").and_then(Value::as_str) {
                Some("conflict") => format!("CONFLICT: {message}"),
                _ => message.to_owned(),
            })
        }
        _ => None,
    }
}
/// Act on one decoded host message. Returns false to stop reading.
fn host_message(conn: &Connection, host_id: &str, nonce: &str, v: Value, sink: &EventSink) -> bool {
    let id = v["id"].as_str().unwrap_or("");
    if v["event"] == "browser-open" {
        if conn.pty.active.load(Ordering::Acquire) && *lock(&conn.ready) {
            sink(
                "host-browser-open",
                json!({"hostId":host_id,"nonce":nonce,"id":id,"url":v["url"]}),
            );
        }
    } else if id == "ready" {
        if conn.binary && v["result"]["protocol"].as_u64() != Some(HOST_PROTOCOL) {
            sink(
                "host-output",
                json!({"hostId":host_id,"text":"The host helper does not match this version of DazeDev.\n"}),
            );
            return false;
        }
        return host_ready(conn, host_id, v["result"].clone(), sink);
    } else if let Some(tx) = lock(&conn.pending).remove(id) {
        let _ = tx.send(match host_error(&v["error"]) {
            Some(e) => Err(e),
            None => Ok(v["result"].clone()),
        });
    }
    true
}
/// Decode protocol 2 bytes and handle the messages. Returns false to stop.
fn host_frames(
    decoder: &mut MessageFrames,
    bytes: &[u8],
    conn: &Connection,
    host_id: &str,
    nonce: &str,
    sink: &EventSink,
) -> bool {
    match decoder.push(bytes) {
        Ok(list) => list
            .into_iter()
            .all(|v| host_message(conn, host_id, nonce, v, sink)),
        Err(e) => {
            sink("host-output", json!({"hostId":host_id,"text":e}));
            false
        }
    }
}
/// Read the management channel: answer bootstrap markers, then decode
/// replies and events until the channel ends. Before the helper starts the
/// channel carries text lines; protocol 2 then switches to binary frames.
fn run_control(
    conn: Arc<Connection>,
    host_id: String,
    nonce: String,
    mut reader: Box<dyn Read + Send>,
    mut bootstrap: Bootstrap,
    sink: EventSink,
) {
    let prefix = format!("DAZED:{nonce}:");
    let python = format!("{prefix}python:");
    let boot = boot_marker(&nonce);
    let mut buffer = Vec::new();
    let mut terminal = ControlTerminal::default();
    let mut frames = ReplyFrames::default();
    let mut messages: Option<MessageFrames> = None;
    let mut chunk = vec![0u8; 65536];
    'read: loop {
        let n = match reader.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        if !conn.pty.active.load(Ordering::Acquire) {
            break;
        }
        if let Some(decoder) = messages.as_mut() {
            if !host_frames(decoder, &chunk[..n], &conn, &host_id, &nonce, &sink) {
                break;
            }
            continue;
        }
        // A terminal (system OpenSSH, WSL, local) needs escape handling;
        // a built-in SSH channel carries the bytes unchanged.
        let clean = if conn.binary {
            chunk[..n].to_vec()
        } else {
            let (clean, replies) = terminal.feed(&chunk[..n]);
            if !replies.is_empty() {
                let _ = write(&conn.pty, &replies);
            }
            clean
        };
        let text = String::from_utf8_lossy(&clean);
        if !*lock(&conn.ready) && !text.contains(&prefix) && !text.contains(&boot) {
            sink("host-output", json!({"hostId":host_id,"text":text}));
        }
        buffer.extend_from_slice(&clean);
        let mut start = 0;
        while let Some(offset) = buffer[start..].iter().position(|b| *b == b'\n') {
            let end = start + offset;
            let line = String::from_utf8_lossy(&buffer[start..end])
                .trim()
                .to_string();
            start = end + 1;
            // Wait for the remote loader, never send source into an SSH
            // password/host-key prompt. Consume each marker only once.
            if let Some(payload) = bootstrap.line(&line) {
                if let Err(e) = write(&conn.pty, &payload) {
                    sink("host-output", json!({"hostId":host_id,"text":e}));
                    break 'read;
                }
                if conn.binary && bootstrap.is_done() {
                    // From here the helper sends binary frames only.
                    let mut decoder = MessageFrames::default();
                    let rest = buffer[start..].to_vec();
                    buffer.clear();
                    if !host_frames(&mut decoder, &rest, &conn, &host_id, &nonce, &sink) {
                        break 'read;
                    }
                    messages = Some(decoder);
                    continue 'read;
                }
                continue;
            }
            if let Some(version) = line.strip_prefix(&python) {
                sink(
                    "host-output",
                    json!({"hostId":host_id,"text":format!("DazeDev needs Python 3.9 or newer on this host (found Python {version}).\n")}),
                );
                continue;
            }
            match frames.line(&line, &prefix) {
                Ok(Some(v)) => {
                    if !host_message(&conn, &host_id, &nonce, v, &sink) {
                        break 'read;
                    }
                }
                Ok(None) => {}
                Err(e) => {
                    sink("host-output", json!({"hostId":host_id,"text":e}));
                    break 'read;
                }
            }
        }
        buffer.drain(..start);
        if buffer.len() > 1_000_000 {
            buffer.clear();
        }
    }
    host_finished(&conn, &host_id, &sink);
    conn.pty.io.kill();
}
impl Transport {
    pub fn set_native_root(&self, path: std::path::PathBuf) {
        *self.native_root.lock().unwrap() = Some(path);
    }
    fn native_client(&self, id: &str) -> Option<crate::local_sessions::Client> {
        self.native_hosts.lock().unwrap().get(id).cloned()
    }
    pub fn native_close(&self, host: &str, ids: &[String], action: &str) -> Result<(), String> {
        let client = self
            .native_client(host)
            .ok_or("Native host is disconnected; reconnect before closing sessions")?;
        // Complete every external attachment before any view is removed.
        for id in ids {
            match action {
                "external" => client.external(id)?,
                "background" => {
                    client.request("keep", json!({"id":id}))?;
                }
                "end" => {
                    // An existing broker can predate idempotent destroy.
                    // Ignore only its exact missing-session response, then
                    // continue ending the remaining explicitly chosen IDs.
                    match client.request("destroy", json!({"id":id})) {
                        Ok(_) => {}
                        Err(error) if error == crate::local_sessions::SESSION_UNAVAILABLE => {}
                        Err(error) => return Err(error),
                    }
                }
                _ => return Err("Choose end, background or external".into()),
            }
        }
        Ok(())
    }
    pub fn shutdown(&self) {
        let views: Vec<_> = lock(&self.native_views).keys().cloned().collect();
        for id in views {
            self.close(&id);
        }
        for (_, p) in lock(&self.terminals).drain() {
            p.active.store(false, Ordering::Release);
            p.io.kill();
        }
        for (_, c) in lock(&self.connections).drain() {
            c.pty.active.store(false, Ordering::Release);
            c.pty.io.kill();
        }
        for (_, (_, session)) in lock(&self.sessions).drain() {
            if let Some(session) = session.upgrade() {
                session.close();
            }
        }
    }
    /// A live built-in SSH connection for this host, if one exists.
    fn live_session(
        &self,
        host_id: &str,
        settings: Option<&str>,
    ) -> Option<Arc<crate::ssh::Session>> {
        let sessions = lock(&self.sessions);
        let (stored, session) = sessions.get(host_id)?;
        if settings.is_some_and(|s| s != stored) {
            return None;
        }
        session.upgrade().filter(|s| !s.is_closed())
    }
    /// Decide how to reach a host. `None` means use a local process
    /// (system OpenSSH, `tailscale ssh`, WSL or a local shell).
    fn ssh_target(
        &self,
        host: &Host,
        tailscale: Option<&Value>,
        sink: &EventSink,
    ) -> Result<Option<(crate::ssh::Target, String)>, String> {
        for s in [
            &host.address,
            &host.user,
            &host.key,
            &host.jump,
            &host.socket,
        ] {
            checked(s)?;
        }
        if host.system_ssh || std::env::var_os("DAZEDEV_SYSTEM_SSH").is_some() {
            return Ok(None);
        }
        match host.kind.as_str() {
            "ssh" => {
                if host.address.is_empty() || host.address.starts_with('-') {
                    return Err("Enter an SSH alias or hostname".into());
                }
                let nonempty = |s: &str| (!s.is_empty()).then(|| s.to_owned());
                let request = crate::ssh::config::Request {
                    host: host.address.clone(),
                    user: nonempty(&host.user),
                    port: host.port,
                    identity: nonempty(&host.key),
                    jump: nonempty(&host.jump),
                };
                let resolved = crate::ssh::config::resolve(&request);
                if let Some(reason) = &resolved.unsupported {
                    sink(
                        "host-output",
                        json!({"hostId":host.id,"text":format!("Using the system OpenSSH client: {reason}.\n")}),
                    );
                    return Ok(None);
                }
                let settings = format!(
                    "ssh|{}|{}|{}|{}|{}|{}",
                    resolved.user,
                    resolved.hostname,
                    resolved.port,
                    resolved.proxy_jump.join(","),
                    resolved.proxy_command.as_deref().unwrap_or(""),
                    host.key
                );
                Ok(Some((crate::ssh::Target::Ssh(resolved), settings)))
            }
            "tailscale" => {
                let Some((ip, keys)) =
                    tailscale.and_then(|status| crate::ssh::tailscale_peer(status, &host.address))
                else {
                    return Ok(None);
                };
                let settings = format!("tailscale|{}|{ip}", host.user);
                Ok(Some((
                    crate::ssh::Target::Tailscale {
                        address: host.address.clone(),
                        ip,
                        user: host.user.clone(),
                        keys,
                    },
                    settings,
                )))
            }
            _ => Ok(None),
        }
    }
    pub fn connect(&self, host: Host, sink: EventSink) -> Result<(), String> {
        self.disconnect(&host.id);
        if host.kind == "powershell" {
            if !cfg!(windows) {
                return Err("Native PowerShell hosts require Windows".into());
            }
            let root = lock(&self.native_root)
                .clone()
                .ok_or("Native session folder unavailable")?;
            let mut client = crate::local_sessions::Client::ensure(root, host.id.clone())?;
            client.shell = host.native_shell.unwrap_or_else(|| "powershell".into());
            let identity = client.request("ping", json!({}))?;
            lock(&self.native_hosts).insert(host.id.clone(), client);
            sink("host-ready", json!({"hostId":host.id,"identity":identity}));
            return Ok(());
        }
        let tailscale = if host.kind == "tailscale" {
            tailscale_status()
        } else {
            None
        };
        if tailscale
            .as_ref()
            .is_some_and(|status| tailscale_target_is_windows(status, &host.address))
        {
            return Err(TAILSCALE_WINDOWS_TARGET.into());
        }
        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let endpoint = lock(&self.browser_endpoints)
            .entry(host.id.clone())
            .or_insert_with(|| uuid::Uuid::new_v4().simple().to_string())
            .clone();
        let script = |binary| {
            control_script(
                &host.socket,
                &nonce,
                &endpoint,
                &self.browser_desktop,
                binary,
            )
        };
        let source = format!(
            "import base64\nBROWSER_SOURCE=base64.b64decode('{}').decode()\n{}",
            STANDARD.encode(include_bytes!("../resources/browser.py")),
            include_str!("../resources/host.py")
        );
        let bootstrap = Bootstrap::new(&nonce, source.as_bytes());
        if let Some((target, settings)) = self.ssh_target(&host, tailscale.as_ref(), &sink)? {
            let script = script(true);
            return self.connect_ssh(host, target, settings, nonce, script, bootstrap, sink);
        }
        let script = script(false);
        let process_command = if matches!(host.kind.as_str(), "ssh" | "tailscale") {
            boot_command(Boot::TerminalControl, &nonce)
        } else {
            // Raw, silent input: the source line is far longer than a
            // canonical-mode terminal line and must not be echoed back.
            format!("stty -echo -icanon min 1 time 0 && {script}")
        };
        let bootstrap = if matches!(host.kind.as_str(), "ssh" | "tailscale") {
            bootstrap.after_script(&nonce, &script)
        } else {
            bootstrap
        };
        let (pty, reader) = spawn(command(&host, &process_command)?)?;
        let conn = Arc::new(Connection {
            pty,
            pending: Mutex::new(HashMap::new()),
            ready: Mutex::new(false),
            browser: crate::browser::Requests::new(nonce.clone()),
            prompts: None,
            binary: false,
        });
        self.register(&host.id, conn.clone());
        let exited_conn = conn.clone();
        let exited_sink = sink.clone();
        let exited_id = host.id.clone();
        observe_exit(&conn.pty, move || {
            host_finished(&exited_conn, &exited_id, &exited_sink)
        });
        std::thread::spawn(move || run_control(conn, host.id, nonce, reader, bootstrap, sink));
        Ok(())
    }
    fn register(&self, host_id: &str, conn: Arc<Connection>) {
        // A racing second connect must not leave the first one running.
        if let Some(old) = lock(&self.connections).insert(host_id.to_owned(), conn) {
            old.pty.active.store(false, Ordering::Release);
            old.pty.io.kill();
        }
    }
    #[allow(clippy::too_many_arguments)]
    fn connect_ssh(
        &self,
        host: Host,
        target: crate::ssh::Target,
        settings: String,
        nonce: String,
        script: String,
        bootstrap: Bootstrap,
        sink: EventSink,
    ) -> Result<(), String> {
        let prompts = {
            let sink = sink.clone();
            let id = host.id.clone();
            crate::ssh::Prompts::new(move |text| {
                sink("host-output", json!({"hostId":id,"text":text}));
            })
        };
        let control = Arc::new(SshControlIo {
            prompts: prompts.clone(),
            channel: OnceLock::new(),
            session: OnceLock::new(),
            killed: AtomicBool::new(false),
        });
        let conn = Arc::new(Connection {
            pty: Pty::new(Box::new(control.clone()), Arc::new(AtomicBool::new(true))),
            pending: Mutex::new(HashMap::new()),
            ready: Mutex::new(false),
            browser: crate::browser::Requests::new(nonce.clone()),
            prompts: Some(prompts.clone()),
            binary: true,
        });
        self.register(&host.id, conn.clone());
        let reuse = self.live_session(&host.id, Some(&settings));
        let sessions = self.sessions.clone();
        std::thread::spawn(move || {
            let fail = |text: String| {
                sink("host-output", json!({"hostId":host.id,"text":text}));
                host_finished(&conn, &host.id, &sink);
            };
            let open = |session: &Arc<crate::ssh::Session>| {
                session.open(boot_command(Boot::Pipe, &nonce), None)
            };
            let mut opened = None;
            if let Some(session) = reuse {
                match open(&session) {
                    Ok(channel) => opened = Some((session, channel)),
                    Err(_) => session.close(),
                }
            }
            if opened.is_none() {
                let connected = crate::ssh::runtime()
                    .block_on(crate::ssh::Session::connect(target, prompts.clone()));
                match connected {
                    Ok(session) => {
                        let session = Arc::new(session);
                        lock(&sessions)
                            .insert(host.id.clone(), (settings, Arc::downgrade(&session)));
                        match open(&session) {
                            Ok(channel) => opened = Some((session, channel)),
                            Err(e) => return fail(format!("{e}\n")),
                        }
                    }
                    Err(e) => {
                        let e = e.strip_prefix(crate::ssh::SYSTEM_SSH).map_or(e.clone(), |reason| {
                            format!("This connection needs the system OpenSSH client ({reason}). Turn on \"Use system OpenSSH\" for this host.\n")
                        });
                        return fail(e);
                    }
                }
            }
            let Some((session, (channel, reader))) = opened else {
                return;
            };
            if control.killed.load(Ordering::Acquire) {
                channel.close();
                return;
            }
            let _ = control.session.set(session);
            let _ = control.channel.set(channel);
            if let Err(e) = write(&conn.pty, format!("{script}\n").as_bytes()) {
                return fail(format!("{e}\n"));
            }
            run_control(conn, host.id, nonce, Box::new(reader), bootstrap, sink);
        });
        Ok(())
    }
    pub fn reserve_browser(&self, host_id: &str, nonce: &str, id: &str) -> Result<(), String> {
        let conn = self
            .connections
            .lock()
            .unwrap()
            .get(host_id)
            .cloned()
            .ok_or("Browser connection has expired")?;
        if !conn.pty.active.load(Ordering::Acquire) || !*conn.ready.lock().unwrap() {
            return Err("Browser connection has expired".into());
        }
        conn.browser.reserve(nonce, id)
    }
    pub fn claim_browser(
        &self,
        host_id: &str,
        nonce: &str,
        id: &str,
        url: &str,
    ) -> Result<String, String> {
        self.reserve_browser(host_id, nonce, id)?;
        crate::browser::validate_url(url)
    }
    pub fn browser_current(&self, host_id: &str, nonce: &str) -> bool {
        self.connections
            .lock()
            .unwrap()
            .get(host_id)
            .map(|c| {
                c.browser.nonce == nonce
                    && c.pty.active.load(Ordering::Acquire)
                    && *c.ready.lock().unwrap()
            })
            .unwrap_or(false)
    }
    pub fn browser_result(&self, host_id: &str, nonce: &str, id: &str, error: Option<String>) {
        let current = self
            .connections
            .lock()
            .unwrap()
            .get(host_id)
            .map(|c| c.browser.nonce == nonce && c.pty.active.load(Ordering::Acquire))
            .unwrap_or(false);
        if current {
            let _ = self.request(
                host_id,
                "browser_result",
                json!({"id":id,"error":error}),
                false,
            );
        }
    }
    pub fn health(&self, id: &str) -> Result<(), String> {
        self.request(id, "ping", json!({}), true).map(|_| ())
    }
    pub fn rpc(&self, id: &str, method: &str, args: Value) -> Result<Value, String> {
        self.request(id, method, args, false)
    }
    fn request(
        &self,
        id: &str,
        method: &str,
        args: Value,
        idle_only: bool,
    ) -> Result<Value, String> {
        if let Some(client) = self.native_client(id) {
            if method == "native_close" {
                let ids: Vec<String> = args["ids"]
                    .as_array()
                    .ok_or("Choose sessions")?
                    .iter()
                    .map(|v| v.as_str().map(str::to_owned).ok_or("Invalid session"))
                    .collect::<Result<_, _>>()?;
                self.native_close(id, &ids, args["action"].as_str().unwrap_or(""))?;
                return Ok(Value::Null);
            }
            return client.request(method, args);
        }
        let c = self
            .connections
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Host is not connected")?;
        if !*c.ready.lock().unwrap() {
            return Err("Complete authentication first".into());
        }
        let request = uuid::Uuid::new_v4().to_string();
        let (tx, rx) = mpsc::channel();
        let _ = idle_only; // Host control requests have an independent lane.
                           // Register before writing so a fast reply is never lost. The lock is not
                           // held during the write: each write is queued whole, so requests never
                           // interleave, and the reader can deliver other replies meanwhile.
        lock(&c.pending).insert(request.clone(), tx);
        let message = json!({"id":request,"method":method,"args":args});
        let payload = if c.binary {
            request_frame(&message)?
        } else {
            (STANDARD.encode(serde_json::to_vec(&message).map_err(|e| e.to_string())?) + "\n")
                .into_bytes()
        };
        if let Err(e) = write(&c.pty, &payload) {
            lock(&c.pending).remove(&request);
            return Err(e);
        }
        let r = rx
            .recv_timeout(Duration::from_secs(
                if matches!(
                    method,
                    "ping" | "sessions" | "browser_prepare" | "browser_result"
                ) {
                    15
                } else {
                    360
                },
            ))
            .map_err(|_| "Host operation timed out; refresh before retrying".to_string());
        lock(&c.pending).remove(&request);
        if r.is_err() && method == "ping" {
            *lock(&c.ready) = false;
            c.pty.io.kill();
        }
        r?
    }
    pub fn authenticate(&self, id: &str, data: &str) -> Result<(), String> {
        let c = self
            .connections
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Not connected")?;
        if *lock(&c.ready) {
            return Err("Already authenticated".into());
        }
        if let Some(prompts) = &c.prompts {
            return if prompts.answer(data.trim_end_matches(['\r', '\n'])) {
                Ok(())
            } else {
                Err("Nothing is waiting for an answer".into())
            };
        }
        // A terminal Enter key is CR. LF alone does not submit Windows console input.
        let response = format!("{}\r", data.trim_end_matches(['\r', '\n']));
        write(&c.pty, response.as_bytes())
    }
    pub fn disconnect(&self, id: &str) {
        self.native_hosts.lock().unwrap().remove(id);
        let removed = self.connections.lock().unwrap().remove(id);
        if let Some(c) = removed {
            c.pty.active.store(false, Ordering::Release);
            *c.ready.lock().unwrap() = false;
            for (_, tx) in lock(&c.pending).drain() {
                let _ = tx.send(Err("Host disconnected".into()));
            }
            c.pty.io.kill();
        }
    }
    pub fn open(
        &self,
        id: String,
        host: Host,
        session: String,
        stamp: Option<String>,
        sink: EventSink,
    ) -> Result<(), String> {
        self.open_flow(id, host, session, stamp, sink, false)
    }
    pub fn open_flow(
        &self,
        id: String,
        host: Host,
        session: String,
        stamp: Option<String>,
        sink: EventSink,
        flow: bool,
    ) -> Result<(), String> {
        self.open_marked(id, host, session, stamp, sink, flow, None)
    }
    pub fn open_marked(
        &self,
        id: String,
        host: Host,
        session: String,
        stamp: Option<String>,
        sink: EventSink,
        flow: bool,
        ready_marker: Option<String>,
    ) -> Result<(), String> {
        self.open_marked_directory(id, host, session, stamp, sink, flow, ready_marker, None)
    }
    #[allow(clippy::too_many_arguments)]
    pub fn open_marked_directory(
        &self,
        id: String,
        host: Host,
        session: String,
        stamp: Option<String>,
        sink: EventSink,
        flow: bool,
        ready_marker: Option<String>,
        directory: Option<String>,
    ) -> Result<(), String> {
        let output = event_output(sink.clone(), id.clone());
        self.open_terminal(
            id,
            host,
            session,
            stamp,
            sink,
            output,
            flow,
            ready_marker,
            directory,
        )
    }
    /// Open a terminal. Output goes to `output`; `terminal-exit` goes to `sink`.
    #[allow(clippy::too_many_arguments)]
    pub fn open_terminal(
        &self,
        id: String,
        host: Host,
        session: String,
        stamp: Option<String>,
        sink: EventSink,
        output: OutputSink,
        flow: bool,
        ready_marker: Option<String>,
        directory: Option<String>,
    ) -> Result<(), String> {
        if host.kind == "powershell" {
            self.close(&id);
            let client = self
                .native_client(&host.id)
                .ok_or("Connect the native Windows host first")?;
            if session.is_empty() {
                return Err("Create a native PowerShell session before attaching".into());
            }
            let descriptor = client.request("describe", json!({"id":session,"stamp":stamp}))?;
            if descriptor["running"] != true {
                return Err("PowerShell session has ended; create a new session explicitly".into());
            }
            let view = Arc::new(NativeView {
                client,
                session,
                stamp,
                viewer: uuid::Uuid::new_v4().to_string(),
                active: AtomicBool::new(true),
                pending: AtomicUsize::new(0),
                input: Mutex::new(None),
            });
            // The broker pushes output over one pipe; reading blocks until
            // output, a heartbeat or the end. Read the first update before
            // registering so a failed attach never leaves a live view behind.
            let mut stream = view
                .client
                .view_stream(&view.session, &view.viewer, view.stamp.as_deref(), None)
                .map_err(String::from)?;
            let first = stream.next().map_err(String::from)?;
            if let Some(old) = lock(&self.native_views).insert(id.clone(), view.clone()) {
                old.active.store(false, Ordering::Release);
                let _ = old.request("view_detach", json!({}));
            }
            let marker = ready_marker
                .map(|m| format!("\x1b]777;dazedev-ready:{m}:{}\x07", descriptor["pid"]))
                .unwrap_or_default();
            std::thread::spawn(move || {
                let mut next = Some(Ok(first));
                let mut first = true;
                while view.active.load(Ordering::Acquire) {
                    // Stop reading while the renderer is behind; the broker
                    // keeps the output until the pipe drains.
                    while flow
                        && view.pending.load(Ordering::Acquire) >= 262144
                        && view.active.load(Ordering::Acquire)
                    {
                        std::thread::sleep(Duration::from_millis(4));
                    }
                    if !view.active.load(Ordering::Acquire) {
                        break;
                    }
                    match next.take().unwrap_or_else(|| stream.next()) {
                        Ok(update) => {
                            let mut data = if update.reset {
                                b"\x1b[2J\x1b[H".to_vec()
                            } else {
                                Vec::new()
                            };
                            data.extend(update.data);
                            if first {
                                data.extend(marker.as_bytes());
                                first = false;
                            }
                            if !data.is_empty() && view.active.load(Ordering::Acquire) {
                                if flow {
                                    view.pending.fetch_add(data.len(), Ordering::AcqRel);
                                }
                                output(&data);
                            }
                            if !update.running {
                                sink("terminal-exit", json!({"id":id}));
                                break;
                            }
                            if update.detached {
                                break;
                            }
                        }
                        Err(error) => {
                            if view.active.load(Ordering::Acquire) {
                                if error.kind == crate::local_sessions::SessionErrorKind::Ended {
                                    sink("terminal-exit", json!({"id":id}));
                                } else {
                                    sink("terminal-exit", json!({"id":id,"error":error.message}));
                                }
                            }
                            break;
                        }
                    }
                }
                view.active.store(false, Ordering::Release);
                let _ = view.request("view_detach", json!({}));
            });
            return Ok(());
        }
        let directory = directory.as_deref().filter(|d| !d.is_empty());
        if let Some(path) = directory {
            checked(path)?;
        }
        if !session.is_empty()
            && (!session.starts_with('$')
                || !session[1..].chars().all(|c| c.is_ascii_digit())
                || session.len() < 2)
        {
            return Err("Invalid tmux session ID".into());
        }
        self.close(&id);
        let socket = if host.socket.is_empty() {
            String::new()
        } else {
            format!("-L {} ", quote(&host.socket))
        };
        let guarded = stamp.is_some();
        let guard = if let Some(stamp) = stamp {
            checked(&stamp)?;
            format!("if [ \"$(tmux {socket}display-message -p -t {} '#{{pid}}:#{{start_time}}:#{{session_created}}')\" = {} ]; then ", quote(&session), quote(&stamp))
        } else {
            String::new()
        };
        // Keep the PTY useful after Ctrl-D exits its login shell. Bound rapid
        // failures so a broken shell/profile cannot create an infinite respawn loop.
        // The marker carries this terminal's nonce, so program output in an
        // ordinary shell cannot pretend the login shell restarted.
        let shell_marker = match ready_marker.as_deref() {
            Some(marker)
                if marker
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-') =>
            {
                format!("dazedev-shell:{marker}")
            }
            Some(_) => return Err("Invalid terminal marker".into()),
            None => "dazedev-shell".to_owned(),
        };
        let shell = r#"dazedev_fast=0; while :; do printf '\033]777;dazedev-shell\007'; dazedev_start=$(date +%s); "${SHELL:-/bin/sh}" -l; dazedev_elapsed=$(($(date +%s)-dazedev_start)); if [ "$dazedev_elapsed" -lt 2 ]; then dazedev_fast=$((dazedev_fast+1)); else dazedev_fast=0; fi; if [ "$dazedev_fast" -ge 3 ]; then printf '%s\n' 'Shell exited repeatedly. Check your login shell configuration, then reconnect.'; exit 1; fi; done"#;
        let shell = shell.replace("dazedev-shell", &shell_marker);
        let script = if session.is_empty() {
            let cwd = directory.map(quote).unwrap_or_else(|| "~".into());
            format!("cd -- {cwd} || cd ~; {shell}")
        } else {
            // Repair alpha-created windows only; preserve unrelated tmux policies.
            // Revive a previously retained dead pane without killing live work.
            let target = quote(&session);
            let repair = format!("if [ -n \"$(tmux {socket}show-options -qv -t {target} @dazed-operation)\" ]; then if [ \"$(tmux {socket}display-message -p -t {target} '#{{pane_dead}}')\" = 1 ]; then tmux {socket}respawn-pane -t {target} '\"${{SHELL:-/bin/sh}}\" -l'; fi; tmux {socket}set-window-option -t {target} remain-on-exit off; fi; ");
            let fallback = if guarded {
                "else printf '%s\\n' 'Saved tmux session is unavailable. Returned to the host shell.'; fi; "
            } else {
                ""
            };
            // xterm accepts UTF-8 regardless of the SSH host's inherited locale.
            // Without -u, a C-locale tmux client replaces Unicode with underscores.
            format!("{guard}{repair}tmux -u {socket}attach-session -t {target}; {fallback}{shell}")
        };
        let script = match self.rpc(&host.id, "browser_prepare", json!({})) {
            Ok(browser) => {
                let executable = browser["command"]
                    .as_str()
                    .ok_or("Invalid browser bridge command")?;
                let endpoint = browser["endpoint"]
                    .as_str()
                    .ok_or("Invalid browser bridge endpoint")?;
                checked(executable)?;
                uuid::Uuid::parse_str(endpoint).map_err(|_| "Invalid browser bridge endpoint")?;
                let folder = std::path::Path::new(executable)
                    .parent()
                    .ok_or("Invalid browser bridge directory")?
                    .to_string_lossy();
                format!("export BROWSER={}; export PATH={}:\"$PATH\"; export DAZEDEV_BROWSER_TTY=\"$(tty)\" DAZEDEV_BROWSER_PID=\"$$\"; \"$BROWSER\" --dazedev-register {} \"$$\" \"$DAZEDEV_BROWSER_TTY\"; {script}", quote(executable), quote(&folder), quote(endpoint))
            }
            Err(error) if error.starts_with("Desktop browser bridge unavailable:") => {
                format!("printf '%s\\n' {} >&2; {script}", quote(&error))
            }
            // An independently healthy terminal may outlive its management channel.
            Err(_) => script,
        };
        let script = if let Some(marker) = ready_marker {
            checked(&marker)?;
            format!(
                "printf '\\033]777;dazedev-ready:%s:%s\\007' {} \"$$\"; {script}",
                quote(&marker)
            )
        } else {
            script
        };
        let remote = matches!(host.kind.as_str(), "ssh" | "tailscale");
        let pane_nonce = uuid::Uuid::new_v4().simple().to_string();
        let session = if remote && !host.system_ssh {
            self.live_session(&host.id, None)
        } else {
            None
        };
        let (pty, mut reader): (Arc<Pty>, Box<dyn Read + Send>) = match session {
            Some(session) => {
                let (channel, reader) =
                    session.open(boot_command(Boot::Pane, &pane_nonce), Some((120, 30)))?;
                (
                    Pty::new(
                        Box::new(SshPaneIo(channel)),
                        Arc::new(AtomicBool::new(true)),
                    ),
                    Box::new(reader),
                )
            }
            None if remote => spawn(command(&host, &boot_command(Boot::Pane, &pane_nonce))?)?,
            None => spawn(command(&host, &script)?)?,
        };
        // Remote panes print a marker once echo is off; then the script is sent.
        let mut boot = remote.then(|| (boot_marker(&pane_nonce), format!("{script}\n")));
        if let Some(old) = lock(&self.terminals).insert(id.clone(), pty.clone()) {
            old.active.store(false, Ordering::Release);
            old.io.kill();
        }
        let exited_pty = pty.clone();
        let exited_sink = sink.clone();
        let exited_id = id.clone();
        observe_exit(&pty, move || {
            terminal_finished(&exited_pty, &exited_id, &exited_sink)
        });
        std::thread::spawn(move || {
            // Large reads join bursts into fewer, bigger messages.
            let mut chunk = vec![0u8; 65536];
            let mut held: Vec<u8> = Vec::new();
            loop {
                let n = match reader.read(&mut chunk) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                if !pty.active.load(Ordering::Acquire) {
                    break;
                }
                let mut data = &chunk[..n];
                let released;
                if let Some((marker, line)) = &boot {
                    held.extend_from_slice(data);
                    match pane_boot(&mut held, marker.as_bytes()) {
                        Some(output) => {
                            if write(&pty, line.as_bytes()).is_err() {
                                break;
                            }
                            boot = None;
                            released = output;
                        }
                        None => {
                            // Show prompts and banners now; keep a possible partial marker.
                            let keep = held.len().min(marker.len());
                            released = held.drain(..held.len() - keep).collect();
                        }
                    }
                    data = &released;
                }
                if data.is_empty() {
                    continue;
                }
                // Only desktop consumers opt in; native probes have no renderer ACK.
                while flow && pty.pending.load(Ordering::Acquire) >= 262144 {
                    if !pty.active.load(Ordering::Acquire) {
                        return;
                    }
                    std::thread::sleep(Duration::from_millis(4));
                }
                if flow {
                    pty.pending.fetch_add(data.len(), Ordering::AcqRel);
                }
                output(data);
            }
            terminal_finished(&pty, &id, &sink);
        });
        Ok(())
    }
    pub fn ack(&self, id: &str, bytes: usize) {
        if let Some(v) = self.native_views.lock().unwrap().get(id) {
            let _ = v
                .pending
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                    Some(n.saturating_sub(bytes))
                });
            return;
        }
        if let Some(p) = self.terminals.lock().unwrap().get(id) {
            let _ = p
                .pending
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                    Some(n.saturating_sub(bytes))
                });
        }
    }
    pub fn binary(&self, id: &str, bytes: &[u8]) -> Result<(), String> {
        // Never retain the registry guard across IPC: renderer ACK runs synchronously.
        let native = lock(&self.native_views).get(id).cloned();
        if let Some(v) = native {
            return v.with_input(|input| input.send(bytes));
        }
        let p = self
            .terminals
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Terminal is detached")?;
        write(&p, bytes)
    }
    pub fn input(&self, id: &str, data: &str) -> Result<(), String> {
        if self.native_views.lock().unwrap().contains_key(id) {
            return self.binary(id, data.as_bytes());
        }
        let p = self
            .terminals
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Terminal is detached")?;
        write(&p, data.as_bytes())
    }
    pub fn resize(&self, id: &str, cols: u16, rows: u16) -> Result<(), String> {
        let native = lock(&self.native_views).get(id).cloned();
        if let Some(v) = native {
            return v.with_input(|input| input.resize(cols.max(1), rows.max(1)));
        }
        let p = self
            .terminals
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Terminal is detached")?;
        p.io.resize(cols, rows)
    }
    pub fn close(&self, id: &str) {
        let native = self.native_views.lock().unwrap().remove(id);
        if let Some(v) = native {
            v.active.store(false, Ordering::Release);
            let _ = v.request("view_detach", json!({}));
            return;
        }
        let removed = lock(&self.terminals).remove(id);
        if let Some(p) = removed {
            p.active.store(false, Ordering::Release);
            p.io.kill();
        }
    }
}
impl Drop for Transport {
    fn drop(&mut self) {
        for v in self.native_views.get_mut().unwrap().values() {
            v.active.store(false, Ordering::Release);
            let _ = v.request("view_detach", json!({}));
        }
        for p in self
            .terminals
            .get_mut()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            p.active.store(false, Ordering::Release);
            p.io.kill();
        }
        for c in self
            .connections
            .get_mut()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            c.pty.active.store(false, Ordering::Release);
            c.pty.io.kill();
        }
        for (_, session) in self
            .sessions
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            if let Some(session) = session.upgrade() {
                session.close();
            }
        }
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn tailscale_windows_detection_requires_one_matching_peer() {
        let peers = serde_json::json!({"Peer": {
            "a": {"DNSName":"winbox.example.ts.net.","OS":"windows","TailscaleIPs":["100.64.0.2"]},
            "b": {"DNSName":"winbox-wsl.example.ts.net.","OS":"linux","TailscaleIPs":["100.64.0.3"]}
        }});
        for address in [
            "winbox.example.ts.net",
            "WINBOX.EXAMPLE.TS.NET.",
            "winbox",
            "100.64.0.2",
        ] {
            assert!(super::tailscale_target_is_windows(&peers, address));
        }
        for address in ["winbox-wsl", "100.64.0.3", "unknown", "winbox.other.ts.net"] {
            assert!(!super::tailscale_target_is_windows(&peers, address));
        }
        assert!(!super::tailscale_target_is_windows(
            &serde_json::json!({}),
            "winbox"
        ));
    }
    use super::*;
    #[cfg(unix)]
    #[test]
    fn native_view_io_never_holds_registry_needed_by_renderer_ack() {
        use std::os::unix::net::UnixListener;
        for method in ["input", "resize", "view_detach"] {
            let root =
                std::env::temp_dir().join(format!("daze-view-lock-{}", uuid::Uuid::new_v4()));
            std::fs::create_dir(&root).unwrap();
            let listener = UnixListener::bind(root.join("sessions.sock")).unwrap();
            let (entered, ready) = mpsc::channel();
            let (release, wait) = mpsc::channel();
            let broker = std::thread::spawn(move || {
                let (mut pipe, _) = listener.accept().unwrap();
                pipe.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let request = crate::local_sessions::ipc::receive(&mut pipe).unwrap();
                // Input and resize share one persistent input pipe.
                let expected = if method == "view_detach" {
                    "view_detach"
                } else {
                    "input_stream"
                };
                assert_eq!(request["method"], expected);
                entered.send(()).unwrap();
                wait.recv_timeout(Duration::from_secs(5)).unwrap();
                crate::local_sessions::ipc::send(&mut pipe, &json!({"result":null})).unwrap();
                if expected == "input_stream" {
                    let frame = crate::local_sessions::ipc::receive(&mut pipe).unwrap();
                    assert!(frame
                        .get(if method == "input" { "data" } else { "resize" })
                        .is_some());
                    crate::local_sessions::ipc::send(&mut pipe, &json!({"result":null})).unwrap();
                } else {
                    pipe.read_exact(&mut [0]).unwrap();
                }
            });
            let transport = Arc::new(Transport::default());
            transport.native_views.lock().unwrap().insert(
                "pane".into(),
                Arc::new(NativeView {
                    client: crate::local_sessions::Client {
                        root: root.clone(),
                        host: "host".into(),
                        shell: "powershell".into(),
                    },
                    session: "session".into(),
                    stamp: None,
                    viewer: "view".into(),
                    active: AtomicBool::new(true),
                    pending: AtomicUsize::new(1),
                    input: Mutex::new(None),
                }),
            );
            let worker = transport.clone();
            let io = std::thread::spawn(move || match method {
                "input" => worker.binary("pane", b"dummy"),
                "resize" => worker.resize("pane", 80, 24),
                _ => {
                    worker.close("pane");
                    Ok(())
                }
            });
            ready.recv_timeout(Duration::from_secs(5)).unwrap();
            let registry_available = transport.native_views.try_lock().is_ok();
            // Release even on regression failure so no blocked worker is leaked.
            release.send(()).unwrap();
            io.join().unwrap().unwrap();
            broker.join().unwrap();
            transport.ack("pane", 1);
            transport.native_views.lock().unwrap().clear();
            std::fs::remove_dir_all(root).unwrap();
            assert!(
                registry_available,
                "{method} held the renderer ACK registry during IPC"
            );
        }
    }
    #[cfg(unix)]
    #[test]
    fn native_end_continues_after_missing_session_but_preserves_real_errors() {
        use std::io::{Read, Write};
        let root = std::env::temp_dir().join(format!("daze-end-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let listener = std::os::unix::net::UnixListener::bind(root.join("sessions.sock")).unwrap();
        listener.set_nonblocking(true).unwrap();
        let broker = std::thread::spawn(move || {
            for (id, error) in [
                ("gone", Some(crate::local_sessions::SESSION_UNAVAILABLE)),
                ("live", None),
                ("wrong-owner", Some("Session belongs to another local host")),
            ] {
                let deadline = std::time::Instant::now() + Duration::from_secs(5);
                let mut stream = loop {
                    match listener.accept() {
                        Ok((stream, _)) => break stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(std::time::Instant::now() < deadline);
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut length = [0; 4];
                stream.read_exact(&mut length).unwrap();
                let mut bytes = vec![0; u32::from_le_bytes(length) as usize];
                stream.read_exact(&mut bytes).unwrap();
                let request: Value = serde_json::from_slice(&bytes).unwrap();
                assert_eq!(request["method"], "destroy");
                assert_eq!(request["args"]["id"], id);
                let response = serde_json::to_vec(&match error {
                    Some(error) => json!({"error":error}),
                    None => json!({"result":[]}),
                })
                .unwrap();
                stream
                    .write_all(&(response.len() as u32).to_le_bytes())
                    .unwrap();
                stream.write_all(&response).unwrap();
                stream.read_exact(&mut [0]).unwrap();
            }
        });
        let transport = Transport::default();
        transport.native_hosts.lock().unwrap().insert(
            "host".into(),
            crate::local_sessions::Client {
                root: root.clone(),
                host: uuid::Uuid::new_v4().to_string(),
                shell: "powershell".into(),
            },
        );
        assert!(transport
            .native_close("host", &["gone".into(), "live".into()], "end")
            .is_ok());
        assert_eq!(
            transport
                .native_close("host", &["wrong-owner".into()], "end")
                .unwrap_err(),
            "Session belongs to another local host"
        );
        broker.join().unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    struct RestoreRuntime(Option<std::ffi::OsString>, std::path::PathBuf);
    impl Drop for RestoreRuntime {
        fn drop(&mut self) {
            match self.0.take() {
                Some(value) => std::env::set_var("XDG_RUNTIME_DIR", value),
                None => std::env::remove_var("XDG_RUNTIME_DIR"),
            }
            let _ = std::fs::remove_dir_all(&self.1);
        }
    }
    #[cfg(unix)]
    #[test]
    fn remote_browser_program_routes_over_authenticated_protocol_after_reconnect() {
        // Keep the browser bridge in a private folder: the shared per-user
        // folder may belong to a real DazeDev session on this machine.
        // Short path: Unix socket names are limited to about 100 bytes.
        let runtime = std::path::PathBuf::from("/tmp").join(format!(
            "dz-{}",
            &uuid::Uuid::new_v4().simple().to_string()[..12]
        ));
        std::fs::create_dir(&runtime).unwrap();
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&runtime, std::fs::Permissions::from_mode(0o700)).unwrap();
        }
        let old_runtime = std::env::var_os("XDG_RUNTIME_DIR");
        std::env::set_var("XDG_RUNTIME_DIR", &runtime);
        std::env::set_var("BROWSER", "/bin/true");
        // A caller's tmux must not decide how the test pane finds its terminal.
        std::env::remove_var("TMUX");
        std::env::remove_var("TMUX_PANE");
        let transport = Transport::default();
        let host: Host =
            serde_json::from_value(json!({"id":"browser-test","name":"test","kind":"local"}))
                .unwrap();
        let (events_tx, events_rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |event, value| {
            let _ = events_tx.send((event.to_string(), value));
        });
        let seen = std::cell::RefCell::new(String::new());
        let await_event = |wanted: &str| {
            let deadline = std::time::Instant::now() + Duration::from_secs(10);
            loop {
                let (event, value) = events_rx
                    .recv_timeout(deadline.saturating_duration_since(std::time::Instant::now()))
                    .unwrap_or_else(|_| {
                        panic!(
                            "Browser fixture timed out waiting for {wanted}: {}",
                            seen.borrow()
                        )
                    });
                if event == wanted {
                    break value;
                }
                let text = match value["data"].as_str() {
                    Some(data) => {
                        String::from_utf8_lossy(&STANDARD.decode(data).unwrap_or_default())
                            .into_owned()
                    }
                    None => value.to_string(),
                };
                seen.borrow_mut().push_str(&format!("[{event}] {text}\n"));
            }
        };
        transport.connect(host.clone(), sink.clone()).unwrap();
        await_event("host-ready");
        let environment = transport
            .rpc(&host.id, "browser_prepare", json!({}))
            .unwrap();
        transport
            .open(
                "browser-pane".into(),
                host.clone(),
                "".into(),
                None,
                sink.clone(),
            )
            .unwrap();
        let mut opened = Vec::new();
        let mut old_nonce = String::new();
        for iteration in 0..2 {
            if iteration == 1 {
                transport.connect(host.clone(), sink.clone()).unwrap();
                await_event("host-ready");
                assert_eq!(
                    transport
                        .rpc(&host.id, "browser_prepare", json!({}))
                        .unwrap()["endpoint"],
                    environment["endpoint"]
                );
            }
            transport.input("browser-pane", "python3 -c 'import webbrowser; assert webbrowser.open(\"https://example.com/dazedev-bridge-test\")'\r").unwrap();
            let request = await_event("host-browser-open");
            let nonce = request["nonce"].as_str().unwrap();
            let id = request["id"].as_str().unwrap();
            if iteration == 1 {
                assert_ne!(nonce, old_nonce);
            }
            let url = transport
                .claim_browser(&host.id, nonce, id, request["url"].as_str().unwrap())
                .unwrap();
            opened.push(url);
            assert!(transport
                .claim_browser(&host.id, nonce, id, request["url"].as_str().unwrap())
                .is_err());
            transport.browser_result(&host.id, nonce, id, None);
            old_nonce = nonce.into();
        }
        assert_eq!(opened, vec!["https://example.com/dazedev-bridge-test"; 2]);
        transport.close("browser-pane");
        transport.disconnect(&host.id);
        assert!(transport
            .claim_browser(
                &host.id,
                &old_nonce,
                &uuid::Uuid::new_v4().to_string(),
                "https://example.com"
            )
            .is_err());
        // Remove only this test endpoint's private metadata and registrations.
        let folder = std::path::Path::new(environment["command"].as_str().unwrap())
            .parent()
            .unwrap();
        let endpoint = environment["endpoint"].as_str().unwrap();
        let metadata = folder.join(format!("endpoint-{endpoint}.json"));
        if let Ok(bytes) = std::fs::read(&metadata) {
            if let Ok(value) = serde_json::from_slice::<Value>(&bytes) {
                if let Some(socket) = value["socket"].as_str() {
                    let _ = std::fs::remove_file(folder.join(socket));
                }
            }
        }
        let _ = std::fs::remove_file(metadata);
        let _restore = RestoreRuntime(old_runtime, runtime.clone());
        for item in std::fs::read_dir(folder).unwrap().flatten() {
            if item.file_name().to_string_lossy().starts_with("tty-") {
                if let Ok(bytes) = std::fs::read(item.path()) {
                    if serde_json::from_slice::<Value>(&bytes)
                        .ok()
                        .map(|v| v["endpoint"] == endpoint)
                        .unwrap_or(false)
                    {
                        let _ = std::fs::remove_file(item.path());
                    }
                }
            }
        }
    }
    #[cfg(unix)]
    #[test]
    fn child_exit_notifies_terminal_before_retained_reader_eof_exactly_once() {
        let mut command = CommandBuilder::new("sh");
        // A descendant retains the slave PTY after the actual child exits.
        command.args(["-c", "trap '' HUP; sleep 3 & exit 0"]);
        let (pty, mut reader) = spawn(command).unwrap();
        let (eof_tx, eof_rx) = mpsc::channel();
        let reader_thread = std::thread::spawn(move || {
            let mut bytes = [0u8; 128];
            while matches!(reader.read(&mut bytes), Ok(n) if n > 0) {}
            let _ = eof_tx.send(());
        });
        let (tx, rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |name, _| {
            tx.send(name.to_string()).unwrap();
        });
        let exited = pty.clone();
        let exit_sink = sink.clone();
        observe_exit(&pty, move || terminal_finished(&exited, "pane", &exit_sink));
        assert_eq!(
            rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            "terminal-exit"
        );
        assert!(
            eof_rx.try_recv().is_err(),
            "reader must still be held open at child exit"
        );
        assert!(!pty.active.load(Ordering::Acquire));
        assert!(!pty.writable.load(Ordering::Acquire));
        assert!(write(&pty, b"NEVER_REPLAY").is_err());
        reader_thread.join().unwrap();
        terminal_finished(&pty, "pane", &sink);
        assert!(rx.try_recv().is_err());
    }
    #[cfg(unix)]
    #[test]
    fn child_exit_drains_host_requests_and_rejects_late_ready_without_eof() {
        let mut command = CommandBuilder::new("sh");
        command.args(["-c", "exit 0"]);
        let (pty, _retained_reader) = spawn(command).unwrap();
        let (reply_tx, reply_rx) = mpsc::channel();
        let conn = Arc::new(Connection {
            pty,
            pending: Mutex::new(HashMap::from([("pending".into(), reply_tx)])),
            ready: Mutex::new(true),
            browser: crate::browser::Requests::new("test".into()),
            prompts: None,
            binary: false,
        });
        let (tx, rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |name, _| {
            tx.send(name.to_string()).unwrap();
        });
        std::thread::sleep(Duration::from_millis(100));
        let exited = conn.clone();
        let exit_sink = sink.clone();
        observe_exit(&conn.pty, move || {
            host_finished(&exited, "host", &exit_sink)
        });
        assert_eq!(
            reply_rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            Err("Host disconnected".into())
        );
        assert_eq!(
            rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            "host-closed"
        );
        assert!(!*conn.ready.lock().unwrap());
        assert!(!host_ready(&conn, "host", json!({}), &sink));
        host_finished(&conn, "host", &sink);
        assert!(rx.try_recv().is_err());
    }
    #[cfg(unix)]
    #[test]
    fn intentional_close_suppresses_child_exit_notification() {
        let mut command = CommandBuilder::new("sh");
        command.args(["-c", "sleep 0.1"]);
        let (pty, _reader) = spawn(command).unwrap();
        pty.active.store(false, Ordering::Release);
        let (tx, rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |name, _| {
            tx.send(name.to_string()).unwrap();
        });
        let (finished_tx, finished_rx) = mpsc::channel();
        let exited = pty.clone();
        observe_exit(&pty, move || {
            terminal_finished(&exited, "pane", &sink);
            finished_tx.send(()).unwrap();
        });
        finished_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(rx.try_recv().is_err());
        assert!(!pty.writable.load(Ordering::Acquire));
    }
    #[cfg(unix)]
    #[test]
    fn blocked_terminal_write_has_deadline_and_cannot_replay() {
        let mut command = CommandBuilder::new("sh");
        command.args(["-c", "stty raw -echo; exec sleep 30"]);
        let (pty, _reader) = spawn(command).unwrap();
        std::thread::sleep(Duration::from_millis(100));
        let start = std::time::Instant::now();
        assert!(write(&pty, &vec![b'x'; 2_000_000]).is_err());
        assert!(start.elapsed() < Duration::from_secs(8));
        assert!(!pty.writable.load(Ordering::Acquire));
        assert!(write(&pty, b"NEVER_REPLAY\n").is_err());
    }
    #[cfg(unix)]
    #[test]
    fn stalled_management_probe_is_bounded() {
        let mut command = CommandBuilder::new("sh");
        command.args(["-c", "stty raw -echo; exec sleep 30"]);
        let (pty, _reader) = spawn(command).unwrap();
        let transport = Transport::default();
        transport.connections.lock().unwrap().insert(
            "stalled".into(),
            Arc::new(Connection {
                pty,
                pending: Mutex::new(HashMap::new()),
                ready: Mutex::new(true),
                browser: crate::browser::Requests::new("test".into()),
                prompts: None,
                binary: false,
            }),
        );
        let start = std::time::Instant::now();
        assert!(transport.health("stalled").is_err());
        assert!(start.elapsed() < Duration::from_secs(18));
        assert!(transport.rpc("stalled", "ping", json!({})).is_err());
    }
    #[test]
    fn management_terminal_answers_split_cursor_requests_and_keeps_prompts() {
        let mut t = ControlTerminal::default();
        assert_eq!(t.feed(b"\x1b["), (vec![], vec![]));
        assert_eq!(
            t.feed(b"6n\x1b[32mPassword: \x1b[0m"),
            (b"Password: ".to_vec(), b"\x1b[1;1R".to_vec())
        );
        assert_eq!(
            t.feed(b"\x1b]0;title\x07ready\n"),
            (b"ready\n".to_vec(), vec![])
        );
    }
    #[cfg(windows)]
    #[test]
    fn windows_hidden_console_starts_without_manual_cursor_response() {
        let mut cmd = CommandBuilder::new("cmd.exe");
        cmd.args([
            "/d",
            "/v:on",
            "/c",
            "set /p ANSWER=DAZE_PASSWORD: & echo DAZE_AUTH=!ANSWER!",
        ]);
        let (pty, mut reader) = spawn(cmd).unwrap();
        let copy = pty.clone();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut decoder = ControlTerminal::default();
            let mut output = Vec::new();
            let mut answered = false;
            let mut buf = [0; 4096];
            while let Ok(n) = reader.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let (text, replies) = decoder.feed(&buf[..n]);
                if !replies.is_empty() {
                    write(&copy, &replies).unwrap();
                }
                output.extend(text);
                if !answered && String::from_utf8_lossy(&output).contains("DAZE_PASSWORD:") {
                    write(&copy, b"yes\r").unwrap();
                    answered = true;
                }
                if String::from_utf8_lossy(&output).contains("DAZE_AUTH=yes") {
                    tx.send(()).ok();
                    break;
                }
            }
        });
        let result = rx.recv_timeout(Duration::from_secs(15));
        pty.io.kill();
        result.expect("Windows console blocked before command output");
    }
    #[test]
    fn framed_replies_reassemble_and_reject_missing_chunks() {
        let expected = json!({"id":"test","result":"Unicode ☃ and a large payload ".repeat(600)});
        let payload = STANDARD.encode(serde_json::to_vec(&expected).unwrap());
        let chunks: Vec<_> = payload.as_bytes().chunks(48).collect();
        let mut decoder = ReplyFrames::default();
        let mut result = None;
        for (i, chunk) in chunks.iter().enumerate() {
            let line = format!(
                "DAZED:test:@{i}/{}:{}",
                chunks.len(),
                std::str::from_utf8(chunk).unwrap()
            );
            result = decoder.line(&line, "DAZED:test:").unwrap();
        }
        assert_eq!(result, Some(expected));
        assert!(decoder.line("DAZED:test:@1/2:eA==", "DAZED:test:").is_err());
    }
    #[cfg(windows)]
    #[test]
    fn windows_management_output_preserves_long_protocol_lines() {
        let mut cmd = CommandBuilder::new("powershell.exe");
        cmd.args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", r#"$payload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"id":"test","result":"'+('x'*10000)+'"}')); $count=[int][Math]::Ceiling($payload.Length/48); for($i=0;$i -lt $count;$i++){[Console]::WriteLine('DAZED:test:@'+$i+'/'+$count+':'+$payload.Substring($i*48,[Math]::Min(48,$payload.Length-$i*48)))}"#]);
        let (pty, mut reader) = spawn(cmd).unwrap();
        let copy = pty.clone();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut terminal = ControlTerminal::default();
            let mut frames = ReplyFrames::default();
            let mut output = Vec::new();
            let mut buf = [0; 4096];
            while let Ok(n) = reader.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let (text, replies) = terminal.feed(&buf[..n]);
                if !replies.is_empty() {
                    write(&copy, &replies).unwrap();
                }
                output.extend(text);
                while let Some(end) = output.iter().position(|b| *b == b'\n') {
                    let line = String::from_utf8_lossy(&output[..end]).trim().to_string();
                    output.drain(..=end);
                    match frames.line(&line, "DAZED:test:") {
                        Ok(None) => {}
                        result => {
                            tx.send(result).ok();
                            return;
                        }
                    }
                }
            }
        });
        let result = rx.recv_timeout(Duration::from_secs(15));
        pty.io.kill();
        assert_eq!(
            result.expect("Windows protocol read stalled").unwrap(),
            Some(json!({"id":"test","result":"x".repeat(10000)}))
        );
    }
    #[test]
    fn bootstrap_waits_for_matching_ready_line_and_sends_source_once() {
        let source = b"print('test')";
        let mut boot = Bootstrap::new("nonce", source);
        for line in [
            "Password: ",
            "DAZED:other:bootstrap",
            "DAZED:nonce:boot",
            "banner DAZED:nonce:bootstrap",
        ] {
            assert!(boot.line(line).is_none());
        }
        let payload = boot.line("DAZED:nonce:bootstrap").unwrap();
        assert_eq!(
            STANDARD
                .decode(payload.strip_suffix(b"\n").unwrap())
                .unwrap(),
            source
        );
        assert!(boot.line("DAZED:nonce:bootstrap").is_none());
    }
    #[test]
    fn boot_command_stays_short_for_ssh_and_tailscale() {
        let script = boot_command(Boot::TerminalControl, "0123456789abcdef0123456789abcdef");
        for kind in ["ssh", "tailscale"] {
            let host: Host = serde_json::from_value(json!({
                "id":"test", "name":"Test", "kind":kind,
                "address":"dev-box", "user":"ubuntu"
            }))
            .unwrap();
            let cmd = command(&host, &script).unwrap();
            let units: usize = cmd
                .get_argv()
                .iter()
                .map(|a| a.to_string_lossy().encode_utf16().count() + 3)
                .sum();
            assert!(
                units < 2048,
                "Leave ample room for Tailscale's SSH arguments: {units}"
            );
            assert!(!script.contains(&STANDARD.encode(include_bytes!("../resources/host.py"))));
        }
    }
    #[test]
    fn bootstrap_loader_handles_large_source_then_preserves_protocol_input() {
        use std::io::{BufRead, BufReader};
        use std::process::{Command, Stdio};
        let python = if cfg!(windows) { "python" } else { "python3" };
        let mut child = Command::new(python)
            .args(["-u", "-c", HOST_LOADER, "test"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let mut input = child.stdin.take().unwrap();
        let mut output = BufReader::new(child.stdout.take().unwrap());
        let mut line = String::new();
        output.read_line(&mut line).unwrap();
        assert!(
            line.trim().is_empty(),
            "Separate the marker from an unfinished authentication prompt"
        );
        line.clear();
        output.read_line(&mut line).unwrap();
        let source = format!("# {}\nprint('loaded', flush=True)\nprint(sys.argv[1]+':'+sys.stdin.readline().strip(), flush=True)\n", "x".repeat(100_000));
        let mut boot = Bootstrap::new("test", source.as_bytes());
        input.write_all(&boot.line(line.trim()).unwrap()).unwrap();
        line.clear();
        output.read_line(&mut line).unwrap();
        assert_eq!(line.trim(), "loaded");
        input.write_all(b"next-request\n").unwrap();
        line.clear();
        output.read_line(&mut line).unwrap();
        assert_eq!(line.trim(), "test:next-request");
        drop(input);
        assert!(child.wait().unwrap().success());
    }
    #[cfg(windows)]
    #[test]
    fn windows_openssh_can_launch_bootstrap_command() {
        let ssh = std::path::PathBuf::from(std::env::var_os("WINDIR").unwrap())
            .join("System32/OpenSSH/ssh.exe");
        // Win32 OpenSSH can hang writing to redirected stderr inside an SSH
        // session. Capture its diagnostics in a unique file instead, and bound
        // the whole process: ConnectTimeout does not cover client startup.
        let log = std::env::temp_dir().join(format!("dazedev-ssh-{}.log", uuid::Uuid::new_v4()));
        let mut child = std::process::Command::new(ssh)
            .arg("-E")
            .arg(&log)
            .args([
                "-F",
                "NUL",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=2",
                "-p",
                "1",
                "127.0.0.1",
            ])
            .arg(boot_command(Boot::TerminalControl, "test"))
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .expect("Windows must launch OpenSSH with the bootstrap command");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        let status = loop {
            if let Some(status) = child.try_wait().unwrap() {
                break Some(status);
            }
            if std::time::Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        };
        let diagnostic = std::fs::read_to_string(&log).unwrap_or_default();
        let _ = std::fs::remove_file(log);
        let status = status.unwrap_or_else(|| panic!("OpenSSH exceeded 10 seconds: {diagnostic}"));
        assert!(!status.success());
        assert!(diagnostic.contains("connect to host"), "{diagnostic}");
    }
    #[test]
    fn quoting() {
        assert_eq!(quote("a'b"), "'a'\"'\"'b'");
    }
    #[test]
    fn tailscale_uses_device_identity_and_preserves_remote_command() {
        let mut h: Host = serde_json::from_value(serde_json::json!({
            "id": "test", "name": "Test", "kind": "tailscale",
            "address": "dev-box", "user": "ubuntu", "key": "ignored", "jump": "ignored"
        }))
        .unwrap();
        let c = command(&h, "printf '%s' 'quoted value'").unwrap();
        let args: Vec<_> = c
            .get_argv()
            .iter()
            .map(|s| s.to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            &args[1..],
            &[
                "ssh",
                "ubuntu@dev-box",
                "-tt",
                "-e",
                "none",
                "-o",
                "ServerAliveInterval=15",
                "-o",
                "ServerAliveCountMax=3",
                "printf '%s' 'quoted value'"
            ]
        );
        for invalid in [
            "",
            "-oProxyCommand=oops",
            "host name",
            "other@host",
            "host\n",
        ] {
            h.address = invalid.into();
            assert!(command(&h, "true").is_err());
        }
        h.address = "dev-box".into();
        h.user = "-bad".into();
        assert!(command(&h, "true").is_err());
    }
    #[cfg(unix)]
    #[test]
    fn plain_terminal_starts_in_literal_directory() {
        let root =
            std::env::temp_dir().join(format!("dazedev-cwd-{} ' $(false)", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let host: Host =
            serde_json::from_value(json!({"id":"cwd-test","name":"test","kind":"local"})).unwrap();
        let transport = Transport::default();
        let (tx, rx) = mpsc::channel();
        transport
            .open_marked_directory(
                "cwd-test".into(),
                host,
                "".into(),
                None,
                Arc::new(move |name, value| {
                    if name == "terminal-output" {
                        tx.send(value).ok();
                    }
                }),
                false,
                None,
                Some(root.to_string_lossy().into_owned()),
            )
            .unwrap();
        transport
            .input("cwd-test", "printf 'CWD-PROOF:%s:END\\n' \"$PWD\"\r")
            .unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        let mut output = String::new();
        while std::time::Instant::now() < deadline {
            if let Ok(value) = rx.recv_timeout(Duration::from_millis(100)) {
                if let Some(data) = value["data"].as_str() {
                    output.push_str(&String::from_utf8_lossy(&STANDARD.decode(data).unwrap()));
                }
            }
            if output.contains(&format!("CWD-PROOF:{}:END", root.display())) {
                break;
            }
        }
        transport.close("cwd-test");
        std::fs::remove_dir(&root).unwrap();
        assert!(
            output.contains(&format!("CWD-PROOF:{}:END", root.display())),
            "{output}"
        );
    }
    #[test]
    fn rejects_option_host() {
        let h = Host {
            id: "x".into(),
            name: "x".into(),
            kind: "ssh".into(),
            address: "-oProxyCommand=oops".into(),
            user: "".into(),
            port: None,
            key: "".into(),
            jump: "".into(),
            distro: "".into(),
            socket: "".into(),
            native_shell: None,
            system_ssh: false,
        };
        assert!(command(&h, "true").is_err());
    }

    #[test]
    fn bootstrap_sends_script_then_source_in_order() {
        let mut boot = Bootstrap::new("n", b"source").after_script("n", "echo script");
        assert!(boot.line("DAZED:n:bootstrap").is_none());
        assert_eq!(boot.line("DZBOOT:n").unwrap(), b"echo script\n");
        assert!(boot.line("DZBOOT:n").is_none());
        assert!(boot.line("DAZED:n:bootstrap").is_some());
    }
    #[test]
    fn pane_boot_strips_marker_line_and_keeps_surrounding_output() {
        let mut held = b"banner\r\nDZBOOT:abc\r\nprompt$ ".to_vec();
        assert_eq!(
            pane_boot(&mut held, b"DZBOOT:abc").unwrap(),
            b"banner\r\nprompt$ ".to_vec()
        );
        assert!(held.is_empty());
        let mut partial = b"DZBO".to_vec();
        assert!(pane_boot(&mut partial, b"DZBOOT:abc").is_none());
    }
    #[test]
    fn boot_commands_are_safe_for_any_login_shell() {
        // fish, nu and tcsh treat backslashes and nested quotes differently;
        // keep the single-quoted body free of both.
        for boot in [Boot::Pipe, Boot::TerminalControl, Boot::Pane] {
            let command = boot_command(boot, "0123456789abcdef");
            let body = command
                .strip_prefix("exec /bin/sh -c '")
                .and_then(|b| b.strip_suffix('\''))
                .unwrap();
            assert!(!body.contains('\'') && !body.contains('\\') && !body.contains('!'));
        }
    }
    #[cfg(unix)]
    #[test]
    fn boot_command_runs_the_script_line_through_posix_sh() {
        use std::process::{Command, Stdio};
        for shell in ["/bin/sh", "/bin/bash", "/usr/bin/zsh"] {
            if !std::path::Path::new(shell).exists() {
                continue;
            }
            let mut child = Command::new(shell)
                .args(["-c", &boot_command(Boot::Pipe, "x")])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .spawn()
                .unwrap();
            child
                .stdin
                .take()
                .unwrap()
                .write_all(b"v='a b'; [ -n \"${v#a}\" ] && printf 'posix:%s\\n' \"$v\"\n")
                .unwrap();
            let output = child.wait_with_output().unwrap();
            assert_eq!(
                String::from_utf8_lossy(&output.stdout),
                "posix:a b\n",
                "{shell}"
            );
        }
    }
    #[test]
    fn loader_explains_old_python() {
        assert!(HOST_LOADER.contains("v<(3,9)") && HOST_LOADER.contains(":python:"));
        let script = control_script("sock", "nonce", "endpoint", "desktop", true);
        assert!(script.contains("exec python3 -I -u -c ") && script.ends_with(" 2"));
        assert!(!control_script("sock", "nonce", "endpoint", "desktop", false).ends_with(" 2"));
    }

    /// End-to-end check against a private OpenSSH server on loopback. It needs
    /// /usr/sbin/sshd and changes HOME for the process, so it runs on request:
    /// `cargo test --no-default-features --lib -- --ignored --test-threads=1 builtin_ssh`
    #[cfg(unix)]
    #[test]
    #[ignore = "starts a private sshd and changes HOME"]
    fn builtin_ssh_shares_one_login_for_control_and_terminals() {
        use std::process::{Command, Stdio};
        let sshd = std::path::Path::new("/usr/sbin/sshd");
        if !sshd.exists() {
            return;
        }
        let root = std::env::temp_dir().join(format!("dazedev-sshd-{}", uuid::Uuid::new_v4()));
        let home = root.join("home");
        std::fs::create_dir_all(home.join(".ssh")).unwrap();
        for name in ["host_key", "client_key"] {
            assert!(Command::new("ssh-keygen")
                .args(["-q", "-t", "ed25519", "-N", "", "-C", "", "-f"])
                .arg(root.join(name))
                .status()
                .unwrap()
                .success());
        }
        std::fs::copy(root.join("client_key.pub"), root.join("authorized_keys")).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let runtime_dir = root.join("runtime");
        std::fs::create_dir(&runtime_dir).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&runtime_dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        }
        std::fs::write(
            root.join("sshd_config"),
            format!(
                "Port {port}\nListenAddress 127.0.0.1\nHostKey {}\nAuthorizedKeysFile {}\nPidFile {}\nUsePAM no\nStrictModes no\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nPubkeyAuthentication yes\nSetEnv XDG_RUNTIME_DIR={}\n",
                root.join("host_key").display(),
                root.join("authorized_keys").display(),
                root.join("sshd.pid").display(),
                runtime_dir.display()
            ),
        )
        .unwrap();
        let log = std::fs::File::create(root.join("sshd.log")).unwrap();
        let mut server = Command::new(sshd)
            .args(["-D", "-e", "-f"])
            .arg(root.join("sshd_config"))
            .stdin(Stdio::null())
            .stderr(log)
            .spawn()
            .unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        while std::net::TcpStream::connect(("127.0.0.1", port)).is_err() {
            assert!(std::time::Instant::now() < deadline, "sshd did not start");
            std::thread::sleep(Duration::from_millis(50));
        }
        // Keep every file this test touches inside its own folder.
        std::fs::write(
            root.join("ssh_config"),
            format!(
                "UserKnownHostsFile {}\nGlobalKnownHostsFile /dev/null\nIdentityAgent none\nHashKnownHosts no\n",
                home.join(".ssh/known_hosts").display()
            ),
        )
        .unwrap();
        crate::ssh::config::set_test_config(Some(root.join("ssh_config")));
        let old_home = std::env::var_os("HOME");
        std::env::set_var("HOME", &home);
        std::env::remove_var("SSH_AUTH_SOCK");
        let user = String::from_utf8(Command::new("id").arg("-un").output().unwrap().stdout)
            .unwrap()
            .trim()
            .to_owned();
        let host: Host = serde_json::from_value(json!({
            "id":"builtin-ssh", "name":"test", "kind":"ssh", "address":"127.0.0.1",
            "port":port, "user":user, "key":root.join("client_key").to_string_lossy(),
            "socket":format!("dazedev-test-{}", uuid::Uuid::new_v4().simple())
        }))
        .unwrap();
        let (tx, rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |event, value| {
            let _ = tx.send((event.to_string(), value));
        });
        let transport = Transport::default();
        let wait = |wanted: &str, check: &dyn Fn(&Value) -> bool| {
            let deadline = std::time::Instant::now() + Duration::from_secs(20);
            loop {
                let (event, value) = rx
                    .recv_timeout(deadline.saturating_duration_since(std::time::Instant::now()))
                    .unwrap_or_else(|_| panic!("timed out waiting for {wanted}"));
                if event == wanted && check(&value) {
                    return value;
                }
            }
        };
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            transport.connect(host.clone(), sink.clone()).unwrap();
            wait("host-output", &|v| {
                v["text"]
                    .as_str()
                    .unwrap_or("")
                    .contains("(yes/no/[fingerprint])")
            });
            transport.authenticate(&host.id, "yes").unwrap();
            wait("host-ready", &|_| true);
            transport.health(&host.id).unwrap();
            let known = std::fs::read_to_string(home.join(".ssh/known_hosts")).unwrap();
            assert!(known.starts_with(&format!("[127.0.0.1]:{port} ssh-ed25519 ")));
            transport
                .open_marked(
                    "builtin-pane".into(),
                    host.clone(),
                    "".into(),
                    None,
                    sink.clone(),
                    false,
                    None,
                )
                .unwrap();
            transport
                .input("builtin-pane", "echo PANE-$((6*7))\r")
                .unwrap();
            let mut output = String::new();
            let deadline = std::time::Instant::now() + Duration::from_secs(20);
            while !output.contains("PANE-42") {
                assert!(std::time::Instant::now() < deadline, "{output}");
                if let Ok((event, value)) = rx.recv_timeout(Duration::from_millis(200)) {
                    if event == "terminal-output" {
                        output.push_str(&String::from_utf8_lossy(
                            &STANDARD.decode(value["data"].as_str().unwrap()).unwrap(),
                        ));
                    }
                }
            }
            assert!(
                !output.contains("DZBOOT") && !output.contains("eval"),
                "{output}"
            );
            // Reconnecting the management channel reuses the live login.
            transport.connect(host.clone(), sink.clone()).unwrap();
            wait("host-ready", &|_| true);
            let log = std::fs::read_to_string(root.join("sshd.log")).unwrap();
            assert_eq!(log.matches("Accepted publickey").count(), 1, "{log}");
            transport.close("builtin-pane");
            transport.disconnect(&host.id);
            // An encrypted key is unlocked only after the server accepts it.
            assert!(Command::new("ssh-keygen")
                .args(["-q", "-t", "ed25519", "-N", "pass phrase", "-C", "", "-f"])
                .arg(root.join("locked_key"))
                .status()
                .unwrap()
                .success());
            let mut keys = std::fs::read_to_string(root.join("authorized_keys")).unwrap();
            keys.push_str(&std::fs::read_to_string(root.join("locked_key.pub")).unwrap());
            std::fs::write(root.join("authorized_keys"), keys).unwrap();
            let mut locked = host.clone();
            locked.key = root.join("locked_key").to_string_lossy().into_owned();
            let second = Transport::default();
            second.connect(locked.clone(), sink.clone()).unwrap();
            wait("host-output", &|v| {
                v["text"]
                    .as_str()
                    .unwrap_or("")
                    .contains("Enter passphrase for key")
            });
            second.authenticate(&locked.id, "wrong").unwrap();
            wait("host-output", &|v| {
                v["text"].as_str().unwrap_or("").contains("Bad passphrase")
            });
            second.authenticate(&locked.id, "pass phrase").unwrap();
            wait("host-ready", &|_| true);
            second.shutdown();
            // A changed host key is refused without a prompt.
            let mut entry = std::fs::read_to_string(home.join(".ssh/known_hosts")).unwrap();
            let other = std::fs::read_to_string(root.join("client_key.pub")).unwrap();
            entry = format!(
                "{} {}\n",
                entry.split_whitespace().next().unwrap(),
                other.trim()
            );
            std::fs::write(home.join(".ssh/known_hosts"), entry).unwrap();
            let third = Transport::default();
            third.connect(host.clone(), sink.clone()).unwrap();
            wait("host-output", &|v| {
                v["text"]
                    .as_str()
                    .unwrap_or("")
                    .contains("REMOTE HOST IDENTIFICATION HAS CHANGED")
            });
            wait("host-closed", &|_| true);
            third.shutdown();
        }));
        transport.shutdown();
        let _ = server.kill();
        let _ = server.wait();
        match old_home {
            Some(h) => std::env::set_var("HOME", h),
            None => std::env::remove_var("HOME"),
        }
        crate::ssh::config::set_test_config(None);
        let _ = std::fs::remove_dir_all(&root);
        if let Err(panic) = result {
            std::panic::resume_unwind(panic);
        }
    }

    fn host_frame(id: &str, data: &[u8], last: bool) -> Vec<u8> {
        let mut body = vec![u8::from(last)];
        body.extend((id.len() as u16).to_be_bytes());
        body.extend(id.as_bytes());
        body.extend(data);
        let mut frame = (body.len() as u32).to_be_bytes().to_vec();
        frame.extend(body);
        frame
    }
    #[test]
    fn protocol_two_reassembles_interleaved_parts() {
        let big = serde_json::to_vec(&json!({"id":"big","result":"x".repeat(1000)})).unwrap();
        let ping = serde_json::to_vec(&json!({"id":"ping","result":null})).unwrap();
        let mut wire = host_frame("big", &big[..400], false);
        wire.extend(host_frame("ping", &ping, true));
        wire.extend(host_frame("big", &big[400..], true));
        let mut decoder = MessageFrames::default();
        // Feed one byte at a time to cover split frames.
        let mut out = Vec::new();
        for byte in &wire {
            out.extend(decoder.push(std::slice::from_ref(byte)).unwrap());
        }
        assert_eq!(out[0]["id"], "ping");
        assert_eq!(out[1]["result"].as_str().unwrap().len(), 1000);
        assert!(decoder.buffer.is_empty() && decoder.parts.is_empty());
        assert!(MessageFrames::default()
            .push(&[0xff, 0xff, 0xff, 0xff])
            .is_err());
    }
    #[test]
    fn protocol_two_errors_keep_conflict_prefix() {
        assert_eq!(
            host_error(&json!({"code":"conflict","message":"file changed"})).unwrap(),
            "CONFLICT: file changed"
        );
        assert_eq!(
            host_error(&json!({"code":"failed","message":"bad"})).unwrap(),
            "bad"
        );
        assert_eq!(host_error(&json!("plain")).unwrap(), "plain");
        assert!(host_error(&Value::Null).is_none());
    }
    #[test]
    fn shared_schema_lists_methods_the_desktop_calls() {
        let schema: Value =
            serde_json::from_str(include_str!("../../../../shared/protocol/host-v2.json")).unwrap();
        assert_eq!(schema["protocol"].as_u64(), Some(HOST_PROTOCOL));
        for method in ["ping", "browser_prepare", "browser_result"] {
            assert_eq!(schema["methods"][method]["lane"], "inline", "{method}");
        }
        let frame = request_frame(&json!({"id":"1"})).unwrap();
        assert_eq!(
            u32::from_be_bytes(frame[..4].try_into().unwrap()) as usize,
            frame.len() - 4
        );
    }
}
