use crate::sync::LockExt;
use crate::{
    transport::{EventSink, Host, Transport},
    voice::{self, Voice},
};
use serde_json::Value;
use std::{fs, path::PathBuf, sync::Arc};
use tauri::{Emitter, Manager, State};
struct AppState {
    pin: Arc<crate::pin::PinGate>,
    input: crate::input::Monitor,
    transport: Arc<Transport>,
    voice: Arc<Voice>,
    data: PathBuf,
    _instance_lock: fs::File,
    update_release: std::sync::Mutex<Option<crate::updates::Release>>,
    update_stage: std::sync::Mutex<Option<PathBuf>>,
    update_downloading: std::sync::atomic::AtomicBool,
    downloads:
        std::sync::Mutex<std::collections::HashMap<String, Arc<std::sync::atomic::AtomicBool>>>,
}
fn sink(app: &tauri::AppHandle) -> EventSink {
    let app = app.clone();
    Arc::new(move |event, value| {
        let _ = app.emit(event, value);
    })
}
#[tauri::command]
fn pin_status(state: State<'_, AppState>) -> crate::pin::Status {
    state.pin.status()
}
#[tauri::command]
async fn pin_unlock(pin: String, state: State<'_, AppState>) -> Result<(), String> {
    let gate = state.pin.clone();
    tauri::async_runtime::spawn_blocking(move || gate.unlock(&pin))
        .await
        .map_err(|_| "PIN could not be verified. Wait and try again.".to_string())?
}
#[tauri::command]
async fn pin_configure(
    current_pin: String,
    new_pin: Option<String>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let gate = state.pin.clone();
    tauri::async_runtime::spawn_blocking(move || gate.configure(&current_pin, new_pin.as_deref()))
        .await
        .map_err(|_| "PIN could not be verified. Wait and try again.".to_string())?
}
#[tauri::command]
async fn open_external(url: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || crate::browser::launch_url(url))
        .await
        .map_err(|_| "Browser launcher task failed".to_string())?
}
#[tauri::command]
fn input_configure(
    binding: Option<crate::input::Binding>,
    state: State<'_, AppState>,
) -> Result<bool, String> {
    state.input.configure(binding)
}
#[tauri::command]
async fn host_connect(
    attempt: Option<String>,
    host: Host,
    state: State<'_, AppState>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let emit = sink(&app);
        t.connect(
            host,
            Arc::new(move |event, mut value| {
                if event == "host-browser-open" {
                    // Reserve before scheduling work so forged/repeated frames cannot
                    // grow an unbounded native opener queue.
                    if app
                        .state::<AppState>()
                        .transport
                        .reserve_browser(
                            value["hostId"].as_str().unwrap_or(""),
                            value["nonce"].as_str().unwrap_or(""),
                            value["id"].as_str().unwrap_or(""),
                        )
                        .is_err()
                    {
                        return;
                    }
                    let app = app.clone();
                    tauri::async_runtime::spawn_blocking(move || {
                        let state = app.state::<AppState>();
                        let host = value["hostId"].as_str().unwrap_or("");
                        let nonce = value["nonce"].as_str().unwrap_or("");
                        let id = value["id"].as_str().unwrap_or("");
                        let result = if !state.pin.permits("open_external") {
                            Err("Unlock DazeDev before opening a browser link".into())
                        } else if !state.transport.browser_current(host, nonce) {
                            Err("Browser connection has expired".into())
                        } else {
                            crate::browser::validate_url(value["url"].as_str().unwrap_or(""))
                                .and_then(crate::browser::launch_url)
                        };
                        state
                            .transport
                            .browser_result(host, nonce, id, result.err());
                    });
                    return;
                }
                value["attempt"] = serde_json::json!(attempt);
                emit(event, value);
            }),
        )
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn host_rpc(
    host_id: String,
    method: String,
    args: Value,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.rpc(&host_id, &method, args))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn host_health(host_id: String, state: State<'_, AppState>) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.health(&host_id))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn host_auth(
    host_id: String,
    data: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.authenticate(&host_id, &data))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn host_disconnect(host_id: String, state: State<'_, AppState>) -> Result<(), String> {
    let t = state.transport.clone();
    let _ = tauri::async_runtime::spawn_blocking(move || t.disconnect(&host_id)).await;
    Ok(())
}
#[tauri::command]
async fn terminal_open(
    directory: Option<String>,
    generation: Option<u64>,
    ready_marker: Option<String>,
    id: String,
    host: Host,
    session: String,
    stamp: Option<String>,
    state: State<'_, AppState>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || {
        t.open_marked_directory(
            id,
            host,
            session,
            stamp,
            Arc::new(move |event, mut value| {
                value["generation"] = serde_json::json!(generation);
                let _ = app.emit(event, value);
            }),
            true,
            ready_marker,
            directory,
        )
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn terminal_input(
    id: String,
    data: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.input(&id, &data))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
fn terminal_ack(id: String, bytes: usize, state: State<'_, AppState>) {
    state.transport.ack(&id, bytes);
}
#[tauri::command]
async fn terminal_binary(
    id: String,
    data: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(data)
        .map_err(|e| e.to_string())?;
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.binary(&id, &bytes))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn terminal_resize(
    id: String,
    cols: u16,
    rows: u16,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let t = state.transport.clone();
    tauri::async_runtime::spawn_blocking(move || t.resize(&id, cols, rows))
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn terminal_close(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let t = state.transport.clone();
    let _ = tauri::async_runtime::spawn_blocking(move || t.close(&id)).await;
    Ok(())
}
#[tauri::command]
async fn app_close(
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    main_window(&window)?;
    // Detach while the event loop can still serve terminal callbacks, then exit
    // all app windows. The independent broker keeps explicitly retained shells.
    state.input.shutdown();
    let transport = state.transport.clone();
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || {
        transport.shutdown();
        voice.shutdown();
    })
    .await
    .map_err(|e| e.to_string())?;
    // All application workers and session views are stopped before Tauri's
    // documented cleanup-and-exit path; kept shells belong to the broker.
    app.cleanup_before_exit();
    std::process::exit(0);
}
#[tauri::command]
fn device_load(state: State<'_, AppState>) -> Result<Value, String> {
    crate::storage::load_device(&state.data)
}
#[tauri::command]
fn device_save(value: Value, state: State<'_, AppState>) -> Result<(), String> {
    crate::storage::save_device(&state.data, &value)
}
#[tauri::command]
fn dictionary_load(state: State<'_, AppState>) -> Result<Value, String> {
    crate::dictionary::load_for_device(
        &state.data,
        &dirs::config_dir()
            .ok_or("User configuration directory unavailable")?
            .join("DazeDev"),
    )
}
#[tauri::command]
fn dictionary_save(value: Value, state: State<'_, AppState>) -> Result<(), String> {
    crate::dictionary::save_for_device(
        &state.data,
        &dirs::config_dir()
            .ok_or("User configuration directory unavailable")?
            .join("DazeDev"),
        &value,
    )
}
#[tauri::command]
fn model_list(state: State<'_, AppState>) -> Vec<voice::ModelEntry> {
    voice::list(&state.data.join("models"))
}
#[tauri::command]
async fn model_download(
    id: String,
    state: State<'_, AppState>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let cancelled = Arc::new(std::sync::atomic::AtomicBool::new(false));
    {
        let mut downloads = state.downloads.locked();
        if downloads.contains_key(&id) {
            return Err("This model is already downloading".into());
        }
        downloads.insert(id.clone(), cancelled.clone());
    }
    let dir = state.data.join("models");
    let copy = id.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        voice::download_cancellable(&dir, &copy, sink(&app), &cancelled)
    })
    .await
    .map_err(|e| e.to_string());
    state.downloads.locked().remove(&id);
    result?
}
#[tauri::command]
fn model_cancel(id: String, state: State<'_, AppState>) {
    if let Some(flag) = state.downloads.locked().get(&id) {
        flag.store(true, std::sync::atomic::Ordering::Release);
    }
}
#[tauri::command]
async fn model_remove(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let voice = state.voice.clone();
    let p = voice::model_path(&state.data.join("models"), &id)?;
    tauri::async_runtime::spawn_blocking(move || {
        voice.release()?;
        fs::remove_file(p).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn voice_start(
    recording: String,
    model: String,
    backend: String,
    cleanup: bool,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let prompt = crate::dictionary::load(&state.data)
        .ok()
        .and_then(|v| crate::dictionary::vocabulary(&v));
    let v = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || {
        v.start_with_prompt(recording, model, backend, cleanup, prompt)
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
fn voice_audio(request: tauri::ipc::Request<'_>, state: State<'_, AppState>) -> Result<(), String> {
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("Expected binary PCM audio".into());
    };
    if bytes.len() % 4 != 0 || bytes.len() > 128000 {
        return Err("Invalid audio frame".into());
    }
    state.voice.audio(
        bytes
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect(),
    )
}
#[tauri::command]
async fn voice_release(state: State<'_, AppState>) -> Result<(), String> {
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || voice.release())
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn voice_cache(
    model: String,
    backend: String,
    cleanup: bool,
    retain: Option<bool>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || match retain {
        Some(retain) => voice.configure_cache(model, backend, cleanup, retain),
        None => voice.configure_legacy_cache(),
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn voice_hardware(state: State<'_, AppState>) -> Result<Value, String> {
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || voice.hardware())
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn voice_stop(state: State<'_, AppState>) -> Result<(), String> {
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || voice.stop())
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn voice_cancel(state: State<'_, AppState>) -> Result<(), String> {
    let voice = state.voice.clone();
    tauri::async_runtime::spawn_blocking(move || voice.cancel())
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn ssh_aliases() -> Vec<String> {
    dirs::home_dir()
        .map(|home| crate::ssh_config::aliases(&home))
        .unwrap_or_default()
}

#[tauri::command]
async fn release_history() -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(crate::updates::changelog)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn update_check(
    state: State<'_, AppState>,
) -> Result<Option<crate::updates::Release>, String> {
    if !cfg!(windows) {
        return Err("In-app updates are currently Windows-only. Download the latest package for this platform.".into());
    }
    let release =
        tauri::async_runtime::spawn_blocking(|| crate::updates::check(env!("CARGO_PKG_VERSION")))
            .await
            .map_err(|e| e.to_string())??;
    *state.update_release.locked() = release.clone();
    Ok(release)
}
#[tauri::command]
async fn update_download(
    version: String,
    state: State<'_, AppState>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    use std::sync::atomic::Ordering;
    if state.update_downloading.swap(true, Ordering::SeqCst) {
        return Err("An update is already downloading".into());
    }
    let result = async {
        let release = state
            .update_release
            .locked()
            .clone()
            .filter(|r| r.version == version)
            .ok_or("Check for updates again before downloading")?;
        let root = state.data.parent().unwrap().to_path_buf();
        let stage = tauri::async_runtime::spawn_blocking(move || {
            crate::updates::download(&root, &release, |done, total| {
                let _ = app.emit(
                    "update-progress",
                    serde_json::json!({"done":done,"total":total}),
                );
            })
        })
        .await
        .map_err(|e| e.to_string())??;
        *state.update_stage.locked() = Some(stage);
        Ok(())
    }
    .await;
    state.update_downloading.store(false, Ordering::SeqCst);
    result
}
#[tauri::command]
fn update_install(state: State<'_, AppState>, app: tauri::AppHandle) -> Result<(), String> {
    if !state.downloads.locked().is_empty() {
        return Err("Wait for voice model downloads to finish before updating".into());
    }
    let stage = state
        .update_stage
        .locked()
        .clone()
        .ok_or("Download and verify the update first")?;
    crate::updates::launch_helper(state.data.parent().unwrap(), &stage)?;
    state.voice.shutdown();
    state.transport.shutdown();
    app.exit(0);
    Ok(())
}
#[tauri::command]
async fn feedback_send(feedback: crate::feedback::Feedback) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || crate::feedback::send(feedback))
        .await
        .map_err(|_| "Feedback could not be sent. Please try again.".to_string())?
}
#[tauri::command]
fn client_info(state: State<'_, AppState>) -> Value {
    serde_json::json!({"version":env!("CARGO_PKG_VERSION"),"dataPath":state.data,"portable":true,"updateSupported":cfg!(windows),"migration":fs::read_to_string(state.data.join("migration.log")).unwrap_or_default()})
}
#[derive(Default)]
struct TerminalWindows(std::sync::Mutex<std::collections::BTreeMap<String, String>>);

fn main_window(window: &tauri::WebviewWindow) -> Result<(), String> {
    if window.label() == "main" {
        Ok(())
    } else {
        Err("Only the workspace window can manage terminal windows".into())
    }
}

#[tauri::command]
async fn terminal_window_open(
    pane_id: String,
    workspace_id: String,
    title: String,
    x: Option<f64>,
    y: Option<f64>,
    dragging: bool,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    windows: State<'_, TerminalWindows>,
) -> Result<(), String> {
    main_window(&window)?;
    uuid::Uuid::parse_str(&pane_id).map_err(|_| "Invalid terminal identifier")?;
    uuid::Uuid::parse_str(&workspace_id).map_err(|_| "Invalid workspace identifier")?;
    let label = format!("terminal-{pane_id}");
    if let Some(existing) = app.get_webview_window(&label) {
        existing.set_focus().map_err(|e| e.to_string())?;
        return Ok(());
    }
    windows
        .0
        .lock()
        .map_err(|_| "Window registry unavailable")?
        .insert(pane_id.clone(), workspace_id);
    let mut builder = tauri::WebviewWindowBuilder::new(
        &app,
        &label,
        tauri::WebviewUrl::App(format!("index.html?terminal={pane_id}").into()),
    )
    .title(title)
    .inner_size(960., 680.)
    .min_inner_size(420., 280.)
    .data_directory(state.data.join("webview"))
    .disable_drag_drop_handler();
    if let (Some(x), Some(y)) = (x, y) {
        if x.is_finite() && y.is_finite() {
            builder = builder.position(x, y);
        }
    }
    match builder.build() {
        Ok(created) => {
            if dragging {
                let _ = created.start_dragging();
            }
            Ok(())
        }
        Err(error) => {
            windows
                .0
                .lock()
                .map_err(|_| "Window registry unavailable")?
                .remove(&pane_id);
            Err(error.to_string())
        }
    }
}

#[tauri::command]
async fn terminal_window_request(
    request_id: String,
    action: String,
    args: Value,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
    windows: State<'_, TerminalWindows>,
) -> Result<(), String> {
    let pane_id = window
        .label()
        .strip_prefix("terminal-")
        .ok_or("Not a terminal window")?;
    if !windows
        .0
        .lock()
        .map_err(|_| "Window registry unavailable")?
        .contains_key(pane_id)
    {
        return Err("Terminal window is no longer attached".into());
    }
    app.emit_to(
        "main",
        "terminal-window-request",
        serde_json::json!({
            "paneId": pane_id, "requestId": request_id, "action": action, "args": args,
        }),
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn terminal_window_publish(
    pane_id: String,
    value: Value,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
) -> Result<(), String> {
    main_window(&window)?;
    app.emit_to(
        format!("terminal-{pane_id}"),
        "terminal-window-message",
        value,
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn terminal_window_close(
    pane_id: String,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
) -> Result<(), String> {
    main_window(&window)?;
    if let Some(child) = app.get_webview_window(&format!("terminal-{pane_id}")) {
        child.destroy().map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn terminal_window_focus(
    pane_id: String,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
) -> Result<(), String> {
    main_window(&window)?;
    let child = app
        .get_webview_window(&format!("terminal-{pane_id}"))
        .ok_or("Terminal window was closed")?;
    child.show().map_err(|e| e.to_string())?;
    child.unminimize().map_err(|e| e.to_string())?;
    child.set_focus().map_err(|e| e.to_string())?;
    app.emit_to(
        child.label(),
        "terminal-window-message",
        serde_json::json!({"kind":"focus"}),
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn terminal_workspace_activate(
    workspace_id: String,
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
    windows: State<'_, TerminalWindows>,
) -> Result<(), String> {
    main_window(&window)?;
    let panes: Vec<_> = windows
        .0
        .lock()
        .map_err(|_| "Window registry unavailable")?
        .iter()
        .filter(|(_, owner)| **owner == workspace_id)
        .map(|(pane, _)| pane.clone())
        .collect();
    for pane in panes {
        if let Some(child) = app.get_webview_window(&format!("terminal-{pane}")) {
            #[cfg(windows)]
            {
                use windows_sys::Win32::UI::WindowsAndMessaging::{
                    SetWindowPos, HWND_TOP, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE,
                };
                if let Ok(hwnd) = child.hwnd() {
                    unsafe {
                        SetWindowPos(
                            hwnd.0 as _,
                            HWND_TOP,
                            0,
                            0,
                            0,
                            0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
                        );
                    }
                }
            }
            #[cfg(not(windows))]
            {
                let _ = child.set_focus();
            }
        }
    }
    #[cfg(not(windows))]
    {
        let _ = window.set_focus();
    }
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(TerminalWindows::default())
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Focused(focused) = event {
                if let Some(pane) = window.label().strip_prefix("terminal-") {
                    let _ = window.app_handle().emit_to(
                        "main",
                        "terminal-window-focus",
                        serde_json::json!({"paneId":pane,"focused":focused}),
                    );
                }
            }
            if matches!(event, tauri::WindowEvent::Destroyed) {
                if let Some(pane) = window.label().strip_prefix("terminal-") {
                    if let Ok(mut registry) = window.state::<TerminalWindows>().0.lock() {
                        registry.remove(pane);
                    }
                    let _ = window.app_handle().emit_to(
                        "main",
                        "terminal-window-closed",
                        serde_json::json!({"paneId":pane}),
                    );
                } else if window.label() == "main" {
                    for (label, child) in window.app_handle().webview_windows() {
                        if label.starts_with("terminal-") {
                            let _ = child.destroy();
                        }
                    }
                }
            }
        })
        .setup(|app| {
            let root = crate::platform::portable_root(&std::env::current_exe()?);
            let (data, instance_lock) = crate::storage::prepare(&root, &app.path().app_data_dir()?)
                .map_err(std::io::Error::other)?;
            let resources = app.path().resource_dir()?;
            let mut runtime = if root.join("runtime").is_dir() {
                root.join("runtime")
            } else {
                resources.join("runtime")
            };
            if cfg!(debug_assertions) && !runtime.exists() {
                runtime = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("runtime");
            }
            let ort_path = runtime.join(crate::platform::onnx_library());
            if ort_path.exists() {
                std::env::set_var("ORT_DYLIB_PATH", &ort_path);
            }
            #[cfg(windows)]
            {
                let path = std::env::var("PATH").unwrap_or_default();
                std::env::set_var("PATH", format!("{};{path}", runtime.display()));
            }
            let exe = runtime.join(if cfg!(windows) {
                "llama-server.exe"
            } else {
                "llama-server"
            });
            let voice = Voice::new(data.join("models"), exe, sink(app.handle()));
            let input_app = app.handle().clone();
            let transport = Arc::new(Transport::default());
            transport.set_native_root(data.join("native-sessions"));
            app.manage(AppState {
                pin: Arc::new(crate::pin::PinGate::open(data.clone())),
                input: crate::input::Monitor::start(move |pressed| {
                    let _ = input_app.emit("input-shortcut", pressed);
                }),
                transport,
                voice: Arc::new(voice),
                data: data.clone(),
                _instance_lock: instance_lock,
                update_release: Default::default(),
                update_stage: Default::default(),
                update_downloading: Default::default(),
                downloads: Default::default(),
            });
            let config = app.config().app.windows[0].clone();
            tauri::WebviewWindowBuilder::from_config(app, &config)?
                .data_directory(data.join("webview"))
                .build()?;
            Ok(())
        })
        .invoke_handler(|invoke| {
            let allowed = invoke
                .message
                .webview()
                .state::<AppState>()
                .pin
                .permits(invoke.message.command());
            if !allowed {
                invoke.resolver.reject("Unlock DazeDev to continue.");
                return true;
            }
            let dispatch: fn(tauri::ipc::Invoke<tauri::Wry>) -> bool = tauri::generate_handler![
                terminal_window_open,
                terminal_window_request,
                terminal_window_publish,
                terminal_window_close,
                terminal_window_focus,
                terminal_workspace_activate,
                pin_status,
                pin_unlock,
                pin_configure,
                open_external,
                feedback_send,
                input_configure,
                update_check,
                release_history,
                update_download,
                update_install,
                client_info,
                ssh_aliases,
                host_connect,
                host_health,
                host_rpc,
                host_auth,
                host_disconnect,
                terminal_open,
                terminal_input,
                terminal_ack,
                terminal_binary,
                terminal_resize,
                terminal_close,
                app_close,
                device_load,
                device_save,
                dictionary_load,
                dictionary_save,
                model_list,
                model_download,
                model_cancel,
                model_remove,
                voice_start,
                voice_audio,
                voice_release,
                voice_cache,
                voice_hardware,
                voice_stop,
                voice_cancel
            ];
            dispatch(invoke)
        })
        .build(tauri::generate_context!())
        .unwrap_or_else(|e| {
            crate::updates::report_error(&e.to_string());
            std::process::exit(1);
        })
        .run(|app, event| {
            if matches!(event, tauri::RunEvent::Exit) {
                let state = app.state::<AppState>();
                state.input.shutdown();
                state.voice.shutdown();
                state.transport.shutdown();
            }
        });
}
