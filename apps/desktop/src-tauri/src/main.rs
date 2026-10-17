#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
fn main() {
    std::panic::set_hook(Box::new(|panic| {
        if let Ok(exe) = std::env::current_exe() {
            {
                // Same folder as the app's settings; installed Linux copies use XDG.
                let data = dazedev::platform::data_dir(&exe);
                let _ = std::fs::create_dir_all(&data);
                let _ = std::fs::write(data.join("startup-error.txt"), panic.to_string());
            }
        }
        eprintln!("DazeDev: {panic}");
    }));
    let args: Vec<_> = std::env::args_os().collect();
    if args.get(1).is_some_and(|a| a == "--apply-update") {
        let result = (|| {
            let root = std::path::PathBuf::from(args.get(2).ok_or("Missing update folder")?);
            let pid = args
                .get(3)
                .ok_or("Missing process ID")?
                .to_string_lossy()
                .parse::<u32>()
                .map_err(|e| e.to_string())?;
            dazedev::updates::run_helper(&root, pid)
        })();
        if let Err(e) = result {
            if let Some(root) = args.get(2) {
                let _ =
                    std::fs::write(std::path::Path::new(root).join("data/update-error.txt"), &e);
            }
            dazedev::updates::report_error(&e);
            std::process::exit(1);
        }
        return;
    }
    #[cfg(windows)]
    dazedev::app_identity::initialize().expect("Could not set DazeDev taskbar identity");
    dazedev::run();
}
