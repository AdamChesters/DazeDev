import { expect, it } from 'vitest';
import { languageForPath, languageModules, languageTable } from '../src/lib/monaco-languages';

it('finds languages by file name and extension without loading Monaco', () => {
  expect(languageForPath('/repo/src/main.py')).toBe('python');
  expect(languageForPath('C:\\repo\\Dockerfile')).toBe('dockerfile');
  expect(languageForPath('notes.MD')).toBe('markdown');
  expect(languageForPath('.cursorrules')).toBe('markdown');
  expect(languageForPath('package.json')).toBe('json');
  expect(languageForPath('unknown.zzz')).toBe('plaintext');
});
it('has a loader for every language that needs one', () => {
  for (const [id, module] of languageTable)
    if (module && module !== 'json') expect(languageModules[module], id).toBeTypeOf('function');
});
