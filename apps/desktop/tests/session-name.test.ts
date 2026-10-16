import { describe, expect, it } from 'vitest';
import { sessionNameError } from '../src/lib/session-name';
describe('session names', () => {
  it('rejects errors at the first step including spaces, separators and shell characters', () => {
    for (const s of ['', 'repo.work', 'name:1', 'two words', 'foo/bar', '$foo', 'name\n'])
      expect(sessionNameError(s)).not.toBe('');
    expect(sessionNameError('codex-user_2')).toBe('');
  });
});
