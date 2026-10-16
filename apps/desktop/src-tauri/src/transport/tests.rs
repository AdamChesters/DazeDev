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
        let root = std::env::temp_dir().join(format!("daze-view-lock-{}", uuid::Uuid::new_v4()));
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
        lock(&transport.native_views).insert(
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
        lock(&transport.native_views).clear();
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
    lock(&transport.native_hosts).insert(
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
struct RestoreRuntime(Option<std::ffi::OsString>, std::path::PathBuf);
#[cfg(unix)]
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
        serde_json::from_value(json!({"id":"browser-test","name":"test","kind":"local"})).unwrap();
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
                    String::from_utf8_lossy(&STANDARD.decode(data).unwrap_or_default()).into_owned()
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
    assert!(!*lock(&conn.ready));
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
    lock(&transport.connections).insert(
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
        assert!(!script.contains(&STANDARD.encode(include_bytes!("../../resources/host.py"))));
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
        serde_json::from_str(include_str!("../../../../../shared/protocol/host-v2.json")).unwrap();
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
