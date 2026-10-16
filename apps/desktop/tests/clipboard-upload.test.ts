import { describe, expect, it } from 'vitest';
import {
  QUICK_UPLOAD_LIMIT,
  readClipboardContent,
  sendChunks,
  uploadClipboardFiles,
  type UploadCall,
} from '../src/lib/clipboard-upload';

// A host that takes chunks out of order and replies with the total received.
// Each reply waits for a later chunk (or a short timer), so replies arrive
// out of order.
function parallelHost(size: number, failAt?: number) {
  const received = new Uint8Array(size);
  let total = 0,
    inFlight = 0,
    most = 0;
  const offsets: number[] = [];
  const request: UploadCall = async <T>(method: string, args: Record<string, unknown>) => {
    if (method !== 'upload_chunk') throw Error(`Unexpected ${method}`);
    const offset = Number(args.offset);
    offsets.push(offset);
    inFlight++;
    most = Math.max(most, inFlight);
    const bytes = Uint8Array.from(atob(String(args.data)), (c) => c.charCodeAt(0));
    await new Promise((resolve) => setTimeout(resolve, offset % 3 === 0 ? 5 : 1));
    inFlight--;
    if (offset === failAt) throw Error('disk full');
    received.set(bytes, offset);
    total += bytes.length;
    return { received: total } as T;
  };
  return { request, received, offsets, most: () => most };
}

describe('chunk sending', () => {
  const bytes = new Uint8Array(4096 * 10 + 123).map((_, i) => (i * 7) % 256);
  const file = new File([bytes], 'big.bin');

  it('keeps up to the offered number of chunks in flight and reassembles out-of-order replies', async () => {
    const host = parallelHost(bytes.length);
    const seen: number[] = [];
    await sendChunks(file, { id: 't', chunk: 4096, parallel: 3 }, host.request, (n) =>
      seen.push(n),
    );
    expect(host.received).toEqual(bytes);
    expect(host.most()).toBe(3);
    expect([...host.offsets].sort((a, b) => a - b)).toEqual(
      Array.from({ length: 11 }, (_, i) => i * 4096),
    );
    expect(seen.at(-1)).toBe(bytes.length);
    expect(seen).toEqual([...seen].sort((a, b) => a - b));
  });

  it('stops sending after a chunk fails and reports the error', async () => {
    const host = parallelHost(bytes.length, 4096 * 2);
    await expect(
      sendChunks(file, { id: 't', chunk: 4096, parallel: 2 }, host.request, () => {}),
    ).rejects.toThrow('disk full');
    // Chunks already in flight may finish; none start after the failure.
    expect(host.offsets.length).toBeLessThan(6);
  });

  it('sends one chunk at a time to a host that does not offer parallel chunks', async () => {
    let inFlight = 0,
      most = 0;
    const request: UploadCall = async <T>(_method: string, args: Record<string, unknown>) => {
      inFlight++;
      most = Math.max(most, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 1));
      inFlight--;
      const length = atob(String(args.data)).length;
      return { offset: Number(args.offset) + length } as T;
    };
    await sendChunks(file, { id: 't', chunk: 4096 }, request, () => {});
    expect(most).toBe(1);
  });
});

describe('clipboard upload', () => {
  it('rejects an oversized batch before contacting a host', async () => {
    let calls = 0;
    const request: UploadCall = async () => {
      calls++;
      throw Error('Unexpected request');
    };
    const files = [new File([new Uint8Array(QUICK_UPLOAD_LIMIT)], 'one'), new File(['x'], 'two')];
    await expect(
      uploadClipboardFiles(
        files,
        request,
        () => true,
        () => {},
      ),
    ).rejects.toThrow('Upload file');
    expect(calls).toBe(0);
  });

  it('preserves binary bytes, checks total capacity and quotes returned paths without Enter', async () => {
    const bytes = new Uint8Array(40000).map((_, i) => i % 256);
    const received: number[] = [];
    const calls: string[] = [];
    const request: UploadCall = async <T>(method: string, args: Record<string, unknown>) => {
      calls.push(method);
      if (method === 'upload_preflight') {
        expect(args.size).toBe(bytes.length);
        return {} as T;
      }
      if (method === 'upload_begin') {
        expect(args.paste).toBe(true);
        return { id: 'transfer' } as T;
      }
      if (method === 'upload_chunk') {
        expect(args.offset).toBe(received.length);
        received.push(...Uint8Array.from(atob(String(args.data)), (c) => c.charCodeAt(0)));
        return { offset: received.length } as T;
      }
      return (
        method === 'upload_finish' ? { path: "/home/user/uploads/a'b image.png" } : true
      ) as T;
    };
    const result = await uploadClipboardFiles(
      [new File([bytes], 'shot.png')],
      request,
      () => true,
      () => {},
    );
    expect(new Uint8Array(received)).toEqual(bytes);
    expect(result).toBe("'/home/user/uploads/a'\\''b image.png'");
    expect(result).not.toMatch(/[\r\n]/);
    expect(calls.at(-1)).toBe('upload_abort');
  });

  it('aborts incomplete uploads when the terminal context changes', async () => {
    let current = true;
    const calls: string[] = [];
    const request: UploadCall = async <T>(method: string) => {
      calls.push(method);
      if (method === 'upload_begin') {
        current = false;
        return { id: 'transfer' } as T;
      }
      return {} as T;
    };
    await expect(
      uploadClipboardFiles(
        [new File(['a'], 'a.txt')],
        request,
        () => current,
        () => {},
      ),
    ).rejects.toThrow('Terminal changed');
    expect(calls).toEqual(['upload_preflight', 'upload_begin', 'upload_abort']);
  });

  it('does not begin a transfer when the capacity check fails', async () => {
    const calls: string[] = [];
    const request: UploadCall = async (method: string) => {
      calls.push(method);
      throw Error('Not enough space');
    };
    await expect(
      uploadClipboardFiles(
        [new File(['a'], 'a.txt')],
        request,
        () => true,
        () => {},
      ),
    ).rejects.toThrow('Not enough space');
    expect(calls).toEqual(['upload_preflight']);
  });
});

describe('clipboard formats', () => {
  it('keeps editor text as text when custom application metadata accompanies it', async () => {
    const result = await readClipboardContent({
      read: async () => [
        {
          types: ['application/x-editor-data', 'text/plain'],
          presentationStyle: 'unspecified',
          getType: async (type: string) => {
            expect(type).toBe('text/plain');
            return new Blob(['code\nwith lines']);
          },
        } as ClipboardItem,
      ],
      readText: async () => {
        throw Error('No fallback expected');
      },
    });
    expect(result).toEqual({ files: [], text: 'code\nwith lines' });
  });
  it('preserves multiline plain text when binary clipboard access is unavailable', async () => {
    const result = await readClipboardContent({
      read: async () => {
        throw new DOMException('Unavailable', 'NotSupportedError');
      },
      readText: async () => 'one\ntwo',
    });
    expect(result).toEqual({ files: [], text: 'one\ntwo' });
  });
  it('prefers image bytes over accompanying text and does not fall back after a file read failure', async () => {
    let fallback = false;
    const clipboard = {
      read: async () => [
        {
          types: ['text/plain', 'image/png'],
          presentationStyle: 'unspecified',
          getType: async (type: string) => {
            expect(type).toBe('image/png');
            return new Blob([new Uint8Array([137, 80, 78, 71])], { type });
          },
        } as ClipboardItem,
      ],
      readText: async () => {
        fallback = true;
        return 'wrong text';
      },
    };
    const result = await readClipboardContent(clipboard);
    expect(result.text).toBe('');
    expect(result.files).toHaveLength(1);
    expect(result.files[0].type).toBe('image/png');
    expect(fallback).toBe(false);
    clipboard.read = async () => [
      {
        types: ['image/png'],
        presentationStyle: 'unspecified',
        getType: async () => {
          throw new DOMException('Denied', 'NotAllowedError');
        },
      } as ClipboardItem,
    ];
    await expect(readClipboardContent(clipboard)).rejects.toThrow('Denied');
    expect(fallback).toBe(false);
  });
  it('reports completed paths after a partial batch failure without returning paste text', async () => {
    let transfer = 0;
    const aborted: string[] = [];
    const request: UploadCall = async <T>(method: string, args: Record<string, unknown>) => {
      if (method === 'upload_begin') return { id: String(++transfer) } as T;
      if (method === 'upload_chunk') {
        if (transfer === 2) throw Error('Disk full');
        return { offset: 1 } as T;
      }
      if (method === 'upload_finish') return { path: '/host/uploads/first.txt' } as T;
      if (method === 'upload_abort') aborted.push(String(args.id));
      return {} as T;
    };
    await expect(
      uploadClipboardFiles(
        [new File(['a'], 'first.txt'), new File(['b'], 'second.txt')],
        request,
        () => true,
        () => {},
      ),
    ).rejects.toThrow('Completed files remain on the source host: /host/uploads/first.txt');
    expect(aborted).toEqual(['1', '2']);
  });
});

describe('upload chunk size', () => {
  it('uses the size the host offers, within limits', async () => {
    const { uploadChunkSize } = await import('../src/lib/clipboard-upload');
    expect(uploadChunkSize({})).toBe(32768);
    expect(uploadChunkSize({ chunk: 1048576 })).toBe(1048576);
    expect(uploadChunkSize({ chunk: 8 * 1048576 })).toBe(1048576);
    expect(uploadChunkSize({ chunk: 100 })).toBe(32768);
    expect(uploadChunkSize({ chunk: '1048576' })).toBe(1048576);
  });
  it('sends one chunk per offered size', async () => {
    const { uploadClipboardFiles } = await import('../src/lib/clipboard-upload');
    const bytes = new Uint8Array(150_000).map((_, i) => i % 251);
    const sizes: number[] = [];
    let offset = 0;
    await uploadClipboardFiles(
      [new File([bytes], 'big.bin')],
      async <T>(method: string, args: Record<string, unknown>) => {
        if (method === 'upload_begin') return { id: 't', path: '/x', chunk: 65536 } as T;
        if (method === 'upload_chunk') {
          const size = atob(String(args.data)).length;
          sizes.push(size);
          offset += size;
          return { offset } as T;
        }
        return (method === 'upload_finish' ? { path: '/x' } : {}) as T;
      },
      () => true,
      () => {},
    );
    expect(sizes).toEqual([65536, 65536, 150_000 - 131072]);
  });
});
