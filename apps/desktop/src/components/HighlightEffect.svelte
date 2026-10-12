<script lang="ts">
  import EnergyBorder from './EnergyBorder.svelte';
  import RainbowBorder from './RainbowBorder.svelte';
  import type { RecordingHighlight } from '../lib/highlights';
  let { kind }: { kind: RecordingHighlight } = $props();
</script>

{#if kind === 'energy'}<EnergyBorder />{:else if kind === 'rainbow'}<RainbowBorder />{/if}
