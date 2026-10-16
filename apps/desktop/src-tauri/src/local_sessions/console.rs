use super::{runtime_exe, Client};
use serde_json::json;
use std::{
    io::{Read, Write},
    os::windows::{io::AsRawHandle, process::CommandExt},
    time::{Duration, Instant},
};
use windows_sys::Win32::System::Console::*;
pub fn open_external(c: &Client, id: &str) -> Result<(), String> {
    if c.legacy() {
        return Err("End the PowerShell sessions from the earlier DazeDev and reconnect before opening a separate window.".into());
    }
    c.request("describe", json!({"id":id}))?;
    let viewer = uuid::Uuid::new_v4().to_string();
    let mut child = std::process::Command::new(runtime_exe(&c.root)?)
        .args(["attach", &c.root.to_string_lossy(), &c.host, id, &viewer])
        .creation_flags(0x00000010)
        .spawn()
        .map_err(|e| e.to_string())?;
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(10) {
        if c.request("view_ready", json!({"id":id,"viewer":viewer}))? == true {
            c.request("keep", json!({"id":id}))?;
            return Ok(());
        }
        if child.try_wait().map_err(|e| e.to_string())?.is_some() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let _ = child.kill();
    Err("PowerShell window did not attach. Your session is still running in DazeDev.".into())
}
struct Modes {
    input: windows_sys::Win32::Foundation::HANDLE,
    output: windows_sys::Win32::Foundation::HANDLE,
    input_mode: u32,
    output_mode: u32,
}
impl Drop for Modes {
    fn drop(&mut self) {
        unsafe {
            SetConsoleMode(self.input, self.input_mode);
            SetConsoleMode(self.output, self.output_mode);
        }
    }
}
pub fn attach(c: Client, id: String, viewer: String) -> Result<(), String> {
    let console_in = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("CONIN$")
        .map_err(|e| format!("Console input unavailable: {e}"))?;
    let console_out = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("CONOUT$")
        .map_err(|e| format!("Console output unavailable: {e}"))?;
    let modes = unsafe {
        let input = console_in.as_raw_handle();
        let output = console_out.as_raw_handle();
        SetStdHandle(STD_INPUT_HANDLE, input);
        SetStdHandle(STD_OUTPUT_HANDLE, output);
        let mut input_mode = 0;
        let mut output_mode = 0;
        if GetConsoleMode(input, &mut input_mode) == 0
            || GetConsoleMode(output, &mut output_mode) == 0
        {
            return Err("Open this session in a console window".into());
        }
        if SetConsoleMode(
            input,
            (input_mode | ENABLE_VIRTUAL_TERMINAL_INPUT)
                & !(ENABLE_LINE_INPUT
                    | ENABLE_ECHO_INPUT
                    | ENABLE_PROCESSED_INPUT
                    | ENABLE_QUICK_EDIT_MODE),
        ) == 0
            || SetConsoleMode(output, output_mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING) == 0
        {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let title: Vec<u16> = format!("DazeDev PowerShell / {id}")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        SetConsoleTitleW(title.as_ptr());
        SetConsoleCP(65001);
        SetConsoleOutputCP(65001);
        Modes {
            input,
            output,
            input_mode,
            output_mode,
        }
    };
    let mut input = c.input_channel(&id, None).map_err(String::from)?;
    std::thread::spawn(move || {
        let mut bytes = [0; 4096];
        let mut stdin = std::io::stdin();
        while let Ok(n) = stdin.read(&mut bytes) {
            if n == 0 || input.send(&bytes[..n]).is_err() {
                break;
            }
        }
    });
    // Console size is a local query; only changes go to the broker.
    let mut resizer = c.input_channel(&id, None).map_err(String::from)?;
    let console = modes.output as usize;
    let watching = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true));
    let watch = watching.clone();
    std::thread::spawn(move || {
        let mut size = (0, 0);
        while watch.load(std::sync::atomic::Ordering::Acquire) {
            unsafe {
                let mut screen: CONSOLE_SCREEN_BUFFER_INFO = std::mem::zeroed();
                if GetConsoleScreenBufferInfo(console as _, &mut screen) != 0 {
                    let next = (
                        (screen.srWindow.Right - screen.srWindow.Left + 1) as u16,
                        (screen.srWindow.Bottom - screen.srWindow.Top + 1) as u16,
                    );
                    if next != size {
                        if resizer.resize(next.0, next.1).is_err() {
                            break;
                        }
                        size = next
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });
    let mut out = std::io::stdout();
    let result = (|| {
        let mut view = c.view_stream(&id, &viewer, None, None)?;
        loop {
            let update = view.next()?;
            if update.reset {
                out.write_all(b"\x1b[2J\x1b[H").map_err(|e| e.to_string())?;
            }
            out.write_all(&update.data)
                .and_then(|_| out.flush())
                .map_err(|e| e.to_string())?;
            // Only acknowledge after console setup and the first successful render.
            c.request("view_attach_ack", json!({"id":id,"viewer":viewer}))?;
            if !update.running || update.detached {
                break;
            }
        }
        Ok::<(), String>(())
    })();
    watching.store(false, std::sync::atomic::Ordering::Release);
    let _ = c.request("view_detach", json!({"id":id,"viewer":viewer}));
    result
}
