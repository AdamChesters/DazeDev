//! The management connection reader and host message handling.
use super::*;
pub(super) fn host_finished(conn: &Connection, id: &str, sink: &EventSink) {
    let notify = conn.pty.active.swap(false, Ordering::AcqRel);
    conn.pty.writable.store(false, Ordering::Release);
    // Serialize the ready/closed notifications too, so buffered output cannot
    // mark an exited host ready after its closed event has been delivered.
    let mut ready = lock(&conn.ready);
    *ready = false;
    for (_, tx) in lock(&conn.pending).drain() {
        let _ = tx.send(Err("Host disconnected".into()));
    }
    if notify {
        sink("host-closed", json!({"hostId":id}));
    }
}
pub(super) fn host_ready(conn: &Connection, id: &str, identity: Value, sink: &EventSink) -> bool {
    let mut ready = lock(&conn.ready);
    if !conn.pty.active.load(Ordering::Acquire) {
        return false;
    }
    *ready = true;
    sink("host-ready", json!({"hostId":id,"identity":identity}));
    true
}
/// Act on one decoded host message. Returns false to stop reading.
pub(super) fn host_message(
    conn: &Connection,
    host_id: &str,
    nonce: &str,
    v: Value,
    sink: &EventSink,
) -> bool {
    match HostMessage::parse(v) {
        Some(HostMessage::BrowserOpen { id, url }) => {
            if conn.pty.active.load(Ordering::Acquire) && *lock(&conn.ready) {
                sink(
                    "host-browser-open",
                    json!({"hostId":host_id,"nonce":nonce,"id":id,"url":url}),
                );
            }
        }
        Some(HostMessage::Ready(identity)) => {
            if conn.binary && identity["protocol"].as_u64() != Some(HOST_PROTOCOL) {
                sink(
                    "host-output",
                    json!({"hostId":host_id,"text":"The host helper does not match this version of DazeDev.\n"}),
                );
                return false;
            }
            return host_ready(conn, host_id, identity, sink);
        }
        Some(HostMessage::Reply { id, result }) => {
            if let Some(tx) = lock(&conn.pending).remove(&id) {
                let _ = tx.send(result);
            }
        }
        None => {}
    }
    true
}
/// Decode protocol 2 bytes and handle the messages. Returns false to stop.
pub(super) fn host_frames(
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
pub(super) fn run_control(
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
