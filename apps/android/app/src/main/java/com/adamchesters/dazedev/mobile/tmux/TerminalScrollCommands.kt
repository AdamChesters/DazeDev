package com.adamchesters.dazedev.mobile.tmux

object TerminalScrollCommands {
    fun command(socket: String, id: String, stamp: String, lines: Int, bottom: Boolean = false): String {
        require(Regex("\\$[0-9]+").matches(id) && stamp.isNotBlank()) { "Refresh this session before scrolling." }
        require(lines in -5000..5000)
        return "python3 -c ${ShellQuoter.quote(script)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(id)} ${ShellQuoter.quote(stamp)} $lines ${if (bottom) 1 else 0}"
    }

    internal val script = """
import subprocess,sys
a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else [])
def run(*args):
    return subprocess.check_output(a+list(args),text=True,timeout=5).strip()
stamp=run('display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}')
if stamp!=sys.argv[3]: raise RuntimeError('Session changed; reconnect before scrolling')
pane=run('display-message','-p','-t',sys.argv[2],'#{pane_id}')
mode=run('display-message','-p','-t',pane,'#{pane_mode}')
lines=int(sys.argv[4]); bottom=sys.argv[5]=='1'
if mode and mode!='copy-mode': raise RuntimeError('Close the current terminal menu before scrolling')
if bottom:
    if mode=='copy-mode': run('send-keys','-X','-t',pane,'cancel')
elif lines<0:
    if not mode: run('copy-mode','-e','-t',pane)
    run('send-keys','-X','-N',str(-lines),'-t',pane,'scroll-up')
elif lines>0 and mode=='copy-mode':
    run('send-keys','-X','-N',str(lines),'-t',pane,'scroll-down')
    if run('display-message','-p','-t',pane,'#{pane_mode}')=='copy-mode' and run('display-message','-p','-t',pane,'#{scroll_position}')=='0':
        run('send-keys','-X','-t',pane,'cancel')
""".trimIndent()
}
