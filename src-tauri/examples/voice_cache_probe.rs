//! Measure the real voice worker's first/repeated start and retained idle cost.
//! Linux /proc probe. Uses installed models only; never downloads or records audio.
//! Arguments: MODEL_DIR LLAMA_SERVER MODEL_ID (WAV_PATH | weights-only).
//! Timings do not include microphone capture, UI or IPC; OS file cache is untouched.
use dazedev::{transport::EventSink, voice::Voice};
use serde_json::json;
use std::{
    path::PathBuf,
    sync::{mpsc, Arc},
    time::{Duration, Instant},
};
fn snapshot() -> serde_json::Value {
    let status = std::fs::read_to_string("/proc/self/status").unwrap();
    let value = |key: &str| {
        status
            .lines()
            .find(|l| l.starts_with(key))
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse::<u64>()
            .unwrap()
    };
    let stat = std::fs::read_to_string("/proc/self/stat").unwrap();
    let fields: Vec<_> = stat
        .rsplit_once(") ")
        .unwrap()
        .1
        .split_whitespace()
        .collect();
    json!({"rssKiB":value("VmRSS:"),"peakKiB":value("VmHWM:"),"cpuTicks":fields[11].parse::<u64>().unwrap()+fields[12].parse::<u64>().unwrap()})
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    assert!(
        args.len() == 5,
        "Usage: voice_cache_probe MODEL_DIR LLAMA_SERVER MODEL_ID (WAV_PATH | weights-only)"
    );
    let dir = PathBuf::from(&args[1]);
    let exe = PathBuf::from(&args[2]);
    let model = &args[3];
    std::env::set_var(
        "ORT_DYLIB_PATH",
        exe.parent()
            .unwrap()
            .join(dazedev::platform::onnx_library()),
    );
    if args.last().map(String::as_str) == Some("weights-only") {
        use transcribe_cpp::{Backend, Model, ModelOptions, SessionOptions};
        transcribe_cpp::init_backends_default().unwrap();
        let baseline = snapshot();
        let t = Instant::now();
        let loaded = Model::load_with(
            dazedev::voice::model_path(&dir, model).unwrap(),
            &ModelOptions {
                backend: Backend::Cpu,
                ..Default::default()
            },
        )
        .unwrap();
        let load_ms = t.elapsed().as_secs_f64() * 1000.;
        let weights = snapshot();
        let mut sessions = Vec::new();
        for _ in 0..5 {
            let t = Instant::now();
            let session = loaded
                .session_with(&SessionOptions {
                    n_threads: dazedev::voice::inference_threads() as i32,
                    ..Default::default()
                })
                .unwrap();
            sessions.push(t.elapsed().as_secs_f64() * 1000.);
            drop(session);
        }
        let before = snapshot();
        std::thread::sleep(Duration::from_secs(10));
        println!(
            "{}",
            json!({"model":model,"baseline":baseline,"weights":weights,"loadMs":load_ms,"sessionMs":sessions,"beforeIdle":before,"afterIdle":snapshot()})
        );
        return;
    }
    let (tx, rx) = mpsc::channel();
    let sink: EventSink = Arc::new(move |name, v| {
        if name == "voice-text" && v["final"] == true {
            let _ = tx.send(v);
        }
    });
    let voice = Voice::new(dir, exe, sink);
    voice.hardware().unwrap();
    let baseline = snapshot();
    let mut starts = Vec::new();
    for n in 0..6 {
        let t = Instant::now();
        voice
            .start(format!("probe-{n}"), model.clone(), "cpu".into(), false)
            .unwrap();
        starts.push(t.elapsed().as_secs_f64() * 1000.);
        voice.cancel();
        voice.hardware().unwrap(); // Queue barrier: cancellation completed.
    }
    let ready = snapshot();
    voice
        .start("inference".into(), model.clone(), "cpu".into(), false)
        .unwrap();
    let mut reader = hound::WavReader::open(&args[4]).unwrap();
    assert_eq!(reader.spec().sample_rate, 16000);
    let pcm: Vec<f32> = reader
        .samples::<i16>()
        .take(16000 * 3)
        .map(|s| s.unwrap() as f32 / 32768.)
        .collect();
    for chunk in pcm.chunks(16000) {
        voice.audio(chunk.to_vec()).unwrap();
    }
    voice.stop().unwrap();
    let final_text = rx.recv_timeout(Duration::from_secs(120)).unwrap();
    assert!(final_text.get("error").is_none(), "{final_text}");
    voice.hardware().unwrap();
    let retained = snapshot();
    let t = Instant::now();
    std::thread::sleep(Duration::from_secs(10));
    let idle_seconds = t.elapsed().as_secs_f64();
    let idle = snapshot();
    voice.release().unwrap();
    let released = snapshot();
    voice.shutdown();
    println!(
        "{}",
        json!({"model":model,"startMs":starts,"baseline":baseline,"ready":ready,"retained":retained,"idle":idle,"idleSeconds":idle_seconds,"released":released})
    );
}
