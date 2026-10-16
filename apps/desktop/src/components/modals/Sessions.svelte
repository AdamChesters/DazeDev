<script lang="ts">
  import type { Device, Pane, Session } from '../../lib/types';
  import { Plus, RefreshCw, Terminal as TerminalIcon, Trash2 } from '@lucide/svelte';
  import type { Task } from './types';
  let {
    attach,
    busy,
    destroyTarget = $bindable(),
    device,
    modal = $bindable(),
    refreshSessions,
    sessionHost,
    sessionPane,
    sessions,
    startSession,
    task,
  }: {
    attach: (s: Session, options?: Partial<Pane>) => Promise<void>;
    busy: boolean;
    destroyTarget: (Session & { hostId?: string }) | null;
    device: Device;
    modal: string;
    refreshSessions: (hostId: string) => Promise<void>;
    sessionHost: string;
    sessionPane: Pane | null;
    sessions: Session[];
    startSession: () => Promise<void>;
    task: Task;
  } = $props();
</script>

<div class="list-toolbar">
  <p>
    {device.hosts.find((h) => h.id === sessionHost)?.name}{sessionPane
      ? ' · ' + sessionPane.name
      : ''}
  </p>
  <button
    aria-label="Refresh sessions"
    disabled={busy}
    onclick={() =>
      void task(async () => {
        await refreshSessions(sessionHost);
      })}><RefreshCw size={16} /></button
  >
</div>
<button class="primary" disabled={busy} onclick={() => void task(startSession)}
  ><Plus size={16} />
  {device.hosts.find((h) => h.id === sessionHost)?.kind === 'powershell'
    ? 'Create new PowerShell session'
    : 'Create new tmux session'}</button
>
<p class="hint">
  Joining attaches only. It never restarts the agent or sends an orientation prompt.
  Switching replaces this terminal view; existing sessions keep running. Attached views
  share input and terminal size. Native PowerShell work stops only when you end the session
  or shut down Windows.
</p>
{#each sessions as s}<div class="item-row">
    <TerminalIcon size={18} />
    <div>
      <strong>{s.name}</strong><small>{s.windows} windows · {s.attached} attached</small>
    </div>
    <button disabled={busy} onclick={() => void task(() => attach(s))}>Join</button>
    <button
      class="icon danger"
      disabled={busy}
      aria-label={`Delete ${s.name}`}
      onclick={() => {
        destroyTarget = { ...s, hostId: sessionHost };
        modal = 'destroy';
      }}><Trash2 size={15} /></button
    >
  </div>{:else}<p class="hint">
    No sessions on this host. Create a named session above.
  </p>{/each}
