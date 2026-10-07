//! Local session ownership is independent of each attached desktop/console view.
#[cfg(windows)]
mod console;
mod host;
mod ipc;
#[cfg(not(windows))]
use portable_pty::ChildKiller;
#[cfg(windows)]
mod job;
use base64::{engine::general_purpose::STANDARD, Engine};
use fs2::FileExt;
use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
const PROTOCOL: u64 = 1;
pub(super) const SESSION_UNAVAILABLE: &str =
    "Session ended or unavailable; create a new session explicitly";
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
    writer: Mutex<Box<dyn Write + Send>>,
    #[cfg(not(windows))]
    killer: Mutex<Box<dyn ChildKiller + Send + Sync>>,
    output: Mutex<Output>,
    alive: AtomicBool,
    #[cfg(windows)]
    job: job::Job,
    keep: AtomicBool,
    viewers: Mutex<HashMap<String, Instant>>,
    created: Instant,
}
impl Session {
    fn descriptor(&self) -> Value {
        let mut v = self.info.lock().unwrap().clone();
        v["running"] = json!(self.alive.load(Ordering::Acquire));
        v["attached"] = json!(self
            .viewers
            .lock()
            .unwrap()
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
            self.killer
                .lock()
                .unwrap()
                .kill()
                .map_err(|e| e.to_string())
        }
    }
}
struct Server {
    root: PathBuf,
    sessions: Mutex<HashMap<String, Arc<Session>>>,
    files: Mutex<host::Files>,
    profiles: Mutex<()>,
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
#[cfg(windows)]
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
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or(SESSION_UNAVAILABLE)?;
        let info = s.info.lock().unwrap();
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
            .lock()
            .unwrap()
            .values()
            .filter(|s| s.info.lock().unwrap()["hostId"] == host)
            .map(|s| s.descriptor())
            .collect::<Vec<_>>())
    }
    fn create(&self, host: &str, a: &Value) -> Result<Value, String> {
        let name = required(a, "name")?;
        if !valid_name(name) {
            return Err("Use up to 80 letters, numbers, underscores or hyphens".into());
        }
        let operation = required(a, "operation")?;
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(s) = sessions.values().find(|s| {
            let i = s.info.lock().unwrap();
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
            let i = s.info.lock().unwrap();
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
                let mut script = format!("& {}", ps_quote(&agent_executable.to_string_lossy()));
                let brief = a["brief"].as_str().unwrap_or("");
                if !brief.is_empty() {
                    script.push(' ');
                    script.push_str(&ps_quote(brief))
                }
                let utf16: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
                let encoded = STANDARD.encode(utf16);
                if encoded.len() > 24000 {
                    return Err("The orientation prompt is too long for a PowerShell launch. Start without it and paste it using Repo.".into());
                }
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
        let writer = pair.master.take_writer().map_err(|e| e.to_string())?;
        let id = uuid::Uuid::new_v4().to_string();
        let stamp = uuid::Uuid::new_v4().to_string();
        let s = Arc::new(Session {
            #[cfg(windows)]
            job,
            info: Mutex::new(
                json!({"id":id,"stamp":stamp,"hostId":host,"operation":operation,"name":name,"path":directory,"shell":shell,"agent":agent,"pid":pid,"windows":1}),
            ),
            master: Mutex::new(pair.master),
            writer: Mutex::new(writer),
            #[cfg(not(windows))]
            killer: Mutex::new(child.clone_killer()),
            output: Mutex::new(Output {
                parser: vt100::Parser::new(30, 120, 1000),
                seq: 0,
                chunks: VecDeque::new(),
                bytes: 0,
            }),
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
                let mut o = reading.output.lock().unwrap();
                o.parser.process(&bytes);
                if count > 0 {
                    let (row, column) = o.parser.screen().cursor_position();
                    let reply = format!("\x1b[{};{}R", row + 1, column + 1);
                    let mut writer = reading.writer.lock().unwrap();
                    for _ in 0..count {
                        let _ = writer.write_all(reply.as_bytes());
                    }
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
            }
        });
        let waiting = s.clone();
        std::thread::spawn(move || {
            let _ = child.wait();
            waiting.alive.store(false, Ordering::Release);
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
            "describe" => Ok(self.session(host, a)?.descriptor()),
            "create" => self.create(host, a),
            "sessions" => Ok(self.list(host)),
            "rename" => {
                let n = required(a, "name")?;
                if !valid_name(n) {
                    return Err("Invalid session name".into());
                }
                let sessions = self.sessions.lock().unwrap();
                if sessions.values().any(|s| {
                    let info = s.info.lock().unwrap();
                    info["hostId"] == host && info["name"] == n && info["id"] != a["id"]
                }) {
                    return Err("A session with that name already exists".into());
                }
                drop(sessions);
                let s = self.session(host, a)?;
                s.info.lock().unwrap()["name"] = json!(n);
                Ok(self.list(host))
            }
            "destroy" => {
                // A stale saved pane may outlive its broker session. Ending it
                // again must still allow the desktop to remove that pane.
                let s = match self.session(host, a) {
                    Ok(s) => s,
                    Err(error) if error == SESSION_UNAVAILABLE => return Ok(self.list(host)),
                    Err(error) => return Err(error),
                };
                s.stop()?;
                self.sessions.lock().unwrap().remove(required(a, "id")?);
                Ok(self.list(host))
            }
            "session_agent" => Ok(self.session(host, a)?.info.lock().unwrap()["agent"].clone()),
            "keep" => {
                let s = self.session(host, a)?;
                s.keep.store(true, Ordering::Release);
                Ok(s.descriptor())
            }
            "view_detach" => {
                let s = self.session(host, a)?;
                {
                    let viewer = required(a, "viewer")?;
                    let mut views = s.viewers.lock().unwrap();
                    views.remove(viewer);
                    views.remove(&format!("ack:{viewer}"));
                }
                if !s.keep.load(Ordering::Acquire) && s.viewers.lock().unwrap().is_empty() {
                    s.stop()?
                }
                Ok(Value::Null)
            }
            "view_poll" => {
                let s = self.session(host, a)?;
                let viewer = required(a, "viewer")?;
                {
                    let mut views = s.viewers.lock().unwrap();
                    if views.len() >= 32 && !views.contains_key(viewer) {
                        return Err("Too many attached views".into());
                    }
                    views.insert(viewer.into(), Instant::now());
                }
                let o = s.output.lock().unwrap();
                let since = a["since"].as_u64();
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
                Ok(
                    json!({"data":STANDARD.encode(bytes),"seq":o.seq,"reset":reset,"running":s.alive.load(Ordering::Acquire),"pid":s.info.lock().unwrap()["pid"]}),
                )
            }
            "view_attach_ack" => {
                let s = self.session(host, a)?;
                s.viewers
                    .lock()
                    .unwrap()
                    .insert(format!("ack:{}", required(a, "viewer")?), Instant::now());
                Ok(Value::Null)
            }
            "view_ready" => {
                let s = self.session(host, a)?;
                let ready = s
                    .viewers
                    .lock()
                    .unwrap()
                    .get(&format!("ack:{}", required(a, "viewer")?))
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(3));
                Ok(json!(ready))
            }
            "input" => {
                let s = self.session(host, a)?;
                if !s.alive.load(Ordering::Acquire) {
                    return Err("PowerShell session has ended".into());
                }
                let bytes = STANDARD
                    .decode(required(a, "data")?)
                    .map_err(|e| e.to_string())?;
                if bytes.len() > 65536 {
                    return Err("Input too large".into());
                }
                let mut w = s.writer.lock().unwrap();
                w.write_all(&bytes)
                    .and_then(|_| w.flush())
                    .map_err(|e| e.to_string())?;
                Ok(Value::Null)
            }
            "resize" => {
                let s = self.session(host, a)?;
                let rows = a["rows"].as_u64().unwrap_or(30).clamp(1, 200) as u16;
                let cols = a["cols"].as_u64().unwrap_or(120).clamp(1, 300) as u16;
                s.master
                    .lock()
                    .unwrap()
                    .resize(PtySize {
                        rows,
                        cols,
                        pixel_width: 0,
                        pixel_height: 0,
                    })
                    .map_err(|e| e.to_string())?;
                s.output
                    .lock()
                    .unwrap()
                    .parser
                    .screen_mut()
                    .set_size(rows, cols);
                Ok(Value::Null)
            }
            "session_context" => {
                let s = self.session(host, a)?;
                let i = s.info.lock().unwrap();
                Ok(json!(format!(
                    "{}:native:native:{}:powershell:0",
                    i["id"].as_str().unwrap_or(""),
                    i["pid"]
                )))
            }
            "terminal_status" => {
                let pid = a["pid"].as_u64();
                let sessions = self.sessions.lock().unwrap();
                let s = sessions.values().find(|s| {
                    let i = s.info.lock().unwrap();
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
pub fn serve(root: &Path) -> Result<(), String> {
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(root.join("broker.lock"))
        .map_err(|e| e.to_string())?;
    if lock.try_lock_exclusive().is_err() {
        return Ok(());
    }
    let server = Arc::new(Server {
        root: root.into(),
        sessions: Mutex::new(HashMap::new()),
        files: Mutex::new(host::Files::default()),
        profiles: Mutex::new(()),
    });
    let reap = server.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(2));
        for s in reap.sessions.lock().unwrap().values() {
            let mut v = s.viewers.lock().unwrap();
            v.retain(|_, t| t.elapsed() < Duration::from_secs(20));
            if v.is_empty()
                && !s.keep.load(Ordering::Acquire)
                && s.created.elapsed() > Duration::from_secs(30)
            {
                let _ = s.stop();
            }
        }
    });
    let workers = Arc::new(AtomicUsize::new(0));
    ipc::listen(root, move |mut pipe| {
        if workers.fetch_add(1, Ordering::AcqRel) >= 24 {
            workers.fetch_sub(1, Ordering::AcqRel);
            return;
        }
        let server = server.clone();
        let workers = workers.clone();
        std::thread::spawn(move || {
            let result = (|| {
                let v = ipc::receive(&mut pipe)?;
                if v["protocol"] != PROTOCOL {
                    return Err("Session protocol mismatch; existing sessions preserved".into());
                }
                server.request(required(&v, "host")?, required(&v, "method")?, &v["args"])
            })();
            let reply = match result {
                Ok(v) => json!({"result":v}),
                Err(e) => json!({"error":e}),
            };
            if ipc::send(&mut pipe, &reply).is_ok() {
                // Closing a Windows pipe discards unread bytes. Keep the server
                // handle until the client confirms it consumed the whole reply.
                let mut acknowledgment = [0];
                let _ = pipe.read_exact(&mut acknowledgment);
            }
            workers.fetch_sub(1, Ordering::AcqRel);
        });
    })
}
#[derive(Clone)]
pub struct Client {
    pub root: PathBuf,
    pub host: String,
    pub shell: String,
}
impl Client {
    pub fn request(&self, method: &str, mut args: Value) -> Result<Value, String> {
        if method == "create" && !args["shell"].is_string() {
            args["shell"] = json!(self.shell);
        }
        let mut pipe = ipc::connect(&self.root)?;
        ipc::send(
            &mut pipe,
            &json!({"protocol":PROTOCOL,"host":self.host,"method":method,"args":args}),
        )?;
        let result = ipc::receive(&mut pipe)?;
        pipe.write_all(&[6]).map_err(|e| e.to_string())?;
        if let Some(e) = result["error"].as_str() {
            Err(e.into())
        } else {
            Ok(result["result"].clone())
        }
    }
    pub fn ensure(root: PathBuf, host: String) -> Result<Self, String> {
        let c = Self {
            root,
            host,
            shell: "powershell".into(),
        };
        if c.request("ping", json!({})).is_ok() {
            return Ok(c);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let executable = runtime_exe(&c.root)?;
            std::process::Command::new(executable)
                .args(["serve", &c.root.to_string_lossy()])
                .creation_flags(0x08000000 | 0x00000200)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        #[cfg(not(windows))]
        {
            return Err("Native PowerShell requires Windows".into());
        }
        #[cfg(windows)]
        {
            for _ in 0..100 {
                if c.request("ping", json!({})).is_ok() {
                    return Ok(c);
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err("Local session manager did not start".into())
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
    let dir = root.join("runtime").join(format!("{:x}", hash.finalize()));
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
            Server {
                root,
                sessions: Mutex::new(HashMap::new()),
                files: Mutex::new(host::Files::default()),
                profiles: Mutex::new(()),
            },
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
    #[test]
    fn broker_ipc_receives_large_replies_before_server_disconnects() {
        let (server, host) = fixture();
        let root = server.root.clone();
        let serving = root.clone();
        std::thread::spawn(move || {
            if let Err(error) = serve(&serving) {
                eprintln!("Broker listener failed: {error}");
            }
        });
        let client = Client {
            root: root.clone(),
            host,
            shell: "powershell".into(),
        };
        let started = Instant::now();
        loop {
            match client.request("ping", json!({})) {
                Ok(_) => break,
                Err(error) => assert!(
                    started.elapsed() < Duration::from_secs(10),
                    "Broker ping failed: {error}"
                ),
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let mut profile = client.request("profile_load", json!({})).unwrap();
        profile["testPayload"] = json!("r".repeat(180_000));
        client
            .request("profile_save", json!({"profile":profile,"revision":0}))
            .unwrap();
        let received = client.request("profile_load", json!({})).unwrap();
        assert_eq!(received["testPayload"], profile["testPayload"]);
        fs::remove_dir_all(root).unwrap();
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
}
