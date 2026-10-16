<script lang="ts">
  import type { BrowserRequest } from './types';
  import type { Device } from '../../lib/types';
  let {
    browserRequest,
    browserRequests,
    browserTrust = $bindable(),
    decideBrowserRequest,
    device,
  }: {
    browserRequest: BrowserRequest;
    browserRequests: BrowserRequest[];
    browserTrust: boolean;
    decideBrowserRequest: (allow: boolean) => Promise<void>;
    device: Device;
  } = $props();
</script>

<h2 id="browser-request-title">
  {device.hosts.find((h) => h.id === browserRequest.hostId)?.name || 'A connected host'} wants
  to open
</h2>
<p id="browser-request-url" class="browser-request-url"><code>{browserRequest.url}</code></p>
<label class="check"
  ><input type="checkbox" bind:checked={browserTrust} /> Allow links from this host until
  DazeDev restarts</label
>
{#if browserRequests.length > 1}<p class="hint">
    {browserRequests.length - 1} more {browserRequests.length === 2 ? 'request' : 'requests'}
    waiting.
  </p>{/if}
<div class="row">
  <button class="primary" onclick={() => void decideBrowserRequest(true)}>Open</button>
  <button onclick={() => void decideBrowserRequest(false)}>Don't open</button>
</div>
