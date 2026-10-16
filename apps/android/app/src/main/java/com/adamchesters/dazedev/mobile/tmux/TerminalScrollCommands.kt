package com.adamchesters.dazedev.mobile.tmux

/** Host command that leaves tmux copy mode. Android never enters copy mode itself. */
object TerminalScrollCommands {
    fun cancel(
        socket: String,
        id: String,
        stamp: String,
    ): String {
        require(Regex("\\$[0-9]+").matches(id) && stamp.isNotBlank()) { "Refresh this session before scrolling." }
        return "python3 -I -c ${ShellQuoter.quote(script)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(id)} ${ShellQuoter.quote(stamp)}"
    }

    internal val script =
        """
import subprocess,sys
a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else [])
def run(*args):
    return subprocess.check_output(a+list(args),text=True,timeout=5).strip()
if run('display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}')!=sys.argv[3]: raise SystemExit('Session ended or restarted; reconnect first')
pane=run('display-message','-p','-t',sys.argv[2],'#{pane_id}')
if run('display-message','-p','-t',pane,'#{pane_mode}')=='copy-mode': run('send-keys','-X','-t',pane,'cancel')
        """.trimIndent()
}
