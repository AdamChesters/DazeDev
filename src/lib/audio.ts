import { invoke } from '@tauri-apps/api/core';
import { diagnostic } from './diagnostics';
export class Capture {
  private stream?: MediaStream;
  private context?: AudioContext;
  private node?: AudioWorkletNode;
  private chain = Promise.resolve();
  private stopped = false;
  private stopping = false;
  private stopTask?: Promise<void>;
  async start(device: string, onerror: (e: unknown) => void, recording = '') {
    const started = performance.now();
    const mark = (event: string) =>
      diagnostic(`voice.capture.${event}`, recording, Math.round(performance.now() - started));
    mark('requested');
    this.stopped = false;
    this.stopping = false;
    this.stopTask = undefined;
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: device ? { exact: device } : undefined,
        channelCount: 1,
        echoCancellation: false,
        noiseSuppression: true,
        autoGainControl: false,
      },
    });
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
    if (this.context?.state !== 'closed') await this.context?.close();
    await this.chain;
  }
}
