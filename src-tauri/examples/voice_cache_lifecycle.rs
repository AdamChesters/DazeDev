//! Native cache lifecycle regression. Uses installed models and a 16kHz mono WAV.
use dazedev::{transport::EventSink, voice::Voice};
use serde_json::Value;
use std::{
    path::PathBuf,
    sync::{mpsc, Arc},
    time::Duration,
};
fn main() {
    let a: Vec<_> = std::env::args().collect();
    assert_eq!(a.len(), 4, "MODEL_DIR LLAMA_SERVER WAV");
    let exe = PathBuf::from(&a[2]);
    std::env::set_var(
        "ORT_DYLIB_PATH",
        exe.parent()
            .unwrap()
            .join(dazedev::platform::onnx_library()),
    );
    let (tx, rx) = mpsc::channel();
    let sink: EventSink = Arc::new(move |name, v| {
        let _ = tx.send((name.to_string(), v));
    });
    let voice = Voice::new(PathBuf::from(&a[1]), exe, sink);
    let mut wav = hound::WavReader::open(&a[3]).unwrap();
    assert_eq!(wav.spec().sample_rate, 16000);
    assert_eq!(wav.spec().channels, 1);
    let pcm: Vec<f32> = wav
        .samples::<i16>()
        .take(48000)
        .map(|s| s.unwrap() as f32 / 32768.)
        .collect();
    voice
        .configure_cache("parakeet".into(), "cpu".into(), true, true)
        .unwrap();
    assert!(!voice.busy());
    assert!(
        rx.try_recv().is_err(),
        "Preload must not emit recording events"
    );
    let warm = voice.hardware().unwrap();
    assert_eq!(warm["loadedModel"], "parakeet:cpu");
    assert_eq!(warm["cleanupRunning"], true);
    for (n, retain) in [true, true, false, false].into_iter().enumerate() {
        if n == 2 {
            voice
                .configure_cache("parakeet".into(), "cpu".into(), true, false)
                .unwrap();
            let h = voice.hardware().unwrap();
            assert_eq!(h["loadedModel"], "");
            assert_eq!(h["cleanupRunning"], false);
        }
        let id = format!("cache-{n}");
        voice
            .start(id.clone(), "parakeet".into(), "cpu".into(), true)
            .unwrap();
        for chunk in pcm.chunks(16000) {
            voice.audio(chunk.to_vec()).unwrap();
        }
        voice.stop().unwrap();
        let mut metrics = Value::Null;
        loop {
            let (name, v) = rx.recv_timeout(Duration::from_secs(120)).unwrap();
            if v["recording"] != id {
                continue;
            }
            if name == "voice-metrics" {
                metrics = v;
                continue;
            }
            if name == "voice-text" && v["final"] == true {
                assert!(v.get("error").is_none(), "{v}");
                assert!(!v["text"].as_str().unwrap().is_empty());
                break;
            }
        }
        let h = voice.hardware().unwrap();
        assert_eq!(h["cleanupRunning"], retain);
        assert_eq!(h["loadedModel"], if retain { "parakeet:cpu" } else { "" });
        if retain {
            assert_eq!(metrics["cleanupLoadMs"], 0);
        } else {
            assert!(metrics["cleanupLoadMs"].as_u64().unwrap() > 0);
        }
        println!("PASS retained={retain} {metrics}");
    }
    voice.configure_legacy_cache().unwrap();
    assert_eq!(voice.hardware().unwrap()["loadedModel"], "");
    voice
        .start("legacy".into(), "parakeet".into(), "cpu".into(), true)
        .unwrap();
    for chunk in pcm.chunks(16000) {
        voice.audio(chunk.to_vec()).unwrap();
    }
    voice.stop().unwrap();
    loop {
        let (name, value) = rx.recv_timeout(Duration::from_secs(120)).unwrap();
        if name == "voice-text" && value["recording"] == "legacy" && value["final"] == true {
            assert!(value.get("error").is_none(), "{value}");
            break;
        }
    }
    let legacy = voice.hardware().unwrap();
    assert_eq!(legacy["loadedModel"], "parakeet:cpu");
    assert_eq!(legacy["cleanupRunning"], false);
    println!("PASS legacy policy: no preload; ASR retained and cleanup released after recording");
    voice
        .configure_cache("parakeet".into(), "cpu".into(), true, true)
        .unwrap();
    voice.cancel();
    assert_eq!(voice.hardware().unwrap()["cleanupRunning"], false);
    voice
        .configure_cache("parakeet".into(), "cpu".into(), true, true)
        .unwrap();
    assert_eq!(voice.hardware().unwrap()["cleanupRunning"], true);
    voice.release().unwrap();
    assert_eq!(voice.hardware().unwrap()["loadedModel"], "");
    assert_eq!(voice.hardware().unwrap()["cleanupRunning"], false);
    voice.shutdown();
    println!(
        "PASS preload, reuse, opt-out, repeated cold recordings, cancellation recovery and release"
    );
}
