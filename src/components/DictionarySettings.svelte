<script lang="ts">
  import {
    dictionaryState,
    dictionaryStatus,
    dictionaryAdoption,
    saveEntry,
    importDictionary,
    setDictionaryHost,
    syncDictionary,
    loadDictionary,
  } from '../lib/dictionary-store';
  import {
    ambiguousAliases,
    dictionaryConflicts,
    entryRevision,
    type DictionaryVersion,
  } from '../lib/dictionary';
  import type { Host } from '../lib/types';
  let {
    hosts,
    compact = false,
    selection = '',
    ondone = () => {},
  }: { hosts: Host[]; compact?: boolean; selection?: string; ondone?: () => void } = $props();
  let expanded = $state(false);
  let expected = $state<string | undefined>();
  let id = $state(''),
    preferred = $state(''),
    aliases = $state(''),
    error = $state(''),
    busy = $state(false);
  $effect(() => {
    aliases = selection;
    preferred = selection;
  });
  const ambiguous = $derived(ambiguousAliases($dictionaryState.dictionary));
  const entries = $derived(Object.entries($dictionaryState.dictionary.entries));
  async function run(work: () => Promise<void>) {
    busy = true;
    error = '';
    try {
      await work();
    } catch (e) {
      error = String(e);
    } finally {
      busy = false;
    }
  }
  function edit(key: string, v: DictionaryVersion) {
    id = key;
    expected = entryRevision($dictionaryState.dictionary, key);
    preferred = v.preferred;
    aliases = v.aliases.join('\n');
  }
  function reset() {
    id = '';
    expected = undefined;
    preferred = '';
    aliases = '';
  }
  function exportFile() {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify($dictionaryState.dictionary, null, 2)], {
        type: 'application/json',
      }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = 'dazedev-dictionary.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
</script>

<section class="dictionary" aria-label="Dictation dictionary">
  <div class="dictionary-header">
    <h3>Dictation dictionary</h3>
    {#if compact}<button aria-expanded={expanded} onclick={() => (expanded = !expanded)}
        >{expanded ? 'Done' : 'Manage phrases'}</button
      >{/if}
  </div>
  {#if !compact || expanded}
    <p>
      Preferred spelling for every workspace and host. Only future dictation changes. Terminal
      history stays as it is.
    </p>
    <form
      onsubmit={(e) => {
        e.preventDefault();
        void run(async () => {
          await saveEntry(
            id || crypto.randomUUID(),
            preferred,
            aliases.split('\n'),
            false,
            false,
            expected,
          );
          reset();
          ondone();
        });
      }}
    >
      <label
        >Heard as <textarea
          rows="2"
          maxlength="4800"
          bind:value={aliases}
          placeholder="sample project&#10;example project"
          required></textarea></label
      >
      <small>One phrase per line. Use full phrases, not a common word such as “road”.</small>
      <label
        >Write as <input
          maxlength="160"
          bind:value={preferred}
          placeholder="ExampleProject"
          required
        /></label
      >
      {#if aliases.trim() && preferred.trim()}<p>
          Example: “{aliases.split('\n')[0]}” → “{preferred}”
        </p>{/if}
      <div class="actions">
        <button disabled={busy} type="submit">{id ? 'Save changes' : 'Add phrase'}</button
        >{#if id}<button type="button" onclick={reset}>Cancel edit</button>{/if}
      </div>
    </form>
    {#if error}<p role="alert">{error}</p>{/if}
    {#if dictionaryConflicts($dictionaryState.dictionary)}<p role="status">
        Conflicting entries are paused. Choose a version, review it above, then save to resolve.
      </p>{/if}
    {#if ambiguous.length}<p role="status">
        These heard phrases have more than one preferred spelling and will not be corrected: {ambiguous.join(
          '; ',
        )}. Edit, disable or delete the duplicate entries below.
      </p>{/if}
    <div class="entries">
      {#each entries as [key, versions] (key)}
        {#if versions.length > 1 || !versions[0].deleted}
          <div class="entry">
            {#if versions.length > 1}<strong>Concurrent changes</strong>{/if}
            {#each versions as version}
              <div>
                <span
                  >{version.deleted ? 'Deleted entry' : version.preferred}{version.disabled
                    ? ' (disabled)'
                    : ''} <small>{version.aliases.join(' / ')}</small></span
                >
                {#if versions.length === 1}<button
                    disabled={busy}
                    onclick={() =>
                      void run(() =>
                        saveEntry(
                          key,
                          version.preferred,
                          version.aliases,
                          false,
                          !version.disabled,
                          entryRevision($dictionaryState.dictionary, key),
                        ),
                      )}>{version.disabled ? 'Enable' : 'Disable'}</button
                  >{/if}
                <button disabled={busy} onclick={() => edit(key, version)}
                  >{versions.length > 1 ? 'Choose and review' : 'Edit'}</button
                >
                <button
                  disabled={busy}
                  onclick={() =>
                    void run(() =>
                      saveEntry(
                        key,
                        version.preferred,
                        version.aliases,
                        true,
                        false,
                        entryRevision($dictionaryState.dictionary, key),
                      ),
                    )}>{versions.length > 1 ? 'Keep deleted' : 'Delete'}</button
                >
              </div>
            {/each}
          </div>
        {/if}
      {/each}
    </div>
    <details class="dictionary-sync">
      <summary>Backup and sync</summary>
      <div class="actions">
        <button onclick={exportFile}>Export JSON</button><label class="import"
          >Import JSON<input
            type="file"
            accept="application/json,.json"
            disabled={busy}
            onchange={(e) => {
              const file = e.currentTarget.files?.[0];
              if (file)
                void run(async () => {
                  if (file.size > 2_000_000) throw Error('Dictionary file is too large.');
                  await importDictionary(JSON.parse(await file.text()));
                });
              e.currentTarget.value = '';
            }}
          /></label
        >
      </div>
      <label
        >Optional sync host
        <select
          value={$dictionaryState.syncHost}
          disabled={busy}
          onchange={(e) => void run(() => setDictionaryHost(e.currentTarget.value))}
        >
          <option value="">This device only</option>
          {#each hosts as host}<option value={host.id}
              >{host.name || host.address || host.id}</option
            >{/each}
        </select>
      </label>
      <p>
        Uses your existing SSH connection and saves a private file in that host user’s home folder.
        Each device keeps an offline copy and backup. No DazeDev cloud account.
      </p>
      {#if $dictionaryState.syncHost}<button
          disabled={busy}
          onclick={() => void run(() => syncDictionary())}>Check and sync</button
        >{/if}
    </details>
    {#if $dictionaryAdoption}
      <div class="adoption">
        <strong>Use this saved dictionary?</strong>
        <p>
          {Object.keys($dictionaryAdoption.entries).length} saved entries, including deletion records.
          Your local entries will merge; competing edits remain visible for review.
        </p>
        <button disabled={busy} onclick={() => void run(() => syncDictionary(true))}
          >Merge and enable sync on this device</button
        >
        <button disabled={busy} onclick={() => void run(() => setDictionaryHost(''))}
          >Keep this device separate</button
        >
      </div>
    {/if}
    <p role="status">{$dictionaryStatus}</p>
    {#if $dictionaryStatus.startsWith('Dictionary could not load')}<button
        disabled={busy}
        onclick={() => void run(() => loadDictionary(true))}>Retry loading</button
      >
      <p>
        Retry after restoring the backup, or import a saved JSON dictionary. Damaged files are
        retained.
      </p>{/if}
  {/if}
</section>

<style>
  .dictionary-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    flex-wrap: wrap;
  }
  .dictionary-sync {
    margin: 0;
  }
  .dictionary-sync summary {
    color: inherit;
    font-size: inherit;
  }
  .dictionary {
    border-top: 1px solid var(--border);
    padding: 10px 0;
    display: grid;
    gap: 10px;
    min-width: 0;
  }
  h3,
  p {
    margin: 0;
  }
  p,
  small {
    font-size: 12px;
    color: var(--muted, #a1adbe);
    overflow-wrap: anywhere;
  }
  form,
  label {
    display: grid;
    gap: 6px;
  }
  input,
  textarea,
  select {
    width: 100%;
    box-sizing: border-box;
    min-width: 0;
    padding: 8px;
    background: var(--panel, #172231);
    color: inherit;
    border: 1px solid var(--line, #364558);
    border-radius: 5px;
    font: inherit;
  }
  button,
  .import {
    padding: 7px 10px;
    border: 1px solid var(--line, #364558);
    background: var(--panel, #172231);
    color: inherit;
    border-radius: 5px;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .entries {
    max-height: 230px;
    overflow: auto;
  }
  .entry {
    padding: 8px 0;
    border-bottom: 1px solid var(--line, #364558);
  }
  .entry div {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }
  .entry span {
    flex: 1;
    min-width: 130px;
    overflow-wrap: anywhere;
  }
  .entry small {
    display: block;
  }
  .import input {
    max-width: 220px;
    padding: 0;
    border: 0;
  }
  .adoption {
    padding: 10px;
    border: 1px solid var(--line, #364558);
    display: grid;
    gap: 8px;
  }
</style>
