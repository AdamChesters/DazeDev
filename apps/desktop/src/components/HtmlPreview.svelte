<script lang="ts">
  import DOMPurify from 'dompurify';
  import { localImageSource } from '../lib/images';
  import RemoteImage from './RemoteImage.svelte';
  let { source }: { source: string } = $props();
  // Remote images are never fetched; each one is listed below the preview.
  function sanitize(value: string) {
    const remote: string[] = [];
    DOMPurify.addHook('afterSanitizeAttributes', (node) => {
      if (node.nodeName !== 'IMG') return;
      const src = node.getAttribute('src') || '';
      if (!src || localImageSource(src)) return;
      node.removeAttribute('src');
      node.setAttribute('alt', `Remote image not loaded: ${src}`);
      if (!remote.includes(src)) remote.push(src);
    });
    try {
      const html = DOMPurify.sanitize(value, {
        WHOLE_DOCUMENT: false,
        FORCE_BODY: true,
        ADD_TAGS: ['style'],
        FORBID_TAGS: [
          'script',
          'iframe',
          'object',
          'embed',
          'form',
          'input',
          'button',
          'textarea',
          'select',
          'meta',
          'link',
          'base',
        ],
        FORBID_ATTR: ['href', 'xlink:href', 'action', 'formaction', 'srcset', 'target'],
      });
      return { html, remote };
    } finally {
      DOMPurify.removeHook('afterSanitizeAttributes');
    }
  }
  const sanitized = $derived(sanitize(source));
  const markup = $derived(
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'\"></head><body>" +
      sanitized.html +
      '</body></html>',
  );
</script>

<p class="hint">
  Static HTML preview. Scripts, links and external files are disabled. Inline CSS is supported.
</p>
<iframe title="HTML preview" sandbox="" referrerpolicy="no-referrer" srcdoc={markup}></iframe>
{#each sanitized.remote as url}<RemoteImage {url} />{/each}

<style>
  iframe {
    width: 100%;
    height: min(55dvh, 640px);
    min-height: 260px;
    background: white;
    border: 1px solid var(--tint-border);
  }
</style>
