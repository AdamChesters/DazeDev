"""DazeDev's stdlib-only host protocol. Executed in memory; installs no daemon."""
import threading, concurrent.futures
import base64, contextlib, fcntl, getpass, hashlib, json, os, pathlib, re, shlex, shutil, socket, subprocess, sys, tempfile, time, uuid
P = pathlib.Path
os.environ['PATH']=str(P.home()/'.local/bin')+os.pathsep+os.environ.get('PATH','')
SCHEMA = 1
BROWSER = None
BROWSER_ERROR = None
SOCKET = os.environ.get('DAZED_TMUX_SOCKET', '')
MACHINE = hashlib.sha256((socket.gethostname() + ':' + (P('/etc/machine-id').read_text().strip() if P('/etc/machine-id').exists() else str(P.home()))).encode()).hexdigest()[:24]
def profile_key(): return MACHINE + (':'+hashlib.sha256(SOCKET.encode()).hexdigest()[:12] if SOCKET else '')
AGENTS = {'codex': ['npm', 'install', '-g', '@openai/codex'], 'claude': ['npm', 'install', '-g', '@anthropic-ai/claude-code'], 'gemini': ['npm', 'install', '-g', '@google/gemini-cli'], 'opencode': ['npm', 'install', '-g', 'opencode-ai']}
INSTALLER = r"""import os, platform, shutil, subprocess, tempfile, urllib.request, tarfile, pathlib
agent = INSTALL_AGENT
home = pathlib.Path.home()
bin_dir = home / '.local' / 'bin'
bin_dir.mkdir(parents=True, exist_ok=True)
os.environ['PATH'] = str(bin_dir) + ':' + str(home / '.opencode' / 'bin') + ':' + os.environ.get('PATH', '')
try:
    distro = platform.freedesktop_os_release()
except (OSError, AttributeError):
    distro = {}
print('Host: ' + distro.get('PRETTY_NAME', platform.system()) + ' / ' + platform.machine(), flush=True)
if not shutil.which(agent):
    print('Installing ' + agent + ' for this user. This runs the upstream publisher\'s current installer; trust that publisher and review its output.', flush=True)
    if platform.system() != 'Linux':
        raise RuntimeError('One-click installation currently supports Linux and WSL hosts.')
    if agent == 'codex':
        arch = {'x86_64':'x86_64', 'amd64':'x86_64', 'aarch64':'aarch64', 'arm64':'aarch64'}.get(platform.machine().lower())
        if not arch: raise RuntimeError('Codex native installer requires x86_64 or arm64 Linux.')
        asset = 'codex-' + arch + '-unknown-linux-musl'
        with tempfile.TemporaryDirectory(prefix='dazedev-install-') as temp:
            archive = pathlib.Path(temp) / 'codex.tar.gz'
            urllib.request.urlretrieve('https://github.com/openai/codex/releases/latest/download/' + asset + '.tar.gz', archive)
            with tarfile.open(archive) as package:
                member = next((m for m in package.getmembers() if m.isfile() and pathlib.PurePosixPath(m.name).name == asset), None)
                if member is None: raise RuntimeError('Unexpected Codex archive contents')
                target = bin_dir / 'codex'
                staged = bin_dir / ('codex.new-' + str(os.getpid()))
                try:
                    with package.extractfile(member) as source, staged.open('xb') as dest: shutil.copyfileobj(source, dest)
                    staged.chmod(0o755); staged.replace(target)
                finally:
                    staged.unlink(missing_ok=True)
    elif agent == 'claude':
        family = distro.get('ID', '')
        prerequisites = {'ubuntu':'sudo apt install bash curl', 'debian':'sudo apt install bash curl', 'linuxmint':'sudo apt install bash curl', 'pop':'sudo apt install bash curl', 'fedora':'sudo dnf install bash curl', 'rhel':'sudo dnf install bash curl', 'rocky':'sudo dnf install bash curl', 'almalinux':'sudo dnf install bash curl', 'arch':'sudo pacman -S bash curl', 'opensuse-leap':'sudo zypper install bash curl', 'opensuse-tumbleweed':'sudo zypper install bash curl', 'alpine':'apk add bash curl libgcc libstdc++ ripgrep'}
        if family == 'alpine': os.environ['USE_BUILTIN_RIPGREP'] = '0'
        required = ['bash', 'curl'] + (['rg'] if family == 'alpine' else [])
        if any(not shutil.which(cmd) for cmd in required):
            raise RuntimeError('Prerequisites missing. Run ' + prerequisites.get(family, 'your package manager to install bash and curl') + ', then retry.')
        with tempfile.TemporaryDirectory(prefix='dazedev-install-') as temp:
            script = pathlib.Path(temp) / 'install.sh'
            urllib.request.urlretrieve('https://claude.ai/install.sh', script)
            subprocess.run(['bash', str(script)], check=True)
    else:
        npm = shutil.which('npm')
        if not npm: raise RuntimeError('Install Node.js 20 or newer and npm with your host package manager, then retry. No system packages were changed.')
        major = int(subprocess.check_output(['node', '-p', 'process.versions.node.split(".")[0]'], text=True).strip())
        if major < 20: raise RuntimeError('Node.js 20 or newer is required. Upgrade Node.js on this host, then retry.')
        package = {'gemini':'@google/gemini-cli', 'opencode':'opencode-ai'}[agent]
        subprocess.run([npm, 'install', '--prefix', str(home / '.local'), '-g', package], check=True)
if not shutil.which(agent): raise RuntimeError(agent + ' was not found after installation. Review the installer output above.')
print(agent + ' ready. Complete authentication in its interface.', flush=True)
"""
_operation_deadline = threading.local()
def run(args, timeout=20, env=None, cwd=None, check=True):
    deadline = getattr(_operation_deadline, 'end', None)
    if deadline is not None:
        timeout = min(timeout, deadline - time.monotonic())
        if timeout <= 0: raise TimeoutError('Host discovery deadline exceeded')
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=env, cwd=cwd)
    if check and p.returncode: raise RuntimeError((p.stderr or p.stdout or 'Command failed')[-3000:].strip())
    return p.stdout.strip()
def tmux(*args, **kwargs): return run(['tmux'] + (['-L', SOCKET] if SOCKET else []) + list(args), **kwargs)
def config_dir():
    x = os.environ.get('XDG_CONFIG_HOME', '')
    return (P(x) if x.startswith('/') else P.home()/'.config')/'dazeddev'
def empty(): return {'schema': SCHEMA, 'revision': 0, 'preferences': {'roots': [str(P.home()/'dev')], 'guidance': '', 'identities': []}, 'workspaces': [], 'discovery': {}}
def read_doc():
    p = config_dir()/'host.json'
    if not p.exists(): return {'schema': SCHEMA, 'machines': {}}
    d = json.loads(p.read_text())
    if d.get('schema') != SCHEMA: raise RuntimeError('Unsupported host.json schema; existing file preserved')
    if not isinstance(d.get('machines'), dict): raise RuntimeError('Malformed host.json; existing file preserved')
    return d
def load_profile():
    d = read_doc(); return d['machines'].get(profile_key(), empty())
def save_profile(profile, expected):
    folder = config_dir(); folder.mkdir(mode=0o700, parents=True, exist_ok=True); os.chmod(folder,0o700)
    with open(folder/'host.lock', 'a') as lock:
        os.chmod(folder/'host.lock', 0o600); fcntl.flock(lock, fcntl.LOCK_EX)
        doc = read_doc(); old = doc['machines'].get(profile_key(), empty())
        if old['revision'] != expected: raise RuntimeError('CONFLICT: workspace changed on another device. Reload or keep your recovery copy.')
        if not isinstance(profile.get('workspaces'), list) or not isinstance(profile.get('preferences'), dict): raise ValueError('Invalid profile')
        profile.update(schema=SCHEMA, revision=expected+1); doc['machines'][profile_key()] = profile
        p = folder/'host.json'
        if p.exists(): shutil.copyfile(p, folder/'host.json.bak'); os.chmod(folder/'host.json.bak', 0o600)
        fd, temp = tempfile.mkstemp(prefix='.host-', dir=folder)
        try:
            with os.fdopen(fd, 'w') as out: json.dump(doc, out); out.flush(); os.fsync(out.fileno())
            os.replace(temp, p)
            dirfd=os.open(folder,os.O_RDONLY); os.fsync(dirfd); os.close(dirfd)
        finally:
            if os.path.exists(temp): os.unlink(temp)
        return profile

def sessions():
    if not shutil.which('tmux'): return []
    raw=tmux('list-sessions','-F','#{session_id}\t#{session_name}\t#{session_windows}\t#{session_attached}\t#{pid}:#{start_time}:#{session_created}', check=False)
    return [dict(id=v[0], name=v[1], windows=int(v[2]), attached=int(v[3]), stamp=v[4]) for line in raw.splitlines() if len(v:=line.split('\t'))==5]
def session_agent(value):
    if not re.fullmatch(r'\$\d+', str(value)): raise ValueError('Invalid session ID')
    parts=tmux('display-message','-p','-t',value,'#{pane_current_command}\t#{pane_pid}',check=False).split('\t')
    if parts and parts[0] in AGENTS: return parts[0]
    if len(parts)!=2 or not parts[1].isdigit(): return 'shell'
    queue=[parts[1]]; visited=set()
    while queue and len(visited)<64:
        pid=queue.pop(); visited.add(pid)
        try:
            base=P('/proc')/pid
            if base.stat().st_uid != os.getuid(): continue
            argv=(base/'cmdline').read_bytes().split(b'\0')
            for raw in argv[:3]:
                name=P(raw.decode(errors='replace')).name
                if name in AGENTS: return name
            children=(base/'task'/pid/'children').read_text().split()
            queue.extend(child for child in children if child not in visited)
        except OSError: pass
    return 'shell'

def session_id(value, stamp=None):
    if not re.fullmatch(r'\$\d+',str(value)): raise ValueError('Invalid session ID')
    current=next((s for s in sessions() if s['id']==value),None)
    if not current: raise RuntimeError('Session no longer exists; reconnect will not recreate it')
    if not stamp or current['stamp']!=stamp: raise RuntimeError('Session changed; refresh the session list before changing or destroying it')
    return value

def repo_info(path):
    path=P(path).expanduser().resolve()
    top=run(['git','-C',str(path),'rev-parse','--show-toplevel'])
    return {'path':top,'name':path.name,'branch':run(['git','-C',top,'branch','--show-current']), 'dirty':bool(run(['git','-C',top,'status','--porcelain'])), 'remote':run(['git','-C',top,'remote','get-url','origin'],check=False), 'worktrees':run(['git','-C',top,'worktree','list','--porcelain'])}
def repos(roots, warnings=None):
    warnings=warnings if warnings is not None else []
    def warn(message):
        if message not in warnings: warnings.append(message)
    found=[];seen=set();deadline=min(time.monotonic()+20,getattr(_operation_deadline,'end',float('inf')))
    for root in roots:
        root=P(root).expanduser()
        if not root.is_dir():
            warn('A configured repository root is missing or inaccessible.');continue
        for d, dirs, files in os.walk(root, onerror=lambda e: warn('A repository directory could not be read.')):
            if time.monotonic()>deadline:
                warn('Repository discovery stopped after 20 seconds. Narrow the roots and refresh.');return found
            rel=len(P(d).relative_to(root).parts)
            dirs[:]=[x for x in dirs if x not in ['node_modules','target','.cache','.venv','vendor'] and not x.startswith('.')]
            if rel>=5:
                if dirs: warn('Nested directories beyond depth 5 were skipped. Add a more specific root to include them.')
                dirs[:]=[]
            if (P(d)/'.git').exists():
                try:
                    info=repo_info(d)
                    if info['path'] not in seen: found.append(info);seen.add(info['path'])
                except Exception: warn('A repository could not be inspected. Check permissions and Git availability.')
            if len(found)>=500:
                warn('Repository discovery reached 500 results. Narrow the configured roots.');return found
    return found

def repository_worktrees(paths):
    if not isinstance(paths, list) or len(paths) > 500 or any(not isinstance(p, str) for p in paths):
        raise ValueError('Expected at most 500 repository paths')
    groups=[]; seen=set(); warnings=[]; deadline=time.monotonic()+5
    def git(path, *args):
        remaining=deadline-time.monotonic()
        if remaining <= 0: raise TimeoutError()
        result=subprocess.run(['git','-C',str(path),*args],capture_output=True,timeout=min(remaining,2),check=True)
        return result.stdout.decode('utf-8',errors='replace')
    for path in paths:
        try:
            key=git(path,'rev-parse','--path-format=absolute','--git-common-dir').strip()
            if key in seen: continue
            seen.add(key)
            raw=git(path,'worktree','list','--porcelain','-z')
            entries=[]
            for record in raw.split('\0\0'):
                fields=dict(field.partition(' ')[::2] for field in record.split('\0') if field)
                target=fields.get('worktree')
                if not target or 'bare' in fields or not P(target).is_dir(): continue
                modified=None; partial=False
                try:
                    files=git(target,'ls-files','-z','--cached','--others','--exclude-standard').split('\0')
                    for index,name in enumerate(files):
                        if index >= 20000 or time.monotonic() >= deadline:
                            partial=True;break
                        if not name: continue
                        try:
                            # lstat never follows symlinks into unrelated directories.
                            stamp=(P(target)/name).lstat().st_mtime
                            modified=stamp if modified is None else max(modified,stamp)
                        except OSError: pass
                except (OSError,subprocess.SubprocessError,TimeoutError): partial=True
                entries.append({'path':target,'branch':fields.get('branch','').removeprefix('refs/heads/'),
                    'modifiedAt':modified,'partial':partial,'locked':'locked' in fields})
            groups.append({'repositoryId':key,'name':P(entries[0]['path']).name if entries else P(path).name,'worktrees':entries})
        except (OSError,subprocess.SubprocessError,TimeoutError):
            if 'Some worktrees could not be inspected. Refresh to retry.' not in warnings:
                warnings.append('Some worktrees could not be inspected. Refresh to retry.')
        if time.monotonic() >= deadline:
            warnings.append('Worktree inspection reached its 5 second limit. Narrow repository roots for a complete list.')
            break
    return {'groups':groups,'warnings':warnings}

def identity_env(login):
    env=os.environ.copy()
    if not login: return env
    if not re.fullmatch(r'[a-zA-Z0-9-]+', login): raise ValueError('Invalid GitHub login')
    token=run(['gh','auth','token','--hostname','github.com','--user',login])
    env['GH_TOKEN']=token
    actual=json.loads(run(['gh','api','user'],env=env))
    if actual['login'].lower()!=login.lower(): raise RuntimeError('GitHub credential identity does not match selection')
    name=actual.get('name') or actual['login'];email=f"{actual['id']}+{actual['login']}@users.noreply.github.com"
    env.update(GIT_AUTHOR_NAME=name,GIT_AUTHOR_EMAIL=email,GIT_COMMITTER_NAME=name,GIT_COMMITTER_EMAIL=email)
    return env

def agent_path(name):
    return shutil.which(name) or next((str(p) for p in [P.home()/'.local/bin'/name, P.home()/'.opencode/bin'/name] if p.is_file() and os.access(p,os.X_OK)), None)

def inventory():
    try: profile=load_profile(); profile_error=''
    except (OSError,ValueError,RuntimeError) as e: profile=empty(); profile_error=str(e)
    agents=[]
    for name in AGENTS:
        path=agent_path(name); version=''; helptext=''
        if path:
            try: version=run([path,'--version'],timeout=8)[:160];helptext=run([path,'--help'],timeout=8,check=False)
            except Exception as e: version=str(e)[:160]
        models=[]
        if name=='codex':
            cache=P.home()/'.codex/models_cache.json'
            if cache.exists():
                try: models=[{'id':m['slug'],'name':m.get('display_name',m['slug']),'efforts':[e.get('reasoning_effort',e.get('effort','')) for e in m.get('supported_reasoning_levels',[])]} for m in json.loads(cache.read_text()).get('models',[]) if m.get('slug')]
                except Exception: pass
        if name=='opencode' and path:
            try: models=[{'id':m,'name':m,'efforts':[]} for m in run([path,'models'],timeout=10,check=False).splitlines() if '/' in m and '\x1b' not in m]
            except Exception: pass
        agents.append({'id':name,'path':path,'version':version,'models':models,'modelFlag':'--model' in helptext,'effortFlag':'--effort' in helptext or name=='codex','install':('Official native user installer' if name in ['codex','claude'] else ' '.join(AGENTS[name]))})
    identities=[]
    if shutil.which('gh'):
        try:
            status=json.loads(run(['gh','auth','status','--json','hosts'],check=False))
            identities=[{'login':a['login'],'active':a.get('active',False)} for a in status.get('hosts',{}).get('github.com',[]) if a.get('login')]
        except Exception: pass
    warnings=[]; found=repos(profile['preferences'].get('roots',[]),warnings)
    return {'warnings':list(dict.fromkeys(warnings)), 'hostname':socket.gethostname(),'user':getpass.getuser(),'machine':MACHINE,'home':str(P.home()),'tmux':shutil.which('tmux'),'agents':agents,'identities':identities,'repos':found, 'sessions':sessions(),'observedAt':time.time(), 'profileError':profile_error}

def capabilities():
    # The companion reads only local CLI metadata; model enumeration must not contact providers.
    agents=[]
    for name in AGENTS:
        path=agent_path(name)
        if not path: continue
        try:
            version=run([path,'--version'],timeout=5,check=False)[:160]
            helptext=run([path,'--help'],timeout=5,check=False)
        except Exception:
            version='';helptext=''
        models=[]
        if name=='codex':
            cache=P.home()/'.codex/models_cache.json'
            try:
                models=[{'id':m['slug'],'efforts':[e.get('reasoning_effort',e.get('effort','')) for e in m.get('supported_reasoning_levels',[])]} for m in json.loads(cache.read_text()).get('models',[]) if m.get('slug')]
            except (OSError,ValueError,KeyError): pass
        agents.append({'id':name,'version':version,'models':models,'modelFlag':'--model' in helptext,'effortFlag':'--effort' in helptext or name=='codex'})
    return {'agents':agents,'protocol':1,'transport':'ssh-tmux','features':['profiles','sessions','repositories','tmux-settings']}

def create_session(a):
    name=a['name']
    if not re.fullmatch(r'[\w-]+',name): raise ValueError('Use letters, numbers, underscores and hyphens for session names')
    path=P(a.get('path') or str(P.home())).expanduser().resolve()
    if not path.is_dir(): raise ValueError('Working directory does not exist')
    # A caller-supplied operation key makes retries idempotent across disconnects.
    op=a.get('operation','')
    if not re.fullmatch(r'[a-zA-Z0-9-]{8,100}',op): raise ValueError('A launch operation ID is required')
    for s in sessions():
        if tmux('show-options','-qv','-t',s['id'],'@dazed-operation',check=False)==op: return s
    agent=a.get('agent','shell');model=a.get('model','');effort=a.get('effort','');login=a.get('identity','')
    env=identity_env(login)
    if agent not in ['shell',*AGENTS]: raise ValueError('Unknown agent')
    prompt=a.get('brief','')
    args=[]
    if agent!='shell':
        exe=agent_path(agent)
        if not exe and not a.get('install'): raise RuntimeError(f'{agent} is not installed on this host')
        if not exe and (model or effort): raise ValueError('Install the agent before selecting non-default model or effort')
        helptext=run([exe,'--help'],timeout=8,check=False) if exe else ''
        args=[exe or agent]
        if model:
            if '--model' not in helptext: raise ValueError('This agent version does not advertise --model')
            args+=['--model',model]
        if effort:
            if agent=='codex': args+=['-c',f'model_reasoning_effort={json.dumps(effort)}']
            elif '--effort' in helptext: args+=['--effort',effort]
            else: raise ValueError('This agent does not advertise effort selection')
        if prompt:
            if agent=='gemini': args+=['--prompt-interactive',prompt]
            elif agent=='opencode': args+=['--prompt',prompt]
            else: args.append(prompt)
    # Resolve credentials inside the new child. Only the account name travels in argv.
    bootstrap = "import os,subprocess,json,shutil; "
    if a.get('install') and agent != 'shell':
        bootstrap += "INSTALL_AGENT="+repr(agent)+"; exec("+repr(INSTALLER)+"); "
    if login:
        bootstrap += "t=subprocess.check_output(['gh','auth','token','--hostname','github.com','--user',"+repr(login)+"],text=True).strip(); os.environ['GH_TOKEN']=t; "
        bootstrap += "u=json.loads(subprocess.check_output(['gh','api','user'],text=True)); assert u['login'].lower()=="+repr(login.lower())+"; "
        bootstrap += "n=u.get('name') or u['login']; e=str(u['id'])+'+'+u['login']+'@users.noreply.github.com'; os.environ.update(GIT_AUTHOR_NAME=n,GIT_COMMITTER_NAME=n,GIT_AUTHOR_EMAIL=e,GIT_COMMITTER_EMAIL=e); "
    executable = args[0] if args else os.environ.get('SHELL','/bin/sh')
    argv = args or [executable]
    bootstrap += 'os.execv(shutil.which('+repr(executable)+') or '+repr(executable)+','+repr(argv)+')'
    # Keep installation/authentication failures visible in a usable shell.
    bootstrap = 'try:\n '+bootstrap+'\nexcept Exception as e:\n print(\"DazeDev: \"+str(e), flush=True)\n os.execl(os.environ.get(\"SHELL\",\"/bin/sh\"), os.environ.get(\"SHELL\",\"/bin/sh\"), \"-l\")'
    encoded=base64.b64encode(bootstrap.encode()).decode()
    command='python3 -c '+shlex.quote('import base64;exec(base64.b64decode('+repr(encoded)+'))')
    if BROWSER:
        browser = BROWSER.environment()['command']
        BROWSER.launch(op)
        command = 'export BROWSER='+shlex.quote(browser)+'; export PATH='+shlex.quote(str(P(browser).parent))+':"$PATH"; export DAZEDEV_BROWSER_ENDPOINT='+shlex.quote(BROWSER.endpoint)+' DAZEDEV_BROWSER_LAUNCH='+shlex.quote(op)+'; '+command
    # Ctrl-C reaches both the agent and its noninteractive launcher. Catch it
    # in the launcher so the child exits normally into a usable login shell.
    if agent != 'shell': command = 'trap : INT; '+command+'; exec "${SHELL:-/bin/sh}" -l'
    # Create a held shell, configure it, then start the agent without typing into its input.
    sid=tmux('new-session','-d','-P','-F','#{session_id}','-s',name,'-c',str(path),'sleep 86400')
    tmux('set-option','-t',sid,'@dazed-operation',op)
    if BROWSER:
        tmux('set-environment','-t',sid,'BROWSER',browser)
        tmux('set-environment','-t',sid,'PATH',str(P(browser).parent)+':'+os.environ.get('PATH',''))
    tmux('set-window-option','-t',sid,'remain-on-exit','off')
    tmux('respawn-pane','-k','-t',sid,'-c',str(path),command)
    return next(s for s in sessions() if s['id']==sid)

def process_context(pid):
    pid=int(pid)
    if pid<=0: raise ValueError('Terminal process is not ready')
    seen=set();pending=[pid];parts=[]
    if sys.platform == 'darwin':
        # BSD ps supplies stable process start time and executable identity without /proc.
        snapshot=run(['/bin/ps','-axo','pid=,ppid=,pgid=,lstart=,comm='], timeout=10, env=dict(os.environ, LC_ALL='C'))
        rows={};children={}
        for line in snapshot.splitlines():
            fields=line.split(None,8)
            if len(fields)!=9 or not all(f.isdigit() for f in fields[:3]): continue
            current,parent,group=map(int,fields[:3])
            rows[current]=str(current)+':'+str(group)+':'+':'.join(fields[3:])
            children.setdefault(parent,[]).append(current)
        if pid not in rows: raise RuntimeError('Terminal process context unavailable; dictation cannot verify its destination')
        while pending and len(seen)<128:
            current=pending.pop()
            if current in seen: continue
            seen.add(current);parts.append(rows[current]);pending.extend(children.get(current,[]))
    else:
        proc=P('/proc')
        if not (proc/str(pid)/'stat').exists(): raise RuntimeError('Terminal process context unavailable; dictation cannot verify its destination')
        while pending and len(seen)<128:
            current=pending.pop()
            if current in seen: continue
            seen.add(current)
            try:
                stat=(proc/str(current)/'stat').read_text();rest=stat[stat.rfind(')')+2:].split()
                # comm + PID + start time + process group identify program replacement.
                parts.append(str(current)+':'+stat[stat.find('('):stat.rfind(')')+1]+':'+rest[19])
                pending.extend(int(p) for p in (proc/str(current)/'task'/str(current)/'children').read_text().split())
            except (OSError,ValueError,IndexError): raise RuntimeError('Terminal program changed while checking its destination')
    if pending: raise RuntimeError('Terminal process tree is too large to verify safely')
    # A manually attached tmux client has its own server-owned foreground process.
    for line in tmux('list-clients','-F','#{client_pid} #{session_id}',check=False).splitlines():
        fields=line.split()
        if len(fields)==2 and fields[0].isdigit() and int(fields[0]) in seen:
            parts.append(tmux('display-message','-p','-t',fields[1],'#{session_id}:#{window_id}:#{pane_id}:#{pane_pid}:#{pane_current_command}:#{pane_dead}'))
    return '|'.join(sorted(parts))

# File operations are explicit user actions on the selected authenticated host.
# No shell interpolation, automatic execution, or implicit overwrite.
UPLOADS = {}
MAX_UPLOAD = 256 * 1024 * 1024
MAX_PASTE_UPLOAD = 10 * 1024 * 1024
UPLOAD_HEADROOM = 16 * 1024 * 1024
MAX_DOCUMENT = 512 * 1024

def upload_size(a):
    size = a.get('size')
    maximum = MAX_PASTE_UPLOAD if a.get('paste') is True else MAX_UPLOAD
    if type(size) is not int or size < 0:
        raise ValueError('File size must be a nonnegative whole number of bytes')
    if size > maximum:
        raise ValueError('Quick paste is limited to 10 MiB; use Upload for larger files' if a.get('paste') is True else 'Files must be at most 256 MiB')
    return size, maximum

def upload_folder(a):
    if a.get('paste') is True:
        base = P(os.environ.get('XDG_DATA_HOME') or P.home()/'.local/share').expanduser()
        if not base.is_absolute(): base = P.home()/'.local/share'
        folder = base/'dazedev'/'uploads'
        # Completed attachments are retained here until the user removes them.
        folder.mkdir(parents=True, mode=0o700, exist_ok=True)
    else:
        folder = P(a['directory']).expanduser()
    folder = folder.resolve(strict=True)
    if not folder.is_dir(): raise ValueError('Destination must be an existing directory')
    return folder

def upload_space(folder, size, excluding=None):
    device = folder.stat().st_dev
    reserved = sum(item['size'] - item['offset'] for key, item in UPLOADS.items()
                   if key != excluding and item['device'] == device)
    free = shutil.disk_usage(folder).free
    available = max(0, free - reserved)
    required = size + UPLOAD_HEADROOM
    if available < required:
        raise ValueError('Not enough free space on destination: ' + str(available) +
                         ' bytes available; ' + str(required) +
                         ' bytes required including 16 MiB free-space headroom')
    return dict(freeBytes=free, availableBytes=available, reservedBytes=reserved, requiredBytes=required)

def upload_expire():
    # Only incomplete transfers owned by this helper can expire.
    for key, value in list(UPLOADS.items()):
        if time.monotonic() - value['touched'] > 1800: upload_abort({'id':key})

def upload_preflight(a):
    size, maximum = upload_size(a)
    folder = upload_folder(a)
    upload_expire()
    return dict(directory=str(folder), maxBytes=maximum, **upload_space(folder, size))

def upload_begin(a):
    name = a['name']
    if not isinstance(name, str) or not name or name in ('.', '..') or '/' in name or '\\' in name or any(ord(c) < 32 for c in name):
        raise ValueError('Choose a regular filename')
    size, _ = upload_size(a)
    folder = upload_folder(a)
    if a.get('paste') is True: name = uuid.uuid4().hex + '-' + name
    target = folder / name
    if target.exists() or target.is_symlink(): raise ValueError('Destination already exists; choose another name')
    upload_expire()
    if len(UPLOADS) >= 4: raise ValueError('Finish or cancel another upload first')
    upload_space(folder, size)
    fd, path = tempfile.mkstemp(prefix='.dazedev-upload-', dir=folder)
    os.close(fd)
    key = uuid.uuid4().hex
    UPLOADS[key] = dict(path=P(path), target=target, size=size, offset=0,
                        device=folder.stat().st_dev, touched=time.monotonic())
    return {'id':key, 'path':str(target)}

def upload_chunk(a):
    item = UPLOADS[a['id']]
    data = base64.b64decode(a['data'], validate=True)
    if len(data) > 32768 or type(a['offset']) is not int or a['offset'] != item['offset'] or item['offset'] + len(data) > item['size']:
        raise ValueError('Upload offset or chunk size does not match; cancel and retry')
    upload_space(item['path'].parent, item['size'] - item['offset'], excluding=a['id'])
    try:
        with item['path'].open('ab') as out: out.write(data)
    except OSError:
        upload_abort({'id':a['id']})
        raise
    item['offset'] += len(data); item['touched'] = time.monotonic()
    return {'offset':item['offset']}

def upload_finish(a):
    item = UPLOADS[a['id']]
    if item['offset'] != item['size'] or item['path'].stat().st_size != item['size']: raise ValueError('Upload incomplete')
    with item['path'].open('rb') as source: os.fsync(source.fileno())
    # Atomic no-clobber publication, including a concurrent destination creation.
    os.link(item['path'], item['target'])
    item['path'].unlink(); del UPLOADS[a['id']]
    return {'path':str(item['target']), 'size':item['size']}

def upload_abort(a):
    item = UPLOADS.pop(a['id'], None)
    if item: item['path'].unlink(missing_ok=True)
    return True

def cleanup_uploads():
    for key in list(UPLOADS): upload_abort({'id':key})
import atexit
atexit.register(cleanup_uploads)

def document_path(value):
    path = P(value).expanduser().resolve(strict=True)
    if (path.suffix.lower() not in ('.md', '.mdc', '.markdown') and path.name not in ('.cursorrules','.clinerules')) or not path.is_file():
        raise ValueError('Choose an existing Markdown or agent-rule file (.md, .mdc, .markdown)')
    return path

MAX_IMAGE = 16 * 1024 * 1024
IMAGE_CHUNK = 49152

def image_mime(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n'): return 'image/png'
    if data.startswith(b'\xff\xd8\xff'): return 'image/jpeg'
    if data[:6] in (b'GIF87a', b'GIF89a'): return 'image/gif'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP': return 'image/webp'
    if data[:2] == b'BM': return 'image/bmp'
    if data[:4] == b'\x00\x00\x01\x00': return 'image/x-icon'
    if data[4:8] == b'ftyp' and data[8:12] in (b'avif', b'avis'): return 'image/avif'
    raise ValueError('Choose a PNG, JPEG, GIF, WebP, BMP, ICO or AVIF image')

def viewer_path(a):
    path = P(a['path']).expanduser()
    if path.is_absolute(): return path.resolve(strict=True)
    base = P(a.get('directory') or P.home()).expanduser()
    if not base.is_absolute(): base = P.home() / base
    try: return (base / path).resolve(strict=True)
    except FileNotFoundError:
        # Bare agent paths may be relative to home; explicit dot paths are not.
        if a['path'].startswith(('./', '../')) or base == P.home(): raise
        return (P.home() / path).resolve(strict=True)

def image_read(a):
    path = viewer_path(a)
    if not path.is_file(): raise ValueError('Choose an existing regular image file')
    offset = a.get('offset', 0)
    if type(offset) is not int or offset < 0: raise ValueError('Invalid image offset')
    with path.open('rb') as source:
        before = os.fstat(source.fileno())
        if not 0 < before.st_size <= MAX_IMAGE: raise ValueError('Image exceeds 16 MiB or is empty')
        revision = str(before.st_size) + ':' + str(before.st_mtime_ns)
        if a.get('revision', revision) != revision: raise ValueError('Image changed; reopen it')
        if offset >= before.st_size: raise ValueError('Invalid image offset')
        mime = image_mime(source.read(16))
        source.seek(offset)
        data = source.read(IMAGE_CHUNK)
        after = os.fstat(source.fileno())
        if before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns:
            raise ValueError('Image changed; reopen it')
    return {'path':str(path), 'mime':mime, 'revision':revision, 'size':before.st_size,
            'offset':offset, 'data':base64.b64encode(data).decode('ascii')}

def file_read(a):
    # Explicit user-selected text only; no directories, devices, pipes or binary data.
    path = viewer_path(a)
    if not path.is_file(): raise ValueError('Choose an existing regular text file')
    with path.open('rb') as source: data = source.read(MAX_DOCUMENT + 1)
    if len(data) > MAX_DOCUMENT: raise ValueError('File exceeds 512 KiB')
    if b'\0' in data: raise ValueError('Binary files cannot be shown as source')
    try: text = data.decode('utf-8')
    except UnicodeDecodeError: raise ValueError('Choose a UTF-8 text file')
    # Preserve the existing edit scope; other source files are viewers only.
    editable = path.suffix.lower() in ('.md', '.mdc', '.markdown') or path.name in ('.cursorrules', '.clinerules')
    return {'path':str(path), 'text':text, 'revision':hashlib.sha256(data).hexdigest(), 'writable':editable and os.access(path, os.W_OK) and os.access(path.parent, os.W_OK)}

def document_read(a):
    path = document_path(a['path'])
    with path.open('rb') as source: data = source.read(MAX_DOCUMENT + 1)
    if len(data) > MAX_DOCUMENT: raise ValueError('Document exceeds 512 KiB')
    return {'path':str(path), 'text':data.decode('utf-8'), 'revision':hashlib.sha256(data).hexdigest(), 'writable':os.access(path, os.W_OK) and os.access(path.parent, os.W_OK)}

def document_save(a):
    path = document_path(a['path'])
    data = a['text'].encode('utf-8')
    if len(data) > MAX_DOCUMENT: raise ValueError('Document exceeds 512 KiB')
    # Lock the actual inode; compare content again immediately before replacement.
    with path.open('rb') as original:
        fcntl.flock(original, fcntl.LOCK_EX)
        before = document_read({'path':str(path)})
        if not before['writable']: raise PermissionError('File is read only')
        if before['revision'] != a['revision']: raise RuntimeError('CONFLICT: file changed on the host. Copy your edits before reloading.')
        fd, staged = tempfile.mkstemp(prefix='.dazedev-edit-', dir=path.parent)
        try:
            os.fchmod(fd, path.stat().st_mode & 0o777)
            with os.fdopen(fd, 'wb') as out: out.write(data); out.flush(); os.fsync(out.fileno())
            if document_read({'path':str(path)})['revision'] != a['revision']: raise RuntimeError('CONFLICT: file changed on the host. Edits were not saved.')
            os.replace(staged, path)
        finally:
            if os.path.exists(staged): os.unlink(staged)
    return document_read({'path':str(path)})

def documents(a):
    root = P(a.get('path') or str(P.home())).expanduser().resolve(strict=True)
    if not root.is_dir(): raise ValueError('Choose a directory to discover guidance')
    found = {}; warnings = []; deadline = time.monotonic() + 5
    names = ('AGENTS.md','AGENTS.override.md','CLAUDE.md','CLAUDE.local.md','GEMINI.md','.cursorrules','.clinerules')
    def add(path, scope, source=''):
        try:
            if not path.is_file() or (path.suffix.lower() not in ('.md','.mdc','.markdown') and path.name not in names): return
            resolved = path.resolve()
            if str(resolved) in found or len(found) >= 300: return
            found[str(resolved)] = {'path':str(resolved),'scope':scope,'source':source,'readable':os.access(resolved,os.R_OK),'writable':os.access(resolved,os.W_OK) and os.access(resolved.parent,os.W_OK)}
        except OSError: warnings.append('Some rule paths could not be inspected.')
    for path in [P('/etc/claude-code/CLAUDE.md'), P('/etc/AGENTS.md')]: add(path,'System')
    home = P.home()
    for path in [home/'AGENTS.md',home/'.codex/AGENTS.md',home/'.codex/AGENTS.override.md',home/'.claude/CLAUDE.md',home/'.gemini/GEMINI.md',P(os.environ.get('CODEX_HOME',str(home/'.codex')))/'AGENTS.md',home/'.config/opencode/AGENTS.md']:
        add(path,'User')
    for rules in [home/'.claude/rules',home/'.config/opencode/rules']:
        if rules.is_dir():
            for path in rules.glob('**/*.md'):
                if time.monotonic() > deadline or len(found) >= 300: break
                add(path,'User')
    for parent in reversed([root,*root.parents]):
        for name in names: add(parent/name,'Repository' if parent == root else 'Ancestor')
    for directory, folders, files in os.walk(root, followlinks=False):
        if time.monotonic() > deadline or len(found) >= 300:
            warnings.append('Discovery limited to 5 seconds and 300 files. Open a narrower directory for more.'); break
        p=P(directory); depth=len(p.relative_to(root).parts)
        folders[:]=[n for n in folders if n not in ('.git','node_modules','target','vendor','.venv','dist') and (not n.startswith('.') or n in ('.claude','.cursor','.github','.codex','.agents','.gemini'))]
        if depth >= 6: folders[:]=[]
        for name in files:
            path=p/name
            if name in names or (path.suffix.lower() in ('.md','.mdc','.markdown') and (depth == 0 or any(n in ('.claude','.cursor','.github','.codex','.agents','.gemini') for n in path.relative_to(root).parts))):
                add(path,'Repository' if depth == 0 else 'Nested')
    # Follow only explicit Markdown references, never URLs, executable imports or arbitrary commands.
    queue=list(found)
    for key in queue:
        if len(found) >= 300 or time.monotonic() > deadline: break
        try: text=document_read({'path':key})['text']
        except (OSError,ValueError,UnicodeError): continue
        links=re.findall(r'\]\(([^)\n]+)\)|`([^`\n]+\.(?:md|mdc|markdown))`|(?:^|\s)@([^\s]+\.(?:md|mdc|markdown))',text)
        for match in links:
            raw=next(v for v in match if v).strip('<>').split('#')[0]
            if not raw or '://' in raw or '{{' in raw: continue
            path=P(raw).expanduser()
            if not path.is_absolute(): path=P(key).parent/path
            old=set(found); add(path,'Referenced',key)
            queue.extend(k for k in found if k not in old)
    return {'files':list(found.values()),'warnings':list(dict.fromkeys(warnings))}

def terminal_status(a):
    unknown={'attachment':'unknown','nestedSsh':False,'hostname':None}
    pid=int(a['pid'])
    if pid <= 0: return unknown
    # Read executable names, not command arguments (which may contain secrets).
    rows={};children={}
    snapshot=run(['ps','-axo','pid=,ppid=,uid=,comm='],timeout=3)
    for line in snapshot.splitlines():
        fields=line.split(None,3)
        if len(fields)!=4 or not all(n.isdigit() for n in fields[:3]): continue
        current,parent,owner=map(int,fields[:3])
        if owner != os.getuid(): continue
        rows[current]=P(fields[3]).name;children.setdefault(parent,[]).append(current)
    if pid not in rows: return unknown
    def descend(root):
        seen=set();pending=[root]
        while pending and len(seen)<128:
            current=pending.pop()
            if current in seen: continue
            seen.add(current);pending.extend(children.get(current,[]))
        return seen
    seen=descend(pid); attached=False; session_id=None; stamp=None; session_name=None; agent=None; pane_id=None; pane_dead=False
    if shutil.which('tmux'):
        for line in tmux('list-clients','-F','#{client_pid}\t#{session_id}\t#{pane_pid}\t#{pane_id}\t#{pane_dead}',check=False,timeout=3).splitlines():
            fields=line.split('\t')
            if len(fields) in (3,5) and fields[0].isdigit() and int(fields[0]) in seen:
                attached=True; session_id=fields[1]
                if len(fields)==5:
                    pane_id=fields[3] if re.fullmatch(r'%\d+',fields[3]) else None
                    pane_dead=fields[4]=='1'
                stamp=tmux('display-message','-p','-t',session_id,'#{pid}:#{start_time}:#{session_created}',check=False,timeout=3).strip() or None
                session_name=tmux('display-message','-p','-t',session_id,'#{session_name}',check=False,timeout=3).strip() or None
                if not pane_dead and fields[2].isdigit():
                    pane_processes=descend(int(fields[2]));seen.update(pane_processes)
                    # Only executable names from this pane determine its agent label.
                    agents={rows[p] for p in pane_processes if rows.get(p) in AGENTS}
                    agent=next(iter(agents)) if len(agents)==1 else 'shell'
    nested=any(rows.get(p) in ('ssh','mosh','mosh-client') for p in seen)
    if nested: return dict(unknown,nestedSsh=True)
    if not attached and any(rows.get(p, '').startswith('tmux') for p in seen): return unknown
    directory=None
    if not attached:
        # The deepest same-user shell is the current interactive shell, not its launcher.
        shell_names={'sh','bash','zsh','fish','dash','ksh','tcsh','csh'}
        candidates=[p for p in seen if rows.get(p, '').lstrip('-') in shell_names]
        leaves=[p for p in candidates if not any(other != p and other in descend(p) for other in candidates)]
        if len(leaves)==1:
            try: directory=os.readlink('/proc/%d/cwd' % leaves[0])
            except OSError: pass
    return {'attachment':'tmux' if attached else 'plain','nestedSsh':False,'hostname':socket.gethostname(),'sessionId':session_id,'stamp':stamp,'sessionName':session_name,'agent':agent,'directory':directory,'paneId':pane_id,'paneDead':pane_dead}


def terminal_recover(a):
    sid=session_id(a['id'],a.get('stamp'))
    pane=a.get('paneId','')
    if not re.fullmatch(r'%\d+',str(pane)): raise ValueError('Invalid pane ID')
    fields=tmux('display-message','-p','-t',pane,'#{session_id}\t#{pane_dead}').split('\t')
    if len(fields)!=2 or fields[0]!=sid: raise RuntimeError('Pane belongs to a different session; refresh before recovery')
    if fields[1]!='1': raise RuntimeError('Pane is not dead; its running process was left untouched')
    directory=P(tmux('display-message','-p','-t',pane,'#{pane_current_path}',check=False) or tmux('display-message','-p','-t',pane,'#{pane_start_path}',check=False) or str(P.home())).expanduser()
    if not directory.is_dir(): directory=P.home()
    shell=os.environ.get('SHELL') or '/bin/sh'
    if not shutil.which(shell): shell='/bin/sh'
    # No -k: tmux itself refuses a live pane if it changed after our check.
    tmux('respawn-pane','-t',pane,'-c',str(directory),'exec '+shlex.quote(shell)+' -l')
    return {'sessionId':sid,'paneId':pane,'paneDead':False}

# Values stay allowlisted: tmux formats and configuration files may execute commands.
TMUX_SETTINGS = {
    'status': ('session', ['on','off','2','3','4','5']),
    'status-position': ('session', ['top','bottom']),
    'mouse': ('session', ['on','off']),
    'history-limit': ('session', [100,100000]),
    'set-titles': ('session', ['on','off']),
    'mode-keys': ('window', ['emacs','vi']),
    'automatic-rename': ('window', ['on','off']),
    'allow-rename': ('window', ['on','off']),
    'pane-border-status': ('window', ['off','top','bottom']),
    'window-size': ('window', ['largest','smallest','manual','latest']),
}
def tmux_settings_read(a):
    if not shutil.which('tmux'): raise RuntimeError('tmux is not installed on this host. No changes were made.')
    available=sessions()
    result={'version':tmux('-V'), 'socket':SOCKET or 'default', 'sessions':available, 'windows':[], 'options':[], 'panes':[]}
    if not available: return result
    target=a.get('session','')
    if target: session_id(target,a.get('stamp'))
    else: target=available[0]['id']
    result['server']=tmux('display-message','-p','-t',target,'#{pid}:#{start_time}')
    if a.get('session'):
        raw=tmux('list-windows','-t',target,'-F','#{window_id}\t#{window_name}\t#{window_active}')
        result['windows']=[{'id':v[0],'name':v[1],'active':v[2]=='1'} for line in raw.splitlines() if len(v:=line.split('\t'))==3]
        window=a.get('window') or next((w['id'] for w in result['windows'] if w['active']),None)
        if not any(w['id']==window for w in result['windows']): raise RuntimeError('Window changed; refresh settings.')
        result['window']=window
        raw=tmux('list-panes','-t',window,'-F','#{pane_id}\t#{pane_in_mode}\t#{mouse_any_flag}\t#{alternate_on}')
        result['panes']=[dict(zip(['id','copyMode','mouseReporting','alternateScreen'],line.split('\t'))) for line in raw.splitlines() if len(line.split('\t'))==4]
    for name,(kind,allowed) in TMUX_SETTINGS.items():
        flags=(['-w'] if kind=='window' else []) + (['-t',result['window'] if kind=='window' else target] if a.get('session') else ['-g'])
        try:
            value=tmux('show-options',*flags,'-Av',name)
            local=tmux('show-options',*flags,'-qv',name)
            result['options'].append({'name':name,'kind':kind,'value':value,'local':local,'inherited':bool(a.get('session') and local==''),'allowed':allowed})
        except RuntimeError:
            result['options'].append({'name':name,'kind':kind,'unsupported':True,'allowed':allowed})
    result['revision']=hashlib.sha256(json.dumps(result,sort_keys=True).encode()).hexdigest()
    return result

def tmux_window_select(a):
    if not a.get('session') or not a.get('stamp') or not a.get('window'):
        raise ValueError('A current session, stamp and window are required')
    current = tmux_settings_read(a)
    # Membership and session stamp are checked before changing shared tmux focus.
    tmux('select-window', '-t', current['window'])
    return tmux_settings_read(a)

def tmux_settings_apply(a):
    name=a.get('name')
    if name not in TMUX_SETTINGS: raise ValueError('Unsupported tmux setting')
    kind,allowed=TMUX_SETTINGS[name]
    reset=a.get('reset') is True
    value=a.get('value')
    if reset and not a.get('session'): raise ValueError('Choose a session to restore inheritance.')
    if not reset:
        if isinstance(allowed[0],int):
            if not isinstance(value,str) or not value.isdigit() or not allowed[0]<=int(value)<=allowed[1]: raise ValueError('History must be between 100 and 100000 lines.')
        elif value not in allowed: raise ValueError('Invalid tmux setting value')
    current=tmux_settings_read(a)
    if not current.get('revision') or a.get('revision')!=current['revision']: raise RuntimeError('Settings changed. Refresh before applying.')
    if any(o['name']==name and o.get('unsupported') for o in current['options']): raise ValueError('This tmux version does not support that setting.')
    flags=(['-w'] if kind=='window' else []) + (['-t',current['window'] if kind=='window' else a['session']] if a.get('session') else ['-g'])
    tmux('set-option',*flags,*(['-u'] if reset else []),name,*([] if reset else [value]))
    return tmux_settings_read(a)


def dictionary_validate(value):
    if not isinstance(value,dict) or value.get('schema') != 1 or not isinstance(value.get('entries'),dict) or len(value['entries'])>2000: raise ValueError('Invalid dictionary')
    if len(json.dumps(value).encode())>2000000: raise ValueError('Dictionary is too large')
    for key, versions in value['entries'].items():
        if (key in ('__proto__','constructor','prototype') or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',key)) or not isinstance(versions,list) or not 1<=len(versions)<=30: raise ValueError('Invalid dictionary entry')
        for v in versions:
            if not isinstance(v,dict) or not isinstance(v.get('clock'),dict) or not 1<=len(v['clock'])<=100 or (type(v.get('deleted')) is not bool or ('disabled' in v and type(v['disabled']) is not bool)): raise ValueError('Invalid dictionary revision')
            if any((k in ('__proto__','constructor','prototype') or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',k)) or type(n) is not int or not 1<=n<=9007199254740991 for k,n in v['clock'].items()): raise ValueError('Invalid dictionary clock')
            aliases=v.get('aliases')
            if not isinstance(aliases,list) or not 1<=len(aliases)<=30: raise ValueError('Invalid dictionary aliases')
            if any(not isinstance(t,str) or not t.strip() or len(t)>160 or re.search(r'[\x00-\x1f\x7f]',t) for t in [v.get('preferred')]+aliases): raise ValueError('Invalid dictionary phrase')
    return value

def dictionary_regular(path):
    import stat
    try: info=path.lstat()
    except FileNotFoundError: return
    if not stat.S_ISREG(info.st_mode): raise ValueError('Dictionary paths must be regular files, not symlinks')

def dictionary_read(a=None):
    path=config_dir()/'dictionary.json'
    dictionary_regular(path)
    if not path.exists(): return {'dictionary':None,'revision':''}
    raw=path.read_bytes()
    if len(raw)>2000000: raise ValueError('Dictionary is too large')
    return {'dictionary':dictionary_validate(json.loads(raw)), 'revision':hashlib.sha256(raw).hexdigest()}

def dictionary_write(a):
    value=dictionary_validate(a['dictionary']);folder=config_dir();folder.mkdir(parents=True,exist_ok=True);os.chmod(folder,0o700)
    for name in ['dictionary.json','dictionary.json.bak','dictionary.lock']: dictionary_regular(folder/name)
    lock_fd=os.open(folder/'dictionary.lock',os.O_RDWR|os.O_CREAT|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(lock_fd,'a+') as lock:
        os.chmod(folder/'dictionary.lock',0o600);fcntl.flock(lock,fcntl.LOCK_EX)
        current=dictionary_read()
        if current['revision']!=a.get('revision'): return {'saved':False}
        path=folder/'dictionary.json'
        if path.exists():
            fd,backup=tempfile.mkstemp(prefix='.dictionary-backup-',dir=folder)
            try:
                with os.fdopen(fd,'wb') as out: out.write(path.read_bytes());out.flush();os.fsync(out.fileno())
                os.replace(backup,folder/'dictionary.json.bak')
            finally:
                if os.path.exists(backup): os.unlink(backup)
        fd,temp=tempfile.mkstemp(prefix='.dictionary-',dir=folder)
        try:
            with os.fdopen(fd,'w') as out: json.dump(value,out);out.flush();os.fsync(out.fileno())
            os.replace(temp,path)
        finally:
            if os.path.exists(temp):os.unlink(temp)
        return {'saved':True}

def dispatch(method,a):
    if method == 'browser_prepare':
        if not BROWSER: raise RuntimeError('Desktop browser bridge unavailable: '+(BROWSER_ERROR or 'reconnect this host'))
        return BROWSER.environment()
    if method == 'browser_result':
        return BROWSER.complete(a) if BROWSER else False
    file_methods = {"upload_preflight":upload_preflight,"upload_begin":upload_begin,"upload_chunk":upload_chunk,"upload_finish":upload_finish,"upload_abort":upload_abort,"documents":documents,"file_read":file_read,"image_read":image_read,"document_read":document_read,"document_save":document_save,"terminal_status":terminal_status}
    if method=='terminal_recover': return terminal_recover(a)
    if method=='tmux_settings_read': return tmux_settings_read(a)
    if method=='tmux_window_select': return tmux_window_select(a)
    if method=='tmux_settings_apply': return tmux_settings_apply(a)
    if method in file_methods: return file_methods[method](a)
    if method=='process_context': return process_context(a['pid'])
    if method=='dictionary_read': return dictionary_read(a)
    if method=='dictionary_write': return dictionary_write(a)
    if method=='capabilities': return capabilities()
    if method=='ping': return {'machine':MACHINE,'user':getpass.getuser(),'hostname':socket.gethostname()}
    if method=='profile_load': return load_profile()
    if method=='profile_save': return save_profile(a['profile'],a['revision'])
    if method=='inventory': return inventory()
    if method=='repository_worktrees': return repository_worktrees(a.get('paths',[]))
    if method=='repositories':
        warnings=[]; found=repos(load_profile()['preferences'].get('roots',[]),warnings)
        return {'repos':found,'warnings':warnings}
    if method=='sessions': return [dict(s,agent=session_agent(s['id'])) for s in sessions()]
    if method=='session_context': return tmux('display-message','-p','-t',session_id(a['id'],a.get('stamp')),'#{session_id}:#{window_id}:#{pane_id}:#{pane_pid}:#{pane_current_command}:#{pane_dead}')
    if method=='session_agent': return session_agent(session_id(a['id'],a.get('stamp')))
    if method=='create': return create_session(a)
    if method=='rename':
        if not re.fullmatch(r'[\w-]+',a['name']): raise ValueError('Invalid session name')
        tmux('rename-session','-t',session_id(a['id'],a.get('stamp')),a['name']);return sessions()
    if method=='destroy': tmux('kill-session','-t',session_id(a['id'],a.get('stamp')));return sessions()
    if method=='install':
        raise ValueError('Use Install and start to show installation and login in a terminal session.')
    if method=='github_repos':
        env=identity_env(a['identity']);return json.loads(run(['gh','api','--paginate','--slurp','user/repos?per_page=100&sort=updated'],env=env,timeout=60))
    if method=='clone':
        full=a['repo'];root=P(a['root']).expanduser().resolve()
        if not re.fullmatch(r'[\w.-]+/[\w.-]+',full): raise ValueError('Invalid repository')
        root.mkdir(parents=True,exist_ok=True);dest=root/full.split('/')[1]
        if dest.exists(): raise ValueError('Destination already exists; existing files preserved')
        run(['gh','repo','clone',full,str(dest)],env=identity_env(a['identity']),timeout=180)
        return repo_info(dest)
    if method=='worktree':
        path=P(a['path']).resolve(); branch=a['branch'];run(['git','check-ref-format','--branch',branch])
        # Honor the repository's explicit script when present.
        script=path/'scripts/new-worktree.sh'
        if script.exists(): run(['bash',str(script),branch],cwd=path,timeout=120)
        else: run(['git','-C',str(path),'worktree','add','-b',branch,a['destination']],env=identity_env(a.get('identity','')),timeout=60)
        return repo_info(path)
    raise ValueError('Unknown method')

def reply_frames(prefix, reply):
    # Short numbered lines survive Windows console display wrapping. The client
    # validates order and reassembles the original JSON before using any result.
    payload=base64.b64encode(json.dumps(reply,separators=(',',':')).encode()).decode()
    total=(len(payload)+47)//48
    return [prefix+'@'+str(i)+'/'+str(total)+':'+payload[i*48:(i+1)*48] for i in range(total)]
# Read-only methods share the read lane; everything else stays ordered on the
# single mutation lane. Inline methods run on the input loop itself.
INLINE_METHODS = frozenset({'ping','browser_prepare','browser_result'})
READ_METHODS = frozenset({'inventory','capabilities','repositories','repository_worktrees','github_repos',
    'sessions','session_context','session_agent','profile_load','documents','file_read','image_read',
    'document_read','terminal_status','process_context','tmux_settings_read','dictionary_read','upload_preflight'})
# Discovery calls get a short deadline; clones, uploads and launches get the long one.
SHORT_DEADLINE_METHODS = frozenset({'inventory','capabilities','repositories','github_repos'})
def lane_for(method):
    if method in INLINE_METHODS: return 'inline'
    return 'read' if method in READ_METHODS else 'mutation'
_reply_lock = threading.Lock()
def send_reply(prefix, reply):
    with _reply_lock:
        for frame in reply_frames(prefix,reply): print(frame,flush=True)
def handle_request(prefix, request):
    try:
        _operation_deadline.end = time.monotonic() + (30 if request['method'] in SHORT_DEADLINE_METHODS else 240)
        reply={'id':request['id'],'result':dispatch(request['method'],request.get('args',{}))}
    except Exception as e: reply={'id':request.get('id','unknown'),'error':str(e)}
    send_reply(prefix,reply)
def main():
    global BROWSER, BROWSER_ERROR
    nonce=sys.argv[1];os.environ['PATH']=str(P.home()/'.local/bin')+':'+os.environ.get('PATH','')
    prefix='DAZED:'+nonce+':'
    if globals().get('BROWSER_SOURCE') and os.environ.get('DAZED_BROWSER_ENDPOINT'):
        try:
            namespace={'__name__':'dazedev_browser'}
            exec(compile(BROWSER_SOURCE,'<dazedev-browser>','exec'),namespace)
            BROWSER=namespace['Bridge'](BROWSER_SOURCE,os.environ['DAZED_BROWSER_ENDPOINT'],os.environ['DAZED_BROWSER_DESKTOP'],lambda value: send_reply(prefix,dict(event='browser-open',**value)),start=False)
        except Exception as error: BROWSER_ERROR=str(error)
    send_reply(prefix,{'id':'ready','result':{'machine':MACHINE,'user':getpass.getuser()}})
    if BROWSER: BROWSER.start()
    # Each lane is bounded. Mutations remain ordered; long discovery/clone jobs
    # cannot starve ping, session lists or profile operations.
    slots=threading.BoundedSemaphore(8)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as reads, concurrent.futures.ThreadPoolExecutor(max_workers=1) as mutations:
        def finish(request):
            try: handle_request(prefix,request)
            finally: slots.release()
        for line in sys.stdin:
            request={}
            try:
                request=json.loads(base64.b64decode(line.strip()))
                lane = lane_for(request['method'])
                if lane != 'inline':
                    if not slots.acquire(blocking=False): raise RuntimeError('Host task queue is full; wait for current operations.')
                    (reads if lane == 'read' else mutations).submit(finish,request)
                else: handle_request(prefix,request)
            except Exception as e: send_reply(prefix,{'id':request.get('id','unknown'),'error':str(e)})
if __name__=='__main__':
    try: main()
    finally:
        if BROWSER: BROWSER.close()
