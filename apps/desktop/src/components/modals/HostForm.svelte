<script lang="ts">
  import { ArrowUpRight } from '@lucide/svelte';
  import type { Device, Host } from '../../lib/types';
  import type { Task } from './types';
  let {
    aliases,
    busy,
    connect,
    device = $bindable(),
    modal = $bindable(),
    newHost = $bindable(),
    persistDevice,
    task,
  }: {
    aliases: string[];
    busy: boolean;
    connect: (h: Host) => Promise<void>;
    device: Device;
    modal: string;
    newHost: Host;
    persistDevice: () => void;
    task: Task;
  } = $props();
</script>

<form
  onsubmit={(e) => {
    e.preventDefault();
    void task(async () => {
      if (!newHost.name.trim()) throw new Error('Give this connection a name.');
      device.hosts = device.hosts.some((h) => h.id === newHost.id)
        ? device.hosts.map((h) => (h.id === newHost.id ? { ...newHost } : h))
        : [...device.hosts, { ...newHost }];
      device.activeHost = newHost.id;
      persistDevice();
      modal = '';
      void connect({ ...newHost });
    });
  }}
>
  <label
    >Connection name<input
      bind:value={newHost.name}
      placeholder="Development box"
      required
    /></label
  ><label
    >Location<select bind:value={newHost.kind}
      ><option value="ssh">SSH · any network</option><option value="tailscale"
        >Tailscale SSH · device identity</option
      ><option value="powershell">Local Windows PowerShell</option><option value="wsl"
        >Local WSL distribution</option
      ><option value="local">Local macOS / Linux</option></select
    ></label
  >{#if newHost.kind === 'ssh' || newHost.kind === 'tailscale'}<label
      >{newHost.kind === 'tailscale'
        ? 'Tailscale hostname or IP'
        : 'SSH alias or hostname'}<input
        list="ssh-aliases"
        bind:value={newHost.address}
        placeholder="dev-box or 100.x.x.x"
        required
      /></label
    >
    <div class="form-row">
      <label
        >SSH user<input
          bind:value={newHost.user}
          required={newHost.kind === 'tailscale'}
          placeholder={newHost.kind === 'tailscale'
            ? 'Linux username, e.g. ubuntu'
            : 'From SSH config'}
        /></label
      >{#if newHost.kind === 'ssh'}<label
          >Port<input
            type="number"
            bind:value={newHost.port}
            min="1"
            max="65535"
            placeholder="22"
          /></label
        >{/if}
    </div>
    <details>
      <summary>Connection options</summary>{#if newHost.kind === 'ssh'}<label
          >Private key path<input
            bind:value={newHost.key}
            placeholder="Use SSH config / agent"
          /></label
        ><label>Jump host<input bind:value={newHost.jump} placeholder="bastion" /></label
        >{/if}<label
        >tmux socket name<input bind:value={newHost.socket} placeholder="default" /></label
      ><label class="check"
        ><input type="checkbox" bind:checked={newHost.systemSsh} /> Use system OpenSSH</label
      >
      <p class="hint">
        Only for hosts that need hardware security keys, PKCS#11 or other options the
        built-in client lacks. Each terminal then signs in separately.
      </p>
    </details>
    <datalist id="ssh-aliases"
      >{#each aliases as alias}<option value={alias}></option>{/each}</datalist
    >
    <p class="hint">
      {#if newHost.kind === 'tailscale'}
        Uses your signed-in Tailscale device identity and Tailscale-verified host keys. No
        SSH password or key is needed. The target must enable Tailscale SSH; an accept
        policy connects directly, while a check policy can require browser reauthentication.
        Native Windows cannot serve Tailscale SSH. For WSL, use its Linux endpoint's own
        tailnet name. DazeDev remote hosts need Python 3.9 or newer and tmux.
      {:else}
        Your OpenSSH config, keys, certificates and agent are respected. You sign in once
        per host; every terminal shares that connection. Password and host-key prompts
        appear next. Passwords are never saved.
      {/if}
    </p>{:else if newHost.kind === 'powershell'}
    <label
      >Shell<select bind:value={newHost.nativeShell}>
        <option value="powershell">Windows PowerShell</option><option value="pwsh"
          >PowerShell 7 (installed)</option
        >
      </select></label
    >
    <p class="hint">
      Runs natively on this Windows machine, with hardware access and no WSL requirement.
      Sessions can stay running when DazeDev closes. PowerShell 7 must already be installed.
    </p>
  {:else if newHost.kind === 'wsl'}<label
      >Distribution<input
        bind:value={newHost.distro}
        placeholder="Default WSL distribution"
      /></label
    >{/if}
  {#if newHost.kind !== 'powershell'}<p class="hint">
      The host needs Python 3 and tmux. DazeDev runs its management helper in memory.
    </p>{/if}
  <button class="primary" disabled={busy}>Save & connect <ArrowUpRight size={15} /></button>
</form>
