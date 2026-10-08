# Platform support

| Application | Code and build                                         | Current boundary                                                                                                                           |
| ----------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Desktop     | `apps/desktop`, Svelte and Tauri                       | Existing Windows application; Unix build scripts share the same source. A source layout does not establish Linux/macOS release validation. |
| Android     | `apps/android`, Kotlin and Compose, Android 9 or newer | SSH/tmux companion with host/workspace folders and one interactive terminal at a time. Separate application ID and device settings.        |

Android uses the desktop host helper over authenticated SSH. Hosts need Python 3 and tmux. Saved workspace layouts stay intact; the phone uses them to order terminal rows. Credentials stay on the device. A changed workspace revision requires reloading; an unsaved draft is retained locally. Windows-local broker sessions are not available through this SSH transport.

The terminal uses the same pinned xterm implementation as desktop, with a Keys panel, native copy/paste controls and bounded SSH streams. Reconnecting discards queued input. Closing the app detaches its client and leaves tmux sessions running.

Moonshine English Medium Streaming provides local speech input after explicit preparation. First use downloads approximately 269 MB. Transcripts remain editable drafts and are pasted without automatically pressing Enter. Other speech models and local cleanup are not offered until device performance and correctness are verified.

Android and desktop releases are independent. Android tags use `android/v…`; desktop tags use `v…`. Android packages must never contain the desktop updater's `latest.json`.

See [building](BUILDING.md) and [Android build instructions](../apps/android/README.md). iOS requires a separate native app; there is no iOS target in this repository.
