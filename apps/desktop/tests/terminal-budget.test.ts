import { describe, expect, it, vi } from 'vitest';
import { TerminalBudget, budgetShares } from '../src/lib/terminal-budget';

const visible = (scrollback = 100000) => ({ hidden: false, scrollback });
const hidden = (scrollback = 100000) => ({ hidden: true, scrollback });

describe('terminal memory budget', () => {
  it('gives each terminal the smaller of its setting and an equal share', () => {
    expect(budgetShares([visible(10000)])).toEqual([{ scrollback: 10000, imageMb: 32 }]);
    expect(budgetShares([visible(), visible(), visible(), visible()])).toEqual(
      Array(4).fill({ scrollback: 50000, imageMb: 32 }),
    );
    const eight = budgetShares(Array.from({ length: 8 }, () => visible()));
    expect(eight[0]).toEqual({ scrollback: 25000, imageMb: 16 });
  });
  it('gives a hidden terminal a quarter of a visible share and stays within the budget', () => {
    const shares = budgetShares([visible(), visible(), hidden(), hidden(), hidden(), hidden()]);
    // Weights 1 + 1 + 4 × 0.25 = 3.
    expect(shares[0]).toEqual({ scrollback: 66666, imageMb: 32 });
    expect(shares[2]).toEqual({ scrollback: 16666, imageMb: 10 });
    expect(shares.reduce((sum, share) => sum + share.scrollback, 0)).toBeLessThanOrEqual(200000);
    expect(shares.reduce((sum, share) => sum + share.imageMb, 0)).toBeLessThanOrEqual(128);
  });
  it('keeps at least 100 lines and 1 MB however many terminals there are', () => {
    const shares = budgetShares(Array.from({ length: 5000 }, () => hidden()));
    expect(shares[0]).toEqual({ scrollback: 100, imageMb: 1 });
  });
  it('recomputes shares as terminals mount, hide and unmount', () => {
    const budget = new TerminalBudget(1000, 64);
    const a = vi.fn();
    const b = vi.fn();
    const first = budget.add(visible(), a);
    expect(a).toHaveBeenLastCalledWith({ scrollback: 1000, imageMb: 32 });
    const second = budget.add(visible(), b);
    expect(a).toHaveBeenLastCalledWith({ scrollback: 500, imageMb: 32 });
    second.update(hidden());
    expect(a).toHaveBeenLastCalledWith({ scrollback: 800, imageMb: 32 });
    expect(b).toHaveBeenLastCalledWith({ scrollback: 200, imageMb: 12 });
    const calls = a.mock.calls.length;
    second.update(hidden());
    expect(a).toHaveBeenCalledTimes(calls);
    second.remove();
    expect(a).toHaveBeenLastCalledWith({ scrollback: 1000, imageMb: 32 });
    first.remove();
  });
});
