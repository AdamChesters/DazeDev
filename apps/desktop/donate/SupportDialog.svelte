<script lang="ts">
  import Brand from '../src/components/Brand.svelte';
  import { openExternal } from '../src/lib/bridge';
  import { identity } from './adapter';
  import SupportTemplate from './templates/SupportDialog.svelte';
  import type { RecordingHighlight } from '../src/lib/highlights';
  let {
    version,
    onupdate,
    ...props
  }: {
    version: string;
    status: string;
    updateState?: 'unchecked' | 'checking' | 'current' | 'available' | 'error' | 'installing';
    updateAvailable?: string;
    updateBusy?: boolean;
    updateSupported?: boolean;
    onupdate: () => void;
    onfeedback: () => void;
    glow?: boolean;
    highlight?: RecordingHighlight;
    highlightColor?: string;
    onglowchange: (enabled: boolean) => void;
  } = $props();
</script>

{#snippet appLogo()}<Brand large />{/snippet}
<SupportTemplate
  {...props}
  {...identity}
  {appLogo}
  appVersion={version}
  checkForUpdates={onupdate}
  {openExternal}
/>
