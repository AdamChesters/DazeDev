import type { Broadcast } from './broadcast.svelte';
import type { Commands } from './commands.svelte';
import type { Connections } from './connections.svelte';
import type { DetachedWindows } from './detached-windows.svelte';
import type { DeviceStore } from './device-store.svelte';
import type { Dictation } from './dictation.svelte';
import type { Panes } from './panes.svelte';
import type { Profiles } from './profiles.svelte';
import type { Sessions } from './sessions.svelte';
import type { Shell } from './shell.svelte';
import type { Updates } from './updates.svelte';
import type { VoiceModels } from './voice.svelte';

// The app shell's state modules. App.svelte creates each module once and
// fills this object; modules read each other through it at call time, so the
// order they are created in does not matter.
export interface App {
  ui: Shell;
  panes: Panes;
  profiles: Profiles;
  sessions: Sessions;
  dictation: Dictation;
  commands: Commands;
  broadcast: Broadcast;
  net: Connections;
  deviceStore: DeviceStore;
  detached: DetachedWindows;
  voiceModels: VoiceModels;
  updates: Updates;
  /** True while the main window or a detached terminal window has focus. */
  appHasFocus: () => boolean;
}
