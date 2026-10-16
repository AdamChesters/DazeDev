<script lang="ts">
  import { type AppAction, appActions, appShortcut } from '../../../lib/app-shortcuts';
  import type { Device } from '../../../lib/types';
  let {
    appShortcutStatus = $bindable(),
    capturingAppShortcut = $bindable(),
    device,
    resetAppShortcuts,
  }: {
    appShortcutStatus: string;
    capturingAppShortcut: AppAction | null;
    device: Device;
    resetAppShortcuts: () => void;
  } = $props();
</script>

<details class="settings-section settings-disclosure">
  <summary>Keyboard shortcuts <span>App actions</span></summary>
  <p class="hint">
    Ctrl+letter shortcuts without Shift or Alt are left to the terminal while a terminal has focus,
    so shell and editor keys such as Ctrl+K keep working there.
  </p>
  {#each appActions as action}
    <div class="hotkey-record">
      <label
        >{action.label}<input
          readonly
          value={appShortcut(device.appShortcuts, action.id).label}
        /></label
      >
      <button
        onclick={() => {
          capturingAppShortcut = action.id;
          appShortcutStatus = 'Press the new key combination. Escape cancels.';
        }}>{capturingAppShortcut === action.id ? 'Press keys…' : 'Change'}</button
      >
    </div>
  {/each}
  <button onclick={resetAppShortcuts}>Reset to defaults</button>
  {#if appShortcutStatus}<p role="status" class="hint">{appShortcutStatus}</p>{/if}
</details>
