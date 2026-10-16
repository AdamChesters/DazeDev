//! Starting the host helper: the loader, boot commands and bootstrap stages.
use super::*;
// Keep the host program out of argv: Windows limits the full command line,
// including the arguments added by `tailscale ssh`, to 32,767 UTF-16 units.
// The loader refuses Python older than 3.9 with a line the client can explain.
pub(super) const HOST_LOADER: &str = "import sys;v=sys.version_info;v<(3,9) and (print('DAZED:'+sys.argv[1]+':python:%d.%d'%v[:2],flush=True),sys.exit(3));import base64;print();print('DAZED:'+sys.argv[1]+':bootstrap',flush=True);exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))";
/// The script that starts the host helper. It is sent as one line of input,
/// never as an argument, so it is always run by /bin/sh.
pub(super) fn control_script(
    socket: &str,
    nonce: &str,
    endpoint: &str,
    desktop: &str,
    binary: bool,
) -> String {
    format!(
        "export DAZED_BROWSER_ENDPOINT={} DAZED_BROWSER_DESKTOP={}; export DAZED_TMUX_SOCKET={}; exec python3 -I -u -c {} {}{}",
        quote(endpoint),
        quote(desktop),
        quote(socket),
        quote(HOST_LOADER),
        quote(nonce),
        if binary { " 2" } else { "" }
    )
}
#[derive(Clone, Copy)]
pub(super) enum Boot {
    /// Management channel without a terminal (built-in SSH).
    Pipe,
    /// Management channel inside a terminal (system OpenSSH).
    TerminalControl,
    /// Interactive terminal.
    Pane,
}
/// A fixed command for the remote login shell. SSH servers run commands
/// through the user's shell, which may be fish, nu or tcsh, so this only
/// starts /bin/sh with a quoted body free of backslashes and inner quotes.
/// That shell then reads the real POSIX script as its next input line.
/// With a terminal, it first turns echo off and prints a marker, so the
/// client sends the script only once it will not be echoed.
pub(super) fn boot_command(boot: Boot, nonce: &str) -> String {
    match boot {
        Boot::Pipe => "exec /bin/sh -c 'IFS= read -r l; eval \"$l\"'".into(),
        Boot::TerminalControl => format!("exec /bin/sh -c 'stty -echo -icanon min 1 time 0 2>/dev/null; echo DZBOOT:{nonce}; IFS= read -r l; eval \"$l\"'"),
        Boot::Pane => format!("exec /bin/sh -c 'stty -echo -icanon min 1 time 0 2>/dev/null; echo DZBOOT:{nonce}; IFS= read -r l; stty echo icanon 2>/dev/null; eval \"$l\"'"),
    }
}
pub(super) fn boot_marker(nonce: &str) -> String {
    format!("DZBOOT:{nonce}")
}
/// Input to send when the remote side prints each marker line, in order.
pub(super) struct Bootstrap {
    pub(super) stages: std::collections::VecDeque<(String, Vec<u8>)>,
}
impl Bootstrap {
    pub(super) fn new(nonce: &str, source: &[u8]) -> Self {
        Self {
            stages: [(
                format!("DAZED:{nonce}:bootstrap"),
                (STANDARD.encode(source) + "\n").into_bytes(),
            )]
            .into(),
        }
    }
    /// First wait for the shell marker and send the script, then the source.
    pub(super) fn after_script(mut self, nonce: &str, script: &str) -> Self {
        self.stages
            .push_front((boot_marker(nonce), format!("{script}\n").into_bytes()));
        self
    }
    pub(super) fn is_done(&self) -> bool {
        self.stages.is_empty()
    }
    pub(super) fn line(&mut self, line: &str) -> Option<Vec<u8>> {
        if self
            .stages
            .front()
            .is_some_and(|(marker, _)| line == marker)
        {
            self.stages.pop_front().map(|(_, payload)| payload)
        } else {
            None
        }
    }
}
/// Look for the boot marker in held pane output. When found, return the
/// output with the marker line removed and clear the buffer.
pub(super) fn pane_boot(held: &mut Vec<u8>, marker: &[u8]) -> Option<Vec<u8>> {
    let start = held.windows(marker.len()).position(|w| w == marker)?;
    let mut end = start + marker.len();
    while end < held.len() && matches!(held[end], b'\r' | b'\n') {
        end += 1;
    }
    let mut output = held[..start].to_vec();
    output.extend_from_slice(&held[end..]);
    held.clear();
    Some(output)
}
