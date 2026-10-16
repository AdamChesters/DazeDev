<script lang="ts">
  import { restoreLinkHoverOnReentry } from '../lib/link-hover';
  import { ReadinessDeadline } from '../lib/connection-health';
  import { uploadClipboardFiles } from '../lib/clipboard-upload';
  import { handleLinkPresses, terminalKeyHandler } from '../lib/terminal-input';
  import {
    PasteConfirmation,
    pasteEventHandler,
    pasteFromClipboard,
  } from '../lib/terminal-paste.svelte';
  import { commandFinishedAlert, terminalNotification } from '../lib/terminal-alerts';
  import {
    OptionalAddons,
    ResizeSender,
    applyTerminalSettings,
    clampScrollback,
    macPlatform as isMac,
    remoteClipboard,
    terminalOptions,
    terminalTheme,
  } from '../lib/terminal-setup';
  import PasteConfirm from './PasteConfirm.svelte';
  import { onMount, tick } from 'svelte';
  import { AckBatcher, outputChannel } from '../lib/terminal-bytes';
  import { terminalBudget, type BudgetMember } from '../lib/terminal-budget';
  import { diagnostic } from '../lib/diagnostics';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import { WebglAddon } from '@xterm/addon-webgl';
  import { registerWebLinks } from '../lib/web-links';
  import { LiveBufferSearch, notifySearchUpdate } from '../lib/terminal-search';
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
    paneLifecycle,
    checkCommandText,
    type PasteSource,
    type TerminalSettings,
  } from '../lib/terminals';
  import '@xterm/xterm/css/xterm.css';
  import { sameStatus, unknownStatus, type PaneStatus } from '../lib/pane-status';
  import { deadPaneTarget, recoverDeadPane } from '../lib/terminal-recovery';
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
    onterminalfocus = () => false,
    oncloseview = () => {},
    onclipboard = () => {},
    onpastesetting = () => {},
    watching = () => document.hasFocus(),
  }: {
    onclipboard?: (characters: number) => void;
    onpastesetting?: (confirm: boolean) => void;
    watching?: () => boolean;
    onfile?: (path: string) => void;
    onsearch?: () => void;
    suppressFocus?: boolean;
    floating?: boolean;
    onterminalfocus?: () => boolean;
    oncloseview?: () => void;
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
  $effect(() => {
    void theme;
    if (!addonsReady) return;
    // The workspace sets the document theme in its own effect; read the
    // colours once this round of effects has finished.
    queueMicrotask(() => {
      if (!dead) term.options.theme = terminalTheme();
    });
  });
  let root: HTMLDivElement;
  let term: Terminal;
  let fit: FitAddon;
  let mounted = false;
  let opened = false;
  let detached = $state(false);
  let reconnecting = $state(false);
  let observedStatus = $state<PaneStatus>(unknownStatus);
  let recoveryBusy = $state(false);
  let recoveryError = $state('');
  let recoveryPending: Promise<void> | undefined;
  let recoveryEpoch = 0;
  const endedTarget = $derived(deadPaneTarget(observedStatus));
  function metadata(value: PaneStatus) {
    const wasEnded = observedStatus.paneDead;
    // Polls return a new object each time; only report real changes.
    if (sameStatus(observedStatus, value)) return;
    observedStatus = value;
    onmetadata(value);
    if (value.paneDead && !recoveryBusy) onstate('Session ended');
    else if (wasEnded && !value.paneDead && value.attachment !== 'unknown') {
      recoveryError = '';
      onstate(pane.shell ? 'Shell' : 'Attached');
    }
  }
  function recover(): Promise<void> {
    if (recoveryPending) return recoveryPending;
    const target = observedStatus;
    const current = generation;
    recoveryBusy = true;
    recoveryError = '';
    recoveryEpoch++;
    onstate('Opening terminal…');
    recoveryPending = (async () => {
      try {
        await recoverDeadPane(target, (args) => rpc(pane.hostId, 'terminal_recover', args));
        if (dead || current !== generation) return;
        const value = await rpc<PaneStatus>(pane.hostId, 'terminal_status', { pid: remotePid });
        if (dead || current !== generation) return;
        if (value.paneDead) throw Error('The terminal is still ended. Try Open terminal again.');
        if (
          value.paneDead !== false ||
          value.sessionId !== target.sessionId ||
          value.paneId !== target.paneId
        )
          throw Error('Session changed. Wait for current terminal status before opening it.');
        metadata(value);
      } catch (error) {
        if (!dead && current === generation) {
          recoveryError = String(error);
          onstate('Could not open terminal. ' + recoveryError);
        }
        throw error;
      } finally {
        recoveryBusy = false;
        recoveryPending = undefined;
      }
    })();
    return recoveryPending;
  }
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
  // One acknowledgement per 64 KiB or 16 ms keeps the host's flow control
  // moving without an IPC call per chunk.
  const acks = new AckBatcher((bytes) => {
    void call('terminal_ack', { id: pane.id, bytes }).catch(() => {});
  });
  let remoteReady = false;
  let readyMarker = '';
  let remotePid = 0;
  let dead = false;
  let suspended = false;
  let serializer: SerializeAddon;
  let addons: OptionalAddons | undefined;
  let addonsReady = $state(false);
  let budget: BudgetMember | undefined;
  let scrollbackLimit = Infinity;
  // Hidden panes keep less history; the main view of a detached pane still
  // feeds that window's snapshots, so it keeps a full share.
  const budgetNeed = () => ({
    hidden: !visible && !floating,
    scrollback: clampScrollback(settings.scrollback),
  });
  let linkMenu = $state<{ url: string; x: number; y: number } | null>(null);
  let hoverLink = '';
  const macPlatform = isMac();
  function followLink(event: MouseEvent, uri: string) {
    const modified = macPlatform ? event.metaKey : event.ctrlKey;
    if (modified) void openLink(uri);
    else if (imagePath(uri) && !term.hasSelection()) onfile?.(uri);
  }
  let fitFrame = 0;
  let resizeNow = false;
  const resizer = new ResizeSender((cols, rows) =>
    opened ? call('terminal_resize', { id: pane.id, cols, rows }) : Promise.resolve(),
  );
  let explicitPaste = false;
  let inputChain: Promise<void> = Promise.resolve();
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  const readiness = new ReadinessDeadline();
  const lifecycle = paneLifecycle;
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
    metadata(unknownStatus);
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
  const pasteClipboard = () =>
    pasteFromClipboard(uploadDestination, { files: pasteFiles, text: pasteUserText });
  const confirmation = new PasteConfirmation(
    () => settings.confirmPaste,
    () => onpastesetting(false),
    () => term.focus(),
  );
  // Clipboard text typed by the user: multi-line text asks first.
  async function pasteUserText(text: string) {
    if (await confirmation.confirm(text)) await paste(text);
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
  // clipboard: normal paste rules; xterm adds bracketed paste when the app asks.
  // command: app-generated single-line text. review: dictation and prompts.
  async function paste(
    text: string,
    enter = false,
    expectedContext?: string,
    source: PasteSource | 'review' = 'clipboard',
  ) {
    if (!opened || detached) throw new Error('Terminal is disconnected.');
    if (expectedContext !== undefined && (!expectedContext || context() !== expectedContext))
      throw new Error(
        source === 'review'
          ? 'Terminal destination changed. Dictation retained for review and copy.'
          : 'The terminal changed. Nothing was pasted; try again.',
      );
    // A line break may execute immediately in applications without bracketed paste.
    if (source === 'review') text = reviewPasteText(text, term.modes.bracketedPasteMode);
    if (source === 'command') checkCommandText(text);
    explicitPaste = true;
    try {
      term.paste(text);
    } finally {
      explicitPaste = false;
    }
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
        { cause: e },
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
  function showFind() {
    onsearch?.();
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
    budget?.update(budgetNeed());
    applyTerminalSettings(term, settings, scrollbackLimit);
    // WebGL is created when the view is first shown and kept while hidden.
    addons?.apply(settings, visible);
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
      acks.reset();
      // Raw output for this attach only; frames from an older PTY never arrive here.
      const output = outputChannel(({ sequence, data }) => {
        if (dead || g !== generation) return;
        term.write(data, () => {
          if (dead || g !== generation) return;
          if (opened && remoteReady) {
            detached = false;
            reconnecting = false;
            retainedOutput = '';
          }
          outputSequence = Math.max(outputSequence, sequence);
          acks.add(data.length);
        });
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
          output,
        }),
      );
      if (g === generation && !dead) {
        if (remoteReady) onstate('Attached');
        resizer.reset();
        resizeNow = true;
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
        // Fit every frame; the PTY gets the final size once dragging pauses.
        if (opened) resizer.request(term.cols, term.rows, resizeNow);
        resizeNow = false;
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
      const epoch = recoveryEpoch;
      try {
        if (opened && remoteReady && connected && (visible || floating)) {
          const value = await rpc<PaneStatus>(pane.hostId, 'terminal_status', { pid: remotePid });
          if (
            !disposed &&
            current === generation &&
            epoch === recoveryEpoch &&
            !(observedStatus.paneDead && value.attachment === 'unknown')
          )
            metadata(value);
        } else if (!opened || !connected) metadata(unknownStatus);
      } catch {
        if (
          !disposed &&
          current === generation &&
          epoch === recoveryEpoch &&
          !recoveryBusy &&
          !observedStatus.paneDead
        )
          metadata(unknownStatus);
      } finally {
        if (!disposed) statusTimer = setTimeout(pollStatus, 2500);
      }
    }
    statusTimer = setTimeout(pollStatus, 500);
    term = new Terminal(
      terminalOptions(settings, {
        activate: followLink,
        hover: (_e, uri) => {
          hoverLink = uri;
          root.title = `${macPlatform ? 'Cmd' : 'Ctrl'}+click to open in your browser`;
        },
        leave: () => {
          hoverLink = '';
          root.title = '';
        },
      }),
    );
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
      if (data !== `dazedev-shell:${readyMarker}`) return false;
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
    let commandStarted = 0;
    const shellStatus = term.parser.registerOscHandler(133, (data) => {
      const event = data.split(';')[0];
      if (event === 'C') commandStarted = Date.now();
      if (event === 'D' && commandStarted) {
        const startedAt = commandStarted;
        commandStarted = 0;
        if (
          commandFinishedAlert({
            startedAt,
            now: Date.now(),
            watched: (visible || floating) && active && watching(),
          })
        )
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
    const linkTarget = () => hoverFile || (webUrl(hoverLink) ? hoverLink : '');
    disposers.push(
      handleLinkPresses({
        root,
        mac: macPlatform,
        modified: (event) => (macPlatform ? event.metaKey : event.ctrlKey),
        intercept: (modified) =>
          modified ||
          ((!!hoverFile || !!webUrl(hoverLink)) && term.modes.mouseTrackingMode !== 'none'),
        record: (modified) => (modified || imagePath(hoverFile || hoverLink) ? linkTarget() : null),
        accept: (url) => {
          const target = linkTarget();
          return !!target && (!url || target === url);
        },
        open: (event) => {
          const target = linkTarget();
          const modified = macPlatform ? event.metaKey : event.ctrlKey;
          if (imagePath(target) && (!modified || !webUrl(target))) onfile?.(target);
          else if (hoverFile) onfile?.(hoverFile);
          else void openLink(hoverLink);
        },
        focus: () => term.focus(),
      }),
    );
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
      new ClipboardAddon(
        undefined,
        remoteClipboard(() => settings.clipboard, onclipboard),
      ),
    );
    addons = new OptionalAddons((addon) => term.loadAddon(addon), {
      gpu: () => new WebglAddon(),
      images: (storageLimit) => new ImageAddon({ storageLimit }),
      ligatures: () => new LigaturesAddon(),
    });
    budget = terminalBudget.add(budgetNeed(), (share) => {
      scrollbackLimit = share.scrollback;
      applyTerminalSettings(term, settings, scrollbackLimit);
      if (addons) addons.imageLimit = share.imageMb;
    });
    disposers.push(() => budget?.remove());
    const progress = new ProgressAddon();
    term.loadAddon(progress);
    const progressListener = progress.onChange(onprogress);
    addonsReady = true;
    term.attachCustomKeyEventHandler(
      terminalKeyHandler({
        selection: () => term.getSelection(),
        copy: (text) => navigator.clipboard.writeText(text),
        report: reportClipboard,
        paste: () => void pasteClipboard().catch((e) => reportClipboard(String(e))),
      }),
    );
    const clipboardPaste = pasteEventHandler(
      () => settings.confirmPaste,
      { files: pasteFiles, text: pasteUserText },
      (e) => reportClipboard(String(e)),
    );
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
      recover,
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
          resizer.mark(cols, rows);
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
      paste: (text, enter, expectedContext, source) => paste(text, enter, expectedContext, source),
      pasteClipboard,
      pasteBroadcast: (text, expectedContext) => paste(text, true, expectedContext, 'review'),
      pasteDictation: (text, enter) => paste(text, enter, undefined, 'review'),
      pasteForReview: (text, expectedContext) => paste(text, false, expectedContext, 'review'),
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
        opened && remoteReady && !dead
          ? liveSearch.collectAsync(options, limit)
          : Promise.resolve([]),
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
      on<{ id: string; generation: number }>('terminal-exit', (v) => {
        if (v.id === pane.id && v.generation === generation) {
          lost('Disconnected. Reconnecting…');
        }
      }),
      // Typing in this pane's detached window goes straight to the PTY; the
      // desktop app reports it here first, so the dictation context moves on
      // exactly as it does for relayed input.
      on<{ paneId: string; data: string }>('terminal-window-input', (v) => {
        if (v.paneId === pane.id && terminalInputChangesContext(v.data)) contextEpoch++;
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
      acks.reset();
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
      resizer.dispose();
      confirmation.cancel();
      notifySearchUpdate(pane.id, true);
      terminals.delete(pane.id);
      binary.dispose();
      progressListener.dispose();
      input.dispose();
      shellMarker.dispose();
      disposers.forEach((f) => f());
      addons?.dispose();
      term.dispose();
      void lifecycle.run(pane.id, () => call('terminal_close', { id: pane.id })).catch(() => {});
    };
  });
</script>

<div class="terminal-wrap" class:offline={detached}>
  {#if clipboardNotice}<div class="clipboard-status" role="status">{clipboardNotice}</div>{/if}
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
  {#if endedTarget && !detached}<div class="ended-banner" aria-label="Session ended">
      <p role="status">Session ended. Open a new terminal here to continue.</p>
      {#if recoveryError}<p role="alert">{recoveryError}</p>{/if}
      <button disabled={recoveryBusy} onclick={() => void recover().catch(() => {})}
        >{recoveryBusy ? 'Opening terminal…' : 'Open terminal'}</button
      >
      <button disabled={recoveryBusy} onclick={oncloseview}>Close view</button>
    </div>{/if}
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
  {#if confirmation.request}<PasteConfirm
      text={confirmation.request.text}
      onpaste={(dontAsk) => confirmation.answer(true, dontAsk)}
      oncancel={() => confirmation.answer(false)}
    />{/if}
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
  .ended-banner {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    padding: 8px;
    background: var(--surface);
    color: var(--text);
    border-top: 1px solid #bd8882;
    z-index: 4;
  }
  .ended-banner p {
    margin: 0 0 6px;
  }
  .ended-banner button {
    margin-right: 6px;
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
