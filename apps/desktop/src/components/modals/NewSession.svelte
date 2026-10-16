<script lang="ts">
  import RepoPicker from '../../components/RepoPicker.svelte';
  import type { Device, Inventory } from '../../lib/types';
  import type { SessionForm, Task } from './types';
  import { sessionNameError } from '../../lib/session-name';
  let {
    busy,
    createSession,
    device,
    form = $bindable(),
    inv,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    nameError = $bindable(),
    selectedAgent,
    sessionHost,
    sessionStep = $bindable(),
    task,
  }: {
    busy: boolean;
    createSession: () => Promise<void>;
    device: Device;
    form: SessionForm;
    inv: Inventory | undefined;
    modal: string;
    nameError: string;
    selectedAgent: Inventory['agents'][number] | undefined;
    sessionHost: string;
    sessionStep: number;
    task: Task;
  } = $props();
</script>

<p class="hint">
  Step {sessionStep + 1} of 3 · {device.hosts.find((h) => h.id === sessionHost)?.name}
</p>
<form
  onsubmit={(e) => {
    e.preventDefault();
    nameError = sessionNameError(form.name);
    if (nameError) {
      sessionStep = 0;
      return;
    }
    if (sessionStep < 2) sessionStep += 1;
    else void task(createSession);
  }}
>
  {#if sessionStep === 0}
    <label
      >Session name<input
        required
        aria-invalid={!!nameError}
        aria-describedby="session-name-error"
        oninput={() => {
          nameError = '';
        }}
        bind:value={form.name}
        placeholder="planning"
      /></label
    >
    {#if nameError}<p id="session-name-error" class="inline-error" role="alert">
        {nameError}
      </p>{:else}<p id="session-name-error" class="hint">
        Use letters, numbers, underscores or hyphens.
      </p>{/if}
  {:else if sessionStep === 1}
    <label
      >Agent<select
        bind:value={form.agent}
        onchange={() => {
          form.model = '';
          form.effort = '';
        }}
        ><option value="shell">None · plain shell</option>{#each inv?.agents || [] as a}<option
            value={a.id}>{a.id}{a.path ? '' : ' · install and start'}</option
          >{/each}</select
      ></label
    >
    {#if form.agent !== 'shell'}
      <label
        >Model<input
          bind:value={form.model}
          disabled={!selectedAgent?.modelFlag}
          placeholder="Agent default"
          list="session-models"
        /></label
      >
      <datalist id="session-models"
        >{#each selectedAgent?.models || [] as m}<option value={m.id}>{m.name}</option
          >{/each}</datalist
      >
      <label
        >Effort<select bind:value={form.effort} disabled={!selectedAgent?.effortFlag}
          ><option value="">Agent default</option
          >{#each selectedAgent?.models.find((m) => m.id === form.model)?.efforts || ['low', 'medium', 'high'] as effort}<option
              value={effort}>{effort}</option
            >{/each}</select
        ></label
      >
    {/if}
  {:else}
    <button type="button" onclick={() => (form.repo = '')}>Use host home directory</button>
    <RepoPicker
      repos={inv?.repos || []}
      hostId={form.hostId || device.activeHost}
      bind:value={form.repo}
      warnings={inv?.warnings || []}
      disabled={busy}
    />
    <label
      >GitHub identity<select bind:value={form.identity}
        ><option value="">Existing host environment</option
        >{#each inv?.identities || [] as i}<option value={i.login}>{i.login}</option>{/each}</select
      ></label
    >
    {#if form.repo && form.agent !== 'shell'}<label class="check"
        ><input type="checkbox" bind:checked={form.brief} /> Read repo instructions and give a concise
        orientation</label
      >{/if}
    <p class="hint">
      {form.repo
        ? 'Starts in the selected working copy. No branch or worktree is created automatically.'
        : 'Starts in the host’s home directory without a repository orientation prompt.'}
    </p>
  {/if}
  <div class="form-row">
    <button
      type="button"
      disabled={busy}
      onclick={() => {
        if (sessionStep) sessionStep -= 1;
        else modal = 'sessions';
      }}>Back</button
    ><button class="primary" disabled={busy}
      >{busy
        ? 'Creating…'
        : sessionStep < 2
          ? 'Next'
          : form.agent !== 'shell' && !selectedAgent?.path
            ? 'Trust upstream installer and start session'
            : 'Create session & join'}</button
    >
  </div>
</form>
