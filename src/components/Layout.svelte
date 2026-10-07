<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { Layout as Node } from '../lib/types';
  import Layout from './Layout.svelte';
  let {
    node,
    pane,
    onresize,
  }: { node: Node; pane: Snippet<[string]>; onresize: (id: string, ratio: number) => void } =
    $props();
  let el = $state<HTMLDivElement>();
  let ratio = $state(0);
  let dragging = $state(false);
  function start(e: PointerEvent) {
    if (node.kind !== 'split') return;
    const n = node;
    dragging = true;
    const rect = el!.getBoundingClientRect();
    const move = (e: PointerEvent) => {
      ratio = Math.max(
        0.08,
        Math.min(
          0.92,
          n.axis === 'row'
            ? (e.clientX - rect.left) / rect.width
            : (e.clientY - rect.top) / rect.height,
        ),
      );
      onresize(n.id, ratio);
    };
    const stop = () => {
      dragging = false;
      onresize(n.id, ratio);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
    ratio = n.ratio;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    e.preventDefault();
  }
</script>

{#if node.kind === 'pane'}{@render pane(node.id)}{:else}<div
    class="split"
    class:vertical={node.axis === 'column'}
    bind:this={el}
    style={`--ratio:${(dragging ? ratio : node.ratio) * 100}%`}
  >
    <div class="split-first"><Layout node={node.first} {pane} {onresize} /></div>
    <div
      role="slider"
      tabindex="0"
      aria-label="Resize workspace tiles"
      aria-orientation={node.axis === 'row' ? 'vertical' : 'horizontal'}
      aria-valuemin="8"
      aria-valuemax="92"
      aria-valuenow={Math.round(node.ratio * 100)}
      class="divider"
      onpointerdown={start}
      onkeydown={(e) => {
        if (
          node.kind === 'split' &&
          ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)
        ) {
          e.preventDefault();
          onresize(node.id, node.ratio + (['ArrowLeft', 'ArrowUp'].includes(e.key) ? -0.03 : 0.03));
        }
      }}
    ></div>
    <div class="split-second"><Layout node={node.second} {pane} {onresize} /></div>
  </div>{/if}
