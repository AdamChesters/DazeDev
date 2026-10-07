import * as monaco from 'monaco-editor/editor';
import 'monaco-editor/features/register.all';
import { json } from 'monaco-editor/languages/register.all';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import JsonWorker from 'monaco-editor/language/json/json.worker?worker';
import CssWorker from 'monaco-editor/language/css/css.worker?worker';
import HtmlWorker from 'monaco-editor/language/html/html.worker?worker';
import TsWorker from 'monaco-editor/language/typescript/ts.worker?worker';

self.MonacoEnvironment = {
  getWorker(_id, label) {
    if (label === 'json') return new JsonWorker();
    if (['css', 'scss', 'less'].includes(label)) return new CssWorker();
    if (['html', 'handlebars', 'razor'].includes(label)) return new HtmlWorker();
    if (['typescript', 'javascript'].includes(label)) return new TsWorker();
    return new EditorWorker();
  },
};
// Viewing a repository must not fetch arbitrary $schema URLs.
json.jsonDefaults.setDiagnosticsOptions({ validate: true, enableSchemaRequest: false });

export function languageForPath(path: string) {
  const name = path.split(/[/\\]/).at(-1) || '';
  if (/\.(mdc|markdown)$/i.test(name) || /^\.(cursor|cline)rules$/i.test(name)) return 'markdown';
  const languages = monaco.languages.getLanguages();
  return (
    languages.find((l) => l.filenames?.some((f) => f.toLowerCase() === name.toLowerCase()))?.id ||
    languages.find((l) => l.extensions?.some((e) => name.toLowerCase().endsWith(e)))?.id ||
    'plaintext'
  );
}
export { monaco };
