use dazedev::transport::{EventSink, Host, Transport};
use serde_json::json;
use std::{
    sync::{mpsc, Arc},
    time::Duration,
};
fn main() {
    let socket = format!("dazed-smoke-{}", std::process::id());
    let tmp = std::env::temp_dir().join(&socket);
    std::fs::create_dir_all(&tmp).unwrap();
    std::env::set_var("XDG_CONFIG_HOME", &tmp);
    let t = Transport::default();
    let h = Host {
        id: "test".into(),
        name: "test".into(),
        kind: if std::env::var_os("DAZED_SMOKE_SSH_KEY").is_some() {
            "ssh"
        } else {
            "local"
        }
        .into(),
        address: "127.0.0.1".into(),
        user: "ubuntu".into(),
        port: Some(22482),
        key: std::env::var("DAZED_SMOKE_SSH_KEY").unwrap_or_default(),
        jump: "".into(),
        distro: "".into(),
        socket: socket.clone(),
        native_shell: None,
    };
    let (tx, rx) = mpsc::channel();
    let sink: EventSink = Arc::new(move |n, v| {
        tx.send((n.to_string(), v)).ok();
    });
    t.connect(h.clone(), sink.clone()).unwrap();
    loop {
        let (n, _) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if n == "host-ready" {
            break;
        }
    }
    // A workspace opens real shells without creating any tmux sessions.
    assert!(t
        .rpc("test", "sessions", json!({}))
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
    t.open("shell".into(), h.clone(), String::new(), None, sink.clone())
        .unwrap();
    t.input("shell", "printf 'PLAIN_%s_OK\\n' SHELL\r").unwrap();
    let mut shell_output = String::new();
    while !shell_output.contains("PLAIN_SHELL_OK") {
        let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if name == "terminal-output" && value["id"] == "shell" {
            use base64::Engine;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(value["data"].as_str().unwrap())
                .unwrap();
            shell_output.push_str(&String::from_utf8_lossy(&bytes));
        }
    }
    assert!(t
        .rpc("test", "sessions", json!({}))
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
    for index in 0..2 {
        t.input("shell", "\u{4}").unwrap();
        let mut output = String::new();
        while !output.contains("dazedev-shell") {
            let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
            assert_ne!(name, "terminal-exit", "Ctrl-D must retain the terminal");
            if name == "terminal-output" && value["id"] == "shell" {
                use base64::Engine;
                output.push_str(&String::from_utf8_lossy(
                    &base64::engine::general_purpose::STANDARD
                        .decode(value["data"].as_str().unwrap())
                        .unwrap(),
                ));
            }
        }
        let marker = format!("SHELL_RESTART_{index}");
        t.input("shell", &format!("printf 'SHELL_RESTART_%s\\n' {index}\r"))
            .unwrap();
        while !output.contains(&marker) {
            let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
            if name == "terminal-output" && value["id"] == "shell" {
                use base64::Engine;
                output.push_str(&String::from_utf8_lossy(
                    &base64::engine::general_purpose::STANDARD
                        .decode(value["data"].as_str().unwrap())
                        .unwrap(),
                ));
            }
        }
    }
    t.close("shell");
    let mut p = t.rpc("test", "profile_load", json!({})).unwrap();
    p["preferences"]["guidance"] = json!("large-profile ".repeat(2000));
    let r = t
        .rpc("test", "profile_save", json!({"profile":p,"revision":0}))
        .unwrap();
    assert_eq!(r["revision"], 1);
    let s=t.rpc("test","create",json!({"name":"smoke","path":tmp,"agent":"shell","operation":uuid::Uuid::new_v4().to_string()})).unwrap();
    let sid = s["id"].as_str().unwrap().to_string();
    // Simulate an alpha.10 retained dead pane; attach must revive only this owned pane.
    let tmux = |args: &[&str]| {
        std::process::Command::new("tmux")
            .args(["-L", &socket])
            .args(args)
            .output()
            .unwrap()
    };
    assert!(
        tmux(&["set-window-option", "-t", &sid, "remain-on-exit", "on"])
            .status
            .success()
    );
    assert!(tmux(&["send-keys", "-t", &sid, "exit", "Enter"])
        .status
        .success());
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(
        String::from_utf8_lossy(
            &tmux(&["display-message", "-p", "-t", &sid, "#{pane_dead}"]).stdout
        )
        .trim(),
        "1"
    );
    t.open(
        "pane".into(),
        h.clone(),
        sid.clone(),
        Some(s["stamp"].as_str().unwrap().into()),
        sink.clone(),
    )
    .unwrap();
    t.resize("pane", 100, 35).unwrap();
    std::thread::sleep(Duration::from_millis(200));
    t.input("pane", "printf 'DAZED_%s_OK\\n' NATIVE\r").unwrap();
    let mut observed = false;
    for _ in 0..20 {
        let (n, v) = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        if n == "terminal-output" {
            use base64::Engine;
            let data = base64::engine::general_purpose::STANDARD
                .decode(v["data"].as_str().unwrap())
                .unwrap();
            if String::from_utf8_lossy(&data).contains("DAZED_NATIVE_OK") {
                observed = true;
                break;
            }
        }
    }
    assert!(observed);
    // A dropped management connection must not close a live tmux terminal.
    t.disconnect("test");
    t.input("pane", "printf 'SURVIVED_%s_OK\\n' DISCONNECT\r")
        .unwrap();
    let mut survived = String::new();
    while !survived.contains("SURVIVED_DISCONNECT_OK") {
        let (n, v) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        assert_ne!(n, "terminal-exit");
        if n == "terminal-output" && v["id"] == "pane" {
            use base64::Engine;
            survived.push_str(&String::from_utf8_lossy(
                &base64::engine::general_purpose::STANDARD
                    .decode(v["data"].as_str().unwrap())
                    .unwrap(),
            ));
        }
    }
    t.connect(h.clone(), sink.clone()).unwrap();
    loop {
        let (n, _) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if n == "host-ready" {
            break;
        }
    }
    t.health("test").unwrap();
    assert!(t
        .rpc("test", "sessions", json!({}))
        .unwrap()
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v["id"] == sid));
    // Detach the sole test session and keep the same PTY alive as a normal shell.
    assert!(std::process::Command::new("tmux")
        .args(["-L", &socket, "detach-client", "-s", &sid])
        .status()
        .unwrap()
        .success());
    let mut detached_output = String::new();
    while !detached_output.contains("dazedev-shell") {
        let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if name == "terminal-output" && value["id"] == "pane" {
            use base64::Engine;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(value["data"].as_str().unwrap())
                .unwrap();
            detached_output.push_str(&String::from_utf8_lossy(&bytes));
        }
    }
    t.input("pane", "printf 'AFTER_%s_OK\\n' DETACH\r").unwrap();
    let mut shell_output = String::new();
    while !shell_output.contains("AFTER_DETACH_OK") {
        let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if name == "terminal-output" && value["id"] == "pane" {
            use base64::Engine;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(value["data"].as_str().unwrap())
                .unwrap();
            shell_output.push_str(&String::from_utf8_lossy(&bytes));
        }
    }
    t.close("pane");
    assert_eq!(
        t.rpc("test", "sessions", json!({}))
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        1
    );
    t.open(
        "exit".into(),
        h,
        sid.clone(),
        Some(s["stamp"].as_str().unwrap().into()),
        sink,
    )
    .unwrap();
    std::thread::sleep(Duration::from_millis(300));
    t.input("exit", "\u{4}").unwrap();
    let mut output = String::new();
    while !output.contains("dazedev-shell") {
        let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if name == "terminal-output" && value["id"] == "exit" {
            use base64::Engine;
            output.push_str(&String::from_utf8_lossy(
                &base64::engine::general_purpose::STANDARD
                    .decode(value["data"].as_str().unwrap())
                    .unwrap(),
            ));
        }
    }
    t.input("exit", "printf 'AFTER_%s_OK\\n' TMUX_EXIT\r")
        .unwrap();
    while !output.contains("AFTER_TMUX_EXIT_OK") {
        let (name, value) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        if name == "terminal-output" && value["id"] == "exit" {
            use base64::Engine;
            output.push_str(&String::from_utf8_lossy(
                &base64::engine::general_purpose::STANDARD
                    .decode(value["data"].as_str().unwrap())
                    .unwrap(),
            ));
        }
    }
    assert!(t
        .rpc("test", "sessions", json!({}))
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
    t.close("exit");
    t.disconnect("test");
    std::fs::remove_dir_all(tmp).unwrap();
    println!("Native PTY, large RPC payload, terminal I/O, resize and detach persistence passed.");
}
