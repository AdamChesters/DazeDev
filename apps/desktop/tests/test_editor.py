from unittest.mock import patch
import importlib.util, json, os, pathlib, pty, shlex, socket, subprocess, sys, tempfile, threading, time, unittest, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'src-tauri/resources/browser.py').read_text()
spec = importlib.util.spec_from_file_location('editor_bridge', ROOT / 'src-tauri/resources/browser.py')
bridge = importlib.util.module_from_spec(spec); spec.loader.exec_module(bridge)
TTY = '/dev/dazedev-editor-test'

class EditorTests(unittest.TestCase):
 """The dazedev-editor shim and the bridge side of editor requests. Every
 bridge uses a private root; nothing touches the shared per-user folder."""
 def setUp(self):
  self.temp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temp.name) / 'bridge'; self.root.mkdir(mode=0o700)
  self.bridges = []; self.requests = []; self.answer = None
  # Only python3 and fake editors on PATH: no real terminal editor can open.
  self.bin = pathlib.Path(self.temp.name) / 'bin'; self.bin.mkdir(); os.symlink(sys.executable, self.bin / 'python3')
  self.file = pathlib.Path(self.temp.name) / 'prompt.md'
 def tearDown(self):
  for server in reversed(self.bridges): server.close()
  self.temp.cleanup()
 def server(self, editor=True, desktop=None):
  def edited(value):
   self.requests.append(value)
   if self.answer: threading.Thread(target=self.answer, args=(server, value), daemon=True).start()
  server = bridge.Bridge(SOURCE, uuid.uuid4().hex, desktop or uuid.uuid4().hex, lambda value: None, root=self.root, edited=edited, editor=editor)
  self.bridges.append(server); return server
 def register(self, server, tty=TTY, pid=None):
  bridge.request(self.root, server.endpoint, dict(kind='register', pid=pid or os.getpid(), tty=tty))
 def fake_editor(self, name, text):
  # A terminal editor stand-in: records its arguments and writes `text`.
  path = self.bin / name
  path.write_text('#!' + sys.executable + ' -I\nimport os,pathlib,sys\npathlib.Path(sys.argv[-1]).write_text(' + repr(text) + ')\npathlib.Path(' + repr(str(self.temp.name)) + ',"ran").write_text(" ".join(sys.argv[1:])+"|"+os.environ.get("DAZEDEV_EDITOR_ACTIVE",""))\n')
  path.chmod(0o700); return path
 def environment(self, **extra):
  value = {k: v for k, v in os.environ.items() if k not in ('TMUX', 'TMUX_PANE', 'VISUAL', 'EDITOR', 'DAZEDEV_EDITOR_FALLBACK', 'DAZEDEV_EDITOR_ACTIVE', 'DAZEDEV_BROWSER_PID')}
  value.update(PATH=str(self.bin), DAZEDEV_BROWSER_TTY=TTY); value.update(extra); return value
 def shim(self):
  return self.bridges[-1].environment()['editor']
 def run_shim(self, *args, timeout=15, **env):
  return subprocess.run([self.shim(), *args, str(self.file)], env=self.environment(**env), capture_output=True, text=True, timeout=timeout)
 def ran(self):
  marker = pathlib.Path(self.temp.name, 'ran')
  return marker.read_text() if marker.exists() else None
 @staticmethod
 def save(text):
  def answer(server, value):
   server.editor_shown(dict(id=value['id']))
   server.editor_done(dict(id=value['id'], text=text))
  return answer

 def test_shim_sits_beside_xdg_open_and_only_editing_apps_advertise_it(self):
  desktop = self.server(); phone = self.server(editor=False)
  editor = pathlib.Path(desktop.environment()['editor'])
  self.assertEqual(editor.name, 'dazedev-editor'); self.assertEqual(editor.parent, pathlib.Path(desktop.environment()['command']).parent)
  self.assertEqual(editor.stat().st_mode & 0o777, 0o700)
  self.assertTrue(bridge.read_private(desktop.metadata)['editor']); self.assertFalse(bridge.read_private(phone.metadata)['editor'])
  # Without a way to send the request, the endpoint cannot edit either.
  silent = bridge.Bridge(SOURCE, uuid.uuid4().hex, uuid.uuid4().hex, lambda v: None, root=self.root, editor=True); self.bridges.append(silent)
  self.assertFalse(bridge.read_private(silent.metadata)['editor'])

 def test_saved_text_is_written_back_exactly(self):
  server = self.server(); self.register(server)
  self.file.write_bytes('Fix it.\r\n[expanded paste] é\n'.encode())
  self.answer = self.save('Rewritten\r\nprompt \U0001f600')
  result = self.run_shim()
  self.assertEqual(result.returncode, 0, result.stderr)
  self.assertEqual(self.file.read_bytes(), 'Rewritten\r\nprompt \U0001f600'.encode())
  self.assertEqual(len(self.requests), 1)
  request = self.requests[0]
  self.assertEqual(request['text'], 'Fix it.\r\n[expanded paste] é\n'); self.assertEqual(request['pid'], os.getpid())
  self.assertRegex(request['id'], '^[0-9a-f]{32}$'); self.assertTrue(request['name'])
  self.assertIsNone(self.ran()); self.assertEqual(server.edits, {})

 def test_cancel_leaves_the_file_unchanged(self):
  server = self.server(); self.register(server)
  original = b'keep\r\nthis\n'; self.file.write_bytes(original); before = self.file.stat().st_mtime_ns
  def cancel(server, value):
   server.editor_shown(dict(id=value['id'])); server.editor_done(dict(id=value['id']))
  self.answer = cancel
  result = self.run_shim()
  self.assertEqual(result.returncode, 0, result.stderr)
  self.assertEqual(self.file.read_bytes(), original); self.assertEqual(self.file.stat().st_mtime_ns, before)
  self.assertIsNone(self.ran())

 def test_route_prefers_an_editing_desktop_and_skips_the_phone(self):
  desktop = self.server(); phone = self.server(editor=False)
  self.register(desktop, '/dev/desktop'); self.register(phone, '/dev/phone')
  with patch.object(bridge, 'attached_ttys', return_value=(['/dev/phone', '/dev/desktop'], True)):
   meta, tty = bridge.editor_route(self.root)
  self.assertEqual((meta['generation'], tty), (desktop.generation, '/dev/desktop'))
  with patch.object(bridge, 'attached_ttys', return_value=(['/dev/phone'], True)):
   with self.assertRaisesRegex(RuntimeError, 'No DazeDev desktop that can edit'): bridge.editor_route(self.root)

 def test_phone_only_attach_opens_the_terminal_editor_at_once(self):
  phone = self.server(editor=False); self.register(phone)
  self.file.write_text('draft'); self.fake_editor('nano', 'edited in nano')
  started = time.monotonic(); result = self.run_shim()
  self.assertLess(time.monotonic() - started, 5)
  self.assertEqual(result.returncode, 0, result.stderr)
  self.assertIn('No DazeDev desktop that can edit', result.stderr); self.assertIn('Opening the terminal editor', result.stderr)
  self.assertEqual(self.file.read_text(), 'edited in nano'); self.assertEqual(self.ran(), str(self.file) + '|1')
  self.assertEqual(self.requests, [])

 def test_users_own_editor_is_the_fallback_with_its_arguments(self):
  self.server(); self.file.write_text('draft')
  mine = self.fake_editor('my-editor', 'mine'); self.fake_editor('nano', 'nano')
  result = self.run_shim(DAZEDEV_EDITOR_FALLBACK=shlex.quote(str(mine)) + ' --wait')
  self.assertEqual(result.returncode, 0, result.stderr)
  self.assertEqual(self.file.read_text(), 'mine'); self.assertEqual(self.ran(), '--wait ' + str(self.file) + '|1')

 def test_fallback_never_runs_the_shim_itself(self):
  server = self.server(); self.file.write_text('draft'); self.fake_editor('vi', 'from vi')
  # VISUAL inherited from an outer DazeDev session points at a shim.
  for fallback in (self.shim(), str(self.root / 'shim-000000000000' / 'dazedev-editor') + ' -x'):
   result = self.run_shim(DAZEDEV_EDITOR_FALLBACK=fallback)
   self.assertEqual(result.returncode, 0, result.stderr); self.assertEqual(self.file.read_text(), 'from vi')
  # Reached again from inside its own terminal editor: no desktop, no loop.
  self.register(server); self.answer = self.save('desktop')
  self.fake_editor('my-editor', 'mine')
  result = self.run_shim(DAZEDEV_EDITOR_ACTIVE='1', DAZEDEV_EDITOR_FALLBACK=str(self.bin / 'my-editor'))
  self.assertEqual(result.returncode, 0, result.stderr); self.assertEqual(self.file.read_text(), 'from vi')
  self.assertEqual(self.requests, [])

 def test_no_terminal_editor_fails_instead_of_waiting(self):
  self.server(); self.file.write_text('draft')
  result = self.run_shim()
  self.assertEqual(result.returncode, 1); self.assertIn('no terminal editor found', result.stderr)
  self.assertEqual(self.file.read_text(), 'draft')
  result = subprocess.run([self.shim()], env=self.environment(), capture_output=True, text=True, timeout=5)
  self.assertEqual(result.returncode, 2); self.assertIn('Usage', result.stderr)

 def test_desktop_error_or_no_answer_hands_over_to_the_terminal_editor(self):
  server = self.server(); self.register(server); self.file.write_text('draft'); self.fake_editor('nano', 'nano text')
  def refuse(server, value): server.editor_done(dict(id=value['id'], error='No DazeDev terminal on this desktop is showing this session.'))
  self.answer = refuse
  result = self.run_shim()
  self.assertIn('No DazeDev terminal on this desktop', result.stderr); self.assertEqual(self.file.read_text(), 'nano text')
  # After the pane was shown, the user chose the terminal editor.
  def hand_over(server, value):
   server.editor_shown(dict(id=value['id'])); time.sleep(.2); server.editor_done(dict(id=value['id'], error='You chose the terminal editor.'))
  self.answer = hand_over; self.file.write_text('draft')
  result = self.run_shim()
  self.assertIn('You chose the terminal editor', result.stderr); self.assertEqual(self.file.read_text(), 'nano text')
  # The desktop never answers.
  self.answer = None; self.file.write_text('draft')
  with patch.object(bridge, 'EDIT_SHOWN_TIMEOUT', .5):
   result = self.run_shim()
  self.assertIn('did not open the editor', result.stderr); self.assertEqual(self.file.read_text(), 'nano text')
  self.assertEqual(server.edits, {})

 def test_disconnect_while_editing_hands_over_to_the_terminal_editor(self):
  server = self.server(); self.register(server); self.file.write_text('draft'); self.fake_editor('nano', 'nano text')
  self.answer = lambda server, value: server.editor_shown(dict(id=value['id']))
  shim = subprocess.Popen([self.shim(), str(self.file)], env=self.environment(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
  deadline = time.time() + 5
  while time.time() < deadline and not server.edits: time.sleep(.05)
  time.sleep(.2); server.close(); self.bridges.remove(server)
  _, stderr = shim.communicate(timeout=10)
  self.assertEqual(shim.returncode, 0, stderr); self.assertIn('disconnected', stderr); self.assertEqual(self.file.read_text(), 'nano text')

 def test_program_that_stops_waiting_ends_the_request(self):
  server = self.server(); self.register(server); self.file.write_text('draft')
  self.answer = lambda server, value: server.editor_shown(dict(id=value['id']))
  shim = subprocess.Popen([self.shim(), str(self.file)], env=self.environment(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
  deadline = time.time() + 5
  while time.time() < deadline and not (server.edits and all(e['shown'].is_set() for e in server.edits.values())): time.sleep(.05)
  identifier = next(iter(server.edits)); shim.kill(); shim.wait()
  deadline = time.time() + 5
  while time.time() < deadline and server.edits: time.sleep(.05)
  with self.assertRaisesRegex(ValueError, 'ended'): server.editor_done(dict(id=identifier, text='late'))
  self.assertEqual(self.file.read_text(), 'draft')

 def test_size_and_encoding_limits(self):
  server = self.server(); self.register(server); self.fake_editor('nano', 'nano text')
  self.file.write_bytes(b'x' * (bridge.MAX_EDIT + 1))
  result = self.run_shim()
  self.assertIn('too large', result.stderr); self.assertEqual(self.file.read_text(), 'nano text')
  self.file.write_bytes(b'\xff\xfe not utf-8')
  result = self.run_shim()
  self.assertIn('not UTF-8', result.stderr); self.assertEqual(self.requests, [])
  # The bridge checks sizes too, both ways.
  with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
   client.settimeout(5); client.connect(str(server.path))
   bridge.send_packet(client, dict(kind='edit', token=server.token, tty=TTY, name='x', text='y' * (bridge.MAX_EDIT + 1)))
   self.assertIn('too large', bridge.read_packet(client)['error'])
  with self.assertRaisesRegex(ValueError, 'ended'): server.editor_done(dict(id='0' * 32))
  self.assertFalse(server.editor_shown(dict(id='0' * 32)))
  self.answer = lambda server, value: (server.editor_shown(dict(id=value['id'])), self.assertRaisesRegex(ValueError, 'too large', server.editor_done, dict(id=value['id'], text='z' * (bridge.MAX_EDIT + 1))), server.editor_done(dict(id=value['id'])))
  self.file.write_text('small'); result = self.run_shim()
  self.assertEqual(result.returncode, 0, result.stderr); self.assertEqual(self.file.read_text(), 'small')

 def test_edit_needs_authentication_a_live_route_and_an_editing_app(self):
  server = self.server(); phone = self.server(editor=False)
  def ask(target, **fields):
   with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
    client.settimeout(5); client.connect(str(target.path))
    bridge.send_packet(client, dict(dict(kind='edit', token=target.token, tty=TTY, name='x', text='y'), **fields))
    return bridge.read_packet(client)
  self.assertIn('authentication', ask(server, token='wrong')['error'])
  self.assertFalse(ask(server)['ok'])  # no registered route
  self.register(phone)
  self.assertIn('cannot edit', ask(phone)['error'])
  self.assertEqual(self.requests, [])

 def test_editor_session_round_trip_through_tmux(self):
  # A fake agent in a private tmux session runs $VISUAL like Codex does and
  # reads the file back after the desktop saves it.
  hostspec = importlib.util.spec_from_file_location('editor_host_fixture', ROOT / 'src-tauri/resources/host.py')
  host = importlib.util.module_from_spec(hostspec); hostspec.loader.exec_module(host)
  server = self.server(); host.BROWSER = server; host.SOCKET = 'dazedev-editor-test-' + uuid.uuid4().hex
  trigger = pathlib.Path(self.temp.name, 'go'); result = pathlib.Path(self.temp.name, 'agent-result')
  self.file.write_text('seed with [Pasted Content 1500 chars] expanded')
  agent = pathlib.Path(self.temp.name, 'fixture-agent')
  agent.write_text('#!' + sys.executable + ' -I\nimport json,os,pathlib,shlex,subprocess,sys,time\nif "--help" in sys.argv: sys.exit(0)\n'
   'while not pathlib.Path(' + repr(str(trigger)) + ').exists(): time.sleep(.05)\n'
   'status=subprocess.run(shlex.split(os.environ["VISUAL"])+[' + repr(str(self.file)) + ']).returncode\n'
   'pathlib.Path(' + repr(str(result)) + ').write_text(json.dumps([status,os.environ["VISUAL"],os.environ["EDITOR"],os.environ["DAZEDEV_EDITOR_FALLBACK"],pathlib.Path(' + repr(str(self.file)) + ').read_text()]))\n')
  agent.chmod(0o700)
  self.answer = self.save('desktop edit\nkept exactly')
  client = None; master = slave = None
  try:
   with patch.object(host, 'agent_path', return_value=str(agent)), patch.dict(os.environ, {'VISUAL': 'vim -p', 'PATH': str(self.bin) + os.pathsep + os.environ['PATH']}):
    session = host.create_session(dict(name='fixture', path=self.temp.name, agent='codex', operation=uuid.uuid4().hex))
   self.assertEqual(host.tmux('show-environment', '-t', session['id'], 'VISUAL'), 'VISUAL=' + self.shim())
   master, slave = pty.openpty(); tty = os.ttyname(slave)
   client = subprocess.Popen(['tmux', '-L', host.SOCKET, 'attach-session', '-t', session['id']], stdin=slave, stdout=slave, stderr=slave, env=dict(os.environ, TERM='xterm-256color'))
   deadline = time.time() + 5
   while time.time() < deadline:
    if tty in subprocess.check_output(['tmux', '-L', host.SOCKET, 'list-clients', '-F', '#{client_tty}'], text=True): break
    time.sleep(.05)
   else: self.fail('Fixture tmux client did not attach')
   self.register(server, tty, client.pid)
   trigger.write_text('go')
   deadline = time.time() + 10
   while time.time() < deadline and not result.exists(): time.sleep(.05)
   status, visual, editor, fallback, text = json.loads(result.read_text())
   self.assertEqual((status, visual, editor, fallback), (0, self.shim(), self.shim(), 'vim -p'))
   self.assertEqual(text, 'desktop edit\nkept exactly')
   self.assertEqual(self.requests[0]['text'], 'seed with [Pasted Content 1500 chars] expanded')
   self.assertEqual(self.requests[0]['pid'], client.pid)
  finally:
   subprocess.run(['tmux', '-L', host.SOCKET, 'kill-server'], capture_output=True)
   if client:
    try: client.wait(timeout=2)
    except subprocess.TimeoutExpired: client.kill(); client.wait()
   for fd in [master, slave]:
    if fd is not None: os.close(fd)

class ClientOrderTests(unittest.TestCase):
 def test_most_recently_active_tmux_client_comes_first(self):
  responses = [subprocess.CompletedProcess([], 0, stdout='$1\n'), subprocess.CompletedProcess([], 0, stdout='100 /dev/pts/1\n300 /dev/pts/3\n200 /dev/pts/2\n')]
  with patch.dict(os.environ, {'TMUX': '/tmp/fixture,1,0', 'TMUX_PANE': '%1'}), patch.object(bridge.subprocess, 'run', side_effect=responses) as run:
   self.assertEqual(bridge.attached_ttys(), (['/dev/pts/3', '/dev/pts/2', '/dev/pts/1'], True))
  self.assertIn('#{client_activity} #{client_tty}', run.call_args_list[1].args[0])

class HostEnvironmentTests(unittest.TestCase):
 def setUp(self):
  hostspec = importlib.util.spec_from_file_location('editor_host_env', ROOT / 'src-tauri/resources/host.py')
  self.host = importlib.util.module_from_spec(hostspec); hostspec.loader.exec_module(self.host)
 def env(self, **values):
  base = {k: v for k, v in os.environ.items() if k not in ('VISUAL', 'EDITOR', 'DAZEDEV_EDITOR_FALLBACK')}
  base.update(values); return patch.dict(os.environ, base, clear=True)
 def test_users_editor_becomes_the_fallback_and_never_a_shim(self):
  shim = '/run/user/1/dazedev-browser/shim-abc/dazedev-editor'
  cases = [({'VISUAL': 'vim', 'EDITOR': 'nano'}, 'vim'), ({'EDITOR': 'nano'}, 'nano'),
   ({'VISUAL': '/old/dazedev-editor', 'EDITOR': 'emacs -nw'}, 'emacs -nw'),
   ({'VISUAL': '/old/dazedev-editor', 'EDITOR': '/old/dazedev-editor', 'DAZEDEV_EDITOR_FALLBACK': 'micro'}, 'micro'),
   ({'VISUAL': '/old/dazedev-editor', 'DAZEDEV_EDITOR_FALLBACK': '/old/dazedev-editor'}, ''), ({}, '')]
  for values, fallback in cases:
   with self.env(**values):
    self.assertEqual(self.host.editor_environment({'editor': shim}), {'VISUAL': shim, 'EDITOR': shim, 'DAZEDEV_EDITOR_FALLBACK': fallback}, values)
  # A helper without the editor shim changes nothing.
  self.assertEqual(self.host.editor_environment({'command': '/x/xdg-open'}), {})
 def test_editor_methods_are_inline_and_answer_without_a_bridge(self):
  for method in ('editor_shown', 'editor_done'): self.assertEqual(self.host.lane_for(method), 'inline')
  self.host.BROWSER = None
  self.assertFalse(self.host.dispatch('editor_shown', {'id': 'x'}))
  with self.assertRaisesRegex(RuntimeError, 'ended'): self.host.dispatch('editor_done', {'id': 'x'})

if __name__ == '__main__': unittest.main()
