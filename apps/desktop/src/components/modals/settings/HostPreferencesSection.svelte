<script lang="ts">
  import type { Device, Pane, Profile, Repo } from '../../../lib/types';
  import { type PromptValues, defaultPrompt, promptVariables, renderPrompt, unknownVariables } from '../../../lib/prompt';
  import type { Task } from '../types';
  let {
    device,
    guidance = $bindable(),
    profiles,
    promptTemplate = $bindable(),
    promptValues,
    reloadHostProfile,
    roots = $bindable(),
    saveHostPreferences,
    settingsHost,
    task,
  }: {
    device: Device;
    guidance: string;
    profiles: Record<string, Profile>;
    promptTemplate: string;
    promptValues: (p?: Pane) => PromptValues;
    reloadHostProfile: () => Promise<void>;
    roots: string;
    saveHostPreferences: () => void;
    settingsHost: string;
    task: Task;
  } = $props();
</script>

{#if profiles[settingsHost]}<details class="settings-section settings-disclosure">
    <summary>
      {device.hosts.find((h) => h.id === settingsHost)?.name} preferences
      <span>Saved on host</span>
    </summary>
    <label
      >Repository roots · one per line<textarea rows="3" bind:value={roots}
      ></textarea></label
    ><label
      >Additional orientation guidance<textarea rows="3" bind:value={guidance}
      ></textarea></label
    >
    <label
      >Repository-start prompt template<textarea rows="8" bind:value={promptTemplate}
      ></textarea></label
    >
    <p class="hint">
      Used when starting an agent with orientation enabled and by the terminal Repo
      picker. Saved on this host.
    </p>
    <p class="hint">
      Variables: {promptVariables.map((v) => '{{' + v + '}}').join(', ')}. Values are
      inserted literally.
    </p>
    {#if unknownVariables(promptTemplate).length}<p class="inline-error">
        Unknown variables: {unknownVariables(promptTemplate).join(', ')}
      </p>{/if}
    <button onclick={() => (promptTemplate = defaultPrompt)}>Reset template</button>
    <details>
      <summary>Preview with example repository</summary>
      <pre class="prompt-preview">{renderPrompt(promptTemplate, promptValues())}</pre>
    </details>
    <div class="form-row">
      <button
        disabled={unknownVariables(promptTemplate).length > 0}
        onclick={saveHostPreferences}>Save host preferences</button
      ><button
        onclick={() => void task(reloadHostProfile)}>Reload host profile</button
      >
    </div>
  </details>{/if}
