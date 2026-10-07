//! Offline regression: burst-buffer a long recording, then verify release/reload.
use dazedev::{
    transport::EventSink,
    voice::{self, Voice},
};
use std::{
    path::PathBuf,
    sync::{mpsc, Arc},
    time::{Duration, Instant},
};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let dir = PathBuf::from(&args[1]);
    let exe = PathBuf::from(&args[2]);
    std::env::set_var(
        "ORT_DYLIB_PATH",
        exe.parent()
            .unwrap()
            .join(dazedev::platform::onnx_library()),
    );
    let (tx, rx) = mpsc::channel();
    let sink: EventSink = Arc::new(move |name, value| {
        if name == "voice-text" && value["final"] == true {
            let _ = tx.send(value);
        }
    });
    let mut reader = hound::WavReader::open(&args[3]).unwrap();
    assert_eq!(reader.spec().sample_rate, 16000);
    let samples: Vec<f32> = reader
        .samples::<i16>()
        .map(|s| s.unwrap() as f32 / 32768.)
        .collect();
    let voice = Voice::new(dir.clone(), exe, sink.clone());
    println!("Hardware: {}", voice.hardware().unwrap());
    for model in args.iter().skip(4) {
        for id in ["silero", model.as_str()] {
            if !voice::list(&dir).iter().any(|m| m.id == id && m.installed) {
                voice::download(&dir, id, sink.clone()).expect("download");
            }
        }
        let start = Instant::now();
        voice
            .start(model.clone(), model.clone(), "cpu".into(), false)
            .unwrap();
        for _ in 0..4 {
            for chunk in samples.chunks(3200) {
                voice.audio(chunk.to_vec()).unwrap();
            }
            voice.audio(vec![0.; 16000]).unwrap();
        }
        voice.stop().unwrap();
        let final_text = rx
            .recv_timeout(Duration::from_secs(600))
            .expect("final text");
        assert!(final_text.get("error").is_none(), "{final_text}");
        let text = final_text["text"].as_str().unwrap();
        assert!(
            text.split_whitespace().count() > 60,
            "Truncated long recording: {text}"
        );
        println!(
            "PASS {model}: {:.1}s audio accepted as a burst; {} words; {:.1}s load+decode",
            4. * (samples.len() as f64 / 16000. + 1.),
            text.split_whitespace().count(),
            start.elapsed().as_secs_f64()
        );
        voice.release().unwrap();
        let state = voice.hardware().unwrap();
        assert_eq!(state["loadedModel"], "");
        assert_eq!(state["pendingSamples"], 0);
        assert_eq!(state["cleanupRunning"], false);
        println!("PASS {model}: model released, no pending PCM or cleanup process");
    }
}
