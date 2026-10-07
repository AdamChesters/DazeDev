<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { call, desktop } from '../lib/bridge';
  import { parsePinStatus, validPin, type PinStatus } from '../lib/pin';
  let status = $state<PinStatus | null>(null),
    currentPin = $state(''),
    newPin = $state(''),
    confirmation = $state('');
  let editing = $state(false);
  let form = $state<HTMLFormElement>();
  let trigger = $state<HTMLButtonElement>();
  function cancelOnEscape(e: KeyboardEvent) {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    cancel();
  }
  async function begin(next: 'change' | 'disable') {
    action = next;
    error = '';
    notice = '';
    editing = true;
    await tick();
    form?.querySelector<HTMLInputElement>('input')?.focus();
  }
  function cancel() {
    if (busy) return;
    currentPin = newPin = confirmation = '';
    error = '';
    editing = false;
    void tick().then(() => trigger?.focus());
  }
  let busy = $state(true),
    action = $state<'change' | 'disable'>('change'),
    error = $state(''),
    notice = $state('');
  async function load() {
    try {
      if (desktop) status = parsePinStatus(await call('pin_status'));
    } catch {
      error = 'PIN settings could not be read.';
    } finally {
      busy = false;
    }
  }
  async function save() {
    if (busy || !status) return;
    error = '';
    notice = '';
    if (action !== 'disable' && (!validPin(newPin) || newPin !== confirmation)) {
      error = 'Enter matching PINs of 6–12 digits.';
      return;
    }
    busy = true;
    const previous = currentPin,
      replacement = action === 'disable' ? null : newPin;
    currentPin = '';
    newPin = '';
    confirmation = '';
    try {
      await call('pin_configure', { currentPin: previous, newPin: replacement });
      status = parsePinStatus(await call('pin_status'));
      notice = status.enabled
        ? 'Startup PIN saved. You will be asked for it when DazeDev next starts.'
        : 'Startup PIN disabled.';
      action = 'change';
      editing = false;
      void tick().then(() => trigger?.focus());
    } catch {
      error = 'PIN settings could not be changed. Check your current PIN, wait, and try again.';
    } finally {
      busy = false;
    }
  }
  onMount(() => {
    void load();
  });
</script>

<svelte:window
  onkeydown={(e) => {
    if (editing && form?.contains(e.target as Node)) cancelOnEscape(e);
  }}
/>

<section aria-label="Startup PIN" class="pin-settings">
  <div class="pin-row">
    <h3>
      Startup PIN <span
        >{status ? (status.enabled ? 'On' : 'Off') : desktop ? 'Loading…' : 'Desktop only'}</span
      >
    </h3>
    {#if desktop && status && !editing}
      <div class="actions">
        <button bind:this={trigger} disabled={busy} onclick={() => void begin('change')}
          >{status.enabled ? 'Change PIN' : 'Set PIN'}</button
        >
        {#if status.enabled}<button disabled={busy} onclick={() => void begin('disable')}
            >Disable PIN</button
          >{/if}
      </div>
    {/if}
  </div>
  {#if editing && status}
    <p class="hint">
      {action === 'disable'
        ? 'Enter your current PIN to remove the startup lock.'
        : 'Use 6–12 digits. Enter the PIN twice to confirm.'}
    </p>
    <p class="hint">
      This locks the app at startup; it does not encrypt files or lock your computer. There is no
      PIN recovery.
    </p>
    <form
      bind:this={form}
      onsubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      {#if status.enabled}
        <label
          >Current PIN<input
            type="password"
            inputmode="numeric"
            pattern={'[0-9]{6,12}'}
            maxlength="12"
            autocomplete="current-password"
            bind:value={currentPin}
            disabled={busy}
            required
          /></label
        >
      {/if}
      {#if action !== 'disable'}
        <label
          >{status.enabled ? 'New PIN' : 'PIN'}<input
            type="password"
            inputmode="numeric"
            pattern={'[0-9]{6,12}'}
            minlength="6"
            maxlength="12"
            autocomplete="new-password"
            bind:value={newPin}
            disabled={busy}
            required
          /></label
        >
        <label
          >Confirm PIN<input
            type="password"
            inputmode="numeric"
            pattern={'[0-9]{6,12}'}
            minlength="6"
            maxlength="12"
            autocomplete="new-password"
            bind:value={confirmation}
            disabled={busy}
            required
          /></label
        >
      {/if}
      <div class="actions">
        <button
          disabled={busy ||
            (status.enabled && !validPin(currentPin)) ||
            (action !== 'disable' && (!validPin(newPin) || newPin !== confirmation))}
          >{busy
            ? 'Saving…'
            : action === 'disable'
              ? 'Disable PIN'
              : status.enabled
                ? 'Change PIN'
                : 'Enable startup PIN'}</button
        >
        <button type="button" disabled={busy} onclick={cancel}>Cancel</button>
      </div>
    </form>
  {/if}
  {#if error}<p role="alert">{error}</p>{/if}{#if notice}<p role="status">{notice}</p>{/if}
</section>

<style>
  .pin-settings {
    border-top: 1px solid var(--border);
    padding: 10px 0;
  }
  form {
    display: grid;
    gap: 10px;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  }
  h3 {
    margin: 0;
  }
  h3 span {
    color: var(--muted);
    font-size: 12px;
    font-weight: normal;
    margin-left: 8px;
  }
  .pin-row,
  .actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .pin-row {
    justify-content: space-between;
  }
  form .actions {
    grid-column: 1 / -1;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }
  .hint {
    color: var(--muted);
    font-size: 12px;
  }
  input {
    width: 100%;
  }
</style>
