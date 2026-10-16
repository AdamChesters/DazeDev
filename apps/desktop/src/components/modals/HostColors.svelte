<script lang="ts">
  import ColorPicker from '../../components/ColorPicker.svelte';
  import type { Device, Host } from '../../lib/types';
  let {
    colorHost,
    device = $bindable(),
    persistDevice,
  }: {
    colorHost: Host;
    device: Device;
    persistDevice: () => void;
  } = $props();
</script>

<p>
  Colors for {colorHost.name}. Workspaces and terminals inherit these unless they have their own
  colors. This choice is saved on this device.
</p>
<ColorPicker
  value={device.hosts.find((h) => h.id === colorHost?.id)?.colors}
  onChange={(colors) => {
    device.hosts = device.hosts.map((h) => (h.id === colorHost?.id ? { ...h, colors } : h));
    persistDevice();
  }}
/>
