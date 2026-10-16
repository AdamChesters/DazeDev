import { describe, expect, it, vi } from 'vitest';
import { deliverDictation } from '../src/lib/terminals';

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
});
