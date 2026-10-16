<script lang="ts">
  import type { Device, Session } from '../../lib/types';
  import type { Task } from './types';
  let {
    busy,
    destroySession,
    destroyTarget,
    device,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    task,
  }: {
    busy: boolean;
    destroySession: () => Promise<void>;
    destroyTarget: (Session & { hostId?: string }) | null;
    device: Device;
    modal: string;
    task: Task;
  } = $props();
</script>

<p>
  Destroy <strong>{destroyTarget?.name}</strong> on
  <strong
    >{device.hosts.find((h) => h.id === (destroyTarget?.hostId || device.activeHost))
      ?.name}</strong
  > and terminate all its running processes? All unsaved running work will be lost, including
  work in other clients attached to this session. This cannot be undone.
</p>
<div class="form-row">
  <button onclick={() => (modal = 'sessions')}>Keep session</button><button
    class="danger"
    disabled={busy}
    onclick={() => void task(destroySession)}>Destroy session</button
  >
</div>
