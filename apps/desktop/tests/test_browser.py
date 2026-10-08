from unittest.mock import patch
import contextlib, importlib.util, io, json, os, pathlib, pty, shlex, socket, subprocess, sys, tempfile, time, unittest, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'src-tauri/resources/browser.py').read_text()
spec = importlib.util.spec_from_file_location('browser_bridge', ROOT / 'src-tauri/resources/browser.py')
bridge = importlib.util.module_from_spec(spec); spec.loader.exec_module(bridge)

class BrowserTests(unittest.TestCase):
 def setUp(self):
  self.temp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temp.name); self.bridges = []; self.opened = []
 def tearDown(self):
  for server in reversed(self.bridges): server.close()
  self.temp.cleanup()
 def server(self, endpoint=None, desktop=None):
  holder = {}
  def opened(value):
   self.opened.append(value); holder['server'].complete({'id':value['id'], 'error':None})
  value = bridge.Bridge(SOURCE, endpoint or uuid.uuid4().hex, desktop or uuid.uuid4().hex, opened, root=self.root)
  holder['server'] = value; self.bridges.append(value); return value
 def register(self, server, tty='/dev/dazedev-test'):
  bridge.request(self.root, server.endpoint, dict(kind='register', pid=os.getpid(), tty=tty))
 def environment(self, **extra):
  value = dict(os.environ, BROWSER=str(self.root/'xdg-open'), DAZEDEV_BROWSER_TTY='/dev/dazedev-test', DAZEDEV_BROWSER_PID=str(os.getpid()))
  value.pop('TMUX', None); value.pop('TMUX_PANE', None); value.update(extra); return value
 def test_real_python_webbrowser_uses_scoped_desktop_bridge(self):
  server=self.server(); self.register(server)
  result=subprocess.run([sys.executable,'-c',"import webbrowser; assert webbrowser.open('https://example.com/auth?code=test')"],env=self.environment(),capture_output=True,text=True,timeout=5)
  self.assertEqual(result.returncode,0,result.stderr)
  self.assertEqual([v['url'] for v in self.opened],['https://example.com/auth?code=test'])
 def test_real_xdg_open_reports_no_desktop_and_rejects_commands(self):
  self.server()
  for url in ['https://example.com','file:///etc/passwd','javascript:alert(1)','http://localhost:1234']:
   result=subprocess.run([str(self.root/'xdg-open'),url],env=self.environment(),capture_output=True,text=True,timeout=5)
   self.assertNotEqual(result.returncode,0)
   self.assertIn('DazeDev browser:',result.stderr)
  self.assertEqual(self.opened,[])
 def test_authentication_is_required_before_open_or_registration(self):
  server=self.server()
  with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
   client.settimeout(2);client.connect(str(server.path));client.sendall(b'{"token":"wrong","kind":"register","pid":1,"tty":"/dev/fake"}\n')
   response=bridge.read_packet(client)
  self.assertFalse(response['ok']);self.assertIn('authentication',response['error']);self.assertEqual(self.opened,[])
 def test_management_reconnect_rotates_socket_without_losing_live_terminal_route(self):
  first=self.server();self.register(first)
  second=self.server(endpoint=first.endpoint,desktop=first.desktop)
  first.close()
  with patch.dict(os.environ,self.environment(),clear=True): bridge.open_url(self.root,'https://example.com/reconnected')
  self.assertEqual(len(self.opened),1)
  self.assertTrue(second.metadata.exists())
 def test_tmux_mirrors_deduplicate_but_distinct_desktops_are_ambiguous(self):
  first=self.server();self.register(first,'/dev/one')
  second=self.server(desktop=first.desktop);self.register(second,'/dev/two')
  with patch.object(bridge,'attached_ttys',return_value=(['/dev/one','/dev/two'],True)):
   bridge.open_url(self.root,'https://example.com/mirror')
  self.assertEqual(len(self.opened),1)
  second.desktop=uuid.uuid4().hex
  meta=bridge.read_private(second.metadata);meta['desktop']=second.desktop;bridge.atomic_file(second.metadata,json.dumps(meta))
  with patch.object(bridge,'attached_ttys',return_value=(['/dev/one','/dev/two'],True)):
   with self.assertRaisesRegex(RuntimeError,'More than one'): bridge.open_url(self.root,'https://example.com/ambiguous')
  self.assertEqual(len(self.opened),1)
 def test_bounded_startup_route_then_requires_attached_client(self):
  server=self.server();server.launch('operation');env=dict(DAZEDEV_BROWSER_ENDPOINT=server.endpoint,DAZEDEV_BROWSER_LAUNCH='operation')
  with patch.dict(os.environ,env),patch.object(bridge,'attached_ttys',return_value=([],True)):
   bridge.open_url(self.root,'https://example.com/startup')
   server.launches['operation']=0
   with self.assertRaisesRegex(RuntimeError,'expired'): bridge.open_url(self.root,'https://example.com/late')
  self.assertEqual(len(self.opened),1)
 def test_dead_terminal_and_reused_tty_do_not_route_to_another_shell(self):
  server=self.server();self.register(server)
  with patch.dict(os.environ,self.environment(DAZEDEV_BROWSER_PID='99999999'),clear=True):
   with self.assertRaisesRegex(RuntimeError,'No attached'):bridge.open_url(self.root,'https://example.com')
  route=bridge.route_path(self.root,'/dev/dazedev-test');value=json.loads(route.read_text());value['stamp']='different';bridge.atomic_file(route,json.dumps(value))
  with patch.dict(os.environ,self.environment(),clear=True):
   with self.assertRaisesRegex(RuntimeError,'No attached'):bridge.open_url(self.root,'https://example.com')
  self.assertEqual(self.opened,[])
 def test_native_denial_is_reported_and_rate_limit_bounds_requests(self):
  server=self.server();self.register(server)
  server.opened=lambda value:server.complete({'id':value['id'],'error':'Unlock DazeDev first'})
  with self.assertRaisesRegex(RuntimeError,'Unlock'):bridge.request(self.root,server.endpoint,dict(kind='open',tty='/dev/dazedev-test',url='https://example.com'))
  server.recent=[bridge.time.monotonic()]*8
  with self.assertRaisesRegex(RuntimeError,'Too many'):bridge.request(self.root,server.endpoint,dict(kind='open',tty='/dev/dazedev-test',url='https://example.com'))
 def test_real_tmux_client_routes_program_to_attached_desktop(self):
  server=self.server();name='dazedev-browser-test-'+uuid.uuid4().hex;client=None;master=slave=None
  try:
   subprocess.run(['tmux','-L',name,'new-session','-d','-s','test','/bin/sh'],check=True)
   master,slave=pty.openpty();tty=os.ttyname(slave)
   client=subprocess.Popen(['tmux','-L',name,'attach-session','-t','test'],stdin=slave,stdout=slave,stderr=slave,env=dict(os.environ,TERM='xterm-256color'))
   deadline=time.time()+5
   while time.time()<deadline:
    if tty in subprocess.check_output(['tmux','-L',name,'list-clients','-F','#{client_tty}'],text=True):break
    time.sleep(.05)
   else:self.fail('Fixture tmux client did not attach')
   bridge.request(self.root,server.endpoint,dict(kind='register',pid=client.pid,tty=tty))
   result=self.root/'tmux-result'
   program="import pathlib,webbrowser;pathlib.Path("+repr(str(result))+").write_text(str(webbrowser.open('https://example.com/tmux-client')))"
   command='BROWSER='+shlex.quote(str(self.root/'xdg-open'))+' '+shlex.quote(sys.executable)+' -c '+shlex.quote(program)
   subprocess.run(['tmux','-L',name,'send-keys','-t','test','-l',command],check=True)
   subprocess.run(['tmux','-L',name,'send-keys','-t','test','Enter'],check=True)
   deadline=time.time()+5
   while time.time()<deadline and not result.exists():time.sleep(.05)
   self.assertEqual(result.read_text(),'True')
   self.assertEqual([v['url'] for v in self.opened],['https://example.com/tmux-client'])
  finally:
   subprocess.run(['tmux','-L',name,'kill-server'],capture_output=True)
   if client:
    try:client.wait(timeout=2)
    except subprocess.TimeoutExpired:client.kill();client.wait()
   for fd in [master,slave]:
    if fd is not None:os.close(fd)
 def test_new_managed_agent_can_open_login_before_first_client_attaches(self):
  spec=importlib.util.spec_from_file_location('browser_host_fixture',ROOT/'src-tauri/resources/host.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
  server=self.server();host.BROWSER=server;host.SOCKET='dazedev-browser-launch-'+uuid.uuid4().hex
  agent=self.root/'fixture-agent'
  agent.write_text('#!'+sys.executable+'\nimport sys,webbrowser\nif "--help" not in sys.argv: assert webbrowser.open("https://example.com/first-login")\n')
  agent.chmod(0o700)
  try:
   with patch.object(host,'agent_path',return_value=str(agent)):
    session=host.create_session(dict(name='fixture',path=str(self.root),agent='codex',operation=uuid.uuid4().hex))
   deadline=time.time()+5
   while time.time()<deadline and not self.opened:time.sleep(.05)
   self.assertEqual([v['url'] for v in self.opened],['https://example.com/first-login'])
   self.assertIn(str(self.root/'xdg-open'),host.tmux('show-environment','-t',session['id'],'BROWSER'))
  finally:subprocess.run(['tmux','-L',host.SOCKET,'kill-server'],capture_output=True)
 def test_optional_bridge_failure_does_not_prevent_host_management_startup(self):
  spec=importlib.util.spec_from_file_location('browser_host_failure',ROOT/'src-tauri/resources/host.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
  host.BROWSER_SOURCE="class Bridge:\n def __init__(self,*args,**kwargs): raise RuntimeError('fixture private directory unavailable')"
  output=io.StringIO()
  with patch.dict(os.environ,{'DAZED_BROWSER_ENDPOINT':uuid.uuid4().hex,'DAZED_BROWSER_DESKTOP':uuid.uuid4().hex}),patch.object(sys,'argv',['host','fixture']),patch.object(sys,'stdin',io.StringIO('')),contextlib.redirect_stdout(output):
   host.main()
  self.assertIn('DAZED:fixture:',output.getvalue())
  self.assertIsNone(host.BROWSER)
  with self.assertRaisesRegex(RuntimeError,'Desktop browser bridge unavailable: fixture'):
   host.dispatch('browser_prepare',{})
 def test_tmux_client_limit_refuses_instead_of_truncating_routes(self):
  responses=[subprocess.CompletedProcess([],0,stdout='$1\n'),subprocess.CompletedProcess([],0,stdout=''.join('/dev/pts/'+str(n)+'\n' for n in range(65)))]
  with patch.dict(os.environ,{'TMUX':'/tmp/fixture,1,0','TMUX_PANE':'%1'}),patch.object(bridge.subprocess,'run',side_effect=responses):
   with self.assertRaisesRegex(RuntimeError,'Too many tmux clients'):bridge.attached_ttys()
 def test_server_waits_for_explicit_protocol_readiness(self):
  server=bridge.Bridge(SOURCE,uuid.uuid4().hex,uuid.uuid4().hex,self.opened.append,root=self.root,start=False);self.bridges.append(server)
  with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
   client.settimeout(.05);client.connect(str(server.path))
   client.sendall((json.dumps(dict(token=server.token,kind='register',pid=os.getpid(),tty='/dev/ready-test'))+'\n').encode())
   with self.assertRaises(socket.timeout):client.recv(1024)
   self.assertFalse(bridge.route_path(self.root,'/dev/ready-test').exists())
   server.start();client.settimeout(2)
   self.assertTrue(bridge.read_packet(client)['ok'])
 def test_insecure_root_is_refused(self):
  self.root.chmod(0o755)
  with self.assertRaisesRegex(RuntimeError,'private'): self.server()
  self.root.chmod(0o700)

if __name__ == '__main__': unittest.main()
