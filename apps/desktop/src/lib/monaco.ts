import * as monaco from 'monaco-editor/editor';
import 'monaco-editor/features/find/register';
import 'monaco-editor/editor/contrib/clipboard/browser/clipboard';
import 'monaco-editor/editor/contrib/bracketMatching/browser/bracketMatching';
import 'monaco-editor/editor/contrib/contextmenu/browser/contextmenu';
import 'monaco-editor/editor/contrib/folding/browser/folding';
import 'monaco-editor/editor/contrib/links/browser/links';
import 'monaco-editor/editor/contrib/wordOperations/browser/wordOperations';
import 'monaco-editor/editor/contrib/tokenization/browser/tokenization';
import { jsonDefaults } from 'monaco-editor/languages/features/json/register';
import 'monaco-editor/languages/features/css/register';
import 'monaco-editor/languages/features/html/register';
import 'monaco-editor/languages/definitions/abap/register';
import 'monaco-editor/languages/definitions/apex/register';
import 'monaco-editor/languages/definitions/azcli/register';
import 'monaco-editor/languages/definitions/bat/register';
import 'monaco-editor/languages/definitions/bicep/register';
import 'monaco-editor/languages/definitions/cameligo/register';
import 'monaco-editor/languages/definitions/clojure/register';
import 'monaco-editor/languages/definitions/coffee/register';
import 'monaco-editor/languages/definitions/cpp/register';
import 'monaco-editor/languages/definitions/csharp/register';
import 'monaco-editor/languages/definitions/csp/register';
import 'monaco-editor/languages/definitions/css/register';
import 'monaco-editor/languages/definitions/cypher/register';
import 'monaco-editor/languages/definitions/dart/register';
import 'monaco-editor/languages/definitions/dockerfile/register';
import 'monaco-editor/languages/definitions/ecl/register';
import 'monaco-editor/languages/definitions/elixir/register';
import 'monaco-editor/languages/definitions/flow9/register';
import 'monaco-editor/languages/definitions/freemarker2/register';
import 'monaco-editor/languages/definitions/fsharp/register';
import 'monaco-editor/languages/definitions/go/register';
import 'monaco-editor/languages/definitions/graphql/register';
import 'monaco-editor/languages/definitions/handlebars/register';
import 'monaco-editor/languages/definitions/hcl/register';
import 'monaco-editor/languages/definitions/html/register';
import 'monaco-editor/languages/definitions/ini/register';
import 'monaco-editor/languages/definitions/java/register';
import 'monaco-editor/languages/definitions/javascript/register';
import 'monaco-editor/languages/definitions/julia/register';
import 'monaco-editor/languages/definitions/kotlin/register';
import 'monaco-editor/languages/definitions/less/register';
import 'monaco-editor/languages/definitions/lexon/register';
import 'monaco-editor/languages/definitions/liquid/register';
import 'monaco-editor/languages/definitions/lua/register';
import 'monaco-editor/languages/definitions/m3/register';
import 'monaco-editor/languages/definitions/markdown/register';
import 'monaco-editor/languages/definitions/mdx/register';
import 'monaco-editor/languages/definitions/mips/register';
import 'monaco-editor/languages/definitions/msdax/register';
import 'monaco-editor/languages/definitions/mysql/register';
import 'monaco-editor/languages/definitions/objective-c/register';
import 'monaco-editor/languages/definitions/pascal/register';
import 'monaco-editor/languages/definitions/pascaligo/register';
import 'monaco-editor/languages/definitions/perl/register';
import 'monaco-editor/languages/definitions/pgsql/register';
import 'monaco-editor/languages/definitions/php/register';
import 'monaco-editor/languages/definitions/pla/register';
import 'monaco-editor/languages/definitions/postiats/register';
import 'monaco-editor/languages/definitions/powerquery/register';
import 'monaco-editor/languages/definitions/powershell/register';
import 'monaco-editor/languages/definitions/protobuf/register';
import 'monaco-editor/languages/definitions/pug/register';
import 'monaco-editor/languages/definitions/python/register';
import 'monaco-editor/languages/definitions/qsharp/register';
import 'monaco-editor/languages/definitions/r/register';
import 'monaco-editor/languages/definitions/razor/register';
import 'monaco-editor/languages/definitions/redis/register';
import 'monaco-editor/languages/definitions/redshift/register';
import 'monaco-editor/languages/definitions/restructuredtext/register';
import 'monaco-editor/languages/definitions/ruby/register';
import 'monaco-editor/languages/definitions/rust/register';
import 'monaco-editor/languages/definitions/sb/register';
import 'monaco-editor/languages/definitions/scala/register';
import 'monaco-editor/languages/definitions/scheme/register';
import 'monaco-editor/languages/definitions/scss/register';
import 'monaco-editor/languages/definitions/shell/register';
import 'monaco-editor/languages/definitions/solidity/register';
import 'monaco-editor/languages/definitions/sophia/register';
import 'monaco-editor/languages/definitions/sparql/register';
import 'monaco-editor/languages/definitions/sql/register';
import 'monaco-editor/languages/definitions/st/register';
import 'monaco-editor/languages/definitions/swift/register';
import 'monaco-editor/languages/definitions/systemverilog/register';
import 'monaco-editor/languages/definitions/tcl/register';
import 'monaco-editor/languages/definitions/twig/register';
import 'monaco-editor/languages/definitions/typescript/register';
import 'monaco-editor/languages/definitions/typespec/register';
import 'monaco-editor/languages/definitions/vb/register';
import 'monaco-editor/languages/definitions/wgsl/register';
import 'monaco-editor/languages/definitions/xml/register';
import 'monaco-editor/languages/definitions/yaml/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import JsonWorker from 'monaco-editor/language/json/json.worker?worker';
import CssWorker from 'monaco-editor/language/css/css.worker?worker';
import HtmlWorker from 'monaco-editor/language/html/html.worker?worker';

self.MonacoEnvironment = {
  getWorker(_id, label) {
    if (label === 'json') return new JsonWorker();
    if (['css', 'scss', 'less'].includes(label)) return new CssWorker();
    if (['html', 'handlebars', 'razor'].includes(label)) return new HtmlWorker();
    return new EditorWorker();
  },
};
// Viewing a repository must not fetch arbitrary $schema URLs.
jsonDefaults.setDiagnosticsOptions({ validate: true, enableSchemaRequest: false });

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
