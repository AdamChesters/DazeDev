import { SvelteMap } from 'svelte/reactivity';
import { connectionDeadline, HOST_HEALTH_TIMEOUT, ConnectionOperations } from '../connection-health';
import { diagnostic } from '../diagnostics';
import { uid, type Host } from '../types';

export interface ConnectionsDeps {
  desktop: boolean;
  hosts: () => Host[];
  call: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  /** The host's management channel is ready for this attempt. */
  onready: (hostId: string, notice?: string) => void;
}

/** A Connecting attempt is replaced this long after its backoff ends. */
export const STALLED_CONNECT_MS = 105000;
/** Automatic reconnects wait this long after a connect starts. */
export const RECONNECT_BACKOFF_MS = 15000;

// Host management connections: state, attempts, health checks and events.
export class Connections {
  connections = $state<Record<string, string>>({});
  logs = $state<Record<string, string>>({});
  /** Current connect attempt id per host. */
  attempts = new SvelteMap<string, string>();
  /** Hosts the user wants connected; health checks reconnect only these. */
  reconnect = new Set<string>();
  #deps: ConnectionsDeps;
  #operations = new ConnectionOperations();
  #checking = new Map<string, symbol>();
  #next = new Map<string, number>();

  constructor(deps: ConnectionsDeps) {
    this.#deps = deps;
  }

  /** True when the attempt is still the host's current, wanted attempt. */
  current(hostId: string, attempt = this.attempts.get(hostId)) {
    return this.attempts.get(hostId) === attempt && this.reconnect.has(hostId);
  }

  async connect(h: Host) {
    if (this.connections[h.id] === 'Connecting') return;
    this.reconnect.add(h.id);
    this.#next.set(h.id, Date.now() + RECONNECT_BACKOFF_MS);
    this.connections[h.id] = 'Connecting';
    diagnostic('host.connect', h.id);
    this.logs[h.id] = '';
    const attempt = uid();
    this.attempts.set(h.id, attempt);
    try {
      await this.#operations.run(h.id, () => {
        if (this.attempts.get(h.id) !== attempt || !this.reconnect.has(h.id))
          return Promise.resolve();
        return this.#deps.call('host_connect', { host: h, attempt });
      });
    } catch (e) {
      if (this.attempts.get(h.id) === attempt) {
        this.connections[h.id] = 'Disconnected';
        this.logs[h.id] = String(e);
      }
    }
  }

  async reconnectHost(h: Host) {
    // Explicit retry also replaces an attempt stuck at Connecting.
    // Replace management only. Healthy terminal PTYs keep running independently.
    this.attempts.delete(h.id);
    this.#checking.delete(h.id);
    this.connections[h.id] = 'Disconnected';
    await this.connect(h);
  }

  async disconnect(hostId: string) {
    this.reconnect.delete(hostId);
    this.attempts.delete(hostId);
    this.#checking.delete(hostId);
    this.connections[hostId] = 'Disconnected';
    delete this.logs[hostId];
    await this.#operations.run(hostId, () => this.#deps.call('host_disconnect', { hostId }));
  }

  async check(onlyHost?: string) {
    if (!this.#deps.desktop) return;
    await Promise.all(
      this.#deps.hosts().map(async (h) => {
        if (onlyHost && h.id !== onlyHost) return;
        if (!this.reconnect.has(h.id) || this.#checking.has(h.id)) return;
        if (this.connections[h.id] === 'Connecting') {
          if (Date.now() < (this.#next.get(h.id) || 0) + STALLED_CONNECT_MS) return;
          // Invalidate the stalled attempt before replacing its management channel.
          this.attempts.delete(h.id);
          this.connections[h.id] = 'Disconnected';
        }
        if (!onlyHost && Date.now() < (this.#next.get(h.id) || 0)) return;
        const check = Symbol();
        const attempt = this.attempts.get(h.id);
        const current = () => this.reconnect.has(h.id) && this.attempts.get(h.id) === attempt;
        this.#checking.set(h.id, check);
        try {
          if (this.connections[h.id] === 'Connected') {
            try {
              await connectionDeadline(
                this.#deps.call('host_health', { hostId: h.id }),
                HOST_HEALTH_TIMEOUT,
                'Host did not respond to its health check.',
              );
              return;
            } catch (e) {
              if (!current()) return;
              this.connections[h.id] = 'Disconnected';
              this.logs[h.id] = String(e);
            }
          }
          if (current()) await this.connect(h);
        } catch (e) {
          if (this.#checking.get(h.id) === check) this.logs[h.id] = String(e);
        } finally {
          if (this.#checking.get(h.id) === check) this.#checking.delete(h.id);
        }
      }),
    );
  }

  /** Sends a password or host-key answer for the current attempt. */
  async respond(hostId: string, value: string) {
    const attempt = this.attempts.get(hostId);
    this.logs[hostId] = '';
    try {
      await this.#deps.call('host_auth', { hostId, data: value });
    } catch (e) {
      if (this.attempts.get(hostId) === attempt) throw e;
    }
  }

  ready(v: { hostId: string; attempt: string; identity?: { notice?: string } }) {
    if (!this.current(v.hostId, v.attempt)) return;
    this.#deps.onready(v.hostId, v.identity?.notice);
  }

  output(v: { hostId: string; text: string; attempt: string }) {
    if (!this.current(v.hostId, v.attempt)) return;
    this.logs[v.hostId] = ((this.logs[v.hostId] || '') + v.text).slice(-12000);
  }

  closed(v: { hostId: string; attempt: string }) {
    if (this.attempts.get(v.hostId) === v.attempt) {
      this.connections[v.hostId] = 'Disconnected';
      this.logs[v.hostId] ||= 'Connection closed.';
    }
  }
}
