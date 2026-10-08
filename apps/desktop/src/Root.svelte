<script lang="ts">
  import { onMount, tick, type Component } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { call, desktop } from './lib/bridge';
  import { parsePinStatus, validPin, type PinStatus } from './lib/pin';
  let Application = $state<Component | null>(null),
    status = $state<PinStatus | null>(null);
  let pin = $state(''),
    error = $state(''),
    busy = $state(true);
  let pinInput = $state<HTMLInputElement | undefined>();
  $effect(() => {
    if (status?.locked && !status.unavailable && !busy) void tick().then(() => pinInput?.focus());
  });
  async function check() {
    busy = true;
    error = '';
    try {
      if (desktop) status = parsePinStatus(await call('pin_status'));
      if (!desktop || (status && !status.locked && !status.unavailable))
        Application = new URLSearchParams(location.search).has('terminal')
          ? (await import('./components/DetachedTerminal.svelte')).default
          : (await import('./App.svelte')).default;
    } catch {
      error = 'Startup protection could not be checked. Close DazeDev or try again.';
    } finally {
      busy = false;
    }
  }
  async function unlock() {
    if (busy || !validPin(pin) || status?.unavailable) return;
    busy = true;
    error = '';
    const submitted = pin;
    pin = '';
    try {
      await call('pin_unlock', { pin: submitted });
      await check();
    } catch {
      error = 'PIN could not be verified. Wait and try again.';
      try {
        status = parsePinStatus(await call('pin_status'));
      } catch {
        /* Keep the gate closed. */
      }
    } finally {
      busy = false;
    }
  }
  onMount(() => {
    void check();
  });
</script>

{#if Application}<Application />
{:else}
  <main class="startup-gate">
    <section aria-labelledby="startup-pin-title">
      <h1 id="startup-pin-title">{status?.locked ? 'Unlock DazeDev' : 'DazeDev'}</h1>
      {#if status?.unavailable}
        <p role="alert">
          Startup PIN protection could not be read. DazeDev stays locked. Close the app and restore
          its protected data from a trusted backup before restarting.
        </p>
      {:else if status?.locked}
        <p>Enter your startup PIN to open workspaces and connect to hosts.</p>
        <form
          onsubmit={(e) => {
            e.preventDefault();
            void unlock();
          }}
        >
          <label
            >PIN<input
              bind:this={pinInput}
              type="password"
              inputmode="numeric"
              pattern={'[0-9]{6,12}'}
              minlength="6"
              maxlength="12"
              autocomplete="current-password"
              spellcheck="false"
              bind:value={pin}
              disabled={busy}
              required
            /></label
          >
          <button class="primary" disabled={busy || !validPin(pin)}
            >{busy ? 'Checking…' : 'Unlock'}</button
          >
        </form>
        {#if status.retryAfter > 0}<p role="status">
            Wait before trying again (up to {status.retryAfter} seconds).
          </p>{/if}
      {:else if busy}<p role="status">Checking startup protection…</p>{/if}
      {#if error}<p role="alert">{error}</p>{/if}
      <p class="scope-note">
        This PIN protects app startup. It does not encrypt your files or replace your
        operating-system lock. There is no PIN recovery or bypass in DazeDev.
      </p>
      <div class="actions">
        {#if error && !status?.locked}<button disabled={busy} onclick={() => void check()}
            >Try again</button
          >{/if}
        {#if desktop}<button onclick={() => void getCurrentWindow().destroy()}>Close DazeDev</button
          >{/if}
      </div>
    </section>
  </main>
{/if}

<style>
  .startup-gate {
    display: grid;
    place-items: center;
    min-height: 100vh;
    padding: 24px;
    background: var(--bg);
    color: var(--text);
  }
  section {
    max-width: 440px;
    width: 100%;
    padding: 24px;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--panel, var(--bg));
  }
  h1 {
    font-size: 24px;
  }
  form {
    display: flex;
    gap: 12px;
    align-items: end;
    margin: 20px 0;
  }
  label {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 6px;
  }
  input {
    width: 100%;
    min-width: 0;
  }
  .scope-note {
    font-size: 12px;
    color: var(--muted);
    line-height: 1.6;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
  button:focus-visible,
  input:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
</style>
