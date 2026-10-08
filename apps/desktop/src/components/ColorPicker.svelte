<script lang="ts">
  import { palettes, tabColors, contrast, type TabColors } from '../lib/colors';
  let {
    value,
    inherited,
    onChange,
    resetLabel = 'Use theme colors',
  }: {
    value?: TabColors;
    inherited?: TabColors;
    onChange: (value?: TabColors) => void;
    resetLabel?: string;
  } = $props();
  let vivid = $state(false);
  const actual = $derived(
    tabColors(value, inherited) || { background: '#3a5269', foreground: '#ffffff' },
  );
</script>

<fieldset class="color-picker">
  <legend>Tab colors</legend>
  <label class="check"><input type="checkbox" bind:checked={vivid} /> Vivid presets</label>
  <div class="color-presets">
    {#each palettes as color}<button
        type="button"
        title={color.name}
        aria-label={`${color.name} ${vivid ? 'vivid' : 'muted'} preset`}
        style:background={vivid ? color.vivid : color.muted}
        style:color="#ffffff"
        onclick={() =>
          onChange({ background: vivid ? color.vivid : color.muted, foreground: '#ffffff' })}
        >{color.name}</button
      >{/each}
  </div>
  <div class="form-row">
    <label
      >Background<input
        type="color"
        value={actual.background}
        oninput={(e) => onChange({ ...actual, background: e.currentTarget.value })}
      /></label
    >
    <label
      >Text<input
        type="color"
        value={actual.foreground}
        oninput={(e) => onChange({ ...actual, foreground: e.currentTarget.value })}
      /></label
    >
  </div>
  <div class="color-preview" style:background={actual.background} style:color={actual.foreground}>
    Workspace / terminal title
  </div>
  {#if contrast(actual) < 4.5}<p class="hint">
      Low text contrast. Choose a lighter text color or darker background for readability.
    </p>{/if}
  <button type="button" onclick={() => onChange(undefined)}>{resetLabel}</button>
</fieldset>

<style>
  .color-picker {
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 12px;
    margin: 12px 0;
  }
  .color-presets {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 8px 0;
  }
  .color-presets button {
    font-size: 12px;
    padding: 7px 10px;
  }
  .color-preview {
    padding: 10px;
    border-radius: 4px;
    margin-bottom: 8px;
  }
  input[type='color'] {
    height: 34px;
    width: 100%;
    padding: 2px;
  }
</style>
