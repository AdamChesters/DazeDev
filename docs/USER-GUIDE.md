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
- Native PowerShell needs no WSL. WSL and remote Unix hosts need Python 3 and tmux.
- Remote layouts and preferences stay on the host; reconnect from another device to reuse them.
- **Tailscale SSH** uses the destination's Tailscale SSH service. Native Windows cannot serve it. For WSL, connect to the Linux device's own tailnet name and username. Ordinary SSH over Tailscale uses separate SSH credentials; DazeDev's remote host still needs Python 3 and tmux.

<img src="assets/screenshots/connect.png" width="490" alt="New host dialog with Local Windows PowerShell selected." />

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
- Open a hostless group and choose **Add terminal views** to copy views from connected hosts. These groups stay on the phone. A group prompts for the hosts it needs, using each host's own authentication.
- In a workspace, choose **Group terminals as tabs**, select terminals and confirm. Switch the resulting tabs above the terminal.
- Tap **Voice** to open a blank prompt and start listening. **End** stops for editing; **Enter** submits once and closes the prompt. Failed or uncertain delivery keeps the draft.
- **Changelog** shows both products, with All, Desktop and Mobile filters. Release history works from bundled or saved notes when offline. Android update checks use only the public GitHub repository and offer an APK download; development APKs can still be installed offline. Desktop-only releases do not trigger Android update prompts, and mobile-only releases do not trigger desktop prompts.

[Update help](../apps/desktop/UPDATES.md) · [Build instructions](BUILDING.md) · [Credits](CREDITS.md)
