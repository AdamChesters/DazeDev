use base64::{engine::general_purpose::STANDARD, Engine};
use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    io::{Read, Write},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc, Arc, Mutex,
    },
    time::Duration,
};
pub type EventSink = Arc<dyn Fn(&str, Value) + Send + Sync>;
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

fn check_tailscale_target(host: &Host) -> Result<(), String> {
    if host.kind != "tailscale" {
        return Ok(());
    }
    // Status is an optional local hint. Bound the probe and keep normal SSH errors if it is unavailable.
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
        return Ok(());
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
    if status.is_some_and(|s| s.success()) {
        if let Ok((Ok(_), bytes)) = rx.recv_timeout(Duration::from_millis(100)) {
            if bytes.len() <= 4 * 1024 * 1024 {
                if let Ok(value) = serde_json::from_slice(&bytes) {
                    if tailscale_target_is_windows(&value, &host.address) {
                        return Err("TAILSCALE_WINDOWS_TARGET: This Tailscale device runs Windows, which cannot serve Tailscale SSH. Connect to a WSL/Linux device with Tailscale SSH enabled, using its own tailnet name and Linux username. Ordinary SSH over Tailscale uses separate SSH credentials; DazeDev's remote host also needs Python 3 and tmux.".into());
                    }
                }
            }
        }
    }
    Ok(())
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
            c.args([
                "-tt",
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
            c.args(["--", "sh", "-lc", remote]);
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
const HOST_LOADER: &str = "import base64,sys;print();print('DAZED:'+sys.argv[1]+':bootstrap',flush=True);exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))";
fn bootstrap_command(socket: &str, nonce: &str) -> String {
    format!(
        "stty -echo -icanon min 1 time 0 && export DAZED_TMUX_SOCKET={} && exec python3 -u -c {} {}",
        quote(socket),
        quote(HOST_LOADER),
        quote(nonce)
    )
}
struct Bootstrap {
    marker: String,
    payload: Option<Vec<u8>>,
}
impl Bootstrap {
    fn new(nonce: &str, source: &[u8]) -> Self {
        Self {
            marker: format!("DAZED:{nonce}:bootstrap"),
            payload: Some((STANDARD.encode(source) + "\n").into_bytes()),
        }
    }
    fn line(&mut self, line: &str) -> Option<Vec<u8>> {
        if line == self.marker {
            self.payload.take()
        } else {
            None
        }
    }
}
struct Pty {
    pending: AtomicUsize,
    active: AtomicBool,
    writer: mpsc::SyncSender<(Vec<u8>, mpsc::Sender<Result<(), String>>)>,
    writable: Arc<AtomicBool>,
    master: Mutex<Box<dyn MasterPty + Send>>,
    killer: Mutex<Box<dyn portable_pty::ChildKiller + Send + Sync>>,
    exited: Mutex<Option<mpsc::Receiver<()>>>,
}
struct Connection {
    pty: Arc<Pty>,
    pending: Mutex<HashMap<String, mpsc::Sender<Result<Value, String>>>>,
    ready: Mutex<bool>,
    browser: crate::browser::Requests,
}
struct NativeView {
    client: crate::local_sessions::Client,
    session: String,
    stamp: Option<String>,
    viewer: String,
    active: AtomicBool,
    pending: AtomicUsize,
}
impl NativeView {
    fn request(&self, method: &str, mut args: Value) -> Result<Value, String> {
        args["id"] = json!(self.session);
        args["stamp"] = json!(self.stamp);
        args["viewer"] = json!(self.viewer);
        self.client.request(method, args)
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
    Ok((
        Arc::new(Pty {
            pending: AtomicUsize::new(0),
            active: AtomicBool::new(true),
            writer: write_tx,
            writable,
            master: Mutex::new(pair.master),
            killer: Mutex::new(killer),
            exited: Mutex::new(Some(exit_rx)),
        }),
        reader,
    ))
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
    let (tx, rx) = mpsc::channel();
    let result = pty
        .writer
        .try_send((data.to_vec(), tx))
        .map_err(|_| "Terminal input is unavailable".to_string())
        .and_then(|_| {
            rx.recv_timeout(Duration::from_secs(5))
                .map_err(|_| "Terminal input timed out; reconnect this terminal".to_string())
        })
        .and_then(|r| r);
    if result.is_err() {
        pty.writable.store(false, Ordering::Release);
        let _ = pty.killer.lock().unwrap().kill();
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
        let views: Vec<_> = self.native_views.lock().unwrap().keys().cloned().collect();
        for id in views {
            self.close(&id);
        }
        for (_, p) in self.terminals.lock().unwrap().drain() {
            p.active.store(false, Ordering::Release);
            let _ = p.killer.lock().unwrap().kill();
        }
        for (_, c) in self.connections.lock().unwrap().drain() {
            c.pty.active.store(false, Ordering::Release);
            let _ = c.pty.killer.lock().unwrap().kill();
        }
    }
    pub fn connect(&self, host: Host, sink: EventSink) -> Result<(), String> {
        check_tailscale_target(&host)?;
        self.disconnect(&host.id);
        if host.kind == "powershell" {
            if !cfg!(windows) {
                return Err("Native PowerShell hosts require Windows".into());
            }
            let root = self
                .native_root
                .lock()
                .unwrap()
                .clone()
                .ok_or("Native session folder unavailable")?;
            let mut client = crate::local_sessions::Client::ensure(root, host.id.clone())?;
            client.shell = host.native_shell.unwrap_or_else(|| "powershell".into());
            let identity = client.request("ping", json!({}))?;
            self.native_hosts
                .lock()
                .unwrap()
                .insert(host.id.clone(), client);
            sink("host-ready", json!({"hostId":host.id,"identity":identity}));
            return Ok(());
        }
        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let endpoint = self
            .browser_endpoints
            .lock()
            .unwrap()
            .entry(host.id.clone())
            .or_insert_with(|| uuid::Uuid::new_v4().simple().to_string())
            .clone();
        let script = format!(
            "export DAZED_BROWSER_ENDPOINT={} DAZED_BROWSER_DESKTOP={}; {}",
            quote(&endpoint),
            quote(&self.browser_desktop),
            bootstrap_command(&host.socket, &nonce)
        );
        let source = format!(
            "import base64\nBROWSER_SOURCE=base64.b64decode('{}').decode()\n{}",
            STANDARD.encode(include_bytes!("../resources/browser.py")),
            include_str!("../resources/host.py")
        );
        let mut bootstrap = Bootstrap::new(&nonce, source.as_bytes());
        let (pty, mut reader) = spawn(command(&host, &script)?)?;
        let conn = Arc::new(Connection {
            pty,
            pending: Mutex::new(HashMap::new()),
            ready: Mutex::new(false),
            browser: crate::browser::Requests::new(nonce.clone()),
        });
        self.connections
            .lock()
            .unwrap()
            .insert(host.id.clone(), conn.clone());
        let exited_conn = conn.clone();
        let exited_sink = sink.clone();
        let exited_id = host.id.clone();
        observe_exit(&conn.pty, move || {
            host_finished(&exited_conn, &exited_id, &exited_sink)
        });
        std::thread::spawn(move || {
            let prefix = format!("DAZED:{nonce}:");
            let mut buffer = Vec::new();
            let mut terminal = ControlTerminal::default();
            let mut frames = ReplyFrames::default();
            let mut chunk = [0u8; 8192];
            'read: loop {
                let n = match reader.read(&mut chunk) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                if !conn.pty.active.load(Ordering::Acquire) {
                    break;
                }
                let (clean, replies) = terminal.feed(&chunk[..n]);
                if !replies.is_empty() {
                    let _ = write(&conn.pty, &replies);
                }
                let text = String::from_utf8_lossy(&clean);
                if !*conn.ready.lock().unwrap() && !text.contains(&prefix) {
                    sink("host-output", json!({"hostId":host.id,"text":text}));
                }
                buffer.extend_from_slice(&clean);
                while let Some(end) = buffer.iter().position(|b| *b == b'\n') {
                    let line = String::from_utf8_lossy(&buffer[..end]).trim().to_string();
                    buffer.drain(..=end);
                    // Wait for the remote loader, never send source into an SSH
                    // password/host-key prompt. Consume the marker only once.
                    if let Some(payload) = bootstrap.line(&line) {
                        if let Err(e) = write(&conn.pty, &payload) {
                            sink("host-output", json!({"hostId":host.id,"text":e}));
                            break 'read;
                        }
                        continue;
                    }
                    match frames.line(&line, &prefix) {
                        Ok(Some(v)) => {
                            let id = v["id"].as_str().unwrap_or("");
                            if v["event"] == "browser-open" {
                                if conn.pty.active.load(Ordering::Acquire)
                                    && *conn.ready.lock().unwrap()
                                {
                                    sink(
                                        "host-browser-open",
                                        json!({"hostId":host.id,"nonce":nonce,"id":id,"url":v["url"]}),
                                    );
                                }
                            } else if id == "ready" {
                                if !host_ready(&conn, &host.id, v["result"].clone(), &sink) {
                                    break 'read;
                                }
                            } else if let Some(tx) = conn.pending.lock().unwrap().remove(id) {
                                let _ = tx.send(if let Some(e) = v["error"].as_str() {
                                    Err(e.to_string())
                                } else {
                                    Ok(v["result"].clone())
                                });
                            }
                        }
                        Ok(None) => {}
                        Err(e) => {
                            sink("host-output", json!({"hostId":host.id,"text":e}));
                            break 'read;
                        }
                    }
                }
                if buffer.len() > 1_000_000 {
                    buffer.clear();
                }
            }
            host_finished(&conn, &host.id, &sink);
            let _ = conn.pty.killer.lock().unwrap().kill();
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
        let mut pending = c.pending.lock().unwrap();
        // Reserve and write in order: a health probe must never queue behind a long operation.
        let _ = idle_only; // Host control requests have an independent lane.
        pending.insert(request.clone(), tx);
        let payload = STANDARD.encode(
            serde_json::to_vec(&json!({"id":request,"method":method,"args":args})).unwrap(),
        ) + "\n";
        if let Err(e) = write(&c.pty, payload.as_bytes()) {
            pending.remove(&request);
            return Err(e);
        }
        drop(pending);
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
        c.pending.lock().unwrap().remove(&request);
        if r.is_err() && method == "ping" {
            *c.ready.lock().unwrap() = false;
            let _ = c.pty.killer.lock().unwrap().kill();
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
        if *c.ready.lock().unwrap() {
            return Err("Already authenticated".into());
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
            for (_, tx) in c.pending.lock().unwrap().drain() {
                let _ = tx.send(Err("Host disconnected".into()));
            }
            let _ = c.pty.killer.lock().unwrap().kill();
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
            });
            // Fetch before registering so a failed attach never leaves a live view behind.
            let first = view.request("view_poll", json!({}))?;
            self.native_views
                .lock()
                .unwrap()
                .insert(id.clone(), view.clone());
            let marker = ready_marker
                .map(|m| format!("\x1b]777;dazedev-ready:{m}:{}\x07", descriptor["pid"]))
                .unwrap_or_default();
            std::thread::spawn(move || {
                let mut next = Some(first);
                let mut sequence = None;
                let mut first = true;
                while view.active.load(Ordering::Acquire) {
                    while flow
                        && view.pending.load(Ordering::Acquire) >= 262144
                        && view.active.load(Ordering::Acquire)
                    {
                        std::thread::sleep(Duration::from_millis(4));
                    }
                    if !view.active.load(Ordering::Acquire) {
                        break;
                    }
                    let response = next
                        .take()
                        .map(Ok)
                        .unwrap_or_else(|| view.request("view_poll", json!({"since":sequence})));
                    match response {
                        Ok(v) => {
                            let mut data = if v["reset"] == true {
                                b"\x1b[2J\x1b[H".to_vec()
                            } else {
                                Vec::new()
                            };
                            match STANDARD.decode(v["data"].as_str().unwrap_or("")) {
                                Ok(b) => data.extend(b),
                                Err(_) => break,
                            }
                            if first {
                                data.extend(marker.as_bytes());
                                first = false;
                            }
                            sequence = v["seq"].as_u64();
                            if !data.is_empty() {
                                if flow {
                                    view.pending.fetch_add(data.len(), Ordering::AcqRel);
                                }
                                sink(
                                    "terminal-output",
                                    json!({"id":id,"data":STANDARD.encode(data)}),
                                );
                            }
                            if v["running"] == false {
                                sink("terminal-exit", json!({"id":id}));
                                break;
                            }
                        }
                        Err(error) => {
                            sink("terminal-exit", json!({"id":id,"error":error}));
                            break;
                        }
                    }
                    std::thread::sleep(Duration::from_millis(30));
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
        let shell = r#"dazedev_fast=0; while :; do printf '\033]777;dazedev-shell\007'; dazedev_start=$(date +%s); "${SHELL:-/bin/sh}" -l; dazedev_elapsed=$(($(date +%s)-dazedev_start)); if [ "$dazedev_elapsed" -lt 2 ]; then dazedev_fast=$((dazedev_fast+1)); else dazedev_fast=0; fi; if [ "$dazedev_fast" -ge 3 ]; then printf '%s\n' 'Shell exited repeatedly. Check your login shell configuration, then reconnect.'; exit 1; fi; done"#;
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
        let (pty, mut reader) = spawn(command(&host, &script)?)?;
        self.terminals
            .lock()
            .unwrap()
            .insert(id.clone(), pty.clone());
        let exited_pty = pty.clone();
        let exited_sink = sink.clone();
        let exited_id = id.clone();
        observe_exit(&pty, move || {
            terminal_finished(&exited_pty, &exited_id, &exited_sink)
        });
        std::thread::spawn(move || {
            let mut chunk = [0u8; 16384];
            loop {
                match reader.read(&mut chunk) {
                    Ok(0) | Err(_) => break,
                    Ok(n) if pty.active.load(Ordering::Acquire) => {
                        // Only desktop consumers opt in; native probes have no renderer ACK.
                        while flow && pty.pending.load(Ordering::Acquire) >= 262144 {
                            if !pty.active.load(Ordering::Acquire) {
                                return;
                            }
                            std::thread::sleep(Duration::from_millis(4));
                        }
                        if flow {
                            pty.pending.fetch_add(n, Ordering::AcqRel);
                        }
                        sink(
                            "terminal-output",
                            json!({"id":id,"data":STANDARD.encode(&chunk[..n])}),
                        );
                    }
                    Ok(_) => break,
                }
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
        if let Some(v) = self.native_views.lock().unwrap().get(id).cloned() {
            return v
                .request("input", json!({"data":STANDARD.encode(bytes)}))
                .map(|_| ());
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
        if let Some(v) = self.native_views.lock().unwrap().get(id).cloned() {
            return v
                .request("resize", json!({"cols":cols,"rows":rows}))
                .map(|_| ());
        }
        let p = self
            .terminals
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or("Terminal is detached")?;
        let r = p
            .master
            .lock()
            .unwrap()
            .resize(PtySize {
                rows: rows.max(1),
                cols: cols.max(1),
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string());
        r
    }
    pub fn close(&self, id: &str) {
        if let Some(v) = self.native_views.lock().unwrap().remove(id) {
            v.active.store(false, Ordering::Release);
            let _ = v.request("view_detach", json!({}));
            return;
        }
        let removed = self.terminals.lock().unwrap().remove(id);
        if let Some(p) = removed {
            p.active.store(false, Ordering::Release);
            let _ = p.killer.lock().unwrap().kill();
        }
    }
}
impl Drop for Transport {
    fn drop(&mut self) {
        for v in self.native_views.get_mut().unwrap().values() {
            v.active.store(false, Ordering::Release);
            let _ = v.request("view_detach", json!({}));
        }
        for p in self.terminals.get_mut().unwrap().values() {
            p.active.store(false, Ordering::Release);
            let _ = p.killer.lock().unwrap().kill();
        }
        for c in self.connections.get_mut().unwrap().values() {
            c.pty.active.store(false, Ordering::Release);
            let _ = c.pty.killer.lock().unwrap().kill();
        }
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn tailscale_windows_detection_requires_one_matching_peer() {
        let peers = serde_json::json!({"Peer": {
            "a": {"DNSName":"vm202.example.ts.net.","OS":"windows","TailscaleIPs":["100.64.0.2"]},
            "b": {"DNSName":"vm202-wsl.example.ts.net.","OS":"linux","TailscaleIPs":["100.64.0.3"]}
        }});
        for address in [
            "vm202.example.ts.net",
            "VM202.EXAMPLE.TS.NET.",
            "vm202",
            "100.64.0.2",
        ] {
            assert!(super::tailscale_target_is_windows(&peers, address));
        }
        for address in ["vm202-wsl", "100.64.0.3", "unknown", "vm202.other.ts.net"] {
            assert!(!super::tailscale_target_is_windows(&peers, address));
        }
        assert!(!super::tailscale_target_is_windows(
            &serde_json::json!({}),
            "vm202"
        ));
    }
    use super::*;
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
    #[cfg(unix)]
    #[test]
    fn remote_browser_program_routes_over_authenticated_protocol_after_reconnect() {
        let transport = Transport::default();
        let host: Host =
            serde_json::from_value(json!({"id":"browser-test","name":"test","kind":"local"}))
                .unwrap();
        let (events_tx, events_rx) = mpsc::channel();
        let sink: EventSink = Arc::new(move |event, value| {
            let _ = events_tx.send((event.to_string(), value));
        });
        let await_event = |wanted: &str| {
            let deadline = std::time::Instant::now() + Duration::from_secs(10);
            loop {
                let (event, value) = events_rx
                    .recv_timeout(deadline.saturating_duration_since(std::time::Instant::now()))
                    .expect("Browser fixture event timed out");
                if event == wanted {
                    break value;
                }
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
        let _ = pty.killer.lock().unwrap().kill();
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
        let _ = pty.killer.lock().unwrap().kill();
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
    fn bootstrap_command_stays_short_for_ssh_and_tailscale() {
        let script = bootstrap_command("test socket's name", "0123456789abcdef0123456789abcdef");
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
            .arg(bootstrap_command("", "test"))
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
        };
        assert!(command(&h, "true").is_err());
    }
}
