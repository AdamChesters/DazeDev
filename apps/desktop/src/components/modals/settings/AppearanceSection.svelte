<script lang="ts">
  import HighlightEffect from '../../../components/HighlightEffect.svelte';
  import type { Device } from '../../../lib/types';
  import { recordingHighlight, recordingHighlights } from '../../../lib/highlights';
  let {
    device = $bindable(),
    persistDevice,
    systemReducedMotion,
  }: {
    device: Device;
    persistDevice: () => void;
    systemReducedMotion: boolean;
  } = $props();
</script>

<details class="settings-section settings-disclosure">
  <summary>Appearance <span>{device.theme}</span></summary>
  <div class="settings-grid">
    <label
      >Theme<select bind:value={device.theme} onchange={persistDevice}
        ><option value="blue">Blue</option><option value="charcoal">Charcoal</option><option
          value="green">Green</option
        ></select
      ></label
    >
    <label
      >Recording highlight<select
        value={recordingHighlight(device.recordingHighlight)}
        onchange={(e) => {
          device.recordingHighlight = recordingHighlight(e.currentTarget.value);
          persistDevice();
        }}
        >{#each recordingHighlights as effect}<option value={effect.id}>{effect.name}</option
          >{/each}</select
      ></label
    >
    <label
      >Highlight animation<select bind:value={device.highlightMotion} onchange={persistDevice}>
        <option value="system">Follow system</option>
        <option value="animate">Always animate highlights</option>
        <option value="static">Keep highlights still</option>
      </select></label
    >
    {#if systemReducedMotion && (!device.highlightMotion || device.highlightMotion === 'system')}
      <p class="muted">
        Your system has reduced motion enabled. Choose Always animate highlights to enable movement
        here.
      </p>
    {/if}
    {#if recordingHighlight(device.recordingHighlight) === 'static'}<label
        >Highlight colour<input
          type="color"
          value={device.recordingHighlightColor || '#39bdd7'}
          oninput={(e) => {
            device.recordingHighlightColor = e.currentTarget.value;
            persistDevice();
          }}
        /></label
      >{/if}
  </div>
  <div
    class="recording-highlight highlight-preview"
    data-highlight={recordingHighlight(device.recordingHighlight)}
    style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
    aria-label="Recording highlight preview"
  >
    {#if recordingHighlight(device.recordingHighlight) !== 'static'}<HighlightEffect
        kind={recordingHighlight(device.recordingHighlight)}
      />{/if}
    Highlight preview
  </div>
</details>
