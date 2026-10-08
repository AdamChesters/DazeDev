<script lang="ts">
  import { credits } from '../lib/credits';
  import { openExternal } from '../lib/bridge';
  import { desktop } from '../lib/bridge';
  const notices = import.meta.glob<string>('../../src-tauri/licenses/*.txt', {
    query: '?raw',
    import: 'default',
  });
  const paths = Object.keys(notices).sort();
  let selected = $state('');
  let text = $state('');
  let error = $state('');
  let loading = $state(false);
  let request = 0;
  async function read(path: string) {
    selected = path;
    const id = ++request;
    loading = true;
    error = '';
    text = '';
    try {
      const value = await notices[path]();
      if (request === id) text = value;
    } catch (e) {
      if (request === id) error = String(e);
    } finally {
      if (request === id) loading = false;
    }
  }
  async function visit(event: MouseEvent, url: string) {
    if (!desktop) return;
    event.preventDefault();
    try {
      await openExternal(url);
    } catch (e) {
      error = String(e);
    }
  }
</script>

<section aria-label="Licenses and credits">
  <h3>Licenses and credits</h3>
  <p>
    DazeDev uses these projects. Their authors retain ownership and credit. Voice models are
    optional downloads.
  </p>
  <ul class="credits">
    {#each credits as item}<li>
        <a
          href={item.url}
          target="_blank"
          rel="noopener noreferrer"
          onclick={(e) => void visit(e, item.url)}>{item.name}</a
        ><span>{item.by} · {item.license}</span>
      </li>{/each}
  </ul>
  <p class="hint">
    Parakeet: Licensed by NVIDIA Corporation under the NVIDIA Open Model License. GGUF conversions
    retain their upstream model terms.
  </p>
  <label
    >Full license text (available offline)<select
      value={selected}
      onchange={(e) => void read(e.currentTarget.value)}
    >
      <option value="" disabled>Select a notice</option>
      {#each paths as path}<option value={path}>{path.split('/').at(-1)}</option>{/each}
    </select></label
  >
  {#if loading}<p role="status">Loading notice…</p>{/if}
  {#if error}<p role="alert">{error}</p>{/if}
  {#if text}<textarea
      readonly
      aria-label="Full license text"
      value={text}
      rows="18"
      spellcheck="false"></textarea>{/if}
</section>

<style>
  .credits {
    padding-left: 20px;
  }
  li {
    margin: 10px 0;
  }
  li span {
    display: block;
    font-size: 13px;
    opacity: 0.85;
  }
  a {
    color: var(--accent, #9bc9f0);
    text-decoration: underline;
  }
  textarea {
    width: 100%;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    max-height: 45dvh;
    overflow: auto;
    padding: 12px;
    border: 1px solid var(--tint-border);
    font-size: 12px;
  }
</style>
