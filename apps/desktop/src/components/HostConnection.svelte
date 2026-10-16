<script lang="ts">
  import type { Host } from '../lib/types';
  import { authenticationState, credentialLabel, verificationUrl } from '../lib/connection';

  let {
    host,
    output,
    closed,
    onauth,
    oncancel,
    onretry,
    onedit,
  }: {
    host: Host;
    output: string;
    closed: boolean;
    onauth: (value: string) => Promise<void>;
    oncancel: () => Promise<void>;
    onretry: () => void;
    onedit: () => void;
  } = $props();
  let credential = $state('');
  let sending = $state(false);
  let error = $state('');
  const authentication = $derived(authenticationState(output, closed));
  const label = $derived(credentialLabel(output));
  async function respond(value: string) {
    if (sending) return;
    sending = true;
    error = '';
    credential = '';
    try {
      await onauth(value);
    } catch (e) {
      error = String(e);
    } finally {
      sending = false;
    }
  }
  async function cancel() {
    try {
      await oncancel();
    } catch (e) {
      error = String(e);
    }
  }
</script>

<section class="host-connection" aria-label={`${host.name} connection`}>
  <p role="status">
    {authentication === 'trust'
      ? 'Confirm host identity'
      : authentication === 'credentials'
        ? 'Authentication required'
        : authentication === 'browser'
          ? 'Browser verification required'
          : ['host-error', 'host-unknown', 'unsupported-target', 'closed'].includes(authentication)
            ? 'Connection needs attention'
            : 'Connecting...'}
  </p>
  {#if output}
    <details
      open={authentication === 'trust' ||
        ['host-error', 'host-unknown', 'unsupported-target'].includes(authentication) ||
        authentication === 'closed'}
    >
      <summary>Connection details</summary>
      <pre>{output}</pre>
    </details>
  {/if}
  {#if authentication === 'trust'}
    <p>Check this host's fingerprint against a trusted source before accepting.</p>
    <button disabled={sending} onclick={() => void respond('yes')}>Accept host key</button>
  {:else if authentication === 'credentials'}
    <form
      onsubmit={(event) => {
        event.preventDefault();
        void respond(credential);
      }}
    >
      <label
        >{label}<input
          type="password"
          bind:value={credential}
          autocomplete="off"
          aria-label={`${host.name} ${label}`}
        /></label
      >
      <button disabled={sending || !credential}>Continue</button>
    </form>
  {:else if authentication === 'browser'}
    <p>Complete the Tailscale check in your browser. This host will connect automatically.</p>
    <button
      onclick={async () => {
        try {
          await navigator.clipboard.writeText(verificationUrl(output));
          error = '';
        } catch (e) {
          error = String(e);
        }
      }}>Copy verification link</button
    >
  {:else if authentication === 'unsupported-target'}
    <p>
      This device runs Windows. Use the WSL/Linux device's own Tailscale name and username, with
      Tailscale SSH enabled there. DazeDev remote hosts need Python 3 and tmux.
    </p>
    <button onclick={onedit}>Edit host</button>
  {:else if authentication === 'host-error'}
    <p>
      Host key verification failed. Check the host identity before retrying. The changed key has not
      been accepted.
    </p>
  {:else if authentication === 'host-unknown'}
    <p>
      This host key is not yet trusted. Verify the host identity before retrying; no key has been
      accepted automatically.
    </p>
    {#if host.kind === 'tailscale'}
      <p>
        Tailscale SSH requires a supported host. Native Windows cannot host Tailscale SSH; use its
        WSL/Linux endpoint. Ordinary SSH over Tailscale is a different connection type.
      </p>
    {/if}
  {:else if authentication === 'closed'}
    <p>Check the connection details, host address and access policy.</p>
  {/if}
  {#if error}<p class="inline-error" role="alert">{error}</p>{/if}
  <div class="connection-actions">
    <button onclick={() => void cancel()}>{closed ? 'Dismiss' : 'Cancel connection'}</button>
    {#if closed || authentication === 'host-error' || authentication === 'host-unknown'}
      <button onclick={onretry}>Retry</button>
      <button onclick={onedit}>Edit host</button>
    {/if}
  </div>
</section>

<style>
  .host-connection {
    margin: 6px 8px;
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 6px;
    font-size: 12px;
    overflow-wrap: anywhere;
  }
  p {
    margin: 0 0 8px;
  }
  form,
  label {
    display: grid;
    gap: 6px;
    min-width: 0;
  }
  input {
    width: 100%;
    min-width: 0;
    box-sizing: border-box;
  }
  pre {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    max-height: 160px;
    overflow: auto;
    font-size: 11px;
  }
  summary {
    cursor: pointer;
    margin-bottom: 6px;
  }
  .connection-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 8px;
  }
  button {
    white-space: normal;
  }
</style>
