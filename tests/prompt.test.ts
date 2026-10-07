import { describe, expect, it } from 'vitest';
import {
  renderPrompt,
  unknownVariables,
  defaultPrompt,
  type PromptValues,
} from '../src/lib/prompt';
import { webUrl } from '../src/lib/terminals';
const values: PromptValues = {
  host: 'Dev',
  address: 'dev',
  user: 'user',
  repo: '/repo/{{host}}',
  identity: 'example-user',
  agent: 'codex',
  model: 'default',
  effort: 'high',
  guidance: 'Wait.',
};
describe('repository prompt templates', () => {
  it('inserts values literally without recursive expansion or shell execution', () => {
    expect(renderPrompt('{{host}} {{repo}} $(touch nope) {{unknown}}', values)).toBe(
      'Dev /repo/{{host}} $(touch nope) {{unknown}}',
    );
  });
  it('flags unknown variables and preserves an intentionally empty template', () => {
    expect(unknownVariables('{{repo}} {{typo}} {{typo}}')).toEqual(['typo']);
    expect(renderPrompt('', values)).toBe('');
    expect(renderPrompt(defaultPrompt, values)).toContain('GitHub identity example-user');
  });
});
describe('terminal external links', () => {
  it('allows only web URLs, never executable or local file protocols', () => {
    expect(webUrl('https://example.com/login?code=a')).toBe('https://example.com/login?code=a');
    for (const u of [
      'javascript:alert(1)',
      'file:///etc/passwd',
      'data:text/plain,hi',
      'not a URL',
    ])
      expect(webUrl(u)).toBeNull();
  });
});
