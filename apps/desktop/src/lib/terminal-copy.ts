type CopyKey = Pick<
  KeyboardEvent,
  | 'type'
  | 'code'
  | 'ctrlKey'
  | 'metaKey'
  | 'altKey'
  | 'shiftKey'
  | 'repeat'
  | 'preventDefault'
  | 'stopPropagation'
>;

export function copySelectedTerminalText(
  event: CopyKey,
  selection: string,
  copy: (text: string) => Promise<void>,
  report: (message: string) => void,
): boolean {
  if (
    event.type !== 'keydown' ||
    event.code !== 'KeyC' ||
    event.altKey ||
    !(event.ctrlKey || event.metaKey) ||
    (!selection && !event.shiftKey)
  )
    return false;
  event.preventDefault();
  event.stopPropagation();
  if (!event.repeat && selection) {
    void Promise.resolve()
      .then(() => copy(selection))
      .catch(() =>
        report('Could not copy the selection. It is still selected; use Copy to try again.'),
      );
  }
  return true;
}
