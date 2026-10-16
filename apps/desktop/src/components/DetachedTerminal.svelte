<script lang="ts">
  import type { SearchNavigation } from '../lib/terminal-search';
  import { restoreLinkHoverOnReentry } from '../lib/link-hover';
  import { copySelectedTerminalText } from '../lib/terminal-copy';
  import HtmlPreview from './HtmlPreview.svelte';
  import SourceViewer from './SourceViewer.svelte';
  import { previewKind } from '../lib/source-viewer';
  import { markdownPreview, type DocumentText } from '../lib/documents';
  import { openPreviewLink } from '../lib/preview-links';
  import ImageViewer from './ImageViewer.svelte';
  import { imagePath, registerViewerLinks, readHostImage, type ViewedImage } from '../lib/images';
  import { onMount } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import { SearchAddon } from '@xterm/addon-search';
  import { registerWebLinks } from '../lib/web-links';
  import { Unicode11Addon } from '@xterm/addon-unicode11';
  import { WebglAddon } from '@xterm/addon-webgl';
  import { ImageAddon } from '@xterm/addon-image';
  import { ClipboardAddon } from '@xterm/addon-clipboard';
  import { LigaturesAddon } from '@xterm/addon-ligatures';
  import { call, on, rpc, openExternal, desktop } from '../lib/bridge';
  import { terminalPresentation } from '../lib/pane-status';
  import { deadPaneTarget } from '../lib/terminal-recovery';
  import { readClipboardContent, uploadClipboardFiles } from '../lib/clipboard-upload';
  import {
    TerminalStream,
    type TerminalSnapshot,
    type TerminalOutput,
    type WindowTerminalState,
  } from '../lib/terminal-window';
  import { VoiceCaptureKeys } from '../lib/voice-capture-keys';
  import { matches, mouseMatches } from '../lib/shortcut';
  import { tabColors } from '../lib/colors';
  import '@xterm/xterm/css/xterm.css';

  const paneId = new URLSearchParams(location.search).get('terminal') || '';
  let root: HTMLDivElement;
  let terminal: Terminal;
  let fit: FitAddon;
  let searcher: SearchAddon;
  let viewState = $state<WindowTerminalState | null>(null);
  let recoveryBusy = $state(false);
  let recoveryError = $state('');
  const endedTarget = $derived(viewState?.metadata ? deadPaneTarget(viewState.metadata) : null);
  async function recover() {
    if (recoveryBusy) return;
    recoveryBusy = true; recoveryError = '';
    try { await request('recover'); }
    catch (error) { recoveryError = String(error); }
    finally { recoveryBusy = false; }
  }
  const presentation = $derived(viewState
    ? terminalPresentation(viewState.pane, viewState.metadata)
    : { name: 'Terminal', type: 'shell', model: '' });
  let lastWindowTitle = '';
  $effect(() => {
    if (!viewState) return;
    const title = `${viewState.workspaceName} / ${presentation.name} - DazeDev`;
    document.title = title;
    if (desktop && lastWindowTitle !== title) {
      lastWindowTitle = title;
      void getCurrentWindow().setTitle(title).catch(() => { lastWindowTitle = ''; });
    }
  });
  let image = $state<ViewedImage | null>(null);
  let viewedDocument = $state<DocumentText | null>(null);
  async function viewImage(path: string) {
    try {
      const hostId = viewState?.pane.hostId;
      if (!hostId) throw Error('Terminal host is unavailable.');
      if (!imagePath(path) && !/^https?:\/\//i.test(path)) {
        const result = await rpc<DocumentText>(hostId, 'file_read', {
          path,
          directory: viewState?.pane.repo || '',
        });
        image = null;
        viewedDocument = result;
        return;
      }
      viewedDocument = null;
      image = /^https?:\/\//i.test(path)
        ? { path, src: path }
        : await readHostImage(hostId, path, viewState?.pane.repo || '');
    } catch (error) {
      fail(error);
    }
  }
  let notice = $state('Connecting to workspace...');
  let searchNavigation = $state<SearchNavigation | null>(null);
  let navigationInput = $state<HTMLInputElement>();
  let query = $state('');
  let searchOpen = $state(false);
  let searchInput = $state<HTMLInputElement>();
  let uploading = $state(false);
  let menu = $state(false);
  let context = '';
  let syncing = false;
  let syncAgain = false;
  let gapTimer: ReturnType<typeof setTimeout>;
  let disposed = false;
  let resizeFrame = 0;
  let lastSize = '';
  let inputChain = Promise.resolve();
  const stream = new TerminalStream();
  const captureKeys = new VoiceCaptureKeys();
  const pending = new Map<
    string,
    {
      resolve: (value: unknown) => void;
      reject: (reason: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  const colors = $derived(
    viewState &&
      tabColors(viewState.pane.colors, viewState.workspaceColors, viewState.host?.colors),
  );
  function request<T>(action: string, args: Record<string, unknown> = {}): Promise<T> {
    if (disposed) return Promise.reject(new Error('Terminal window is closed.'));
    const requestId = crypto.randomUUID();
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId);
        reject(
          new Error(
            action === 'input' || action === 'paste'
              ? 'Delivery uncertain. Input was not retried.'
              : 'The workspace window did not respond.',
          ),
        );
      }, 20000);
      pending.set(requestId, { resolve: (value) => resolve(value as T), reject, timer });
      void call('terminal_window_request', { requestId, action, args }).catch((error) => {
        clearTimeout(timer);
        pending.delete(requestId);
        reject(new Error(String(error)));
      });
    });
  }
  function fail(error: unknown) {
    notice = String(error);
  }
  function write(outputs: TerminalOutput[]) {
    for (const output of outputs)
      terminal.write(Uint8Array.from(atob(output.data), (c) => c.charCodeAt(0)));
  }
  async function sync() {
    if (disposed) return;
    if (syncing) {
      syncAgain = true;
      return;
    }
    syncing = true;
    try {
      const result = await request<{ state: WindowTerminalState; snapshot: TerminalSnapshot }>(
        'snapshot',
      );
      if (disposed) return;
      viewState = result.state;
      context = result.snapshot.context;
      terminal.resize(result.snapshot.cols, result.snapshot.rows);
      terminal.reset();
      await new Promise<void>((resolve) => terminal.write(result.snapshot.data, resolve));
      write(stream.restore(result.snapshot));
      notice = viewState.ready ? '' : viewState.status || 'Disconnected. Reconnect when ready.';
      lastSize = '';
      fitted();
    } catch (error) {
      fail(error);
    } finally {
      syncing = false;
      fitted();
      if (syncAgain) {
        syncAgain = false;
        void sync();
      }
    }
  }
  function fitted() {
    if (!fit || resizeFrame || disposed || syncing) return;
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = 0;
      try {
        fit.fit();
        const size = `${terminal.cols}:${terminal.rows}`;
        if (size !== lastSize) {
          lastSize = size;
          void request('resize', { cols: terminal.cols, rows: terminal.rows }).catch((error) => {
            lastSize = '';
            fail(error);
          });
        }
      } catch (error) {
        fail(error);
      }
    });
  }
  async function action(action: string) {
    menu = false;
    try {
      await request(action);
    } catch (error) {
      fail(error);
    }
  }
  function search() {
    void request('search-open').catch(fail);
  }
  async function pasteFiles(files: File[]) {
    if (uploading || !viewState?.ready || !context)
      throw new Error('Connect this terminal before pasting a file.');
    uploading = true;
    const destination = context;
    const hostId = viewState.pane.hostId;
    try {
      const paths = await uploadClipboardFiles(
        files,
        (method, args) => rpc(hostId, method, args),
        () =>
          !disposed &&
          context === destination &&
          viewState?.pane.hostId === hostId &&
          !!viewState.ready,
        (message) => {
          notice = message;
        },
        viewState.host?.kind === 'powershell',
      );
      context = await request<string>('paste', { text: paths, context: destination });
      notice = 'Uploaded to this terminal’s host. Path pasted without Enter.';
      terminal.focus();
    } finally {
      uploading = false;
    }
  }
  async function pasteClipboard() {
    const destination = context;
    const content = await readClipboardContent(navigator.clipboard);
    if (!destination || context !== destination)
      throw new Error('Terminal changed while reading the clipboard. Paste again.');
    if (content.files.length) await pasteFiles(content.files);
    else {
      context = await request<string>('paste', { text: content.text, context: destination });
      terminal.focus();
    }
  }
  $effect(() => {
    if (viewState && terminal) {
      document.documentElement.dataset.theme = viewState.theme;
      terminal.options.fontSize = viewState.settings.fontSize;
      terminal.options.scrollback = viewState.settings.scrollback;
      terminal.options.screenReaderMode = !!viewState.settings.screenReader;
      fitted();
    }
  });
  onMount(() => {
    let cleanup: (() => void)[] = [];
    const fontSize = 13;
    let hoveredImage = '';
    const followLink = (event: MouseEvent, url: string) => {
      const modified = event.ctrlKey || event.metaKey;
      if (modified && /^https?:\/\//i.test(url)) void openExternal(url).catch(fail);
      else if (imagePath(url) && (modified || !terminal.hasSelection())) void viewImage(url);
    };
    const hoverLink = (url: string) => {
      hoveredImage = imagePath(url) ? url : '';
      root.title = url
        ? imagePath(url)
          ? 'Click to preview; Ctrl+click (Cmd+click on macOS) to open in your browser'
          : 'Ctrl+click (Cmd+click on macOS) to open in your browser'
        : '';
    };
    terminal = new Terminal({
      fontSize,
      fontFamily: 'Cascadia Code, Consolas, monospace',
      scrollback: 4000,
      cursorBlink: true,
      theme: { background: '#151616', foreground: '#d5d9d2', cursor: '#39bdd7' },
      allowProposedApi: true,
      linkHandler: {
        activate: followLink,
        hover: (_event, url) => hoverLink(url),
        leave: () => hoverLink(''),
      },
    });
    fit = new FitAddon();
    searcher = new SearchAddon();
    terminal.loadAddon(fit);
    terminal.loadAddon(searcher);
    registerViewerLinks(terminal, (path) => void viewImage(path), {
      hover: (path) => {
        hoveredImage = imagePath(path) ? path : '';
        root.title = path
          ? imagePath(path)
            ? 'Click to open in the viewer'
            : 'Ctrl+click (Cmd+click on macOS) to open in the viewer'
          : '';
      },
    });
    const webLinks = registerWebLinks(terminal, followLink, hoverLink);
    cleanup.push(() => webLinks.dispose());
    const unicode = new Unicode11Addon();
    terminal.loadAddon(unicode);
    terminal.unicode.activeVersion = '11';
    terminal.open(root);
    cleanup.push(restoreLinkHoverOnReentry(terminal));
    let imagePress: { url: string; x: number; y: number; modified: boolean } | null = null;
    const selectionEvents = new WeakSet<Event>();
    const imageDown = (event: MouseEvent) => {
      if (selectionEvents.has(event) || event.button !== 0 || !hoveredImage) return;
      const modified = event.ctrlKey || event.metaKey;
      if (!modified && terminal.modes.mouseTrackingMode === 'none') return;
      imagePress = { url: hoveredImage, x: event.clientX, y: event.clientY, modified };
      event.preventDefault();
      event.stopImmediatePropagation();
      if (modified) terminal.focus();
      else {
        const mac = /Mac|iPhone|iPad|iPod/i.test(navigator.platform);
        const selection = new MouseEvent('mousedown', {
          bubbles: true,
          cancelable: true,
          clientX: event.clientX,
          clientY: event.clientY,
          button: event.button,
          buttons: event.buttons,
          shiftKey: !mac,
          altKey: mac,
        });
        selectionEvents.add(selection);
        event.target?.dispatchEvent(selection);
      }
    };
    const imageUp = (event: MouseEvent) => {
      if (!imagePress || event.button !== 0) return;
      const pressed = imagePress;
      imagePress = null;
      if (
        root.contains(event.target as Node) &&
        hoveredImage === pressed.url &&
        (event.ctrlKey || event.metaKey) === pressed.modified &&
        Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < 5
      ) {
        event.preventDefault();
        event.stopImmediatePropagation();
        followLink(event, pressed.url);
      }
    };
    const cancelImage = () => (imagePress = null);
    root.addEventListener('mousedown', imageDown, true);
    window.addEventListener('mouseup', imageUp, true);
    window.addEventListener('blur', cancelImage);
    cleanup.push(() => {
      root.removeEventListener('mousedown', imageDown, true);
      window.removeEventListener('mouseup', imageUp, true);
      window.removeEventListener('blur', cancelImage);
    });
    const data = terminal.onData((value) => {
      if (!viewState?.ready) {
        notice = 'Terminal is disconnected. Reconnect when ready.';
        return;
      }
      inputChain = inputChain
        .then(async () => {
          if (!viewState?.ready)
            throw new Error('Terminal input is paused. Reconnect before typing.');
          context = await request<string>('input', { data: value });
        })
        .catch((error) => {
          // Never resend uncertain keystrokes after a timeout or connection loss.
          fail(error);
          if (viewState) viewState.ready = false;
        });
    });
    const binary = terminal.onBinary((value) => {
      if (viewState?.ready) void request('binary', { data: btoa(value) }).catch(fail);
    });
    terminal.attachCustomKeyEventHandler((event) => {
      if (
        copySelectedTerminalText(
          event,
          terminal.getSelection(),
          (text) => text.length <= 1048576 ? navigator.clipboard.writeText(text) : Promise.reject(new Error('Selection is too large for the clipboard.')),
          (message) => (notice = message),
        )
      )
        return false;
      if (event.type !== 'keydown' || !(event.ctrlKey || event.metaKey)) return true;
      if (!event.altKey && event.code === 'KeyV') {
        event.preventDefault();
        void pasteClipboard().catch(fail);
        return false;
      }
      if (event.shiftKey && event.code === 'KeyF') {
        event.preventDefault();
        search();
        return false;
      }
      return true;
    });
    const paste = (event: ClipboardEvent) => {
      const files = Array.from(event.clipboardData?.files || []);
      if (!files.length) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      void pasteFiles(files).catch(fail);
    };
    const forwardKey = (event: KeyboardEvent) => {
      if (!viewState) return;
      const control =
        event.type === 'keyup'
          ? captureKeys.up(event.key)
          : captureKeys.down(event, viewState.voiceStatus, document.hasFocus()).consume;
      if (
        !control &&
        !matches(event, viewState.inputShortcut) &&
        !matches(event, viewState.commandShortcut)
      )
        return;
      event.preventDefault();
      event.stopImmediatePropagation();
      void request('key', {
        type: event.type,
        key: event.key,
        code: event.code,
        ctrlKey: event.ctrlKey,
        altKey: event.altKey,
        shiftKey: event.shiftKey,
        metaKey: event.metaKey,
        repeat: event.repeat,
      }).catch(fail);
    };
    const forwardPointer = (event: PointerEvent) => {
      if (
        !viewState ||
        ![viewState.inputShortcut, viewState.commandShortcut].some((binding) =>
          mouseMatches(event, binding),
        )
      )
        return;
      event.preventDefault();
      event.stopImmediatePropagation();
      void request('pointer', { type: event.type, button: event.button }).catch(fail);
    };
    const mouse = (event: MouseEvent) => {
      if (
        viewState &&
        [viewState.inputShortcut, viewState.commandShortcut].some((binding) =>
          mouseMatches(event, binding),
        )
      )
        event.preventDefault();
    };
    window.addEventListener('keydown', forwardKey, true);
    window.addEventListener('keyup', forwardKey, true);
    window.addEventListener('auxclick', mouse, true);
    window.addEventListener('pointerdown', forwardPointer, true);
    window.addEventListener('pointerup', forwardPointer, true);
    cleanup.push(() => {
      window.removeEventListener('keydown', forwardKey, true);
      window.removeEventListener('keyup', forwardKey, true);
      window.removeEventListener('auxclick', mouse, true);
      window.removeEventListener('pointerdown', forwardPointer, true);
      window.removeEventListener('pointerup', forwardPointer, true);
      clearTimeout(gapTimer);
    });
    root.addEventListener('paste', paste, true);
    const observer = new ResizeObserver(fitted);
    observer.observe(root);
    void (async () => {
      const stop = await on<{
        kind?: string;
        requestId?: string;
        result?: unknown;
        error?: string;
        navigation?: SearchNavigation | null;
        selection?: { start: { x: number; y: number }; end: { x: number; y: number } };
        state?: WindowTerminalState;
        sequence?: number;
        generation?: number;
        data?: string;
      }>('terminal-window-message', (message) => {
        if (message.requestId) {
          const job = pending.get(message.requestId);
          if (!job) return;
          clearTimeout(job.timer);
          pending.delete(message.requestId);
          if (message.error) job.reject(new Error(message.error));
          else job.resolve(message.result);
        } else if (message.kind === 'search-navigation') {
          const wasSearching = !!searchNavigation;
          searchNavigation = message.navigation || null;
          if (searchNavigation) requestAnimationFrame(() => navigationInput?.focus());
          else if (wasSearching) requestAnimationFrame(() => terminal.focus());
        } else if (message.kind === 'search-selection' && message.selection) {
          const { start, end } = message.selection;
          terminal.write('', () => {
            if (disposed) return;
            terminal.scrollToLine(start.y);
            terminal.select(start.x, start.y, (end.y - start.y) * terminal.cols + end.x - start.x);
          });
        } else if (message.kind === 'focus') {
          if (searchNavigation) navigationInput?.focus();
          else terminal.focus();
        } else if (message.kind === 'output') {
          write(stream.receive(message as TerminalOutput));
          if (stream.hasGap && !gapTimer)
            gapTimer = setTimeout(() => {
              gapTimer = undefined!;
              if (stream.hasGap) void sync();
            }, 300);
        } else if (message.kind === 'state' && message.state) {
          const changed =
            viewState &&
            (viewState.pane.hostId !== message.state.pane.hostId ||
              viewState.pane.sessionId !== message.state.pane.sessionId ||
              viewState.ready !== message.state.ready);
          viewState = message.state;
          if (changed) void sync();
          if (!viewState.ready) notice = viewState.status || 'Disconnected. Reconnect when ready.';
        }
      });
      if (disposed) {
        stop();
        return;
      }
      cleanup.push(stop);
      await sync();
      if (disposed) return;
      if (viewState?.settings.gpu) {
        try {
          const gpu = new WebglAddon();
          terminal.loadAddon(gpu);
          gpu.onContextLoss(() => gpu.dispose());
        } catch {
          /* Canvas remains available. */
        }
      }
      if (viewState?.settings.images) terminal.loadAddon(new ImageAddon());
      if (viewState?.settings.ligatures) terminal.loadAddon(new LigaturesAddon());
      terminal.loadAddon(
        new ClipboardAddon(undefined, {
          readText: async () =>
            viewState?.settings.clipboard === 'read-write' ? navigator.clipboard.readText().then((text) => text.length <= 1048576 ? text : '') : '',
          writeText: async (_selection, text) => {
            if (viewState?.settings.clipboard !== 'off' && text.length <= 1048576) await navigator.clipboard.writeText(text);
          },
        }),
      );
      terminal.focus();
      const stopFocus = await getCurrentWindow().onFocusChanged((event) => {
        if (event.payload) void request('focus').catch(fail);
      });
      if (disposed) stopFocus();
      else cleanup.push(stopFocus);
    })().catch(fail);
    return () => {
      disposed = true;
      observer.disconnect();
      cancelAnimationFrame(resizeFrame);
      root.removeEventListener('paste', paste, true);
      for (const job of pending.values()) {
        clearTimeout(job.timer);
        job.reject(new Error('Terminal window was closed.'));
      }
      pending.clear();
      cleanup.forEach((stop) => stop());
      data.dispose();
      binary.dispose();
      terminal.dispose();
    };
  });
</script>

<div class="detached-shell">
  {#if searchNavigation}<div
      class="scoped-search-navigation"
      role="search"
      aria-label="Search live terminals"
    >
      <div class="navigation-row">
        <input
          bind:this={navigationInput}
          value={searchNavigation.query}
          readonly
          aria-label="Search term"
          onkeydown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              void request('search-step', { direction: event.shiftKey ? -1 : 1 }).catch(fail);
            }
            if (event.key === 'Escape') {
              event.preventDefault();
              void request('search-close').catch(fail);
            }
          }}
        />
        <button
          aria-label="Previous match"
          onclick={() => void request('search-step', { direction: -1 }).catch(fail)}
          >Previous</button
        >
        <span aria-live="polite">{searchNavigation.position}</span>
        <button
          aria-label="Next match"
          onclick={() => void request('search-step', { direction: 1 }).catch(fail)}>Next</button
        >
        <button onclick={() => void request('search-results').catch(fail)}>Results</button>
        <button aria-label="Close search" onclick={() => void request('search-close').catch(fail)}
          >×</button
        >
      </div>
      <div class="navigation-location">{searchNavigation.destination}</div>
    </div>{/if}
  <header style:background-color={colors?.background} style:color={colors?.foreground}>
    <span class="title pane-title"
      ><span class="terminal-name" title={presentation.name}
        >{presentation.name}</span
      ><span
        class="terminal-type"
        title={`${presentation.type}${presentation.model ? ' / ' + presentation.model : ''}`}
        >{presentation.type}</span
      ></span
    >
    <button onclick={() => void action('host')} title="Connection options for this terminal’s host"
      >{viewState?.host?.name || 'Host'}</button
    >
    <button
      onclick={() => {
        menu = !menu;
      }}
      aria-expanded={menu}>Menu</button
    >
    <button onclick={() => void action('dock')}>Return to workspace</button>
  </header>
  {#if endedTarget}<div class="ended-banner" aria-label="Session ended">
      <p role="status">Session ended. Open a new terminal here to continue.</p>
      {#if recoveryError}<p role="alert">{recoveryError}</p>{/if}
      <button disabled={recoveryBusy} onclick={() => void recover()}
        >{recoveryBusy ? 'Opening terminal…' : 'Open terminal'}</button>
      <button disabled={recoveryBusy} onclick={() => void action('close')}>Close view</button>
    </div>{/if}
  {#if menu}<nav aria-label="Terminal actions">
      <button onclick={() => void action('repo')}>Repo</button>
      <button onclick={() => void action('tmux')}
        >{viewState?.host?.kind === 'powershell' ? 'Sessions' : 'tmux'}</button
      >
      <button
        onclick={() => void action('model')}
        disabled={!viewState ||
          !['codex', 'claude', 'gemini', 'opencode'].includes(viewState.pane.agent)}
        >Model / effort</button
      >
      <button onclick={search}>Find</button>
      <button
        onclick={() => {
          void navigator.clipboard.writeText(terminal.getSelection()).catch(fail);
          terminal.focus();
        }}>Copy</button
      >
      <button onclick={() => void pasteClipboard().catch(fail)}>Paste</button>
      <button
        onclick={() => {
          terminal.selectAll();
          menu = false;
        }}>Select all</button
      >
      <button onclick={() => void action('close')}>Close</button>
    </nav>{/if}
  {#if searchOpen}<div class="terminal-search" role="search" aria-label="Search terminal">
      <input
        bind:this={searchInput}
        bind:value={query}
        aria-label="Search terminal output"
        oninput={() => searcher.findNext(query)}
        onkeydown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            if (event.shiftKey) searcher.findPrevious(query);
            else searcher.findNext(query);
          }
          if (event.key === 'Escape') {
            searchOpen = false;
            terminal.focus();
          }
        }}
      />
      <button onclick={() => searcher.findPrevious(query)}>Previous</button><button
        onclick={() => searcher.findNext(query)}>Next</button
      >
      <button
        onclick={() => {
          searchOpen = false;
          searcher.clearDecorations();
          terminal.focus();
        }}>Close search</button
      >
    </div>{/if}
  {#if viewState && viewState.voiceTarget === viewState.pane.id && viewState.voiceStatus !== 'idle'}
    <p class="status" role="status">
      {viewState.voiceStatus === 'listening'
        ? 'Listening'
        : viewState.voiceStatus}{viewState.dictation ? ': ' + viewState.dictation : ''}
    </p>
  {/if}
  {#if notice}<p class="status" role="status">{notice}</p>{/if}
  {#if viewState && !viewState.ready}<button onclick={() => void action('reconnect')}
      >Reconnect terminal</button
    >{/if}
  <div
    class="terminal"
    bind:this={root}
    role="group"
    aria-label="Terminal output"
    oncontextmenu={(event) => {
      event.preventDefault();
      menu = true;
    }}
  ></div>
</div>

{#if image}
  <div class="modal-backdrop">
    <div class="modal wide image-dialog" role="dialog" aria-label="Image preview" aria-modal="true">
      <button
        class="image-close"
        aria-label="Close image preview"
        onclick={() => {
          image = null;
          terminal.focus();
        }}>×</button
      >
      <ImageViewer src={image.src} path={image.path} />
    </div>
  </div>
{/if}

{#if viewedDocument}
  <div class="modal-backdrop">
    <div class="modal wide" role="dialog" aria-label="File preview" aria-modal="true">
      <div class="modal-header">
        <h2>File preview</h2>
        <button
          aria-label="Close file preview"
          onclick={() => {
            viewedDocument = null;
            terminal.focus();
          }}>×</button
        >
      </div>
      <p><code>{viewedDocument.path}</code></p>
      {#if previewKind(viewedDocument.path) === 'html'}
        <HtmlPreview source={viewedDocument.text} />
      {:else if previewKind(viewedDocument.path) === 'markdown'}
        <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
        <article onclick={(event) => openPreviewLink(event, fail)}>
          {@html markdownPreview(viewedDocument.text)}
        </article>
      {:else}
        <SourceViewer
          path={viewedDocument.path}
          value={viewedDocument.text}
          readonly={true}
          onchange={() => {}}
        />
      {/if}
    </div>
  </div>
{/if}

<style>
  .ended-banner { padding: 8px; border-bottom: 1px solid #bd8882; }
  .ended-banner p { margin: 0 0 6px; }
  .ended-banner button { margin-right: 6px; }
  .scoped-search-navigation {
    position: fixed;
    z-index: 90;
    top: 50px;
    right: 12px;
    padding: 10px;
    width: min(520px, calc(100vw - 24px));
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 7px;
    box-shadow: 0 10px 30px #0005;
  }
  .navigation-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 5px;
    font-size: 12px;
  }
  .navigation-row input {
    flex: 1;
    min-width: 100px;
    width: 100px;
  }
  .navigation-row button {
    min-height: 30px;
    padding: 4px 7px;
  }
  .navigation-location {
    margin-top: 6px;
    color: var(--muted);
    font-size: 12px;
    overflow-wrap: anywhere;
  }

  .image-dialog {
    position: relative;
    padding: 0;
    overflow: hidden;
  }
  .image-close {
    position: absolute;
    top: 6px;
    right: 6px;
    z-index: 1;
    padding: 0;
    width: 24px;
    height: 24px;
    min-height: 0;
    border: 0;
    background: #16181c;
    color: #fff;
    font-size: 18px;
  }

  .detached-shell {
    display: flex;
    flex-direction: column;
    height: 100dvh;
    background: var(--bg);
    color: var(--text);
  }
  header button {
    color: inherit;
  }
  header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--line);
  }
  .title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
  }
  nav {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    padding: 6px;
  }
  .terminal {
    flex: 1;
    min-height: 0;
    padding: 6px;
  }
  .status {
    margin: 0;
    padding: 6px 10px;
    font-size: 12px;
  }
  button:focus-visible,
  input:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
</style>
