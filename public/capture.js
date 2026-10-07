class Capture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.pending = new Float32Array(3200);
    this.used = 0;
    this.position = 0;
    this.finished = false;
    this.port.onmessage = (e) => {
      if (e.data === 'flush') {
        this.finished = true;
        if (this.used) {
          const chunk = this.pending.slice(0, this.used);
          this.port.postMessage(chunk, [chunk.buffer]);
        }
        this.pending = new Float32Array(3200);
        this.used = 0;
        this.port.postMessage('flushed');
      }
    };
  }
  process(inputs) {
    if (this.finished) return true;
    const channel = inputs[0]?.[0];
    if (!channel) return true;
    const step = sampleRate / 16000;
    for (; this.position < channel.length; this.position += step) {
      const i = Math.floor(this.position),
        f = this.position - i;
      this.pending[this.used++] =
        channel[i] * (1 - f) + (channel[Math.min(i + 1, channel.length - 1)] || 0) * f;
      if (this.used === this.pending.length) {
        this.port.postMessage(this.pending, [this.pending.buffer]);
        this.pending = new Float32Array(3200);
        this.used = 0;
      }
    }
    this.position -= channel.length;

    return true;
  }
}
registerProcessor('dazed-capture', Capture);
