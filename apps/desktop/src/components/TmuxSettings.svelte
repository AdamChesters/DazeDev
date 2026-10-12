<script lang="ts">
  import { onMount } from 'svelte';
  import { rpc } from '../lib/bridge';
  import type { Host, Session } from '../lib/types';
  let { host, appScrollback }: { host: Host; appScrollback: number } = $props();
  interface Option {
    name: string;
    kind: string;
    value?: string;
    local?: string;
    inherited?: boolean;
    unsupported?: boolean;
    allowed: (string | number)[];
  }
  interface Snapshot {
    version: string;
    socket: string;
    sessions: Session[];
    windows: { id: string; name: string; active: boolean }[];
    window?: string;
    revision?: string;
    options: Option[];
    panes: { id: string; copyMode: string; mouseReporting: string; alternateScreen: string }[];
  }
  const labels: Record<string, string> = {
    status: 'Status bar rows',
    'status-position': 'Status bar position',
    mouse: 'Mouse interaction',
    'history-limit': 'Host history lines',
    'set-titles': 'Report terminal titles',
    'mode-keys': 'Copy-mode keys',
    'automatic-rename': 'Automatic window names',
    'allow-rename': 'Let applications rename windows',
    'pane-border-status': 'Pane title position',
    'window-size': 'Multiple-viewer sizing',
  };
  const hints: Record<string, string> = {
    'history-limit':
      'New panes use this limit. Existing history and your application’s own history may differ.',
    'set-titles': 'Reports an outer terminal title. DazeDev keeps its own saved pane name.',
    mouse:
      'Lets tmux handle clicks and scrolling; it does not add mouse editing to every application.',
    'window-size': 'Affects clients sharing this tmux window. Manual keeps its current size.',
    'mode-keys': 'These keys apply to tmux copy mode, not the shell or agent prompt.',
  };
  let snapshot = $state<Snapshot | null>(null);
  let session = $state('');
  let windowId = $state('');
  let draft = $state<Record<string, string>>({});
  let working = $state(false);
  let error = $state('');
  let message = $state('');
  const config = $derived(
    snapshot?.options
      .filter((o) => !o.unsupported)
      .map((o) => `set-option ${o.kind === 'window' ? '-gw' : '-g'} ${o.name} ${o.value}`)
      .join('\n') || '',
  );
  function args() {
    return {
      session,
      stamp: snapshot?.sessions.find((s) => s.id === session)?.stamp,
      window: windowId,
    };
  }
  function receive(value: Snapshot) {
    snapshot = value;
    windowId = value.window || '';
    draft = Object.fromEntries(value.options.map((o) => [o.name, o.value || '']));
  }
  async function refresh() {
    working = true;
    error = '';
    message = '';
    try {
      receive(await rpc<Snapshot>(host.id, 'tmux_settings_read', args()));
    } catch (e) {
      error = String(e);
    } finally {
      working = false;
    }
  }
  async function apply(option: Option, reset = false) {
    working = true;
    error = '';
    message = '';
    try {
      receive(
        await rpc<Snapshot>(host.id, 'tmux_settings_apply', {
          ...args(),
          revision: snapshot?.revision,
          name: option.name,
          value: draft[option.name],
          reset,
        }),
      );
      message = `${labels[option.name]} ${reset ? 'now inherits its default' : 'applied'}. No configuration file was changed.`;
    } catch (e) {
      error = String(e);
    } finally {
      working = false;
    }
  }
  onMount(() => {
    void refresh();
  });
</script>

<div class="tmux-settings">
  <p>
    <strong>{host.name}</strong>{#if snapshot}
      · {snapshot.version} · Socket: {snapshot.socket}{/if}
  </p>
  <p class="hint">
    Changes affect the selected tmux scope and other clients sharing it. They stay live until
    changed or the tmux server exits. DazeDev does not rewrite or reload host configuration.
  </p>
  <div class="form-row">
    <label
      >Scope<select
        bind:value={session}
        disabled={working}
        onchange={() => {
          windowId = '';
          void refresh();
        }}
        ><option value="">Host defaults on this socket</option
        >{#each snapshot?.sessions || [] as s}<option value={s.id}>Session: {s.name}</option
          >{/each}</select
      ></label
    >
    {#if session}<label
        >Window<select bind:value={windowId} disabled={working} onchange={() => void refresh()}
          >{#each snapshot?.windows || [] as w}<option value={w.id}>{w.name} ({w.id})</option
            >{/each}</select
        ></label
      >{/if}
    <button disabled={working} onclick={() => void refresh()}>Refresh</button>
  </div>
  {#if error}<p role="alert" class="error">{error}</p>{/if}
  <p role="status">{working ? 'Reading or applying tmux settings…' : message}</p>
  {#if snapshot && !snapshot.sessions.length}<p>
      No running tmux server on this socket. Create or join a session, then refresh. No server was
      started.
    </p>{/if}
  {#each snapshot?.options || [] as option (option.name)}
    <div class="option">
      <label for={`tmux-${option.name}`}
        >{labels[option.name]}
        <small
          >{session
            ? option.kind === 'window'
              ? `Window ${windowId}`
              : 'Selected session'
            : `${option.kind} default`}{option.inherited ? ' · inherited' : ''}</small
        ></label
      >
      {#if option.unsupported}<span>Unavailable in this tmux version</span>{:else}
        <div class="controls">
          {#if option.name === 'history-limit'}<input
              id={`tmux-${option.name}`}
              type="number"
              min="100"
              max="100000"
              step="1"
              value={draft[option.name]}
              oninput={(e) => (draft[option.name] = e.currentTarget.value)}
              disabled={working}
            />
          {:else}<select
              id={`tmux-${option.name}`}
              bind:value={draft[option.name]}
              disabled={working}
              >{#each option.allowed as value}<option value={String(value)}>{value}</option
                >{/each}</select
            >{/if}
          <button
            disabled={working ||
              draft[option.name] === option.value ||
              (option.name === 'history-limit' &&
                (!/^\d+$/.test(draft[option.name]) ||
                  Number(draft[option.name]) < 100 ||
                  Number(draft[option.name]) > 100000))}
            onclick={() => void apply(option)}>Apply now</button
          >
          {#if session}<button
              disabled={working || option.inherited}
              onclick={() => void apply(option, true)}>Use default</button
            >{/if}
        </div>
      {/if}
      {#if hints[option.name]}<p class="hint">{hints[option.name]}</p>{/if}
    </div>
  {/each}
  <details>
    <summary>Scrolling and clicking inside a prompt</summary>
    <p>
      DazeDev retains {appScrollback.toLocaleString()} lines on this device; change that in Settings →
      Terminal. Tmux keeps separate history on the host. An agent can also manage its own scrolling.
    </p>
    <p>
      Alt-click can move the cursor by sending arrow keys. A plain click may select text or go to
      the running application. Tmux mouse mode, copy mode and the agent’s input editor can change
      this behavior; enabling mouse mode is not a universal fix.
    </p>
    {#each snapshot?.panes || [] as pane}<p>
        Pane {pane.id}: copy/view mode {pane.copyMode === '1' ? 'active' : 'off'}; application mouse
        reporting {pane.mouseReporting === '1' ? 'on' : 'off'}; alternate screen {pane.alternateScreen ===
        '1'
          ? 'on'
          : 'off'}.
      </p>{/each}
  </details>
  {#if !session && config}<details>
      <summary>Keep host defaults after tmux restarts</summary>
      <p>
        These are the current live defaults, not a saved configuration. Copy them for review before
        adding them to the host’s tmux configuration. Includes or plugins may override them. DazeDev
        does not change that file.
      </p>
      <textarea aria-label="Host default tmux configuration" readonly rows="10" value={config}
      ></textarea>
      <button
        onclick={async () => {
          try {
            await navigator.clipboard.writeText(config);
            message = 'Configuration copied. It has not been saved on the host.';
          } catch {
            error = 'Clipboard unavailable. Select and copy the configuration above.';
          }
        }}>Copy config for future sessions</button
      >
    </details>{/if}
</div>

<style>
  .tmux-settings {
    max-width: 760px;
  }
  .option {
    border-top: 1px solid var(--border);
    padding: 12px 0;
  }
  .option label {
    display: block;
    margin-bottom: 6px;
  }
  .option small {
    font-weight: normal;
    display: block;
  }
  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .controls select,
  .controls input {
    min-width: 110px;
    flex: 1;
  }
  .hint {
    line-height: 1.5;
  }
  details {
    border-top: 1px solid var(--border);
    padding: 12px 0;
  }
  summary {
    cursor: pointer;
  }
  textarea {
    width: 100%;
    resize: vertical;
    font-family: monospace;
  }
  .error {
    color: var(--danger, #ed9c92);
  }
</style>
