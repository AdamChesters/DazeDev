<script lang="ts">
  import type { ClientInfo, UpdateInfo } from '../types';
  import type { Device } from '../../../lib/types';
  import { exportDiagnostics } from '../../../lib/diagnostics';
  import { releaseVersion } from '../../../lib/release-version';
  let {
    availableUpdate,
    checkUpdates,
    clientInfo,
    device = $bindable(),
    openUpdates,
    showChangelog,
    updateBusy,
    updateLabel,
    updateStatus,
  }: {
    availableUpdate: UpdateInfo | null;
    checkUpdates: (manual?: boolean, settingsOpening?: boolean) => Promise<void>;
    clientInfo: ClientInfo;
    device: Device;
    openUpdates: () => void;
    showChangelog: (origin?: string) => void;
    updateBusy: boolean;
    updateLabel: string;
    updateStatus: string;
  } = $props();
</script>

<section class="settings-section" aria-label="Updates">
  <h3>Updates · DazeDev {releaseVersion(clientInfo.version)}</h3>

  {#if clientInfo.updateSupported}<label class="check"
      ><input
        type="checkbox"
        checked={device.checkUpdates !== false}
        onchange={(e) => {
          device.checkUpdates = e.currentTarget.checked;
        }}
      /> Check at startup and every 30 minutes</label
    >
    <div class="settings-actions">
      <button disabled={updateBusy} onclick={() => void checkUpdates(true)}
        >Check for updates</button
      ><button onclick={() => showChangelog()}>Changelog</button><button
        onclick={() => exportDiagnostics(clientInfo.version)}>Export diagnostics</button
      >
    </div>{:else}<p class="hint">Test build: download updates manually for this platform.</p>
    <div class="settings-actions">
      <button onclick={() => showChangelog()}>Changelog</button><button
        onclick={() => exportDiagnostics(clientInfo.version)}>Export diagnostics</button
      >
    </div>{/if}
  <p class="hint">
    Opening Settings always checks for updates, even when automatic checks are off.
  </p>
  {#if availableUpdate}<button onclick={openUpdates}
      >Update to {releaseVersion(availableUpdate.version)}</button
    >{/if}
  <p class="hint" role="status">{updateStatus || updateLabel}</p>
  {#if clientInfo.migration}<p class="hint">{clientInfo.migration}</p>{/if}
</section>
