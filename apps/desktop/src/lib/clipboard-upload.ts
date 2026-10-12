import { encodeChunk, shellPath } from './documents';

export const QUICK_UPLOAD_LIMIT = 10 * 1024 * 1024;
export type UploadCall = <T>(method: string, args: Record<string, unknown>) => Promise<T>;

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
      const transfer = await request<{ id: string; path: string }>('upload_begin', {
        paste: true,
        name: file.name || (file.type === 'image/png' ? 'screenshot.png' : 'clipboard-file'),
        size: file.size,
      });
      try {
        for (let offset = 0; offset < file.size;) {
          ensureCurrent();
          const bytes = new Uint8Array(await file.slice(offset, offset + 32768).arrayBuffer());
          const result = await request<{ offset: number }>('upload_chunk', {
            id: transfer.id,
            offset,
            data: encodeChunk(bytes),
          });
          if (result.offset !== offset + bytes.length)
            throw new Error('Upload progress did not match. Retry the upload.');
          offset = result.offset;
          progress(
            `Uploading ${index + 1}/${files.length}: ${Math.round((offset / file.size) * 100)}%`,
          );
        }
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
