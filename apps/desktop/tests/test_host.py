from unittest.mock import patch
import base64, importlib.util, os, pathlib, subprocess, tempfile, unittest, uuid, time, json, pty, signal
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('host',ROOT/'src-tauri/resources/host.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
class HostTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.old=os.environ.get('XDG_CONFIG_HOME');os.environ['XDG_CONFIG_HOME']=self.temp.name;host.SOCKET='dazed-test-'+uuid.uuid4().hex
 def tearDown(self):
  subprocess.run(['tmux','-L',host.SOCKET,'kill-server'],capture_output=True)
  if self.old is None:os.environ.pop('XDG_CONFIG_HOME',None)
  else:os.environ['XDG_CONFIG_HOME']=self.old
  self.temp.cleanup()
 def test_ctrl_c_agent_returns_to_shell_in_tmux(self):
  root=pathlib.Path(self.temp.name);agent=root/'fixture-agent'
  agent.write_text('#!/usr/bin/python3\nimport sys,time\nif "--help" in sys.argv: sys.exit(0)\nprint("FIXTURE_AGENT_READY",flush=True)\nwhile True: time.sleep(0.1)\n');agent.chmod(0o700)
  with patch.object(host,'agent_path',return_value=str(agent)):
   session=host.create_session({'name':'exit-fixture','path':str(root),'operation':uuid.uuid4().hex,'agent':'codex'})
  host.tmux('set-window-option','-t',session['id'],'remain-on-exit','on')
  deadline=time.monotonic()+3
  while time.monotonic()<deadline:
   if 'FIXTURE_AGENT_READY' in host.tmux('capture-pane','-p','-t',session['id']):break
   time.sleep(.05)
  else:self.fail('Agent did not start')
  host.tmux('send-keys','-t',session['id'],'C-c')
  time.sleep(.3)
  self.assertEqual(host.tmux('display-message','-p','-t',session['id'],'#{pane_dead}'),'0','Agent Ctrl-C must leave a live shell, not a dead pane')
  host.tmux('send-keys','-t',session['id'],'echo RECOVERED_SHELL_READY','Enter')
  deadline=time.monotonic()+3
  while time.monotonic()<deadline:
   if 'RECOVERED_SHELL_READY' in [line.strip() for line in host.tmux('capture-pane','-p','-t',session['id']).splitlines()]:break
   time.sleep(.05)
  else:self.fail('Fallback shell did not accept input')
 def dead_fixture(self):
  host.tmux('new-session','-d','-s','dead-fixture','-c',self.temp.name,'sleep 86400')
  session=host.sessions()[0]
  pane=host.tmux('display-message','-p','-t',session['id'],'#{pane_id}')
  host.tmux('set-window-option','-t',session['id'],'remain-on-exit','on')
  host.tmux('respawn-pane','-k','-t',pane,'exit 7')
  deadline=time.monotonic()+2
  while time.monotonic()<deadline:
   if host.tmux('display-message','-p','-t',pane,'#{pane_dead}')=='1':return dict(id=session['id'],stamp=session['stamp'],paneId=pane)
   time.sleep(.02)
  self.fail('Dead fixture did not exit')
 def test_dead_pane_recovery_returns_shell_without_restarting_agent(self):
  args=self.dead_fixture();host.terminal_recover(args)
  self.assertEqual(host.tmux('display-message','-p','-t',args['paneId'],'#{pane_dead}'),'0')
  host.tmux('send-keys','-t',args['paneId'],'echo RECOVERED_DEAD_PANE','Enter')
  deadline=time.monotonic()+2
  while time.monotonic()<deadline:
   if 'RECOVERED_DEAD_PANE' in [line.strip() for line in host.tmux('capture-pane','-p','-t',args['paneId']).splitlines()]:break
   time.sleep(.02)
  else:self.fail('Recovered pane did not execute shell command')
  with self.assertRaisesRegex(RuntimeError,'not dead|already running'):host.terminal_recover(args)
 def test_dead_pane_recovery_refuses_stale_stamp_and_wrong_session(self):
  args=self.dead_fixture()
  with self.assertRaisesRegex(RuntimeError,'changed'):host.terminal_recover(dict(args,stamp='old-server-stamp'))
  host.tmux('new-session','-d','-s','other-session','sleep 86400');other=next(x for x in host.sessions() if x['name']=='other-session')
  with self.assertRaisesRegex(RuntimeError,'different session'):host.terminal_recover(dict(args,id=other['id'],stamp=other['stamp']))
  self.assertEqual(host.tmux('display-message','-p','-t',args['paneId'],'#{pane_dead}'),'1')
  with self.assertRaises(ValueError):host.terminal_recover(dict(args,paneId=';kill-server'))
 def test_real_attached_dead_pane_metadata_and_recovery(self):
  args=self.dead_fixture();child,master=pty.fork()
  if child==0:
   os.environ['TERM']='xterm-256color';os.execvp('tmux',['tmux','-L',host.SOCKET,'attach-session','-t',args['id']])
  try:
   deadline=time.monotonic()+3;status={}
   while time.monotonic()<deadline:
    status=host.terminal_status({'pid':os.getpid()})
    if status.get('paneDead'):break
    time.sleep(.05)
   self.assertTrue(status.get('paneDead'),'Reconnect must detect an already-dead pane')
   self.assertEqual(status['paneId'],args['paneId']);self.assertEqual(status['sessionId'],args['id']);self.assertEqual(status['stamp'],args['stamp'])
   host.terminal_recover(dict(id=status['sessionId'],stamp=status['stamp'],paneId=status['paneId']))
   self.assertFalse(host.terminal_status({'pid':os.getpid()})['paneDead'])
  finally:
   os.close(master)
   try:os.kill(child,signal.SIGTERM)
   except ProcessLookupError:pass
   os.waitpid(child,0)
 def test_upload_preserves_bytes_and_refuses_overwrite(self):
  root=pathlib.Path(self.temp.name);data=bytes(range(256))*200
  item=host.upload_begin({'directory':str(root),'name':"quoted ' sample.bin",'size':len(data)})
  with self.assertRaises(ValueError):host.upload_chunk({'id':item['id'],'offset':1,'data':base64.b64encode(b'x').decode()})
  for offset in range(0,len(data),32768):host.upload_chunk({'id':item['id'],'offset':offset,'data':base64.b64encode(data[offset:offset+32768]).decode()})
  result=host.upload_finish({'id':item['id']});self.assertEqual(pathlib.Path(result['path']).read_bytes(),data)
  with self.assertRaises(ValueError):host.upload_begin({'directory':str(root),'name':"quoted ' sample.bin",'size':0})
  for name in ['../escape','..','bad/name','bad\\name','bad\nname']:
   with self.assertRaises(ValueError):host.upload_begin({'directory':str(root),'name':name,'size':0})
 def test_upload_cancel_and_concurrent_destination_creation(self):
  root=pathlib.Path(self.temp.name)
  item=host.upload_begin({'directory':str(root),'name':'canceled','size':1});host.upload_abort(item)
  self.assertFalse((root/'canceled').exists());self.assertEqual(list(root.glob('.dazedev-upload-*')),[])
  item=host.upload_begin({'directory':str(root),'name':'raced','size':0});(root/'raced').write_text('other work')
  with self.assertRaises(FileExistsError):host.upload_finish(item)
  host.upload_abort(item);self.assertEqual((root/'raced').read_text(),'other work')
 def test_document_edit_conflict_and_literal_content(self):
  path=pathlib.Path(self.temp.name)/'AGENTS.md';path.write_text('# Original\n');path.chmod(0o640)
  first=host.document_read({'path':str(path)})
  result=host.document_save({'path':str(path),'revision':first['revision'],'text':'# Unicode café\n<script>literal</script>'})
  self.assertEqual(path.read_text(),result['text']);self.assertEqual(path.stat().st_mode&0o777,0o640)
  with self.assertRaisesRegex(RuntimeError,'CONFLICT'):host.document_save({'path':str(path),'revision':first['revision'],'text':'stale edit'})
  self.assertEqual(path.read_text(),result['text'])
  binary=path.parent/'secret.env';binary.write_text('not a document')
  with self.assertRaises(ValueError):host.document_read({'path':str(binary)})
 def test_document_discovery_scopes_nested_and_references(self):
  root=pathlib.Path(self.temp.name)/'repo';root.mkdir();(root/'sub').mkdir();(root/'node_modules').mkdir()
  (root/'AGENTS.md').write_text('Read [private guidance](../private.md) and `sub/CLAUDE.md`.')
  (root.parent/'private.md').write_text('Private scoped guidance')
  (root/'sub/CLAUDE.md').write_text('Nested instructions')
  (root/'node_modules/AGENTS.md').write_text('Skip dependency guidance')
  result=host.documents({'path':str(root)})
  files={f['path']:f for f in result['files']}
  self.assertEqual(files[str(root/'AGENTS.md')]['scope'],'Repository')
  self.assertEqual(files[str(root/'sub/CLAUDE.md')]['scope'],'Nested')
  self.assertEqual(files[str(root.parent/'private.md')]['scope'],'Referenced')
  self.assertNotIn(str(root/'node_modules/AGENTS.md'),files)
 def test_terminal_status_tracks_actual_client_and_nested_ssh(self):
  uid=os.getuid();snapshot=f'100 1 {uid} sh\n101 100 {uid} tmux\n200 1 {uid} bash\n201 200 {uid} ssh\n'
  with patch.object(host,'run',return_value=snapshot),patch.object(host,'tmux',return_value='101\t$1\t200'),patch.object(host.shutil,'which',return_value='/bin/tmux'):
   self.assertEqual(host.terminal_status({'pid':100})['attachment'],'unknown')
   self.assertTrue(host.terminal_status({'pid':100})['nestedSsh'])
   with patch.object(host,'run',return_value=snapshot.replace(f'201 200 {uid} ssh\n','')):
    self.assertEqual(host.terminal_status({'pid':100})['attachment'],'tmux')
   with patch.object(host,'tmux',return_value=''):
    self.assertEqual(host.terminal_status({'pid':100})['attachment'],'unknown')
   with patch.object(host,'run',return_value=f'100 1 {uid} sh\n'),patch.object(host,'tmux',return_value=''):
    self.assertEqual(host.terminal_status({'pid':100})['attachment'],'plain')
   self.assertEqual(host.terminal_status({'pid':999})['attachment'],'unknown')
 def test_terminal_clone_resolves_live_session_and_stamp(self):
  uid=os.getuid()
  def tmux(*args,**kwargs):
   if args[0]=='list-clients': return '101\t$9\t200'
   self.assertEqual(args[0:4],('display-message','-p','-t','$9'))
   if args[-1]=='#{session_name}': return 'manual-session'
   return '99:88:77'
  with patch.object(host,'run',return_value=f'100 1 {uid} sh\n101 100 {uid} tmux\n200 1 {uid} bash\n'),patch.object(host,'tmux',side_effect=tmux),patch.object(host.shutil,'which',return_value='/bin/tmux'):
   status=host.terminal_status({'pid':100})
   self.assertEqual((status['attachment'],status['sessionId'],status['stamp']),('tmux','$9','99:88:77'))
   self.assertEqual(status['sessionName'],'manual-session')
   self.assertEqual(status['agent'],'shell')
 def test_manual_tmux_status_labels_only_known_same_user_pane_agents(self):
  uid=os.getuid()
  snapshot=f'100 1 {uid} sh\n101 100 {uid} tmux\n200 1 {uid} bash\n201 200 {uid} codex\n202 200 {uid+1} claude\n'
  def tmux(*args,**kwargs):
   if args[0]=='list-clients': return '101\t$9\t200'
   return 'manual-codex' if args[-1]=='#{session_name}' else '99:88:77'
  with patch.object(host,'run',return_value=snapshot),patch.object(host,'tmux',side_effect=tmux),patch.object(host.shutil,'which',return_value='/bin/tmux'):
   status=host.terminal_status({'pid':100})
   self.assertEqual((status['attachment'],status['sessionName'],status['agent']),('tmux','manual-codex','codex'))
   with patch.object(host,'run',return_value=snapshot.replace(f'201 200 {uid} codex',f'201 200 {uid} unfamiliar-command')):
    self.assertEqual(host.terminal_status({'pid':100})['agent'],'shell')
   with patch.object(host,'run',return_value=snapshot+f'203 200 {uid} ssh\n'):
    status=host.terminal_status({'pid':100})
    self.assertEqual(status['attachment'],'unknown');self.assertTrue(status['nestedSsh'])
    self.assertNotIn('sessionName',status);self.assertNotIn('agent',status)
 def test_plain_terminal_clone_reads_deepest_shell_directory(self):
  uid=os.getuid()
  with patch.object(host,'run',return_value=f'100 1 {uid} sh\n101 100 {uid} bash\n'),patch.object(host.shutil,'which',return_value=None),patch.object(host.os,'readlink',return_value="/tmp/space and ' quote") as readlink:
   status=host.terminal_status({'pid':100})
   self.assertEqual(status['attachment'],'plain');self.assertIsNone(status['sessionId'])
   self.assertEqual(status['directory'],"/tmp/space and ' quote")
   readlink.assert_called_once_with('/proc/101/cwd')
 def test_macos_process_context_tracks_children_and_fails_closed(self):
  snapshot='100 1 100 Mon Sep 21 10:00:00 2026 /bin/zsh\n101 100 101 Mon Sep 21 10:00:01 2026 /usr/local/bin/agent with spaces\n200 1 200 Mon Sep 21 10:00:00 2026 /bin/other\n'
  with patch.object(host.sys,'platform','darwin'), patch.object(host,'run',return_value=snapshot), patch.object(host,'tmux',return_value=''):
   first=host.process_context(100)
   self.assertIn('agent with spaces',first);self.assertNotIn('/bin/other',first)
   with patch.object(host,'run',return_value=snapshot.replace('agent with spaces','different-agent')):
    self.assertNotEqual(first,host.process_context(100))
   with self.assertRaisesRegex(RuntimeError,'unavailable'):host.process_context(999)
 def test_protocol_frames_fit_console_and_preserve_unicode(self):
  prefix='DAZED:'+('a'*32)+':'
  reply={'id':'ready','result':'large Unicode ☃ payload '*1000}
  frames=host.reply_frames(prefix,reply)
  self.assertTrue(all(len(line)<120 for line in frames))
  chunks=[]
  for i,line in enumerate(frames):
   header,payload=line[len(prefix)+1:].split(':',1)
   self.assertEqual(header,str(i)+'/'+str(len(frames)));chunks.append(payload)
  self.assertEqual(json.loads(base64.b64decode(''.join(chunks))),reply)
 def test_atomic_profile_conflict_preserves_newer_data(self):
  p=host.load_profile();p['preferences']['guidance']='first';saved=host.save_profile(p,0);self.assertEqual(saved['revision'],1)
  with self.assertRaisesRegex(RuntimeError,'CONFLICT'):host.save_profile(host.empty(),0)
  self.assertEqual(host.load_profile()['preferences']['guidance'],'first')
  p['preferences']['guidance']='second';host.save_profile(p,1)
  self.assertTrue((host.config_dir()/'host.json.bak').exists())
  self.assertEqual((host.config_dir()/'host.json').stat().st_mode&0o777,0o600)
 def test_shared_tabs_and_order_roundtrip_with_revision_checks(self):
  fixture=json.loads((ROOT.parents[1]/'shared/protocol/profile-v1.json').read_text())
  fixture['revision']=0
  saved=host.save_profile(fixture,0)
  loaded=host.load_profile()
  self.assertEqual(loaded['workspaces'],fixture['workspaces'])
  self.assertTrue(loaded['workspaces'][0]['tabGroups'][0]['futureGroup'])
  with self.assertRaisesRegex(RuntimeError,'CONFLICT'):host.save_profile(host.empty(),0)
  self.assertEqual(host.load_profile(),loaded)
 def test_unknown_schema_preserved(self):
  d=host.config_dir();d.mkdir();p=d/'host.json';p.write_text('{"schema":99,"machines":{}}')
  with self.assertRaisesRegex(RuntimeError,'Unsupported'):host.load_profile()
  self.assertIn('99',p.read_text())
 def test_tmux_lifecycle_and_launch_retry(self):
  args={'name':'test-session','path':self.temp.name,'agent':'shell','operation':str(uuid.uuid4())}
  s=host.create_session(args);self.assertEqual(host.create_session(args)['id'],s['id']);self.assertEqual(len(host.sessions()),1)
  with self.assertRaisesRegex(RuntimeError,'Session changed'):host.dispatch('destroy',{'id':s['id'],'stamp':'old-server'})
  self.assertEqual(len(host.sessions()),1)
  host.dispatch('rename',{'id':s['id'],'stamp':s['stamp'],'name':'renamed'});self.assertEqual(host.sessions()[0]['name'],'renamed')
  host.dispatch('destroy',{'id':s['id'],'stamp':s['stamp']});self.assertEqual(host.sessions(),[])
  with self.assertRaises(ValueError):host.session_id('$(touch /tmp/nope)')
 def test_custom_tmux_socket_has_separate_workspace_profile(self):
  p=host.empty();p['preferences']['guidance']='socket one';host.save_profile(p,0)
  old=host.SOCKET;host.SOCKET=old+'-other'
  self.assertEqual(host.load_profile()['revision'],0)
  host.SOCKET=old;self.assertEqual(host.load_profile()['preferences']['guidance'],'socket one')
 def test_agent_arguments_and_working_directory_are_literal(self):
  root=pathlib.Path(self.temp.name);repo=root/"repo with ' quotes";repo.mkdir();bin=root/'bin';bin.mkdir();result=root/'arguments.json';marker=root/'injected'
  cli=bin/'codex';cli.write_text('#!/usr/bin/env python3\nimport sys,json,pathlib,os\nif "--help" in sys.argv: print("--model")\nelse: pathlib.Path('+repr(str(result))+').write_text(json.dumps({"args":sys.argv[1:],"cwd":os.getcwd()}))\n');cli.chmod(0o700)
  previous=os.environ['PATH'];os.environ['PATH']=str(bin)+os.pathsep+previous
  model='model; touch '+str(marker)
  try:
   session=host.create_session({'name':'literal-agent','path':str(repo),'agent':'codex','model':model,'effort':'high','brief':'Inspect and wait.','operation':str(uuid.uuid4())})
   for _ in range(50):
    if result.exists():break
    time.sleep(.02)
   value=json.loads(result.read_text());self.assertEqual(value['cwd'],str(repo));self.assertEqual(value['args'],['--model',model,'-c','model_reasoning_effort="high"','Inspect and wait.']);self.assertFalse(marker.exists());self.assertTrue(session['stamp'])
  finally:os.environ['PATH']=previous
 def test_install_launch_runs_in_session_and_is_retry_safe(self):
  from unittest.mock import patch
  root=pathlib.Path(self.temp.name); result=root/'installed'; exe=root/'fake-agent'
  exe.write_text('#!/bin/sh\nprintf ready > '+str(result)+'\nsleep 2\n');exe.chmod(0o700)
  installer="import os,shutil; print('install visible',flush=True); shutil.which=lambda name: "+repr(str(exe))
  args={'name':'install-test','path':str(root),'agent':'claude','install':True,'operation':str(uuid.uuid4())}
  with patch.object(host,'agent_path',return_value=None),patch.object(host,'INSTALLER',installer):
   session=host.create_session(args)
   self.assertEqual(host.create_session(args)['id'],session['id'])
  for _ in range(100):
   if result.exists():break
   time.sleep(.02)
  self.assertEqual(result.read_text(),'ready')
  self.assertIn('install visible',host.tmux('capture-pane','-p','-t',session['id']))
 def test_agent_exit_returns_to_shell_and_ctrl_d_ends_tmux(self):
  root=pathlib.Path(self.temp.name);cli=root/'codex';cli.write_text('#!/bin/sh\nexit 0\n');cli.chmod(0o700)
  previous=os.environ['PATH'];os.environ['PATH']=str(root)+os.pathsep+previous
  try:
   session=host.create_session({'name':'exit-flow','path':str(root),'agent':'codex','operation':str(uuid.uuid4())})
   sid=session['id']
   time.sleep(.2)
   self.assertEqual(host.tmux('display-message','-p','-t',sid,'#{pane_dead}'),'0')
   self.assertEqual(host.tmux('show-window-options','-v','-t',sid,'remain-on-exit'),'off')
   host.tmux('send-keys','-t',sid,'echo SHELL_AFTER_AGENT','Enter')
   time.sleep(.2)
   self.assertIn('SHELL_AFTER_AGENT',host.tmux('capture-pane','-p','-t',sid))
   host.tmux('send-keys','-t',sid,'C-d')
   for _ in range(30):
    if not host.sessions():break
    time.sleep(.05)
   self.assertEqual(host.sessions(),[])
  finally:os.environ['PATH']=previous
 def test_missing_agent_requires_explicit_install(self):
  from unittest.mock import patch
  with patch.object(host,'agent_path',return_value=None):
   with self.assertRaisesRegex(RuntimeError,'not installed'):
    host.create_session({'name':'missing','agent':'claude','path':self.temp.name,'operation':str(uuid.uuid4())})
  self.assertEqual(host.sessions(),[])
 def test_prompt_template_roundtrips_per_profile(self):
  p=host.empty();p['preferences']['promptTemplate']='Review {{repo}} as {{identity}}.'
  host.save_profile(p,0)
  self.assertEqual(host.load_profile()['preferences']['promptTemplate'],p['preferences']['promptTemplate'])
 def test_repo_discovery_preserves_dirty_tree(self):
  repo=pathlib.Path(self.temp.name)/'repo';repo.mkdir();subprocess.run(['git','init','-q',str(repo)],check=True)
  (repo/'ongoing.txt').write_text('do not overwrite')
  found=host.repos([self.temp.name]);self.assertEqual(len(found),1);self.assertTrue(found[0]['dirty']);self.assertEqual((repo/'ongoing.txt').read_text(),'do not overwrite')
 def test_slow_capabilities_does_not_block_control_lane(self):
  code="import importlib.util,time; s=importlib.util.spec_from_file_location('host',"+repr(str(ROOT/'src-tauri/resources/host.py'))+"); h=importlib.util.module_from_spec(s);s.loader.exec_module(h); original=h.dispatch; h.dispatch=lambda m,a: (time.sleep(.5) or {}) if m=='capabilities' else original(m,a);h.main()"
  lines=''.join(base64.b64encode(json.dumps({'id':i,'method':m,'args':{}}).encode()).decode()+'\n' for i,m in [('slow','capabilities'),('fast','ping')])
  result=subprocess.run(['python3','-c',code,'fixture'],input=lines,text=True,capture_output=True,timeout=5,check=True)
  replies=[];chunks=[]
  for line in result.stdout.splitlines():
   header,payload=line.split(':@',1)[1].split(':',1);i,total=map(int,header.split('/'))
   if i==0:chunks=[]
   chunks.append(payload)
   if i==total-1:replies.append(json.loads(base64.b64decode(''.join(chunks))))
  self.assertEqual([r['id'] for r in replies],['ready','fast','slow'])
 def test_process_context_and_discovery_warnings(self):
  context=host.process_context(os.getpid());self.assertIn(str(os.getpid())+':',context)
  with self.assertRaises(ValueError):host.process_context(0)
  warnings=[];self.assertEqual(host.repos([self.temp.name+'/missing'],warnings),[]);self.assertTrue(warnings)
if __name__=='__main__':unittest.main()
