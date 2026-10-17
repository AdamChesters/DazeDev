from unittest.mock import patch
import importlib.util, json, os, pathlib, shlex, subprocess, sys, tempfile, time, unittest, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('host_agents', ROOT / 'src-tauri/resources/host.py'); host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)

def wait_for(check, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check(): return True
        time.sleep(.05)
    return False

def fake_cli(path, result):
    # Records its arguments; prints a help text naming --model and --effort.
    path.write_text('#!/usr/bin/env python3\nimport sys,json,pathlib\nif "--help" in sys.argv: print("--model --effort")\n'
                    'elif "--version" in sys.argv: print("9.9.9")\nelse: pathlib.Path(' + repr(str(result)) + ').write_text(json.dumps(sys.argv[1:]))\n')
    path.chmod(0o700)

class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temp.name)
        self.env = patch.dict(os.environ, {'XDG_CONFIG_HOME': str(self.root / 'config'), 'SHELL': '/bin/sh'}); self.env.start()
        host.SOCKET = 'dazed-agents-' + uuid.uuid4().hex; host._custom_cache.clear(); host._snapshots.clear(); host.STATUS_TTL = 0

    def tearDown(self):
        subprocess.run(['tmux', '-L', host.SOCKET, 'kill-server'], capture_output=True)
        self.env.stop(); self.temp.cleanup()

    def save(self, *agents, revision=None):
        current = host.agents_read()['revision'] if revision is None else revision
        return host.agents_save({'agents': list(agents), 'revision': current})

    def launch(self, **args):
        return host.create_session(dict({'name': 'agent-' + uuid.uuid4().hex[:8], 'path': str(self.root), 'operation': str(uuid.uuid4())}, **args))

    # Built-in table

    def test_built_in_agents_use_one_documented_user_scoped_installer(self):
        self.assertEqual(list(host.AGENTS)[:4], ['codex', 'claude', 'gemini', 'opencode'])
        for name, agent in host.AGENTS.items():
            self.assertRegex(name, host.AGENT_ID)
            self.assertTrue(agent['name'])
            install = agent['install']
            self.assertEqual(len({'native', 'script', 'npm'} & set(install)), 1, name)
            if 'script' in install: self.assertTrue(install['script'].startswith('https://'), name)
            if 'npm' in install: self.assertGreaterEqual(install['node'], 20)
            self.assertIn(agent.get('prompt', 'arg'), ['arg', '-i', '--prompt', '--prompt-interactive'])
        self.assertEqual(host.AGENTS['qwen']['install'], {'npm': '@qwen-code/qwen-code', 'node': 22})
        self.assertEqual(host.AGENTS['goose']['install']['env'], {'CONFIGURE': 'false'})
        self.assertEqual(host.agent_install_text('gemini'), 'npm install -g @google/gemini-cli')
        self.assertEqual(host.agent_install_text('codex'), 'Official native user installer')
        self.assertIn('https://cursor.com/install', host.agent_install_text('cursor-agent'))

    def test_built_in_prompt_flags_and_subcommands(self):
        bin_dir = self.root / 'bin'; bin_dir.mkdir(); result = self.root / 'args.json'
        for name in ['copilot', 'goose', 'qwen', 'droid', 'amp']: fake_cli(bin_dir / name, result)
        with patch.dict(os.environ, {'PATH': str(bin_dir) + os.pathsep + os.environ['PATH']}):
            cases = [('copilot', {'brief': 'Look around.', 'model': 'gpt-x', 'effort': 'high'}, ['--model', 'gpt-x', '--effort', 'high', '-i', 'Look around.']),
                     ('qwen', {'brief': '--not-a-flag'}, ['--prompt-interactive', '--not-a-flag']),
                     ('droid', {'brief': 'Explain; rm -rf /'}, ['Explain; rm -rf /']),
                     ('goose', {}, ['session']),
                     ('goose', {'brief': 'Plan it'}, ['run', '--interactive', '--text', 'Plan it'])]
            for agent, extra, expected in cases:
                result.unlink(missing_ok=True)
                self.launch(agent=agent, **extra)
                self.assertTrue(wait_for(result.exists), agent)
                self.assertEqual(json.loads(result.read_text()), expected, agent)
            with self.assertRaisesRegex(ValueError, 'does not take a starting prompt'): self.launch(agent='amp', brief='hello')
            # goose sets model and effort inside its session, not through flags.
            with self.assertRaisesRegex(ValueError, 'does not advertise'): self.launch(agent='goose', model='m')

    def test_inventory_and_capabilities_list_built_in_and_custom_agents(self):
        self.save({'id': 'mine', 'name': 'My agent', 'command': 'my-agent --fast', 'version': 'echo mine 1.2; echo second line'},
                  {'id': 'missing', 'name': 'Missing', 'command': 'not-here', 'install': 'echo install'})
        found = {'codex': '/bin/true', 'my-agent': '/bin/true'}
        with patch.object(host, 'agent_path', side_effect=lambda n: found.get(n)), patch.object(host, 'repos', return_value=[]), patch.object(host, 'sessions', return_value=[]), \
             patch.object(host, 'run', side_effect=lambda args, **k: 'mine 1.2\nsecond line' if args[:2] == ['/bin/sh', '-c'] else ''):
            inventory = {a['id']: a for a in host.inventory()['agents']}
            capabilities = host.capabilities()
        self.assertEqual(list(inventory)[:len(host.AGENTS)], list(host.AGENTS))
        self.assertEqual(inventory['mine'], {'id': 'mine', 'name': 'My agent', 'path': '/bin/true', 'version': 'mine 1.2', 'models': [], 'modelFlag': False,
                                             'effortFlag': False, 'install': '', 'installable': False, 'prompt': False, 'custom': True})
        self.assertEqual((inventory['missing']['path'], inventory['missing']['installable']), (None, True))
        self.assertEqual((inventory['copilot']['name'], inventory['copilot']['prompt'], inventory['amp']['prompt']), ('GitHub Copilot CLI', True, False))
        self.assertEqual([a['id'] for a in capabilities['agents']], ['codex', 'mine'])
        self.assertEqual(capabilities['agents'][1]['name'], 'My agent'); self.assertFalse(capabilities['agents'][1]['prompt'])
        self.assertIn('custom-agents', capabilities['features'])

    def test_inventory_reports_an_unreadable_custom_agents_file(self):
        host.config_dir().mkdir(parents=True); host.agents_file().write_text('{"schema":1,"agents":[{"id":"Bad id"}]}')
        with patch.object(host, 'agent_path', return_value=None), patch.object(host, 'repos', return_value=[]), patch.object(host, 'sessions', return_value=[]):
            result = host.inventory()
        self.assertEqual(len(result['agents']), len(host.AGENTS))
        self.assertTrue(any('Custom agents could not be read' in w for w in result['warnings']))
        self.assertIn('Bad id', host.agents_file().read_text())

    # Custom agent storage

    def test_custom_agents_roundtrip_with_revision_checks_and_unknown_fields(self):
        self.assertEqual(host.agents_read(), {'agents': [], 'revision': ''})
        saved = self.save({'id': 'mine', 'name': '  My agent ', 'command': 'my-agent --flag', 'futureField': [1]})
        self.assertEqual(saved['agents'], [{'id': 'mine', 'name': 'My agent', 'command': 'my-agent --flag', 'install': '', 'version': '', 'binary': 'my-agent', 'futureField': [1]}])
        self.assertEqual(host.agents_file().stat().st_mode & 0o777, 0o600)
        with self.assertRaisesRegex(host.HostError, 'changed on another device'): self.save(revision='')
        with self.assertRaisesRegex(host.HostError, 'changed on another device'): self.save(revision='0' * 64)
        doc = json.loads(host.agents_file().read_text()); doc['futureTop'] = {'keep': True}; host.agents_file().write_text(json.dumps(doc))
        saved = self.save(*host.agents_read()['agents'], {'id': 'other', 'name': 'Other', 'command': '/opt/other/bin/other'})
        self.assertEqual([a['id'] for a in saved['agents']], ['mine', 'other']); self.assertEqual(saved['agents'][1]['binary'], '/opt/other/bin/other')
        self.assertEqual(json.loads(host.agents_file().read_text())['futureTop'], {'keep': True})
        self.assertEqual(self.save()['agents'], [])

    def test_custom_agent_validation_rejects_hostile_input(self):
        good = {'id': 'mine', 'name': 'Mine', 'command': 'mine'}
        bad = [dict(good, id='Mine'), dict(good, id='-mine'), dict(good, id='mine;touch'), dict(good, id='$(id)'), dict(good, id='../x'), dict(good, id='a' * 33),
               dict(good, id='shell'), dict(good, id='codex'), dict(good, id=7), dict(good, name=''), dict(good, name='x' * 61), dict(good, name='a\nb'),
               dict(good, command=''), dict(good, command='mine\ntouch /tmp/x'), dict(good, command='mine\x1b[2J'), dict(good, command='x' * 2001),
               dict(good, command=['mine']), dict(good, install='a\rb'), dict(good, version='v\x00'), dict(good, binary='-rf'), dict(good, binary='two words'),
               dict(good, binary='relative/path'), dict(good, binary='/opt/../etc/x'), dict(good, binary='.hidden'),
               dict(good, command='FOO=1 mine'), dict(good, command="'unterminated"), dict(good, binary='bash'), dict(good, binary='/usr/bin/node'),
               dict(good, command='sh -c mine'), dict(good, command='npx some-agent'), 'mine', None]
        for value in bad:
            with self.assertRaises(ValueError, msg=repr(value)): host.custom_agents_validate([value])
        with self.assertRaisesRegex(ValueError, 'own ID'): host.custom_agents_validate([good, good])
        with self.assertRaisesRegex(ValueError, 'at most 32'): host.custom_agents_validate([dict(good, id='a' + str(i)) for i in range(33)])
        with self.assertRaises(ValueError): host.custom_agents_validate({'id': 'mine'})
        self.assertEqual(host.custom_agents_validate([dict(good, command='FOO=1 mine', binary='mine')])[0]['binary'], 'mine')
        self.assertFalse(host.agents_file().exists())

    def test_custom_agents_file_must_not_be_a_symlink(self):
        host.config_dir().mkdir(parents=True); target = self.root / 'elsewhere.json'; target.write_text('{"schema":1,"agents":[]}')
        host.agents_file().symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symlinks'): host.agents_read()
        with self.assertRaisesRegex(ValueError, 'symlinks'): host.agents_save({'agents': [], 'revision': ''})
        self.assertEqual(target.read_text(), '{"schema":1,"agents":[]}')

    def test_dispatch_and_lanes(self):
        self.assertEqual(host.dispatch('agents_read', {}), {'agents': [], 'revision': ''})
        self.assertEqual(host.dispatch('agents_save', {'agents': [{'id': 'x1', 'name': 'X', 'command': 'x'}], 'revision': ''})['agents'][0]['id'], 'x1')
        self.assertEqual((host.lane_for('agents_read'), host.lane_for('agents_save')), ('read', 'mutation'))

    # Custom agent launch

    def test_custom_command_runs_as_saved_and_requests_cannot_replace_it(self):
        result = self.root / 'result.txt'; marker = self.root / 'injected'
        command = "printf '%s|%s|%s' 'one two' \"$DAZE_FIXTURE\" \"$(echo sub)\" > " + shlex.quote(str(result))
        binary = self.root / 'mine-agent'; binary.write_text('#!/bin/sh\n'); binary.chmod(0o700)
        self.save({'id': 'mine', 'name': 'Mine', 'command': command, 'binary': str(binary)})
        with patch.dict(os.environ, {'DAZE_FIXTURE': 'expanded'}):
            session = self.launch(agent='mine', command='touch ' + shlex.quote(str(marker)), install=True)
        self.assertTrue(wait_for(result.exists))
        self.assertEqual(result.read_text(), 'one two|expanded|sub')
        self.assertFalse(marker.exists())
        self.assertEqual(host.session_agent(session['id']), 'shell')

    def test_custom_agent_refuses_prompts_models_and_unknown_ids(self):
        self.save({'id': 'mine', 'name': 'Mine', 'command': 'true', 'binary': '/usr/bin/true' if os.path.exists('/usr/bin/true') else '/bin/true'})
        with self.assertRaisesRegex(ValueError, 'without a starting prompt'): self.launch(agent='mine', brief='hello')
        with self.assertRaisesRegex(ValueError, 'own model settings'): self.launch(agent='mine', model='m')
        with self.assertRaisesRegex(ValueError, 'own model settings'): self.launch(agent='mine', effort='high')
        for agent in ['other', 'mine; touch x', '../mine', ['mine'], None]:
            with self.assertRaisesRegex(ValueError, 'Unknown agent'): self.launch(agent=agent)
        with self.assertRaisesRegex(ValueError, 'session names'): self.launch(agent='mine', name='x; tmux kill-server')
        self.assertEqual(host.sessions(), [])

    def test_missing_custom_agent_needs_explicit_install_with_a_command(self):
        self.save({'id': 'bare', 'name': 'Bare', 'command': 'definitely-not-installed-dz'})
        with self.assertRaisesRegex(RuntimeError, 'not installed on this host'): self.launch(agent='bare')
        with self.assertRaisesRegex(RuntimeError, 'no install command'): self.launch(agent='bare', install=True)
        self.assertEqual(host.sessions(), [])

    def test_custom_install_runs_visibly_in_the_session_then_launches(self):
        binary = self.root / 'tools' / 'my-agent'; ready = self.root / 'ready'
        install = 'mkdir -p ' + shlex.quote(str(binary.parent)) + " && printf '#!/bin/sh\\ntouch %s\\nsleep 5\\n' " + shlex.quote(shlex.quote(str(ready))) + ' > ' + shlex.quote(str(binary)) + ' && chmod +x ' + shlex.quote(str(binary))
        # The display name reaches the installer only as data.
        name = "M'); open('hijack','w'); ('"
        self.save({'id': 'mine', 'name': name, 'command': shlex.quote(str(binary)), 'install': install})
        self.assertEqual(host.agents_read()['agents'][0]['binary'], str(binary))
        session = self.launch(agent='mine', install=True)
        self.assertTrue(wait_for(ready.exists))
        screen = host.tmux('capture-pane', '-p', '-J', '-t', session['id'])
        self.assertIn('Installing ' + name + ' with its saved install command', screen)
        self.assertIn(name + ' ready', screen)
        self.assertFalse((self.root / 'hijack').exists())
        self.assertTrue(wait_for(lambda: host.session_agent(session['id']) == 'mine'))

    # Detection

    def test_pane_status_detects_custom_binaries_including_truncated_names(self):
        self.save({'id': 'long', 'name': 'Long', 'command': 'my-long-agent-binary --x'}, {'id': 'wrap', 'name': 'Wrapped claude', 'command': 'claude --x'})
        self.assertEqual(host.agent_named('my-long-agent-b'), 'long')
        self.assertEqual(host.agent_named('my-long-agent-binary'), 'long')
        self.assertIsNone(host.agent_named('my-long-agent'))
        # A custom binary never takes over a built-in agent's name.
        self.assertEqual(host.agent_named('claude'), 'claude')
        uid = os.getuid(); snapshot = f'100 1 {uid} sh\n101 100 {uid} tmux\n200 1 {uid} bash\n201 200 {uid} my-long-agent-b\n'
        with patch.object(host, 'run', return_value=snapshot), patch.object(host, 'tmux', return_value='101\t$9\t200\t%4\t0\t9:8:7\tlong-1'), patch.object(host.shutil, 'which', return_value='/bin/tmux'):
            self.assertEqual(host.terminal_status({'pid': 100})['agent'], 'long')
        with patch.object(host, 'run', return_value=snapshot.replace('my-long-agent-b', 'unrelated')), patch.object(host, 'tmux', return_value='101\t$9\t200\t%4\t0\t9:8:7\tlong-1'), patch.object(host.shutil, 'which', return_value='/bin/tmux'):
            self.assertEqual(host.terminal_status({'pid': 100})['agent'], 'shell')

    # Installer, run offline against local fixtures

    def installer(self, agent, spec, path):
        home = self.root / 'home'; home.mkdir(exist_ok=True)
        code = 'INSTALL_AGENT=' + repr(agent) + '; INSTALL_SPEC=' + repr(spec) + '; INSTALL_DIRS=' + repr(host.AGENT_DIRS) + '; exec(' + repr(host.INSTALLER) + ')'
        return home, subprocess.run([sys.executable, '-I', '-c', code], capture_output=True, text=True, timeout=30,
                                    env={'HOME': str(home), 'PATH': path, 'LANG': 'C.UTF-8'}, stdin=subprocess.DEVNULL)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'one-click install is Linux only')
    def test_installer_runs_publisher_script_with_documented_environment(self):
        script = self.root / 'install.sh'
        script.write_text('mkdir -p "$HOME/.amp/bin"\nprintf "#!/bin/sh\\n" > "$HOME/.amp/bin/fakeagent"\nchmod +x "$HOME/.amp/bin/fakeagent"\nprintf "%s" "$CONFIGURE" > "$HOME/configure"\n')
        home, result = self.installer('fakeagent', {'script': script.as_uri(), 'env': {'CONFIGURE': 'false'}, 'name': 'Fake'}, '/usr/bin:/bin')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Installer: ' + script.as_uri(), result.stdout); self.assertIn('Fake ready', result.stdout)
        self.assertEqual((home / 'configure').read_text(), 'false')
        home, result = self.installer('notcreated', {'script': script.as_uri()}, '/usr/bin:/bin')
        self.assertNotEqual(result.returncode, 0); self.assertIn('notcreated was not found after installation', result.stderr)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'one-click install is Linux only')
    def test_installer_checks_node_version_and_installs_npm_packages_under_local(self):
        tools = self.root / 'tools'; tools.mkdir(); calls = self.root / 'npm-calls'
        (tools / 'npm').write_text('#!/bin/sh\nprintf "%s " "$@" >> ' + shlex.quote(str(calls)) + '\nmkdir -p "$HOME/.local/bin"\nprintf "#!/bin/sh\\n" > "$HOME/.local/bin/qwen"\nchmod +x "$HOME/.local/bin/qwen"\n')
        (tools / 'node').write_text('#!/bin/sh\necho "${FAKE_NODE:-20}"\n')
        for tool in ['npm', 'node']: (tools / tool).chmod(0o700)
        spec = dict(host.AGENTS['qwen']['install'], name='Qwen Code')
        home, result = self.installer('qwen', spec, str(tools) + ':/usr/bin:/bin')
        self.assertNotEqual(result.returncode, 0); self.assertIn('Node.js 22 or newer is required', result.stderr); self.assertFalse(calls.exists())
        (tools / 'node').write_text('#!/bin/sh\necho 22\n')
        home, result = self.installer('qwen', spec, str(tools) + ':/usr/bin:/bin')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.read_text().split(), ['install', '--prefix', str(home / '.local'), '-g', '@qwen-code/qwen-code'])

    def test_installer_runs_custom_install_command_on_any_platform(self):
        target = self.root / 'custom-bin'
        spec = {'name': 'Mine', 'binary': str(target), 'command': 'printf "#!/bin/sh\\n" > ' + shlex.quote(str(target)) + ' && chmod +x ' + shlex.quote(str(target)), 'shell': '/bin/sh'}
        home, result = self.installer('mine', spec, '/usr/bin:/bin')
        self.assertEqual(result.returncode, 0, result.stderr); self.assertTrue(target.exists()); self.assertIn('Mine ready', result.stdout)

if __name__ == '__main__': unittest.main()
