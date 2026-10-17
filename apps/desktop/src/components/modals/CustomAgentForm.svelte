<script lang="ts">
  import { untrack } from 'svelte';
  import { agentIdFrom, binaryFrom, customAgentError, type CustomAgent } from '../../lib/agents';
  let {
    agent,
    busy,
    cancel,
    others,
    save,
  }: {
    /** The record being edited; a blank record adds a new agent. */
    agent: CustomAgent;
    busy: boolean;
    cancel: () => void;
    /** The other custom agents, for the unique ID check. */
    others: { id: string }[];
    save: (agent: CustomAgent) => void;
  } = $props();
  // The form edits its own copy; a new agent's ID follows its name until edited.
  let draft = $state(untrack(() => ({ ...agent })));
  let idEdited = $state(untrack(() => !!agent.id));
  let error = $state('');
</script>

<form
  class="custom-agent"
  onsubmit={(e) => {
    e.preventDefault();
    error = customAgentError(draft, others);
    if (!error) save({ ...draft, binary: draft.binary.trim() || binaryFrom(draft.command) });
  }}
>
  <label
    >Display name<input
      required
      maxlength="60"
      bind:value={draft.name}
      oninput={() => {
        if (!idEdited) draft.id = agentIdFrom(draft.name);
      }}
      placeholder="My agent"
    /></label
  >
  <label
    >ID<input
      required
      maxlength="32"
      bind:value={draft.id}
      oninput={() => (idEdited = true)}
      placeholder="my-agent"
      spellcheck="false"
    /></label
  >
  <label
    >Launch command<input
      required
      maxlength="2000"
      bind:value={draft.command}
      placeholder="my-agent --interactive"
      spellcheck="false"
    /></label
  >
  <p class="hint">
    Runs exactly as typed, in the host user's shell, in the session's working directory.
  </p>
  <label
    >Install command (optional)<input
      maxlength="2000"
      bind:value={draft.install}
      placeholder="npm install --prefix ~/.local -g my-agent"
      spellcheck="false"
    /></label
  >
  <label
    >Detection binary (optional)<input
      maxlength="256"
      bind:value={draft.binary}
      placeholder={binaryFrom(draft.command) || 'my-agent'}
      spellcheck="false"
    /></label
  >
  <p class="hint">
    The program name or absolute path that shows the agent is installed and running. Blank uses the
    launch command's first word.
  </p>
  <label
    >Version command (optional)<input
      maxlength="500"
      bind:value={draft.version}
      placeholder="my-agent --version"
      spellcheck="false"
    /></label
  >
  {#if error}<p class="inline-error" role="alert">{error}</p>{/if}
  <div class="form-row">
    <button type="button" disabled={busy} onclick={cancel}>Cancel</button><button
      class="primary"
      disabled={busy}>{busy ? 'Saving…' : 'Save agent'}</button
    >
  </div>
</form>
