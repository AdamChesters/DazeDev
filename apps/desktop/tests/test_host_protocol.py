import ast, importlib.util, pathlib, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
HOST = ROOT / 'src-tauri/resources/host.py'
BROWSER = ROOT / 'src-tauri/resources/browser.py'
spec = importlib.util.spec_from_file_location('host_protocol', HOST); host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)

def dispatched_methods():
    # Method names compared against `method` or used as file_methods keys in dispatch().
    tree = ast.parse(HOST.read_text())
    func = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'dispatch')
    names = set()
    for node in ast.walk(func):
        if isinstance(node, ast.Compare) and isinstance(node.left, ast.Name) and node.left.id == 'method':
            for value in node.comparators:
                if isinstance(value, ast.Constant) and isinstance(value.value, str): names.add(value.value)
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'file_methods' for t in node.targets):
            names.update(k.value for k in node.value.keys)
    return names

class LaneTests(unittest.TestCase):
    def test_every_lane_and_deadline_name_is_dispatched(self):
        methods = dispatched_methods()
        self.assertIn('session_context', methods)
        for table in (host.INLINE_METHODS, host.READ_METHODS, host.SHORT_DEADLINE_METHODS):
            self.assertEqual(set(table) - methods, set())

    def test_read_only_methods_use_read_lane(self):
        for method in ['session_context', 'repositories', 'tmux_settings_read', 'process_context', 'terminal_status',
                       'image_read', 'document_read', 'dictionary_read', 'upload_preflight', 'file_read', 'documents',
                       'sessions', 'session_agent', 'profile_load', 'capabilities', 'inventory', 'repository_worktrees']:
            self.assertEqual(host.lane_for(method), 'read', method)
        for method in ['create', 'rename', 'destroy', 'clone', 'worktree', 'profile_save', 'upload_begin', 'upload_chunk',
                       'upload_finish', 'upload_abort', 'document_save', 'dictionary_write', 'tmux_settings_apply',
                       'tmux_window_select', 'terminal_recover']:
            self.assertEqual(host.lane_for(method), 'mutation', method)
        for method in ['ping', 'browser_prepare', 'browser_result']:
            self.assertEqual(host.lane_for(method), 'inline', method)

    def test_repositories_gets_short_deadline(self):
        self.assertIn('repositories', host.SHORT_DEADLINE_METHODS)
        self.assertNotIn('clone', host.SHORT_DEADLINE_METHODS)

class PythonVersionTests(unittest.TestCase):
    def test_sources_parse_as_python_3_9(self):
        for path in (HOST, BROWSER):
            ast.parse(path.read_text(), str(path), feature_version=(3, 9))
        # Code that runs in a child interpreter on the host.
        ast.parse(host.INSTALLER, '<installer>', feature_version=(3, 9))

    def test_no_stdlib_calls_newer_than_3_9(self):
        newer = ['zip(strict', 'contextlib.chdir', 'datetime.UTC', 'tomllib', 'itertools.pairwise', 'bit_count(',
                 'aiter(', 'anext(', 'TaskGroup', 'ExceptionGroup', 'except*', 'Path.walk', '.is_junction(', 'from typing import Self']
        for path in (HOST, BROWSER):
            text = path.read_text()
            for name in newer: self.assertNotIn(name, text, str(path))

if __name__ == '__main__': unittest.main()
