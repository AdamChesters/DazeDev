<script lang="ts">
  import ClipboardOptions from './ClipboardOptions.svelte';
  import type { Device } from '../../../lib/types';
  import { appShortcut } from '../../../lib/app-shortcuts';
  let {
    device = $bindable(),
    fail,
    setPasteConfirmation,
    softAlert,
  }: {
    device: Device;
    fail: (e: unknown) => void;
    setPasteConfirmation: (confirm: boolean) => void;
    softAlert: () => Promise<void>;
  } = $props();
</script>

{#if device.terminal}<details class="settings-section settings-disclosure">
    <summary>Terminal <span>Text, notifications and clipboard</span></summary>
    <label class="check"
      ><input
        type="checkbox"
        checked={device.terminalAlerts?.visual || false}
        onchange={(e) => {
          device.terminalAlerts = {
            sound: device.terminalAlerts?.sound || false,
            visual: e.currentTarget.checked,
          };
        }}
      /> Show terminal completion notifications</label
    >
    <label class="check"
      ><input
        type="checkbox"
        checked={device.terminalAlerts?.sound || false}
        onchange={(e) => {
          device.terminalAlerts = {
            visual: device.terminalAlerts?.visual || false,
            sound: e.currentTarget.checked,
          };
          if (e.currentTarget.checked) void softAlert().catch(fail);
        }}
      /> Play a soft notification sound</label
    >
    <p class="hint">
      Uses explicit terminal notifications or shell command-completion markers. Support depends on
      the agent or shell integration; DazeDev does not infer completion from inactivity. "Command
      finished" shows when you are not watching that terminal or the command ran for 10 seconds or
      more. Each terminal alerts at most once every three seconds.
    </p>
    <div class="form-row">
      <label
        >Font size<input
          type="number"
          min="8"
          max="32"
          bind:value={device.terminal.fontSize}
        /></label
      >
      <label
        >History lines<input
          type="number"
          min="100"
          max="100000"
          step="100"
          bind:value={device.terminal.scrollback}
        /></label
      >
    </div>
    <p class="hint">
      Each open terminal keeps this many lines in memory, including terminals in other workspaces.
      Default 10,000; up to 100,000.
    </p>
    <div class="settings-checks">
      <label class="check"
        ><input type="checkbox" bind:checked={device.terminal.gpu} /> GPU rendering</label
      >
      <label class="check"
        ><input type="checkbox" bind:checked={device.terminal.images} /> Terminal images</label
      >
      <label class="check"
        ><input type="checkbox" bind:checked={device.terminal.ligatures} /> Font ligatures</label
      >
    </div>
    <label class="check"
      ><input type="checkbox" bind:checked={device.terminal.screenReader} />Screen reader terminal
      output</label
    >
    <label class="check"
      ><input
        type="checkbox"
        checked={device.terminal.confirmPaste !== false}
        onchange={(e) => setPasteConfirmation(e.currentTarget.checked)}
      /> Ask before pasting text with line breaks</label
    >
    <label
      >Remote copy (OSC 52)<select bind:value={device.terminal.clipboard}>
        <ClipboardOptions />
      </select></label
    >
    <p class="hint">
      Copy: Ctrl+Shift+C. Paste: Ctrl+V or Ctrl+Shift+V. Search: {appShortcut(
        device.appShortcuts,
        'search',
      ).label}. Remote paste access lets programs on the host read this device's clipboard.
    </p>
  </details>{/if}
