export type Diagnostic = { at: string; event: string; target: string; duration?: number };
const entries: Diagnostic[] = [];
export function diagnostic(event: string, target = '', duration?: number) {
  entries.push({ at: new Date().toISOString(), event, target, duration });
  if (entries.length > 200) entries.shift();
}
export function diagnosticSnapshot() {
  return entries.map((e) => ({ ...e }));
}
export function exportDiagnostics(version: string) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify({ version, events: diagnosticSnapshot() }, null, 2)], {
      type: 'application/json',
    }),
  );
  const a = document.createElement('a');
  a.href = url;
  a.download = 'dazedev-diagnostics.json';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
