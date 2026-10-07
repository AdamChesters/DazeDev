<script lang="ts">
  import DOMPurify from 'dompurify';
  let { source }: { source: string } = $props();
  const markup = $derived(
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'\"></head><body>" +
      DOMPurify.sanitize(source, {
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
      }) +
      '</body></html>',
  );
</script>

<p class="hint">
  Static HTML preview. Scripts, links and external files are disabled. Inline CSS is supported.
</p>
<iframe title="HTML preview" sandbox="" referrerpolicy="no-referrer" srcdoc={markup}></iframe>

<style>
  iframe {
    width: 100%;
    height: min(55dvh, 640px);
    min-height: 260px;
    background: white;
    border: 1px solid var(--tint-border);
  }
</style>
