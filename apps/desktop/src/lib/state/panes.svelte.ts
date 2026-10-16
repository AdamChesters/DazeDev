import { untrack } from 'svelte';
import { call, desktop, rpc } from '../bridge';
import { retainedPaneColors } from '../colors';
import { paneContainers, pruneKeys, prunePaneChoices } from '../device-state';
import { balanced, dropZone, ids, insertAt, rectangles, remove, swap, type Edge } from '../layout';
import type { PaneStatus } from '../pane-status';
import {
  createNativeTerminalPane,
  insertPaneStack,
  removePanes,
  selectPanes,
} from '../pane-transfer';
import type { TerminalMenuItem } from '../terminal-menu';
import {
  cleanTabGroups,
  retainTabPanes,
  tabLayout,
  tabSelections,
  terminalTabs,
} from '../terminal-tabs';
import { terminals } from '../terminals';
import { blankProfile, uid, type Layout, type Pane, type Session, type Workspace } from '../types';
import { reconcileViewerLayout, terminalProjection } from '../viewer-layout';
import { HOSTLESS, workspaceHosts } from '../workspace-groups';
import type { App } from './app';

export type FileContext = Pick<Pane, 'id' | 'hostId' | 'repo'>;
export type Viewer = {
  id: string;
  workspaceId: string;
  pane: FileContext;
  initialPath?: string;
  locked: boolean;
  title: string;
  order: number;
};
type DropTarget = { id: string; edge: Edge | 'center' };

// Workspaces, panes and their layout: selection, focus, adding, moving,
// closing, file viewers and the terminal context menu.
export class Panes {
  viewers = $state<Viewer[]>([]);
  viewerLayouts = $state<Record<string, Layout>>({});
  stageWidth = $state(1200);
  draggingPane = $state('');
  selectedPanes = $state<string[]>([]);
  dropTarget = $state<DropTarget | null>(null);
  dragCopy = $state(false);
  dragHover = $state('');
  dragPosition = $state({ x: 0, y: 0 });
  hostlessConnect = $state<Workspace | null>(null);
  nativeCloseTargets = $state<{ hostId: string; ids: string[] }[]>([]);
  closingApp = $state(false);
  filesTarget = $state<FileContext | null>(null);
  filesLocked = $state(false);
  switchTarget = $state<Pane | null>(null);
  switchHost = $state('');
  cancelPaneDrag: (() => void) | null = null;
  #filesContext = '';
  viewerOrder = 0;
  #selectionWorkspace = '';
  #pendingClose: (() => void) | null = null;
  #nativeCloseDone: (() => void | Promise<void>) | null = null;
  #seenContainers = new Set<string>();
  #app: App;

  workspaces = $derived(Object.values(this.#ui.profiles).flatMap((p) => p.workspaces));
  workspaceLayouts = $derived(
    Object.fromEntries(
      this.workspaces.map((w) => {
        const detached = this.#app.detached.panes;
        return [
          w.id,
          tabLayout(
            reconcileViewerLayout(
              terminalProjection(this.viewerLayouts[w.id] || w.layout, [
                ...w.panes.filter((p) => !detached.has(p.id)).map((p) => p.id),
                ...this.viewers.filter((v) => v.workspaceId === w.id).map((v) => v.id),
              ]),
              terminalProjection(
                w.layout,
                w.panes.filter((p) => !detached.has(p.id)).map((p) => p.id),
              ) || { kind: 'pane' as const, id: '' },
              this.viewers.filter((v) => v.workspaceId === w.id).map((v) => v.id),
            ) || { kind: 'pane' as const, id: '' },
            cleanTabGroups(
              w.tabGroups,
              w.panes.map((p) => p.id),
            ),
            tabSelections(w, this.#ui.device.activeTerminalTabs),
          ),
        ];
      }),
    ),
  );
  workspaceBoxes = $derived(
    Object.fromEntries(this.workspaces.map((w) => [w.id, rectangles(this.layoutFor(w))])),
  );
  current = $derived(this.workspaces.find((w) => w.id === this.#ui.device.activeWorkspace));
  activePane = $derived(this.current?.panes.find((p) => p.id === this.current?.focused));
  viewerLocked = $derived(this.viewers.some((v) => v.locked));
  hostlessNeeds = $derived.by(() =>
    this.hostlessConnect
      ? workspaceHosts(this.hostlessConnect, this.#ui.device.hosts, this.#app.net.connections)
      : null,
  );

  constructor(app: App) {
    this.#app = app;
    // Per-pane state goes when its pane goes, so the device file does not grow.
    $effect(() => {
      const ui = this.#ui;
      const device = ui.device;
      const { byWorkspace, byGroup } = paneContainers(this.workspaces);
      const live = new Set(this.workspaces.flatMap((w) => w.panes.map((p) => p.id)));
      const hostIds = new Set([HOSTLESS, ...device.hosts.map((h) => h.id)]);
      const complete = device.hosts.every((h) => !!ui.profiles[h.id]);
      untrack(() => {
        for (const id of [...byWorkspace.keys(), ...byGroup.keys()]) this.#seenContainers.add(id);
        const status = pruneKeys(ui.paneStatus, live);
        if (status !== ui.paneStatus) ui.paneStatus = status as Record<string, PaneStatus>;
        const states = pruneKeys(ui.terminalState, live);
        if (states !== ui.terminalState) ui.terminalState = states;
        const progress = pruneKeys(ui.terminalProgress, live);
        if (progress !== ui.terminalProgress) ui.terminalProgress = progress;
        this.onprune(live);
        const focus = prunePaneChoices(
          device.localFocus || {},
          byWorkspace,
          this.#seenContainers,
          complete,
        );
        if (device.localFocus && focus !== device.localFocus) device.localFocus = focus;
        const tabs = prunePaneChoices(
          device.activeTerminalTabs || {},
          byGroup,
          this.#seenContainers,
          complete,
        );
        if (device.activeTerminalTabs && tabs !== device.activeTerminalTabs)
          device.activeTerminalTabs = tabs;
        for (const key of ['clipboardByHost', 'collapsedHosts'] as const) {
          const record = device[key] as Record<string, unknown> | undefined;
          if (!record) continue;
          const pruned = pruneKeys(record, hostIds);
          if (pruned !== record) (device as unknown as Record<string, unknown>)[key] = pruned;
        }
      });
    });
    $effect(() => {
      if (this.#ui.modal !== 'close-native') this.closingApp = false;
    });
  }

  get #ui() {
    return this.#app.ui;
  }

  /** Called with the live pane ids whenever panes change. */
  onprune: (live: Set<string>) => void = () => {};

  layoutFor = (w: Workspace): Layout => this.workspaceLayouts[w.id] || w.layout;

  owner(paneId: string | undefined) {
    return this.workspaces.find((w) => w.panes.some((p) => p.id === paneId));
  }

  // Saves only when the remembered focus really changes.
  setLocalFocus = (workspaceId: string, paneId: string) => {
    const device = this.#ui.device;
    if (device.localFocus?.[workspaceId] === paneId) return;
    (device.localFocus ||= {})[workspaceId] = paneId;
  };

  selectPane = (paneId: string, workspace: Workspace, additive = false) => {
    const device = this.#ui.device;
    const group = terminalTabs(workspace, paneId);
    if (group && device.activeTerminalTabs?.[group.id] !== paneId) {
      (device.activeTerminalTabs ||= {})[group.id] = paneId;
    }
    if (this.#selectionWorkspace !== workspace.id) this.selectedPanes = [];
    this.#selectionWorkspace = workspace.id;
    this.selectedPanes = selectPanes(this.selectedPanes, paneId, additive);
    workspace.focused = paneId;
    this.setLocalFocus(workspace.id, paneId);
  };

  updateWorkspace = (w: Workspace) => {
    const p = this.#ui.profiles[w.hostId];
    w = {
      ...w,
      tabGroups: cleanTabGroups(
        w.tabGroups,
        w.panes.map((p) => p.id),
      ),
    };
    const previous = p.workspaces.find((x) => x.id === w.id);
    if (w.layout !== previous?.layout) this.viewerLayouts[w.id] = w.layout;
    let savedLayout = retainTabPanes(w.layout, w.tabGroups);
    for (const pane of w.panes)
      if (this.#app.detached.panes.has(pane.id) && !ids(savedLayout).includes(pane.id))
        savedLayout = ids(savedLayout).filter(Boolean).length
          ? insertAt(savedLayout, ids(savedLayout).filter(Boolean)[0], pane.id, 'right')
          : { kind: 'pane', id: pane.id };
    w = {
      ...w,
      layout: terminalProjection(
        savedLayout,
        w.panes.map((p) => p.id),
      ) || { kind: 'pane' as const, id: '' },
    };
    p.workspaces = p.workspaces.map((x) => (x.id === w.id ? w : x));
  };

  selectWorkspace = (w: Workspace) => {
    const ui = this.#ui;
    const device = ui.device;
    const dictation = this.#app.dictation;
    if (dictation.active && !w.panes.some((p) => p.id === dictation.target)) {
      ui.notice = 'Stop dictation before switching workspaces.';
      return;
    }
    this.selectedPanes = [];
    this.#selectionWorkspace = w.id;
    device.collapsedHosts = { ...device.collapsedHosts, [w.hostId]: false };
    device.activeWorkspace = w.id;
    device.activeHost = w.hostId;
    ui.maximize = '';
    if (desktop) void call('terminal_workspace_activate', { workspaceId: w.id }).catch(ui.fail);
    if (w.hostId === HOSTLESS) {
      const needs = workspaceHosts(w, device.hosts, this.#app.net.connections);
      if (needs.panes.some((pane, index) => pane.hostId !== w.panes[index].hostId))
        this.updateWorkspace({ ...w, panes: needs.panes });
      if (needs.required.length || needs.missing.length) this.hostlessConnect = w;
    }
  };

  newHostlessWorkspace = () => {
    const ui = this.#ui;
    const profile = (ui.profiles[HOSTLESS] ||= blankProfile());
    const workspace: Workspace = {
      id: uid(),
      name: 'Hostless workspace',
      hostId: HOSTLESS,
      panes: [],
      layout: { kind: 'pane', id: '' },
      focused: '',
    };
    profile.workspaces.push(workspace);
    this.selectWorkspace(workspace);
    return workspace;
  };

  addTerminal = (edge: Edge, target = this.activePane, asTab = false) => {
    const ui = this.#ui;
    if (!this.current) return;
    let w = this.current;
    if (w.hostId === HOSTLESS) {
      ui.notice =
        'Open a terminal in a host workspace, then hold Ctrl and drag its view into this group.';
      return;
    }
    target ||= {
      id: ids(this.layoutFor(w)).find(Boolean) || '',
      hostId: w.hostId,
      hostKey: ui.host(w.hostId)?.fingerprint,
    } as Pane;
    let pane: Pane = {
      id: uid(),
      hostId: target.hostId,
      hostKey: target.hostKey,
      sessionId: '',
      shell: true,
      name: `Terminal ${w.panes.length + 1}`,
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      operation: uid(),
    };
    const insert = () => {
      const owner = ui.profiles[w.hostId]?.workspaces.find((item) => item.id === w.id);
      if (!owner) return false;
      w = owner;
      ui.maximize = '';
      ui.paneMenu = null;
      const layout = this.layoutFor(w);
      this.updateWorkspace({
        ...w,
        panes: [...w.panes, pane],
        layout: ids(layout).filter(Boolean).length
          ? insertAt(
              layout,
              ids(layout).includes(target.id) ? target.id : ids(layout)[0],
              pane.id,
              edge,
            )
          : { kind: 'pane', id: pane.id },
        focused: pane.id,
        tabGroups: asTab
          ? [
              ...(w.tabGroups || []).filter((group) => !group.panes.includes(target!.id)),
              {
                id: terminalTabs(w, target.id)?.id || uid(),
                panes: [...(terminalTabs(w, target.id)?.panes || [target.id]), pane.id],
              },
            ]
          : w.tabGroups,
      });
      const updated = ui.profiles[w.hostId].workspaces.find((item) => item.id === w.id)!;
      this.selectPane(pane.id, updated);
      return true;
    };
    if (ui.nativePane(pane)) {
      void ui.task(async () => {
        pane = await createNativeTerminalPane(pane, (args) =>
          rpc<Session>(pane.hostId, 'create', args),
        );
        if (!insert()) await rpc(pane.hostId, 'destroy', { id: pane.sessionId, stamp: pane.stamp });
      });
    } else insert();
  };

  async #preparePaneDrop(source: Workspace, destination: Workspace, original: Pane, copy: boolean) {
    const ui = this.#ui;
    let pane = original;
    if (destination.hostId === HOSTLESS && !copy)
      throw new Error(
        'Hold Ctrl while dragging to add a view to this hostless workspace. The original stays in place.',
      );
    if (source.id !== destination.id) {
      const header = document.querySelector<HTMLElement>(
        `[data-pane-id="${CSS.escape(original.id)}"] .pane-header`,
      );
      const style = header ? getComputedStyle(header) : null;
      const toHex = (value: string | undefined, fallback: string) => {
        const rgb = value?.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        return rgb
          ? '#' +
              rgb
                .slice(1, 4)
                .map((v) => Number(v).toString(16).padStart(2, '0'))
                .join('')
          : fallback;
      };
      pane = {
        ...original,
        colors: retainedPaneColors(
          original.colors,
          source.colors,
          ui.host(original.hostId)?.colors,
          {
            background: toHex(style?.backgroundColor, '#242822'),
            foreground: toHex(style?.color, '#edede8'),
          },
        ),
      };
    }
    if (copy) {
      const terminal = terminals.get(original.id);
      const context = terminal?.context();
      const status = desktop
        ? await rpc<PaneStatus>(original.hostId, 'terminal_status', {
            pid: terminal?.processId() || 0,
          })
        : {
            attachment: original.shell ? 'plain' : 'tmux',
            sessionId: original.sessionId,
            stamp: original.stamp,
            nestedSsh: false,
            directory: undefined,
          };
      if (desktop && (!context || terminal?.context() !== context))
        throw new Error('The source terminal changed. Try duplicating it again.');
      if (status.attachment === 'unknown' || status.nestedSsh)
        throw new Error(
          'Cannot identify this terminal’s session safely. Return to its managed host shell and try again.',
        );
      const attached = status.attachment === 'tmux' || status.attachment === 'native';
      if (destination.hostId === HOSTLESS && !attached)
        throw new Error(
          'Hostless workspaces need a persistent session. Open or create a managed terminal on its host first.',
        );
      if (destination.hostId === HOSTLESS && (!status.stamp || !original.hostKey))
        throw new Error(
          'Reconnect the source host before cloning this view so its host and session identities can be verified.',
        );
      if (attached && !status.sessionId)
        throw new Error(
          'The host helper needs reconnecting before this session can be duplicated.',
        );
      pane = {
        ...pane,
        id: uid(),
        operation: uid(),
        error: undefined,
        sessionId: attached ? status.sessionId! : '',
        stamp: attached ? status.stamp : undefined,
        shell: !attached,
        directory: attached ? undefined : status.directory,
        agent: attached ? original.agent : 'shell',
        model: attached ? original.model : '',
        effort: attached ? original.effort : '',
        name: attached ? original.name : 'Terminal',
      };
    }
    return pane;
  }

  async #commitPaneDrop(
    sourceId: string,
    destinationId: string,
    paneIds: string | string[],
    target: DropTarget,
    copy: boolean,
    sidebar = false,
  ) {
    const ui = this.#ui;
    const workspaces = () => this.workspaces;
    const source = workspaces().find((w) => w.id === sourceId);
    const destination = workspaces().find((w) => w.id === destinationId);
    const wanted = new Set(typeof paneIds === 'string' ? [paneIds] : paneIds);
    const originals = source?.panes.filter((p) => wanted.has(p.id));
    if (!source || !destination || !originals?.length || originals.length !== wanted.size) return;
    if (sidebar && !copy && sourceId === destinationId) return;
    const prepared = await Promise.all(
      originals.map((pane) => this.#preparePaneDrop(source, destination, pane, copy)),
    );
    const from = workspaces().find((w) => w.id === sourceId);
    const to = workspaces().find((w) => w.id === destinationId);
    if (
      !from ||
      !to ||
      originals.some((p) => from.panes.find((x) => x.id === p.id) !== p) ||
      (target.id && !ids(this.layoutFor(to)).includes(target.id))
    )
      return;
    if (!copy && from.id === to.id && wanted.has(target.id)) return;
    const moving = originals.map((p) => p.id);
    const remaining =
      !copy && from.id === to.id ? removePanes(this.layoutFor(to), moving) : this.layoutFor(to);
    const incoming = prepared.map((p) => p.id);
    let layout: Layout;
    if (sidebar || !ids(remaining).some(Boolean)) layout = insertPaneStack(remaining, incoming);
    else if (target.edge === 'center') {
      const order = ids(remaining);
      order.splice(Math.max(0, order.indexOf(target.id)), 0, ...incoming);
      layout = balanced(order, this.stageWidth);
    } else layout = insertPaneStack(remaining, incoming, target.id, target.edge);
    if (!copy && from.id !== to.id) {
      const panes = from.panes.filter((p) => !wanted.has(p.id));
      this.updateWorkspace({
        ...from,
        panes,
        layout: removePanes(this.layoutFor(from), moving),
        focused: wanted.has(from.focused) ? panes[0]?.id || '' : from.focused,
      });
    }
    this.updateWorkspace({
      ...to,
      panes: [...to.panes.filter((p) => copy || !wanted.has(p.id)), ...prepared],
      layout,
      focused: incoming[0],
    });
    this.selectedPanes = [];
    if (sidebar) {
      ui.notice = `${prepared.length === 1 ? prepared[0].name : prepared.length + ' terminals'} ${copy ? 'duplicated in' : 'moved to'} ${to.name}.`;
      if (ui.maximize && wanted.has(ui.maximize) && !copy) ui.maximize = '';
    } else this.selectWorkspace(to);
  }

  #commitViewerDrop(id: string, destinationId: string, target: DropTarget, sidebar = false) {
    const viewer = this.viewers.find((v) => v.id === id);
    const from = this.workspaces.find((w) => w.id === viewer?.workspaceId);
    const to = this.workspaces.find((w) => w.id === destinationId);
    if (
      !viewer ||
      !from ||
      !to ||
      target.id === id ||
      (sidebar && from.id === to.id) ||
      (target.id && !ids(this.layoutFor(to)).includes(target.id))
    )
      return;
    const sourceLayout = remove(this.layoutFor(from), id) || { kind: 'pane' as const, id: '' };
    const remaining = from.id === to.id ? sourceLayout : this.layoutFor(to);
    let layout: Layout;
    if (sidebar) layout = insertPaneStack(remaining, [id]);
    else if (!ids(remaining).filter(Boolean).length) layout = { kind: 'pane' as const, id };
    else if (target.edge === 'center') {
      const order = ids(remaining);
      order.splice(Math.max(0, order.indexOf(target.id)), 0, id);
      layout = balanced(order, this.stageWidth);
    } else layout = insertAt(remaining, target.id, id, target.edge);
    viewer.workspaceId = to.id;
    if (from.id !== to.id) this.updateWorkspace({ ...from, layout: sourceLayout });
    this.updateWorkspace({ ...to, layout });
    this.#ui.maximize = '';
    if (!sidebar) this.selectWorkspace(to);
  }

  closeViewer = (id: string) => {
    const viewer = this.viewers.find((v) => v.id === id);
    if (!viewer || viewer.locked) return;
    const owner = this.workspaces.find((w) => w.id === viewer.workspaceId);
    const layout = owner && remove(this.layoutFor(owner), id);
    this.viewers = this.viewers.filter((v) => v.id !== id);
    if (owner)
      this.updateWorkspace({ ...owner, layout: layout || { kind: 'pane' as const, id: '' } });
    if (this.#ui.maximize === id) this.#ui.maximize = '';
  };

  menuPaneDrop = (pane: Pane, destination: Workspace, copy: boolean) => {
    const source = this.owner(pane.id);
    const target =
      destination.panes.find((p) => p.id === destination.focused && (copy || p.id !== pane.id)) ||
      destination.panes.find((p) => copy || p.id !== pane.id);
    this.#ui.paneMenu = null;
    if (!source || (!copy && source.id === destination.id && !target)) return;
    void this.#ui.task(() =>
      this.#commitPaneDrop(
        source.id,
        destination.id,
        pane.id,
        { id: target?.id || '', edge: 'right' },
        copy,
      ),
    );
  };

  startPaneDrag = (e: PointerEvent, p: Pick<Pane, 'id'>, workspace: Workspace, viewer = false) => {
    const ui = this.#ui;
    const device = ui.device;
    if (
      e.button !== 0 ||
      (e.target as HTMLElement).closest('button, .pane-actions') ||
      this.#app.dictation.active ||
      ui.modal
    )
      return;
    this.cancelPaneDrag?.();
    if (!viewer) {
      if (e.shiftKey) this.selectPane(p.id, workspace, true);
      else if (this.#selectionWorkspace !== workspace.id || !this.selectedPanes.includes(p.id))
        this.selectPane(p.id, workspace);
    }
    const group = viewer
      ? [p.id]
      : workspace.panes
          .filter((pane) => this.selectedPanes.includes(pane.id))
          .map((pane) => pane.id);
    const header = e.currentTarget as HTMLElement;
    const origin = device.activeWorkspace,
      originHost = device.activeHost,
      originMax = ui.maximize;
    const startX = e.clientX,
      startY = e.clientY;
    let moved = false;
    let destination = '',
      last = e;
    let sidebar = false;
    header.setPointerCapture(e.pointerId);
    const locate = (event: PointerEvent) => {
      this.dragCopy = !viewer && event.ctrlKey;
      this.dragPosition = { x: event.clientX + 14, y: event.clientY + 14 };
      const hit = document.elementFromPoint(event.clientX, event.clientY);
      const tab = hit?.closest<HTMLElement>('[data-workspace-tab]')?.dataset.workspaceTab || '';
      this.dragHover = tab;
      sidebar = false;
      if (tab) {
        this.dropTarget = null;
        destination = '';
        if (this.workspaces.some((w) => w.id === tab) && (this.dragCopy || tab !== workspace.id)) {
          destination = tab;
          this.dropTarget = { id: '', edge: 'right' };
          sidebar = true;
        }
        return;
      }
      const element = hit?.closest<HTMLElement>('[data-pane-id]');
      const w =
        element &&
        this.workspaces.find(
          (w) =>
            w.id === device.activeWorkspace &&
            ids(this.layoutFor(w)).includes(element.dataset.paneId || ''),
        );
      this.dropTarget = null;
      destination = '';
      // Headers and navigation are never docking targets.
      if (
        !w ||
        !element ||
        hit?.closest('.pane-header') ||
        (!this.dragCopy && group.includes(element.dataset.paneId || ''))
      ) {
        const empty = hit?.closest<HTMLElement>('[data-empty-workspace]');
        if (empty) {
          destination = empty.dataset.emptyWorkspace!;
          this.dropTarget = { id: '', edge: 'center' };
        }
        return;
      }
      const r = element.getBoundingClientRect();
      destination = w.id;
      this.dropTarget = {
        id: element.dataset.paneId!,
        edge: dropZone((event.clientX - r.left) / r.width, (event.clientY - r.top) / r.height),
      };
    };
    const move = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      last = event;
      if (!moved && Math.hypot(event.clientX - startX, event.clientY - startY) < 6) return;
      if (!group.length) return;
      moved = true;
      this.draggingPane = p.id;
      ui.paneMenu = null;
      if (
        !viewer &&
        group.length === 1 &&
        !event.ctrlKey &&
        desktop &&
        (event.clientX < 0 ||
          event.clientX > innerWidth ||
          event.clientY < 0 ||
          event.clientY > innerHeight)
      ) {
        cleanup();
        restore();
        const pane = workspace.panes.find((pane) => pane.id === group[0]);
        if (pane)
          void this.#app.detached
            .detach(pane, event.screenX - 120, event.screenY - 24, true)
            .catch(ui.fail);
        return;
      }
      locate(event);
    };
    const cleanup = () => {
      this.draggingPane = '';
      this.dropTarget = null;
      this.dragHover = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', abort);
      window.removeEventListener('keydown', key, true);
      window.removeEventListener('keyup', key, true);
      window.removeEventListener('blur', abort);
      header.removeEventListener('lostpointercapture', abort);
      if (header.hasPointerCapture(e.pointerId)) header.releasePointerCapture(e.pointerId);
      this.cancelPaneDrag = null;
    };
    const restore = () => {
      device.activeWorkspace = origin;
      device.activeHost = originHost;
      ui.maximize = originMax;
    };
    const abort = () => {
      cleanup();
      restore();
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        abort();
      } else if (event.key === 'Control' && moved)
        locate(
          new PointerEvent('pointermove', {
            clientX: last.clientX,
            clientY: last.clientY,
            ctrlKey: event.type === 'keydown',
          }),
        );
    };
    const finish = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      if (moved) locate(event);
      const target = this.dropTarget,
        to = destination,
        copy = this.dragCopy;
      cleanup();
      if (!moved || !target || !to) {
        if (!moved && !viewer && !e.shiftKey) this.selectPane(p.id, workspace);
        restore();
        return;
      }
      const direct = sidebar;
      void ui.task(async () => {
        try {
          if (viewer) this.#commitViewerDrop(p.id, to, target, direct);
          else await this.#commitPaneDrop(workspace.id, to, group, target, copy, direct);
        } catch (error) {
          restore();
          throw error;
        }
      });
    };
    this.cancelPaneDrag = abort;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', abort);
    window.addEventListener('keydown', key, true);
    window.addEventListener('keyup', key, true);
    window.addEventListener('blur', abort);
    header.addEventListener('lostpointercapture', abort);
  };

  openFiles = (
    p: FileContext,
    mode: 'upload' | 'documents',
    workspaceId?: string,
    initialPath = '',
  ) => {
    const ui = this.#ui;
    if (mode === 'documents') {
      const owner =
        this.workspaces.find((w) => w.id === workspaceId) || this.owner(p.id) || this.current;
      if (!owner) {
        this.filesTarget = p;
        this.#filesContext = '';
        this.filesLocked = false;
        ui.modal = 'documents';
        ui.paneMenu = null;
        return;
      }
      const id = uid();
      const prior = this.layoutFor(owner);
      this.viewerLayouts[owner.id] = ids(prior).filter(Boolean).length
        ? insertAt(prior, ids(prior).includes(p.id) ? p.id : ids(prior).at(-1)!, id, 'right')
        : { kind: 'pane' as const, id };
      this.viewers.push({
        id,
        workspaceId: owner.id,
        pane: { ...p },
        locked: false,
        title: 'Files and previews',
        initialPath,
        order: ++this.viewerOrder,
      });
      ui.maximize = '';
      ui.paneMenu = null;
      return;
    }
    ui.terminalReturnFocus = p.id;
    this.filesTarget = p;
    this.#filesContext = terminals.get(p.id)?.context() || '';
    this.filesLocked = false;
    ui.modal = mode;
    ui.paneMenu = null;
  };

  pasteFilePath = async (text: string) => {
    const terminal = this.filesTarget && terminals.get(this.filesTarget.id);
    if (!terminal || !this.#filesContext || terminal.context() !== this.#filesContext)
      throw new Error('Terminal changed. Copy the path or reopen Upload for the current terminal.');
    await terminal.pasteForReview(text, this.#filesContext);
    this.#filesContext = terminal.context();
  };

  toggleStatus = (p: Pane) => {
    p.hideStatus = !p.hideStatus;
    this.#ui.paneMenu = null;
  };

  choosePaneHost = (p: Pane) => {
    this.switchTarget = p;
    this.switchHost = p.hostId;
    this.#ui.modal = 'pane-host';
    this.#ui.paneMenu = null;
  };

  switchPaneHost = async () => {
    const ui = this.#ui;
    const target = this.switchTarget;
    const owner = this.owner(target?.id);
    if (!target || !owner) throw new Error('Terminal no longer exists.');
    if (this.#app.dictation.status !== 'idle')
      throw new Error('Finish dictation before changing this connection.');
    if (this.#app.net.connections[this.switchHost] !== 'Connected')
      throw new Error('Connect the selected host first.');
    if (this.switchHost === target.hostId) {
      ui.modal = '';
      return;
    }
    const replacement: Pane = {
      ...target,
      id: uid(),
      hostId: this.switchHost,
      hostKey: ui.host(this.switchHost)?.fingerprint,
      sessionId: '',
      stamp: undefined,
      shell: true,
      name: 'Terminal',
      repo: '',
      agent: 'shell',
      identity: '',
      model: '',
      effort: '',
      operation: uid(),
      error: undefined,
    };
    await call('terminal_close', { id: target.id });
    this.updateWorkspace({
      ...owner,
      panes: owner.panes.map((p) => (p.id === target.id ? replacement : p)),
      layout: swap(this.layoutFor(owner), target.id, replacement.id),
      focused: replacement.id,
    });
    if (ui.maximize === target.id) ui.maximize = replacement.id;
    ui.modal = '';
  };

  offerNativeClose = (panes: Pane[], done: () => void | Promise<void>) => {
    const groups = new Map<string, Set<string>>();
    for (const p of panes)
      if (this.#ui.nativePane(p) && p.sessionId) {
        if (!groups.has(p.hostId)) groups.set(p.hostId, new Set());
        groups.get(p.hostId)!.add(p.sessionId);
      }
    if (!groups.size) return false;
    this.nativeCloseTargets = [...groups].map(([hostId, ids]) => ({ hostId, ids: [...ids] }));
    this.#nativeCloseDone = done;
    this.#ui.modal = 'close-native';
    return true;
  };

  finishNativeClose = async (action: 'end' | 'background' | 'external') => {
    const ui = this.#ui;
    for (const target of this.nativeCloseTargets) {
      const host = ui.host(target.hostId);
      if (!host) throw new Error('The local host was removed. Cancel and reconnect it first.');
      if (this.#app.net.connections[host.id] !== 'Connected') await this.#app.net.connect(host);
      await rpc(host.id, 'native_close', { ids: target.ids, action });
    }
    const done = this.#nativeCloseDone;
    this.#nativeCloseDone = null;
    this.nativeCloseTargets = [];
    ui.modal = '';
    await done?.();
  };

  cancelNativeClose = () => {
    this.#nativeCloseDone = null;
    this.#ui.modal = '';
  };

  confirmPendingClose = () => {
    const action = this.#pendingClose;
    this.#pendingClose = null;
    this.#ui.modal = '';
    action?.();
  };

  closePane = (p: Pane, confirmed = false) => {
    const ui = this.#ui;
    const dictation = this.#app.dictation;
    if (dictation.active && p.id === dictation.target) {
      ui.notice = 'Stop dictation before closing its terminal.';
      return;
    }
    const owner = this.owner(p.id);
    if (!owner) return;
    if (
      owner.hostId !== HOSTLESS &&
      !confirmed &&
      ui.nativePane(p) &&
      this.offerNativeClose([p], () => this.closePane(p, true))
    )
      return;
    if (!p.sessionId && !confirmed) {
      this.#pendingClose = () => this.closePane(p, true);
      ui.modal = 'close-terminal';
      return;
    }
    const layout = remove(retainTabPanes(this.layoutFor(owner), owner.tabGroups), p.id);
    if (!layout) {
      this.closeWorkspace(owner, confirmed);
      return;
    }
    this.updateWorkspace({
      ...owner,
      layout,
      panes: owner.panes.filter((x) => x.id !== p.id),
      focused: owner.panes.find((x) => x.id !== p.id)?.id || '',
    });
    ui.maximize = '';
  };

  closeWorkspace = (w: Workspace, confirmed = false) => {
    const ui = this.#ui;
    const device = ui.device;
    const dictation = this.#app.dictation;
    if (this.viewers.some((v) => v.workspaceId === w.id && v.locked)) {
      ui.notice = 'Save or discard viewer edits before closing this workspace.';
      return;
    }
    if (dictation.active && w.panes.some((p) => p.id === dictation.target)) {
      ui.notice = 'Stop dictation before closing its workspace.';
      return;
    }
    if (
      w.hostId !== HOSTLESS &&
      !confirmed &&
      this.offerNativeClose(w.panes, () => this.closeWorkspace(w, true))
    )
      return;
    if (!confirmed && w.panes.some((p) => !p.sessionId)) {
      this.#pendingClose = () => this.closeWorkspace(w, true);
      ui.modal = 'close-terminal';
      return;
    }
    this.viewers = this.viewers.filter((v) => v.workspaceId !== w.id);
    delete this.viewerLayouts[w.id];
    ui.profiles[w.hostId].workspaces = ui.profiles[w.hostId].workspaces.filter(
      (x) => x.id !== w.id,
    );
    if (device.activeWorkspace === w.id) {
      const next = ui.profiles[w.hostId].workspaces[0] || this.workspaces[0];
      device.activeWorkspace = next?.id || '';
      if (next) device.activeHost = next.hostId;
      ui.maximize = '';
    }
    ui.notice = w.panes.some(ui.nativePane)
      ? 'Workspace closed.'
      : 'Workspace deleted. Its tmux sessions are still running.';
  };

  contextPane = (e: MouseEvent, p: Pane, selection = terminals.get(p.id)?.selection?.() || '') => {
    e.preventDefault();
    this.#ui.paneMenu = {
      pane: p,
      selection: selection.trim().slice(0, 160),
      x: Math.max(8, Math.min(e.clientX, innerWidth - 316)),
      y: Math.max(8, Math.min(e.clientY, innerHeight - 420)),
    };
  };

  captureTerminalSelection = (node: HTMLElement, pane: Pane) => {
    const capture = (event: MouseEvent) => {
      const selection = terminals.get(pane.id)?.selection?.() || '';
      if (!selection.trim()) return;
      event.stopPropagation();
      this.contextPane(event, pane, selection);
    };
    node.addEventListener('contextmenu', capture, true);
    return {
      update(value: Pane) {
        pane = value;
      },
      destroy() {
        node.removeEventListener('contextmenu', capture, true);
      },
    };
  };

  menuItems = $derived.by((): TerminalMenuItem[] => {
    const ui = this.#ui;
    const dictation = this.#app.dictation;
    if (!ui.paneMenu) return [];
    const p = ui.paneMenu.pane;
    const close = (run: () => void) => () => {
      run();
      ui.paneMenu = null;
    };
    const owner = this.owner(p.id);
    const items: TerminalMenuItem[] = [{ id: 'split', label: 'Split', kind: 'split' }];
    if (owner?.hostId !== HOSTLESS)
      items.push({
        id: 'new-tab',
        label: 'New terminal tab',
        run: close(() => this.addTerminal('right', p, true)),
      });
    if (owner && terminalTabs(owner, p.id))
      items.push({
        id: 'ungroup-tab',
        label: 'Move tab into its own pane',
        run: close(() => {
          this.updateWorkspace({
            ...owner,
            tabGroups: (owner.tabGroups || []).map((group) => ({
              ...group,
              panes: group.panes.filter((id) => id !== p.id),
            })),
          });
        }),
      });
    if (owner && this.selectedPanes.length > 1)
      items.push({
        id: 'group-tabs',
        label: 'Group selected terminals as tabs',
        run: close(() => {
          const members = owner.panes
            .filter((pane) => this.selectedPanes.includes(pane.id))
            .map((pane) => pane.id);
          if (members.length < 2) return;
          const group = { id: uid(), panes: members };
          this.updateWorkspace({
            ...owner,
            tabGroups: [
              ...(owner.tabGroups || []).map((g) => ({
                ...g,
                panes: g.panes.filter((id) => !members.includes(id)),
              })),
              group,
            ],
          });
          (ui.device.activeTerminalTabs ||= {})[group.id] = p.id;
        }),
      });
    if (dictation.recent?.target === p.id)
      items.push({
        id: 'copy-last',
        label: 'Copy last dictation',
        run: close(() => {
          void navigator.clipboard.writeText(dictation.recent!.text).catch(ui.fail);
        }),
      });
    if (dictation.held?.target === p.id)
      items.push(
        {
          id: 'send-held',
          label: 'Send held dictation',
          run: close(() => void ui.task(dictation.sendHeld)),
        },
        { id: 'copy-held', label: 'Copy held dictation', run: close(dictation.copyHeld) },
        {
          id: 'discard-held',
          label: 'Discard held dictation',
          run: close(() => (dictation.held = null)),
        },
      );
    items.push({ id: 'transfer', label: 'Move or duplicate to workspace', kind: 'transfer' });
    const selection = ui.paneMenu.selection || '';
    items.push({
      id: 'dictionary',
      label: selection
        ? 'Add selection to dictation dictionary'
        : 'Add dictation dictionary phrase',
      run: close(() => {
        ui.dictionarySelection = selection;
      }),
    });
    const terminal = (run: (controls: NonNullable<ReturnType<typeof terminals.get>>) => void) =>
      close(() => {
        const controls = terminals.get(p.id);
        if (controls) run(controls);
      });
    items.push(
      { id: 'host', label: 'Change host', run: () => this.choosePaneHost(p) },
      { id: 'upload', label: 'Upload file', run: () => this.openFiles(p, 'upload') },
      { id: 'files', label: 'Files and previews', run: () => this.openFiles(p, 'documents') },
      {
        id: 'status',
        label: p.hideStatus ? 'Show status line' : 'Hide status line',
        run: () => this.toggleStatus(p),
      },
      { id: 'copy', label: 'Copy', run: terminal((t) => void t.copy()) },
      {
        id: 'paste',
        label: 'Paste',
        run: close(() => {
          void ui.task(async () => {
            const terminal = terminals.get(p.id);
            if (terminal?.pasteClipboard) await terminal.pasteClipboard();
            else await terminal?.paste(await navigator.clipboard.readText());
          });
        }),
      },
      { id: 'select-all', label: 'Select all', run: terminal((t) => t.selectAll()) },
      { id: 'find', label: 'Find', run: terminal((t) => t.search()) },
      { id: 'export', label: 'Export terminal output', run: terminal((t) => t.exportOutput()) },
      { id: 'clear', label: 'Clear scrollback', run: terminal((t) => t.clear()) },
      {
        id: 'colors',
        label: 'Terminal colors',
        run: close(() => {
          ui.colorPane = p;
          ui.modal = 'pane-colors';
        }),
      },
      {
        id: 'maximize',
        label: 'Maximise / restore',
        run: close(() => {
          ui.maximize = ui.maximize === p.id ? '' : p.id;
        }),
      },
    );
    if (p.sessionId)
      items.push({
        id: 'rename',
        label: ui.nativePane(p) ? 'Rename PowerShell session' : 'Rename tmux session',
        run: close(() => {
          ui.renameTarget = p;
          ui.renameValue = p.name;
          ui.modal = 'rename';
        }),
      });
    items.push({ id: 'close', label: 'Close', run: close(() => this.closePane(p)) });
    return items;
  });
}
