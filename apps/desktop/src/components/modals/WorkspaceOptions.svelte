<script lang="ts">
  import ColorPicker from '../../components/ColorPicker.svelte';
  import type { Device, Profile, Workspace } from '../../lib/types';
  let {
    closeWorkspace,
    device,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    profiles = $bindable(),
    renameValue = $bindable(),
    saved,
    updateWorkspace,
    workspaceTarget = $bindable(),
  }: {
    closeWorkspace: (w: Workspace, confirmed?: boolean) => void;
    device: Device;
    modal: string;
    profiles: Record<string, Profile>;
    renameValue: string;
    saved: (hostId: string) => void;
    updateWorkspace: (w: Workspace) => void;
    workspaceTarget: Workspace | null;
  } = $props();
</script>

<form
  onsubmit={(e) => {
    e.preventDefault();
    if (workspaceTarget) {
      updateWorkspace({
        ...workspaceTarget,
        name: renameValue.trim() || workspaceTarget.name,
      });
      modal = '';
    }
  }}
>
  <label>Workspace name<input bind:value={renameValue} /></label>
  <p class="hint">
    Applies to this sidebar tab and all terminals without their own color override,
    including new terminals.
  </p>
  <ColorPicker
    value={workspaceTarget?.colors}
    inherited={device.hosts.find((h) => h.id === workspaceTarget?.hostId)?.colors}
    resetLabel="Use host colors"
    onChange={(colors) => {
      if (workspaceTarget) {
        workspaceTarget = { ...workspaceTarget, colors };
        updateWorkspace(workspaceTarget);
      }
    }}
  />
  <div class="form-row">
    <button
      type="button"
      onclick={() => {
        if (!workspaceTarget) return;
        const list = profiles[workspaceTarget.hostId].workspaces;
        const i = list.findIndex((w) => w.id === workspaceTarget!.id);
        if (i > 0) {
          [list[i - 1], list[i]] = [list[i], list[i - 1]];
          saved(workspaceTarget.hostId);
        }
      }}>Move up</button
    ><button
      type="button"
      onclick={() => {
        if (!workspaceTarget) return;
        const list = profiles[workspaceTarget.hostId].workspaces;
        const i = list.findIndex((w) => w.id === workspaceTarget!.id);
        if (i < list.length - 1) {
          [list[i + 1], list[i]] = [list[i], list[i + 1]];
          saved(workspaceTarget.hostId);
        }
      }}>Move down</button
    >
  </div>
  <div class="form-row">
    <button
      type="button"
      onclick={() => {
        modal = '';
        closeWorkspace(workspaceTarget!);
      }}>Delete workspace</button
    ><button class="primary">Save name</button>
  </div>
  <p class="hint">
    Deleting removes the saved layout and closes plain shells. Attached tmux sessions stay
    alive.
  </p>
</form>
