import { afterEach, expect, it, vi } from 'vitest';
import { Capture, MicrophoneTest, microphoneConstraints } from '../src/lib/audio';
import { diagnosticSnapshot } from '../src/lib/diagnostics';
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }));
import { invoke } from '@tauri-apps/api/core';
const analyser = () => ({
  fftSize: 0,
  getFloatTimeDomainData: (samples: Float32Array) => samples.fill(0.5),
  disconnect: vi.fn(),
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
it('can deliver captured audio while start is still awaiting AudioContext.resume', async () => {
  let resume!: () => void;
  const pendingResume = new Promise<void>((resolve) => {
    resume = resolve;
  });
  let node: { port: { onmessage: ((event: { data: Float32Array }) => void) | null } };
  const connected = vi.fn();
  vi.stubGlobal('navigator', {
    mediaDevices: { getUserMedia: vi.fn().mockResolvedValue({ getAudioTracks: () => [] }) },
  });
  vi.stubGlobal(
    'AudioContext',
    class {
      state = 'running';
      destination = {};
      audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) };
      createMediaStreamSource() {
        return { connect: connected };
      }
      createGain() {
        return { gain: { value: 1 }, connect() {} };
      }
      createAnalyser() {
        return analyser();
      }
      resume() {
        return pendingResume;
      }
      addEventListener() {}
    },
  );
  vi.stubGlobal(
    'AudioWorkletNode',
    class {
      port = { onmessage: null };
      constructor() {
        node = this;
      }
      connect(destination: unknown) {
        return destination;
      }
    },
  );
  let ready = false;
  const start = new Capture().start('', vi.fn(), 'startup-order-test').then(() => {
    ready = true;
  });
  await vi.waitFor(() => expect(connected).toHaveBeenCalled());
  const frame = new Float32Array(3200);
  node!.port.onmessage!({ data: frame });
  await vi.waitFor(() =>
    expect(invoke).toHaveBeenCalledWith('voice_audio', expect.any(Uint8Array)),
  );
  expect(ready).toBe(false);
  let events = diagnosticSnapshot()
    .filter((e) => e.target === 'startup-order-test')
    .map((e) => e.event);
  expect(events).toContain('voice.capture.first-frame');
  expect(events).not.toContain('voice.capture.resume-complete');
  resume();
  await start;
  expect(ready).toBe(true);
  events = diagnosticSnapshot()
    .filter((e) => e.target === 'startup-order-test')
    .map((e) => e.event);
  expect(events.indexOf('voice.capture.first-frame')).toBeLessThan(
    events.indexOf('voice.capture.resume-complete'),
  );
});

it('stops a microphone granted after capture was cancelled while awaiting permission', async () => {
  let grant!: (stream: unknown) => void;
  const stop = vi.fn();
  vi.stubGlobal('navigator', {
    mediaDevices: {
      getUserMedia: () =>
        new Promise((resolve) => {
          grant = resolve;
        }),
    },
  });
  const context = vi.fn();
  vi.stubGlobal('AudioContext', context);
  const capture = new Capture();
  const starting = capture.start('', vi.fn());
  await capture.stop();
  grant({ getTracks: () => [{ stop }] });
  await starting;
  expect(stop).toHaveBeenCalledOnce();
  expect(context).not.toHaveBeenCalled();
});

it('duplicate stop waits for the same final flush and queued PCM delivery', async () => {
  let completeWrite!: () => void;
  vi.mocked(invoke).mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        completeWrite = resolve;
      }) as any,
  );
  const capture = new Capture();
  const trackStop = vi.fn();
  let port: any;
  vi.stubGlobal('navigator', {
    mediaDevices: {
      getUserMedia: async () => ({
        getTracks: () => [{ stop: trackStop }],
        getAudioTracks: () => [],
      }),
    },
  });
  vi.stubGlobal(
    'AudioContext',
    class {
      state = 'running';
      destination = {};
      audioWorklet = { addModule: async () => {} };
      createMediaStreamSource() {
        return { connect() {} };
      }
      createGain() {
        return { gain: { value: 1 }, connect() {} };
      }
      createAnalyser() {
        return analyser();
      }
      async resume() {}
      addEventListener() {}
      async close() {
        this.state = 'closed';
      }
    },
  );
  vi.stubGlobal(
    'AudioWorkletNode',
    class {
      port = { onmessage: null as any, postMessage: vi.fn() };
      constructor() {
        port = this.port;
      }
      connect(destination: unknown) {
        return destination;
      }
      disconnect() {}
    },
  );
  await capture.start('', vi.fn());
  let stopped = false;
  const stopping = capture.stop();
  expect(capture.stop()).toBe(stopping);
  void stopping.then(() => {
    stopped = true;
  });
  expect(trackStop).toHaveBeenCalledOnce();
  port.onmessage({ data: new Float32Array([0.1]) });
  port.onmessage({ data: 'flushed' });
  await vi.waitFor(() => expect(completeWrite).toBeTypeOf('function'));
  expect(stopped).toBe(false);
  completeWrite();
  await stopping;
  expect(stopped).toBe(true);
});

it('reports the input level only while the microphone is open', async () => {
  const capture = new Capture();
  vi.stubGlobal('navigator', {
    mediaDevices: {
      getUserMedia: async () => ({ getTracks: () => [], getAudioTracks: () => [] }),
    },
  });
  vi.stubGlobal(
    'AudioContext',
    class {
      state = 'running';
      destination = {};
      audioWorklet = { addModule: async () => {} };
      createMediaStreamSource() {
        return { connect() {} };
      }
      createGain() {
        return { gain: { value: 1 }, connect() {} };
      }
      createAnalyser() {
        return analyser();
      }
      async resume() {}
      addEventListener() {}
      async close() {
        this.state = 'closed';
      }
    },
  );
  vi.stubGlobal(
    'AudioWorkletNode',
    class {
      port = {
        onmessage: null as any,
        postMessage: (message: string) => {
          if (message === 'flush') this.port.onmessage({ data: 'flushed' });
        },
      };
      connect(destination: unknown) {
        return destination;
      }
      disconnect() {}
    },
  );
  expect(capture.level()).toBeNull();
  expect(capture.waveform()).toBeNull();
  await capture.start('', vi.fn());
  expect(capture.level()).toEqual({ rms: 0.5, peak: 0.5 });
  expect(capture.waveform()).toHaveLength(2048);
  expect(invoke).not.toHaveBeenCalled();
  await capture.stop();
  expect(capture.level()).toBeNull();
  expect(capture.waveform()).toBeNull();
});

it('uses the same microphone constraints for dictation and the settings test', () => {
  expect(microphoneConstraints('')).toEqual({
    audio: {
      deviceId: undefined,
      channelCount: 1,
      echoCancellation: false,
      noiseSuppression: true,
      autoGainControl: false,
    },
  });
  expect((microphoneConstraints('usb').audio as MediaTrackConstraints).deviceId).toEqual({
    exact: 'usb',
  });
});

it('the settings microphone test shows a level, sends no audio and releases the microphone', async () => {
  const trackStop = vi.fn();
  const getUserMedia = vi.fn(async (_constraints: MediaStreamConstraints) => ({
    getTracks: () => [{ stop: trackStop }],
    getAudioTracks: () => [{ addEventListener() {} }],
  }));
  const tap = analyser();
  const close = vi.fn();
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });
  vi.stubGlobal(
    'AudioContext',
    class {
      state = 'running';
      createMediaStreamSource() {
        return { connect() {} };
      }
      createAnalyser() {
        return tap;
      }
      async resume() {}
      async close() {
        this.state = 'closed';
        close();
      }
    },
  );
  const test = new MicrophoneTest();
  expect(test.level()).toBeNull();
  await test.start('usb', vi.fn());
  expect(getUserMedia).toHaveBeenCalledWith(microphoneConstraints('usb'));
  expect(test.level()).toEqual({ rms: 0.5, peak: 0.5 });
  await test.stop();
  await test.stop();
  expect(test.level()).toBeNull();
  expect(trackStop).toHaveBeenCalledOnce();
  expect(tap.disconnect).toHaveBeenCalledOnce();
  expect(close).toHaveBeenCalledOnce();
  expect(invoke).not.toHaveBeenCalled();
});

it('releases a microphone granted after the settings test was stopped', async () => {
  let grant!: (stream: unknown) => void;
  const stop = vi.fn();
  vi.stubGlobal('navigator', {
    mediaDevices: {
      getUserMedia: () =>
        new Promise((resolve) => {
          grant = resolve;
        }),
    },
  });
  const context = vi.fn();
  vi.stubGlobal('AudioContext', context);
  const test = new MicrophoneTest();
  const starting = test.start('', vi.fn());
  await test.stop();
  grant({ getTracks: () => [{ stop }] });
  await starting;
  expect(stop).toHaveBeenCalledOnce();
  expect(context).not.toHaveBeenCalled();
});
