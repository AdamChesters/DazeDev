<script lang="ts">
  import { type ReleaseEntry, releaseHeading } from '../../lib/changelog';
  import type { UpdateInfo } from './types';
  let {
    availableUpdate,
    changelogReturn,
    historyStatus,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    releaseFilter = $bindable(),
    releaseHistory,
  }: {
    availableUpdate: UpdateInfo | null;
    changelogReturn: string;
    historyStatus: string;
    modal: string;
    releaseFilter: 'all' | 'desktop' | 'mobile';
    releaseHistory: ReleaseEntry[];
  } = $props();
</script>

<div class="row" aria-label="Release platform">
  {#each ['all', 'desktop', 'mobile'] as platform}
    <button
      aria-pressed={releaseFilter === platform}
      onclick={() => (releaseFilter = platform as typeof releaseFilter)}
      >{platform === 'all' ? 'All' : platform === 'desktop' ? 'Desktop' : 'Mobile'}</button
    >
  {/each}
</div>
{#if historyStatus}<p class="hint" role="status">{historyStatus}</p>{/if}
{#if releaseFilter !== 'mobile' && availableUpdate && !releaseHistory.some((entry) => entry.platform === 'desktop' && entry.version === availableUpdate?.version)}<article
    class="release-entry"
  >
    <h3>Desktop · {releaseHeading(availableUpdate.version)}</h3>
    <p>{availableUpdate.notes}</p>
  </article>{/if}
{#each releaseHistory.filter((entry) => releaseFilter === 'all' || entry.platform === releaseFilter) as entry}<article
    class="release-entry"
  >
    <h3>
      {entry.platform === 'desktop' ? 'Desktop' : 'Mobile'} · {entry.platform === 'desktop'
        ? releaseHeading(entry.version, entry.date)
        : `${entry.date} : ${entry.version}`}{entry.channel === 'development'
        ? ' · Development build'
        : ''}
    </h3>
    <p>{entry.notes}</p>
  </article>{/each}
<button onclick={() => (modal = changelogReturn)}>Back</button>
