fn main() {
    let args: Vec<_> = std::env::args().collect();
    let result = match args.get(1).map(String::as_str) {
        Some("serve") if args.len() == 3 => {
            dazedev::local_sessions::serve(std::path::Path::new(&args[2]))
        }
        Some("attach") if args.len() == 6 => dazedev::local_sessions::attach_console(
            dazedev::local_sessions::Client {
                root: args[2].clone().into(),
                host: args[3].clone(),
                shell: "powershell".into(),
            },
            args[4].clone(),
            args[5].clone(),
        ),
        _ => {
            Err("Use serve <data-folder> or attach <data-folder> <host> <session> <viewer>".into())
        }
    };
    if let Err(error) = result {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
