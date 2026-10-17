<p align="center">
  <img src="apps/desktop/public/brand.svg" width="80" height="80" alt="DazeDev logo" />
</p>

<h1 align="center">DazeDev</h1>

**Terminals, but faster.**<br>
*Talk to many instead of typing to a few.*

I got tired of typing The Lord of the Rings into agents [(seriously, I proved it)](https://github.com/AdamChesters/FrodoTappins-There-And-Backspace-Again), and my fleet of terminal windows was growing, so I made something to organise sessions and speak to them. On-device. For free. It got better and better. Now it’s yours.

![DazeDev terminal workspace with the code-review terminal highlighted while listening for voice input.](docs/assets/dazedev-readme.png)

## Core features

- **Quick organisation:** split, tile and drag terminals or whole groups between workspaces. Attach to existing tmux sessions.
- **Local voice + tidy-up:** dictate straight into a terminal. Speech recognition and text cleanup run on your machine, with no API fees after downloading the models.
- **Sessions + repos:** one click to see, join, or make tmux sessions. One click to see, select, and auto-prompt your agent to get up to speed with your repo.
- **Any agent:** install and start Codex, Claude Code, Gemini, Copilot, Cursor, Qwen, goose, Amp, Aider and more, or add your own with its launch command.
- **Global search:** find text across open terminal buffers, or narrow to a host, workspace or terminal.
- **Global broadcast:** review and send one message to selected sessions across hosts and workspaces.
- **Config on your host:** remote workspace layouts and preferences stay on the host. Reconnect from another device to the same setup and tmux sessions.
- **Local Windows agents:** use native PowerShell or WSL, alongside your remote SSH hosts.

## Quality of life

- Persistent PowerShell sessions and detachable windows for extra monitors.
- A built-in SSH client: one sign-in per host for every terminal, using your `~/.ssh/config` and known_hosts.
- File uploads, source viewing, Markdown editing and image previews.
- Long pastes show in full in Codex, and Ctrl+G opens an agent's prompt in a DazeDev edit pane.
- Dictation dictionary, configurable hotkeys and transcript recovery.
- Custom colours, terminal notifications and an optional startup PIN.
- Signed updates that preserve your settings and downloaded models.

## Get started

- [Download the Windows portable ZIP](https://github.com/AdamChesters/DazeDev/releases), extract it and run `DazeDev.exe`.
- Requires Windows x64 and WebView2. WSL and remote Unix hosts also need Python 3.9 or newer and tmux.
- Keep the adjacent `data/` folder when moving or manually updating the app.

## Coming soon

- **Mobile:** an app with on-device voice input.
- **Linux Mint:** test .deb and AppImage packages already build and install in clean Mint 21.3 and 22.3 containers. A check on a real Mint machine comes before the first download.

Frontier voice is a later research topic. Provider access, subscription eligibility,
privacy, latency and cost need to be established before a feature is proposed.

**macOS and Linux testers wanted.** [Join us on Discord](https://discord.gg/fs4WyaQPA).

In-app feedback goes straight to our Slack. Ask, get.

[User guide](docs/USER-GUIDE.md) · [Updates](apps/desktop/UPDATES.md) · [Banner downloads](docs/media.md)

The Android companion lives in [apps/android](apps/android/README.md). See [platform support](docs/PLATFORM-SUPPORT.md) for its current scope and build requirements.

Built with Tauri, Svelte, xterm.js, russh and the projects in [Licenses and credits](docs/CREDITS.md). Full [third-party notices](apps/desktop/src-tauri/licenses/) are bundled with the app.

## Feedback and support

<p align="center">
  <a href="https://discord.gg/fs4WyaQPA"><img src="assets/support/social-discord.svg" width="38" height="38" alt="Discord" title="Discord"></a>
  <a href="https://www.reddit.com/r/AdamChsLab/"><img src="assets/support/social-reddit.svg" width="38" height="38" alt="Reddit" title="Reddit"></a>
  <a href="https://github.com/AdamChesters"><img src="assets/support/social-github.svg" width="38" height="38" alt="GitHub" title="GitHub"></a>
  <a href="https://www.linkedin.com/in/adamchesters/"><img src="assets/support/social-linkedin.svg" width="38" height="38" alt="LinkedIn" title="LinkedIn"></a>
  <a href="https://adamch.com"><img src="assets/support/social-website.svg" width="38" height="38" alt="AdamCh.com" title="AdamCh.com"></a>
</p>

<p align="center">Thanks for using DazeDev. I like to fix bugs and implement features rapidly. Issue? Idea? Let's work on it! Contact me anywhere you prefer.</p>

<h3 align="center">Support</h3>

<p align="center">I love making things to have fun, share fun, and make life a lil better. If you got value out of one of them, feel free to buy us a coffee. Or a house. Push comes to shove, I prefer Aston Martin. Supporters get golden crowns on the Discord / tags on the subreddit!</p>

<p align="center">
  <a href="https://github.com/sponsors/AdamChesters"><img src="assets/support/support-github.svg" width="220" height="86" alt="GitHub Sponsors" title="GitHub Sponsors"></a>
  <a href="https://buymeacoffee.com/adamch"><img src="assets/support/support-buymeacoffee.svg" width="220" height="86" alt="Buy Me a Coffee" title="Buy Me a Coffee"></a>
  <a href="https://www.paypal.com/donate/?business=KLHSZPXTSVSAU&amp;no_recurring=0&amp;item_name=I%27ve+donated+to+lots+of+small+creators+for+their+useful+little+tools%2C+now+I+create+them.+Dig+one?+I%27d+love+your+support.&amp;currency_code=AUD"><img src="assets/support/support-paypal.svg" width="220" height="86" alt="Support with PayPal" title="Support with PayPal"></a>
</p>
