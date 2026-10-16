import { encodeChunk, shellPath } from './documents';

export const QUICK_UPLOAD_LIMIT = 10 * 1024 * 1024;
/** Chunk size the host offered in upload_begin; older hosts take 32 KiB. */
export function uploadChunkSize(transfer: { chunk?: unknown }) {
  const chunk = Number(transfer.chunk);
  return Number.isSafeInteger(chunk) && chunk >= 4096 ? Math.min(chunk, 1048576) : 32768;
}
export type UploadCall = <T>(method: string, args: Record<string, unknown>) => Promise<T>;
export type UploadTransfer = { id: string; chunk?: unknown; parallel?: unknown };
/** Most chunks kept in flight, whatever the host offers. */
export const MAX_PARALLEL_CHUNKS = 16;

/**
 * Sends `file` as upload_chunk requests. A host that offers `parallel: n` in
 * upload_begin takes up to n chunks at once, in any order, and replies with the
 * total bytes received; older hosts take one chunk at a time and reply with the
 * next offset. `current` runs before each chunk and throws to stop. On any
 * error no further chunks are sent; the caller aborts the transfer.
 */
export async function sendChunks(
  file: Blob,
  transfer: UploadTransfer,
  request: UploadCall,
  progress: (acknowledged: number) => void,
  current: () => void = () => {},
) {
  const size = uploadChunkSize(transfer);
  const read = async (offset: number) =>
    new Uint8Array(await file.slice(offset, offset + size).arrayBuffer());
  const parallel = Number(transfer.parallel);
  if (transfer.parallel === undefined || !Number.isSafeInteger(parallel) || parallel < 1) {
    for (let offset = 0; offset < file.size;) {
      current();
      const bytes = await read(offset);
      const result = await request<{ offset: number }>('upload_chunk', {
        id: transfer.id,
        offset,
        data: encodeChunk(bytes),
      });
      if (result.offset !== offset + bytes.length)
        throw new Error('Upload progress did not match. Retry the upload.');
      offset = result.offset;
      progress(offset);
    }
    return;
  }
  let next = 0,
    acknowledged = 0,
    failure: unknown;
  const worker = async () => {
    while (failure === undefined && next < file.size) {
      const offset = next;
      next += size;
      try {
        current();
        const bytes = await read(offset);
        if (failure !== undefined) return;
        const result = await request<{ received: number }>('upload_chunk', {
          id: transfer.id,
          offset,
          data: encodeChunk(bytes),
        });
        if (!Number.isSafeInteger(result?.received) || result.received > file.size)
          throw new Error('Upload progress did not match. Retry the upload.');
        if (result.received > acknowledged) progress((acknowledged = result.received));
      } catch (e) {
        if (failure === undefined) failure = e;
      }
    }
  };
  const lanes = Math.min(parallel, MAX_PARALLEL_CHUNKS, Math.ceil(file.size / size));
  await Promise.all(Array.from({ length: lanes }, worker));
  if (failure !== undefined) throw failure;
  if (acknowledged !== file.size)
    throw new Error('Upload progress did not match. Retry the upload.');
}

export function validateClipboardFiles(files: readonly Blob[]) {
  const size = files.reduce((sum, file) => sum + file.size, 0);
  if (!files.length) throw new Error('No clipboard files were found.');
  if (size > QUICK_UPLOAD_LIMIT)
    throw new Error('Quick paste is limited to 10 MiB. Use Upload file for larger files.');
  return size;
}

export async function uploadClipboardFiles(
  files: readonly File[],
  request: UploadCall,
  current: () => boolean,
  progress: (message: string) => void,
  powershell = false,
): Promise<string> {
  const size = validateClipboardFiles(files);
  const paths: string[] = [];
  const ensureCurrent = () => {
    if (!current()) throw new Error('Terminal changed during upload. No path was pasted.');
  };
  ensureCurrent();
  await request('upload_preflight', { paste: true, size });
  try {
    for (const [index, file] of files.entries()) {
      ensureCurrent();
      const transfer = await request<UploadTransfer & { path: string }>('upload_begin', {
        paste: true,
        name: file.name || (file.type === 'image/png' ? 'screenshot.png' : 'clipboard-file'),
        size: file.size,
      });
      try {
        await sendChunks(
          file,
          transfer,
          request,
          (acknowledged) =>
            progress(
              `Uploading ${index + 1}/${files.length}: ${Math.round((acknowledged / file.size) * 100)}%`,
            ),
          ensureCurrent,
        );
        ensureCurrent();
        const result = await request<{ path: string }>('upload_finish', { id: transfer.id });
        paths.push(result.path);
      } finally {
        await request('upload_abort', { id: transfer.id }).catch(() => {});
      }
    }
    ensureCurrent();
    return paths.map((path) => shellPath(path, powershell)).join(' ');
  } catch (error) {
    if (paths.length)
      throw new Error(
        `${String(error)} Completed files remain on the source host: ${paths.join(', ')}`,
        { cause: error },
      );
    throw error;
  }
}

export async function readClipboardContent(
  clipboard: Pick<Clipboard, 'readText'> & Partial<Pick<Clipboard, 'read'>>,
): Promise<{ files: File[]; text: string }> {
  let items: ClipboardItems | undefined;
  try {
    items = await clipboard.read?.();
  } catch (error) {
    if (
      !(error instanceof DOMException) ||
      !['NotAllowedError', 'NotSupportedError'].includes(error.name)
    )
      throw error;
  }
  if (!items) return { files: [], text: await clipboard.readText() };
  const hasImage = items.some((item) => item.types.some((type) => type.startsWith('image/')));
  const text = items.find((item) => item.types.includes('text/plain'));
  if (!hasImage && text)
    return { files: [], text: await (await text.getType('text/plain')).text() };
  const files: File[] = [];
  for (const item of items) {
    const type =
      item.types.find((t) => t.startsWith('image/')) ||
      (!hasImage ? item.types.find((t) => !t.startsWith('text/')) : undefined);
    if (type) {
      const blob = await item.getType(type);
      const extension = type.startsWith('image/')
        ? type.split('/')[1].replace(/[^a-z0-9]/gi, '')
        : type === 'application/pdf'
          ? 'pdf'
          : 'bin';
      files.push(new File([blob], `clipboard.${extension || 'bin'}`, { type }));
    }
  }
  if (files.length) return { files, text: '' };
  throw new Error('Clipboard content is not available. Use Upload file or paste a screenshot.');
}
