import * as monaco from 'monaco-editor/editor';
import 'monaco-editor/features/find/register';
import 'monaco-editor/editor/contrib/clipboard/browser/clipboard';
import 'monaco-editor/editor/contrib/bracketMatching/browser/bracketMatching';
import 'monaco-editor/editor/contrib/contextmenu/browser/contextmenu';
import 'monaco-editor/editor/contrib/folding/browser/folding';
import 'monaco-editor/editor/contrib/links/browser/links';
import 'monaco-editor/editor/contrib/wordOperations/browser/wordOperations';
import 'monaco-editor/editor/contrib/tokenization/browser/tokenization';
import { languageModules, languageTable, languageForPath } from './monaco-languages';

// Workers start only when Monaco first asks for one.
self.MonacoEnvironment = {
  async getWorker(_id, label) {
    if (label === 'json')
      return new (await import('monaco-editor/language/json/json.worker?worker')).default();
    if (['css', 'scss', 'less'].includes(label))
      return new (await import('monaco-editor/language/css/css.worker?worker')).default();
    if (['html', 'handlebars', 'razor'].includes(label))
      return new (await import('monaco-editor/language/html/html.worker?worker')).default();
    return new (await import('monaco-editor/editor/editor.worker?worker')).default();
  },
};

const loading = new Map<string, Promise<void>>();
function once(key: string, load: () => Promise<unknown>) {
  if (!loading.has(key))
    loading.set(
      key,
      load().then(
        () => {},
        (error) => {
          loading.delete(key);
          throw error;
        },
      ),
    );
  return loading.get(key)!;
}
// Register a language (and its language service) the first time it is used.
export async function loadLanguage(id: string): Promise<void> {
  const entry = languageTable.find(([language]) => language === id);
  if (!entry || !entry[1]) return;
  if (entry[1] === 'json')
    return once('json', async () => {
      const { jsonDefaults } = await import('monaco-editor/languages/features/json/register');
      // Viewing a repository must not fetch arbitrary $schema URLs.
      jsonDefaults.setDiagnosticsOptions({ validate: true, enableSchemaRequest: false });
    });
  await once(entry[1], languageModules[entry[1]]);
  if (['css', 'scss', 'less'].includes(id))
    await once('css-service', () => import('monaco-editor/languages/features/css/register'));
  if (['html', 'handlebars', 'razor'].includes(id))
    await once('html-service', () => import('monaco-editor/languages/features/html/register'));
}
export { languageForPath, monaco };
