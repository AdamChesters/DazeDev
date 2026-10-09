# Host protocol compatibility fixtures

The desktop host helper at `apps/desktop/src-tauri/resources/host.py` is the wire implementation. Android stages that exact file into generated assets. Replies are nonce-scoped `DAZED:<nonce>:@<index>/<total>:<base64>` lines; requests are newline-terminated base64 JSON with `id`, `method` and `args`. Indices begin at zero.

Profiles use schema 1 and compare-and-swap revisions. Preserve unknown fields, pane IDs, desktop layout and focus. Mobile focus is device-local. A terminal target includes host machine/user/socket identity, tmux session ID and creation stamp; a saved name alone cannot identify it. SSH host-key trust is separate from the profile identity.

`profile-v1.json` exercises layout ordering, a pane on another host, and fields a newer client might add. Desktop and Android tests consume this same fixture. Host-side tests cover revision conflicts and reused session rejection.

Workspaces can include `tabGroups`, an array of `{ "id": "group-id", "panes": ["pane-id", "another-pane-id"] }` records. Membership uses existing pane IDs. Keep every pane in the saved layout so clients without tab support still display all terminals. Active tab selection stays on the device. Preserve extra fields in group records when editing membership.

Hostless workspace groups use the same workspace and pane records with the reserved owner `@device-workspaces`. They are stored on the device, outside host profiles. Cloned views get new pane IDs and keep the original host identity, session ID and creation stamp. Closing a cloned view never requests session termination. Actual host workspace order and tab groups still use the host's revision checks.
