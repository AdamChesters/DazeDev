//! One offline worker shared by all panes; audio and finalization use one FIFO.
use crate::audio_queue::{AudioBudget, AudioFrame};
use crate::transport::EventSink;
use ort::{session::Session as OrtSession, value::Tensor};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    fs::{self, File},
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    time::{Duration, Instant},
};
use transcribe_cpp::{
    Backend, CancelToken, Model, ModelOptions, RunExtension, RunOptions, Session, SessionOptions,
    TimestampKind, WhisperRunOptions,
};
#[derive(Clone, Serialize, Deserialize)]
pub struct ModelEntry {
    pub id: String,
    pub name: String,
    pub family: String,
    pub license: String,
    pub url: String,
    pub sha256: String,
    pub size: u64,
    pub file: String,
    #[serde(default)]
    pub installed: bool,
}
pub fn catalog() -> Vec<ModelEntry> {
    serde_json::from_str(include_str!("../resources/models.json")).expect("embedded model catalog")
}
pub fn model_path(dir: &Path, id: &str) -> Result<PathBuf, String> {
    let m = catalog()
        .into_iter()
        .find(|m| m.id == id)
        .ok_or("Unknown model")?;
    Ok(dir.join(m.file))
}
pub fn list(dir: &Path) -> Vec<ModelEntry> {
    catalog()
        .into_iter()
        .map(|mut m| {
            m.installed = fs::metadata(dir.join(&m.file))
                .map(|s| s.len() == m.size)
                .unwrap_or(false);
            m
        })
        .collect()
}
pub fn download(dir: &Path, id: &str, sink: EventSink) -> Result<(), String> {
    download_cancellable(dir, id, sink, &std::sync::atomic::AtomicBool::new(false))
}
pub fn download_cancellable(
    dir: &Path,
    id: &str,
    sink: EventSink,
    cancelled: &std::sync::atomic::AtomicBool,
) -> Result<(), String> {
    let m = catalog()
        .into_iter()
        .find(|m| m.id == id)
        .ok_or("Unknown model")?;
    fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    if crate::platform::available_space(dir).map_err(|e| e.to_string())? < m.size + 64 * 1024 * 1024 {
        return Err("Not enough free space for this model and its download staging file.".into());
    }
    let tmp = dir.join(format!("{}.{}.part", m.file, uuid::Uuid::new_v4()));
    let result = (|| {
        let client = crate::local_http::builder()
            .connect_timeout(Duration::from_secs(20))
            .timeout(Duration::from_secs(7200))
            .build()
            .map_err(|e| e.to_string())?;
        let mut response = client
            .get(&m.url)
            .send()
            .and_then(|r| r.error_for_status())
            .map_err(|e| e.to_string())?;
        let mut file = File::create(&tmp).map_err(|e| e.to_string())?;
        let mut hash = Sha256::new();
        let mut buf = [0; 131072];
        let mut done = 0;
        let mut last = Instant::now();
        loop {
            if cancelled.load(std::sync::atomic::Ordering::Acquire) {
                return Err("Model download cancelled. Installed models were preserved.".into());
            }
            let n = response.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            hash.update(&buf[..n]);
            file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
            done += n as u64;
            if done > m.size {
                return Err("Model exceeds expected size".into());
            }
            if last.elapsed() > Duration::from_millis(150) {
                sink(
                    "model-progress",
                    json!({"id":id,"done":done,"total":m.size}),
                );
                last = Instant::now();
            }
        }
        if done != m.size || format!("{:x}", hash.finalize()) != m.sha256 {
            return Err("Model checksum failed; download discarded".into());
        }
        if cancelled.load(std::sync::atomic::Ordering::Acquire) {
            return Err("Model download cancelled.".into());
        }
        file.sync_all().map_err(|e| e.to_string())?;
        drop(file);
        fs::rename(&tmp, dir.join(&m.file)).map_err(|e| e.to_string())?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(tmp);
    }
    result
}
struct Vad {
    session: OrtSession,
    state: Vec<f32>,
    context: Vec<f32>,
}
// ort rc.10 keeps its environment in a Rust static (which is never dropped).
// macOS ONNX Runtime must release that environment before its C++ logging mutex
// is destroyed. Register once *after* native initialization, rather than freeing
// the shared environment when a model is switched or an individual worker stops.
fn register_ort_exit_cleanup() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        use ort::AsPointer;
        use std::{ffi::c_void, sync::OnceLock};
        static REGISTERED: OnceLock<Result<(), String>> = OnceLock::new();
        return REGISTERED
            .get_or_init(|| {
                unsafe extern "C" {
                    static __dso_handle: u8;
                    fn __cxa_atexit(
                        callback: unsafe extern "C" fn(*mut c_void),
                        argument: *mut c_void,
                        image: *const c_void,
                    ) -> i32;
                }
                unsafe extern "C" fn release_environment(environment: *mut c_void) {
                    // All Voice workers are joined before exit. The rc.10 Rust static
                    // has no destructor, so this is the single ReleaseEnv invocation.
                    unsafe {
                        (ort::api().ReleaseEnv)(environment.cast());
                    }
                }
                let environment = ort::environment::get_environment().map_err(|e| e.to_string())?;
                let result = unsafe {
                    __cxa_atexit(
                        release_environment,
                        environment.ptr().cast_mut().cast(),
                        std::ptr::addr_of!(__dso_handle).cast(),
                    )
                };
                if result == 0 {
                    Ok(())
                } else {
                    Err("Could not register safe voice runtime shutdown".into())
                }
            })
            .clone();
    }
    #[cfg(not(target_os = "macos"))]
    Ok(())
}

impl Vad {
    fn load(path: &Path) -> Result<Self, String> {
        let session = OrtSession::builder()
            .map_err(|e| e.to_string())?
            .with_intra_threads(1)
            .map_err(|e| e.to_string())?
            .commit_from_file(path)
            .map_err(|e| e.to_string())?;
        register_ort_exit_cleanup()?;
        Ok(Self {
            session,
            state: vec![0.; 256],
            context: vec![0.; 64],
        })
    }
    fn reset(&mut self) {
        self.state.fill(0.);
        self.context.fill(0.);
    }
    fn speech(&mut self, pcm: &[f32]) -> Result<f32, String> {
        let mut input = self.context.clone();
        input.extend_from_slice(pcm);
        self.context = pcm[pcm.len() - 64..].to_vec();
        let samples = Tensor::from_array(([1usize, 576usize], input)).map_err(|e| e.to_string())?;
        let state = Tensor::from_array(([2usize, 1usize, 128usize], self.state.clone()))
            .map_err(|e| e.to_string())?;
        let sr =
            Tensor::from_array((Vec::<usize>::new(), vec![16000i64])).map_err(|e| e.to_string())?;
        let outputs = self
            .session
            .run(ort::inputs!["input"=>samples,"state"=>state,"sr"=>sr])
            .map_err(|e| e.to_string())?;
        let score = outputs["output"]
            .try_extract_tensor::<f32>()
            .map_err(|e| e.to_string())?
            .1[0];
        self.state = outputs["stateN"]
            .try_extract_tensor::<f32>()
            .map_err(|e| e.to_string())?
            .1
            .to_vec();
        Ok(score)
    }
}
type SharedChild = Arc<Mutex<Option<Child>>>;
struct Cleanup {
    child: SharedChild,
    url: String,
    key: String,
}
impl Drop for Cleanup {
    fn drop(&mut self) {
        stop_child(&self.child);
    }
}
fn stop_child(child: &SharedChild) {
    if let Some(mut process) = child.lock().unwrap().take() {
        let _ = process.kill();
        let _ = process.wait();
    }
}
/// A cleanup failure. `keep` is true when the runtime answered normally and can
/// serve the next dictation, so it stays loaded.
struct CleanupError {
    message: String,
    keep: bool,
}
impl CleanupError {
    fn reload(message: impl ToString) -> Self {
        Self {
            message: message.to_string(),
            keep: false,
        }
    }
    fn keep(message: &str) -> Self {
        Self {
            message: message.into(),
            keep: true,
        }
    }
}
impl Cleanup {
    fn start(exe: &Path, model: &Path, child_slot: SharedChild) -> Result<Self, String> {
        if !exe.exists() {
            return Err("Cleanup runtime is missing from this build".into());
        }
        let listener = std::net::TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let key = uuid::Uuid::new_v4().to_string();
        let mut cmd = Command::new(exe);
        cmd.args(["--model"]).arg(model).args([
            "--host",
            "127.0.0.1",
            "--port",
            &port.to_string(),
            "--threads",
            &inference_threads().min(4).to_string(),
            "--threads-batch",
            &inference_threads().min(4).to_string(),
            "--parallel",
            "1",
            "--spec-type",
            "ngram-simple",
            "--spec-ngram-simple-size-n",
            "3",
            "--spec-ngram-simple-size-m",
            "12",
            "--ctx-size",
            "4096",
            "--n-gpu-layers",
            "0",
            "--no-webui",
            "--log-disable",
        ]);
        cmd.env("LLAMA_API_KEY", &key);
        cmd.stdout(Stdio::null()).stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000);
        }
        let child = cmd.spawn().map_err(|e| e.to_string())?;
        *child_slot.lock().unwrap() = Some(child);
        let service = Self {
            child: child_slot,
            url: format!("http://127.0.0.1:{port}"),
            key,
        };
        let client = crate::local_http::client(Duration::from_millis(500))?;
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(120) {
            if service
                .child
                .lock()
                .unwrap()
                .as_mut()
                .ok_or("Cleanup stopped")?
                .try_wait()
                .map_err(|e| e.to_string())?
                .is_some()
            {
                return Err("Local cleanup runtime exited during model load".into());
            }
            if client
                .get(format!("{}/health", service.url))
                .bearer_auth(&service.key)
                .send()
                .map(|r| r.status().is_success())
                .unwrap_or(false)
            {
                return Ok(service);
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        Err("Cleanup model load timed out".into())
    }
    fn clean(
        &self,
        text: &str,
        progress: impl Fn(usize, usize, usize),
    ) -> Result<String, CleanupError> {
        let client =
            crate::local_http::client(Duration::from_secs(90)).map_err(CleanupError::reload)?;
        let mut out = Vec::new();
        let mut chunks = Vec::new();
        let mut current = String::new();
        for word in text.split_whitespace() {
            if current.len() + word.len() > 2400 {
                chunks.push(current);
                current = String::new();
            }
            if !current.is_empty() {
                current.push(' ');
            }
            current.push_str(word);
        }
        if !current.is_empty() {
            chunks.push(current);
        }
        let total = chunks.len();
        for (index, chunk) in chunks.into_iter().enumerate() {
            progress(index + 1, total, 0);
            let response = client.post(format!("{}/v1/chat/completions", self.url))
                .bearer_auth(&self.key).json(&json!({"messages":[{"role":"system","content":crate::transcript::CLEANUP_SYSTEM},{"role":"user","content":format!("{}\n{chunk}", crate::transcript::CLEANUP_CONTROL)}],"temperature":0,"max_tokens":1600,"stream":true,"chat_template_kwargs":{"enable_thinking":false}}))
                .send().and_then(|r| r.error_for_status()).map_err(CleanupError::reload)?;
            let mut text = String::new();
            let mut complete = false;
            let mut last_progress = Instant::now();
            for line in BufReader::new(response).lines() {
                let line = line.map_err(CleanupError::reload)?;
                let Some(data) = line.strip_prefix("data: ") else {
                    continue;
                };
                if data == "[DONE]" {
                    break;
                }
                let value: Value = serde_json::from_str(data).map_err(CleanupError::reload)?;
                if value.get("error").is_some() {
                    return Err(CleanupError::keep("the tidy-up model reported an error."));
                }
                let choice = &value["choices"][0];
                if choice["finish_reason"] == "length" {
                    return Err(CleanupError::keep("the tidy-up was cut short."));
                }
                if choice["finish_reason"] == "stop" {
                    complete = true;
                }
                if let Some(delta) = choice["delta"]["content"].as_str() {
                    text.push_str(delta);
                }
                if last_progress.elapsed() >= Duration::from_millis(250) {
                    progress(index + 1, total, text.split_whitespace().count());
                    last_progress = Instant::now();
                }
            }
            if !complete || text.trim().is_empty() {
                return Err(CleanupError::keep("the tidy-up reply was incomplete."));
            }
            progress(index + 1, total, text.split_whitespace().count());
            if !crate::transcript::preserves_literals(&chunk, &text) {
                return Err(CleanupError::keep(
                    "the tidy-up changed a number, name or \"not\".",
                ));
            }
            out.push(text.trim().to_string());
        }
        Ok(out.join("\n"))
    }
}
enum Message {
    Start {
        recording: String,
        model: String,
        backend: String,
        cleanup: bool,
        preload: bool,
        prompt: Option<String>,
        retain: bool,
        retain_cleanup: bool,
        ack: mpsc::Sender<Result<(), String>>,
    },
    Audio(AudioFrame),
    Hardware(mpsc::Sender<Value>),
    Stop,
    Cancel,
    Release(mpsc::Sender<()>),
    Shutdown,
}
pub struct Voice {
    retain: AtomicBool,
    retain_cleanup: AtomicBool,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
    tx: mpsc::Sender<Message>,
    busy: Arc<AtomicBool>,
    budget: AudioBudget,
    cancel: CancelToken,
    child: SharedChild,
}
impl Voice {
    pub fn new(dir: PathBuf, cleanup_exe: PathBuf, sink: EventSink) -> Self {
        let (tx, rx) = mpsc::channel();
        let budget = AudioBudget::default();
        let worker_budget = budget.clone();
        let busy = Arc::new(AtomicBool::new(false));
        let worker_busy = busy.clone();
        let cancel = CancelToken::new();
        let worker_cancel = cancel.clone();
        let child = Arc::new(Mutex::new(None));
        let worker_child = child.clone();
        let worker = std::thread::Builder::new()
            .name("voice-inference".into())
            .spawn(move || {
                worker(
                    rx,
                    dir,
                    cleanup_exe,
                    sink,
                    worker_child,
                    worker_budget,
                    worker_busy,
                    worker_cancel,
                )
            })
            .expect("voice worker");
        Self {
            retain: AtomicBool::new(true),
            retain_cleanup: AtomicBool::new(false),
            worker: Mutex::new(Some(worker)),
            tx,
            busy,
            budget,
            cancel,
            child,
        }
    }
    pub fn start(
        &self,
        recording: String,
        model: String,
        backend: String,
        cleanup: bool,
    ) -> Result<(), String> {
        self.start_request(recording, model, backend, cleanup, false, None)
    }
    pub fn start_with_prompt(
        &self,
        recording: String,
        model: String,
        backend: String,
        cleanup: bool,
        prompt: Option<String>,
    ) -> Result<(), String> {
        self.start_request(recording, model, backend, cleanup, false, prompt)
    }
    pub fn configure_legacy_cache(&self) -> Result<(), String> {
        self.release()?;
        self.retain.store(true, Ordering::Release);
        self.retain_cleanup.store(false, Ordering::Release);
        Ok(())
    }
    pub fn configure_cache(
        &self,
        model: String,
        backend: String,
        cleanup: bool,
        retain: bool,
    ) -> Result<(), String> {
        // Configure only while idle; UI serializes changes with recording start.
        if self.busy() {
            return Err("Stop dictation before changing model caching".into());
        }
        self.retain.store(retain, Ordering::Release);
        self.retain_cleanup.store(retain, Ordering::Release);
        if !retain {
            return self.release();
        }
        self.start_request(String::new(), model, backend, cleanup, true, None)
    }
    fn start_request(
        &self,
        recording: String,
        model: String,
        backend: String,
        cleanup: bool,
        preload: bool,
        prompt: Option<String>,
    ) -> Result<(), String> {
        if self.busy.swap(true, Ordering::AcqRel) {
            return Err("A recording is already active".into());
        }
        self.cancel.reset();
        let (tx, rx) = mpsc::channel();
        self.tx
            .send(Message::Start {
                recording,
                model,
                backend,
                cleanup,
                preload,
                prompt,
                retain: self.retain.load(Ordering::Acquire),
                retain_cleanup: self.retain_cleanup.load(Ordering::Acquire),
                ack: tx,
            })
            .map_err(|e| e.to_string())?;
        let result = match rx.recv_timeout(Duration::from_secs(180)) {
            Ok(result) => result,
            Err(e) => {
                self.cancel.cancel();
                let _ = self.tx.send(Message::Cancel);
                Err(e.to_string())
            }
        };
        if result.is_err() {
            self.busy.store(false, Ordering::Release);
        }
        result
    }
    pub fn audio(&self, samples: Vec<f32>) -> Result<(), String> {
        if samples.len() > 32000 || samples.iter().any(|x| !x.is_finite()) {
            return Err("Invalid audio frame".into());
        }
        if samples.is_empty() || !self.busy() {
            return Ok(());
        }
        let frame = self.budget.reserve(samples)?;
        self.tx
            .send(Message::Audio(frame))
            .map_err(|e| e.to_string())
    }
    pub fn stop(&self) -> Result<(), String> {
        self.tx.send(Message::Stop).map_err(|e| e.to_string())?;
        Ok(())
    }
    pub fn cancel(&self) {
        self.cancel.cancel();
        stop_child(&self.child);
        let _ = self.tx.send(Message::Cancel);
    }
    pub fn shutdown(&self) {
        self.cancel.cancel();
        stop_child(&self.child);
        let _ = self.tx.send(Message::Shutdown);
        // Native backend globals must outlive the inference thread and model destructors.
        // In particular libc++ tears its mutexes down at process exit on macOS.
        let worker = self.worker.lock().unwrap().take();
        if let Some(worker) = worker {
            let _ = worker.join();
        }
    }
    pub fn release(&self) -> Result<(), String> {
        if self.busy.swap(true, Ordering::AcqRel) {
            return Err("Stop dictation before changing or removing voice models".into());
        }
        let (tx, rx) = mpsc::channel();
        self.tx
            .send(Message::Release(tx))
            .map_err(|e| e.to_string())?;
        rx.recv_timeout(Duration::from_secs(120))
            .map_err(|e| e.to_string())
    }
    pub fn hardware(&self) -> Result<Value, String> {
        let (tx, rx) = mpsc::channel();
        self.tx
            .send(Message::Hardware(tx))
            .map_err(|e| e.to_string())?;
        rx.recv_timeout(Duration::from_secs(30))
            .map_err(|e| e.to_string())
    }
    pub fn busy(&self) -> bool {
        self.busy.load(Ordering::Acquire)
    }
}
impl Drop for Voice {
    fn drop(&mut self) {
        self.shutdown();
    }
}
fn worker(
    rx: mpsc::Receiver<Message>,
    dir: PathBuf,
    cleanup_exe: PathBuf,
    sink: EventSink,
    child: SharedChild,
    budget: AudioBudget,
    busy: Arc<AtomicBool>,
    cancel: CancelToken,
) {
    let backend_init = transcribe_cpp::init_backends_default().map_err(|e| e.to_string());
    let mut session: Option<Session> = None;
    let mut cached_cleanup: Option<Cleanup> = None;
    let mut retain = true;
    let mut retain_cleanup = false;
    let mut vad: Option<Vad> = None;
    let mut loaded = String::new();
    let mut actual = String::new();
    let mut enabled = false;
    let mut cleaning = false;
    let mut carry: VecDeque<f32> = VecDeque::new();
    let mut preroll: VecDeque<f32> = VecDeque::new();
    let mut segment: Vec<f32> = Vec::new();
    let mut committed = String::new();
    let mut silence = 0usize;
    let mut last_len = 0usize;
    let mut preview_interval = 16000 * 3;
    let threads = inference_threads();
    let mut recording_id = String::new();
    let mut decode_family = None;
    loop {
        if !enabled && !retain {
            session = None;
            vad = None;
            cached_cleanup = None;
            loaded.clear();
        }
        let Ok(msg) = rx.recv() else {
            break;
        };
        match msg {
            Message::Start {
                recording,
                model,
                backend,
                cleanup: want,
                preload,
                prompt,
                retain: keep,
                retain_cleanup: keep_cleanup,
                ack,
            } => {
                decode_family = if model.starts_with("whisper-") {
                    prompt.map(|initial_prompt| {
                        RunExtension::Whisper(WhisperRunOptions {
                            initial_prompt: Some(initial_prompt),
                            ..Default::default()
                        })
                    })
                } else {
                    None
                };
                retain = keep;
                retain_cleanup = keep_cleanup;
                recording_id = recording;
                let r = (|| {
                    backend_init.as_ref().map_err(Clone::clone)?;
                    if !catalog().iter().any(|m| m.id == model && m.family == "asr") {
                        return Err("Select a transcription model".into());
                    }
                    if loaded != format!("{model}:{backend}") {
                        session = None;
                        loaded.clear();
                        let path = model_path(&dir, &model)?;
                        if !path.exists() {
                            return Err("Download the transcription model in Settings first".into());
                        }
                        session = None;
                        let options = ModelOptions {
                            backend: match backend.as_str() {
                                "cpu" => Backend::Cpu,
                                "vulkan" => Backend::Vulkan,
                                _ => Backend::Auto,
                            },
                            ..Default::default()
                        };
                        let m = Model::load_with(path, &options).map_err(|e| e.to_string())?;
                        actual = m.backend();
                        session = Some(
                            m.session_with(&SessionOptions {
                                n_threads: threads as i32,
                                ..Default::default()
                            })
                            .map_err(|e| e.to_string())?,
                        );
                        session.as_mut().unwrap().set_cancel_token(&cancel);
                        loaded = format!("{model}:{backend}");
                    }
                    if vad.is_none() {
                        vad = Some(Vad::load(&model_path(&dir, "silero")?).map_err(|e| {
                            format!("Silero: {e}. Download voice detection in Settings.")
                        })?);
                    }
                    // Preload cleanup only for an idle cache request; otherwise load after capture.
                    if want && !model_path(&dir, "s1-mini")?.exists() {
                        return Err("Download S1-mini or turn cleanup off".into());
                    }
                    vad.as_mut().unwrap().reset();
                    if !want {
                        cached_cleanup = None;
                    }
                    if preload && want && cached_cleanup.is_none() {
                        cached_cleanup = Some(Cleanup::start(
                            &cleanup_exe,
                            &model_path(&dir, "s1-mini")?,
                            child.clone(),
                        )?);
                    }
                    enabled = !preload;
                    cleaning = want;
                    carry.clear();
                    preroll.clear();
                    segment.clear();
                    committed.clear();
                    silence = 0;
                    last_len = 0;
                    preview_interval = 16000 * 3;
                    if !preload {
                        emit_voice(
                            &sink,
                            &recording_id,
                            json!({"text":"","final":false,"backend":actual}),
                        );
                    }
                    Ok(())
                })();
                if preload {
                    busy.store(false, Ordering::Release);
                }
                if r.is_err() {
                    cached_cleanup = None;
                    session = None;
                    vad = None;
                    loaded.clear();
                    busy.store(false, Ordering::Release);
                }
                let _ = ack.send(r);
            }
            Message::Audio(frame) if enabled && !cancel.is_cancelled() => {
                carry.extend(frame.samples.iter().copied());
                drop(frame);
                while carry.len() >= 512 && !cancel.is_cancelled() {
                    let frame: Vec<f32> = carry.drain(..512).collect();
                    let score = match vad.as_mut().unwrap().speech(&frame) {
                        Ok(s) => s,
                        Err(e) => {
                            enabled = false;
                            busy.store(false, Ordering::Release);
                            emit_voice(
                                &sink,
                                &recording_id,
                                json!({"text":committed,"raw":committed,"final":true,"error":e}),
                            );
                            break;
                        }
                    };
                    if score >= 0.5 {
                        if segment.is_empty() {
                            segment.extend(preroll.drain(..));
                        }
                        segment.extend(&frame);
                        silence = 0;
                    } else if !segment.is_empty() {
                        segment.extend(&frame);
                        silence += 512;
                    } else {
                        preroll.extend(&frame);
                        while preroll.len() > 4096 {
                            preroll.pop_front();
                        }
                    }
                    // Batch complete speech when behind. Whisper pads short clips internally,
                    // so decoding every brief pause multiplies encoder work without saving audio.
                    let queued = budget.pending() + carry.len();
                    let final_segment = !segment.is_empty()
                        && (segment.len() >= 16000 * 12
                            || (silence >= 8192 && segment.len() >= 16000 * 4 && queued < 16000));
                    let partial = segment.len() >= last_len + preview_interval
                        && budget.pending() + carry.len() < 16000;
                    if final_segment || partial {
                        let decode_start = Instant::now();
                        match session.as_mut().unwrap().run(
                            &segment,
                            &RunOptions {
                                timestamps: TimestampKind::None,
                                family: decode_family.clone(),
                                ..Default::default()
                            },
                        ) {
                            Ok(result) => {
                                // Spend at most ~25% of capture time on speculative re-decodes.
                                preview_interval =
                                    (decode_start.elapsed().as_secs_f64() * 4.0 * 16000.0)
                                        .max(48000.0)
                                        .min(192000.0) as usize;
                                let text = result.text.trim();
                                if final_segment {
                                    if !committed.is_empty() && !text.is_empty() {
                                        committed.push(' ');
                                    }
                                    committed.push_str(text);
                                    segment.clear();
                                    last_len = 0;
                                    silence = 0;
                                    emit_voice(
                                        &sink,
                                        &recording_id,
                                        json!({"text":committed,"final":false,"pendingSeconds":budget.pending() as f64 / 16000.0}),
                                    );
                                } else {
                                    last_len = segment.len();
                                    emit_voice(
                                        &sink,
                                        &recording_id,
                                        json!({"text":format!("{}{}{}",committed,if committed.is_empty(){""}else{" "},text),"final":false,"pendingSeconds":budget.pending() as f64 / 16000.0}),
                                    );
                                }
                            }
                            Err(e) => {
                                enabled = false;
                                busy.store(false, Ordering::Release);
                                emit_voice(
                                    &sink,
                                    &recording_id,
                                    json!({"text":committed,"raw":committed,"final":true,"error":e.to_string()}),
                                );
                                break;
                            }
                        }
                    }
                }
            }
            Message::Stop => {
                if !enabled || cancel.is_cancelled() {
                    busy.store(false, Ordering::Release);
                    continue;
                }
                enabled = false;
                let final_started = Instant::now();
                sink(
                    "voice-phase",
                    json!({"recording":recording_id,"phase":"transcribing"}),
                );
                if !segment.is_empty() {
                    segment.extend(carry.drain(..));
                    match session.as_mut().unwrap().run(
                        &segment,
                        &RunOptions {
                            timestamps: TimestampKind::None,
                            family: decode_family.clone(),
                            ..Default::default()
                        },
                    ) {
                        Ok(r) => {
                            if !committed.is_empty() {
                                committed.push(' ')
                            }
                            committed.push_str(r.text.trim());
                        }
                        Err(e) => {
                            busy.store(false, Ordering::Release);
                            emit_voice(
                                &sink,
                                &recording_id,
                                json!({"text":committed,"raw":committed,"final":true,"error":e.to_string()}),
                            );
                            continue;
                        }
                    }
                }
                if cancel.is_cancelled() {
                    busy.store(false, Ordering::Release);
                    continue;
                }
                let asr_ms = final_started.elapsed().as_millis();
                let cleanup_started = Instant::now();
                let mut load_ms = 0u128;
                let result = if cleaning && !committed.trim().is_empty() {
                    sink(
                        "voice-phase",
                        json!({"recording":recording_id,"phase":"cleanup-loading"}),
                    );
                    let result = (|| {
                        if cached_cleanup.is_none() {
                            cached_cleanup = Some(
                                Cleanup::start(
                                    &cleanup_exe,
                                    &model_path(&dir, "s1-mini").map_err(CleanupError::reload)?,
                                    child.clone(),
                                )
                                .map_err(CleanupError::reload)?,
                            );
                            load_ms = cleanup_started.elapsed().as_millis();
                        }
                        cached_cleanup.as_ref().unwrap().clean(&committed, |part, total, words| {
                            sink("voice-phase", json!({"recording":recording_id,"phase":"cleanup","part":part,"total":total,"words":words}));
                        })
                    })();
                    // A rejected tidy-up is not a runtime fault; keep the model ready.
                    if !retain_cleanup || result.as_ref().is_err_and(|e| !e.keep) {
                        cached_cleanup = None;
                    }
                    result.map_err(|e| e.message)
                } else {
                    Ok(committed.clone())
                };
                sink(
                    "voice-metrics",
                    json!({"recording":recording_id,"finalAsrMs":asr_ms,"cleanupLoadMs":load_ms,"cleanupTotalMs":cleanup_started.elapsed().as_millis()}),
                );
                busy.store(false, Ordering::Release);
                if cancel.is_cancelled() {
                    continue;
                }
                match result {
                    Ok(text) => emit_voice(
                        &sink,
                        &recording_id,
                        json!({"text":text,"raw":committed,"final":true}),
                    ),
                    Err(e) => emit_voice(
                        &sink,
                        &recording_id,
                        json!({"text":committed,"raw":committed,"final":true,"error":format!("Tidy-up failed: {e} Your original words are kept.")}),
                    ),
                }
            }
            Message::Cancel => {
                if !retain_cleanup {
                    cached_cleanup = None;
                }
                enabled = false;
                busy.store(false, Ordering::Release);
                carry.clear();
                segment.clear();
                committed.clear();
            }
            Message::Release(ack) => {
                cached_cleanup = None;
                enabled = false;
                session = None;
                vad = None;
                loaded.clear();
                carry.clear();
                segment.clear();
                committed.clear();
                busy.store(false, Ordering::Release);
                let _ = ack.send(());
            }
            Message::Hardware(ack) => {
                let mut profile = hardware_profile();
                profile["loadedModel"] = json!(loaded);
                profile["pendingSamples"] = json!(budget.pending());
                profile["cleanupRunning"] = json!(child.lock().unwrap().is_some());
                let _ = ack.send(profile);
            }
            Message::Shutdown => break,
            _ => {}
        }
    }
}

fn emit_voice(sink: &EventSink, recording: &str, mut value: Value) {
    value["recording"] = json!(recording);
    sink("voice-text", value);
}

pub fn inference_threads() -> usize {
    let logical = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2);
    (logical / 2).clamp(1, 8)
}
fn memory_bytes() -> u64 {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
        let mut memory: MEMORYSTATUSEX = std::mem::zeroed();
        memory.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
        return if GlobalMemoryStatusEx(&mut memory) != 0 {
            memory.ullTotalPhys
        } else {
            0
        };
    }
    #[cfg(target_os = "linux")]
    {
        return fs::read_to_string("/proc/meminfo")
            .unwrap_or_default()
            .lines()
            .find_map(|s| {
                s.strip_prefix("MemTotal:")
                    .and_then(|s| s.split_whitespace().next())
                    .and_then(|s| s.parse::<u64>().ok())
            })
            .unwrap_or(0)
            * 1024;
    }
    #[cfg(target_os = "macos")]
    {
        return Command::new("/usr/sbin/sysctl")
            .args(["-n", "hw.memsize"])
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .and_then(|s| s.trim().parse().ok())
            .unwrap_or(0);
    }
    #[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
    {
        0
    }
}
fn hardware_profile() -> Value {
    let devices: Vec<Value> = transcribe_cpp::devices().iter().map(|d| json!({
        "name":d.description,"backend":d.kind,"memory":d.memory_total,"freeMemory":d.memory_free
    })).collect();
    json!({"logicalCpus":std::thread::available_parallelism().map(|n|n.get()).unwrap_or(1),
        "inferenceThreads":inference_threads(),"memory":memory_bytes(),"devices":devices,
        "vulkan":cfg!(feature="vulkan") && transcribe_cpp::backend_available(Backend::Vulkan)})
}
