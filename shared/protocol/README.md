# Host protocol compatibility fixtures

The desktop host helper at `apps/desktop/src-tauri/resources/host.py` is the wire implementation. Android stages that exact file into generated assets. Replies are nonce-scoped `DAZED:<nonce>:@<index>/<total>:<base64>` lines; requests are newline-terminated base64 JSON with `id`, `method` and `args`. Indices begin at zero.

Profiles use schema 1 and compare-and-swap revisions. Preserve unknown fields, pane IDs, desktop layout and focus. Mobile focus is device-local. A terminal target includes host machine/user/socket identity, tmux session ID and creation stamp; a saved name alone cannot identify it. SSH host-key trust is separate from the profile identity.

`profile-v1.json` exercises layout ordering, a pane on another host, and fields a newer client might add. Desktop and Android tests consume this same fixture. Host-side tests cover revision conflicts and reused session rejection.
