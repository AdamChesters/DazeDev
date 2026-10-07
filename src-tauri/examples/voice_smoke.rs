use dazedev::{
    transport::EventSink,
    voice::{self, Voice},
};
use std::{
    path::PathBuf,
    sync::{mpsc, Arc},
    time::Duration,
};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let dir = PathBuf::from(&args[1]);
    let exe = PathBuf::from(&args[2]);
    let wav = &args[3];
    let model = args.get(4).map(String::as_str).unwrap_or("parakeet");
    let runtime = exe
        .parent()
        .unwrap()
        .join(dazedev::platform::onnx_library());
    std::env::set_var("ORT_DYLIB_PATH", runtime);
    let (tx, rx) = mpsc::channel();
    let sink: EventSink = Arc::new(move |name, v| {
        if name.starts_with("voice-") {
            println!("{v}");
            tx.send((name.to_string(), v)).unwrap();
        }
    });
    for id in ["silero", model, "s1-mini"] {
        if !voice::list(&dir)
            .iter()
            .find(|m| m.id == id)
            .unwrap()
            .installed
        {
            voice::download(&dir, id, sink.clone()).expect("model download");
        }
    }
    let voice = Voice::new(dir, exe, sink);
    voice
        .start("smoke".into(), model.into(), "cpu".into(), true)
        .expect("voice start");
    let mut reader = hound::WavReader::open(wav).unwrap();
    assert_eq!(reader.spec().sample_rate, 16000);
    assert_eq!(reader.spec().channels, 1);
    let samples: Vec<f32> = reader
        .samples::<i16>()
        .map(|s| s.unwrap() as f32 / 32768.)
        .collect();
    for chunk in samples.chunks(3200) {
        voice.audio(chunk.to_vec()).unwrap();
        std::thread::sleep(Duration::from_millis(200));
    }
    voice.stop().unwrap();
    let mut phases = Vec::new();
    let mut progress = false;
    let result = loop {
        let (name, value) = rx.recv_timeout(Duration::from_secs(180)).unwrap();
        if name == "voice-phase" {
            phases.push(value["phase"].as_str().unwrap().to_string());
            progress |= value["words"].as_u64().unwrap_or(0) > 0;
        }
        if value["final"] == true {
            break value;
        }
    };
    assert!(phases.contains(&"transcribing".to_string()));
    assert!(phases.contains(&"cleanup-loading".to_string()));
    assert!(phases.contains(&"cleanup".to_string()));
    assert!(
        progress,
        "Cleanup must report actual streamed output progress"
    );
    assert!(result.get("error").is_none(), "{result}");
    assert!(!result["text"].as_str().unwrap().trim().is_empty());
    voice.shutdown();
    voice.shutdown(); // Repeated shutdown and the subsequent Drop must be harmless.
    println!("PASS {model}: native transcription, Silero, S1-mini cleanup and joined shutdown");
}
