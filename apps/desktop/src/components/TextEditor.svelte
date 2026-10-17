<script lang="ts">
  import { onMount } from 'svelte';
  import type { editor } from 'monaco-editor/editor';
  // An editable Monaco editor for prompt text, with a plain textarea when
  // Monaco cannot load. `onchange` reports only edits the user makes; the
  // owner keeps the original text until then, because both editors
  // normalise line endings.
  let {
    value,
    label,
    onchange,
    onsubmit,
    oncancel,
  }: {
    value: string;
    label: string;
    onchange: (text: string) => void;
    onsubmit: () => void;
    oncancel: () => void;
  } = $props();
  let container: HTMLDivElement;
  let fallback = $state<HTMLTextAreaElement>();
  let loading = $state(true);
  let error = $state('');
  onMount(() => {
    let disposed = false;
    let instance: editor.IStandaloneCodeEditor | undefined;
    let model: editor.ITextModel | undefined;
    let subscription: { dispose(): void } | undefined;
    void import('../lib/monaco')
      .then(async ({ monaco, loadLanguage }) => {
        await loadLanguage('markdown');
        if (disposed) return;
        model = monaco.editor.createModel(value, 'markdown');
        instance = monaco.editor.create(container, {
          model,
          theme: 'vs-dark',
          automaticLayout: true,
          minimap: { enabled: false },
          fontSize: 14,
          wordWrap: 'on',
          scrollBeyondLastLine: false,
          lineNumbers: 'on',
          links: false,
          tabFocusMode: true,
          ariaLabel: label,
          padding: { top: 8, bottom: 8 },
          stickyScroll: { enabled: false },
        });
        instance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => onsubmit());
        instance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => onsubmit());
        subscription = instance.onDidChangeModelContent(() => onchange(instance!.getValue()));
        loading = false;
        instance.focus();
      })
      .catch((e) => {
        if (disposed) return;
        error = String(e);
        loading = false;
        queueMicrotask(() => fallback?.focus());
      });
    return () => {
      disposed = true;
      subscription?.dispose();
      instance?.dispose();
      model?.dispose();
    };
  });
</script>

<!-- Escape cancels from either editor; Monaco closes its own widgets first. -->
<div
  class="text-editor"
  role="presentation"
  onkeydown={(event) => {
    if (event.key === 'Escape' && !event.defaultPrevented) {
      event.preventDefault();
      event.stopPropagation();
      oncancel();
    }
  }}
>
  {#if loading}<p role="status">Loading editor…</p>{/if}
  {#if error}<textarea
      bind:this={fallback}
      aria-label={label}
      {value}
      spellcheck="false"
      oninput={(e) => onchange(e.currentTarget.value)}
      onkeydown={(e) => {
        if ((e.ctrlKey || e.metaKey) && (e.key === 'Enter' || e.key === 's')) {
          e.preventDefault();
          onsubmit();
        }
      }}></textarea>
  {/if}
  <div class="editor" class:hidden={!!error} bind:this={container}></div>
</div>

<style>
  .text-editor {
    position: relative;
    flex: 1;
    min-height: 120px;
    display: flex;
    flex-direction: column;
  }
  .editor {
    flex: 1;
    min-height: 120px;
    border: 1px solid var(--border);
  }
  .hidden {
    display: none;
  }
  textarea {
    flex: 1;
    min-height: 120px;
    width: 100%;
    box-sizing: border-box;
    resize: none;
    font-family: var(--terminal-font, 'Cascadia Code', 'Consolas', monospace);
    font-size: 13px;
    white-space: pre-wrap;
  }
  p {
    margin: 4px 0;
    font-size: 12px;
  }
</style>
