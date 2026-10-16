//! Decoding management channel output: terminal queries, protocol 1
//! reply lines and protocol 2 binary frames.
use super::*;
// The management PTY has no xterm renderer. Windows ConPTY asks its terminal
// for a cursor position before forwarding input; answer queries, never display
// them as authentication instructions. Keep partial escape sequences across reads.
#[derive(Default)]
pub(super) struct ControlTerminal {
    pub(super) escape: Vec<u8>,
}
impl ControlTerminal {
    pub(super) fn feed(&mut self, bytes: &[u8]) -> (Vec<u8>, Vec<u8>) {
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
pub(super) struct ReplyFrames {
    pub(super) data: String,
    pub(super) next: usize,
    pub(super) total: usize,
}
impl ReplyFrames {
    pub(super) fn line(&mut self, line: &str, prefix: &str) -> Result<Option<Value>, String> {
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
/// Host helper protocol on clean channels (see shared/protocol/host-v2.json).
pub(super) const HOST_PROTOCOL: u64 = 2;
pub(super) const MAX_HOST_MESSAGE: usize = 64 * 1024 * 1024;
/// Reassembles protocol 2 frames: u32 length, u8 flags (1 = last part),
/// u16 id length, id, data. Parts of different messages may interleave.
#[derive(Default)]
pub(super) struct MessageFrames {
    pub(super) buffer: Vec<u8>,
    pub(super) parts: HashMap<Vec<u8>, Vec<u8>>,
}
impl MessageFrames {
    pub(super) fn push(&mut self, bytes: &[u8]) -> Result<Vec<Value>, String> {
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
pub(super) fn request_frame(request: &Value) -> Result<Vec<u8>, String> {
    let body = serde_json::to_vec(request).map_err(|e| e.to_string())?;
    let mut frame = (body.len() as u32).to_be_bytes().to_vec();
    frame.extend(body);
    Ok(frame)
}
/// Error text for the frontend. Protocol 2 sends `{code, message}`; a
/// conflict keeps the `CONFLICT:` prefix the frontend looks for.
pub(super) fn host_error(error: &Value) -> Option<String> {
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
