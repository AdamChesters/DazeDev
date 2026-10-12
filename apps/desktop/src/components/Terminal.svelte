<script lang="ts">
  import { restoreLinkHoverOnReentry } from '../lib/link-hover';
  import { ReadinessDeadline, ConnectionOperations } from '../lib/connection-health';
  import { uploadClipboardFiles, readClipboardContent } from '../lib/clipboard-upload';
  import { copySelectedTerminalText } from '../lib/terminal-copy';
  import { terminalNotification } from '../lib/terminal-alerts';
  import { onMount, tick } from 'svelte';
  import { onTerminalOutput } from '../lib/terminal-output';
  import { diagnostic } from '../lib/diagnostics';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import { WebglAddon } from '@xterm/addon-webgl';
  import { registerWebLinks } from '../lib/web-links';
  import { LiveBufferSearch, notifySearchUpdate } from '../lib/terminal-search';
  import { SearchAddon } from '@xterm/addon-search';
  import { ClipboardAddon } from '@xterm/addon-clipboard';
  import { Unicode11Addon } from '@xterm/addon-unicode11';
  import { SerializeAddon } from '@xterm/addon-serialize';
  import { ProgressAddon } from '@xterm/addon-progress';
  import { ImageAddon } from '@xterm/addon-image';
  import { LigaturesAddon } from '@xterm/addon-ligatures';
  import { imagePath, registerViewerLinks } from '../lib/images';
  import { openExternal } from '../lib/bridge';
  import {
    terminals,
    terminalDefaults,
    terminalSlot,
    webUrl,
    reviewPasteText,
    terminalInputChangesContext,
    type TerminalSettings,
  } from '../lib/terminals';
  import '@xterm/xterm/css/xterm.css';
  import { unknownStatus, type PaneStatus } from '../lib/pane-status';
  import { call, on, desktop, rpc } from '../lib/bridge';
  import type { Pane, Host } from '../lib/types';
  let {
    theme,
    pane,
    host,
    active,
    visible,
    connected,
    onfile,
    onsearch,
    suppressFocus = false,
    ondisconnect = () => {},
    onreconnect = async () => {},
    onfocus,
    onstate,
    onshell,
    settings = terminalDefaults,
    transcript = '',
    onprogress = () => {},
    onmetadata = () => {},
    onalert = () => {},
    floating = false,
    onoutput = () => {},
    onterminalfocus = () => false,
  }: {
    onfile?: (path: string) => void;
    onsearch?: () => void;
    suppressFocus?: boolean;
    floating?: boolean;
    onoutput?: (value: { sequence: number; generation: number; data: string }) => void;
    onterminalfocus?: () => boolean;
    theme: 'blue' | 'charcoal' | 'green';
    pane: Pane;
    host: Host | undefined;
    active: boolean;
    visible: boolean;
    connected: boolean;
    ondisconnect?: () => void;
    onreconnect?: () => Promise<void>;
    onfocus: () => void;
    onstate: (s: string) => void;
    onshell: () => void;
    settings?: TerminalSettings;
    transcript?: string;
    onalert?: (message: string) => void;
    onmetadata?: (value: PaneStatus) => void;
    onprogress?: (value: { state: number; value: number }) => void;
  } = $props();
  const accent = $derived(theme === 'blue' ? '#39bdd7' : theme === 'green' ? '#b9d5ad' : '#c4c9cf');
  $effect(() => {
    if (term)
      term.options.theme = {
        ...term.options.theme,
        cursor: accent,
        selectionBackground: accent + '44',
      };
  });
  let root: HTMLDivElement;
  let term: Terminal;
  let fit: FitAddon;
  let mounted = false;
  let opened = false;
  let detached = $state(false);
  let reconnecting = $state(false);
  let retainedOutput = $state('');
  let retainedView = $state<HTMLDivElement>();
  function outputText() {
    return Array.from(
      { length: term.buffer.active.length },
      (_, i) => term.buffer.active.getLine(i)?.translateToString(true) || '',
    )
      .join('\n')
      .trimEnd();
  }
  function selectedText() {
    if (!detached) return term.getSelection();
    const selection = window.getSelection();
    return retainedView && selection?.anchorNode && retainedView.contains(selection.anchorNode)
      ? selection.toString()
      : '';
  }
  async function reconnect() {
    if (reconnecting || closing) return;
    suspended = false;
    reconnecting = true;
    try {
      if (!connected) await onreconnect();
      await attach(true);
    } catch (error) {
      lost(String(error));
    }
  }
  let generation = 0;
  let contextEpoch = 0;
  let outputSequence = 0;
  let remoteReady = false;
  let readyMarker = '';
  let remotePid = 0;
  let dead = false;
  let suspended = false;
  let searcher: SearchAddon;
  let serializer: SerializeAddon;
  let gpu: WebglAddon | undefined;
  let ligatures: LigaturesAddon | undefined;
  let images: ImageAddon | undefined;
  let addonsReady = $state(false);
  let findOpen = $state(false),
    query = $state(''),
    regex = $state(false),
    matchCase = $state(false);
  let searchResult = $state('');
  let findInput = $state<HTMLInputElement>();
  let linkMenu = $state<{ url: string; x: number; y: number } | null>(null);
  let hoverLink = '';
  const macPlatform = /Mac|iPhone|iPad|iPod/i.test(navigator.platform);
  function followLink(event: MouseEvent, uri: string) {
    const modified = macPlatform ? event.metaKey : event.ctrlKey;
    if (modified) void openLink(uri);
    else if (imagePath(uri) && !term.hasSelection()) onfile?.(uri);
  }
  let fitFrame = 0,
    lastSize = '';
  let explicitPaste = false;
  let inputChain: Promise<void> = Promise.resolve();
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  const readiness = new ReadinessDeadline();
  const lifecycle = new ConnectionOperations();
  let closing = $state(false);
  let closePending: Promise<unknown> | undefined;
  let retries = 0;
  let attachedAt = 0;
  function lost(reason: string) {
    if (dead) return;
    notifySearchUpdate(pane.id, true);
    terminals.get(pane.id)?.releaseSearch?.();
    readiness.clear();
    diagnostic('terminal.disconnected', pane.id);
    if (opened && remoteReady && Date.now() - attachedAt > 30000) retries = 0;
    opened = false;
    remoteReady = false;
    contextEpoch++;
    if (!detached) {
      retainedOutput = outputText();
      void tick().then(() => {
        if (retainedView) retainedView.scrollTop = retainedView.scrollHeight;
      });
    }
    detached = true;
    reconnecting = false;
    onstate(reason);
    onmetadata(unknownStatus);
    ondisconnect();
    clearTimeout(reconnectTimer);
    if (!suspended && retries < 3)
      reconnectTimer = setTimeout(() => {
        if (!dead && !suspended && connected) {
          retries++;
          void attach();
        }
      }, 3000);
  }
  function send(data: string) {
    const g = generation;
    const job = inputChain
      .catch(() => {})
      .then(() => {
        if (!opened || g !== generation)
          throw new Error('Terminal disconnected; input was not replayed.');
        return call<void>('terminal_input', { id: pane.id, data });
      })
      .catch((e) => {
        if (g === generation) lost(String(e));
        throw e;
      });
    inputChain = job;
    return job;
  }
  let clipboardUploading = false;
  let clipboardNotice = $state('');
  let clipboardTimer: ReturnType<typeof setTimeout>;
  function reportClipboard(message: string, transient = true) {
    if (dead) return;
    clipboardNotice = message;
    clearTimeout(clipboardTimer);
    if (transient) clipboardTimer = setTimeout(() => (clipboardNotice = ''), 15000);
  }
  async function pasteFiles(files: File[]) {
    if (clipboardUploading) throw new Error('Finish the current clipboard upload first.');
    const expected = uploadDestination();
    const sourceHost = pane.hostId;
    if (!expected || !sourceHost || !connected)
      throw new Error('Connect this terminal before pasting a file.');
    clipboardUploading = true;
    try {
      const paths = await uploadClipboardFiles(
        files,
        (method, args) => rpc(sourceHost, method, args),
        () => uploadDestination() === expected,
        (message) => reportClipboard(message, false),
        host?.kind === 'powershell',
      );
      if (uploadDestination() !== expected)
        throw new Error(`Terminal changed during upload. Files remain at: ${paths}`);
      await paste(paths);
      reportClipboard(
        `Uploaded to ${host?.name || sourceHost}. Path pasted without Enter. Files stay in the host upload folder until removed.`,
      );
    } finally {
      clipboardUploading = false;
    }
  }
  async function pasteClipboard() {
    const expected = uploadDestination();
    const content = await readClipboardContent(navigator.clipboard);
    if (!expected || uploadDestination() !== expected)
      throw new Error('Terminal changed while reading the clipboard. Paste again.');
    if (content.files.length) await pasteFiles(content.files);
    else await paste(content.text);
  }
  function uploadDestination() {
    return !dead && opened && remoteReady && !detached
      ? JSON.stringify([generation, pane.hostId, pane.sessionId, pane.stamp || ''])
      : '';
  }
  function context() {
    return opened && remoteReady && !detached
      ? `${generation}:${contextEpoch}:${pane.hostId}:${pane.sessionId}:${pane.stamp || ''}`
      : '';
  }
  async function paste(text: string, enter = false, expectedContext?: string, review = false) {
    if (!opened || detached) throw new Error('Terminal is disconnected.');
    if (expectedContext !== undefined && (!expectedContext || context() !== expectedContext))
      throw new Error('Terminal destination changed. Dictation retained for review and copy.');
    // A line break may execute immediately in applications without bracketed paste.
    if (review) text = reviewPasteText(text, term.modes.bracketedPasteMode);
    if (!review && expectedContext !== undefined && /[\x00-\x1f\x7f]/.test(text))
      throw new Error(
        'Dictation contains line breaks or control characters. Review and copy it before pasting.',
      );
    explicitPaste = true;
    try {term.paste(text);} finally {explicitPaste = false;}
    const afterPaste = context();
    try {
      await inputChain;
      if (enter) {
        if (!afterPaste || context() !== afterPaste)
          throw new Error('Destination changed before Enter.');
        await send('\r');
      }
    } catch (e) {
      throw new Error(
        `Delivery uncertain; some text may already be in the terminal. Enter was not retried. ${e}`,
      );
    }
  }
  async function openLink(value: string) {
    const url = webUrl(value);
    if (!url) return;
    try {
      if (desktop) await openExternal(url);
      else window.open(url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      onstate(`Cannot open link: ${e}`);
    }
  }
  function find(previous = false) {
    if (!query) {
      searcher?.clearDecorations();
      searchResult = '';
      return;
    }
    const options = {
      regex,
      caseSensitive: matchCase,
      decorations: {
        matchBackground: '#405775',
        activeMatchBackground: '#087ec9',
        matchOverviewRuler: '#39bdd7',
        activeMatchColorOverviewRuler: '#ffffff',
      },
    };
    try {
      const found = previous
        ? searcher.findPrevious(query, options)
        : searcher.findNext(query, options);
      if (!found) searchResult = 'No matches';
    } catch {
      searchResult = 'Invalid expression';
    }
  }
  function showFind() {
    if (onsearch) {
      onsearch();
      return;
    }
    findOpen = true;
    requestAnimationFrame(() => findInput?.focus());
  }
  function exportOutput() {
    const output = detached ? retainedOutput : serializer.serialize({ excludeAltBuffer: false });
    const url = URL.createObjectURL(new Blob([output], { type: 'text/plain;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `${pane.name.replace(/[^a-z0-9_-]/gi, '_')}.ansi`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  $effect(() => {
    if (!addonsReady) return;
    term.options.screenReaderMode = settings.screenReader || false;
    term.options.fontSize = Math.max(8, Math.min(32, settings.fontSize));
    term.options.scrollback = Math.max(100, Math.min(100000, settings.scrollback));
    if (settings.gpu && visible && !gpu) {
      try {
        gpu = new WebglAddon();
        gpu.onContextLoss(() => {
          gpu?.dispose();
          gpu = undefined;
        });
        term.loadAddon(gpu);
      } catch {
        gpu?.dispose();
        gpu = undefined;
      }
    } else if ((!settings.gpu || !visible) && gpu) {
      gpu.dispose();
      gpu = undefined;
    }
    if (settings.images && !images) {
      images = new ImageAddon({ storageLimit: 32 });
      term.loadAddon(images);
    } else if (!settings.images && images) {
      images.dispose();
      images = undefined;
    }
    if (settings.ligatures && !ligatures) {
      ligatures = new LigaturesAddon();
      term.loadAddon(ligatures);
    } else if (!settings.ligatures && ligatures) {
      ligatures.dispose();
      ligatures = undefined;
    }
    fitted();
  });
  async function attach(manual = false) {
    if (closePending) await closePending;
    if (dead || suspended) return;
    if (!mounted || (!connected && !manual) || !host || (!pane.sessionId && !pane.shell) || opened)
      return;
    clearTimeout(reconnectTimer);
    if (manual) retries = 0;
    opened = true;
    attachedAt = Date.now();
    if (detached) reconnecting = true;
    const g = ++generation;
    remoteReady = false;
    readyMarker = crypto.randomUUID();
    onstate('Attaching');
    diagnostic('terminal.attach', pane.id);
    const releaseSlot = await terminalSlot();
    try {
      if (dead || g !== generation) return;
      readiness.arm(() => {
        if (dead || g !== generation || remoteReady) return;
        // Close only this unready PTY; a management failure never closes healthy panes.
        closing = true;
        closePending = lifecycle
          .run(pane.id, () => call('terminal_close', { id: pane.id }))
          .catch(() => {})
          .finally(() => {
            closing = false;
            closePending = undefined;
          });
        lost('Terminal connection timed out. Reconnect to try again.');
      });
      await lifecycle.run(pane.id, () =>
        call('terminal_open', {
          id: pane.id,
          host,
          session: pane.sessionId,
          stamp: pane.stamp || null,
          directory: pane.directory || null,
          readyMarker,
          generation: g,
        }),
      );
      if (g === generation && !dead) {
        if (remoteReady) onstate('Attached');
        lastSize = '';
        fitted();
      }
    } catch (e) {
      if (g === generation) lost(String(e));
    } finally {
      releaseSlot();
    }
  }
  function fitted() {
    if (!fit || !visible || floating || fitFrame) return;
    fitFrame = requestAnimationFrame(() => {
      fitFrame = 0;
      try {
        fit.fit();
        const size = `${term.cols}:${term.rows}`;
        if (opened && size !== lastSize) {
          lastSize = size;
          void call('terminal_resize', { id: pane.id, cols: term.cols, rows: term.rows }).catch(
            () => {
              lastSize = '';
            },
          );
        }
      } catch {}
    });
  }
  $effect(() => {
    connected;
    pane.sessionId;
    // Management and terminal PTYs are independent. Losing the management
    // channel must not disable input or replace a still-running shell.
    if (connected && !suspended) void attach();
  });
  $effect(() => {
    if (visible)
      requestAnimationFrame(() => {
        fitted();
        if (active && !suppressFocus) term?.focus();
      });
  });
  onMount(() => {
    let disposed = false;
    const disposers: (() => void)[] = [];
    let statusTimer: ReturnType<typeof setTimeout>;
    async function pollStatus() {
      const current = generation;
      try {
        if (opened && remoteReady && connected && visible) {
          const value = await rpc<PaneStatus>(pane.hostId, 'terminal_status', { pid: remotePid });
          if (!disposed && current === generation) onmetadata(value);
        } else if (!opened || !connected) onmetadata(unknownStatus);
      } catch {
        if (!disposed && current === generation) onmetadata(unknownStatus);
      } finally {
        if (!disposed) statusTimer = setTimeout(pollStatus, 2500);
      }
    }
    statusTimer = setTimeout(pollStatus, 500);
    term = new Terminal({
      fontFamily: '"Cascadia Code", "SFMono-Regular", Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.2,
      scrollback: 4000,
      convertEol: false,
      cursorBlink: true,
      macOptionClickForcesSelection: true,
      allowProposedApi: true,
      linkHandler: {
        activate: followLink,
        hover: (_e, uri) => {
          hoverLink = uri;
          root.title = `${macPlatform ? 'Cmd' : 'Ctrl'}+click to open in your browser`;
        },
        leave: () => {
          hoverLink = '';
          root.title = '';
        },
      },
      theme: {
        background: '#151616',
        foreground: '#edede8',
        cursor: accent,
        selectionBackground: accent + '44',
        black: '#151616',
        brightBlack: '#858b83',
        green: '#acd49e',
        blue: '#9cbce3',
        yellow: '#dcc28c',
        red: '#ed9c92',
      },
    });
    const shellMarker = term.parser.registerOscHandler(777, (data) => {
      if (data.startsWith(`dazedev-ready:${readyMarker}:`)) {
        if (!opened) return true;
        remotePid = Number(data.split(':').at(-1));
        remoteReady = Number.isSafeInteger(remotePid) && remotePid > 0;
        if (remoteReady) {
          attachedAt = Date.now();
          readiness.clear();
          onstate('Attached');
        }
        return true;
      }
      const notification = terminalNotification(777, data);
      if (notification) {
        onalert(notification);
        return true;
      }
      if (data !== 'dazedev-shell') return false;
      contextEpoch++;
      onshell();
      onstate('Shell');
      return true;
    });
    const notificationHandler = term.parser.registerOscHandler(9, (data) => {
      const message = terminalNotification(9, data);
      if (!message) return false;
      onalert(message);
      return true;
    });
    disposers.push(() => notificationHandler.dispose());
    let commandRunning = false;
    const shellStatus = term.parser.registerOscHandler(133, (data) => {
      const event = data.split(';')[0];
      if (event === 'C') commandRunning = true;
      if (event === 'D' && commandRunning) {
        commandRunning = false;
        onalert('Command finished');
      }
      return false;
    });
    disposers.push(() => shellStatus.dispose());
    fit = new FitAddon();
    term.loadAddon(fit);
    term.open(root);
    disposers.push(restoreLinkHoverOnReentry(term));
    let hoverFile = '';
    let linkPress: { url: string; x: number; y: number; modified: boolean } | null = null;
    const selectionEvents = new WeakSet<Event>();
    const linkDown = (event: MouseEvent) => {
      if (selectionEvents.has(event) || event.button !== 0) return;
      const modified = macPlatform ? event.metaKey : event.ctrlKey;
      if (
        !modified &&
        ((!hoverFile && !webUrl(hoverLink)) || term.modes.mouseTrackingMode === 'none')
      )
        return;
      event.preventDefault();
      event.stopImmediatePropagation();
      if (modified || imagePath(hoverFile || hoverLink)) {
        linkPress = {
          url: hoverFile || (webUrl(hoverLink) ? hoverLink : ''),
          x: event.clientX,
          y: event.clientY,
          modified,
        };
      }
      if (modified) {
        term.focus();
      } else {
        // Use xterm's selection override so link clicks never reach a remote TUI.
        const selection = new MouseEvent('mousedown', {
          bubbles: true,
          cancelable: true,
          view: window,
          clientX: event.clientX,
          clientY: event.clientY,
          button: event.button,
          buttons: event.buttons,
          detail: event.detail,
          shiftKey: !macPlatform,
          altKey: macPlatform,
        });
        selectionEvents.add(selection);
        event.target?.dispatchEvent(selection);
      }
    };
    const linkUp = (event: MouseEvent) => {
      if (!linkPress || event.button !== 0) return;
      const pressed = linkPress;
      linkPress = null;
      const target = hoverFile || (webUrl(hoverLink) ? hoverLink : '');
      if (
        root.contains(event.target as Node) &&
        target &&
        (!pressed.url || target === pressed.url) &&
        Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < 5 &&
        (macPlatform ? event.metaKey : event.ctrlKey) === pressed.modified
      ) {
        event.preventDefault();
        event.stopImmediatePropagation();
        if (imagePath(target) && (!pressed.modified || !webUrl(target))) onfile?.(target);
        else if (hoverFile) onfile?.(hoverFile);
        else void openLink(hoverLink);
      }
    };
    const cancelLink = () => {
      linkPress = null;
    };
    root.addEventListener('mousedown', linkDown, true);
    window.addEventListener('mouseup', linkUp, true);
    window.addEventListener('blur', cancelLink);
    disposers.push(() => {
      root.removeEventListener('mousedown', linkDown, true);
      window.removeEventListener('mouseup', linkUp, true);
      window.removeEventListener('blur', cancelLink);
    });
    if (onfile)
      registerViewerLinks(term, onfile, {
        hover: (path) => {
          hoverFile = path;
          root.title = path
            ? imagePath(path)
              ? 'Click to open in the viewer'
              : `${macPlatform ? 'Cmd' : 'Ctrl'}+click to open in the viewer`
            : '';
        },
      });
    searcher = new SearchAddon();
    term.loadAddon(searcher);
    serializer = new SerializeAddon();
    term.loadAddon(serializer);
    term.loadAddon(new Unicode11Addon());
    term.unicode.activeVersion = '11';
    const webLinks = registerWebLinks(term, followLink, (uri) => {
      hoverLink = uri;
      root.title = uri
        ? imagePath(uri)
          ? `Click to preview; ${macPlatform ? 'Cmd' : 'Ctrl'}+click to open in your browser`
          : `${macPlatform ? 'Cmd' : 'Ctrl'}+click to open in your browser`
        : '';
    });
    disposers.push(() => webLinks.dispose());
    term.loadAddon(
      new ClipboardAddon(undefined, {
        readText: async () =>
          settings.clipboard === 'read-write'
            ? navigator.clipboard.readText().then((text) => (text.length <= 1048576 ? text : ''))
            : '',
        writeText: async (_selection, text) => {
          if (settings.clipboard !== 'off' && text.length <= 1048576)
            await navigator.clipboard.writeText(text);
        },
      }),
    );
    const progress = new ProgressAddon();
    term.loadAddon(progress);
    const progressListener = progress.onChange(onprogress);
    const resultListener = searcher.onDidChangeResults((r) => {
      searchResult = r.resultCount ? `${r.resultIndex + 1} / ${r.resultCount}` : 'No matches';
    });
    addonsReady = true;
    term.attachCustomKeyEventHandler((e) => {
      if (
        copySelectedTerminalText(
          e,
          term.getSelection(),
          (text) => navigator.clipboard.writeText(text),
          reportClipboard,
        )
      )
        return false;
      if (e.type !== 'keydown' || !(e.ctrlKey || e.metaKey)) return true;
      if (e.shiftKey && e.code === 'KeyF') {
        e.preventDefault();
        showFind();
        return false;
      }
      if (!e.altKey && e.code === 'KeyV') {
        e.preventDefault();
        void pasteClipboard().catch((e) => reportClipboard(String(e)));
        return false;
      }
      return true;
    });
    const clipboardPaste = (event: ClipboardEvent) => {
      const files = Array.from(event.clipboardData?.files || []);
      if (!files.length) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      void pasteFiles(files).catch((e) => reportClipboard(String(e)));
    };
    root.addEventListener('paste', clipboardPaste, true);
    const input = term.onData((data) => {
      // The detached parser owns protocol replies while the primary view is inert.
      if (floating && !explicitPaste) return;
      if (terminalInputChangesContext(data)) contextEpoch++;
      if (opened) void send(data).catch((e) => onstate(String(e)));
    });
    const binary = term.onBinary((data) => {
      if (floating) return;
      if (opened)
        void call('terminal_binary', { id: pane.id, data: btoa(data) }).catch((e) =>
          lost(String(e)),
        );
    });
    const liveSearch = new LiveBufferSearch(term);
    disposers.push(() => liveSearch.release());
    const searchOutput = term.onWriteParsed(() => notifySearchUpdate(pane.id));
    disposers.push(() => searchOutput.dispose());
    terminals.set(pane.id, {
      focus: () => {
        if (!onterminalfocus()) term.focus();
      },
      input: (data) => {
        if (!opened || !remoteReady || detached)
          return Promise.reject(new Error('Terminal is disconnected.'));
        if (terminalInputChangesContext(data)) contextEpoch++;
        return send(data);
      },
      binary: (data) => {
        if (!opened || !remoteReady || detached)
          return Promise.reject(new Error('Terminal is disconnected.'));
        return call('terminal_binary', { id: pane.id, data });
      },
      resize: async (cols, rows) => {
        if (floating && cols > 0 && rows > 0 && cols <= 1000 && rows <= 1000) {
          term.resize(cols, rows);
          lastSize = `${cols}:${rows}`;
          if (opened) await call('terminal_resize', { id: pane.id, cols, rows });
        }
      },
      snapshot: () =>
        new Promise((resolve) =>
          term.write('', () =>
            resolve({
              sequence: outputSequence,
              generation,
              data: serializer.serialize(),
              cols: term.cols,
              rows: term.rows,
              context: context(),
              ready: !!context(),
            }),
          ),
        ),
      resume: () => {
        suspended = false;
        if (connected) void attach();
      },
      disconnect: async () => {
        suspended = true;
        clearTimeout(reconnectTimer);
        ++generation;
        lost('Disconnected by you. Reconnect when ready.');
        clearTimeout(reconnectTimer);
        await lifecycle.run(pane.id, () => call('terminal_close', { id: pane.id }));
      },
      paste,
      pasteClipboard,
      pasteBroadcast: (text, expectedContext) => paste(text, true, expectedContext, true),
      pasteDictation: (text, enter) => paste(text, enter, undefined, true),
      pasteForReview: (text, expectedContext) => paste(text, false, expectedContext, true),
      submitReviewed: async (expectedContext) => {
        await inputChain;
        if (!expectedContext || context() !== expectedContext)
          throw new Error(
            'The terminal changed after dictation. Review its text and press Enter there.',
          );
        contextEpoch++;
        await send('\r');
      },
      context,
      processId: () => remotePid,
      selection: selectedText,
      copy: () => navigator.clipboard.writeText(selectedText()),
      selectAll: () => {
        if (!detached) return term.selectAll();
        if (retainedView) {
          const range = document.createRange();
          range.selectNodeContents(retainedView);
          const selection = window.getSelection();
          selection?.removeAllRanges();
          selection?.addRange(range);
        }
      },
      search: showFind,
      findMatches: (options, limit) =>
        opened && remoteReady && !dead ? liveSearch.collectAsync(options, limit) : Promise.resolve([]),
      revealMatch: (id) => opened && remoteReady && liveSearch.reveal(id),
      releaseSearch: () => liveSearch.release(),
      searchSelection: () => liveSearch.selection(),
      exportOutput,
      clear: () => {
        if (!detached) {
          term.clear();
          liveSearch.release();
          notifySearchUpdate(pane.id, true);
        }
      },
      text: () => (detached ? retainedOutput : outputText()),
    });
    void Promise.all([
      onTerminalOutput(pane.id, (v) => {
        if (v.id === pane.id && v.generation === generation) {
          const bytes = Uint8Array.from(atob(v.data), (c) => c.charCodeAt(0));
          term.write(bytes, () => {
            if (!disposed && v.generation === generation && opened && remoteReady) {
              detached = false;
              reconnecting = false;
              retainedOutput = '';
            }
            if (!disposed)
              onoutput({ sequence: ++outputSequence, generation: v.generation, data: v.data });
            if (!disposed)
              void call('terminal_ack', { id: pane.id, bytes: bytes.length }).catch(() => {});
          });
        }
      }),
      on<{ id: string; generation: number }>('terminal-exit', (v) => {
        if (v.id === pane.id && v.generation === generation) {
          lost('Disconnected. Reconnecting…');
        }
      }),
    ]).then((unsubs) => {
      if (disposed) {
        unsubs.forEach((f) => f());
        return;
      }
      disposers.push(...unsubs);
      mounted = true;
      void attach();
    });
    const observer = new ResizeObserver(fitted);
    observer.observe(root);
    if (!desktop)
      term.writeln(
        '\x1b[38;2;167;170;165mDesktop preview · terminal connects in the installed app\x1b[0m',
      );
    return () => {
      disposed = true;
      root.removeEventListener('paste', clipboardPaste, true);
      readiness.clear();
      clearTimeout(statusTimer);
      clearTimeout(clipboardTimer);
      clearTimeout(reconnectTimer);
      dead = true;
      ++generation;
      mounted = false;
      observer.disconnect();
      cancelAnimationFrame(fitFrame);
      notifySearchUpdate(pane.id, true);
      terminals.delete(pane.id);
      binary.dispose();
      progressListener.dispose();
      resultListener.dispose();
      input.dispose();
      shellMarker.dispose();
      disposers.forEach((f) => f());
      term.dispose();
      void lifecycle.run(pane.id, () => call('terminal_close', { id: pane.id })).catch(() => {});
    };
  });
</script>

<div class="terminal-wrap" class:offline={detached}>
  {#if clipboardNotice}<div class="clipboard-status" role="status">{clipboardNotice}</div>{/if}
  {#if findOpen}<div class="terminal-search" role="search" aria-label="Search terminal">
      <input
        bind:this={findInput}
        bind:value={query}
        aria-label="Search terminal output"
        oninput={() => find()}
        onkeydown={(e) => {
          if (e.key === 'Enter') find(e.shiftKey);
          if (e.key === 'Escape') {
            findOpen = false;
            term.focus();
          }
        }}
      />
      <label
        ><input type="checkbox" bind:checked={matchCase} onchange={() => find()} />Match case</label
      >
      <label
        ><input type="checkbox" bind:checked={regex} onchange={() => find()} />Regular expression</label
      >
      <span aria-live="polite">{searchResult}</span>
      <button onclick={() => find(true)} aria-label="Previous match">↑</button><button
        onclick={() => find()}
        aria-label="Next match">↓</button
      >
      <button
        onclick={() => {
          findOpen = false;
          searcher.clearDecorations();
          term.focus();
        }}
        aria-label="Close search">×</button
      >
    </div>{/if}
  <div
    class="terminal"
    inert={detached}
    aria-hidden={detached}
    bind:this={root}
    onfocusin={onfocus}
    role="group"
    aria-label="Terminal output"
    oncontextmenu={(e) => {
      if (term?.hasSelection()) return;
      if (hoverLink && webUrl(hoverLink)) {
        e.preventDefault();
        e.stopPropagation();
        linkMenu = {
          url: hoverLink,
          x: Math.min(e.clientX, innerWidth - 200),
          y: Math.min(e.clientY, innerHeight - 100),
        };
      }
    }}
  ></div>
  {#if detached}
    <div
      class="retained-output"
      role="textbox"
      aria-readonly="true"
      aria-multiline="true"
      bind:this={retainedView}
      tabindex="0"
      aria-label="Last received terminal output, offline"
      style:font-size={`${settings.fontSize || 13}px`}
    >
      {retainedOutput}
    </div>
    <button
      class="disconnect-banner"
      disabled={reconnecting || closing}
      title={reconnecting
        ? 'Reconnecting. Showing last received output.'
        : 'Disconnected — offline. Reconnect to host. Showing last received output.'}
      aria-label={reconnecting ? 'Reconnecting' : 'Disconnected — offline. Reconnect to host'}
      onclick={() => void reconnect()}
    >
      <span role="status"
        >{reconnecting ? 'Reconnecting…' : 'Disconnected'}{#if !reconnecting}<span
            class="disconnect-state-label"
          >
            {' — offline'}</span
          ><span class="reconnect-label">. Reconnect to host.</span>{/if}</span
      >
    </button>
  {/if}
  {#if transcript}<div class="live-transcript" aria-live="off">
      <span>Transcribing</span>
      {transcript}
    </div>{/if}
</div>
{#if linkMenu}<button
    class="menu-dismiss"
    aria-label="Close link menu"
    onclick={() => (linkMenu = null)}
  ></button>
  <div class="terminal-link-menu" style={`left:${linkMenu.x}px;top:${linkMenu.y}px`}>
    <button
      onclick={() => {
        void openLink(linkMenu!.url);
        linkMenu = null;
      }}>Open link</button
    >
    <button
      onclick={() => {
        void navigator.clipboard.writeText(linkMenu!.url);
        linkMenu = null;
      }}>Copy link</button
    >
  </div>
{/if}

<style>
  .terminal-wrap {
    container-type: inline-size;
  }
  .offline .terminal {
    visibility: hidden;
  }
  .retained-output {
    position: absolute;
    inset: 0 0 27px;
    margin: 0;
    padding: 0;
    overflow: auto;
    white-space: pre;
    font-family: var(--terminal-font, 'Cascadia Code', 'Consolas', monospace);
    line-height: 1.2;
    color: #a3a3a3;
    background: #181818;
    user-select: text;
    cursor: text;
  }
  .retained-output:focus-visible {
    outline: 1px solid var(--accent);
    outline-offset: -1px;
  }
  .disconnect-banner {
    position: absolute;
    bottom: 0;
    left: 0;
    width: 100%;
    height: 27px;
    min-height: 27px;
    padding: 3px 8px;
    display: block;
    border: 1px solid #bd8882;
    border-radius: 3px;
    background: #b77e76;
    color: #231817;
    font-size: 12px;
    font-weight: 600;
    text-align: left;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    z-index: 3;
  }
  .disconnect-banner:disabled {
    opacity: 1;
    cursor: wait;
  }
  @container (max-width: 365px) {
    .reconnect-label {
      display: none;
    }
  }
  @container (max-width: 180px) {
    .disconnect-state-label {
      display: none;
    }
  }

  .clipboard-status {
    position: absolute;
    z-index: 5;
    right: 8px;
    bottom: 28px;
    left: 8px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel);
    color: inherit;
    font-size: 12px;
    pointer-events: none;
  }
  .terminal-wrap {
    position: relative;
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .terminal {
    flex: 1;
    min-height: 0;
  }
  .terminal-search {
    position: absolute;
    top: 0;
    right: 0;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px;
    background: var(--surface);
    max-width: 100%;
  }
  .terminal-search input:not([type='checkbox']) {
    width: 140px;
    min-width: 50px;
  }
  .terminal-search label {
    display: flex;
    align-items: center;
    gap: 2px;
  }
  .terminal-search button {
    padding: 4px 6px;
  }
  .live-transcript {
    position: absolute;
    bottom: 4px;
    left: 8px;
    right: 8px;
    max-height: 35%;
    overflow-y: auto;
    padding: 6px 10px;
    color: #d7f1ff;
    background: #122536ed;
    border-left: 2px solid #39bdd7;
    pointer-events: none;
    white-space: pre-wrap;
  }
  .live-transcript span {
    color: #39bdd7;
    font-size: 10px;
    margin-right: 5px;
  }
  .menu-dismiss {
    position: fixed;
    inset: 0;
    z-index: 89;
    background: transparent;
    border: 0;
  }
  .terminal-link-menu {
    position: fixed;
    z-index: 90;
    background: var(--surface);
    border: 1px solid var(--border);
    display: grid;
    padding: 4px;
  }
</style>
