import importlib.util, json, os, pathlib, subprocess, sys, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
HOST = ROOT / 'src-tauri/resources/host.py'
SCHEMA = json.loads((ROOT.parents[1] / 'shared/protocol/host-v2.json').read_text())
spec = importlib.util.spec_from_file_location('host_v2', HOST); host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from test_host_protocol import dispatched_methods

def parse_frames(data):
    messages, parts, offset = [], {}, 0
    while offset + 4 <= len(data):
        size = int.from_bytes(data[offset:offset + 4], 'big')
        body = data[offset + 4:offset + 4 + size]
        offset += 4 + size
        flags, length = body[0], int.from_bytes(body[1:3], 'big')
        ident, chunk = body[3:3 + length].decode(), body[3 + length:]
        parts.setdefault(ident, []).append(chunk)
        if flags & 1: messages.append((ident, json.loads(b''.join(parts.pop(ident)))))
    return messages

class Recorder:
    def __init__(self): self.writes = []
    def write(self, data): self.writes.append(bytes(data))
    def flush(self): pass

class SchemaTests(unittest.TestCase):
    def test_shared_schema_matches_host_tables(self):
        self.assertEqual(SCHEMA['protocol'], host.PROTOCOL)
        methods = SCHEMA['methods']
        self.assertEqual(set(methods), dispatched_methods() | {'create', 'destroy', 'rename', 'clone', 'install'} & set(methods))
        for name, spec in methods.items():
            self.assertEqual(spec['lane'], host.lane_for(name), name)
            self.assertEqual(spec['deadline'], 30 if name in host.SHORT_DEADLINE_METHODS else 240, name)

class FrameTests(unittest.TestCase):
    def test_frame_layout(self):
        data = host.frame('abc', b'{}', True)
        self.assertEqual(int.from_bytes(data[:4], 'big'), len(data) - 4)
        self.assertEqual(data[4], 1)
        self.assertEqual(parse_frames(data), [('abc', {})])

    def test_large_reply_is_split_and_ping_is_not_held_behind_it(self):
        out = Recorder()
        writer = host.FrameWriter(out)
        with writer.cond:
            writer.put({'id': 'big', 'result': 'x' * (host.FRAME_CHUNK * 3)})
            writer.put({'id': 'ping', 'result': None}, urgent=True)
        writer.close()
        ids = [host_id for w in out.writes for host_id in [w[7:7 + int.from_bytes(w[5:7], 'big')].decode()]]
        self.assertEqual(ids[0], 'ping')
        self.assertGreater(ids.count('big'), 2)
        messages = parse_frames(b''.join(out.writes))
        self.assertEqual([m[0] for m in messages], ['ping', 'big'])
        self.assertEqual(len(messages[1][1]['result']), host.FRAME_CHUNK * 3)

    def test_errors_carry_codes(self):
        _, data = host.encode_message({'id': '1', 'error': host.HostError('conflict', 'file changed')})
        self.assertEqual(json.loads(data)['error'], {'code': 'conflict', 'message': 'file changed'})
        _, data = host.encode_message({'id': '2', 'error': ValueError('bad')})
        self.assertEqual(json.loads(data)['error'], {'code': 'failed', 'message': 'bad'})
        self.assertEqual(str(host.HostError('conflict', 'file changed')), 'CONFLICT: file changed')

class BinaryHostTests(unittest.TestCase):
    def test_binary_host_answers_ping_and_ignores_stray_child_output(self):
        env = dict(os.environ, DAZED_TMUX_SOCKET='dazedev-v2-test-' + str(os.getpid()))
        env.pop('DAZED_BROWSER_ENDPOINT', None)
        child = subprocess.Popen([sys.executable, '-I', '-u', str(HOST), 'nonce', '2'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        request = json.dumps({'id': 'p1', 'method': 'ping', 'args': {}}).encode()
        out, _ = child.communicate(len(request).to_bytes(4, 'big') + request, timeout=20)
        messages = dict(parse_frames(out))
        self.assertEqual(messages['ready']['result']['protocol'], 2)
        self.assertIn('result', messages['p1'])

if __name__ == '__main__':
    unittest.main()
