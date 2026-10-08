import socket,threading,json,pathlib,time,paramiko,argparse,subprocess,os,pty,fcntl,termios,struct,signal
parser=argparse.ArgumentParser(description="Disposable loopback SSH server for integration tests")
parser.add_argument("directory", type=pathlib.Path)
parser.add_argument("--real-host", action="store_true", help="Run a disposable key-only host and PTY fixture")
options=parser.parse_args()
p=options.directory
p.mkdir(parents=True, exist_ok=True)
server_key=paramiko.RSAKey.generate(2048)
client_key=paramiko.RSAKey.generate(2048)
client_key.write_private_key_file(str(p/'fixture-key.pem'),password='fixture-passphrase')
(p/'fixture-key.pem').chmod(0o600)
events=[]
fixture_socket='dazedev-fixture-'+str(os.getpid())
env=dict(PATH='/usr/bin:/bin',HOME=str(p/'home'),XDG_CONFIG_HOME=str(p/'config'),SHELL='/bin/sh',LANG='C.UTF-8')
(p/'home').mkdir(exist_ok=True)
(p/'fixture-socket.txt').write_text(fixture_socket)
(p/'fixture-host-source.txt').write_text(str(pathlib.Path(__file__).resolve().parents[2]/'desktop/src-tauri/resources/host.py'))
class Server(paramiko.ServerInterface):
 def get_allowed_auths(self,u):return 'publickey,password,keyboard-interactive'
 def check_auth_none(self,u):
  events.append([u,'none']);
  if options.real_host:return paramiko.AUTH_FAILED
  return paramiko.AUTH_SUCCESSFUL if u=='none-user' else paramiko.AUTH_FAILED
 def check_auth_password(self,u,password):
  events.append([u,'password']);
  if options.real_host:return paramiko.AUTH_FAILED
  return paramiko.AUTH_SUCCESSFUL if u=='password-user' and password=='fixture-password' else paramiko.AUTH_FAILED
 def check_auth_publickey(self,u,k):
  events.append([u,'publickey']);return paramiko.AUTH_SUCCESSFUL if u=='key-user' and k==client_key else paramiko.AUTH_FAILED
 def check_channel_request(self,k,i):return paramiko.OPEN_SUCCEEDED if k=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
 def check_channel_pty_request(self,c,term,width,height,pixelwidth,pixelheight,modes):
  self.pty_size=(width,height);self.pty_term=term.decode();return options.real_host
 def check_channel_window_change_request(self,c,width,height,pixelwidth,pixelheight):
  master=getattr(self,'master',None)
  if master is not None:fcntl.ioctl(master,termios.TIOCSWINSZ,struct.pack('HHHH',height,width,pixelheight,pixelwidth))
  return True
 def check_channel_exec_request(self,c,cmd):
  if options.real_host:
   if fixture_socket.encode() not in cmd:return False
   def run():
    master=None
    try:
     if hasattr(self,'pty_size'):
      master,slave=pty.openpty();self.master=master
      width,height=self.pty_size;fcntl.ioctl(master,termios.TIOCSWINSZ,struct.pack('HHHH',height,width,0,0))
      process=subprocess.Popen(cmd.decode(),shell=True,stdin=slave,stdout=slave,stderr=slave,env=dict(env,TERM=self.pty_term),start_new_session=True)
      os.close(slave)
      def send_input():
       try:
        while data:=c.recv(8192):os.write(master,data)
       except (OSError,EOFError):pass
       finally:
        if process.poll() is None:os.killpg(process.pid,signal.SIGTERM)
      threading.Thread(target=send_input,daemon=True).start()
      while data:=os.read(master,8192):c.sendall(data)
     else:
      process=subprocess.Popen(cmd.decode(),shell=True,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=env,start_new_session=True)
      def send_input():
       try:
        while data:=c.recv(8192):process.stdin.write(data);process.stdin.flush()
       except (OSError,EOFError):pass
       finally:
        if process.poll() is None:os.killpg(process.pid,signal.SIGTERM)
      threading.Thread(target=send_input,daemon=True).start()
      while data:=os.read(process.stdout.fileno(),8192):c.sendall(data)
     status=process.wait();c.send_exit_status(status if status>=0 else 128-status)
    except (OSError,EOFError):pass
    finally:
     if master is not None:os.close(master)
     c.close()
   threading.Thread(target=run,daemon=True).start();return True
  def reply():
   try:
    if cmd==b'overflow':c.sendall(b'x'*(1048576+100))
    else:c.sendall(b'fixture-ok\n')
    c.send_exit_status(0);c.close()
   except Exception:pass
  threading.Thread(target=reply,daemon=True).start();return True

def serve(s):
 while True:
  conn,_=s.accept()
  def handle(conn):
   t=paramiko.Transport(conn)
   try:
    t.add_server_key(server_key);t.start_server(server=Server())
    while t.is_active():time.sleep(.05)
   except Exception:pass
   finally:t.close();(p/'ssh-events.json').write_text(json.dumps(events))
  threading.Thread(target=handle,args=(conn,),daemon=True).start()
sockets={}
for family,host in [(socket.AF_INET,'127.0.0.1'),(socket.AF_INET6,'::1')]:
 try:
  s=socket.socket(family);s.bind((host,0));s.listen(10);sockets[host]=s.getsockname()[1]
  threading.Thread(target=serve,args=(s,),daemon=True).start()
 except OSError:pass
if '127.0.0.1' not in sockets:raise RuntimeError('Loopback binding unavailable; run the local SSH fixture with socket access.')
(p/'fixture-ports.json.tmp').write_text(json.dumps(sockets))
(p/'fixture-ports.json.tmp').replace(p/'fixture-ports.json')
def stop(*_):
 if options.real_host:subprocess.run(['tmux','-L',fixture_socket,'kill-server'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 raise SystemExit(0)
signal.signal(signal.SIGTERM,stop)
signal.signal(signal.SIGINT,stop)
print('Loopback SSH fixture ready',flush=True)
while True:time.sleep(1)
