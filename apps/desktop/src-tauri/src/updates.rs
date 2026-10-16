//! Signed, consent-driven portable updates. No credentials are shipped to clients.
use base64::{engine::general_purpose::STANDARD, Engine};
use ed25519_dalek::{Signature, VerifyingKey};
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Cursor, Read},
    path::{Path, PathBuf},
    time::Duration,
};

const GITHUB_RELEASES: &str =
    "https://api.github.com/repos/AdamChesters/DazeDev/releases?per_page=100";
const GITHUB_DOWNLOAD: &str = "https://github.com/AdamChesters/DazeDev/releases/download/";
const MAX_ZIP: u64 = 256 * 1024 * 1024;
pub fn changelog() -> Result<serde_json::Value, String> {
    let bytes = fetch("https://raw.githubusercontent.com/AdamChesters/DazeDev/main/shared/releases/changelog.json", 1024 * 1024)?;
    serde_json::from_slice(&bytes).map_err(|e| e.to_string())
}
#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Release {
    pub version: String,
    pub notes: String,
    pub url: String,
    pub sha256: String,
    pub size: u64,
}
#[derive(Serialize, Deserialize)]
struct Signed {
    payload: String,
    signature: String,
}
pub fn verify_manifest(bytes: &[u8], current: &str, key: &str) -> Result<Option<Release>, String> {
    if bytes.len() > 65536 {
        return Err("Update metadata is too large".into());
    }
    let signed: Signed = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
    let key: [u8; 32] = STANDARD
        .decode(key.trim())
        .map_err(|e| e.to_string())?
        .try_into()
        .map_err(|_| "Invalid update public key")?;
    let key = VerifyingKey::from_bytes(&key).map_err(|e| e.to_string())?;
    let sig = Signature::from_slice(
        &STANDARD
            .decode(signed.signature)
            .map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    key.verify_strict(signed.payload.as_bytes(), &sig)
        .map_err(|_| "Update signature is invalid")?;
    let release: Release = serde_json::from_str(&signed.payload).map_err(|e| e.to_string())?;
    let version = Version::parse(&release.version).map_err(|e| e.to_string())?;
    if release.size == 0
        || release.size > MAX_ZIP
        || release.sha256.len() != 64
        || !release.sha256.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err("Invalid update file metadata".into());
    }
    let url = reqwest::Url::parse(&release.url).map_err(|e| e.to_string())?;
    if !github_asset(&release.url)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !url.path().ends_with(".zip")
    {
        return Err("Unexpected update download address".into());
    }
    let current = Version::parse(current).map_err(|e| e.to_string())?;
    Ok(
        (version > current && (!current.pre.is_empty() || version.pre.is_empty()))
            .then_some(release),
    )
}
fn public_key() -> &'static str {
    include_str!("../resources/update-public-key.txt")
}
fn github_asset(value: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(value) else {
        return false;
    };
    value.starts_with(GITHUB_DOWNLOAD)
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path().split('/').count() == 7
        && !value.contains('%')
}
fn asset_client(timeout: u64) -> Result<reqwest::blocking::Client, String> {
    crate::local_http::builder()
        .user_agent("DazeDev-updater")
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(timeout))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let url = attempt.url();
            if attempt.previous().len() >= 5 {
                return attempt.error("Too many update redirects");
            }
            if url.scheme() == "https"
                && url.username().is_empty()
                && url.password().is_none()
                && matches!(
                    url.host_str(),
                    Some(
                        "github.com"
                            | "release-assets.githubusercontent.com"
                            | "objects.githubusercontent.com"
                    )
                )
            {
                attempt.follow()
            } else {
                attempt.error("Unexpected update redirect")
            }
        }))
        .build()
        .map_err(|e| e.to_string())
}
fn fetch(url: &str, limit: u64) -> Result<Vec<u8>, String> {
    let response = asset_client(12)?
        .get(url)
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?;
    let mut bytes = vec![];
    response
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() as u64 > limit {
        return Err("Update metadata is too large".into());
    }
    Ok(bytes)
}
#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    assets: Vec<GithubAsset>,
}
#[derive(Deserialize)]
struct GithubAsset {
    name: String,
    browser_download_url: String,
}
fn github_candidate(bytes: &[u8], current: &str) -> Result<Option<(Version, String)>, String> {
    let current = Version::parse(current).map_err(|e| e.to_string())?;
    let releases: Vec<GithubRelease> = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
    Ok(releases
        .into_iter()
        .filter(|r| !r.draft)
        .filter_map(|r| {
            let version =
                Version::parse(r.tag_name.strip_prefix('v').unwrap_or(&r.tag_name)).ok()?;
            if version <= current
                || (current.pre.is_empty() && (r.prerelease || !version.pre.is_empty()))
            {
                return None;
            }
            let prefix = format!("{GITHUB_DOWNLOAD}{}/", r.tag_name);
            let asset = r.assets.into_iter().find(|a| {
                a.name == "latest.json"
                    && a.browser_download_url == format!("{prefix}latest.json")
                    && github_asset(&a.browser_download_url)
            })?;
            Some((version, asset.browser_download_url))
        })
        .max_by(|a, b| a.0.cmp(&b.0)))
}
// Discovery and downloads stay on the published GitHub release the user accepts.
fn discover(
    current: &str,
    key: &str,
    get: impl Fn(&str, u64) -> Result<Vec<u8>, String>,
) -> Result<Option<(Release, Vec<u8>)>, String> {
    let list = get(GITHUB_RELEASES, 2 * 1024 * 1024).map_err(|_| {
        "GitHub updates could not be reached. Check your connection and try again later."
    })?;
    let Some((version, url)) = github_candidate(&list, current)? else {
        return Ok(None);
    };
    let metadata = get(&url, 65536)
        .map_err(|_| "Update details could not be downloaded from GitHub. Try again later.")?;
    let release = verify_manifest(&metadata, current, key)?.ok_or("GitHub update is not newer")?;
    if release.version != version.to_string()
        || release.url.rsplit_once('/').map(|(p, _)| p) != url.rsplit_once('/').map(|(p, _)| p)
    {
        return Err("GitHub release does not match signed update metadata".into());
    }
    Ok(Some((release, metadata)))
}
pub fn check(current: &str) -> Result<Option<Release>, String> {
    discover(current, public_key(), fetch).map(|r| r.map(|(release, _)| release))
}
fn payload_path(name: &str) -> Result<PathBuf, String> {
    if name.contains(['\\', ':']) || name.starts_with('/') {
        return Err("Unsafe update path".into());
    }
    let p = Path::new(name);
    if p.components()
        .any(|c| !matches!(c, std::path::Component::Normal(_)))
    {
        return Err("Unsafe update path".into());
    }
    let lower = name.to_ascii_lowercase();
    if lower == "dazedev.exe"
        || (!lower.contains('/') && lower.ends_with(".dll"))
        || lower.starts_with("runtime/")
        || lower.starts_with("licenses/")
        || lower == "readme.txt"
    {
        Ok(p.to_path_buf())
    } else {
        Err("Update tried to replace portable user data".into())
    }
}
fn inspect_zip(bytes: &[u8]) -> Result<Vec<PathBuf>, String> {
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| e.to_string())?;
    if zip.len() > 2048 {
        return Err("Too many update files".into());
    }
    let mut total = 0u64;
    let mut names = std::collections::HashSet::new();
    let mut paths = vec![];
    let mut app = false;
    for i in 0..zip.len() {
        let mut f = zip.by_index(i).map_err(|e| e.to_string())?;
        if f.is_dir() {
            continue;
        }
        let p = payload_path(f.name())?;
        if !names.insert(f.name().to_ascii_lowercase())
            || f.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000)
        {
            return Err("Duplicate or linked update file".into());
        }
        total = total.checked_add(f.size()).ok_or("Update size overflow")?;
        if total > 512 * 1024 * 1024 {
            return Err("Expanded update is too large".into());
        }
        if f.name() == "DazeDev.exe" {
            let mut magic = [0; 2];
            f.read_exact(&mut magic).map_err(|e| e.to_string())?;
            app = magic == *b"MZ";
        }
        paths.push(p);
    }
    if !app {
        return Err("Update does not contain the Windows application".into());
    }
    Ok(paths)
}
fn extract(bytes: &[u8], root: &Path) -> Result<(), String> {
    extract_files(bytes, root, None)
}
fn retry_file_lock<T>(mut operation: impl FnMut() -> std::io::Result<T>) -> std::io::Result<T> {
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        match operation() {
            Err(e)
                if cfg!(windows)
                    && matches!(e.raw_os_error(), Some(5 | 32 | 33))
                    && std::time::Instant::now() < deadline =>
            {
                std::thread::sleep(Duration::from_millis(100));
            }
            result => return result,
        }
    }
}
fn extract_files(bytes: &[u8], root: &Path, selected: Option<&[PathBuf]>) -> Result<(), String> {
    inspect_zip(bytes)?;
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| e.to_string())?;
    for i in 0..zip.len() {
        let mut f = zip.by_index(i).map_err(|e| e.to_string())?;
        if f.is_dir() {
            continue;
        }
        let relative = payload_path(f.name())?;
        if selected.is_some_and(|paths| !paths.contains(&relative)) {
            continue;
        }
        let out = root.join(relative);
        replace_file(&mut f, &out)
            .map_err(|e| format!("Could not write {}: {e}", out.display()))?;
    }
    Ok(())
}
/// Write beside the target, then swap it in. A crash leaves either the old or
/// the new file in place, never a truncated one.
fn replace_file(source: &mut impl Read, out: &Path) -> std::io::Result<()> {
    fs::create_dir_all(
        out.parent()
            .ok_or_else(|| std::io::Error::other("No parent folder"))?,
    )?;
    let mut name = out.as_os_str().to_owned();
    name.push(".new");
    let staged = PathBuf::from(name);
    let result = (|| {
        let mut dest = fs::File::create(&staged)?;
        std::io::copy(source, &mut dest)?;
        dest.sync_all()?;
        drop(dest);
        retry_file_lock(|| swap_in(&staged, out))
    })();
    if result.is_err() {
        let _ = fs::remove_file(&staged);
    }
    result
}
#[cfg(windows)]
fn swap_in(staged: &Path, out: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let from: Vec<u16> = staged.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = out.as_os_str().encode_wide().chain(Some(0)).collect();
    if unsafe {
        MoveFileExW(
            from.as_ptr(),
            to.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}
#[cfg(not(windows))]
fn swap_in(staged: &Path, out: &Path) -> std::io::Result<()> {
    fs::rename(staged, out)?;
    if let Some(parent) = out.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}
/// Remove older update folders. Keep the newest `staged` and `backups`, and
/// never the folder this process runs from.
fn prune_updates(root: &Path, staged: usize, backups: usize) {
    let running = std::env::current_exe()
        .ok()
        .and_then(|exe| fs::canonicalize(exe).ok());
    let Ok(entries) = fs::read_dir(root.join(".updates")) else {
        return;
    };
    let mut found: Vec<(bool, std::time::SystemTime, PathBuf)> = entries
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().into_owned();
            let kind = if name.starts_with("staged-") {
                true
            } else if name.starts_with("backup-") {
                false
            } else {
                return None;
            };
            let meta = entry.path().symlink_metadata().ok()?;
            if !meta.is_dir() {
                return None;
            }
            let time = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
            Some((kind, time, entry.path()))
        })
        .collect();
    found.sort_by(|a, b| b.1.cmp(&a.1));
    for kind in [true, false] {
        let keep = if kind { staged } else { backups };
        for (_, _, path) in found.iter().filter(|f| f.0 == kind).skip(keep) {
            let in_use = running.as_ref().is_some_and(|exe| {
                fs::canonicalize(path).is_ok_and(|folder| exe.starts_with(folder))
            });
            if !in_use {
                let _ = fs::remove_dir_all(path);
            }
        }
    }
}
/// Progress events at most every 100 ms or 1%, plus the final one.
struct Throttle {
    last: Option<(std::time::Instant, u64)>,
}
impl Throttle {
    fn ready(&mut self, done: u64, total: u64, now: std::time::Instant) -> bool {
        let due = match self.last {
            None => true,
            Some((time, at)) => {
                done >= total
                    || now.duration_since(time) >= Duration::from_millis(100)
                    || done.saturating_sub(at) >= total / 100
            }
        };
        if due {
            self.last = Some((now, done));
        }
        due
    }
}
fn verify_zip(bytes: &[u8], release: &Release) -> Result<(), String> {
    if bytes.len() as u64 != release.size
        || format!("{:x}", Sha256::digest(bytes)) != release.sha256.to_ascii_lowercase()
    {
        return Err("Update checksum mismatch. Nothing was installed.".into());
    }
    inspect_zip(bytes)?;
    Ok(())
}
pub fn download(
    root: &Path,
    expected: &Release,
    progress: impl Fn(u64, u64),
) -> Result<PathBuf, String> {
    // Recheck signed metadata before download; a newer release needs fresh consent.
    // Pin the source the user approved, even if another source changes meanwhile.
    let metadata_url = if github_asset(&expected.url) {
        format!(
            "{}/latest.json",
            expected.url.rsplit_once('/').ok_or("Invalid update URL")?.0
        )
    } else {
        return Err("Unexpected update source".into());
    };
    let metadata = fetch(&metadata_url, 65536)?;
    let release = verify_manifest(&metadata, "0.0.0-0", public_key())?.ok_or("No update")?;
    if release.version != expected.version
        || release.sha256 != expected.sha256
        || release.url != expected.url
        || release.size != expected.size
    {
        return Err("The available update changed. Check again before installing.".into());
    }
    let mut response = asset_client(300)?
        .get(&release.url)
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?;
    let mut bytes = Vec::with_capacity(release.size as usize);
    let mut buf = [0u8; 65536];
    let mut throttle = Throttle { last: None };
    loop {
        let n = response.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        if bytes.len() as u64 + n as u64 > release.size {
            return Err("Update exceeded its signed size".into());
        }
        bytes.extend_from_slice(&buf[..n]);
        if throttle.ready(bytes.len() as u64, release.size, std::time::Instant::now()) {
            progress(bytes.len() as u64, release.size);
        }
    }
    verify_zip(&bytes, &release)?;
    let staging = root
        .join(".updates")
        .join(format!("staged-{}", uuid::Uuid::new_v4()));
    let mut archive =
        zip::ZipArchive::new(std::io::Cursor::new(&bytes)).map_err(|e| e.to_string())?;
    let mut required = release.size + 64 * 1024 * 1024;
    for i in 0..archive.len() {
        required = required.saturating_add(
            archive
                .by_index(i)
                .map_err(|e| e.to_string())?
                .size()
                .saturating_mul(2),
        );
    }
    if crate::platform::available_space(root).map_err(|e| e.to_string())? < required {
        return Err(
            "Not enough free space to stage the update and preserve a rollback copy.".into(),
        );
    }
    fs::create_dir_all(&staging).map_err(|e| e.to_string())?;
    let result = (|| {
        fs::write(staging.join("release.json"), metadata).map_err(|e| e.to_string())?;
        fs::write(staging.join("update.zip"), &bytes).map_err(|e| e.to_string())?;
        extract(&bytes, &staging.join("app"))?;
        Ok(staging.clone())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    } else {
        prune_updates(root, 1, 2);
    }
    result
}
// Preserve a complete backup of every replaced file. Keep user data untouched.
pub fn apply_archive(root: &Path, bytes: &[u8]) -> Result<PathBuf, String> {
    inspect_zip(bytes)?;
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| e.to_string())?;
    let mut paths = vec![];
    for i in 0..zip.len() {
        let mut file = zip.by_index(i).map_err(|e| e.to_string())?;
        if file.is_dir() {
            continue;
        }
        let relative = payload_path(file.name())?;
        let old = root.join(&relative);
        if old.exists() {
            let mut incoming = vec![];
            file.read_to_end(&mut incoming).map_err(|e| e.to_string())?;
            let existing = retry_file_lock(|| fs::read(&old)).map_err(|e| {
                format!(
                    "Could not read {}: {e}. No files were changed.",
                    old.display()
                )
            })?;
            if existing == incoming {
                continue;
            }
            retry_file_lock(|| fs::OpenOptions::new().write(true).open(&old)).map_err(|e| {
                format!(
                    "Could not replace {}: {e}. No files were changed. Close other DazeDev windows and retry the update.",
                    old.display()
                )
            })?;
        }
        paths.push(relative);
    }
    let backup = root
        .join(".updates")
        .join(format!("backup-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&backup).map_err(|e| e.to_string())?;
    let mut saved = vec![];
    for p in &paths {
        let old = root.join(p);
        if old.exists() {
            let to = backup.join(p);
            fs::create_dir_all(to.parent().unwrap()).map_err(|e| e.to_string())?;
            retry_file_lock(|| fs::copy(&old, &to)).map_err(|e| {
                format!(
                    "Could not back up {}: {e}. No files were changed.",
                    old.display()
                )
            })?;
            saved.push(p.clone());
        }
    }
    if let Err(e) = extract_files(bytes, root, Some(&paths)) {
        for p in &paths {
            if saved.contains(p) {
                let _ = fs::File::open(backup.join(p))
                    .and_then(|mut old| replace_file(&mut old, &root.join(p)));
            } else {
                let _ = fs::remove_file(root.join(p));
            }
        }
        return Err(format!(
            "Update failed; original files restored where possible. Backup: {}. {e}",
            backup.display()
        ));
    }
    Ok(backup)
}
#[cfg(windows)]
fn wait_parent(pid: u32) -> Result<(), String> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, WAIT_OBJECT_0},
        System::Threading::{OpenProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE},
    };
    unsafe {
        let h = OpenProcess(PROCESS_SYNCHRONIZE, 0, pid);
        if h.is_null() {
            return Ok(());
        }
        let result = WaitForSingleObject(h, 60000);
        CloseHandle(h);
        if result != WAIT_OBJECT_0 {
            return Err("The previous app did not exit. No files were changed.".into());
        }
    }
    Ok(())
}
pub fn run_helper(root: &Path, pid: u32) -> Result<(), String> {
    #[cfg(windows)]
    wait_parent(pid)?;
    #[cfg(not(windows))]
    {
        let _ = (root, pid);
        return Err("Portable installation is currently Windows-only".into());
    }
    #[cfg(windows)]
    {
        let canonical_root = fs::canonicalize(root).map_err(|e| e.to_string())?;
        let root = canonical_root.as_path();
        let data = root.join("data");
        let lock = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(data.join("instance.lock"))
            .map_err(|e| e.to_string())?;
        lock.try_lock()
            .map_err(|_| "Another DazeDev instance is using this folder")?;
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let stage =
            fs::canonicalize(exe.parent().unwrap().parent().unwrap()).map_err(|e| e.to_string())?;
        if stage.parent() != Some(root.join(".updates").as_path()) {
            return Err("Invalid update staging location".into());
        }
        let metadata = fs::read(stage.join("release.json")).map_err(|e| e.to_string())?;
        let release =
            verify_manifest(&metadata, "0.0.0-0", public_key())?.ok_or("Invalid update")?;
        let bytes = fs::read(stage.join("update.zip")).map_err(|e| e.to_string())?;
        verify_zip(&bytes, &release)?;
        let backup = apply_archive(root, &bytes)?;
        prune_updates(root, 1, 2);
        drop(lock);
        if let Err(e) = std::process::Command::new(root.join("DazeDev.exe"))
            .current_dir(root)
            .spawn()
        {
            for p in inspect_zip(&bytes)? {
                if backup.join(&p).exists() {
                    let _ = fs::File::open(backup.join(&p))
                        .and_then(|mut old| replace_file(&mut old, &root.join(&p)));
                }
            }
            let _ = std::process::Command::new(root.join("DazeDev.exe")).spawn();
            return Err(format!(
                "Could not restart update: {e}. Previous files restored."
            ));
        }
        Ok(())
    }
}
pub fn launch_helper(root: &Path, stage: &Path) -> Result<(), String> {
    #[cfg(not(windows))]
    {
        let _ = (root, stage);
        return Err("Portable installation is currently Windows-only".into());
    }
    #[cfg(windows)]
    {
        std::process::Command::new(stage.join("app/DazeDev.exe"))
            .args(["--apply-update"])
            .arg(root)
            .arg(std::process::id().to_string())
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use std::io::Write;
    fn signed(version: &str) -> (Vec<u8>, String) {
        let key = SigningKey::from_bytes(&[7; 32]);
        let payload = serde_json::to_string(&Release {
            version: version.into(),
            notes: "test".into(),
            url: format!("{GITHUB_DOWNLOAD}v{version}/test.zip"),
            sha256: "a".repeat(64),
            size: 12,
        })
        .unwrap();
        let signature = STANDARD.encode(key.sign(payload.as_bytes()).to_bytes());
        (
            serde_json::to_vec(&Signed { payload, signature }).unwrap(),
            STANDARD.encode(key.verifying_key().to_bytes()),
        )
    }
    #[test]
    fn release_series_orders_for_existing_alpha_and_stable_clients() {
        let (base, key) = signed("0.3.0-alpha.0");
        assert!(verify_manifest(&base, "0.2.0-alpha.30", &key)
            .unwrap()
            .is_some());
        assert!(verify_manifest(&base, "0.3.0-alpha.0", &key)
            .unwrap()
            .is_none());
        assert!(verify_manifest(&base, "0.2.0", &key).unwrap().is_none());
        let (increment, key) = signed("0.3.1-alpha.0");
        assert!(verify_manifest(&increment, "0.3.0-alpha.0", &key)
            .unwrap()
            .is_some());
        let (step, key) = signed("0.4.0-alpha.0");
        assert!(verify_manifest(&step, "0.3.2-alpha.0", &key)
            .unwrap()
            .is_some());
        let (stable, key) = signed("1.0.0");
        assert!(verify_manifest(&stable, "0.4.0-alpha.0", &key)
            .unwrap()
            .is_some());
        let (increment, key) = signed("1.0.1");
        assert!(verify_manifest(&increment, "1.0.0", &key)
            .unwrap()
            .is_some());
        let (step, key) = signed("1.1.0");
        assert!(verify_manifest(&step, "1.0.2", &key).unwrap().is_some());
    }
    #[test]
    fn signatures_and_prerelease_order() {
        let (mut b, k) = signed("0.2.0-alpha.10");
        assert!(verify_manifest(&b, "0.2.0-alpha.2", &k).unwrap().is_some());
        assert!(verify_manifest(&b, "0.2.0", &k).unwrap().is_none());
        assert!(verify_manifest(&b, "0.2.0-alpha.10", &k).unwrap().is_none());
        b[15] ^= 1;
        assert!(verify_manifest(&b, "0.0.0", &k).is_err());
    }
    fn github_fixture(version: &str, draft: bool) -> serde_json::Value {
        serde_json::json!({"tag_name":format!("v{version}"), "draft":draft,
            "prerelease":version.contains('-'), "assets":[{"name":"latest.json",
            "browser_download_url":format!("{GITHUB_DOWNLOAD}v{version}/latest.json")}]})
    }
    fn github_signed(version: &str, url: &str) -> (Vec<u8>, String) {
        let key = SigningKey::from_bytes(&[7; 32]);
        let payload = serde_json::to_string(&Release {
            version: version.into(),
            notes: "test".into(),
            url: url.into(),
            sha256: "a".repeat(64),
            size: 12,
        })
        .unwrap();
        let signature = STANDARD.encode(key.sign(payload.as_bytes()).to_bytes());
        (
            serde_json::to_vec(&Signed { payload, signature }).unwrap(),
            STANDARD.encode(key.verifying_key().to_bytes()),
        )
    }
    #[test]
    fn github_selection_handles_public_transition_and_channels() {
        let list = serde_json::to_vec(&vec![
            github_fixture("0.2.0-alpha.2", false),
            github_fixture("0.2.0", false),
            github_fixture("0.2.0-alpha.10", false),
            github_fixture("9.0.0", true),
            github_fixture("0.3.0-alpha.1", false),
        ])
        .unwrap();
        assert_eq!(
            github_candidate(&list, "0.2.0-alpha.17")
                .unwrap()
                .unwrap()
                .0
                .to_string(),
            "0.3.0-alpha.1"
        );
        assert!(github_candidate(&list, "0.2.0").unwrap().is_none());
        let list = serde_json::to_vec(&vec![github_fixture("0.2.0", false)]).unwrap();
        assert_eq!(
            github_candidate(&list, "0.2.0-alpha.17")
                .unwrap()
                .unwrap()
                .0
                .to_string(),
            "0.2.0"
        );
        assert!(github_candidate(&list, "0.2.0").unwrap().is_none());
    }
    #[test]
    fn desktop_updater_ignores_android_releases_even_with_a_manifest() {
        let mut android = github_fixture("99.0.0", false);
        android["tag_name"] = serde_json::json!("android/v99.0.0");
        android["assets"][0]["browser_download_url"] =
            serde_json::json!(format!("{GITHUB_DOWNLOAD}android/v99.0.0/latest.json"));
        let list =
            serde_json::to_vec(&vec![android.clone(), github_fixture("0.4.0", false)]).unwrap();
        assert_eq!(
            github_candidate(&list, "0.3.3-alpha.0")
                .unwrap()
                .unwrap()
                .0
                .to_string(),
            "0.4.0"
        );
        assert!(github_candidate(
            &serde_json::to_vec(&vec![android]).unwrap(),
            "0.3.3-alpha.0"
        )
        .unwrap()
        .is_none());
    }
    #[test]
    fn discovers_only_the_matching_github_release() {
        let list = serde_json::to_vec(&vec![github_fixture("0.2.0", false)]).unwrap();
        let (metadata, key) = github_signed("0.2.0", &format!("{GITHUB_DOWNLOAD}v0.2.0/app.zip"));
        let result = discover("0.2.0-alpha.17", &key, |url, _| match url {
            GITHUB_RELEASES => Ok(list.clone()),
            value if value == format!("{GITHUB_DOWNLOAD}v0.2.0/latest.json") => {
                Ok(metadata.clone())
            }
            _ => panic!("Unexpected update request: {url}"),
        })
        .unwrap()
        .unwrap();
        assert_eq!(result.0.version, "0.2.0");
        assert_eq!(result.1, metadata);
    }
    #[test]
    fn current_and_empty_release_lists_need_only_one_request() {
        let (_, key) = signed("0.2.0");
        for list in [
            b"[]".to_vec(),
            serde_json::to_vec(&vec![
                github_fixture("0.2.0", false),
                github_fixture("0.3.0-alpha.1", false),
            ])
            .unwrap(),
        ] {
            let calls = std::cell::Cell::new(0);
            assert!(discover("0.2.0", &key, |url, _| {
                assert_eq!(url, GITHUB_RELEASES);
                calls.set(calls.get() + 1);
                Ok(list.clone())
            })
            .unwrap()
            .is_none());
            assert_eq!(calls.get(), 1);
        }
    }
    #[test]
    fn unavailable_or_invalid_github_responses_fail_without_another_destination() {
        let (_, key) = signed("0.2.0");
        for response in [
            Err("404".into()),
            Err("429".into()),
            Err("offline".into()),
            Ok(b"not json".to_vec()),
        ] {
            let calls = std::cell::Cell::new(0);
            assert!(discover("0.1.0", &key, |url, _| {
                assert_eq!(url, GITHUB_RELEASES);
                calls.set(calls.get() + 1);
                response.clone()
            })
            .is_err());
            assert_eq!(calls.get(), 1);
        }
        let list = serde_json::to_vec(&vec![github_fixture("0.2.0", false)]).unwrap();
        let calls = std::cell::Cell::new(0);
        assert!(discover("0.1.0", &key, |url, _| {
            calls.set(calls.get() + 1);
            if url == GITHUB_RELEASES {
                Ok(list.clone())
            } else {
                assert_eq!(url, format!("{GITHUB_DOWNLOAD}v0.2.0/latest.json"));
                Err("unavailable".into())
            }
        })
        .is_err());
        assert_eq!(calls.get(), 2);
    }
    #[test]
    fn rejects_unsigned_or_mismatched_github_updates() {
        let list = serde_json::to_vec(&vec![github_fixture("0.2.0", false)]).unwrap();
        for url in [
            format!("{GITHUB_DOWNLOAD}v0.1.0/app.zip"),
            "http://updates.example.test/releases/test.zip".into(),
            "https://github.com/attacker/DazeDev/releases/download/v0.2.0/app.zip".into(),
        ] {
            let (metadata, key) = github_signed("0.2.0", &url);
            assert!(discover("0.2.0-alpha.17", &key, |url, _| {
                Ok(if url == GITHUB_RELEASES {
                    list.clone()
                } else {
                    assert_eq!(url, format!("{GITHUB_DOWNLOAD}v0.2.0/latest.json"));
                    metadata.clone()
                })
            })
            .is_err());
        }
        let (mut metadata, key) =
            github_signed("0.2.0", &format!("{GITHUB_DOWNLOAD}v0.2.0/app.zip"));
        metadata[15] ^= 1;
        assert!(discover("0.2.0-alpha.17", &key, |url, _| {
            Ok(if url == GITHUB_RELEASES {
                list.clone()
            } else {
                assert_eq!(url, format!("{GITHUB_DOWNLOAD}v0.2.0/latest.json"));
                metadata.clone()
            })
        })
        .is_err());
    }
    #[test]
    fn blocks_user_data_and_traversal() {
        for p in [
            "../DazeDev.exe",
            "runtime/../../data/device.json",
            "data/device.json",
            "runtime/evil:ads",
            "runtime\\evil.dll",
            "/DazeDev.exe",
        ] {
            assert!(payload_path(p).is_err(), "{p}");
        }
    }
    fn archive() -> Vec<u8> {
        let mut w = zip::ZipWriter::new(Cursor::new(vec![]));
        for (name, bytes) in [
            ("DazeDev.exe", b"MZnew".as_slice()),
            ("runtime/test.dll", b"new-dll".as_slice()),
        ] {
            w.start_file(name, zip::write::SimpleFileOptions::default())
                .unwrap();
            w.write_all(bytes).unwrap();
        }
        w.finish().unwrap().into_inner()
    }
    #[test]
    fn rejects_corrupt_and_incomplete_downloads() {
        let bytes = archive();
        let release = Release {
            version: "1.0.0".into(),
            notes: "".into(),
            url: format!("{GITHUB_DOWNLOAD}v1.0.0/test.zip"),
            sha256: format!("{:x}", Sha256::digest(&bytes)),
            size: bytes.len() as u64,
        };
        assert!(verify_zip(&bytes, &release).is_ok());
        assert!(verify_zip(&bytes[..bytes.len() - 1], &release).is_err());
        let mut corrupt = bytes;
        corrupt[5] ^= 1;
        assert!(verify_zip(&corrupt, &release).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn locked_target_is_not_replaced() {
        use std::os::windows::fs::OpenOptionsExt;
        let root = std::env::temp_dir().join(format!("daze-lock-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        let locked = fs::OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(root.join("DazeDev.exe"))
            .unwrap();
        assert!(apply_archive(&root, &archive()).is_err());
        drop(locked);
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZold");
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn identical_runtime_file_is_not_rewritten_or_backed_up() {
        let root = std::env::temp_dir().join(format!("daze-identical-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("runtime")).unwrap();
        let dll = root.join("runtime/test.dll");
        fs::write(&dll, b"new-dll").unwrap();
        let modified = fs::metadata(&dll).unwrap().modified().unwrap();
        let backup = apply_archive(&root, &archive()).unwrap();
        assert_eq!(fs::metadata(&dll).unwrap().modified().unwrap(), modified);
        assert!(!backup.join("runtime/test.dll").exists());
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZnew");
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn unchanged_locked_runtime_does_not_block_update() {
        use std::os::windows::fs::OpenOptionsExt;
        let root = std::env::temp_dir().join(format!("daze-runtime-lock-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("runtime")).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        let dll = root.join("runtime/test.dll");
        fs::write(&dll, b"new-dll").unwrap();
        let locked = fs::OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&dll)
            .unwrap();
        let backup = apply_archive(&root, &archive()).unwrap();
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZnew");
        assert_eq!(fs::read(backup.join("DazeDev.exe")).unwrap(), b"MZold");
        assert!(!backup.join("runtime/test.dll").exists());
        drop(locked);
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn locked_changed_runtime_fails_before_replacing_the_app() {
        use std::os::windows::fs::OpenOptionsExt;
        let root =
            std::env::temp_dir().join(format!("daze-preflight-lock-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("runtime")).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        let dll = root.join("runtime/test.dll");
        fs::write(&dll, b"old-dll").unwrap();
        let locked = fs::OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&dll)
            .unwrap();
        let error = apply_archive(&root, &archive()).unwrap_err();
        assert!(
            error.contains("runtime") && error.contains("test.dll"),
            "{error}"
        );
        assert!(error.contains("No files were changed"), "{error}");
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZold");
        assert_eq!(fs::read(&dll).unwrap(), b"old-dll");
        assert!(!root.join(".updates").exists());
        drop(locked);
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(windows)]
    #[test]
    fn transient_runtime_lock_is_retried() {
        use std::os::windows::fs::OpenOptionsExt;
        let root =
            std::env::temp_dir().join(format!("daze-transient-lock-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("runtime")).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        let dll = root.join("runtime/test.dll");
        fs::write(&dll, b"old-dll").unwrap();
        let locked = fs::OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&dll)
            .unwrap();
        let unlock = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(250));
            drop(locked);
        });
        let backup = apply_archive(&root, &archive()).unwrap();
        unlock.join().unwrap();
        assert_eq!(fs::read(&dll).unwrap(), b"new-dll");
        assert_eq!(
            fs::read(backup.join("runtime/test.dll")).unwrap(),
            b"old-dll"
        );
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn failed_replacement_restores_already_written_files() {
        let root = std::env::temp_dir().join(format!("daze-rollback-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        fs::write(root.join("runtime"), b"blocking file").unwrap();
        assert!(apply_archive(&root, &archive()).is_err());
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZold");
        assert_eq!(fs::read(root.join("runtime")).unwrap(), b"blocking file");
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn replacement_leaves_no_staged_file_and_keeps_old_file_on_failure() {
        let root = std::env::temp_dir().join(format!("daze-swap-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let target = root.join("DazeDev.exe");
        fs::write(&target, b"MZold").unwrap();
        replace_file(&mut b"MZnew".as_slice(), &target).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"MZnew");
        assert!(!root.join("DazeDev.exe.new").exists());
        struct Broken;
        impl Read for Broken {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::Error::other("connection lost"))
            }
        }
        assert!(replace_file(&mut Broken, &target).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"MZnew");
        assert!(!root.join("DazeDev.exe.new").exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn old_update_folders_are_pruned_to_the_newest() {
        let root = std::env::temp_dir().join(format!("daze-prune-{}", uuid::Uuid::new_v4()));
        let updates = root.join(".updates");
        for n in 0..4 {
            for kind in ["staged", "backup"] {
                let dir = updates.join(format!("{kind}-{n}"));
                fs::create_dir_all(&dir).unwrap();
                fs::write(dir.join("file"), b"x").unwrap();
                // Folder times order the pruning; keep them distinct.
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        fs::write(updates.join("other"), b"kept").unwrap();
        prune_updates(&root, 1, 2);
        let mut left: Vec<_> = fs::read_dir(&updates)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();
        assert_eq!(left, ["backup-2", "backup-3", "other", "staged-3"]);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn progress_is_throttled_by_time_and_share() {
        let start = std::time::Instant::now();
        let mut throttle = Throttle { last: None };
        assert!(throttle.ready(65536, 100_000_000, start));
        assert!(!throttle.ready(131072, 100_000_000, start));
        assert!(throttle.ready(1_100_000, 100_000_000, start));
        assert!(!throttle.ready(1_200_000, 100_000_000, start));
        assert!(throttle.ready(1_300_000, 100_000_000, start + Duration::from_millis(100)));
        assert!(throttle.ready(100_000_000, 100_000_000, start + Duration::from_millis(101)));
    }
    #[test]
    fn portable_replacement_preserves_data_and_backup() {
        let root = std::env::temp_dir().join(format!("daze-update-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("data/models")).unwrap();
        fs::write(root.join("DazeDev.exe"), b"MZold").unwrap();
        fs::write(root.join("data/device.json"), b"settings").unwrap();
        fs::write(root.join("data/models/model"), b"model").unwrap();
        let backup = apply_archive(&root, &archive()).unwrap();
        assert_eq!(fs::read(root.join("DazeDev.exe")).unwrap(), b"MZnew");
        assert_eq!(fs::read(backup.join("DazeDev.exe")).unwrap(), b"MZold");
        assert_eq!(
            fs::read(root.join("data/device.json")).unwrap(),
            b"settings"
        );
        assert_eq!(fs::read(root.join("data/models/model")).unwrap(), b"model");
        fs::remove_dir_all(root).unwrap();
    }
}

pub fn report_error(message: &str) {
    #[cfg(windows)]
    unsafe {
        let text: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
        let title: Vec<u16> = "DazeDev".encode_utf16().chain(Some(0)).collect();
        windows_sys::Win32::UI::WindowsAndMessaging::MessageBoxW(
            std::ptr::null_mut(),
            text.as_ptr(),
            title.as_ptr(),
            windows_sys::Win32::UI::WindowsAndMessaging::MB_OK
                | windows_sys::Win32::UI::WindowsAndMessaging::MB_ICONERROR,
        );
    }
    #[cfg(not(windows))]
    eprintln!("DazeDev: {message}");
}
