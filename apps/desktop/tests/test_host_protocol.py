import ast, base64, importlib.util, io, json, os, pathlib, signal, subprocess, sys, tempfile, threading, time, unittest
from unittest.mock import patch
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
        for method in ['ping', 'browser_prepare', 'browser_result', 'editor_shown', 'editor_done']:
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

def decode(text, prefix):
    replies=[]; chunks=[]
    for line in text.splitlines():
        if not line.startswith(prefix+'@'): continue
        header,payload=line[len(prefix)+1:].split(':',1); i,total=map(int,header.split('/'))
        if i==0: chunks=[]
        chunks.append(payload)
        if i==total-1: replies.append(json.loads(base64.b64decode(''.join(chunks))))
    return replies

class BlockingOut(io.StringIO):
    def __init__(self, gate):
        super().__init__(); self.gate=gate; self.first=True; self.started=threading.Event()
    def write(self, text):
        if self.first:
            self.first=False; self.started.set(); self.gate.wait(5)
        return super().write(text)

class ReplyWriterTests(unittest.TestCase):
    prefix='DAZED:fixture:'
    def test_urgent_reply_goes_before_queued_replies_and_callers_do_not_wait(self):
        gate=threading.Event(); out=BlockingOut(gate)
        with patch.object(sys,'stdout',out):
            writer=host.ReplyWriter()
            with patch.object(host,'WRITER',writer):
                host.send_reply(self.prefix,{'id':'long','result':'x'*100000})
                self.assertTrue(out.started.wait(2))
                host.send_reply(self.prefix,{'id':'queued','result':1})
                started=time.monotonic()
                host.handle_request(self.prefix,{'id':'p','method':'ping','args':{}},True)
                self.assertLess(time.monotonic()-started,1,'ping must not wait for a reply being written')
                gate.set(); writer.close()
        self.assertEqual([r['id'] for r in decode(out.getvalue(),self.prefix)],['long','p','queued'])

    def test_each_reply_keeps_its_frames_together(self):
        out=io.StringIO()
        with patch.object(sys,'stdout',out):
            writer=host.ReplyWriter()
            with patch.object(host,'WRITER',writer):
                threads=[threading.Thread(target=host.send_reply,args=(self.prefix,{'id':str(n),'result':str(n)*5000})) for n in range(8)]
                for t in threads: t.start()
                for t in threads: t.join()
                writer.close()
        replies=decode(out.getvalue(),self.prefix)
        self.assertEqual(sorted(r['id'] for r in replies),[str(n) for n in range(8)])
        self.assertTrue(all(r['result']==r['id']*5000 for r in replies))

    def test_oversized_or_unencodable_reply_fails_only_that_request(self):
        out=io.StringIO()
        with patch.object(sys,'stdout',out),patch.object(host,'MAX_REPLY_FRAMES',100):
            host.send_reply(self.prefix,{'id':'big','result':'x'*10000})
            host.send_reply(self.prefix,{'id':'odd','result':object()})
            host.send_reply(self.prefix,{'id':'small','result':'ok'})
        big,odd,small=decode(out.getvalue(),self.prefix)
        self.assertEqual(big['id'],'big'); self.assertIn('too large',big['error']); self.assertNotIn('result',big)
        self.assertEqual(odd['id'],'odd'); self.assertIn('could not be encoded',odd['error'])
        self.assertEqual(small,{'id':'small','result':'ok'})

    def test_large_reply_over_real_process_does_not_end_link(self):
        code=("import importlib.util; s=importlib.util.spec_from_file_location('host',"+repr(str(HOST))+"); h=importlib.util.module_from_spec(s);"
              "s.loader.exec_module(h); h.MAX_REPLY_FRAMES=1000; original=h.dispatch;"
              "h.dispatch=lambda m,a: 'y'*200000 if m=='capabilities' else original(m,a); h.main()")
        lines=''.join(base64.b64encode(json.dumps({'id':i,'method':m,'args':{}}).encode()).decode()+'\n' for i,m in [('big','capabilities'),('fast','ping')])
        result=subprocess.run([sys.executable,'-I','-c',code,'fixture'],input=lines,text=True,capture_output=True,timeout=10,check=True)
        replies={r['id']:r for r in decode(result.stdout,'DAZED:fixture:')}
        self.assertIn('too large',replies['big']['error'])
        self.assertEqual(replies['fast']['result']['machine'],host.MACHINE)

class SignalTests(unittest.TestCase):
    def start_upload(self, folder):
        code=("import importlib.util; s=importlib.util.spec_from_file_location('host',"+repr(str(HOST))+"); h=importlib.util.module_from_spec(s);"
              "s.loader.exec_module(h); h.main()")
        proc=subprocess.Popen([sys.executable,'-I','-c',code,'fixture'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        request={'id':'u','method':'upload_begin','args':{'directory':str(folder),'name':'partial.bin','size':10}}
        proc.stdin.write(base64.b64encode(json.dumps(request).encode()).decode()+'\n'); proc.stdin.flush()
        seen=''
        while True:
            line=proc.stdout.readline()
            self.assertTrue(line,'host ended before replying')
            seen+=line
            replies=[r for r in decode(seen,'DAZED:fixture:') if r['id']=='u']
            if replies: break
        self.assertIn('result',replies[0])
        self.assertEqual(len(list(folder.glob('.dazedev-upload-*'))),1)
        return proc

    def test_hangup_and_terminate_remove_upload_temp_files(self):
        for sig in (signal.SIGHUP,signal.SIGTERM):
            with tempfile.TemporaryDirectory() as temp:
                folder=pathlib.Path(temp); proc=self.start_upload(folder)
                try:
                    proc.send_signal(sig)
                    self.assertEqual(proc.wait(5),128+sig)
                finally:
                    if proc.poll() is None: proc.kill(); proc.wait()
                    proc.stdin.close(); proc.stdout.close()
                self.assertEqual(list(folder.glob('.dazedev-upload-*')),[])

if __name__ == '__main__': unittest.main()
