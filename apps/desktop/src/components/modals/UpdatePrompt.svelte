<script lang="ts">
  import { Check, Download } from '@lucide/svelte';
  import type { ClientInfo, UpdateInfo, UpdateState } from './types';
  import { desktop } from '../../lib/bridge';
  import { releaseVersion } from '../../lib/release-version';
  let {
    availableUpdate,
    busy,
    checkUpdates,
    clientInfo,
    closeUpdates,
    installUpdate,
    showChangelog,
    updateBusy,
    updateLabel,
    updateProgress,
    updateState,
    updateStatus,
    voiceStatus,
  }: {
    availableUpdate: UpdateInfo | null;
    busy: boolean;
    checkUpdates: (manual?: boolean, settingsOpening?: boolean) => Promise<void>;
    clientInfo: ClientInfo;
    closeUpdates: () => void;
    installUpdate: () => Promise<void>;
    showChangelog: (origin?: string) => void;
    updateBusy: boolean;
    updateLabel: string;
    updateProgress: number;
    updateState: UpdateState;
    updateStatus: string;
    voiceStatus: string;
  } = $props();
</script>

<h2>
  {availableUpdate
    ? `DazeDev ${releaseVersion(availableUpdate.version)} is available`
    : 'DazeDev updates'}
</h2>
<p class="hint">
  Installed: {releaseVersion(clientInfo.version)}.{availableUpdate
    ? ` Download: ${(availableUpdate.size / 1000000).toFixed(1)} MB.`
    : ''}
</p>
<button disabled={updateState === 'installing'} onclick={() => showChangelog('')}
  >Changelog</button
>
{#if availableUpdate}<p class="hint">
    The app restarts after updating. Your settings, models and remote tmux sessions are
    preserved.
  </p>{/if}
{#if !desktop || !clientInfo.updateSupported}<p class="hint">
    Download updates manually for this platform.
  </p>{/if}
<p role="status">
  {updateStatus || updateLabel}{updateState === 'installing' ? ` ${updateProgress}%` : ''}
</p>
<div class="form-row">
  <button disabled={updateState === 'installing'} onclick={closeUpdates}
    >{availableUpdate ? 'Later' : 'Close'}</button
  >
  {#if desktop && clientInfo.updateSupported}<button
      disabled={updateBusy}
      onclick={() => void checkUpdates()}>Check again</button
    >{/if}
  {#if availableUpdate}<button
      class="primary"
      disabled={updateBusy || voiceStatus !== 'idle' || busy}
      onclick={() => void installUpdate()}>Download &amp; restart</button
    >{/if}
</div>
