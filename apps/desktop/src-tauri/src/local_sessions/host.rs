use super::{required, Server};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

pub fn executable(name: &str) -> Option<PathBuf> {
    std::env::split_paths(&std::env::var_os("PATH")?)
        .flat_map(|p| {
            [
                p.join(name),
                p.join(format!("{name}.exe")),
                p.join(format!("{name}.cmd")),
            ]
        })
        .find(|p| p.is_file())
}
pub fn agent_path(name: &str) -> Option<PathBuf> {
    #[cfg(windows)]
    {
        executable(&format!("{name}.exe")).or_else(|| executable(&format!("{name}.cmd")))
    }
    #[cfg(not(windows))]
    {
        executable(name)
    }
}
pub fn directory(value: &str) -> Result<PathBuf, String> {
    let p = if value.is_empty() {
        dirs::home_dir().ok_or("Home directory unavailable")?
    } else {
        PathBuf::from(value)
    };
    let p = p.canonicalize().map_err(|e| e.to_string())?;
    if !p.is_dir() {
        return Err("Choose an existing directory".into());
    }
    Ok(p)
}
fn run(program: &str, args: &[&str]) -> Result<String, String> {
    let mut command = Command::new(program);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(|e| e.to_string())?;
    let mut stdout = child.stdout.take().ok_or("Missing command output")?;
    let output = std::thread::spawn(move || {
        let mut data = Vec::new();
        let _ = stdout.by_ref().take(1024 * 1024).read_to_end(&mut data);
        data
    });
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait().map_err(|e| e.to_string())? {
            let data = output.join().map_err(|_| "Command output unavailable")?;
            return if status.success() {
                Ok(String::from_utf8_lossy(&data).trim().into())
            } else {
                Err("Command failed".into())
            };
        }
        if start.elapsed() > Duration::from_secs(10) {
            let _ = child.kill();
            let _ = child.wait();
            return Err("Command timed out".into());
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}
fn empty() -> Value {
    json!({"schema":1,"revision":0,"preferences":{"roots":[],"guidance":"","identities":[]},"workspaces":[],"discovery":{}})
}
fn profile_path(server: &Server, host: &str) -> Result<PathBuf, String> {
    uuid::Uuid::parse_str(host).map_err(|_| "Invalid native host identity")?;
    Ok(server.root.join(format!("profile-{host}.json")))
}
fn load(path: &Path) -> Result<Value, String> {
    if !path.exists() {
        return Ok(empty());
    }
    let v: Value = serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if v["schema"] != 1 || !v["workspaces"].is_array() || !v["preferences"].is_object() {
        return Err("Unsupported local profile; existing file preserved".into());
    }
    Ok(v)
}
fn repo(path: &Path) -> Option<Value> {
    let p = path.to_str()?;
    run("git", &["-C", p, "rev-parse", "--show-toplevel"]).ok()?;
    Some(
        json!({"name":path.file_name()?.to_string_lossy(),"path":path,"branch":run("git",&["-C",p,"branch","--show-current"]).unwrap_or_default(),"dirty":!run("git",&["-C",p,"status","--porcelain"]).unwrap_or_default().is_empty(),"remote":run("git",&["-C",p,"remote","get-url","origin"]).unwrap_or_default(),"worktrees":run("git",&["-C",p,"worktree","list","--porcelain"]).unwrap_or_default()}),
    )
}
fn repositories(profile: &Value) -> Vec<Value> {
    let mut result = Vec::new();
    let start = Instant::now();
    for root in profile["preferences"]["roots"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        let path = Path::new(root);
        if let Some(r) = repo(path) {
            result.push(r)
        }
        if let Ok(entries) = fs::read_dir(path) {
            for e in entries.flatten().take(100) {
                if start.elapsed() > Duration::from_secs(5) {
                    return result;
                }
                if e.path().is_dir() {
                    if let Some(r) = repo(&e.path()) {
                        result.push(r)
                    }
                }
            }
        }
    }
    result
}
fn editable(path: &Path) -> bool {
    matches!(
        path.extension()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_lowercase()
            .as_str(),
        "md" | "mdc" | "markdown"
    ) || matches!(
        path.file_name().and_then(|s| s.to_str()),
        Some(".cursorrules" | ".clinerules")
    )
}
fn read_file(path: &Path) -> Result<Value, String> {
    let path = path.canonicalize().map_err(|e| e.to_string())?;
    let f = fs::File::open(&path).map_err(|e| e.to_string())?;
    if !f.metadata().map_err(|e| e.to_string())?.is_file() {
        return Err("Choose a regular text file".into());
    }
    let mut bytes = Vec::new();
    f.take(512 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > 512 * 1024 || bytes.contains(&0) {
        return Err("Choose a UTF-8 text file up to 512 KiB".into());
    }
    let text = std::str::from_utf8(&bytes).map_err(|_| "Choose a UTF-8 text file")?;
    Ok(
        json!({"path":path,"text":text,"revision":format!("{:x}",Sha256::digest(&bytes)),"writable":editable(&path)&&!fs::metadata(&path).map_err(|e|e.to_string())?.permissions().readonly()}),
    )
}
fn image_mime(data: &[u8]) -> Result<&'static str, String> {
    if data.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Ok("image/png");
    }
    if data.starts_with(b"\xff\xd8\xff") {
        return Ok("image/jpeg");
    }
    if data.starts_with(b"GIF87a") || data.starts_with(b"GIF89a") {
        return Ok("image/gif");
    }
    if data.starts_with(b"RIFF") && data.get(8..12) == Some(b"WEBP") {
        return Ok("image/webp");
    }
    if data.starts_with(b"BM") {
        return Ok("image/bmp");
    }
    if data.starts_with(b"\x00\x00\x01\x00") {
        return Ok("image/x-icon");
    }
    if data.get(4..8) == Some(b"ftyp") && matches!(data.get(8..12), Some(b"avif") | Some(b"avis")) {
        return Ok("image/avif");
    }
    Err("Choose a PNG, JPEG, GIF, WebP, BMP, ICO or AVIF image".into())
}
fn viewer_path(a: &Value, home: &Path) -> Result<PathBuf, String> {
    let raw = required(a, "path")?;
    let path = if raw == "~" {
        home.to_path_buf()
    } else if let Some(relative) = raw.strip_prefix("~/").or_else(|| raw.strip_prefix("~\\")) {
        home.join(relative)
    } else {
        PathBuf::from(raw)
    };
    if path.is_absolute() {
        return path.canonicalize().map_err(|e| e.to_string());
    }
    let directory = a["directory"].as_str().unwrap_or("");
    let base = if let Some(relative) = directory
        .strip_prefix("~/")
        .or_else(|| directory.strip_prefix("~\\"))
    {
        home.join(relative)
    } else {
        PathBuf::from(directory)
    };
    let base = if base.is_absolute() {
        base
    } else {
        home.join(base)
    };
    match base.join(&path).canonicalize() {
        Ok(path) => Ok(path),
        Err(error)
            if error.kind() == std::io::ErrorKind::NotFound
                && !raw.starts_with("./")
                && !raw.starts_with("../")
                && !raw.starts_with(".\\")
                && !raw.starts_with("..\\")
                && base != home =>
        {
            home.join(path).canonicalize().map_err(|e| e.to_string())
        }
        Err(error) => Err(error.to_string()),
    }
}

fn image_read(a: &Value) -> Result<Value, String> {
    let path = viewer_path(a, &dirs::home_dir().ok_or("Home directory unavailable")?)?;
    if !fs::metadata(&path).map_err(|e| e.to_string())?.is_file() {
        return Err("Choose a regular image file".into());
    }
    let mut file = fs::File::open(&path).map_err(|e| e.to_string())?;
    let before = file.metadata().map_err(|e| e.to_string())?;
    let size = before.len();
    if size == 0 || size > 16 * 1024 * 1024 {
        return Err("Image exceeds 16 MiB or is empty".into());
    }
    let modified = before.modified().map_err(|e| e.to_string())?;
    let revision = format!("{}:{:?}", size, modified);
    if a.get("revision")
        .is_some_and(|r| r.as_str() != Some(&revision))
    {
        return Err("Image changed; reopen it".into());
    }
    let offset = match a.get("offset") {
        Some(v) => v.as_u64().ok_or("Invalid image offset")?,
        None => 0,
    };
    if offset >= size {
        return Err("Invalid image offset".into());
    }
    let mut header = [0; 16];
    let count = file.read(&mut header).map_err(|e| e.to_string())?;
    let mime = image_mime(&header[..count])?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| e.to_string())?;
    let mut data = Vec::new();
    (&mut file)
        .take(49152)
        .read_to_end(&mut data)
        .map_err(|e| e.to_string())?;
    let after = file.metadata().map_err(|e| e.to_string())?;
    if after.len() != size || after.modified().map_err(|e| e.to_string())? != modified {
        return Err("Image changed; reopen it".into());
    }
    Ok(
        json!({"path":path,"mime":mime,"revision":revision,"size":size,"offset":offset,"data":STANDARD.encode(data)}),
    )
}
struct Upload {
    file: fs::File,
    path: PathBuf,
    target: PathBuf,
    size: u64,
    offset: u64,
    host: String,
    touched: Instant,
}
#[derive(Default)]
pub struct Files {
    uploads: HashMap<String, Upload>,
}
impl Drop for Upload {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}
fn upload_folder(server: &Server, a: &Value) -> Result<PathBuf, String> {
    if a["paste"] == true {
        let p = server.root.join("uploads");
        fs::create_dir_all(&p).map_err(|e| e.to_string())?;
        directory(p.to_str().ok_or("Invalid upload folder")?)
    } else {
        directory(required(a, "directory")?)
    }
}
fn size(a: &Value) -> Result<u64, String> {
    let n = a["size"].as_u64().ok_or("Invalid file size")?;
    if n > if a["paste"] == true {
        10 * 1024 * 1024
    } else {
        256 * 1024 * 1024
    } {
        return Err("File exceeds upload limit".into());
    }
    Ok(n)
}
fn space(files: &Files, folder: &Path, size: u64, exclude: Option<&str>) -> Result<Value, String> {
    let free = fs2::available_space(folder).map_err(|e| e.to_string())?;
    let reserved: u64 = files
        .uploads
        .iter()
        .filter(|(id, _)| Some(id.as_str()) != exclude)
        .map(|(_, u)| u.size - u.offset)
        .sum();
    let available = free.saturating_sub(reserved);
    let required = size + 16 * 1024 * 1024;
    if available < required {
        return Err("Not enough destination space including 16 MiB headroom".into());
    }
    Ok(
        json!({"freeBytes":free,"availableBytes":available,"reservedBytes":reserved,"requiredBytes":required}),
    )
}
pub(super) fn request(
    server: &Server,
    host: &str,
    method: &str,
    a: &Value,
) -> Result<Value, String> {
    let profile = profile_path(server, host)?;
    match method {
        "profile_load" => {
            let _guard = server.profiles.lock().unwrap();
            load(&profile)
        }
        "profile_save" => {
            let _guard = server.profiles.lock().unwrap();
            let before = load(&profile)?;
            if before["revision"] != a["revision"] {
                return Err("CONFLICT: local workspace changed; reload or keep recovery".into());
            }
            let mut v = a["profile"].clone();
            if !v["workspaces"].is_array() || !v["preferences"].is_object() {
                return Err("Invalid profile".into());
            }
            v["schema"] = json!(1);
            v["revision"] = json!(
                before["revision"]
                    .as_u64()
                    .ok_or("Invalid profile revision")?
                    + 1
            );
            atomic_private(
                &profile,
                &serde_json::to_vec_pretty(&v).map_err(|e| e.to_string())?,
            )?;
            Ok(v)
        }
        "inventory" => {
            let p = load(&profile)?;
            let agents:Vec<_>=["codex","claude","gemini","opencode"].iter().map(|name|json!({"id":name,"path":agent_path(name),"version":"","models":[],"modelFlag":false,"effortFlag":false,"install":"Use the agent's official Windows installer in PowerShell"})).collect();
            Ok(
                json!({"hostname":std::env::var("COMPUTERNAME").unwrap_or_default(),"machine":"native-windows","user":std::env::var("USERNAME").unwrap_or_default(),"home":dirs::home_dir(),"tmux":null,"nativeSessions":true,"agents":agents,"identities":[],"repos":repositories(&p),"sessions":server.list(host),"warnings":[]}),
            )
        }
        "repositories" => Ok(json!({"repos":repositories(&load(&profile)?),"warnings":[]})),
        "repository_worktrees" => {
            let mut rows = Vec::new();
            for p in a["paths"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                let data = run("git", &["-C", p, "worktree", "list", "--porcelain"])?;
                for block in data.split("\n\n") {
                    let path = block.lines().find_map(|s| s.strip_prefix("worktree "));
                    if let Some(path) = path {
                        rows.push(json!({"path":path,"branch":block.lines().find_map(|s|s.strip_prefix("branch refs/heads/")).unwrap_or(""),"repository":p,"isMain":path==p,"createdAt":null}))
                    }
                }
            }
            Ok(
                json!({"groups":a["paths"].as_array().into_iter().flatten().filter_map(Value::as_str).map(|path|json!({"repositoryId":path,"name":Path::new(path).file_name().unwrap_or_default().to_string_lossy(),"worktrees":rows.iter().filter(|row|row["repository"]==path).map(|row|json!({"path":row["path"],"branch":row["branch"],"modifiedAt":null,"partial":true,"locked":false})).collect::<Vec<_>>() })).collect::<Vec<_>>(),"warnings":[]}),
            )
        }
        "image_read" => image_read(a),
        "file_read" | "document_read" => {
            let resolved = viewer_path(a, &dirs::home_dir().ok_or("Home directory unavailable")?)?;
            let path = resolved.as_path();
            if method == "document_read" && !editable(path) {
                return Err("Choose Markdown or an agent rule file".into());
            }
            read_file(path)
        }
        "document_save" => {
            let _guard = server.profiles.lock().unwrap();
            let path = Path::new(required(a, "path")?);
            let before = read_file(path)?;
            if before["writable"] != true {
                return Err("File is read only".into());
            }
            if before["revision"] != a["revision"] {
                return Err("CONFLICT: file changed; edits were not saved".into());
            }
            let text = required(a, "text")?;
            if text.len() > 512 * 1024 {
                return Err("Document exceeds 512 KiB".into());
            }
            let path = PathBuf::from(required(&before, "path")?);
            atomic(&path, text.as_bytes())?;
            read_file(&path)
        }
        "documents" => {
            let path = directory(a["path"].as_str().unwrap_or(""))?;
            let mut rows = Vec::new();
            for folder in [path.clone(), dirs::home_dir().unwrap_or(path)] {
                for name in [
                    "AGENTS.md",
                    "CLAUDE.md",
                    "GEMINI.md",
                    "README.md",
                    ".cursorrules",
                    ".clinerules",
                ] {
                    let p = folder.join(name);
                    if p.is_file() {
                        rows.push(json!({"path":p,"name":name,"scope":"local","source":"","readable":true,"writable":!fs::metadata(&p).map(|m|m.permissions().readonly()).unwrap_or(true)}))
                    }
                }
            }
            Ok(json!({"files":rows,"warnings":[]}))
        }
        "dictionary_read" | "dictionary_write" => {
            let _guard = server.profiles.lock().unwrap();
            let path = server.root.join("dictionary.json");
            let before = crate::dictionary::load(&server.root)?;
            let revision = if path.exists() {
                format!(
                    "{:x}",
                    Sha256::digest(fs::read(&path).map_err(|e| e.to_string())?)
                )
            } else {
                String::new()
            };
            if method == "dictionary_read" {
                return Ok(json!({"dictionary":before,"revision":revision}));
            }
            if a["revision"] != revision {
                return Ok(json!({"saved":false}));
            }
            crate::dictionary::save(&server.root, &a["dictionary"])?;
            Ok(json!({"saved":true}))
        }
        "upload_preflight" | "upload_begin" => {
            let folder = upload_folder(server, a)?;
            let n = size(a)?;
            let mut files = server.files.lock().unwrap();
            files
                .uploads
                .retain(|_, u| u.touched.elapsed() < Duration::from_secs(1800));
            let mut info = space(&files, &folder, n, None)?;
            if method == "upload_preflight" {
                info["directory"] = json!(folder);
                info["maxBytes"] = json!(if a["paste"] == true {
                    10 * 1024 * 1024
                } else {
                    256 * 1024 * 1024
                });
                return Ok(info);
            }
            if files.uploads.len() >= 4 {
                return Err("Finish or cancel another upload first".into());
            }
            let name = required(a, "name")?;
            if name.is_empty()
                || name
                    .chars()
                    .any(|c| c.is_control() || "/\\:<>\"|?*".contains(c))
                || name == "."
                || name == ".."
                || name.ends_with(['.', ' '])
            {
                return Err("Choose a regular filename".into());
            }
            let id = uuid::Uuid::new_v4().to_string();
            let target = folder.join(if a["paste"] == true {
                format!("{id}-{name}")
            } else {
                name.into()
            });
            if target.exists() {
                return Err("Destination already exists".into());
            }
            let path = folder.join(format!(".dazedev-upload-{id}"));
            let file = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
                .map_err(|e| e.to_string())?;
            files.uploads.insert(
                id.clone(),
                Upload {
                    file,
                    path,
                    target: target.clone(),
                    size: n,
                    offset: 0,
                    host: host.into(),
                    touched: Instant::now(),
                },
            );
            Ok(json!({"id":id,"path":target}))
        }
        "upload_chunk" | "upload_finish" | "upload_abort" => {
            let id = required(a, "id")?;
            let mut files = server.files.lock().unwrap();
            let u = files.uploads.get(id).ok_or("Upload unavailable")?;
            if u.host != host {
                return Err("Upload belongs to another host".into());
            }
            if method == "upload_abort" {
                files.uploads.remove(id);
                return Ok(json!(true));
            }
            space(
                &files,
                u.path.parent().unwrap(),
                u.size - u.offset,
                Some(id),
            )?;
            let u = files.uploads.get_mut(id).unwrap();
            if method == "upload_chunk" {
                let data = STANDARD
                    .decode(required(a, "data")?)
                    .map_err(|e| e.to_string())?;
                if data.len() > 32768
                    || a["offset"].as_u64() != Some(u.offset)
                    || u.offset + data.len() as u64 > u.size
                {
                    return Err("Upload offset or size mismatch".into());
                }
                u.file.write_all(&data).map_err(|e| e.to_string())?;
                u.offset += data.len() as u64;
                u.touched = Instant::now();
                return Ok(json!({"offset":u.offset}));
            }
            if u.offset != u.size {
                return Err("Upload incomplete".into());
            }
            u.file.sync_all().map_err(|e| e.to_string())?;
            fs::hard_link(&u.path, &u.target).map_err(|e| e.to_string())?;
            let result = json!({"path":u.target,"size":u.size});
            files.uploads.remove(id);
            Ok(result)
        }
        "process_context" => Ok(json!({"directory":null,"agent":"shell"})),
        "tmux_settings_read" | "tmux_settings_apply" => Err(
            "tmux settings apply to WSL and remote hosts; native PowerShell uses its own profile"
                .into(),
        ),
        _ => Err(format!("{method} is unavailable for native PowerShell")),
    }
}

fn atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    atomic_with_privacy(path, bytes, false)
}
fn atomic_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    atomic_with_privacy(path, bytes, true)
}
fn atomic_with_privacy(path: &Path, bytes: &[u8], private: bool) -> Result<(), String> {
    let temp = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut f = if private {
            crate::private_storage::create(&temp)
        } else {
            fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp)
        }
        .map_err(|e| e.to_string())?;
        f.write_all(bytes)
            .and_then(|_| f.sync_all())
            .map_err(|e| e.to_string())?;
        drop(f);
        fs::rename(&temp, path).map_err(|e| e.to_string())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

#[cfg(test)]
mod image_tests {
    use super::*;
    #[test]
    fn relative_viewer_paths_use_workspace_then_home_and_keep_dot_paths_explicit() {
        let home =
            std::env::temp_dir().join(format!("dazedev-relative-test-{}", uuid::Uuid::new_v4()));
        let workspace = home.join("workspace");
        fs::create_dir_all(&workspace).unwrap();
        fs::create_dir(home.join("dev")).unwrap();
        fs::write(home.join("dev/preview.png"), b"fixture").unwrap();
        fs::write(home.join("preview.html"), b"home").unwrap();
        fs::write(workspace.join("preview.html"), b"workspace").unwrap();
        let resolve = |path: &str| viewer_path(&json!({"path":path,"directory":workspace}), &home);
        assert_eq!(
            resolve("preview.html").unwrap(),
            workspace.join("preview.html").canonicalize().unwrap()
        );
        assert_eq!(
            resolve("dev/preview.png").unwrap(),
            home.join("dev/preview.png").canonicalize().unwrap()
        );
        assert!(resolve("./dev/preview.png").is_err());
        assert_eq!(
            resolve("../preview.html").unwrap(),
            home.join("preview.html").canonicalize().unwrap()
        );
        assert_eq!(
            resolve("~/preview.html").unwrap(),
            home.join("preview.html").canonicalize().unwrap()
        );
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn image_chunks_preserve_bytes_and_reject_changes_and_nonimages() {
        let root =
            std::env::temp_dir().join(format!("dazedev-image-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let path = root.join("it's image.png");
        let mut bytes = b"\x89PNG\r\n\x1a\n".to_vec();
        bytes.resize(60008, 0);
        fs::write(&path, &bytes).unwrap();
        let first = image_read(&json!({"path":path})).unwrap();
        let second =
            image_read(&json!({"path":path,"offset":49152,"revision":first["revision"]})).unwrap();
        let mut received = STANDARD.decode(first["data"].as_str().unwrap()).unwrap();
        received.extend(STANDARD.decode(second["data"].as_str().unwrap()).unwrap());
        assert_eq!(received, bytes);
        assert_eq!(first["mime"], "image/png");
        bytes.push(1);
        fs::write(&path, &bytes).unwrap();
        assert!(
            image_read(&json!({"path":path,"revision":first["revision"]}))
                .unwrap_err()
                .contains("changed")
        );
        fs::write(&path, b"<svg />").unwrap();
        assert!(image_read(&json!({"path":path})).is_err());
        assert!(image_read(&json!({"path":root})).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
