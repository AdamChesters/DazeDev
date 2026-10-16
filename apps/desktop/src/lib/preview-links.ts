import { openExternal } from './bridge';
import { webUrl } from './terminals';

// Click handler for previews: buttons marked data-open-url open that web
// address in the user's browser through the checked external-link path.
export function openPreviewLink(event: MouseEvent, fail: (error: unknown) => void) {
  const marked = (event.target as Element | null)?.closest?.('[data-open-url]');
  const url = webUrl(marked?.getAttribute('data-open-url') || '');
  if (!url) return;
  event.preventDefault();
  void openExternal(url).catch(fail);
}
