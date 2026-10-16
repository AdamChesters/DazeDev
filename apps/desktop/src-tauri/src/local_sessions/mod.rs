//! Local session ownership is independent of each attached desktop/console view.
#[cfg(windows)]
mod console;
mod host;
pub(crate) mod ipc;
#[cfg(not(windows))]
use portable_pty::ChildKiller;
#[cfg(windows)]
pub(crate) mod job;
mod launch;
mod stream;
use crate::sync::LockExt;
use base64::{engine::general_purpose::STANDARD, Engine};
pub use ipc::{Error as SessionError, ErrorKind as SessionErrorKind};
use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc, Arc, Condvar, Mutex,
    },
    time::{Duration, Instant},
};
pub use stream::{InputChannel, ViewStream, ViewUpdate};
/// Wire protocol version. Each version listens on its own pipe.
pub const PROTOCOL: u64 = 2;
/// The first broker protocol, which has no hello or streams.
const LEGACY_PROTOCOL: u64 = 1;
pub(super) const SESSION_UNAVAILABLE: &str =
    "Session ended or unavailable; create a new session explicitly";
const SESSION_ENDED: &str = "PowerShell session has ended";
/// Request workers. More connections wait in a queue instead of being dropped.
const WORKERS: usize = 24;
const QUEUE: usize = 64;
/// Persistent view and input pipes, each served by its own thread.
const STREAMS: usize = 128;
/// The broker exits after this long with no sessions and no clients.
const IDLE_EXIT: Duration = Duration::from_secs(600);
const HISTORY: usize = 512 * 1024;
struct Output {
    parser: vt100::Parser,
    seq: u64,
    chunks: VecDeque<(u64, Vec<u8>)>,
    bytes: usize,
}
// The broker owns cursor queries: snapshots omit them, and multiple views must
// never send competing replies. Keep a partial query across pipe reads.
#[derive(Default)]
struct CursorQueries {
    pending: Vec<u8>,
}
impl CursorQueries {
    fn filter(&mut self, bytes: &[u8]) -> (Vec<u8>, usize) {
        const QUERY: &[u8] = b"\x1b[6n";
        let mut output = Vec::with_capacity(bytes.len());
        let mut queries = 0;
        for &byte in bytes {
            self.pending.push(byte);
            while !QUERY.starts_with(&self.pending) {
                output.push(self.pending.remove(0));
            }
            if self.pending == QUERY {
                queries += 1;
                self.pending.clear();
            }
        }
        (output, queries)
    }
}
struct Session {
    info: Mutex<Value>,
    master: Mutex<Box<dyn MasterPty + Send>>,
    /// All PTY input goes through one writer thread, so no lock is held
    /// during a blocking write and output reading never waits on input.
    input: mpsc::SyncSender<Vec<u8>>,
    #[cfg(not(windows))]
    killer: Mutex<Box<dyn ChildKiller + Send + Sync>>,
    output: Mutex<Output>,
    /// Signalled when output arrives or the shell exits.
    changed: Condvar,
    alive: AtomicBool,
    #[cfg(windows)]
    job: job::Job,
    keep: AtomicBool,
    viewers: Mutex<HashMap<String, Instant>>,
    created: Instant,
}
impl Session {
    fn write(&self, bytes: Vec<u8>) -> Result<(), String> {
        if !self.alive.load(Ordering::Acquire) {
            return Err(SESSION_ENDED.into());
        }
        self.input
            .send(bytes)
            .map_err(|_| SESSION_ENDED.to_string())
    }
    /// Output since `since`, or a full screen when the viewer is too far behind.
    fn snapshot(&self, since: Option<u64>) -> Value {
        let o = self.output.locked();
        self.snapshot_locked(&o, since)
    }
    fn snapshot_locked(&self, o: &Output, since: Option<u64>) -> Value {
        let reset = since.is_none()
            || since.is_some_and(|n| {
                n > o.seq || o.chunks.front().is_some_and(|(first, _)| n + 1 < *first)
            });
        let bytes = if reset {
            o.parser.screen().state_formatted()
        } else {
            o.chunks
                .iter()
                .filter(|(seq, _)| *seq > since.unwrap())
                .flat_map(|(_, b)| b.iter().copied())
                .collect()
        };
        json!({"data":STANDARD.encode(bytes),"seq":o.seq,"reset":reset,"running":self.alive.load(Ordering::Acquire),"pid":self.info.locked()["pid"]})
    }
    fn input_base64(&self, data: &str) -> Result<(), String> {
        let bytes = STANDARD.decode(data).map_err(|e| e.to_string())?;
        if bytes.len() > 65536 {
            return Err("Input too large".into());
        }
        self.write(bytes)
    }
    fn resize(&self, cols: Option<u64>, rows: Option<u64>) -> Result<(), String> {
        let rows = rows.unwrap_or(30).clamp(1, 200) as u16;
        let cols = cols.unwrap_or(120).clamp(1, 300) as u16;
        self.master
            .locked()
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())?;
        self.output
            .locked()
            .parser
            .screen_mut()
            .set_size(rows, cols);
        Ok(())
    }
    fn touch(&self, viewer: &str) -> Result<(), String> {
        let mut views = self.viewers.locked();
        if views.len() >= 32 && !views.contains_key(viewer) {
            return Err("Too many attached views".into());
        }
        views.insert(viewer.into(), Instant::now());
        Ok(())
    }
    fn descriptor(&self) -> Value {
        let mut v = self.info.locked().clone();
        v["running"] = json!(self.alive.load(Ordering::Acquire));
        v["attached"] = json!(self
            .viewers
            .locked()
            .keys()
            .filter(|k| !k.starts_with("ack:"))
            .count());
        v["keepRunning"] = json!(self.keep.load(Ordering::Acquire));
        v
    }
    fn stop(&self) -> Result<(), String> {
        #[cfg(windows)]
        {
            self.job.stop()
        }
        #[cfg(not(windows))]
        {
            self.killer.locked().kill().map_err(|e| e.to_string())
        }
    }
}
struct Server {
    root: PathBuf,
    sessions: Mutex<HashMap<String, Arc<Session>>>,
    files: Mutex<host::Files>,
    profiles: Mutex<()>,
    /// Identifies the broker build; clients restart an idle broker that differs.
    build: String,
    /// Open connections, including persistent streams.
    clients: AtomicUsize,
    streams: AtomicUsize,
    activity: Mutex<Instant>,
    exiting: AtomicBool,
}
impl Server {
    fn new(root: &Path, build: String) -> Self {
        Self {
            root: root.into(),
            sessions: Mutex::new(HashMap::new()),
            files: Mutex::new(host::Files::default()),
            profiles: Mutex::new(()),
            build,
            clients: AtomicUsize::new(0),
            streams: AtomicUsize::new(0),
            activity: Mutex::new(Instant::now()),
            exiting: AtomicBool::new(false),
        }
    }
    /// Sessions whose shell is still running.
    fn live_sessions(&self) -> usize {
        self.sessions
            .locked()
            .values()
            .filter(|s| s.alive.load(Ordering::Acquire))
            .count()
    }
    fn idle_for(&self, limit: Duration) -> bool {
        self.live_sessions() == 0
            && self.clients.load(Ordering::Acquire) == 0
            && self.activity.locked().elapsed() >= limit
    }
}
fn required<'a>(v: &'a Value, k: &str) -> Result<&'a str, String> {
    v[k].as_str()
        .filter(|s| !s.contains('\0'))
        .ok_or_else(|| format!("Missing or invalid {k}"))
}
fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 80
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
}
#[cfg(all(windows, test))]
fn ps_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}
pub fn shell_path(shell: &str) -> Result<PathBuf, String> {
    #[cfg(windows)]
    {
        match shell {
            "powershell" => {
                let p = PathBuf::from(
                    std::env::var_os("SystemRoot").ok_or("Windows folder is unavailable")?,
                )
                .join("System32/WindowsPowerShell/v1.0/powershell.exe");
                if p.is_file() {
                    return Ok(p);
                }
            }
            "pwsh" => {
                if let Some(p) = host::executable("pwsh.exe") {
                    return Ok(p);
                }
            }
            _ => return Err("Choose Windows PowerShell or installed PowerShell 7".into()),
        }
        Err("Selected PowerShell is not installed".into())
    }
    #[cfg(not(windows))]
    {
        if shell == "test-sh" {
            Ok("/bin/sh".into())
        } else {
            Err("Native PowerShell sessions require Windows".into())
        }
    }
}
impl Server {
    fn session(&self, host: &str, a: &Value) -> Result<Arc<Session>, String> {
        let id = required(a, "id")?;
        let s = self
            .sessions
            .locked()
            .get(id)
            .cloned()
            .ok_or(SESSION_UNAVAILABLE)?;
        let info = s.info.locked();
        if info["hostId"] != host {
            return Err("Session belongs to another local host".into());
        }
        if let Some(stamp) = a["stamp"].as_str() {
            if info["stamp"] != stamp {
                return Err("Session identity changed".into());
            }
        }
        drop(info);
        Ok(s)
    }
    fn list(&self, host: &str) -> Value {
        json!(self
            .sessions
            .locked()
            .values()
            .filter(|s| s.info.locked()["hostId"] == host)
            .map(|s| s.descriptor())
            .collect::<Vec<_>>())
    }
    fn create(&self, host: &str, a: &Value) -> Result<Value, String> {
        let name = required(a, "name")?;
        if !valid_name(name) {
            return Err("Use up to 80 letters, numbers, underscores or hyphens".into());
        }
        let operation = required(a, "operation")?;
        let mut sessions = self.sessions.locked();
        if let Some(s) = sessions.values().find(|s| {
            let i = s.info.locked();
            i["hostId"] == host && i["operation"] == operation
        }) {
            return Ok(s.descriptor());
        }
        if sessions.len() >= 32 {
            return Err(
                "End and remove an existing session before creating more (limit 32)".into(),
            );
        }
        if sessions.values().any(|s| {
            let i = s.info.locked();
            i["hostId"] == host && i["name"] == name
        }) {
            return Err("A session with that name already exists".into());
        }
        let shell = a["shell"].as_str().unwrap_or("powershell");
        let executable = shell_path(shell)?;
        let directory = host::directory(a["path"].as_str().unwrap_or(""))?;
        let mut command = CommandBuilder::new(executable);
        command.cwd(&directory);
        command.env("TERM", "xterm-256color");
        command.env("COLORTERM", "truecolor");
        let agent = a["agent"].as_str().unwrap_or("shell");
        #[cfg(windows)]
        {
            command.arg("-NoLogo");
            if !a["loadProfile"].as_bool().unwrap_or(true) {
                command.arg("-NoProfile")
            }
            if agent != "shell" {
                if !["codex", "claude", "gemini", "opencode"].contains(&agent) {
                    return Err("Unsupported local agent".into());
                }
                let agent_executable = host::agent_path(agent).ok_or_else(|| format!("{agent} is not installed on Windows. Open a PowerShell session and install/sign in there first."))?;
                if !a["model"].as_str().unwrap_or("").is_empty()
                    || !a["effort"].as_str().unwrap_or("").is_empty()
                {
                    return Err("Set local agent model and effort in its own interface".into());
                }
                let brief = a["brief"].as_str().unwrap_or("");
                if brief.contains('\0') || brief.len() > 24000 {
                    return Err("The orientation prompt is too long for a PowerShell launch. Start without it and paste it using Repo.".into());
                }
                let launch = launch::resolve(&agent_executable, || host::executable("node.exe"));
                if launch.batch && !launch::batch_safe(brief) {
                    return Err(format!("{agent} is installed as a batch file that cannot take this prompt safely. Start without it and paste it using Repo."));
                }
                if !brief.is_empty() {
                    command.env(launch::BRIEF_VARIABLE, brief);
                }
                let script = launch::script(&launch, !brief.is_empty());
                let utf16: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
                let encoded = STANDARD.encode(utf16);
                command.args(["-NoExit", "-EncodedCommand", &encoded]);
            }
        }
        #[cfg(not(windows))]
        {
            let _ = agent;
            command.arg("-i");
        }
        let pair = native_pty_system()
            .openpty(PtySize {
                rows: 30,
                cols: 120,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())?;
        let mut child = pair
            .slave
            .spawn_command(command)
            .map_err(|e| e.to_string())?;
        drop(pair.slave);
        let pid = child
            .process_id()
            .ok_or("PowerShell process ID unavailable")?;
        #[cfg(windows)]
        let job = match job::Job::assign(pid) {
            Ok(job) => job,
            Err(e) => {
                let _ = child.kill();
                return Err(e);
            }
        };
        let mut reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
        let mut writer = pair.master.take_writer().map_err(|e| e.to_string())?;
        let (input, pending) = mpsc::sync_channel::<Vec<u8>>(256);
        std::thread::spawn(move || {
            for bytes in pending {
                if writer
                    .write_all(&bytes)
                    .and_then(|_| writer.flush())
                    .is_err()
                {
                    break;
                }
            }
        });
        let id = uuid::Uuid::new_v4().to_string();
        let stamp = uuid::Uuid::new_v4().to_string();
        let s = Arc::new(Session {
            #[cfg(windows)]
            job,
            info: Mutex::new(
                json!({"id":id,"stamp":stamp,"hostId":host,"operation":operation,"name":name,"path":directory,"shell":shell,"agent":agent,"pid":pid,"windows":1}),
            ),
            master: Mutex::new(pair.master),
            input,
            #[cfg(not(windows))]
            killer: Mutex::new(child.clone_killer()),
            output: Mutex::new(Output {
                parser: vt100::Parser::new(30, 120, 1000),
                seq: 0,
                chunks: VecDeque::new(),
                bytes: 0,
            }),
            changed: Condvar::new(),
            alive: AtomicBool::new(true),
            keep: AtomicBool::new(a["keepRunning"].as_bool().unwrap_or(true)),
            viewers: Mutex::new(HashMap::new()),
            created: Instant::now(),
        });
        let reading = s.clone();
        std::thread::spawn(move || {
            let mut bytes = [0; 16384];
            let mut queries = CursorQueries::default();
            while let Ok(n) = reader.read(&mut bytes) {
                if n == 0 {
                    break;
                }
                let (bytes, count) = queries.filter(&bytes[..n]);
                let mut o = reading.output.locked();
                o.parser.process(&bytes);
                if count > 0 {
                    let (row, column) = o.parser.screen().cursor_position();
                    let reply = format!("\x1b[{};{}R", row + 1, column + 1);
                    // Never wait on input here: a full input queue must not
                    // stop output from draining.
                    let _ = reading.input.try_send(reply.repeat(count).into_bytes());
                }
                o.seq += 1;
                let seq = o.seq;
                o.bytes += bytes.len();
                o.chunks.push_back((seq, bytes));
                while o.bytes > HISTORY {
                    if let Some((_, b)) = o.chunks.pop_front() {
                        o.bytes -= b.len()
                    } else {
                        break;
                    }
                }
                drop(o);
                reading.changed.notify_all();
            }
        });
        let waiting = s.clone();
        std::thread::spawn(move || {
            let _ = child.wait();
            waiting.alive.store(false, Ordering::Release);
            let _guard = waiting.output.locked();
            waiting.changed.notify_all();
        });
        sessions.insert(id, s.clone());
        Ok(s.descriptor())
    }
    fn request(&self, host: &str, method: &str, a: &Value) -> Result<Value, String> {
        uuid::Uuid::parse_str(host).map_err(|_| "Invalid native host identity")?;
        match method {
            "ping" => Ok(
                json!({"protocol":PROTOCOL,"machine":"native-windows","user":std::env::var("USERNAME").unwrap_or_default()}),
            ),
            "hello" => Ok(json!({
                "protocol": PROTOCOL,
                "build": self.build,
                "sessions": self.live_sessions(),
                "features": ["view_stream", "input_stream"],
            })),
            "exit" => {
                // Only an empty broker may leave; live sessions keep it running.
                if self.live_sessions() > 0 {
                    return Err("Sessions are still running".into());
                }
                self.exiting.store(true, Ordering::Release);
                Ok(Value::Null)
            }
            "describe" => Ok(self.session(host, a)?.descriptor()),
            "create" => self.create(host, a),
            "sessions" => Ok(self.list(host)),
            "rename" => {
                let n = required(a, "name")?;
                if !valid_name(n) {
                    return Err("Invalid session name".into());
                }
                let sessions = self.sessions.locked();
                if sessions.values().any(|s| {
                    let info = s.info.locked();
                    info["hostId"] == host && info["name"] == n && info["id"] != a["id"]
                }) {
                    return Err("A session with that name already exists".into());
                }
                drop(sessions);
                let s = self.session(host, a)?;
                s.info.locked()["name"] = json!(n);
                Ok(self.list(host))
            }
            "destroy" => {
                // A stale saved pane may outlive its broker session. Ending it
                // again must still allow the desktop to remove that pane.
                if !self.sessions.locked().contains_key(required(a, "id")?) {
                    return Ok(self.list(host));
                }
                let s = self.session(host, a)?;
                s.stop()?;
                self.sessions.locked().remove(required(a, "id")?);
                Ok(self.list(host))
            }
            "session_agent" => Ok(self.session(host, a)?.info.locked()["agent"].clone()),
            "keep" => {
                let s = self.session(host, a)?;
                s.keep.store(true, Ordering::Release);
                Ok(s.descriptor())
            }
            "view_detach" => {
                let s = self.session(host, a)?;
                {
                    let viewer = required(a, "viewer")?;
                    let mut views = s.viewers.locked();
                    views.remove(viewer);
                    views.remove(&format!("ack:{viewer}"));
                }
                // Wake a streaming view so it sees the detach now.
                drop(s.output.locked());
                s.changed.notify_all();
                if !s.keep.load(Ordering::Acquire) && s.viewers.locked().is_empty() {
                    s.stop()?
                }
                Ok(Value::Null)
            }
            "view_poll" => {
                let s = self.session(host, a)?;
                s.touch(required(a, "viewer")?)?;
                Ok(s.snapshot(a["since"].as_u64()))
            }
            "view_attach_ack" => {
                let s = self.session(host, a)?;
                s.viewers
                    .locked()
                    .insert(format!("ack:{}", required(a, "viewer")?), Instant::now());
                Ok(Value::Null)
            }
            "view_ready" => {
                let s = self.session(host, a)?;
                let ready = s
                    .viewers
                    .locked()
                    .get(&format!("ack:{}", required(a, "viewer")?))
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(3));
                Ok(json!(ready))
            }
            "input" => {
                let s = self.session(host, a)?;
                s.input_base64(required(a, "data")?)?;
                Ok(Value::Null)
            }
            "resize" => {
                let s = self.session(host, a)?;
                s.resize(a["cols"].as_u64(), a["rows"].as_u64())?;
                Ok(Value::Null)
            }
            "session_context" => {
                let s = self.session(host, a)?;
                let i = s.info.locked();
                Ok(json!(format!(
                    "{}:native:native:{}:powershell:0",
                    i["id"].as_str().unwrap_or(""),
                    i["pid"]
                )))
            }
            "terminal_status" => {
                let pid = a["pid"].as_u64();
                let sessions = self.sessions.locked();
                let s = sessions.values().find(|s| {
                    let i = s.info.locked();
                    i["hostId"] == host && i["pid"].as_u64() == pid
                });
                Ok(
                    json!({"attachment":if s.is_some_and(|s|s.alive.load(Ordering::Acquire)){"native"}else{"unknown"},"nestedSsh":false,"hostname":null}),
                )
            }
            _ => host::request(self, host, method, a),
        }
    }
}
pub(super) fn error_reply(error: &str) -> Value {
    let code = if error == SESSION_UNAVAILABLE || error == SESSION_ENDED {
        "ended"
    } else {
        "failed"
    };
    json!({"error":error,"code":code})
}
/// Send one reply and keep the pipe open until the client has read it.
pub(super) fn reply(pipe: &mut ipc::Stream, result: Result<Value, String>) {
    let message = match result {
        Ok(v) => json!({ "result": v }),
        Err(e) => error_reply(&e),
    };
    if ipc::send(pipe, &message).is_ok() {
        // Closing a Windows pipe discards unread bytes. Keep the server
        // handle until the client confirms it consumed the whole reply.
        let mut acknowledgment = [0];
        let _ = pipe.read_exact(&mut acknowledgment);
    }
}
pub(super) fn parse_reply(reply: Value) -> Result<Value, SessionError> {
    if let Some(e) = reply["error"].as_str() {
        let ended = reply["code"] == "ended" || e == SESSION_UNAVAILABLE || e == SESSION_ENDED;
        let kind = if ended {
            SessionErrorKind::Ended
        } else {
            SessionErrorKind::Failed
        };
        Err(SessionError::new(kind, e))
    } else {
        Ok(reply["result"].clone())
    }
}
/// Counts a connection as a client while it is open.
struct Connection<'a>(&'a Server);
impl<'a> Connection<'a> {
    fn new(server: &'a Server) -> Self {
        server.clients.fetch_add(1, Ordering::AcqRel);
        *server.activity.locked() = Instant::now();
        Self(server)
    }
}
impl Drop for Connection<'_> {
    fn drop(&mut self) {
        *self.0.activity.locked() = Instant::now();
        self.0.clients.fetch_sub(1, Ordering::AcqRel);
    }
}
type Exit = Arc<dyn Fn() + Send + Sync>;
fn handle(server: &Arc<Server>, mut pipe: ipc::Stream, exit: &Exit) {
    let _connection = Connection::new(server);
    let Ok(v) = ipc::receive(&mut pipe) else {
        return;
    };
    let target = (|| {
        if v["protocol"] != PROTOCOL {
            return Err("Session protocol mismatch; existing sessions preserved".to_string());
        }
        Ok((required(&v, "host")?, required(&v, "method")?))
    })();
    if let Ok((host, method @ ("view_stream" | "input_stream"))) = target {
        if server.streams.fetch_add(1, Ordering::AcqRel) >= STREAMS {
            server.streams.fetch_sub(1, Ordering::AcqRel);
            return reply(&mut pipe, Err("Too many open session views".into()));
        }
        let (server, host, method) = (server.clone(), host.to_string(), method.to_string());
        std::thread::spawn(move || {
            let _connection = Connection::new(&server);
            stream::serve(&server, &host, &method, &v["args"], pipe);
            server.streams.fetch_sub(1, Ordering::AcqRel);
        });
        return;
    }
    let result = target.and_then(|(host, method)| server.request(host, method, &v["args"]));
    reply(&mut pipe, result);
    if server.exiting.load(Ordering::Acquire) {
        exit();
    }
}
pub fn serve(root: &Path) -> Result<(), String> {
    serve_with(root, IDLE_EXIT, Arc::new(|| std::process::exit(0)))
}
fn serve_with(root: &Path, idle: Duration, exit: Exit) -> Result<(), String> {
    crate::private_storage::directory(root).map_err(|e| e.to_string())?;
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(root.join(format!("broker-v{PROTOCOL}.lock")))
        .map_err(|e| e.to_string())?;
    if lock.try_lock().is_err() {
        return Ok(());
    }
    let _runtime = hold_runtime(root);
    let server = Arc::new(Server::new(root, build_id(root)));
    let reap = server.clone();
    let idle_exit = exit.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(2));
        for s in reap.sessions.locked().values() {
            let mut v = s.viewers.locked();
            v.retain(|_, t| t.elapsed() < Duration::from_secs(20));
            if v.is_empty()
                && !s.keep.load(Ordering::Acquire)
                && s.created.elapsed() > Duration::from_secs(30)
            {
                let _ = s.stop();
            }
        }
        if reap.idle_for(idle) {
            idle_exit();
            return;
        }
    });
    // A fixed pool serves requests. When it is busy, new connections wait in
    // the queue, and then in the pipe itself, instead of being dropped.
    let (queue, waiting) = mpsc::sync_channel::<ipc::Stream>(QUEUE);
    let waiting = Arc::new(Mutex::new(waiting));
    for _ in 0..WORKERS {
        let (waiting, server, exit) = (waiting.clone(), server.clone(), exit.clone());
        std::thread::spawn(move || loop {
            let next = waiting.locked().recv();
            match next {
                Ok(pipe) => handle(&server, pipe, &exit),
                Err(_) => return,
            }
        });
    }
    ipc::listen(root, move |pipe| {
        let _ = queue.send(pipe);
    })
}
/// The runtime folder this broker runs from, if it is a cached runtime.
fn runtime_dir(root: &Path) -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?.canonicalize().ok()?;
    let dir = exe.parent()?;
    let cache = root.join("runtime").canonicalize().ok()?;
    (dir.parent()? == cache).then(|| dir.to_path_buf())
}
fn build_id(root: &Path) -> String {
    runtime_dir(root)
        .and_then(|dir| Some(dir.file_name()?.to_string_lossy().into_owned()))
        .unwrap_or_else(|| format!("dev-{}", env!("CARGO_PKG_VERSION")))
}
const IN_USE: &str = "in-use.lock";
/// A shared lock tells pruning that a broker still runs from this folder.
fn hold_runtime(root: &Path) -> Option<fs::File> {
    let file = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(runtime_dir(root)?.join(IN_USE))
        .ok()?;
    file.try_lock_shared().ok()?;
    Some(file)
}
#[cfg_attr(not(windows), allow(dead_code))]
/// Keep the current runtime and the newest other one. Skip any folder a
/// running broker uses.
fn prune_runtimes(cache: &Path, current: &str) {
    let Ok(entries) = fs::read_dir(cache) else {
        return;
    };
    let mut others: Vec<(std::time::SystemTime, PathBuf)> = entries
        .flatten()
        .filter(|e| e.file_name() != current)
        .filter_map(|e| {
            let meta = e.path().symlink_metadata().ok()?;
            meta.is_dir()
                .then(|| (meta.modified().unwrap_or(std::time::UNIX_EPOCH), e.path()))
        })
        .collect();
    others.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, dir) in others.into_iter().skip(1) {
        let lock = dir.join(IN_USE);
        let guard = match fs::OpenOptions::new().write(true).open(&lock) {
            Ok(file) => match file.try_lock() {
                Ok(()) => Some(file),
                Err(_) => continue,
            },
            Err(_) => None,
        };
        // Renaming fails on Windows while a program inside is running, which
        // also protects runtimes from brokers that predate the lock file.
        let doomed = dir.with_extension(format!("old-{}", uuid::Uuid::new_v4().simple()));
        drop(guard);
        if fs::rename(&dir, &doomed).is_ok() {
            let _ = fs::remove_dir_all(&doomed);
        }
    }
}
fn routes() -> &'static Mutex<HashMap<(PathBuf, String), u64>> {
    static ROUTES: std::sync::OnceLock<Mutex<HashMap<(PathBuf, String), u64>>> =
        std::sync::OnceLock::new();
    ROUTES.get_or_init(Default::default)
}
#[derive(Clone)]
pub struct Client {
    pub root: PathBuf,
    pub host: String,
    pub shell: String,
}
impl Client {
    fn version(&self) -> u64 {
        routes()
            .locked()
            .get(&(self.root.clone(), self.host.clone()))
            .copied()
            .unwrap_or(PROTOCOL)
    }
    /// True while this host's sessions still live in an older broker.
    pub fn legacy(&self) -> bool {
        self.version() != PROTOCOL
    }
    pub fn request(&self, method: &str, args: Value) -> Result<Value, String> {
        self.call(method, args).map_err(String::from)
    }
    /// Like `request`, with an error kind for ended sessions, a missing
    /// broker and timeouts.
    pub fn call(&self, method: &str, mut args: Value) -> Result<Value, SessionError> {
        if method == "create" && !args["shell"].is_string() {
            args["shell"] = json!(self.shell);
        }
        let version = self.version();
        let mut pipe = self.connect(method)?;
        ipc::send(
            &mut pipe,
            &json!({"protocol":version,"host":self.host,"method":method,"args":args}),
        )?;
        let reply = ipc::receive(&mut pipe)?;
        pipe.write_all(&[6]).map_err(SessionError::from)?;
        let mut result = parse_reply(reply)?;
        if method == "ping" && version != PROTOCOL {
            result["upgradePending"] = json!(true);
            result["notice"] = json!("PowerShell sessions from an earlier DazeDev are still running. End them and reconnect to finish the upgrade.");
        }
        Ok(result)
    }
    fn connect(&self, method: &str) -> Result<ipc::Stream, SessionError> {
        let version = self.version();
        match ipc::connect_version(&self.root, version) {
            // A broker that exited while idle had no sessions; start it again.
            Err(e)
                if e.kind == SessionErrorKind::Unavailable
                    && version == PROTOCOL
                    && cfg!(windows)
                    && !matches!(method, "ping" | "hello" | "exit") =>
            {
                self.start_broker()?;
                ipc::connect_version(&self.root, version)
            }
            result => result,
        }
    }
    /// Live sessions for this host in a broker from an earlier protocol.
    fn legacy_sessions(&self) -> Option<usize> {
        let mut pipe = ipc::connect_version(&self.root, LEGACY_PROTOCOL).ok()?;
        ipc::send(
            &mut pipe,
            &json!({"protocol":LEGACY_PROTOCOL,"host":self.host,"method":"sessions","args":{}}),
        )
        .ok()?;
        let reply = ipc::receive(&mut pipe).ok()?;
        let _ = pipe.write_all(&[6]);
        let sessions = parse_reply(reply).ok()?;
        Some(
            sessions
                .as_array()?
                .iter()
                .filter(|s| s["running"] != false)
                .count(),
        )
    }
    fn hello(&self) -> Result<Value, SessionError> {
        let mut pipe = ipc::connect_version(&self.root, PROTOCOL)?;
        ipc::send(
            &mut pipe,
            &json!({"protocol":PROTOCOL,"host":self.host,"method":"hello","args":{}}),
        )?;
        let reply = ipc::receive(&mut pipe)?;
        let _ = pipe.write_all(&[6]);
        parse_reply(reply)
    }
    /// Connect to the broker, starting or replacing it as needed:
    /// - an older broker still running this host's sessions keeps serving
    ///   them; `ping` then reports `upgradePending`;
    /// - a broker of this protocol is used, unless it is a different build
    ///   with no sessions, which is asked to exit and replaced.
    pub fn ensure(root: PathBuf, host: String) -> Result<Self, String> {
        let c = Self {
            root,
            host,
            shell: "powershell".into(),
        };
        let key = (c.root.clone(), c.host.clone());
        if c.legacy_sessions().is_some_and(|n| n > 0) {
            routes().locked().insert(key, LEGACY_PROTOCOL);
            return Ok(c);
        }
        routes().locked().remove(&key);
        if let Ok(info) = c.hello() {
            let wanted = c.wanted_build();
            let same = wanted.as_deref().is_none_or(|build| info["build"] == build);
            if same || info["sessions"].as_u64().unwrap_or(0) > 0 {
                return Ok(c);
            }
            let _ = c.call("exit", json!({}));
            let gone = Instant::now();
            while c.hello().is_ok() && gone.elapsed() < Duration::from_secs(5) {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        c.start_broker()?;
        Ok(c)
    }
    /// The build this client would start, if it starts cached runtimes.
    fn wanted_build(&self) -> Option<String> {
        #[cfg(windows)]
        {
            let exe = runtime_exe(&self.root).ok()?;
            Some(exe.parent()?.file_name()?.to_string_lossy().into_owned())
        }
        #[cfg(not(windows))]
        {
            None
        }
    }
    fn start_broker(&self) -> Result<(), SessionError> {
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let executable = runtime_exe(&self.root)
                .map_err(|e| SessionError::new(SessionErrorKind::Unavailable, e))?;
            let started = Instant::now();
            let mut spawned = None;
            while started.elapsed() < Duration::from_secs(5) {
                if self.hello().is_ok() {
                    return Ok(());
                }
                // Spawn again if an exiting broker still held the lock.
                if spawned.is_none_or(|at: Instant| at.elapsed() > Duration::from_secs(1)) {
                    std::process::Command::new(&executable)
                        .args(["serve", &self.root.to_string_lossy()])
                        .creation_flags(0x08000000 | 0x00000200)
                        .stdin(std::process::Stdio::null())
                        .stdout(std::process::Stdio::null())
                        .stderr(std::process::Stdio::null())
                        .spawn()
                        .map_err(SessionError::from)?;
                    spawned = Some(Instant::now());
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(SessionError::new(
                SessionErrorKind::Unavailable,
                "Local session manager did not start",
            ))
        }
        #[cfg(not(windows))]
        {
            Err(SessionError::new(
                SessionErrorKind::Unavailable,
                "Native PowerShell requires Windows",
            ))
        }
    }
    pub fn external(&self, id: &str) -> Result<(), String> {
        #[cfg(windows)]
        {
            console::open_external(self, id)
        }
        #[cfg(not(windows))]
        {
            let _ = id;
            Err("External PowerShell requires Windows".into())
        }
    }
}
#[cfg(windows)]
fn runtime_exe(root: &Path) -> Result<PathBuf, String> {
    use sha2::{Digest, Sha256};
    let app = crate::platform::portable_root(&std::env::current_exe().map_err(|e| e.to_string())?);
    let source = app.join("runtime/DazeDevSession.exe");
    let bytes = fs::read(&source)
        .map_err(|_| "DazeDevSession.exe is missing. Extract the complete portable package.")?;
    let mut files = vec![(std::ffi::OsString::from("DazeDevSession.exe"), bytes)];
    for entry in fs::read_dir(&app).map_err(|e| e.to_string())? {
        let path = entry.map_err(|e| e.to_string())?.path();
        if path
            .extension()
            .is_some_and(|s| s.eq_ignore_ascii_case("dll"))
        {
            files.push((
                path.file_name().unwrap().to_owned(),
                fs::read(&path).map_err(|e| e.to_string())?,
            ));
        }
    }
    files.sort_by(|a, b| a.0.cmp(&b.0));
    let mut hash = Sha256::new();
    for (name, bytes) in &files {
        hash.update(name.to_string_lossy().as_bytes());
        hash.update(Sha256::digest(bytes));
    }
    let name = format!("{:x}", hash.finalize());
    let dir = root.join("runtime").join(&name);
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    for (name, bytes) in &files {
        let target = dir.join(name);
        if target.exists() {
            if fs::read(&target).map_err(|e| e.to_string())? != *bytes {
                return Err("Cached session runtime differs from the installed package; existing sessions preserved".into());
            }
        } else {
            let temp = dir.join(format!("{}.tmp", uuid::Uuid::new_v4()));
            fs::write(&temp, bytes).map_err(|e| e.to_string())?;
            fs::rename(temp, target).map_err(|e| e.to_string())?;
        }
    }
    prune_runtimes(&root.join("runtime"), &name);
    let dest = dir.join("DazeDevSession.exe");
    Ok(dest)
}
pub fn attach_console(c: Client, id: String, viewer: String) -> Result<(), String> {
    #[cfg(windows)]
    {
        console::attach(c, id, viewer)
    }
    #[cfg(not(windows))]
    {
        let _ = (c, id, viewer);
        Err("Console attachment requires Windows".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (Server, String) {
        let root =
            std::env::temp_dir().join(format!("dazedev-native-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        (
            Server::new(&root, "test".into()),
            uuid::Uuid::new_v4().to_string(),
        )
    }
    #[test]
    fn ending_stale_sessions_preserves_other_sessions_and_identity_checks() {
        let (server, host) = fixture();
        let live = server
            .request(
                &host,
                "create",
                &json!({
                    "name":"live", "operation":"end-regression",
                    "shell":if cfg!(windows){"powershell"}else{"test-sh"}, "loadProfile":false
                }),
            )
            .unwrap();
        let id = &live["id"];
        assert_eq!(
            server
                .request(&host, "destroy", &json!({"id":"already-gone"}))
                .unwrap()[0]["id"],
            *id
        );
        assert!(server
            .request(&host, "destroy", &json!({"id":id,"stamp":"wrong"}))
            .is_err());
        assert!(server
            .request(
                &uuid::Uuid::new_v4().to_string(),
                "destroy",
                &json!({"id":id})
            )
            .is_err());
        assert!(server.request(&host, "destroy", &json!({})).is_err());
        assert!(server.request(&host, "describe", &json!({"id":id})).is_ok());
        server.request(&host, "destroy", &json!({"id":id})).unwrap();
        assert_eq!(
            server.request(&host, "destroy", &json!({"id":id})).unwrap(),
            json!([])
        );
        assert!(server
            .request(&host, "describe", &json!({"id":id}))
            .is_err());
        fs::remove_dir_all(server.root).unwrap();
    }
    #[test]
    fn framed_messages_reject_oversize_and_truncation() {
        let mut bytes = Vec::new();
        ipc::send(&mut bytes, &json!({"text":"hello"})).unwrap();
        assert_eq!(
            ipc::receive(&mut bytes.as_slice()).unwrap()["text"],
            "hello"
        );
        assert!(ipc::receive(&mut ((ipc::LIMIT + 1) as u32).to_le_bytes().as_slice()).is_err());
        assert!(ipc::receive(&mut &bytes[..bytes.len() - 1]).is_err());
    }
    #[test]
    fn profiles_compare_revisions_and_preserve_conflicting_changes() {
        let (server, host) = fixture();
        let profile = server.request(&host, "profile_load", &json!({})).unwrap();
        let saved = server
            .request(
                &host,
                "profile_save",
                &json!({"profile":profile,"revision":0}),
            )
            .unwrap();
        assert_eq!(saved["revision"], 1);
        assert!(server
            .request(
                &host,
                "profile_save",
                &json!({"profile":profile,"revision":0})
            )
            .unwrap_err()
            .contains("CONFLICT"));
        assert_eq!(
            server.request(&host, "profile_load", &json!({})).unwrap(),
            saved
        );
        assert!(server
            .request("../../bad", "profile_load", &json!({}))
            .is_err());
        fs::remove_dir_all(server.root).unwrap();
    }
    #[test]
    fn local_upload_is_host_scoped_ordered_and_cannot_overwrite() {
        let (server, host) = fixture();
        let started = server
            .request(
                &host,
                "upload_begin",
                &json!({"paste":true,"name":"test's image.png","size":3}),
            )
            .unwrap();
        let id = &started["id"];
        assert!(server
            .request(
                &uuid::Uuid::new_v4().to_string(),
                "upload_chunk",
                &json!({"id":id,"offset":0,"data":"YWJj"})
            )
            .is_err());
        assert!(server
            .request(
                &host,
                "upload_chunk",
                &json!({"id":id,"offset":1,"data":"YWJj"})
            )
            .is_err());
        server
            .request(
                &host,
                "upload_chunk",
                &json!({"id":id,"offset":0,"data":"YWJj"}),
            )
            .unwrap();
        fs::write(started["path"].as_str().unwrap(), b"original").unwrap();
        assert!(server
            .request(&host, "upload_finish", &json!({"id":id}))
            .is_err());
        assert_eq!(
            fs::read(started["path"].as_str().unwrap()).unwrap(),
            b"original"
        );
        server
            .request(&host, "upload_abort", &json!({"id":id}))
            .unwrap();
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn broker_ipc_receives_large_replies_before_server_disconnects() {
        let (server, _) = fixture();
        let root = server.root.clone();
        let (client, _) = broker(&root, IDLE_EXIT);
        let mut profile = client.request("profile_load", json!({})).unwrap();
        profile["testPayload"] = json!("r".repeat(180_000));
        client
            .request("profile_save", json!({"profile":profile,"revision":0}))
            .unwrap();
        let received = client.request("profile_load", json!({})).unwrap();
        assert_eq!(received["testPayload"], profile["testPayload"]);
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(unix)]
    /// Start a broker on the fixture root. Its exit hook only sets a flag.
    fn broker(root: &Path, idle: Duration) -> (Client, Arc<AtomicBool>) {
        let exited = Arc::new(AtomicBool::new(false));
        let flag = exited.clone();
        let serving = root.to_path_buf();
        std::thread::spawn(move || {
            let exit: Exit = Arc::new(move || flag.store(true, Ordering::Release));
            if let Err(error) = serve_with(&serving, idle, exit) {
                eprintln!("Broker listener failed: {error}");
            }
        });
        let client = Client {
            root: root.into(),
            host: uuid::Uuid::new_v4().to_string(),
            shell: "test-sh".into(),
        };
        let started = Instant::now();
        while let Err(error) = client.hello() {
            assert!(
                started.elapsed() < Duration::from_secs(10),
                "Broker hello failed: {error}"
            );
            std::thread::sleep(Duration::from_millis(20));
        }
        (client, exited)
    }
    #[cfg(unix)]
    fn wait_for(view: &mut ViewStream, token: &str) -> ViewUpdate {
        let start = Instant::now();
        let mut seen = String::new();
        loop {
            let update = view.next().unwrap();
            seen.push_str(&String::from_utf8_lossy(&update.data));
            if seen.contains(token) {
                return update;
            }
            assert!(start.elapsed() < Duration::from_secs(15), "missing {token}");
        }
    }
    #[cfg(unix)]
    #[test]
    fn streamed_view_receives_pushed_output_and_ends_on_detach() {
        let (server, _) = fixture();
        let (client, _) = broker(&server.root, IDLE_EXIT);
        let info = client
            .request("create", json!({"name":"stream","operation":"stream-test"}))
            .unwrap();
        let id = info["id"].as_str().unwrap();
        let mut view = client.view_stream(id, "viewer", None, None).unwrap();
        let first = view.next().unwrap();
        assert!(first.reset && first.running);
        let mut input = client.input_channel(id, None).unwrap();
        input.resize(100, 40).unwrap();
        input.send(b"echo STREAM_$((6*7))\n").unwrap();
        let shown = wait_for(&mut view, "STREAM_42");
        // Nothing new arrives: the next update is a heartbeat, not a busy loop.
        let started = Instant::now();
        let mut idle = view.next().unwrap();
        while !idle.data.is_empty() {
            idle = view.next().unwrap();
        }
        assert!(started.elapsed() >= Duration::from_secs(1));
        assert!(idle.seq >= shown.seq);
        client
            .request("view_detach", json!({"id":id,"viewer":"viewer"}))
            .unwrap();
        let started = Instant::now();
        loop {
            let update = view.next().unwrap();
            if update.detached {
                break;
            }
            assert!(started.elapsed() < Duration::from_secs(8));
        }
        assert!(started.elapsed() < Duration::from_secs(8));
        assert_eq!(view.next().unwrap_err().kind, SessionErrorKind::Ended);
        client.request("destroy", json!({"id":id})).unwrap();
        let gone = client.input_channel(id, None).err().unwrap();
        assert_eq!(gone.kind, SessionErrorKind::Ended);
        let gone = client.view_stream(id, "late", None, None).unwrap().next();
        assert_eq!(gone.unwrap_err().kind, SessionErrorKind::Ended);
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn view_stream_ends_when_the_shell_exits() {
        let (server, _) = fixture();
        let (client, _) = broker(&server.root, IDLE_EXIT);
        let info = client
            .request("create", json!({"name":"exit","operation":"exit-test"}))
            .unwrap();
        let id = info["id"].as_str().unwrap();
        let mut view = client.view_stream(id, "viewer", None, None).unwrap();
        client
            .input_channel(id, None)
            .unwrap()
            .send(b"exit\n")
            .unwrap();
        let started = Instant::now();
        while view.next().unwrap().running {
            assert!(started.elapsed() < Duration::from_secs(15));
        }
        assert_eq!(view.next().unwrap_err().kind, SessionErrorKind::Ended);
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn errors_tell_ended_sessions_from_a_missing_broker() {
        let (server, host) = fixture();
        let missing = Client {
            root: server.root.clone(),
            host,
            shell: "test-sh".into(),
        };
        assert_eq!(
            missing.call("sessions", json!({})).unwrap_err().kind,
            SessionErrorKind::Unavailable
        );
        let (client, _) = broker(&server.root, IDLE_EXIT);
        let error = client.call("describe", json!({"id":"gone"})).unwrap_err();
        assert_eq!(error.kind, SessionErrorKind::Ended);
        assert_eq!(error.message, SESSION_UNAVAILABLE);
        assert_eq!(
            client
                .call("rename", json!({"name":"bad name"}))
                .unwrap_err()
                .kind,
            SessionErrorKind::Failed
        );
        // A pipe closed mid-reply is end of file, not "no data yet".
        let (mut near, far) = ipc::Stream::pair().unwrap();
        drop(far);
        assert_eq!(
            ipc::receive(&mut near).unwrap_err().kind,
            SessionErrorKind::Unavailable
        );
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn busy_broker_queues_connections_instead_of_dropping_them() {
        let (server, _) = fixture();
        let (client, _) = broker(&server.root, IDLE_EXIT);
        // Silent connections occupy every worker.
        let silent: Vec<_> = (0..WORKERS + 6)
            .map(|_| ipc::Stream::connect(ipc::endpoint(&server.root)).unwrap())
            .collect();
        std::thread::sleep(Duration::from_millis(200));
        let callers: Vec<_> = (0..10)
            .map(|_| {
                let client = client.clone();
                std::thread::spawn(move || client.call("hello", json!({})))
            })
            .collect();
        std::thread::sleep(Duration::from_millis(300));
        drop(silent);
        for caller in callers {
            assert_eq!(caller.join().unwrap().unwrap()["protocol"], PROTOCOL);
        }
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn broker_reports_build_and_exits_only_without_sessions() {
        let (server, _) = fixture();
        let (client, exited) = broker(&server.root, IDLE_EXIT);
        let hello = client.call("hello", json!({})).unwrap();
        assert_eq!(hello["protocol"], PROTOCOL);
        assert_eq!(hello["build"], format!("dev-{}", env!("CARGO_PKG_VERSION")));
        assert_eq!(hello["sessions"], 0);
        let info = client
            .request("create", json!({"name":"held","operation":"exit-hold"}))
            .unwrap();
        assert_eq!(client.call("hello", json!({})).unwrap()["sessions"], 1);
        assert!(client.call("exit", json!({})).is_err());
        assert!(!exited.load(Ordering::Acquire));
        client.request("destroy", json!({"id":info["id"]})).unwrap();
        client.call("exit", json!({})).unwrap();
        let started = Instant::now();
        while !exited.load(Ordering::Acquire) {
            assert!(started.elapsed() < Duration::from_secs(5));
            std::thread::sleep(Duration::from_millis(10));
        }
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn idle_broker_exits_on_its_own() {
        let (server, _) = fixture();
        let (_client, exited) = broker(&server.root, Duration::from_millis(100));
        let started = Instant::now();
        while !exited.load(Ordering::Acquire) {
            assert!(started.elapsed() < Duration::from_secs(8));
            std::thread::sleep(Duration::from_millis(50));
        }
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn older_broker_with_sessions_keeps_serving_its_host() {
        let (server, host) = fixture();
        let listener =
            std::os::unix::net::UnixListener::bind(ipc::endpoint_version(&server.root, 1)).unwrap();
        std::thread::spawn(move || {
            for pipe in listener.incoming() {
                let mut pipe = pipe.unwrap();
                let request = ipc::receive(&mut pipe).unwrap();
                assert_eq!(request["protocol"], 1);
                let result = match request["method"].as_str().unwrap() {
                    "sessions" => json!([{"id":"old","running":true}]),
                    _ => json!({"protocol":1}),
                };
                ipc::send(&mut pipe, &json!({ "result": result })).unwrap();
                let _ = pipe.read_exact(&mut [0]);
            }
        });
        let client = Client::ensure(server.root.clone(), host).unwrap();
        assert!(client.legacy());
        let ping = client.request("ping", json!({})).unwrap();
        assert_eq!(ping["protocol"], 1);
        assert_eq!(ping["upgradePending"], true);
        assert!(client.input_channel("old", None).is_ok());
        fs::remove_dir_all(server.root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn current_broker_is_reused_by_ensure() {
        let (server, _) = fixture();
        let (client, _) = broker(&server.root, IDLE_EXIT);
        let ensured = Client::ensure(server.root.clone(), client.host.clone()).unwrap();
        assert!(!ensured.legacy());
        assert_eq!(
            ensured.request("ping", json!({})).unwrap()["protocol"],
            PROTOCOL
        );
        fs::remove_dir_all(server.root).unwrap();
    }
    #[test]
    fn runtime_pruning_keeps_current_previous_and_in_use() {
        let cache =
            std::env::temp_dir().join(format!("dazedev-runtime-test-{}", uuid::Uuid::new_v4()));
        let mut held = None;
        for name in ["oldest", "in-use", "older", "previous", "current"] {
            fs::create_dir_all(cache.join(name)).unwrap();
            fs::write(cache.join(name).join("DazeDevSession.exe"), b"MZ").unwrap();
            if name == "in-use" {
                let lock = fs::File::create(cache.join(name).join(IN_USE)).unwrap();
                lock.lock_shared().unwrap();
                held = Some(lock);
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        prune_runtimes(&cache, "current");
        let mut left: Vec<_> = fs::read_dir(&cache)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();
        assert_eq!(left, ["current", "in-use", "previous"]);
        drop(held);
        fs::remove_dir_all(cache).unwrap();
    }
    #[test]
    fn cursor_queries_are_owned_once_across_split_reads() {
        let mut queries = CursorQueries::default();
        assert_eq!(queries.filter(b"prompt\x1b["), (b"prompt".to_vec(), 0));
        assert_eq!(
            queries.filter(b"6n\x1b[31mred\x1b[6n"),
            (b"\x1b[31mred".to_vec(), 2)
        );
        assert_eq!(queries.filter(b"\x1b\x1b[6n"), (b"\x1b".to_vec(), 1));
    }
    #[test]
    fn real_shell_keeps_pid_state_output_and_identity_across_detach() {
        let (server, host) = fixture();
        let args = json!({"name":"persist","operation":"one","shell":if cfg!(windows){"powershell"}else{"test-sh"},"loadProfile":false});
        let info = server.request(&host, "create", &args).unwrap();
        let id = &info["id"];

        assert_eq!(
            server.request(&host, "create", &args).unwrap()["pid"],
            info["pid"]
        );
        assert!(server
            .request(&host, "describe", &json!({"id":id,"stamp":"wrong"}))
            .is_err());
        assert!(server
            .request(
                &uuid::Uuid::new_v4().to_string(),
                "describe",
                &json!({"id":id})
            )
            .is_err());
        let command = if cfg!(windows) {
            "$global:DazeToken='SAME_PROCESS_8492'; Write-Output $DazeToken\r"
        } else {
            "DAZE_TOKEN=SAME_PROCESS_8492; echo $DAZE_TOKEN\n"
        };
        server
            .request(
                &host,
                "input",
                &json!({"id":id,"data":STANDARD.encode(command)}),
            )
            .unwrap();
        let start = Instant::now();
        let sequence = loop {
            let output = server
                .request(&host, "view_poll", &json!({"id":id,"viewer":"first"}))
                .unwrap();
            let sequence = output["seq"].as_u64().unwrap();
            let bytes = STANDARD.decode(output["data"].as_str().unwrap()).unwrap();
            if String::from_utf8_lossy(&bytes).contains("SAME_PROCESS_8492") {
                break sequence;
            }
            assert!(
                start.elapsed() < Duration::from_secs(15),
                "shell output missing"
            );
            std::thread::sleep(Duration::from_millis(50));
        };
        server
            .request(&host, "view_detach", &json!({"id":id,"viewer":"first"}))
            .unwrap();
        assert_eq!(
            server
                .request(&host, "describe", &json!({"id":id}))
                .unwrap()["pid"],
            info["pid"]
        );
        let command = if cfg!(windows) {
            "Write-Output ('STATE_'+$DazeToken)\r"
        } else {
            "echo STATE_$DAZE_TOKEN\n"
        };
        server
            .request(
                &host,
                "input",
                &json!({"id":id,"data":STANDARD.encode(command)}),
            )
            .unwrap();
        let start = Instant::now();
        loop {
            let output = server
                .request(
                    &host,
                    "view_poll",
                    &json!({"id":id,"viewer":"second","since":sequence}),
                )
                .unwrap();
            let bytes = STANDARD.decode(output["data"].as_str().unwrap()).unwrap();
            if String::from_utf8_lossy(&bytes).contains("STATE_SAME_PROCESS_8492") {
                break;
            }
            assert!(start.elapsed() < Duration::from_secs(15));
            std::thread::sleep(Duration::from_millis(50));
        }
        assert_eq!(
            server
                .request(&host, "view_ready", &json!({"id":id,"viewer":"second"}))
                .unwrap(),
            false
        );
        server
            .request(
                &host,
                "view_attach_ack",
                &json!({"id":id,"viewer":"second"}),
            )
            .unwrap();
        assert_eq!(
            server
                .request(&host, "view_ready", &json!({"id":id,"viewer":"second"}))
                .unwrap(),
            true
        );
        let reset = server
            .request(
                &host,
                "view_poll",
                &json!({"id":id,"viewer":"second","since":u64::MAX}),
            )
            .unwrap();
        assert_eq!(reset["reset"], true);
        server.request(&host, "destroy", &json!({"id":id})).unwrap();
        assert!(server
            .request(&host, "describe", &json!({"id":id}))
            .is_err());
        fs::remove_dir_all(server.root).unwrap();
    }

    #[cfg(windows)]
    #[test]
    fn powershell_submits_text_followed_by_a_separate_return() {
        let (server, host) = fixture();
        struct Cleanup<'a>(&'a Server);
        impl Drop for Cleanup<'_> {
            fn drop(&mut self) {
                let sessions = self.0.sessions.lock().unwrap_or_else(|e| e.into_inner());
                for session in sessions.values() {
                    let _ = session.stop();
                }
                let _ = fs::remove_dir_all(&self.0.root);
            }
        }
        let _cleanup = Cleanup(&server);
        let info = server
            .request(
                &host,
                "create",
                &json!({"name":"separate-return","operation":"broadcast-regression","shell":"powershell","loadProfile":false}),
            )
            .unwrap();
        let id = &info["id"];
        let session = server.session(&host, &json!({"id":id})).unwrap();
        let start = Instant::now();
        loop {
            let ready = session
                .output
                .locked()
                .parser
                .screen()
                .contents()
                .lines()
                .any(|line| line.trim_start().starts_with("PS ") && line.trim_end().ends_with('>'));
            if ready {
                break;
            }
            assert!(
                start.elapsed() < Duration::from_secs(15),
                "PowerShell prompt did not become ready"
            );
            std::thread::sleep(Duration::from_millis(25));
        }
        let marker = server.root.join("submitted.txt");
        let mut expected = String::new();
        for n in 0..3 {
            let command = format!(
                "[IO.File]::AppendAllText({}, 'submission-{n};')",
                ps_quote(&marker.to_string_lossy())
            );
            // Match broadcast's separate, acknowledged text and Return writes.
            for text in [command.as_str(), "\r"] {
                server
                    .request(
                        &host,
                        "input",
                        &json!({"id":id,"data":STANDARD.encode(text)}),
                    )
                    .unwrap();
            }
            expected.push_str(&format!("submission-{n};"));
            let start = Instant::now();
            loop {
                if fs::read_to_string(&marker).is_ok_and(|value| value == expected) {
                    break;
                }
                assert!(
                    start.elapsed() < Duration::from_secs(15),
                    "Separate Return did not execute submission {n}"
                );
                std::thread::sleep(Duration::from_millis(25));
            }
        }
        assert_eq!(fs::read_to_string(marker).unwrap(), expected);
    }
}
