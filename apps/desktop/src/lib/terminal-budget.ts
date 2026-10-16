// One memory budget for all terminals mounted in a window. Each terminal gets
// a share of the scrollback lines and image cache; a hidden terminal counts as
// a quarter, so visible ones keep more history.

export const scrollbackBudget = 200000;
export const imageBudgetMb = 128;
/** Upper limit per terminal, however few terminals are mounted. */
export const maxImageMb = 32;
const minScrollback = 100;
const hiddenWeight = 0.25;

export interface BudgetNeed {
  hidden: boolean;
  /** The user's scrollback setting, already clamped. */
  scrollback: number;
}
export interface BudgetShare {
  scrollback: number;
  imageMb: number;
}

export function budgetShares(
  needs: BudgetNeed[],
  lines = scrollbackBudget,
  imagesMb = imageBudgetMb,
): BudgetShare[] {
  const weights = needs.map((need) => (need.hidden ? hiddenWeight : 1));
  const total = weights.reduce((sum, weight) => sum + weight, 0);
  return needs.map((need, i) => ({
    scrollback: Math.min(
      need.scrollback,
      Math.max(minScrollback, Math.floor((lines * weights[i]) / total)),
    ),
    imageMb: Math.max(1, Math.min(maxImageMb, Math.floor((imagesMb * weights[i]) / total))),
  }));
}

export interface BudgetMember {
  /** Updates this terminal's need; every terminal's share is recomputed. */
  update(need: BudgetNeed): void;
  remove(): void;
}

export class TerminalBudget {
  private members = new Map<
    object,
    { need: BudgetNeed; apply: (share: BudgetShare) => void; share?: BudgetShare }
  >();
  constructor(
    private lines = scrollbackBudget,
    private imagesMb = imageBudgetMb,
  ) {}
  add(need: BudgetNeed, apply: (share: BudgetShare) => void): BudgetMember {
    const key = {};
    this.members.set(key, { need, apply });
    this.recompute();
    return {
      update: (next) => {
        const member = this.members.get(key);
        if (!member) return;
        if (member.need.hidden === next.hidden && member.need.scrollback === next.scrollback)
          return;
        member.need = next;
        this.recompute();
      },
      remove: () => {
        if (this.members.delete(key)) this.recompute();
      },
    };
  }
  private recompute() {
    const members = [...this.members.values()];
    const shares = budgetShares(
      members.map((member) => member.need),
      this.lines,
      this.imagesMb,
    );
    members.forEach((member, i) => {
      const share = shares[i];
      if (member.share?.scrollback === share.scrollback && member.share.imageMb === share.imageMb)
        return;
      member.share = share;
      member.apply(share);
    });
  }
}

/** The budget shared by the terminals in this window. */
export const terminalBudget = new TerminalBudget();
