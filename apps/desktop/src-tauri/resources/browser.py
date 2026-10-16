"""Private, per-connection desktop browser bridge and its scoped command shim."""
import contextlib, fcntl, hashlib, hmac, json, os, pathlib, re, socket, stat, subprocess, sys, tempfile, threading, time, urllib.parse, uuid
P = pathlib.Path
MAX_REQUEST = 16384

def validate_url(value):
    if not isinstance(value, str) or not value or len(value) > 8192 or any(ord(c) <= 32 or ord(c) == 127 for c in value):
        raise ValueError('Expected one HTTP or HTTPS link without control characters')
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme.lower() not in ('http', 'https') or not parsed.hostname or parsed.username is not None or parsed.password is not None:
        raise ValueError('Only HTTP and HTTPS links without embedded credentials can be opened')
    if parsed.hostname.lower().rstrip('.') in ('localhost', '0.0.0.0', '::', '::1') or parsed.hostname.lower().rstrip('.').endswith('.localhost') or parsed.hostname.startswith('127.'):
        raise ValueError('This link points to the remote host itself. Localhost/OAuth forwarding is not available; use the program\'s device-code or remote login flow.')
    return value

def default_root():
    # The per-user runtime directory is private and not swept by /tmp cleaners.
    runtime = os.environ.get('XDG_RUNTIME_DIR', '')
    if runtime.startswith('/') and os.path.isdir(runtime): return P(runtime) / 'dazedev-browser'
    return P('/tmp') / ('dazedev-browser-' + str(os.getuid()))

def private_dir(path):
    path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise RuntimeError('DazeDev browser directory must be private and owned by this user')
    return path

def private_root(root=None):
    return private_dir(P(root) if root is not None else default_root())

SHIM_DIR = re.compile(r'shim-[0-9a-f]{12}')
def install_shim(root, source):
    # Each helper version gets its own shim, so versions never overwrite each other.
    folder = private_dir(private_root(root) / ('shim-' + hashlib.sha256(source.encode()).hexdigest()[:12]))
    path = folder / 'xdg-open'
    if not path.is_file(): atomic_file(path, '#!/usr/bin/env python3\n' + source, 0o700)
    return path

@contextlib.contextmanager
def registry_lock(root):
    fd = os.open(root / '.registry-lock', os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'a+') as lock:
        info = os.fstat(lock.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError('Invalid browser registry lock')
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield

def atomic_file(path, value, mode=0o600):
    fd, temporary = tempfile.mkstemp(prefix='.bridge-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w') as out: out.write(value)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)

def read_private(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_size > MAX_REQUEST:
        raise RuntimeError('Invalid browser bridge registration')
    return json.loads(path.read_text())

def process_stamp(pid):
    pid = int(pid)
    if pid <= 0: raise ValueError('Invalid terminal process')
    os.kill(pid, 0)
    proc = P('/proc') / str(pid) / 'stat'
    if proc.exists(): return proc.read_text().rsplit(')', 1)[1].split()[19]
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True, timeout=2, check=True).stdout.strip()
    if not result: raise ValueError('Terminal process is no longer running')
    return result

def route_path(root, tty):
    if not isinstance(tty, str) or not tty.startswith('/dev/') or len(tty) > 256 or any(ord(c) < 32 for c in tty):
        raise ValueError('Browser bridge needs a terminal TTY')
    return root / ('tty-' + hashlib.sha256(tty.encode()).hexdigest() + '.json')

def endpoint_path(root, endpoint):
    if not re.fullmatch('[a-f0-9]{32}', endpoint or ''): raise ValueError('Invalid browser connection')
    return root / ('endpoint-' + endpoint + '.json')

def read_packet(client):
    data = bytearray()
    while len(data) <= MAX_REQUEST:
        chunk = client.recv(min(4096, MAX_REQUEST + 1 - len(data)))
        if not chunk: break
        data.extend(chunk)
        if b'\n' in chunk: break
    if len(data) > MAX_REQUEST or not data.endswith(b'\n'):
        raise ValueError('Browser request is too large or incomplete')
    return json.loads(data)

def request(root, endpoint, value):
    meta = read_private(endpoint_path(root, endpoint))
    target = root / meta['socket']
    if target.parent != root or not re.fullmatch(r'socket-[a-f0-9]{32}', target.name): raise ValueError('Invalid browser socket')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(12)
        try: client.connect(str(target))
        except OSError: raise RuntimeError('DazeDev is disconnected. Reconnect this host in the desktop app and try again.') from None
        client.sendall((json.dumps(dict(value, token=meta['token'])) + '\n').encode())
        reply = read_packet(client)
        if not reply.get('ok'): raise RuntimeError(reply.get('error', 'Desktop browser request failed'))
        return reply

class Bridge:
    def __init__(self, source, endpoint, desktop, opened, root=None, start=True):
        self.root = private_root(root)
        self.endpoint = endpoint
        self.desktop = desktop
        with registry_lock(self.root):
            self.metadata = endpoint_path(self.root, endpoint)
            previous = None
            try: previous = read_private(self.metadata)
            except (OSError, ValueError): pass
            for stale in list(self.root.glob('endpoint-*.json'))[:64]:
                value = {}
                try:
                    value = read_private(stale)
                    if process_stamp(value['pid']) == value['stamp']: continue
                except (OSError, ValueError): pass
                except KeyError: continue
                try:
                    stale.unlink()
                    name = value.get('socket', '')
                    if re.fullmatch(r'socket-[a-f0-9]{32}', name): (self.root / name).unlink(missing_ok=True)
                except OSError: pass
            self.generation = uuid.uuid4().hex
            self.token = uuid.uuid4().hex + uuid.uuid4().hex
            self.path = self.root / ('socket-' + self.generation)
            self.opened = opened
            self.pending = {}
            self.pending_lock = threading.Lock()
            self.closed = threading.Event()
            self.recent = []
            self.launches = {}
            self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.server.bind(str(self.path)); os.chmod(self.path, 0o600)
            self.server.listen(4); self.server.settimeout(.5)
            self.source = source
            self.shim = install_shim(self.root, source)
            atomic_file(self.metadata, json.dumps(dict(socket=self.path.name, token=self.token, desktop=desktop, generation=self.generation, pid=os.getpid(), stamp=process_stamp(os.getpid()))))
            if previous and re.fullmatch(r'socket-[a-f0-9]{32}', previous.get('socket', '')):
                (self.root / previous['socket']).unlink(missing_ok=True)
        # Remove only stale registrations; surviving shell registrations are kept
        # so a management reconnect can replace its endpoint without terminal input.
        with registry_lock(self.root):
            for path in list(self.root.glob('tty-*.json'))[:512]:
                try:
                    route = read_private(path)
                    if process_stamp(route['pid']) == route['stamp']: continue
                except Exception: pass
                try: path.unlink()
                except FileNotFoundError: pass
        self.thread = threading.Thread(target=self.serve, daemon=True)
        if start: self.start()
    def start(self):
        self.thread.start()
    def environment(self):
        # Recreate the shim if something removed it since the bridge started.
        self.shim = install_shim(self.root, self.source)
        return dict(command=str(self.shim), endpoint=self.endpoint)
    def launch(self, operation):
        self.launches = {key: until for key, until in self.launches.items() if until > time.monotonic()}
        if len(self.launches) >= 32: raise RuntimeError('Too many pending browser launch registrations')
        self.launches[operation] = time.monotonic() + 30
    def complete(self, value):
        with self.pending_lock:
            pending = self.pending.get(value.get('id'))
            if pending:
                pending['error'] = value.get('error')
                pending['event'].set()
        return True
    def handle(self, value):
        if not isinstance(value, dict) or not isinstance(value.get('token'), str) or not hmac.compare_digest(value['token'], self.token):
            raise ValueError('Browser connection authentication failed')
        if value.get('kind') == 'register':
            tty = value['tty']; pid = int(value['pid'])
            record = dict(endpoint=self.endpoint, pid=pid, stamp=process_stamp(pid), tty=tty)
            with registry_lock(self.root):
                atomic_file(route_path(self.root, tty), json.dumps(record))
            return
        if value.get('kind') != 'open': raise ValueError('Unsupported browser request')
        url = validate_url(value.get('url'))
        if value.get('launch'):
            if self.launches.get(value['launch'], 0) <= time.monotonic():
                raise ValueError('Startup browser registration expired. Attach this session in DazeDev and try again.')
        else:
            route = read_private(route_path(self.root, value['tty']))
            if route['endpoint'] != self.endpoint or process_stamp(route['pid']) != route['stamp']:
                raise ValueError('Terminal browser registration expired. Reconnect the terminal.')
        now = time.monotonic(); self.recent = [stamp for stamp in self.recent if now - stamp < 60]
        if len(self.recent) >= 8: raise ValueError('Too many browser requests. Wait a minute and try again.')
        self.recent.append(now)
        identifier = uuid.uuid4().hex
        pending = dict(event=threading.Event(), error='Desktop did not acknowledge the browser request')
        with self.pending_lock: self.pending[identifier] = pending
        try:
            self.opened(dict(id=identifier, url=url))
            if not pending['event'].wait(10): raise RuntimeError(pending['error'])
            if pending['error']: raise RuntimeError(pending['error'])
        finally:
            with self.pending_lock: self.pending.pop(identifier, None)
    def serve(self):
        while not self.closed.is_set():
            try: client, _ = self.server.accept()
            except socket.timeout: continue
            except OSError: break
            with client:
                client.settimeout(2)
                try:
                    self.handle(read_packet(client)); reply = dict(ok=True)
                except Exception as error: reply = dict(ok=False, error=str(error))
                try: client.sendall((json.dumps(reply) + '\n').encode())
                except OSError: pass
    def close(self):
        self.closed.set(); self.server.close()
        with self.pending_lock:
            for pending in self.pending.values():
                pending['error'] = 'DazeDev disconnected before opening the browser'
                pending['event'].set()
        with registry_lock(self.root):
            try:
                if read_private(self.metadata).get('generation') == self.generation: self.metadata.unlink()
            except (OSError, ValueError): pass
        try: self.path.unlink()
        except FileNotFoundError: pass
        if self.thread.ident is not None: self.thread.join(timeout=1)

def attached_ttys():
    if os.environ.get('TMUX'):
        socket_path = os.environ['TMUX'].rsplit(',', 2)[0]
        pane = os.environ.get('TMUX_PANE', '')
        if not re.fullmatch(r'%[0-9]+', pane): raise RuntimeError('Cannot identify this tmux pane')
        prefix = ['tmux', '-S', socket_path]
        session = subprocess.run(prefix + ['display-message', '-p', '-t', pane, '#{session_id}'], capture_output=True, text=True, timeout=2, check=True).stdout.strip()
        clients = subprocess.run(prefix + ['list-clients', '-t', session, '-F', '#{client_tty}'], capture_output=True, text=True, timeout=2, check=True).stdout.splitlines()
        if len(clients) > 64: raise RuntimeError('Too many tmux clients to identify the desktop safely. Open the displayed link yourself.')
        return clients, True
    tty = os.environ.get('DAZEDEV_BROWSER_TTY')
    if not tty:
        try: tty = os.ttyname(0)
        except OSError: return [], False
    return [tty], False

def open_url(root, url):
    validate_url(url)
    clients, tmux = attached_ttys()
    choices = {}
    for tty in clients:
        try:
            route = read_private(route_path(root, tty))
            if process_stamp(route['pid']) != route['stamp']: continue
            if not tmux and os.environ.get('DAZEDEV_BROWSER_PID') and str(route['pid']) != os.environ['DAZEDEV_BROWSER_PID']: continue
            meta = read_private(endpoint_path(root, route['endpoint']))
            if not (root / meta['socket']).exists(): continue
            choices.setdefault(meta['desktop'], (route['endpoint'], tty))
        except (OSError, ValueError, KeyError, RuntimeError): continue
    if not choices and tmux and not clients and os.environ.get('DAZEDEV_BROWSER_LAUNCH'):
        # A newly launched agent can request login before its first client attaches.
        # The creating connection grants only a short, server-checked startup window.
        request(root, os.environ.get('DAZEDEV_BROWSER_ENDPOINT'), dict(kind='open', launch=os.environ['DAZEDEV_BROWSER_LAUNCH'], url=url))
        return
    if not choices: raise RuntimeError('No attached DazeDev desktop is available. Connect this terminal in DazeDev and try again.')
    if len(choices) != 1: raise RuntimeError('More than one DazeDev desktop is attached to this tmux session. Detach the other desktop or open the displayed link yourself.')
    endpoint, tty = next(iter(choices.values()))
    request(root, endpoint, dict(kind='open', tty=tty, url=url))

def main():
    try:
        here = P(__file__).resolve().parent
        root = private_root(here.parent if SHIM_DIR.fullmatch(here.name) else here)
        if len(sys.argv) == 5 and sys.argv[1] == '--dazedev-register':
            request(root, sys.argv[2], dict(kind='register', pid=int(sys.argv[3]), tty=sys.argv[4]))
        elif len(sys.argv) == 2: open_url(root, sys.argv[1])
        else: raise ValueError('Usage: xdg-open <http-or-https-url>')
    except Exception as error:
        print('DazeDev browser: ' + str(error), file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
