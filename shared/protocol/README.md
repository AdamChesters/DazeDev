# Host protocol compatibility fixtures

The desktop host helper at `apps/desktop/src-tauri/resources/host.py` is the wire implementation. Android stages that exact file into generated assets.

Protocol 1 runs only over the system OpenSSH client, which needs a terminal for its own prompts. Replies are nonce-scoped `DAZED:<nonce>:@<index>/<total>:<base64>` lines; requests are newline-terminated base64 JSON with `id`, `method` and `args`. Indices begin at zero. All lines of one reply arrive together.

Protocol 2 runs over channels that carry bytes unchanged: the desktop's built-in SSH, WSL and local shells (plain pipes), and Android. The loader passes `2` after the nonce. After the source line, both directions use binary frames. Requests are a 4-byte big-endian length followed by the JSON request. Replies and events are `u32 length, u8 flags, u16 id length, id, data`: a message may be split into parts with the same id, flag bit 1 marks the last part, and parts of different messages may interleave, so a large reply never delays `ping`. Errors are `{"code", "message"}`; codes are listed in `host-v2.json`. The `ready` result reports `protocol` and `schema`.

Uploads: `upload_begin` returns `chunk` (1 MiB on protocol 2, 32 KiB on protocol 1). On protocol 2 it also returns `parallel` (4): the client may keep that many `upload_chunk` requests in flight, each with its own `offset`, and chunks may arrive in any order. Each reply is `{"offset", "received"}`: the contiguous bytes written and the total bytes received. Without `parallel`, send one chunk at a time in order.

`host-v2.json` lists every method with its worker lane and deadline. Python, Rust and frontend tests check it against host.py and the calls the desktop makes.

Profiles use schema 1 and compare-and-swap revisions. Preserve unknown fields, pane IDs, desktop layout and focus. Mobile focus is device-local. A terminal target includes host machine/user/socket identity, tmux session ID and creation stamp; a saved name alone cannot identify it. SSH host-key trust is separate from the profile identity.

`profile-v1.json` exercises layout ordering, a pane on another host, and fields a newer client might add. Desktop and Android tests consume this same fixture. Host-side tests cover revision conflicts and reused session rejection.

Workspaces can include `tabGroups`, an array of `{ "id": "group-id", "panes": ["pane-id", "another-pane-id"] }` records. Membership uses existing pane IDs. Keep every pane in the saved layout so clients without tab support still display all terminals. Active tab selection stays on the device. Preserve extra fields in group records when editing membership.

Hostless workspace groups use the same workspace and pane records with the reserved owner `@device-workspaces`. They are stored on the device, outside host profiles. Cloned views get new pane IDs and keep the original host identity, session ID and creation stamp. Closing a cloned view never requests session termination. Actual host workspace order and tab groups still use the host's revision checks.
