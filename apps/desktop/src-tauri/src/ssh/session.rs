//! One SSH connection per host, shared by the management channel and every
//! terminal on that host. Prompts use OpenSSH's wording so the existing host
//! connection panel recognises host-key, password and code requests.
use super::{config, known_hosts};
use russh::{
    client::{self, AuthResult, KeyboardInteractiveAuthResponse},
    keys::{
        self,
        agent::{client::AgentClient, AgentIdentity},
        ssh_key, HashAlg, PrivateKey, PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate,
    },
    ChannelMsg, Disconnect, MethodKind, MethodSet,
};
use std::{
    borrow::Cow,
    io::Read,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Duration,
};
use tokio::sync::{mpsc, oneshot};

/// Error prefix asking the caller to use the system OpenSSH client instead.
pub const SYSTEM_SSH: &str = "SYSTEM_SSH:";

pub fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("dazedev-ssh")
            .enable_all()
            .build()
            .expect("SSH runtime")
    })
}

/// Questions and notices shown in the host connection panel.
pub struct Prompts {
    show: Box<dyn Fn(&str) + Send + Sync>,
    pending: Mutex<Option<oneshot::Sender<String>>>,
    closed: AtomicBool,
    batch: AtomicBool,
}

impl Prompts {
    pub fn new(show: impl Fn(&str) + Send + Sync + 'static) -> Arc<Self> {
        Arc::new(Self {
            show: Box::new(show),
            pending: Mutex::new(None),
            closed: AtomicBool::new(false),
            batch: AtomicBool::new(false),
        })
    }
    pub fn show(&self, text: &str) {
        if !text.is_empty() {
            (self.show)(text);
        }
    }
    async fn ask(&self, text: &str) -> Result<String, String> {
        if self.closed.load(Ordering::Acquire) {
            return Err("Connection cancelled".into());
        }
        if self.batch.load(Ordering::Acquire) {
            return Err("BatchMode is set, so DazeDev cannot ask for this.".into());
        }
        let (tx, rx) = oneshot::channel();
        *self.pending.lock().unwrap_or_else(|e| e.into_inner()) = Some(tx);
        self.show(text);
        match tokio::time::timeout(Duration::from_secs(600), rx).await {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(_)) => Err("Connection cancelled".into()),
            Err(_) => Err("No answer to the connection prompt".into()),
        }
    }
    /// Answer the question currently shown. Returns false if none is waiting.
    pub fn answer(&self, value: &str) -> bool {
        let pending = self
            .pending
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        match pending {
            Some(tx) => tx.send(value.to_owned()).is_ok(),
            None => false,
        }
    }
    pub fn close(&self) {
        self.closed.store(true, Ordering::Release);
        self.pending
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
    }
}

#[derive(Clone)]
enum HostKeys {
    KnownHosts {
        files: Vec<PathBuf>,
        write: PathBuf,
        name: String,
        display: String,
        strict: config::Strict,
        hash: bool,
    },
    /// Keys published by the tailnet for this device.
    Pinned {
        keys: Vec<PublicKey>,
        display: String,
    },
}

fn changed_warning(kind: &str, fingerprint: &str, display: &str, place: &str) -> String {
    format!(
        "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n\
         @    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n\
         @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n\
         IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\n\
         Someone could be eavesdropping on you right now (man-in-the-middle attack)!\n\
         It is also possible that a host key has just been changed.\n\
         The fingerprint for the {kind} key sent by the remote host {display} is\n\
         {fingerprint}.\n\
         {place}\n\
         Host key verification failed.\n"
    )
}

impl HostKeys {
    async fn verify(&self, key: &PublicKey, prompts: &Prompts) -> Result<(), String> {
        let kind = known_hosts::kind_label(key);
        let fingerprint = known_hosts::fingerprint(key);
        let (files, write, name, display, strict, hash) = match self {
            HostKeys::Pinned { keys, display } => {
                return if keys.iter().any(|k| k.key_data() == key.key_data()) {
                    Ok(())
                } else {
                    Err(changed_warning(
                        &kind,
                        &fingerprint,
                        display,
                        "It does not match the key Tailscale publishes for this device.",
                    ))
                };
            }
            HostKeys::KnownHosts {
                files,
                write,
                name,
                display,
                strict,
                hash,
            } => (files, write, name, display, *strict, *hash),
        };
        let other_types = match known_hosts::check(files, std::slice::from_ref(name), key) {
            known_hosts::Status::Known => return Ok(()),
            known_hosts::Status::Revoked => {
                return Err(format!(
                    "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n\
                     @       WARNING: REVOKED HOST KEY DETECTED!               @\n\
                     @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n\
                     The {kind} host key for {display} is marked as revoked.\n\
                     Host key verification failed.\n"
                ))
            }
            known_hosts::Status::Changed { file, line } => {
                return Err(changed_warning(
                    &kind,
                    &fingerprint,
                    display,
                    &format!("Offending {kind} key in {}:{line}", file.display()),
                ))
            }
            known_hosts::Status::Unknown { other_types } => other_types,
        };
        let remember = |prompts: &Prompts| match known_hosts::record(write, name, key, hash) {
            Ok(()) => prompts.show(&format!(
                "Warning: Permanently added '{display}' ({kind}) to the list of known hosts.\n"
            )),
            Err(e) => prompts.show(&format!(
                "Failed to add the host to the list of known hosts ({}): {e}\n",
                write.display()
            )),
        };
        match strict {
            config::Strict::Yes => Err(format!(
                "No {kind} host key is known for {display} and you have requested strict checking.\nHost key verification failed.\n"
            )),
            config::Strict::AcceptNew | config::Strict::No => {
                remember(prompts);
                Ok(())
            }
            config::Strict::Ask => {
                let mut question = format!(
                    "The authenticity of host '{display}' can't be established.\n{kind} key fingerprint is {fingerprint}.\n"
                );
                if !other_types.is_empty() {
                    question.push_str(&format!(
                        "This host already has a different type of key recorded ({}).\n",
                        other_types.join(", ")
                    ));
                }
                question.push_str("Are you sure you want to continue connecting (yes/no/[fingerprint])? ");
                for _ in 0..3 {
                    let answer = prompts.ask(&question).await?;
                    let answer = answer.trim();
                    if answer.eq_ignore_ascii_case("yes") || answer == fingerprint {
                        remember(prompts);
                        return Ok(());
                    }
                    if answer.eq_ignore_ascii_case("no") {
                        break;
                    }
                    question = "Please type 'yes', 'no' or the fingerprint: ".into();
                }
                Err("Host key verification failed.\n".into())
            }
        }
    }
}

pub struct Handler {
    keys: HostKeys,
    prompts: Arc<Prompts>,
    failure: Arc<Mutex<Option<String>>>,
}

impl client::Handler for Handler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let key = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(cert) => PublicKey::from(cert.public_key().clone()),
        };
        match self.keys.verify(&key, &self.prompts).await {
            Ok(()) => Ok(true),
            Err(text) => {
                *self.failure.lock().unwrap_or_else(|e| e.into_inner()) = Some(text);
                Ok(false)
            }
        }
    }

    async fn auth_banner(
        &mut self,
        banner: &str,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        self.prompts.show(banner);
        Ok(())
    }
}

trait Stream: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}
impl<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send> Stream for T {}

pub enum Target {
    Ssh(config::Resolved),
    /// Tailscale SSH: the tailnet authenticates the user and publishes the
    /// device's host keys, so no known_hosts entry or key is needed.
    Tailscale {
        address: String,
        ip: String,
        user: String,
        keys: Vec<PublicKey>,
    },
}

pub struct Session {
    handle: client::Handle<Handler>,
    _hops: Vec<client::Handle<Handler>>,
    pub label: String,
}

fn ssh_error(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn host_keys(cfg: &config::Resolved, display: &str) -> HostKeys {
    let write = cfg.user_known_hosts.first().cloned().unwrap_or_else(|| {
        dirs::home_dir()
            .unwrap_or_default()
            .join(".ssh/known_hosts")
    });
    HostKeys::KnownHosts {
        files: cfg.known_hosts_files(),
        write,
        name: cfg.known_hosts_name(),
        display: display.to_owned(),
        strict: cfg.strict,
        hash: cfg.hash_known_hosts,
    }
}

fn same_family(a: &ssh_key::Algorithm, b: &ssh_key::Algorithm) -> bool {
    use ssh_key::Algorithm::*;
    match (a, b) {
        (Rsa { .. }, Rsa { .. }) => true,
        _ => a == b,
    }
}

async fn tcp(cfg: &config::Resolved) -> Result<Box<dyn Stream>, String> {
    let timeout = cfg.connect_timeout.unwrap_or(Duration::from_secs(20));
    let stream = tokio::time::timeout(
        timeout,
        tokio::net::TcpStream::connect((cfg.hostname.as_str(), cfg.port)),
    )
    .await
    .map_err(|_| {
        format!(
            "ssh: connect to host {} port {}: Connection timed out",
            cfg.hostname, cfg.port
        )
    })?
    .map_err(|e| {
        format!(
            "ssh: connect to host {} port {}: {e}",
            cfg.hostname, cfg.port
        )
    })?;
    let _ = stream.set_nodelay(true);
    Ok(Box::new(stream))
}

async fn proxy_command(
    command: &str,
    cfg: &config::Resolved,
    prompts: &Arc<Prompts>,
) -> Result<Box<dyn Stream>, String> {
    let command = config::expand(command, cfg);
    #[cfg(windows)]
    let mut child = {
        use std::os::windows::process::CommandExt;
        let mut c = tokio::process::Command::new("cmd.exe");
        c.as_std_mut().args(["/d", "/c"]).raw_arg(&command);
        c.creation_flags(0x08000000);
        c
    };
    #[cfg(not(windows))]
    let mut child = {
        let mut c = tokio::process::Command::new("/bin/sh");
        c.arg("-c").arg(format!("exec {command}"));
        c
    };
    let mut child = child
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| format!("ProxyCommand could not start: {e}"))?;
    let stdin = child.stdin.take().ok_or("ProxyCommand has no input")?;
    let stdout = child.stdout.take().ok_or("ProxyCommand has no output")?;
    if let Some(stderr) = child.stderr.take() {
        let prompts = prompts.clone();
        tokio::spawn(async move {
            use tokio::io::AsyncBufReadExt;
            let mut lines = tokio::io::BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                prompts.show(&format!("{line}\n"));
            }
        });
    }
    // The child must live as long as the stream; it is killed when dropped.
    tokio::spawn(async move {
        let _ = child.wait().await;
    });
    Ok(Box::new(tokio::io::join(stdout, stdin)))
}

async fn handshake(
    stream: Box<dyn Stream>,
    cfg: &config::Resolved,
    keys: HostKeys,
    prompts: &Arc<Prompts>,
) -> Result<client::Handle<Handler>, String> {
    let mut config = client::Config {
        keepalive_interval: Some(cfg.server_alive_interval.unwrap_or(Duration::from_secs(15))),
        keepalive_max: cfg.server_alive_count_max.max(1),
        nodelay: true,
        ..Default::default()
    };
    if let HostKeys::KnownHosts { files, name, .. } = &keys {
        let recorded = known_hosts::recorded_algorithms(files, std::slice::from_ref(name));
        if !recorded.is_empty() {
            let mut order: Vec<ssh_key::Algorithm> = config.preferred.key.to_vec();
            order.sort_by_key(|a| !recorded.iter().any(|r| same_family(r, a)));
            config.preferred.key = Cow::Owned(order);
        }
    }
    let failure = Arc::new(Mutex::new(None));
    let handler = Handler {
        keys,
        prompts: prompts.clone(),
        failure: failure.clone(),
    };
    match client::connect_stream(Arc::new(config), stream, handler).await {
        Ok(handle) => Ok(handle),
        Err(e) => Err(failure
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_else(|| {
                format!(
                    "ssh: connection to {} port {} failed: {e}",
                    cfg.hostname, cfg.port
                )
            })),
    }
}

enum Outcome {
    Success,
    Failed { remaining: MethodSet, partial: bool },
}

impl From<AuthResult> for Outcome {
    fn from(result: AuthResult) -> Self {
        match result {
            AuthResult::Success => Outcome::Success,
            AuthResult::Failure {
                remaining_methods,
                partial_success,
            } => Outcome::Failed {
                remaining: remaining_methods,
                partial: partial_success,
            },
        }
    }
}

#[derive(Debug)]
enum SignError {
    Send,
    Failed(String),
}

impl From<russh::SendError> for SignError {
    fn from(_: russh::SendError) -> Self {
        SignError::Send
    }
}

impl std::fmt::Display for SignError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SignError::Send => f.write_str("SSH connection closed"),
            SignError::Failed(e) => f.write_str(e),
        }
    }
}

fn sign(key: PrivateKey, hash: Option<HashAlg>, mut data: Vec<u8>) -> Result<Vec<u8>, String> {
    use keys::ssh_encoding::Encode;
    let key = PrivateKeyWithHashAlg::new(Arc::new(key), hash);
    let signature: ssh_key::Signature = match key.key_data() {
        ssh_key::private::KeypairData::Rsa(pair) => {
            let ssh_key::Algorithm::Rsa { hash } = key.algorithm() else {
                return Err("RSA key has an unexpected algorithm".into());
            };
            keys::signature::Signer::try_sign(&(pair, hash), &data).map_err(ssh_error)?
        }
        pair => keys::signature::Signer::try_sign(pair, &data).map_err(ssh_error)?,
    };
    let encoded = signature.encode_vec().map_err(ssh_error)?;
    encoded.encode(&mut data).map_err(ssh_error)?;
    Ok(data)
}

/// Signs with an encrypted key file. The passphrase is asked for only after
/// the server has accepted the public key, as OpenSSH does.
struct FileSigner<'a> {
    path: &'a Path,
    text: &'a str,
    prompts: &'a Prompts,
}

impl russh::Signer for FileSigner<'_> {
    type Error = SignError;

    fn auth_sign(
        &mut self,
        _key: &AgentIdentity,
        hash_alg: Option<HashAlg>,
        to_sign: Vec<u8>,
    ) -> impl std::future::Future<Output = Result<Vec<u8>, Self::Error>> + Send {
        async move {
            let key = unlock(self.path, self.text, self.prompts)
                .await
                .map_err(SignError::Failed)?;
            sign(key, hash_alg, to_sign).map_err(SignError::Failed)
        }
    }
}

async fn unlock(path: &Path, text: &str, prompts: &Prompts) -> Result<PrivateKey, String> {
    let mut question = format!("Enter passphrase for key '{}': ", path.display());
    for _ in 0..3 {
        let passphrase = prompts.ask(&question).await?;
        if passphrase.is_empty() {
            break;
        }
        match keys::decode_secret_key(text, Some(&passphrase)) {
            Ok(key) => return Ok(key),
            Err(_) => question = format!("Bad passphrase, try again for {}: ", path.display()),
        }
    }
    Err(format!("No usable passphrase for {}", path.display()))
}

fn read_public(path: &Path) -> Option<PublicKey> {
    let mut public = path.as_os_str().to_owned();
    public.push(".pub");
    std::fs::read_to_string(&public)
        .ok()
        .and_then(|text| PublicKey::from_openssh(text.trim()).ok())
}

fn certificate_for(
    path: &Path,
    cfg: &config::Resolved,
    key: &PublicKey,
) -> Option<ssh_key::Certificate> {
    let mut default = path.as_os_str().to_owned();
    default.push("-cert.pub");
    std::iter::once(PathBuf::from(default))
        .chain(cfg.certificate_files.iter().cloned())
        .filter_map(|p| keys::load_openssh_certificate(p).ok())
        .find(|cert| cert.public_key() == key.key_data())
}

fn is_rsa(key: &PublicKey) -> bool {
    matches!(key.algorithm(), ssh_key::Algorithm::Rsa { .. })
}

type Agent = AgentClient<Box<dyn keys::agent::client::AgentStream + Send + Unpin>>;

async fn agent(cfg: &config::Resolved) -> Option<Agent> {
    let configured = cfg
        .identity_agent
        .as_deref()
        .filter(|a| *a != "SSH_AUTH_SOCK");
    if configured == Some("none") {
        return None;
    }
    #[cfg(unix)]
    {
        let path = match configured {
            Some(path) => path.to_owned(),
            None => std::env::var("SSH_AUTH_SOCK").ok()?,
        };
        tokio::time::timeout(Duration::from_secs(3), AgentClient::connect_uds(path))
            .await
            .ok()?
            .ok()
            .map(|a| a.dynamic())
    }
    #[cfg(windows)]
    {
        let pipe = configured
            .map(str::to_owned)
            .or_else(|| std::env::var("SSH_AUTH_SOCK").ok())
            .unwrap_or_else(|| r"\\.\pipe\openssh-ssh-agent".to_owned());
        let named = tokio::time::timeout(
            Duration::from_secs(3),
            AgentClient::connect_named_pipe(pipe),
        )
        .await;
        if let Ok(Ok(agent)) = named {
            return Some(agent.dynamic());
        }
        if configured.is_some() {
            return None;
        }
        tokio::time::timeout(Duration::from_secs(3), AgentClient::connect_pageant())
            .await
            .ok()?
            .ok()
            .map(|a| a.dynamic())
    }
}

async fn publickey(
    handle: &mut client::Handle<Handler>,
    cfg: &config::Resolved,
    prompts: &Prompts,
) -> Result<Option<Outcome>, String> {
    let rsa_hash = handle
        .best_supported_rsa_hash()
        .await
        .ok()
        .flatten()
        .flatten();
    let user = cfg.user.clone();
    let files: Vec<(PathBuf, Option<PublicKey>)> = cfg
        .identity_files
        .iter()
        .filter(|p| p.is_file())
        .map(|p| (p.clone(), read_public(p)))
        .collect();
    let mut offered: Vec<PublicKey> = Vec::new();
    let mut last = None;
    if let Some(mut agent) = agent(cfg).await {
        let identities = agent.request_identities().await.unwrap_or_default();
        for identity in identities {
            let key = match &identity {
                AgentIdentity::PublicKey { key, .. } => key.clone(),
                AgentIdentity::Certificate { certificate, .. } => {
                    PublicKey::from(certificate.public_key().clone())
                }
            };
            if cfg.identities_only
                && !files
                    .iter()
                    .any(|(_, k)| k.as_ref().is_some_and(|k| k.key_data() == key.key_data()))
            {
                continue;
            }
            let hash = if is_rsa(&key) { rsa_hash } else { None };
            let result = match identity {
                AgentIdentity::PublicKey { key, .. } => {
                    handle
                        .authenticate_publickey_with(&user, key, hash, &mut agent)
                        .await
                }
                AgentIdentity::Certificate { certificate, .. } => {
                    handle
                        .authenticate_certificate_with(&user, certificate, hash, &mut agent)
                        .await
                }
            };
            offered.push(key);
            match result.map(Outcome::from) {
                Ok(Outcome::Success) => return Ok(Some(Outcome::Success)),
                Ok(outcome @ Outcome::Failed { partial: true, .. }) => return Ok(Some(outcome)),
                Ok(outcome) => last = Some(outcome),
                Err(_) => {}
            }
        }
    }
    for (path, public) in files {
        if public
            .as_ref()
            .is_some_and(|p| offered.iter().any(|o| o.key_data() == p.key_data()))
        {
            continue;
        }
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        let result = match keys::decode_secret_key(&text, None) {
            Ok(key) => {
                let public = key.public_key().clone();
                let key = Arc::new(key);
                if let Some(cert) = certificate_for(&path, cfg, &public) {
                    handle.authenticate_openssh_cert(&user, key, cert).await
                } else {
                    let hash = if is_rsa(&public) { rsa_hash } else { None };
                    handle
                        .authenticate_publickey(&user, PrivateKeyWithHashAlg::new(key, hash))
                        .await
                }
                .map_err(ssh_error)
            }
            Err(keys::Error::KeyIsEncrypted) => {
                let public = public.or_else(|| {
                    PrivateKey::from_openssh(&text)
                        .ok()
                        .map(|k| k.public_key().clone())
                });
                match public {
                    Some(public) if certificate_for(&path, cfg, &public).is_none() => {
                        let hash = if is_rsa(&public) { rsa_hash } else { None };
                        let mut signer = FileSigner {
                            path: &path,
                            text: &text,
                            prompts,
                        };
                        handle
                            .authenticate_publickey_with(&user, public, hash, &mut signer)
                            .await
                            .map_err(ssh_error)
                    }
                    public => {
                        // No public key to probe with, or a certificate needs the
                        // unlocked key: ask for the passphrase first.
                        let Ok(key) = unlock(&path, &text, prompts).await else {
                            continue;
                        };
                        let key = Arc::new(key);
                        match public.and_then(|p| certificate_for(&path, cfg, &p)) {
                            Some(cert) => handle.authenticate_openssh_cert(&user, key, cert).await,
                            None => {
                                let hash = if is_rsa(key.public_key().into()) {
                                    rsa_hash
                                } else {
                                    None
                                };
                                handle
                                    .authenticate_publickey(
                                        &user,
                                        PrivateKeyWithHashAlg::new(key, hash),
                                    )
                                    .await
                            }
                        }
                        .map_err(ssh_error)
                    }
                }
            }
            // Keys this client cannot use (for example security keys).
            Err(_) => continue,
        };
        match result.map(Outcome::from) {
            Ok(Outcome::Success) => return Ok(Some(Outcome::Success)),
            Ok(outcome @ Outcome::Failed { partial: true, .. }) => return Ok(Some(outcome)),
            Ok(outcome) => last = Some(outcome),
            Err(e) if e.contains("Connection cancelled") => return Err(e),
            Err(_) => {}
        }
    }
    Ok(last)
}

async fn keyboard_interactive(
    handle: &mut client::Handle<Handler>,
    user: &str,
    prompts: &Prompts,
) -> Result<Outcome, String> {
    let mut response = handle
        .authenticate_keyboard_interactive_start(user, None)
        .await
        .map_err(ssh_error)?;
    loop {
        match response {
            KeyboardInteractiveAuthResponse::Success => return Ok(Outcome::Success),
            KeyboardInteractiveAuthResponse::Failure {
                remaining_methods,
                partial_success,
            } => {
                return Ok(Outcome::Failed {
                    remaining: remaining_methods,
                    partial: partial_success,
                })
            }
            KeyboardInteractiveAuthResponse::InfoRequest {
                name,
                instructions,
                prompts: questions,
            } => {
                for text in [name, instructions] {
                    if !text.is_empty() {
                        prompts.show(&format!("{}\n", text.trim_end()));
                    }
                }
                let mut answers = Vec::with_capacity(questions.len());
                for question in questions {
                    answers.push(prompts.ask(&question.prompt).await?);
                }
                response = handle
                    .authenticate_keyboard_interactive_respond(answers)
                    .await
                    .map_err(ssh_error)?;
            }
        }
    }
}

fn method_names(methods: &MethodSet) -> String {
    methods
        .iter()
        .map(|m| <&'static str>::from(m))
        .collect::<Vec<_>>()
        .join(",")
}

async fn authenticate(
    handle: &mut client::Handle<Handler>,
    cfg: &config::Resolved,
    prompts: &Prompts,
) -> Result<(), String> {
    let user = cfg.user.clone();
    let mut methods = match handle.authenticate_none(&user).await.map_err(ssh_error)? {
        AuthResult::Success => return Ok(()),
        AuthResult::Failure {
            remaining_methods, ..
        } => remaining_methods,
    };
    let mut tried: Vec<&str> = Vec::new();
    loop {
        let next = cfg.preferred_auth.iter().find(|m| {
            !tried.contains(&m.as_str())
                && match m.as_str() {
                    "publickey" => cfg.pubkey && methods.contains(&MethodKind::PublicKey),
                    "keyboard-interactive" => {
                        cfg.kbd_interactive && methods.contains(&MethodKind::KeyboardInteractive)
                    }
                    "password" => cfg.password && methods.contains(&MethodKind::Password),
                    _ => false,
                }
        });
        let Some(method) = next else {
            return Err(format!(
                "{user}@{}: Permission denied ({}).\n",
                cfg.hostname,
                method_names(&methods)
            ));
        };
        tried.push(method.as_str());
        let outcome = match method.as_str() {
            "publickey" => publickey(handle, cfg, prompts).await?,
            "keyboard-interactive" => Some(keyboard_interactive(handle, &user, prompts).await?),
            _ => {
                let mut outcome = None;
                let question = format!("{user}@{}'s password: ", cfg.hostname);
                for attempt in 0..cfg.password_prompts.max(1) {
                    if attempt > 0 {
                        prompts.show("Permission denied, please try again.\n");
                    }
                    let password = prompts.ask(&question).await?;
                    let result = handle
                        .authenticate_password(&user, password)
                        .await
                        .map_err(ssh_error)?;
                    let result = Outcome::from(result);
                    let done = !matches!(result, Outcome::Failed { partial: false, .. });
                    outcome = Some(result);
                    if done {
                        break;
                    }
                }
                outcome
            }
        };
        match outcome {
            Some(Outcome::Success) => return Ok(()),
            Some(Outcome::Failed { remaining, partial }) => {
                if partial {
                    // Another factor is required; allow methods to run again.
                    tried.clear();
                    tried.push(method.as_str());
                }
                methods = remaining;
            }
            None => {}
        }
    }
}

impl Session {
    pub async fn connect(target: Target, prompts: Arc<Prompts>) -> Result<Session, String> {
        match target {
            Target::Tailscale {
                address,
                ip,
                user,
                keys,
            } => {
                let request = config::Request {
                    host: ip.clone(),
                    user: Some(user.clone()),
                    port: Some(22),
                    ..Default::default()
                };
                let cfg = config::parse("", &request);
                let display = format!("{user}@{address}");
                let stream = tcp(&cfg).await?;
                let mut handle = handshake(
                    stream,
                    &cfg,
                    HostKeys::Pinned {
                        keys,
                        display: display.clone(),
                    },
                    &prompts,
                )
                .await?;
                match handle.authenticate_none(&user).await.map_err(ssh_error)? {
                    AuthResult::Success => Ok(Session {
                        handle,
                        _hops: Vec::new(),
                        label: display,
                    }),
                    AuthResult::Failure { .. } => Err(format!(
                        "{display}: Tailscale SSH did not allow this user. Check the tailnet SSH policy.\n"
                    )),
                }
            }
            Target::Ssh(cfg) => {
                if let Some(reason) = &cfg.unsupported {
                    return Err(format!("{SYSTEM_SSH}{reason}"));
                }
                prompts.batch.store(cfg.batch_mode, Ordering::Release);
                let mut hops: Vec<client::Handle<Handler>> = Vec::new();
                for hop in &cfg.proxy_jump {
                    let hop_request = config::parse_hop(hop);
                    let hop_cfg =
                        tokio::task::spawn_blocking(move || config::resolve(&hop_request))
                            .await
                            .map_err(ssh_error)?;
                    if hop_cfg.unsupported.is_some()
                        || hop_cfg.proxy_command.is_some()
                        || !hop_cfg.proxy_jump.is_empty()
                    {
                        return Err(format!("{SYSTEM_SSH}jump host {hop} needs its own proxy"));
                    }
                    let stream: Box<dyn Stream> = match hops.last() {
                        Some(previous) => Box::new(
                            previous
                                .channel_open_direct_tcpip(
                                    hop_cfg.hostname.clone(),
                                    hop_cfg.port as u32,
                                    "127.0.0.1",
                                    0,
                                )
                                .await
                                .map_err(|e| format!("Jump to {hop} failed: {e}"))?
                                .into_stream(),
                        ),
                        None => tcp(&hop_cfg).await?,
                    };
                    let display = hop_cfg.known_hosts_name();
                    let mut handle =
                        handshake(stream, &hop_cfg, host_keys(&hop_cfg, &display), &prompts)
                            .await?;
                    authenticate(&mut handle, &hop_cfg, &prompts).await?;
                    hops.push(handle);
                }
                let stream: Box<dyn Stream> = if let Some(last) = hops.last() {
                    Box::new(
                        last.channel_open_direct_tcpip(
                            cfg.hostname.clone(),
                            cfg.port as u32,
                            "127.0.0.1",
                            0,
                        )
                        .await
                        .map_err(|e| format!("Jump to {} failed: {e}", cfg.hostname))?
                        .into_stream(),
                    )
                } else if let Some(command) = &cfg.proxy_command {
                    proxy_command(command, &cfg, &prompts).await?
                } else {
                    tcp(&cfg).await?
                };
                let display = cfg.known_hosts_name();
                let mut handle =
                    handshake(stream, &cfg, host_keys(&cfg, &display), &prompts).await?;
                authenticate(&mut handle, &cfg, &prompts).await?;
                Ok(Session {
                    handle,
                    _hops: hops,
                    label: format!("{}@{}", cfg.user, cfg.host),
                })
            }
        }
    }

    pub fn is_closed(&self) -> bool {
        self.handle.is_closed()
    }

    pub fn close(self: &Arc<Self>) {
        let session = self.clone();
        runtime().spawn(async move {
            let _ = session
                .handle
                .disconnect(Disconnect::ByApplication, "", "en")
                .await;
        });
    }

    /// Open a channel running `command` through the remote login shell.
    pub fn open(
        self: &Arc<Self>,
        command: String,
        pty: Option<(u16, u16)>,
    ) -> Result<(ChannelIo, OutputReader), String> {
        let (tx, rx) = std::sync::mpsc::channel();
        let session = self.clone();
        runtime().spawn(async move {
            let _ = tx.send(session.open_async(command, pty).await);
        });
        rx.recv_timeout(Duration::from_secs(20))
            .map_err(|_| "Opening the SSH channel timed out".to_string())?
    }

    async fn open_async(
        self: Arc<Self>,
        command: String,
        pty: Option<(u16, u16)>,
    ) -> Result<(ChannelIo, OutputReader), String> {
        let channel = self
            .handle
            .channel_open_session()
            .await
            .map_err(|e| format!("SSH channel could not open: {e}"))?;
        if let Some((cols, rows)) = pty {
            channel
                .request_pty(false, "xterm-256color", cols as u32, rows as u32, 0, 0, &[])
                .await
                .map_err(ssh_error)?;
        }
        channel.exec(true, command).await.map_err(ssh_error)?;
        let (mut read, write) = channel.split();
        // Each channel drains into its own queue so one busy terminal does not
        // stall the shared connection. A full queue applies backpressure.
        let (out_tx, out_rx) = mpsc::channel::<Vec<u8>>(512);
        let (cmd_tx, mut cmd_rx) = mpsc::channel::<Command>(256);
        let keep = self.clone();
        runtime().spawn(async move {
            let _keep = keep;
            while let Some(message) = read.wait().await {
                match message {
                    ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => {
                        if out_tx.send(data.to_vec()).await.is_err() {
                            break;
                        }
                    }
                    ChannelMsg::Eof | ChannelMsg::Close | ChannelMsg::Failure => break,
                    _ => {}
                }
            }
        });
        runtime().spawn(async move {
            while let Some(command) = cmd_rx.recv().await {
                match command {
                    Command::Data(bytes, reply) => {
                        let result = write.data_bytes(bytes).await.map_err(ssh_error);
                        let failed = result.is_err();
                        let _ = reply.send(result);
                        if failed {
                            break;
                        }
                    }
                    Command::Resize(cols, rows) => {
                        let _ = write.window_change(cols as u32, rows as u32, 0, 0).await;
                    }
                    Command::Close => {
                        let _ = write.eof().await;
                        let _ = write.close().await;
                        break;
                    }
                }
            }
        });
        Ok((ChannelIo { commands: cmd_tx }, OutputReader::new(out_rx)))
    }
}

enum Command {
    Data(Vec<u8>, std::sync::mpsc::Sender<Result<(), String>>),
    Resize(u16, u16),
    Close,
}

/// Synchronous handle for writing to one SSH channel.
pub struct ChannelIo {
    commands: mpsc::Sender<Command>,
}

impl ChannelIo {
    pub fn write(&self, data: &[u8], timeout: Duration) -> Result<(), String> {
        let (tx, rx) = std::sync::mpsc::channel();
        self.commands
            .try_send(Command::Data(data.to_vec(), tx))
            .map_err(|_| "Terminal input is unavailable".to_string())?;
        rx.recv_timeout(timeout)
            .map_err(|_| "Terminal input timed out; reconnect this terminal".to_string())?
    }
    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), String> {
        self.commands
            .try_send(Command::Resize(cols.max(1), rows.max(1)))
            .map_err(|_| "Terminal is disconnected".to_string())
    }
    pub fn close(&self) {
        let _ = self.commands.try_send(Command::Close);
    }
}

/// Blocking reader over a channel's output. Returns end of file when the
/// channel or connection closes.
pub struct OutputReader {
    rx: mpsc::Receiver<Vec<u8>>,
    buffer: Vec<u8>,
    position: usize,
}

impl OutputReader {
    fn new(rx: mpsc::Receiver<Vec<u8>>) -> Self {
        Self {
            rx,
            buffer: Vec::new(),
            position: 0,
        }
    }
}

impl Read for OutputReader {
    /// Blocks for the first bytes, then also returns whatever else is
    /// already queued, so bursts arrive as one read.
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        while self.position >= self.buffer.len() {
            match self.rx.blocking_recv() {
                Some(bytes) => {
                    self.buffer = bytes;
                    self.position = 0;
                }
                None => return Ok(0),
            }
        }
        let mut n = 0;
        loop {
            let take = (out.len() - n).min(self.buffer.len() - self.position);
            out[n..n + take].copy_from_slice(&self.buffer[self.position..self.position + take]);
            self.position += take;
            n += take;
            if n == out.len() {
                return Ok(n);
            }
            match self.rx.try_recv() {
                Ok(bytes) => {
                    self.buffer = bytes;
                    self.position = 0;
                }
                Err(_) => return Ok(n),
            }
        }
    }
}

/// Look up a tailnet device in `tailscale status --json` output: its first
/// address and the SSH host keys it publishes.
pub fn tailscale_peer(
    status: &serde_json::Value,
    address: &str,
) -> Option<(String, Vec<PublicKey>)> {
    let wanted = address.trim_end_matches('.').to_ascii_lowercase();
    let peers = status.get("Peer")?.as_object()?;
    let mut matches = peers.values().filter(|peer| {
        let dns = peer["DNSName"]
            .as_str()
            .unwrap_or("")
            .trim_end_matches('.')
            .to_ascii_lowercase();
        dns == wanted
            || (!wanted.contains('.') && dns.split('.').next() == Some(wanted.as_str()))
            || peer["TailscaleIPs"]
                .as_array()
                .is_some_and(|ips| ips.iter().any(|ip| ip.as_str() == Some(&wanted)))
    });
    let peer = matches.next()?;
    if matches.next().is_some() {
        return None;
    }
    let ip = peer["TailscaleIPs"]
        .as_array()?
        .iter()
        .filter_map(|ip| ip.as_str())
        .find(|ip| !ip.contains(':'))
        .or_else(|| peer["TailscaleIPs"][0].as_str())?
        .to_owned();
    let keys: Vec<PublicKey> = peer["sshHostKeys"]
        .as_array()?
        .iter()
        .filter_map(|k| k.as_str())
        .filter_map(|k| PublicKey::from_openssh(k.trim()).ok())
        .collect();
    (!keys.is_empty()).then_some((ip, keys))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tailscale_peer_needs_one_match_and_published_keys() {
        let key =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ48UXvX/ZaeCLfRa3a5p6NnXs2Rtn1sHjVU0Hur6FCn";
        let status = serde_json::json!({"Peer": {
            "a": {"DNSName":"build.example.ts.net.","TailscaleIPs":["100.64.0.2","fd7a::2"],"sshHostKeys":[key]},
            "b": {"DNSName":"plain.example.ts.net.","TailscaleIPs":["100.64.0.3"]}
        }});
        let (ip, keys) = tailscale_peer(&status, "build").unwrap();
        assert_eq!(ip, "100.64.0.2");
        assert_eq!(keys.len(), 1);
        assert!(tailscale_peer(&status, "plain").is_none());
        assert!(tailscale_peer(&status, "missing").is_none());
    }

    #[test]
    fn local_key_signature_has_the_agent_layout() {
        use keys::ssh_encoding::Decode;
        let key =
            PrivateKey::random(&mut keys::key::safe_rng(), ssh_key::Algorithm::Ed25519).unwrap();
        let data = b"session data".to_vec();
        let signed = sign(key.clone(), None, data.clone()).unwrap();
        assert!(signed.starts_with(&data));
        let mut rest = &signed[data.len()..];
        let blob = Vec::<u8>::decode(&mut rest).unwrap();
        assert!(rest.is_empty());
        let signature = ssh_key::Signature::decode(&mut blob.as_slice()).unwrap();
        assert_eq!(signature.algorithm(), ssh_key::Algorithm::Ed25519);
        assert!(
            keys::signature::Verifier::verify(key.public_key().key_data(), &data, &signature)
                .is_ok()
        );
    }

    #[test]
    fn prompts_answer_only_a_waiting_question() {
        let shown = Arc::new(Mutex::new(Vec::<String>::new()));
        let sink = shown.clone();
        let prompts = Prompts::new(move |t| sink.lock().unwrap().push(t.to_owned()));
        assert!(!prompts.answer("early"));
        let waiting = prompts.clone();
        let answer = runtime().spawn(async move { waiting.ask("Password: ").await });
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while !prompts.answer("secret") {
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        let value = runtime().block_on(answer).unwrap().unwrap();
        assert_eq!(value, "secret");
        assert_eq!(shown.lock().unwrap().as_slice(), ["Password: "]);
        prompts.close();
        assert!(runtime()
            .block_on(async { prompts.ask("Again: ").await })
            .is_err());
    }
}
