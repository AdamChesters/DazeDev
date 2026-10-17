# Platform support

| Application | Code and build                                         | Current boundary                                                                                                                           |
| ----------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Desktop     | `apps/desktop`, Svelte and Tauri                       | Windows is the qualified platform. Linux x64 test packages (.deb, AppImage, portable archive) are CI-verified in Mint containers; see below. macOS remains unvalidated. |
| Android     | `apps/android`, Kotlin and Compose, Android 9 or newer | SSH/tmux companion with host/workspace folders and one interactive terminal at a time. Separate application ID and device settings.        |

Android uses the desktop host helper over authenticated SSH. Hosts need Python 3 and tmux. Saved workspace layouts stay intact; the phone uses them to order terminal rows. Credentials stay on the device. A changed workspace revision keeps an unsaved draft locally. Reload the host profile, then review and reapply edits that do not conflict with newer changes. Windows-local broker sessions are not available through this SSH transport.

Tap the terminal title to search connected hosts and their workspace terminals. The separate tmux-window picker changes the active window for all clients attached to that session.

The terminal uses the same pinned xterm implementation as desktop, with a Keys panel, native copy/paste controls and bounded SSH streams. Reconnecting discards queued input. Closing the app detaches its client and leaves tmux sessions running.

Moonshine English Medium Streaming provides local speech input after explicit preparation. First use downloads approximately 269 MB. Transcripts remain editable drafts and are pasted without automatically pressing Enter. Other speech models and local cleanup are not offered until device performance and correctness are verified.

Android and desktop releases are independent. Android tags use `android/v…`; desktop tags use `v…`. Android packages must never contain the desktop updater's `latest.json`.

## Linux desktop test packages

Build and verification steps are in [building](BUILDING.md#linux-packages). Status as of 0.3.9-alpha.0:

@@LINUX_RESULTS@@

Linux behaviour that differs from Windows:

- The .deb and AppImage keep settings, models and logs in `~/.local/share/DazeDev`; the portable archive keeps `data/` beside the app.
- Updates are manual: install the newer .deb or replace the AppImage. The app does not download updates on Linux or macOS.
- Hotkeys work while DazeDev is focused. System-wide hotkeys are Windows-only.
- Voice runs on the CPU. No GPU acceleration or performance claim is made for Linux.
- Local sessions need `python3` and `tmux` on this machine (the .deb recommends them). Remote hosts need them on the host instead.

Not yet verified on a physical Mint machine: a real microphone through PipeWire, Wayland sessions, GPU-accelerated WebKit rendering (if the window is blank, try `WEBKIT_DISABLE_DMABUF_RENDERER=1`), the AppImage through FUSE outside a container, and the menu entry and icon in the Cinnamon desktop.

Known dependency advisory: Tauri's Linux stack (Tauri 2.11 to 2.12, wry, tao, webkit2gtk-rs 2.0.2) uses the GTK 3 bindings and `glib` 0.18.5. RUSTSEC-2024-0429 marks `glib::VariantStrIter`'s iterator methods unsound before 0.20. No crate in DazeDev's dependency tree, and no DazeDev code, calls `VariantStrIter` or `Variant::array_iter_str`, so the affected code is unreachable. This is accepted until Tauri's Linux backend moves to newer bindings (gtk-rs published GTK 3 bindings on glib 0.22 in September 2026; webkit2gtk-rs and Tauri have not adopted them yet). Recheck with `cargo audit` when upgrading Tauri.

See [building](BUILDING.md) and [Android build instructions](../apps/android/README.md). iOS requires a separate native app; there is no iOS target in this repository.
