<script lang="ts">
  import { untrack } from 'svelte';
  import ImageViewer from './ImageViewer.svelte';
  import { imagePath, readHostImage, type ViewedImage } from '../lib/images';
  import SourceViewer from './SourceViewer.svelte';
  import HtmlPreview from './HtmlPreview.svelte';
  import { previewKind } from '../lib/source-viewer';
  import { rpc } from '../lib/bridge';
  import { uploadChunkSize } from '../lib/clipboard-upload';
  import { openPreviewLink } from '../lib/preview-links';
  import type { Host, Inventory } from '../lib/types';
  import {
    markdownPreview,
    encodeChunk,
    shellPath,
    type HostDocument,
    type DocumentText,
  } from '../lib/documents';
  let {
    hosts,
    inventories,
    connected,
    initialHost,
    initialPath = '',
    directory,
    mode,
    onpaste,
    onbusy,
    ondocument = () => {},
  }: {
    hosts: Host[];
    inventories: Record<string, Inventory>;
    connected: Record<string, string>;
    initialHost: string;
    initialPath?: string;
    directory: string;
    mode: 'upload' | 'documents';
    onpaste: (text: string) => Promise<void>;
    onbusy: (value: boolean) => void;
    ondocument?: (path: string, hostId: string) => void;
  } = $props();
  let hostId = $state(untrack(() => initialHost));
  let folder = $state(untrack(() => directory || inventories[initialHost]?.home || ''));
  let files = $state<HostDocument[]>([]);
  let document = $state<DocumentText | null>(null);
  let draft = $state('');
  let path = $state(untrack(() => initialPath));
  let image = $state<ViewedImage | null>(null);
  $effect(() => {
    const requested = initialPath;
    if (requested) untrack(() => void task(() => read(requested)));
  });
  let error = $state('');
  let message = $state('');
  let working = $state(false);
  let selected = $state<File | null>(null);
  let filename = $state('');
  let progress = $state(0);
  let uploaded = $state('');
  type UploadCapacity = {
    directory: string;
    freeBytes: number;
    availableBytes: number;
    reservedBytes: number;
    requiredBytes: number;
    maxBytes: number;
  };
  let capacity = $state<UploadCapacity | null>(null),
    capacitySize = $state(0);
  let storageChecking = $state(false),
    storageError = $state(''),
    storageRefresh = $state(0);
  const manualLimit = 256 * 1024 * 1024;
  const uploadLimit = $derived(Math.min(manualLimit, capacity?.maxBytes ?? manualLimit));
  const tooLarge = $derived(!!selected && selected.size > uploadLimit);
  const insufficientStorage = $derived(
    !!selected &&
      !!capacity &&
      selected.size + capacity.requiredBytes - capacitySize > capacity.availableBytes,
  );
  function bytesLabel(bytes: number) {
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let unit = 0;
    while (bytes >= 1024 && unit < units.length - 1) {
      bytes /= 1024;
      unit++;
    }
    return `${unit ? bytes.toFixed(2) : bytes} ${units[unit]}`;
  }
  async function inspectStorage(targetHost: string, targetFolder: string, size: number) {
    const value = await rpc<UploadCapacity>(targetHost, 'upload_preflight', {
      directory: targetFolder,
      size,
      paste: false,
    });
    if (
      !value ||
      typeof value.directory !== 'string' ||
      !value.directory ||
      ['freeBytes', 'availableBytes', 'reservedBytes', 'requiredBytes', 'maxBytes'].some(
        (key) =>
          !Number.isSafeInteger(value[key as keyof UploadCapacity]) ||
          Number(value[key as keyof UploadCapacity]) < 0,
      ) ||
      value.requiredBytes < size
    )
      throw Error('The host returned invalid storage information. No upload was started.');
    return value;
  }
  $effect(() => {
    const destinationHost = hostId,
      destinationFolder = folder,
      file = selected;
    const connection = connected[destinationHost];
    storageRefresh;
    capacity = null;
    capacitySize = 0;
    storageError = '';
    storageChecking = false;
    if (mode !== 'upload' || !destinationFolder.trim() || connection !== 'Connected') return;
    let active = true;
    storageChecking = true;
    const timer = setTimeout(() => {
      // A zero-size query still returns the destination's headroom requirement, allowing
      // the file size/available space comparison to remain visible when the file will not fit.
      void inspectStorage(destinationHost, destinationFolder, 0)
        .then((value) => {
          if (active) {
            capacity = value;
            capacitySize = 0;
          }
        })
        .catch((e) => {
          if (active) storageError = String(e);
        })
        .finally(() => {
          if (active) storageChecking = false;
        });
    }, 250);
    void file; // A different selection invalidates even a same-sized file's pending check.
    return () => {
      active = false;
      clearTimeout(timer);
    };
  });
  let canceled = false;
  let preview = $state(false);
  let editing = $state(false);
  let pickerOpen = $state(true);
  const kind = $derived(previewKind(document?.path || ''));
  const dirty = $derived(document !== null && draft !== document.text);
  $effect(() => {
    onbusy(working || dirty);
  });
  async function task(fn: () => Promise<void>) {
    if (working) return;
    working = true;
    error = '';
    message = '';
    try {
      await fn();
    } catch (e) {
      error = String(e);
    } finally {
      working = false;
    }
  }
  function changeHost() {
    document = null;
    image = null;
    ondocument('', hostId);
    files = [];
    draft = '';
    path = '';
    uploaded = '';
    folder = inventories[hostId]?.home || '';
  }
  async function discover() {
    const result = await rpc<{ files: HostDocument[]; warnings: string[] }>(hostId, 'documents', {
      path: folder,
    });
    files = result.files;
    message = result.warnings.join(' ') || `${files.length} documents found.`;
  }
  async function read(value: string) {
    if (imagePath(value) || /^https?:\/\//i.test(value)) {
      const result = /^https?:\/\//i.test(value)
        ? { path: value, src: value }
        : await readHostImage(hostId, value, folder);
      image = result;
      document = null;
      draft = '';
      path = result.path;
      ondocument(result.path, hostId);
      pickerOpen = false;
      editing = false;
      return;
    }
    const result = await rpc<DocumentText>(hostId, 'file_read', { path: value, directory: folder });
    image = null;
    document = result;
    draft = result.text;
    path = result.path;
    ondocument(result.path, hostId);
    preview = Boolean(previewKind(result.path));
    editing = false;
    pickerOpen = false;
  }
  async function save() {
    if (!document) return;
    document = await rpc<DocumentText>(hostId, 'document_save', {
      path: document.path,
      text: draft,
      revision: document.revision,
    });
    message = 'Saved on the selected host.';
  }
  async function upload() {
    if (!selected) return;
    const file = selected;
    const targetHost = hostId,
      targetFolder = folder,
      targetName = filename;
    if (file.size > manualLimit) throw Error('The file exceeds the 256 MiB upload limit.');
    canceled = false;
    uploaded = '';
    progress = 0;
    capacity = null;
    capacitySize = 0;
    const checked = await inspectStorage(targetHost, targetFolder, file.size);
    if (canceled) throw Error('Upload canceled. No destination file was created.');
    capacity = checked;
    capacitySize = file.size;
    if (file.size > checked.maxBytes || checked.requiredBytes > checked.availableBytes)
      throw Error('The selected file exceeds the upload limit or available destination storage.');
    const transfer = await rpc<{ id: string; path: string; chunk?: number }>(targetHost, 'upload_begin', {
      directory: checked.directory,
      name: targetName,
      size: file.size,
    });
    try {
      for (let offset = 0; offset < file.size;) {
        if (canceled) throw new Error('Upload canceled. No destination file was created.');
        const bytes = new Uint8Array(
          await file.slice(offset, offset + uploadChunkSize(transfer)).arrayBuffer(),
        );
        const result = await rpc<{ offset: number }>(targetHost, 'upload_chunk', {
          id: transfer.id,
          offset,
          data: encodeChunk(bytes),
        });
        offset = result.offset;
        progress = file.size ? (offset / file.size) * 100 : 100;
      }
      if (canceled) throw new Error('Upload canceled.');
      const result = await rpc<{ path: string }>(targetHost, 'upload_finish', { id: transfer.id });
      uploaded = result.path;
      progress = 100;
      message = 'Upload complete. The terminal has not received any input.';
      storageRefresh++;
    } finally {
      await rpc(targetHost, 'upload_abort', { id: transfer.id }).catch(() => {});
    }
  }
</script>

{#if error}<p class="file-error" role="alert">{error}</p>{/if}
{#if message}<p role="status">{message}</p>{/if}
<details bind:open={pickerOpen}>
  {#if mode === 'documents'}<summary>Choose host and file</summary>{/if}
  <p class="hint">
    Files are accessed on the selected host. A terminal's nested SSH connection does not change this
    selection.
  </p>
  <label
    >Host<select bind:value={hostId} disabled={working || dirty} onchange={changeHost}>
      {#each hosts as host}<option value={host.id} disabled={connected[host.id] !== 'Connected'}
          >{host.name}{connected[host.id] !== 'Connected' ? ' (not connected)' : ''}</option
        >{/each}
    </select></label
  >
  <label
    >{mode === 'upload' ? 'Destination directory' : 'Repository or directory'}<input
      bind:value={folder}
      disabled={working || dirty}
      placeholder="/home/user/project"
    /></label
  >
  {#if mode === 'upload'}
    <label
      >Local file or screenshot<input
        type="file"
        disabled={working}
        onchange={(e) => {
          selected = e.currentTarget.files?.[0] || null;
          filename = selected?.name || '';
          uploaded = '';
        }}
      /></label
    >
    <label>Destination filename<input bind:value={filename} disabled={working} /></label>
    <p class="hint">
      {hosts.find((h) => h.id === hostId)?.name}: {folder.replace(/\/$/, '')}/{filename || '…'} · Existing
      files will not be overwritten. Maximum 256 MiB.
    </p>
    <div class="upload-capacity" aria-live="polite">
      {#if storageChecking}<p role="status">Checking destination storage…</p>
      {:else if storageError}<p class="file-error" role="alert">{storageError}</p>
      {:else if capacity}<p>
          Available destination storage: <strong>{bytesLabel(capacity.availableBytes)}</strong>
          ({bytesLabel(capacity.freeBytes)} free; {bytesLabel(capacity.reservedBytes)} reserved by other
          uploads).
        </p>
        <p class="hint">
          Checked directory: {capacity.directory}. Upload limit: {bytesLabel(uploadLimit)}. Space is
          checked again before transferring.
        </p>
      {/if}
      {#if selected}<p>Selected file: {bytesLabel(selected.size)}.</p>{/if}
      {#if tooLarge}<p class="file-error" role="alert">
          This file exceeds the {bytesLabel(uploadLimit)} upload limit. No data will be transferred.
        </p>
      {:else if insufficientStorage}<p class="file-error" role="alert">
          Not enough destination storage for this file and the required safety margin. Free space or
          choose another directory, then refresh storage.
        </p>{/if}
      <button
        disabled={working || storageChecking || !folder.trim() || connected[hostId] !== 'Connected'}
        onclick={() => storageRefresh++}>Refresh storage</button
      >
    </div>
    {#if working}<progress max="100" value={progress} aria-label="Upload progress"></progress><span
        >{Math.round(progress)}%</span
      ><button onclick={() => (canceled = true)}>Cancel upload</button>{/if}
    <button
      class="primary"
      disabled={working ||
        storageChecking ||
        !capacity ||
        tooLarge ||
        insufficientStorage ||
        !selected ||
        !filename ||
        !folder ||
        connected[hostId] !== 'Connected'}
      onclick={() => void task(upload)}>Upload file</button
    >
    {#if uploaded}<p><code>{uploaded}</code></p>
      <button
        onclick={() =>
          void task(() =>
            onpaste(shellPath(uploaded, hosts.find((h) => h.id === hostId)?.kind === 'powershell')),
          )}>Paste path for review</button
      >{/if}
  {:else}
    <p class="hint">
      Find system, user, ancestor, repository, nested and explicitly referenced Markdown/rule files.
      A discovery result does not prove an agent loaded it. Nested rules may narrow scope;
      precedence varies by agent. No file is merged or executed.
    </p>
    <button
      disabled={working || dirty || !folder || connected[hostId] !== 'Connected'}
      onclick={() => void task(discover)}>Discover documents and rules</button
    >
    <div class="document-list">
      {#each files as file}<button
          disabled={working || dirty || !file.readable}
          onclick={() => void task(() => read(file.path))}
          title={file.source ? `Referenced by ${file.source}` : file.scope}
        >
          <strong>{file.path}</strong><small
            >{hosts.find((h) => h.id === hostId)?.name} · {file.scope} · {file.writable
              ? 'Read/write'
              : 'Read only'}</small
          >
        </button>{/each}
    </div>
    <label
      >Open an image, source, Markdown or HTML file by full or relative path, or image URL<input
        bind:value={path}
        disabled={working || dirty}
      /></label
    >
    <button
      disabled={working || dirty || !path || connected[hostId] !== 'Connected'}
      onclick={() => void task(() => read(path))}>Open file</button
    >
  {/if}
</details>
{#if mode === 'documents' && image}
  <p class="image-path">
    <strong>{hosts.find((h) => h.id === hostId)?.name}</strong> · <code>{image.path}</code>
  </p>
  <button disabled={working} onclick={() => void task(() => read(image!.path))}>Reload image</button
  >
  {#key image}<ImageViewer src={image.src} path={image.path} />{/key}
{/if}
{#if mode === 'documents' && document}
  <p>
    <strong>{hosts.find((h) => h.id === hostId)?.name}</strong> · <code>{document.path}</code>
  </p>
  <div class="form-row">
    <button aria-pressed={!preview} onclick={() => (preview = false)}>Source</button>
    {#if kind}<button aria-pressed={preview} onclick={() => (preview = true)}
        >Preview {kind === 'html' ? 'HTML' : 'Markdown'}</button
      >{/if}
    {#if document.writable}<button
        aria-pressed={editing}
        disabled={working}
        onclick={() => {
          editing = !editing;
          preview = false;
        }}>{editing ? 'Finish editing' : 'Edit source'}</button
      >{/if}
    <button disabled={working || dirty} onclick={() => void task(() => read(document!.path))}
      >Reload from host</button
    >
    <button
      class="primary"
      disabled={working || !dirty || !document.writable}
      onclick={() => void task(save)}>Save file</button
    >
    {#if dirty}<button
        disabled={working}
        onclick={() => {
          draft = document!.text;
          error = '';
        }}>Discard edits</button
      ><button onclick={() => void task(() => navigator.clipboard.writeText(draft))}
        >Copy edits</button
      >{/if}
  </div>
  {#if dirty}<p role="status">
      Unsaved edits. Save or discard before closing or choosing another file.
    </p>{/if}
  <p class="hint">
    {editing ? 'Editing' : 'Read only'} · UTF-8 text, up to 512 KiB. Ctrl+F to find, Ctrl+G to go to a
    line. Tab moves to the next control.
  </p>
  {#if preview && kind === 'markdown'}<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
    <article
      class="markdown-preview"
      onclick={(event) => openPreviewLink(event, (e) => (error = String(e)))}
    >
      <!-- eslint-disable-next-line svelte/no-at-html-tags -- markdownPreview escapes its input -->
      {@html markdownPreview(draft)}
    </article>
  {:else if preview && kind === 'html'}<HtmlPreview source={draft} />
  {/if}
  <div hidden={preview}>
    {#key `${hostId}:${document.path}`}<SourceViewer
        path={document.path}
        value={draft}
        readonly={!editing || !document.writable || working}
        onchange={(text) => (draft = text)}
      />{/key}
  </div>
{/if}

<style>
  .image-path {
    overflow-wrap: anywhere;
  }
  summary {
    cursor: pointer;
    padding: 8px 0;
    margin-bottom: 8px;
  }
</style>
