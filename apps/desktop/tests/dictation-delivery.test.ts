import { describe, expect, it, vi } from 'vitest';
import { deliverDictation, reviewPasteText } from '../src/lib/terminals';

describe('dictation delivery', () => {
  it('pastes without consulting unavailable or changing foreground context', async () => {
    const terminal = {
      pasteDictation: vi.fn().mockResolvedValue(undefined),
      context: vi.fn(() => {
        throw new Error('Agent awaiting an answer');
      }),
      processId: vi.fn(() => {
        throw new Error('Context unavailable');
      }),
    };
    await deliverDictation(terminal, 'My answer', false);
    expect(terminal.pasteDictation).toHaveBeenCalledExactlyOnceWith('My answer', false);
    expect(terminal.context).not.toHaveBeenCalled();
    expect(terminal.processId).not.toHaveBeenCalled();
  });
  it('preserves explicit automatic submission and propagates actual delivery failures', async () => {
    const pasteDictation = vi.fn().mockRejectedValue(new Error('Disconnected'));
    await expect(deliverDictation({ pasteDictation }, 'Answer', true)).rejects.toThrow(
      'Disconnected',
    );
    expect(pasteDictation).toHaveBeenCalledExactlyOnceWith('Answer', true);
  });
  it('pastes multiline speech without executing shell lines when bracketed paste is unavailable', () => {
    expect(reviewPasteText('First line\nSecond line', false)).toBe('First line Second line');
    expect(reviewPasteText('First line\nSecond line', true)).toBe('First line\nSecond line');
  });
});
