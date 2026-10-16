//! Processes and channels behind a terminal or management connection.
use super::*;
/// A local process in a pseudo-terminal (system OpenSSH, WSL or a local shell).
pub(super) struct ProcessIo {
    pub(super) writer: mpsc::SyncSender<(Vec<u8>, mpsc::Sender<Result<(), String>>)>,
    pub(super) master: Mutex<Box<dyn MasterPty + Send>>,
    pub(super) killer: Mutex<Box<dyn portable_pty::ChildKiller + Send + Sync>>,
}
impl Io for ProcessIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        let (tx, rx) = mpsc::channel();
        self.writer
            .try_send((data.to_vec(), tx))
            .map_err(|_| "Terminal input is unavailable".to_string())?;
        rx.recv_timeout(Duration::from_secs(5))
            .map_err(|_| "Terminal input timed out; reconnect this terminal".to_string())?
    }
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        lock(&self.master)
            .resize(PtySize {
                rows: rows.max(1),
                cols: cols.max(1),
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())
    }
    fn kill(&self) {
        let _ = lock(&self.killer).kill();
    }
}
/// A terminal channel on a built-in SSH connection.
pub(super) struct SshPaneIo(pub(super) crate::ssh::ChannelIo);
impl Io for SshPaneIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        self.0.write(data, Duration::from_secs(5))
    }
    fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        self.0.resize(cols, rows)
    }
    fn kill(&self) {
        self.0.close()
    }
}
/// The management channel of a built-in SSH connection. Input is refused
/// until authentication completes and the channel opens.
pub(super) struct SshControlIo {
    pub(super) prompts: Arc<crate::ssh::Prompts>,
    pub(super) channel: OnceLock<crate::ssh::ChannelIo>,
    pub(super) session: OnceLock<Arc<crate::ssh::Session>>,
    pub(super) killed: AtomicBool,
}
impl Io for SshControlIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        match self.channel.get() {
            Some(channel) => channel.write(data, Duration::from_secs(5)),
            None => Err("Host is still connecting".into()),
        }
    }
    fn resize(&self, _cols: u16, _rows: u16) -> Result<(), String> {
        Ok(())
    }
    fn kill(&self) {
        self.killed.store(true, Ordering::Release);
        self.prompts.close();
        if let Some(channel) = self.channel.get() {
            channel.close();
        }
    }
}
pub(super) fn spawn(mut cmd: CommandBuilder) -> Result<(Arc<Pty>, Box<dyn Read + Send>), String> {
    cmd.env("TERM", "xterm-256color");
    cmd.env("COLORTERM", "truecolor");
    let pair = native_pty_system()
        .openpty(PtySize {
            rows: 30,
            cols: 120,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| e.to_string())?;
    let mut child = pair.slave.spawn_command(cmd).map_err(|e| e.to_string())?;
    drop(pair.slave);
    let reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
    let mut writer = pair.master.take_writer().map_err(|e| e.to_string())?;
    let (write_tx, write_rx) =
        mpsc::sync_channel::<(Vec<u8>, mpsc::Sender<Result<(), String>>)>(32);
    let writable = Arc::new(AtomicBool::new(true));
    let worker_writable = writable.clone();
    std::thread::spawn(move || {
        while let Ok((bytes, reply)) = write_rx.recv() {
            if !worker_writable.load(Ordering::Acquire) {
                let _ = reply.send(Err("Terminal is disconnected".into()));
                continue;
            }
            let result = writer
                .write_all(&bytes)
                .and_then(|_| writer.flush())
                .map_err(|e| e.to_string());
            if result.is_err() {
                worker_writable.store(false, Ordering::Release);
            }
            let _ = reply.send(result);
        }
    });
    let killer = child.clone_killer();
    let (exit_tx, exit_rx) = mpsc::channel();
    std::thread::spawn(move || {
        let _ = child.wait();
        // ConPTY can retain its read pipe after the child exits. Do not wait for EOF.
        let _ = exit_tx.send(());
    });
    let pty = Pty::new(
        Box::new(ProcessIo {
            writer: write_tx,
            master: Mutex::new(pair.master),
            killer: Mutex::new(killer),
        }),
        writable,
    );
    *lock(&pty.exited) = Some(exit_rx);
    Ok((pty, reader))
}
/// Input side of a process started with pipes.
pub(super) struct PipeIo {
    pub(super) writer: mpsc::SyncSender<(Vec<u8>, mpsc::Sender<Result<(), String>>)>,
    pub(super) child: Arc<Mutex<std::process::Child>>,
}
impl Io for PipeIo {
    fn write(&self, data: &[u8]) -> Result<(), String> {
        let (tx, rx) = mpsc::channel();
        self.writer
            .try_send((data.to_vec(), tx))
            .map_err(|_| "Host helper input is unavailable".to_string())?;
        rx.recv_timeout(Duration::from_secs(5))
            .map_err(|_| "Host helper input timed out; reconnect this host".to_string())?
    }
    fn resize(&self, _cols: u16, _rows: u16) -> Result<(), String> {
        Ok(())
    }
    fn kill(&self) {
        let _ = lock(&self.child).kill();
    }
}
/// Start a process with pipes. Its error output is shown as connection
/// details; its standard output is returned for the protocol reader.
pub(super) fn spawn_piped(
    mut cmd: std::process::Command,
    host_id: &str,
    sink: &EventSink,
) -> Result<(Arc<Pty>, Box<dyn Read + Send>), String> {
    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    let mut stdin = child.stdin.take().ok_or("Host helper has no input")?;
    let stdout = child.stdout.take().ok_or("Host helper has no output")?;
    if let Some(stderr) = child.stderr.take() {
        let sink = sink.clone();
        let id = host_id.to_owned();
        std::thread::spawn(move || {
            use std::io::BufRead;
            for line in std::io::BufReader::new(stderr)
                .lines()
                .map_while(Result::ok)
            {
                sink(
                    "host-output",
                    json!({"hostId":id,"text":format!("{line}\n")}),
                );
            }
        });
    }
    let (write_tx, write_rx) =
        mpsc::sync_channel::<(Vec<u8>, mpsc::Sender<Result<(), String>>)>(32);
    let writable = Arc::new(AtomicBool::new(true));
    let worker_writable = writable.clone();
    std::thread::spawn(move || {
        while let Ok((bytes, reply)) = write_rx.recv() {
            if !worker_writable.load(Ordering::Acquire) {
                let _ = reply.send(Err("Host helper is disconnected".into()));
                continue;
            }
            let result = stdin
                .write_all(&bytes)
                .and_then(|_| stdin.flush())
                .map_err(|e| e.to_string());
            if result.is_err() {
                worker_writable.store(false, Ordering::Release);
            }
            let _ = reply.send(result);
        }
    });
    // Reap the child and report its exit, as for a terminal process.
    let child = Arc::new(Mutex::new(child));
    let waited = child.clone();
    let (exit_tx, exit_rx) = mpsc::channel();
    std::thread::spawn(move || {
        loop {
            match lock(&waited).try_wait() {
                Ok(None) => {}
                _ => break,
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        let _ = exit_tx.send(());
    });
    let pty = Pty::new(
        Box::new(PipeIo {
            writer: write_tx,
            child,
        }),
        writable,
    );
    *lock(&pty.exited) = Some(exit_rx);
    Ok((pty, Box::new(stdout)))
}
