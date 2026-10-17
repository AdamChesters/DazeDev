//! Editor requests from a host. Agents such as Codex and Claude Code run
//! $VISUAL/$EDITOR with a file path for Ctrl+G and wait for it to exit.
//! DazeDev's host helper sends the file text here so the desktop can open
//! it in an editable pane over the terminal that runs the agent. The
//! desktop answers with `editor_shown`, then `editor_done` (the saved text,
//! nothing for cancel, or an error so the helper falls back to a terminal
//! editor).
use crate::sync::LockExt;
use serde_json::Value;
use std::{collections::HashSet, sync::Mutex};

/// Largest file text sent to the desktop. Larger files stay in the
/// terminal editor.
pub const MAX_TEXT: usize = 8 * 1024 * 1024;
/// Requests one connection may have waiting for the user.
pub const MAX_PENDING: usize = 8;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Request {
    pub id: String,
    /// The DazeDev terminal's shell process on the host; the desktop shows
    /// the request in the terminal that reported this process.
    pub pid: u32,
    /// What asked, for the pane title, such as the agent or file name.
    pub name: String,
    pub text: String,
}

/// Check one `editor-open` event from the host.
pub fn validate(id: &str, pid: &Value, name: &Value, text: &Value) -> Result<Request, String> {
    if id.len() != 32 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Invalid editor request".into());
    }
    let pid = pid
        .as_u64()
        .filter(|pid| (1..=u32::MAX as u64).contains(pid))
        .ok_or("Editor request has no terminal")? as u32;
    let text = text.as_str().ok_or("Editor request has no text")?;
    if text.len() > MAX_TEXT {
        return Err("This file is too large for the DazeDev editor".into());
    }
    let name: String = name
        .as_str()
        .unwrap_or("")
        .chars()
        .filter(|c| !c.is_control())
        .take(120)
        .collect();
    Ok(Request {
        id: id.to_ascii_lowercase(),
        pid,
        name: if name.trim().is_empty() {
            "Editor".into()
        } else {
            name
        },
        text: text.to_owned(),
    })
}

/// Requests sent to the desktop and not yet answered with `editor_done`.
/// Bounds how much file text a host can push into the window.
#[derive(Default)]
pub struct Pending(Mutex<HashSet<String>>);
impl Pending {
    /// False for a repeated request or when too many wait.
    pub fn reserve(&self, id: &str) -> bool {
        let mut pending = self.0.locked();
        pending.len() < MAX_PENDING && pending.insert(id.to_owned())
    }
    pub fn release(&self, id: &str) {
        self.0.locked().remove(id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    const ID: &str = "0123456789abcdef0123456789abcdef";
    #[test]
    fn accepts_a_request_and_keeps_the_text_exactly() {
        let text = "line one\r\nline two\n\ttabbed \u{1f600}\n";
        let request = validate(ID, &json!(4242), &json!("codex"), &json!(text)).unwrap();
        assert_eq!(request.text, text);
        assert_eq!(request.pid, 4242);
        assert_eq!(request.name, "codex");
        assert_eq!(
            validate(ID, &json!(1), &json!("a\x1b[31mb\n"), &json!(""))
                .unwrap()
                .name,
            "a[31mb"
        );
        assert_eq!(
            validate(ID, &json!(1), &Value::Null, &json!(""))
                .unwrap()
                .name,
            "Editor"
        );
    }
    #[test]
    fn rejects_malformed_requests() {
        for (id, pid, text) in [
            ("short", json!(1), json!("")),
            ("0123456789abcdef0123456789abcdeg", json!(1), json!("")),
            (ID, json!(0), json!("")),
            (ID, json!(-1), json!("")),
            (ID, json!(u32::MAX as u64 + 1), json!("")),
            (ID, json!("42"), json!("")),
            (ID, json!(1), Value::Null),
            (ID, json!(1), json!("x".repeat(MAX_TEXT + 1))),
        ] {
            assert!(
                validate(id, &pid, &json!("n"), &text).is_err(),
                "{id} {pid}"
            );
        }
    }
    #[test]
    fn pending_requests_are_bounded_and_unique() {
        let pending = Pending::default();
        assert!(pending.reserve("a"));
        assert!(!pending.reserve("a"));
        for i in 1..MAX_PENDING {
            assert!(pending.reserve(&i.to_string()));
        }
        assert!(!pending.reserve("extra"));
        pending.release("a");
        assert!(pending.reserve("extra"));
    }
}
