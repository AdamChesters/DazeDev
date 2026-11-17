import { invoke } from '@tauri-apps/api/core';
import { diagnostic } from './diagnostics';
import { measure, type RawLevel } from './mic-level';

/** One definition, so the settings test hears the microphone exactly as dictation does. */
export function microphoneConstraints(device: string): MediaStreamConstraints {
  return {
    audio: {
      deviceId: device ? { exact: device } : undefined,
      channelCount: 1,
      echoCancellation: false,
      noiseSuppression: true,
      autoGainControl: false,
    },
  };
}

/** Reads the current input level from an audio source without touching the captured audio. */
class LevelTap {
  #analyser: AnalyserNode;
  #samples: Float32Array<ArrayBuffer>;
  constructor(context: AudioContext, source: AudioNode) {
    this.#analyser = context.createAnalyser();
    // 128 ms at 16 kHz: follows speech closely and is long enough to draw around a pane.
    this.#analyser.fftSize = 2048;
    this.#samples = new Float32Array(this.#analyser.fftSize);
    source.connect(this.#analyser);
  }
  /** The most recent 128 ms of input. The buffer is reused on every call. */
  samples(): Float32Array {
    this.#analyser.getFloatTimeDomainData(this.#samples);
    return this.#samples;
  }
  read(): RawLevel {
    return measure(this.samples());
  }
  disconnect() {
    this.#analyser.disconnect();
  }
}

export class Capture {
  private stream?: MediaStream;
  private context?: AudioContext;
  private node?: AudioWorkletNode;
  private chain = Promise.resolve();
  private stopped = false;
  private stopping = false;
  private stopTask?: Promise<void>;
  private tap?: LevelTap;
  /** Current input level, or null when the microphone is not open. */
  level = (): RawLevel | null => (this.live() ? this.tap!.read() : null);
  /** Recent input samples for drawing a waveform, or null when the microphone is not open. */
  waveform = (): Float32Array | null => (this.live() ? this.tap!.samples() : null);
  private live() {
    return !!this.tap && !this.stopped && !this.stopping;
  }
  async start(device: string, onerror: (e: unknown) => void, recording = '') {
    const started = performance.now();
    const mark = (event: string) =>
      diagnostic(`voice.capture.${event}`, recording, Math.round(performance.now() - started));
    mark('requested');
    this.stopped = false;
    this.stopping = false;
    this.stopTask = undefined;
    this.stream = await navigator.mediaDevices.getUserMedia(microphoneConstraints(device));
    if (this.stopped) {
      this.stream.getTracks().forEach((track) => track.stop());
      return;
    }
    mark('microphone-open');
    this.context = new AudioContext({ sampleRate: 16000 });
    await this.context.audioWorklet.addModule('/capture.js');
    if (this.stopped) return;
    mark('worklet-loaded');
    let firstFrame = true;
    this.node = new AudioWorkletNode(this.context, 'dazed-capture');
    this.node.port.onmessage = (e) => {
      if (this.stopped) return;
      const samples = e.data as Float32Array;
      if (firstFrame) {
        firstFrame = false;
        mark('first-frame');
      }
      // Keep PCM binary across the IPC boundary; avoid serializing thousands of numbers.
      this.chain = this.chain
        .then(() =>
          invoke<void>(
            'voice_audio',
            new Uint8Array(samples.buffer, samples.byteOffset, samples.byteLength),
          ),
        )
        .catch(onerror);
    };
    const source = this.context.createMediaStreamSource(this.stream);
    source.connect(this.node);
    this.tap = new LevelTap(this.context, source);
    const mute = this.context.createGain();
    mute.gain.value = 0;
    this.node.connect(mute).connect(this.context.destination);
    mark('graph-connected');
    await this.context.resume();
    mark('resume-complete');
    let reported = false;
    const interrupted = () => {
      if (!this.stopped && !this.stopping && !reported) {
        reported = true;
        onerror(
          new Error(
            'Microphone stopped or was suspended. Finalizing captured speech; reconnect the microphone before recording again.',
          ),
        );
      }
    };
    this.stream.getAudioTracks().forEach((track) => {
      track.addEventListener('ended', interrupted);
      track.addEventListener('mute', interrupted);
    });
    this.context.addEventListener('statechange', () => {
      if (this.context?.state !== 'running') interrupted();
    });
  }
  stop(): Promise<void> {
    if (!this.stopTask) this.stopTask = this.finish();
    return this.stopTask;
  }
  private async finish() {
    if (this.stopped) return;
    this.stopping = true;
    // Stop the physical source immediately, then flush already captured audio.
    this.stream?.getTracks().forEach((t) => t.stop());
    if (this.node) {
      await new Promise<void>((resolve) => {
        const old = this.node!.port.onmessage;
        const timeout = setTimeout(resolve, 1000);
        this.node!.port.onmessage = (e) => {
          if (e.data === 'flushed') {
            clearTimeout(timeout);
            resolve();
            return;
          }
          old?.call(this.node!.port, e);
        };
        this.node!.port.postMessage('flush');
      });
    }
    this.stopped = true;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.node?.disconnect();
    this.tap?.disconnect();
    this.tap = undefined;
    if (this.context?.state !== 'closed') await this.context?.close();
    await this.chain;
  }
}

/** Opens the microphone only to show its level in Settings. Nothing is recorded or transcribed. */
export class MicrophoneTest {
  private stream?: MediaStream;
  private context?: AudioContext;
  private tap?: LevelTap;
  private stopped = false;
  level = (): RawLevel | null => (this.tap && !this.stopped ? this.tap.read() : null);
  async start(device: string, onended: () => void) {
    const stream = await navigator.mediaDevices.getUserMedia(microphoneConstraints(device));
    if (this.stopped) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }
    this.stream = stream;
    this.context = new AudioContext({ sampleRate: 16000 });
    this.tap = new LevelTap(this.context, this.context.createMediaStreamSource(stream));
    stream.getAudioTracks().forEach((track) =>
      track.addEventListener('ended', () => {
        if (!this.stopped) onended();
      }),
    );
    await this.context.resume();
  }
  async stop() {
    if (this.stopped) return;
    this.stopped = true;
    this.stream?.getTracks().forEach((track) => track.stop());
    this.tap?.disconnect();
    this.tap = undefined;
    if (this.context && this.context.state !== 'closed') await this.context.close();
  }
}
