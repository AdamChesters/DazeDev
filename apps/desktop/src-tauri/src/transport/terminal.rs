//! Opening and driving terminals: SSH panes, local processes and native views.
use super::*;
impl Transport {
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
                    .ok_or("Invalid browser bridge directory")?;
                // Helpers older than the editor bridge return no editor.
                let editor = match browser["editor"].as_str() {
                    Some(editor) => {
                        checked(editor)?;
                        if std::path::Path::new(editor).parent() != Some(folder) {
                            return Err("Invalid editor bridge command".into());
                        }
                        editor_exports(editor)
                    }
                    None => String::new(),
                };
                let folder = folder.to_string_lossy();
                format!("export BROWSER={}; export PATH={}:\"$PATH\"; {editor}export DAZEDEV_BROWSER_TTY=\"$(tty)\" DAZEDEV_BROWSER_PID=\"$$\"; \"$BROWSER\" --dazedev-register {} \"$$\" \"$DAZEDEV_BROWSER_TTY\"; {script}", quote(executable), quote(&folder), quote(endpoint))
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
        if let Some(v) = lock(&self.native_views).get(id) {
            let _ = v
                .pending
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                    Some(n.saturating_sub(bytes))
                });
            return;
        }
        if let Some(p) = lock(&self.terminals).get(id) {
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
        if lock(&self.native_views).contains_key(id) {
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
        let native = lock(&self.native_views).remove(id);
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
