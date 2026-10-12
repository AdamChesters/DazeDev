import content from './content.json';
import manifest from './manifest.json';
export { content, manifest };
export const identity = { appName: 'DazeDev', appId: 'DazeDev' };
const assets = import.meta.glob<string>('./assets/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
});
export const artwork = Object.fromEntries(
  Object.entries(assets).map(([path, url]) => [path.slice(2), url]),
);
export function appText(text: string, appName = identity.appName): string {
  return text.replaceAll('{appName}', appName);
}
