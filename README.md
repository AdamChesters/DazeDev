# DazeDev

A portable Windows terminal workspace for local and remote hosts.

Create a workspace with a name, host and terminal count. Local Windows hosts use persistent PowerShell sessions; remote hosts offer plain shells and tmux sessions. Use **Sessions** to join a running session or create a named one. New sessions can optionally launch an agent in a selected repository; existing sessions attach without sending prompts.

Download the Windows portable ZIP from [GitHub Releases](https://github.com/AdamChesters/DazeDev/releases), extract it into a writable folder, and run `DazeDev.exe`. The app starts maximized; press F11 to toggle fullscreen. Client settings, voice models and WebView data stay in `data/` beside the executable. First launch moves historic DazedDev settings/models into that folder, retaining conflicts under `data/migration-recovery/`.

Split buttons add one terminal immediately. Right-click a pane for directional splits.
Click a terminal title bar, then Shift-click other title bars to select a group.
Drag any selected title bar to move the group. Hold Ctrl while
dragging to attach another view of the same tmux session, or to open a fresh shell
when the source is a plain terminal. Its original host stays connected, even in
another host’s workspace. Drop directly on a sidebar workspace to place the
terminals in a vertical stack on the right, without opening that workspace.
Drop inside the current workspace to choose a docking position. Escape or an
invalid drop cancels. A plain title-bar click selects just that terminal.
Drag workspace names in the sidebar to reorder them within their host. The
workspace menu also has Move up and Move down; Alt+Up/Down does the same.
Terminal controls shrink in stages: Repo + tmux + Menu, then tmux + Menu, then
Menu alone. Local Windows terminals show Sessions in place of tmux.
Right-click the title bar for equivalent Move/Duplicate workspace actions.
Use **Other screen**, or drag a terminal title outside the app, to open it in a
separate window. Move that window between monitors with its normal title bar.
It stays in its original workspace and remains visible when another workspace
is selected. Selecting a workspace raises its terminal windows above overlapping
windows. **Return to workspace** or closing the separate window docks the same
session; it does not end the shell or tmux session.
Drag its header to dock at a highlighted edge or retile centrally; double-click
that header to maximise/restore. Balance prefers compact tiling, including a 2×2 grid for four terminals; narrow
windows reduce the column count. Arrange in columns always uses one row. Layout changes keep
existing terminals alive. Detaching from tmux returns to a shell in that pane.
If a connection stalls, use Reconnect host in its menu or the terminal retry button.
When a saved workspace includes another host with configured reusable access,
DazeDev offers to connect it. Keep disconnected leaves it alone for this session.
Click a secondary host label above a terminal to connect or disconnect that host.
Disconnect applies to all its terminals: tmux sessions survive, plain shells end,
and the displayed output remains available.
Host health checks run every 15 seconds; a failed check clears connected status.
Recovering host management preserves terminal connections that are still working,
and reconnecting never resends previous input.

Dictation pastes directly into the captured terminal, without Enter by default.
Settings has separate configurable hotkeys for terminal dictation and IDE commands.
Both work only while DazeDev is focused; mouse side buttons keep their normal
behavior in other apps. Losing focus cancels active capture. Other device buttons
must emit a supported keyboard or mouse event.

While listening, Enter finishes recording and leaves terminal text for review,
even if automatic Enter is enabled. Escape abandons preparing or active capture.
Intentional stops include a 750 ms audio tail; cancellation is immediate. IDE
commands open a separate early-alpha modal: edit the transcript, press Enter to
interpret it, review the proposed actions, then choose Implement. Interpretation
alone never executes the proposal. The sidebar Voice command button opens the same
modal. While a command or broadcast modal is open, either voice hotkey transcribes
into that modal instead of the previously focused terminal.

The sidebar Broadcast button opens an editable message for all terminals. All hosts
and All workspaces are selected by default; clear either to limit the recipients.
Review the recipient list, then choose Send to paste the message and press Enter,
including in bare shells. Verified views of the same tmux session receive it once.
Disconnected panes are skipped; failed or uncertain deliveries are reported without
retrying.

Updates appears first in Settings and checks whenever Settings opens. Automatic
checks run at startup and every 30 minutes unless disabled. The sidebar version
indicator shows update status and opens the update dialog. Download and restart
still require confirmation.

Dictation recovery copies are available from the terminal title-bar right-click menu. Copy the last dictation or its original transcription, or review undelivered text. Recovery does not display repeated banners or resize the terminal.

Right-click hosts or workspaces to remove them. Remote tmux sessions survive. Tailscale SSH can use device identity; normal SSH supports existing keys, certificates and passwords. First-run voice setup downloads Parakeet, Silero and S1-mini together.

The app checks for newer signed releases at launch and asks before downloading/restarting. Manual checks and the startup toggle are in Settings. Updates use published GitHub releases over HTTPS without a GitHub login. See [UPDATES.md](UPDATES.md) for update behavior and recovery.

Windows x64 and Microsoft Edge WebView2 Runtime are required. The target machine needs Python 3 and tmux; local Windows sessions can use native PowerShell or WSL. Native PowerShell sessions run in a separate local manager, survive closing DazeDev, and can be reattached or handed off to a PowerShell console window without restarting their process. End them from Sessions when finished. Windows shutdown ends them.

## Development

`npm ci`, `npm run gate`, and `cargo test --manifest-path src-tauri/Cargo.toml --no-default-features --locked` run source checks. `node scripts/prepare-runtime.mjs` downloads pinned native voice runtimes. `npm run tauri -- dev` launches the desktop app with platform build prerequisites installed.

Windows CI compiles the app, tests speech and migration, and creates one portable ZIP through `scripts/package-portable.ps1`. Models are downloaded in-app and retained across updates.

## Feedback and support

Open **Feedback / Donate** at the bottom of the sidebar for feedback, updates,
Discord and donation options. Feedback uses a public HTTPS relay. Your contact details are remembered locally. Failed messages stay
in the form until delivery is confirmed.

I love making things. Anything I've ever built has been to have fun, share fun, and make life a bit easier. If you got value from one of these things, and you'd like to chuck us a coffee, a bottle, or a god damned Ferrari, go your hardest. Then hustle over to discord to claim your supporter role!

- [GitHub Sponsors](https://github.com/sponsors/AdamChesters)
- [Buy Me a Coffee](https://buymeacoffee.com/adamch)
- [Donate with PayPal](https://www.paypal.com/donate/?business=KLHSZPXTSVSAU&no_recurring=0&item_name=I%27ve+donated+to+lots+of+small+creators+for+their+useful+little+tools%2C+now+I+create+them.+Dig+one?+I%27d+love+your+support.&currency_code=AUD)
- [Join the Discord](https://discord.gg/fs4WyaQPA)

More projects: [GitHub](https://github.com/AdamChesters) | [AdamCh.com](https://adamch.com).

## Branding

`public/brand.svg` is the single icon source. `Brand.svelte` shares the wordmark
and icon in the header and home screen. `scripts/generate-icons.mjs` regenerates
Windows/package icon assets from that SVG on every build.

## Terminal controls

Host connections are checked periodically and when the app regains focus or network
access. Interrupted connections retry; detached terminals also offer a reconnect
button. Reconnection preserves existing tmux sessions and never replays failed
input. A plain shell starts fresh if its connection was lost. Disconnect in Settings
stops automatic host reconnection.

Ctrl+click web links (Cmd+click on macOS) to open them in this device's browser. Ordinary clicks and drags remain terminal interaction and selection. Right-click a terminal
for Copy, Paste, Select All, Find, output export, colors and split actions.
Ctrl+Shift+C copies; Ctrl+V or Ctrl+Shift+V pastes text or clipboard images/files.
Ctrl+Shift+F, the title-bar Search button, or Find in the terminal menu opens a
compact search pop-up. Choose Terminal, Workspace, All on host, or Global to
search live terminals' current output and scrollback. Match case and Regular
expression are optional. Results scroll inside the pop-up; Go to selects and
highlights a match. Enter advances through matches and switches workspace or
host as needed; Shift+Enter goes back. The results collapse to a small search
bar with Previous, Next and Results controls. Detached terminals get the same
navigation bar. Escape closes search. Queries and results are held only while
search is open; cleared scrollback, closed/disconnected terminals and unopened
sessions are unavailable. No output archive or query history is saved.
Settings controls font size, history length, GPU rendering, images, ligatures and remote
clipboard access. Remote copy is enabled by default; reading the local clipboard
requires selecting the read/write option. Selected-host clipboard permissions can
override the default; screen-reader terminal output is available in Settings. Exported `.ansi` files preserve terminal
formatting; they do not restore processes.

Workspace settings offer muted/vivid color presets and custom background/text
colors. Terminals inherit their workspace colors unless individually overridden
through Terminal colors. Reset restores inheritance. The palette button beside
Settings cycles the application theme across the title bar, sidebar and app surfaces;
terminal backgrounds stay unchanged. Drag the sidebar edge to resize it, or focus
the divider and use arrow keys. Its width is remembered on this device. Names wrap
and the active workspace uses your chosen highlight effect. Notifications appear
in the title bar.

Listen records into the selected terminal. A boxed status in its secondary bar
shows Preparing, Listening and Finishing, centered above the terminal. Only active listening fades in the
terminal border, adds subtle frosting to the rest of the app and gently lifts the receiving pane.
Stopping restores the normal view; a maximized pane stays within the available
space. Blue energy scales particle density with border length, with drifting smoke, rounded paths and independent brightness pulses.
Configure toggle or hold-to-talk in Settings. Partial transcription appears inside that terminal during recording;
the cleaned result is pasted when recording stops. These previews can change and
are not sent to the running program. A faster model updates them more often. The hotkey reveals and focuses its
destination; that workspace and terminal remain selected through cleanup and
paste. Stop dictation before switching workspaces or closing its destination.
Settings offers Blue energy, Rainbow shimmer and Static colour recording borders,
with a preview and a colour picker for the static option. Highlights animate independently
of Windows animation settings by default. Keep highlights still disables movement;
Follow system is an explicit opt-in. This
choice is saved on this device and affects highlights only.

Install agents in the sidebar opens host discovery. Install and start creates a tmux session showing the
installer and the agent's own login interface. Codex and Claude use official
native Linux installers; Gemini/OpenCode use a user-local npm prefix and require
Node.js 20+ and npm. Missing system prerequisites are explained in the terminal.
Model and Effort buttons open the supported agent’s live picker in its terminal.
Codex combines model and effort in one picker. They do not restart the agent.

Host preferences in Settings include an editable repository-start prompt and a
preview. Variables use `{{name}}`: `host` (connection name), `address` (hostname),
`user` (host login), `repo` (working directory), `identity` (GitHub identity),
`agent`, `model`, `effort`, and `guidance` (additional host instructions).
Templates apply only to newly created agent sessions with a selected repository
and orientation enabled. Existing tmux sessions attach without a prompt.

Voice settings detect logical CPU count, RAM and usable Vulkan devices, recommend
a starting model, and explain acceleration availability for the selected model.
Recommendations are hardware-based estimates; actual speed depends on model,
drivers and workload. Larger Whisper models can lag on CPU. Changing the model,
acceleration or cleanup choice unloads the previous voice runtime.

Audio capture runs separately from native inference and sends binary PCM data.
A RAM queue holds up to 15 minutes of pending audio (about 58 MB) so short inference
stalls do not interrupt recording. When inference falls behind, it batches audio
and skips redundant previews. Final transcription still processes all accepted audio.
By default, cleanup loads after recording and exits after finalization. The optional
preload setting keeps it resident; release mode also unloads speech models between recordings. Border animations use
precomputed movement and opacity changes. Off-screen energy effects pause.

Settings puts voice model selection and downloads in one list, followed by hotkey
assignment and behavior. Selecting a missing model downloads it before switching.
The voice alert stays visible through final transcription, cleanup model loading,
the S1-mini tidy-up pass (with output progress), and paste. Cleanup uses verified
speculative decoding to accelerate repeated text without bypassing the model.

Exiting an agent returns to a shell. Exiting a DazeDev-created tmux pane returns
to the host terminal; Ctrl-D in that shell starts another shell in the same pane.
Repeated immediate shell failures stop recovery and offer reconnection. Plain
terminal status is measured from the active process tree; manual tmux commands remain available.

Dictation pastes into its selected terminal even when the foreground application
appears busy. Automatic Enter remains a separate preference. A recovery copy stays
available for two minutes after insertion; undo or remove text in the receiving
application if necessary. An unavailable destination preserves the transcript for
review and copy. Recovery drafts are memory-only and do not survive a forced exit.

Microphone interruption finalizes accepted speech. Tidy-up preserves technical
literals and retains the original for review if its checks detect a change.
Model downloads can be canceled; cancellation takes effect between network reads.
Model and update staging check disk space. Hidden terminals release GPU renderers
while keeping their processes and scrollback.

Focus is device-local; workspace structure remains on the host. Reloading a host
profile preserves up to three previous local copies in `data/device.json` under
`profileBackups` and exports a recovery copy. Damaged settings are preserved and
a valid backup is recovered when available. Export diagnostics in Settings saves
up to 200 lifecycle/timing events without terminal output, dictation, authentication
text or command arguments. Closing a plain terminal warns that its work may stop;
closing an attached tmux view leaves the remote session running.

Remote programs in newly opened DazeDev terminals can request the desktop browser
through a scoped `BROWSER` variable and `xdg-open` shim. Existing running agents
need restarting to inherit that environment. Only HTTP(S) URLs are supported;
remote localhost URLs and OAuth callback forwarding are not supported. If different
desktops are attached to the same tmux session, the request fails rather than
opening on an arbitrary machine. System browser settings are unchanged.

## macOS and Linux test builds

Alpha.16 adds separate portable archives for Apple Silicon, Intel Macs and Linux
x64. Extract the whole folder into a writable location. On macOS open `DazeDev.app`;
on Linux run `./Start\ DazeDev.sh`. Settings and downloaded models remain in the
adjacent `data/` folder. Keep that folder when manually upgrading. These test builds
do not use the Windows in-app updater.

The build targets macOS 14+ and Linux Mint 22.x (Ubuntu 24.04). Mac builds are
ad-hoc signed, not Apple-notarized; use macOS Privacy & Security's app-specific
Open Anyway option if required. Do not disable Gatekeeper. On Mint, install missing
runtime dependencies with `sudo apt install libwebkit2gtk-4.1-0 libgtk-3-0t64 libgomp1`.
Local Unix/WSL hosts additionally need `python3` and `tmux`; native Windows PowerShell hosts need neither; remote SSH hosts need these
on the remote machine. Allow microphone access when prompted.

Parakeet, Whisper, Silero and S1-mini use architecture-specific native CPU runtimes.
GPU acceleration and system-wide hotkeys on macOS/Linux are not included in these
first test packages; use Listen or the focused-app hotkey. Compatibility checks use
recorded audio on native CI runners and do not establish physical microphone or
real-time performance on every tester's hardware.

One-click agent installation currently targets Linux/WSL hosts. On a local Mac, install agents with their official installers; remote Linux agent installation remains available.

## Terminal tools in the development build

- **Repo** selects a repository and pastes its host's configured orientation
  prompt into the existing terminal for review. It does not create a session or
  press Enter. Select the repository host explicitly after manual nested SSH.
- **Upload** opens the local file chooser for files or screenshots, then transfers
  to an existing directory on the chosen connected host. You can track progress or cancel the transfer.
  Files can be up to 256 MiB; uploads never overwrite existing files.
  Paste path for review is a separate action and does not press Enter.
- **Files** opens a Monaco viewer beside the terminal in the workspace tiling layout.
  Drag its title bar to dock beside another tile or into another workspace; Escape
  or dropping outside a tile cancels. Viewers share splitters and Balance/Columns
  with terminals. Double-click the title or use Expand to fill the workspace, and close
  it with X without interrupting terminals. Open viewers and their placement last
  for the current app session; save edits before exiting. Open multiple viewers to compare files,
  including separate views of the same file. Open an explicit UTF-8 source path
  (up to 512 KiB) for syntax highlighting, search, folding and bracket matching.
  Ctrl-click (Cmd-click on macOS) supported file paths printed in a terminal to open
  them on that terminal’s host. Use full paths, ~/ paths or relative paths. Relative
  paths use the workspace directory; bare paths also try the host’s home directory
  when absent there. Quote paths with spaces or use Markdown links. HTML and Markdown open in preview mode. Images
  support wheel zoom, drag to pan and double-click to fit (up to 16 MiB).
  Source files are read-only; Markdown and agent rules support explicit Edit,
  Save and Discard. Saves refuse stale revisions and preserve file permissions.
  Markdown preview is inert. HTML preview supports inline CSS but blocks scripts,
  links and external/relative resources. CSS files themselves show source.
  Document discovery still finds Markdown and rules across user, system, ancestor,
  repository, nested and referenced locations; results do not prove an agent
  loaded a file. The viewer is bundled locally and loads only when opened.
- Click the **host label** to change one pane's connection while keeping its
  workspace and layout. The previous tmux session stays on its original host;
  changing from a plain shell closes that shell. Other-host panes show a distinct
  host-label glow using the selected highlight style.
- The status line checks actual tmux attachment, including manual attach/detach.
  Green means tmux, pulsing red means a plain terminal, and gray means unknown.
  Nested SSH shows the original connection with an unverified-destination label.
  Right-click the pane to hide or restore the status line; the choice is saved.
- **Terminal notifications** can show an alert and play a soft sound. They use
  explicit OSC notifications or shell completion markers, so support depends on
  the agent/shell integration. They are off by default and never infer completion
  from silence.
- **Voice model memory** offers the existing load-on-first-use behavior, optional
  preload/retention, or release after every recording. Preload uses more RAM and
  does not activate the microphone or download models. It is not enabled by default.

The file tools use the authenticated host connection. They do not follow nested
SSH automatically: choose the destination host and path explicitly. The host must
already be configured and connected in DazeDev.

## Dictation dictionary

Settings includes a dictionary for preferred spellings and misheard phrases across all workspaces and terminal hosts. Add full phrases such as “example project” → “ExampleProject”, then edit, disable or delete them as needed. Select text in a terminal and right-click **Add selection to dictation dictionary** to review the “Heard as” and “Write as” fields before saving. This affects future dictation only; it does not change terminal history or send terminal input.

The dictionary stays on your device with a last-good backup. JSON export/import lets you keep another copy or move entries between devices. Optionally choose an existing SSH host in Settings, connect to it, then review and accept **Merge and enable sync on this device**. Sync uses that host user's DazeDev configuration directory, not a DazeDev cloud account. Each device keeps its own offline copy; subsequent synchronization runs while the app is open and the host connection is available. Concurrent edits or deletions remain visible as conflicts and are not applied to dictation until resolved. Deleted entries retain revision records so an older offline copy cannot restore them accidentally.

Whisper receives a small preferred-phrase hint when recording starts; other engines use the same final-text corrections without unsupported vocabulary controls. Portable copies and restored dictionaries keep their entries but receive a fresh writing identity when their separate user-configuration record does not match, so later sync cannot silently discard new offline edits.

## Host colors and tmux settings

Host options includes Host colors and, when connected, tmux settings. Terminal
headers use their own colors first, then workspace colors, then their original
host's colors. Moving or duplicating into another workspace retains the visible
source color. Terminal colors → Use workspace colors restores inheritance.
Host colors are saved on this device; pane and workspace colors stay with the
workspace profile. Cross-host labels keep the original host's color.

The tmux panel reads the selected host's socket, version and effective settings.
Choose host defaults or a session and window before applying a change. Apply now
changes the live server only and can affect its other clients. Use default removes
a session/window override. Copy config for future sessions copies the current
host defaults for review; it does not save or reload a configuration file.
The panel also distinguishes host history from DazeDev scrollback and shows
copy/view mode, application mouse reporting and alternate-screen state.

### Broadcast messages

Broadcast in the sidebar opens an editable message. Both voice shortcuts dictate into the open Broadcast or Voice command dialog; Enter finishes recording and Escape cancels it. Review recipients, then explicitly select **Send to all … terminal sessions** to paste the message and press Enter, including in bare shells.

All hosts and All workspaces are selected initially. Clear either option to restrict recipients to the host or workspace selected when the dialog opened; the filters combine. Verified tmux mirrors receive one message per source session. Other connected terminals are individual recipients. Disconnected panes are skipped, changed recipients require another review, and uncertain deliveries are reported without retrying or replaying input.

Terminal right-click actions start with **Split**. Open it for Up, Left, Right and Down in a compact directional layout. Drag an action's grip to reorder the menu, or focus its grip and press Alt+Up/Down. The order is saved on this device and reused across terminals; conditional actions keep their position when they become available again. Escape or dropping outside the menu cancels a reorder.

### Startup PIN

Settings → Startup PIN can require a 6–12 digit PIN before DazeDev shows workspaces or connects to hosts. It is off by default and applies when the app starts; enabling it does not interrupt the current session. Changing or disabling an enabled PIN requires the current PIN.

The PIN is an app startup gate, not file encryption or a replacement for your operating-system lock. It does not protect files from someone who already controls your OS account. DazeDev stores a salted, slow verifier instead of the PIN and limits repeated attempts across restarts. Keep your PIN safe and back up the complete app data folder: there is no in-app PIN recovery or bypass, and missing or damaged protection files leave the app locked.

## Credits

Built with [Tauri](https://github.com/tauri-apps/tauri), [Svelte](https://github.com/sveltejs/svelte), [xterm.js](https://github.com/xtermjs/xterm.js), [Monaco](https://github.com/microsoft/monaco-editor), [DOMPurify](https://github.com/cure53/DOMPurify), [Lucide](https://github.com/lucide-icons/lucide) and [portable-pty](https://github.com/wez/wezterm).
Startup PIN verification uses [ring](https://github.com/briansmith/ring).
Voice uses [Whisper](https://github.com/openai/whisper), [NVIDIA Parakeet](https://huggingface.co/nvidia/parakeet-unified-en-0.6b), [Handy Computer conversions](https://huggingface.co/handy-computer), [Silero](https://github.com/snakers4/silero-vad), [S1-mini by Superwhisper](https://huggingface.co/superwhisper/s1-mini), [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp), [llama.cpp](https://github.com/ggml-org/llama.cpp) and [ONNX Runtime](https://github.com/microsoft/onnxruntime)/[ort](https://github.com/pykeio/ort). See [licenses](src-tauri/licenses/) or Settings → Licenses and credits for full notices.

Build Windows releases with `scripts/build-windows.ps1` from an initialized MSVC environment. It builds both application binaries and remaps local source paths in Rust and native library diagnostics. Portable packaging places this helper under `runtime/`, so signed updates from earlier versions can install it while preserving `data/`. Keep the complete portable folder together.

Release names use Alpha-V0.3, then incremental 0.3.01, 0.3.02 and onward. Stable releases begin at 1.0, followed by 1.0.01 and onward. Signed updater and package identities retain compatible semantic versions (for example, Alpha-V0.3 is `0.3.0-alpha.0`).
