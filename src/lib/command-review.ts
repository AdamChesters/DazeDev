import type { Action } from './commands';
export interface CommandContext {
  hostId: string;
  hostName: string;
  workspaceId: string;
  workspaceName: string;
  paneId: string;
  paneName: string;
  paneContext: string;
}
export interface CommandReview {
  text: string;
  context: CommandContext;
  action: Action;
  summary: string;
  consumed: boolean;
}
export function reviewCommand(
  text: string,
  action: Action,
  context: CommandContext,
  hostName = context.hostName,
): CommandReview {
  let summary: string;
  switch (action.kind) {
    case 'new':
      if (action.destination === 'current' && !context.workspaceId)
        throw new Error('Open a workspace first, or say “open a new workspace”.');
      summary = `Open ${action.count} ${action.count === 1 ? 'terminal' : 'terminals'} ${action.destination === 'current' ? `in workspace “${context.workspaceName}”` : `in a new workspace “${action.name}”`} on ${hostName}. ${action.agent === 'shell' ? 'Use shells.' : `Start ${action.agent} in each terminal, in separate tmux sessions.`} ${action.chooseRepo ? 'Choose a repository before starting.' : action.repo ? `Repository: ${action.repo}.` : 'Use the host home directory.'}${action.model ? ` Model: ${action.model}.` : ''}${action.effort ? ` Effort: ${action.effort}.` : ''}${action.identity ? ` Identity: ${action.identity}.` : ''} The launch form lets you review these options.`;
      break;
    case 'split':
      if (!context.paneId) throw new Error('Select a terminal to split first.');
      summary = `Add one plain terminal ${action.axis === 'row' ? 'to the right of' : 'below'} “${context.paneName}” in “${context.workspaceName}”, using that terminal’s host.`;
      break;
    case 'sessions':
      summary = `Show tmux sessions for ${context.paneId ? `terminal “${context.paneName}”` : hostName}.`;
      break;
    case 'switch':
      summary = `Switch to workspace “${action.name}”.`;
      break;
    case 'rename':
    case 'destroy':
      if (!context.paneId) throw new Error('Select a terminal first.');
      summary =
        action.kind === 'rename'
          ? `Open the rename form for “${context.paneName}”, with name “${action.name}”.`
          : `Open the confirmation to destroy the tmux session in “${context.paneName}”.`;
      break;
    case 'worktree':
      summary = `Open the worktree form for branch “${action.branch}” on ${hostName}.`;
      break;
  }
  return { text, action, context: { ...context }, summary, consumed: false };
}
// Consume before awaiting any effect: double clicks and re-entrant events cannot replay a command.
export function consumeReview(
  review: CommandReview,
  text: string,
  context: CommandContext,
): Action {
  if (review.consumed) throw new Error('This command has already been implemented.');
  if (review.text !== text || JSON.stringify(review.context) !== JSON.stringify(context))
    throw new Error('The command or destination changed. Interpret it again before implementing.');
  review.consumed = true;
  return review.action;
}
