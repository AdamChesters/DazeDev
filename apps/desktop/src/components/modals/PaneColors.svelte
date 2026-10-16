<script lang="ts">
  import ColorPicker from '../../components/ColorPicker.svelte';
  import type { Device, Pane, Workspace } from '../../lib/types';
  import { tabColors } from '../../lib/colors';
  let {
    colorPane = $bindable(),
    device,
    updateWorkspace,
    workspaces,
  }: {
    colorPane: Pane | null;
    device: Device;
    updateWorkspace: (w: Workspace) => void;
    workspaces: Workspace[];
  } = $props();
  const owner = $derived(workspaces.find((w) => w.panes.some((p) => p.id === colorPane?.id)));
</script>

<p class="hint">
  Overrides this terminal only. Other terminals follow their workspace colors.
</p>
<ColorPicker
  value={colorPane?.colors}
  inherited={tabColors(
    owner?.colors,
    device.hosts.find((h) => h.id === colorPane?.hostId)?.colors,
  )}
  resetLabel="Use workspace colors"
  onChange={(colors) => {
    if (!owner || !colorPane) return;
    colorPane.colors = colors;
    updateWorkspace({
      ...owner,
      panes: owner.panes.map((p) => (p.id === colorPane!.id ? { ...p, colors } : p)),
    });
  }}
/>
