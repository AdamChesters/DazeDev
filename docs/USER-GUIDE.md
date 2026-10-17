# DazeDev quick guide

Download the [Windows ZIP](https://github.com/AdamChesters/DazeDev/releases), extract it and run `DazeDev.exe`. Requires Windows x64 and WebView2.

Screenshots show the app interface with sample hosts and terminal output.

## Arrange your workspace

- **New workspace:** choose a host, name and terminal count. Use **tmux / Sessions** to join existing sessions.
- **Split** adds a pane; **Balance** tiles them. Drag title bars to rearrange. Shift-click selects a group.
- **Other screen** moves a terminal into its own window.
- Click a host tile to expand or collapse its workspaces. Drag hosts or workspaces to reorder them; their menus also offer Move up and Move down.
- **New terminal tab** adds a terminal behind the current pane. Shift-click terminal headers, then choose **Group selected terminals as tabs** to combine existing panes. **Move tab into its own pane** separates one again.
- **Hostless workspace** creates a group on this device. Hold Ctrl and drag persistent terminal views into it; the originals stay in their host workspaces. Opening the group offers to connect its required hosts.
- Highlight terminal text and press **Ctrl+C** to copy it. With no selection, Ctrl+C keeps its normal terminal behavior.

![DazeDev with three terminals; the code-review terminal is highlighted while listening for voice input.](assets/screenshots/workspace.png)

## Connect a machine

- **New host → Location:** choose PowerShell, WSL or SSH, then **Save & connect**.
- Native PowerShell needs no WSL. WSL and remote Unix hosts need Python 3.9 or newer and tmux. Login shells such as fish, nu and tcsh work.
- Remote layouts and preferences stay on the host; reconnect from another device to reuse them.
- **SSH hosts** use DazeDev's built-in SSH client. You sign in once per host, and every terminal on that host shares the connection.
- DazeDev reads your `~/.ssh/config` through `ssh -G`, so `Include`, `Match`, `ProxyJump`, `ProxyCommand`, `IdentityFile` and known_hosts settings still apply.
- Host keys are checked against known_hosts. DazeDev asks before it trusts an unknown key and refuses a key that has changed.
- Sign-in can use your SSH agent (the Windows OpenSSH agent, Pageant or `SSH_AUTH_SOCK`), key files, certificates, keyboard-interactive prompts and passwords. DazeDev asks for a key's passphrase only after the server accepts that key.
- A host that needs PKCS#11 or a hardware security key can use your system's OpenSSH instead: turn on **Use system OpenSSH** in that host's settings.
- **Tailscale SSH** uses the destination's Tailscale SSH service and checks the host key against the keys the tailnet publishes for that device. Native Windows cannot serve it. For WSL, connect to the Linux device's own tailnet name and username. Ordinary SSH over Tailscale uses separate SSH credentials; DazeDev's remote host still needs Python 3.9 or newer and tmux.

<img src="assets/screenshots/connect.png" width="490" alt="New host dialog with Local Windows PowerShell selected." />

## Agents

- **Agents** lists the agents found on the selected host: Codex, Claude Code, Gemini CLI, OpenCode, GitHub Copilot CLI, Cursor CLI, Qwen Code, Factory Droid, goose, Amp, Crush, Aider and Kimi Code. **Install and start** runs the publisher's own user-level installer in a new session, so you can watch it.
- **Add your own agent** takes a display name, an ID (lowercase letters, numbers and hyphens) and the launch command. DazeDev runs that command exactly as typed in the host user's shell, in the session's folder.
- Optional fields: an install command (run only when you choose it, in the session), a detection binary (the program that shows the agent is installed and running; by default the command's first word) and a version command.
- Custom agents are saved on the host for that user, so every desktop and phone connected to it sees them. They start without a starting prompt; put model options in the launch command. Native PowerShell hosts list the built-in agents only.

## Dictate, then review

- Install the voice models in **Settings → Voice models**.
- Select a terminal and click **Listen**. **Enter** finishes recording; **Escape** cancels.
- **S1-mini tidy-up** cleans the transcript locally. Voice and tidy-up have no API fees.
- Text is pasted for review; automatic Enter is optional. Configure hotkeys in Settings.
- The first Listen opens an introduction showing your current keyboard or mouse shortcut. In toggle mode, tap to listen, tap to stop, then double-tap to submit the reviewed text. Changing terminal input invalidates this shortcut submission; review and press Enter in the terminal instead. Hold-to-talk keeps its press/release behavior.

<img src="assets/screenshots/voice.png" width="622" alt="Dictation settings with S1-mini tidy-up enabled and automatic Enter disabled." />

## Search across terminals

- Press **Ctrl+Shift+F**, type your query and select **Global**.
- Narrow to a host, workspace or terminal. **Go to** opens a match.
- Searches open terminal output and scrollback.

<img src="assets/screenshots/search.png" width="680" alt="Global search showing matching output from open terminal buffers." />

## Broadcast a message

- Open **Broadcast** in the sidebar; type or dictate your message.
- Choose the scope, then **Review recipients**.
- **Send** pastes the message **and presses Enter** in each listed session, including bare shells.

<img src="assets/screenshots/broadcast.png" width="490" alt="Broadcast review listing three terminal recipients before sending." />

## Questions DazeDev asks first

- When a host asks to open a link in your browser, DazeDev shows the host name and the full address. Choose **Open** or **Don't open**. Tick **Allow links from this host until DazeDev restarts** to stop the questions for that host.
- If a repository has `scripts/new-worktree.sh`, creating a worktree asks whether to **Run script**, **Create without script** or **Cancel**.
- Pasting text with a line break shows the line count and the first lines before it pastes. Text longer than about 2,000 characters opens in a preview over the terminal instead, where you can read and edit it: **Paste** (Ctrl+Enter) sends it and **Cancel** (Esc) sends nothing. Tick **Don't ask again**, or clear **Ask before pasting text with line breaks** in **Settings → Terminal**, to turn off both.
- **Remote copy (OSC 52)** in **Settings → Terminal** decides what happens when a program on a host copies to this device's clipboard: **Block**, **Allow and notify** (the default) or **Allow silently**. A fourth option also lets programs on the host read your clipboard.

## Long pastes and the prompt editor

- Codex folds a paste of more than 1,000 characters into a "[Pasted Content N chars]" placeholder. DazeDev sends pastes of up to about 2,000 characters in pieces, so Codex shows the text itself.
- In terminals and sessions DazeDev starts, `$VISUAL` and `$EDITOR` point to the DazeDev editor. Press **Ctrl+G** in Codex or Claude Code and the prompt opens in an edit pane over that terminal, with any folded paste shown in full.
- **Save** (Ctrl+Enter or Ctrl+S) returns the text to the agent, **Cancel** leaves it as it was, and **Terminal editor** continues in your own editor. Your own `$VISUAL` or `$EDITOR` is kept as that terminal editor; otherwise nano or vi is used.
- If this desktop is not showing the terminal, the terminal is in its own window, only the phone is connected or the connection drops, the terminal editor opens straight away. Agents started before you updated DazeDev need a restart to use the pane.
- `git commit` in these sessions also opens the pane unless `GIT_EDITOR` or `core.editor` is set.

## Shortcuts and history

- **Settings → Keyboard shortcuts** changes the keys for the command palette (Ctrl+K), terminal search (Ctrl+Shift+F) and full screen (F11). DazeDev refuses a key that another action or a voice hotkey already uses. **Reset to defaults** puts them back.
- While a terminal has focus, Ctrl+letter keys without Shift or Alt go to the terminal, so Ctrl+K works in your shell or editor there.
- **Settings → Terminal → History lines** sets how much scrollback each terminal keeps in memory: 10,000 lines by default, up to 100,000. All open terminals share at most 200,000 lines and 128 MB of images, so with many terminals each keeps less. Terminals you cannot see (background tabs and other workspaces) get a smaller share.

## Open files beside your terminals

- Right-click a terminal → **Files and previews**, then enter a path.
- Preview source, Markdown, HTML and images. Use **Edit source → Save file** for Markdown.
- **Upload** transfers local files to the chosen host.

<img src="assets/screenshots/files.png" width="680" alt="A Markdown file open in DazeDev's tiled viewer." />

## Keep your setup

- Keep the adjacent `data/` folder when moving or manually updating DazeDev.
- Closing a tmux view leaves the session running. Plain shells may end; PowerShell offers background sessions.

## On Android

- The home page separates hosts and hostless workspace groups. Hold a workspace's drag handle to reorder it, or use its menu. Host workspace order and tab grouping are saved to the host and appear on desktop after refresh.
- Open a hostless group and choose **Add terminal views** to copy views from connected hosts. Use a terminal's options to remove its view from the group; its original session stays open. These groups stay on the phone. A group prompts for the hosts it needs, using each host's own authentication.
- In a workspace, choose **Group terminals as tabs**, select terminals and confirm. Switch the resulting tabs above the terminal.
- **Stay connected in the background** keeps SSH connections open after you leave the app: Off, 5 minutes (the default), 15 minutes, 1 hour or Until I disconnect. A notification counts down and offers **Disconnect all**. When the time runs out, hosts disconnect, and the app tells you why when you return.
- When you return, DazeDev checks each connected host. A host that does not answer within a few seconds shows Disconnected with the reason. Hosts that need no typed password reconnect and reopen your terminal by themselves; for the others, tap **Reconnect** to sign in again and reopen the same session. Typed input is never resent.
- While the on-screen keyboard is open, a row above it offers Esc, Tab, one-shot Ctrl and Alt, arrows and hard-to-reach symbols; swipe it sideways for Home, End, PgUp and PgDn. A key fires only when you tap it; a swipe never presses one. Turn the row off with **Keys above keyboard**.
- **New session** lists every agent the host reports, including custom agents added on desktop.
- Tap **Voice** to open a blank prompt and start listening. **End** stops for editing; **Enter** submits once and closes the prompt. Failed or uncertain delivery keeps the draft.
- **Changelog** shows both products, with All, Desktop and Mobile filters. Release history works from bundled or saved notes when offline. Android update checks use only the public GitHub repository. The app downloads the new APK itself and installs it only if its SHA-256 matches the release’s `SHA256SUMS` file; development APKs can still be installed offline. Desktop-only releases do not trigger Android update prompts, and mobile-only releases do not trigger desktop prompts.

[Update help](../apps/desktop/UPDATES.md) · [Build instructions](BUILDING.md) · [Credits](CREDITS.md)

Portable settings are plaintext; the startup PIN is an app access gate, not encryption. DazeDev restricts its data folder to the current user. If the filesystem cannot enforce private permissions, existing settings are preserved; move the app and its data into a private user folder, or use `DAZEDEV_DATA_DIR` for a private storage location.
