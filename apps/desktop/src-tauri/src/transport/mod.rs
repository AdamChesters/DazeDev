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
mod boot;
mod control;
mod frames;
mod launch;
mod process;
mod terminal;
#[cfg(test)]
mod tests;
use boot::*;
use control::*;
use frames::*;
use launch::*;
pub use launch::{command, quote};
use process::*;
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
// The channel retains an early exit until the caller has installed its connection
// and callback. EOF and child completion share the same active flag below.
fn observe_exit(pty: &Pty, finished: impl FnOnce() + Send + 'static) {
    if let Some(exited) = lock(&pty.exited).take() {
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
impl Transport {
    pub fn set_native_root(&self, path: std::path::PathBuf) {
        *lock(&self.native_root) = Some(path);
    }
    fn native_client(&self, id: &str) -> Option<crate::local_sessions::Client> {
        lock(&self.native_hosts).get(id).cloned()
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
                    // Ignore only its ended-session response, then continue
                    // ending the remaining explicitly chosen IDs.
                    match client.call("destroy", json!({"id":id})) {
                        Ok(_) => {}
                        Err(error)
                            if error.kind == crate::local_sessions::SessionErrorKind::Ended => {}
                        Err(error) => return Err(error.into()),
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
            STANDARD.encode(include_bytes!("../../resources/browser.py")),
            include_str!("../../resources/host.py")
        );
        let bootstrap = Bootstrap::new(&nonce, source.as_bytes());
        if let Some((target, settings)) = self.ssh_target(&host, tailscale.as_ref(), &sink)? {
            let script = script(true);
            return self.connect_ssh(host, target, settings, nonce, script, bootstrap, sink);
        }
        // WSL and local shells need no terminal for the helper: plain pipes
        // carry binary frames. The system OpenSSH client needs a terminal for
        // its own prompts, so it keeps the text protocol.
        let piped = matches!(host.kind.as_str(), "wsl" | "local");
        let script = script(piped);
        let (pty, reader, bootstrap) = if piped {
            let (pty, reader) = spawn_piped(pipe_command(&host, &script)?, &host.id, &sink)?;
            (pty, reader, bootstrap)
        } else {
            let (pty, reader) = spawn(command(
                &host,
                &boot_command(Boot::TerminalControl, &nonce),
            )?)?;
            (pty, reader, bootstrap.after_script(&nonce, &script))
        };
        let conn = Arc::new(Connection {
            pty,
            pending: Mutex::new(HashMap::new()),
            ready: Mutex::new(false),
            browser: crate::browser::Requests::new(nonce.clone()),
            prompts: None,
            binary: piped,
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
        if !conn.pty.active.load(Ordering::Acquire) || !*lock(&conn.ready) {
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
                c.browser.nonce == nonce && c.pty.active.load(Ordering::Acquire) && *lock(&c.ready)
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
        if !*lock(&c.ready) {
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
        lock(&self.native_hosts).remove(id);
        let removed = lock(&self.connections).remove(id);
        if let Some(c) = removed {
            c.pty.active.store(false, Ordering::Release);
            *lock(&c.ready) = false;
            for (_, tx) in lock(&c.pending).drain() {
                let _ = tx.send(Err("Host disconnected".into()));
            }
            c.pty.io.kill();
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
