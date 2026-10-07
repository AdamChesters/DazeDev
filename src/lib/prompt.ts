export const promptVariables = [
  'host',
  'address',
  'user',
  'repo',
  'identity',
  'agent',
  'model',
  'effort',
  'guidance',
] as const;
export type PromptValues = Record<(typeof promptVariables)[number], string>;
export const defaultPrompt = `Work in {{repo}} on {{host}}, using GitHub identity {{identity}}. Read applicable repository instructions. Inspect existing checkouts, worktrees, branch, dirty state, relevant handoff/status docs and accessible PR/CI status. Preserve ongoing work. Give at most five concise bullets covering current state, unfinished work, blockers and the next useful action. Then be ready for my brief. Do not start implementation or create a branch/worktree until requested.
{{guidance}}`;
export function renderPrompt(template: string, values: PromptValues): string {
  // One pass: variable contents are literal, never recursively expanded or executed.
  return template.replace(/\{\{([a-z]+)\}\}/g, (token, name: string) =>
    Object.hasOwn(values, name) ? values[name as keyof PromptValues] : token,
  );
}
export function unknownVariables(template: string): string[] {
  return [...new Set([...template.matchAll(/\{\{([^{}]+)\}\}/g)].map((m) => m[1]))].filter(
    (v) => !(promptVariables as readonly string[]).includes(v),
  );
}
