<script lang="ts">
  import { onMount } from 'svelte';
  import type { editor } from 'monaco-editor/editor';
  let {
    path,
    value,
    readonly = true,
    onchange,
  }: {
    path: string;
    value: string;
    readonly?: boolean;
    onchange: (text: string) => void;
  } = $props();
  let container: HTMLDivElement;
  let instance: editor.IStandaloneCodeEditor | undefined;
  let loading = $state(true);
  let error = $state('');
  onMount(() => {
    let disposed = false;
    let model: editor.ITextModel | undefined;
    let subscription: { dispose(): void } | undefined;
    void import('../lib/monaco')
      .then(({ monaco, languageForPath }) => {
        if (disposed) return;
        model = monaco.editor.createModel(value, languageForPath(path));
        instance = monaco.editor.create(container, {
          model,
          readOnly: readonly,
          domReadOnly: readonly,
          theme: 'vs-dark',
          automaticLayout: true,
          minimap: { enabled: false },
          fontSize: 14,
          scrollBeyondLastLine: false,
          folding: true,
          lineNumbers: 'on',
          bracketPairColorization: { enabled: true },
          guides: { bracketPairs: true },
          links: false,
          tabFocusMode: true,
          ariaLabel: `Source: ${path}`,
          padding: { top: 10, bottom: 10 },
          stickyScroll: { enabled: false },
        });
        subscription = instance.onDidChangeModelContent(() => onchange(instance!.getValue()));
        loading = false;
        instance.focus();
      })
      .catch((e) => {
        if (!disposed) {
          error = String(e);
          loading = false;
        }
      });
    return () => {
      disposed = true;
      subscription?.dispose();
      instance?.dispose();
      model?.dispose();
      instance = undefined;
    };
  });
  $effect(() => {
    const text = value;
    if (instance && instance.getValue() !== text) instance.setValue(text);
  });
  $effect(() => {
    const locked = readonly;
    instance?.updateOptions({ readOnly: locked, domReadOnly: locked });
  });
</script>

<div class="source-viewer">
  {#if loading}<p role="status">Loading source viewer…</p>{/if}
  {#if error}<p role="alert">Source viewer could not load: {error}</p>
    <textarea
      aria-label="Document source fallback"
      {readonly}
      {value}
      oninput={(e) => onchange(e.currentTarget.value)}></textarea>
  {/if}
  <div class="editor" bind:this={container}></div>
</div>

<style>
  .source-viewer {
    position: relative;
    min-width: 0;
  }
  .editor {
    height: min(55dvh, 640px);
    min-height: 260px;
    border: 1px solid var(--tint-border);
  }
  textarea {
    width: 100%;
    min-height: 260px;
  }
</style>
