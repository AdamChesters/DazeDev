//! Portable client storage and verified migration from the installer releases.
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
};

fn digest(path: &Path) -> std::io::Result<Vec<u8>> {
    let mut f = fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(hash.finalize().to_vec())
}
fn move_verified(
    source: &Path,
    target: &Path,
    recovery: &Path,
    moved: &mut usize,
) -> std::io::Result<()> {
    let meta = fs::symlink_metadata(source)?;
    if meta.file_type().is_symlink() {
        return Ok(());
    }
    if meta.is_dir() {
        fs::create_dir_all(target)?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            move_verified(
                &entry.path(),
                &target.join(entry.file_name()),
                &recovery.join(entry.file_name()),
                moved,
            )?;
        }
        let _ = fs::remove_dir(source);
        return Ok(());
    }
    if !meta.is_file() {
        return Ok(());
    }
    fs::create_dir_all(target.parent().unwrap())?;
    if target.exists() {
        if target.is_file() && digest(source)? == digest(target)? {
            fs::remove_file(source)?;
            *moved += 1;
            return Ok(());
        }
        let mut destination = recovery.to_path_buf();
        if destination.exists() {
            destination.set_file_name(format!(
                "{}-{}",
                destination.file_name().unwrap().to_string_lossy(),
                uuid::Uuid::new_v4()
            ));
        }
        fs::create_dir_all(destination.parent().unwrap())?;
        return move_verified(
            source,
            &destination,
            &destination.with_extension("conflict"),
            moved,
        );
    }
    // Rename is an atomic move on the same volume. Across volumes, copy and
    // verify before deleting anything from the original location.
    if fs::rename(source, target).is_err() {
        let tmp = target.with_extension(format!("migrating-{}", uuid::Uuid::new_v4()));
        let result = (|| {
            fs::copy(source, &tmp)?;
            fs::OpenOptions::new().write(true).open(&tmp)?.sync_all()?;
            if digest(source)? != digest(&tmp)? {
                return Err(std::io::Error::other("Migration checksum mismatch"));
            }
            fs::rename(&tmp, target)?;
            fs::remove_file(source)
        })();
        if result.is_err() {
            let _ = fs::remove_file(tmp);
        }
        result?;
    }
    *moved += 1;
    Ok(())
}
const PIN_FILES: [&str; 3] = [
    "startup-pin.marker",
    "startup-pin-rate.json",
    "startup-pin.json",
];
fn preflight_pin_migration(data: &Path, sources: &[PathBuf]) -> Result<(), String> {
    let mut expected: Option<Vec<Vec<u8>>> = None;
    for directory in std::iter::once(data).chain(sources.iter().map(|p| p.as_path())) {
        let mut files = Vec::new();
        for name in PIN_FILES {
            match fs::symlink_metadata(directory.join(name)) {
                Ok(m) if m.is_file() && !m.file_type().is_symlink() && m.len() <= 16_384 => files
                    .push(Some(fs::read(directory.join(name)).map_err(|_| {
                        "Startup PIN migration could not be verified."
                    })?)),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => files.push(None),
                _ => return Err(
                    "Startup PIN migration could not be verified. Protected data was not moved."
                        .into(),
                ),
            }
        }
        if files.iter().all(Option::is_none) {
            continue;
        }
        if files.iter().any(Option::is_none)
            || crate::pin::PinGate::open(directory.to_path_buf())
                .status()
                .unavailable
        {
            return Err("Startup PIN migration could not be verified. Restore the complete protected data before restarting.".into());
        }
        let complete: Vec<Vec<u8>> = files.into_iter().flatten().collect();
        if expected.as_ref().is_some_and(|old| old != &complete) {
            return Err(
                "Different protected application folders cannot be merged automatically.".into(),
            );
        }
        expected = Some(complete);
    }
    Ok(())
}
pub fn migrate(data: &Path, sources: &[PathBuf]) -> Result<usize, String> {
    preflight_pin_migration(data, sources)?;
    let mut moved = 0;
    for (i, source) in sources.iter().enumerate() {
        if !source.is_dir() || source == data {
            continue;
        }
        let recovery = data.join("migration-recovery").join(format!("source-{i}"));
        for name in [
            "startup-pin.marker",
            "startup-pin-rate.json",
            "startup-pin.json",
            "device.json",
            "device.json.bak",
            "models",
        ] {
            let path = source.join(name);
            if path.exists() {
                move_verified(&path, &data.join(name), &recovery.join(name), &mut moved).map_err(
                    |e| {
                        format!(
                            "Could not migrate {}: {e}. Originals not yet moved are preserved.",
                            path.display()
                        )
                    },
                )?;
            }
        }
        let _ = fs::remove_dir(source);
    }
    Ok(moved)
}
pub fn prepare(root: &Path, old_app_data: &Path) -> Result<(PathBuf, fs::File), String> {
    let data = std::env::var_os("DAZEDEV_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| root.join("data"));
    fs::create_dir_all(&data)
        .map_err(|e| format!("DazeDev needs a writable portable folder: {e}"))?;
    crate::private_storage::tree(&data).map_err(|e| format!("This filesystem cannot protect DazeDev data: {e}. Existing settings were preserved. Move the app and data to a private user folder, or set DAZEDEV_DATA_DIR to a private storage folder."))?;
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(data.join("instance.lock"))
        .map_err(|e| e.to_string())?;
    lock.try_lock().map_err(|_| {
        "DazeDev is already running from this folder. Close it before opening another copy."
            .to_string()
    })?;
    let mut sources = vec![old_app_data.to_path_buf()];
    for parent in [dirs::data_dir(), dirs::data_local_dir(), dirs::config_dir()]
        .into_iter()
        .flatten()
    {
        for name in ["com.adamchesters.dazeddev", "com.adamchesters.dazedev"] {
            sources.push(parent.join(name));
        }
    }
    // Prior user-selected 0.1/0.2 folders, next to this version or under the
    // old/new product folder. Never scan arbitrary drives or other apps.
    let mut parents = vec![root.to_path_buf()];
    if let Some(parent) = root.parent() {
        parents.push(parent.to_path_buf());
        if let Some(grand) = parent.parent() {
            parents.push(grand.join("dazeddev"));
            parents.push(grand.join("dazedev"));
        }
    }
    for parent in parents {
        if let Ok(entries) = fs::read_dir(parent) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if entry.path().is_dir()
                    && (["0.1", "0.2"].iter().any(|v| {
                        name == *v
                            || name.starts_with(&format!("{v}."))
                            || name.starts_with(&format!("{v}-"))
                    }))
                    && entry.path() != root
                {
                    sources.push(entry.path().join("data"));
                    sources.push(entry.path());
                }
            }
        }
    }
    let mut seen = std::collections::HashSet::new();
    sources.retain(|p| seen.insert(p.clone()));
    let moved = migrate(&data, &sources)?;
    crate::private_storage::tree(&data).map_err(|e| e.to_string())?;
    if moved > 0 {
        let mut f = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(data.join("migration.log"))
            .map_err(|e| e.to_string())?;
        writeln!(f, "Moved {moved} legacy settings/model files. Conflicting portable settings were retained; old conflicting files are in migration-recovery.").map_err(|e| e.to_string())?;
    }
    Ok((data, lock))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn migration_moves_models_and_preserves_conflicting_settings() {
        let temp = std::env::temp_dir().join(format!("daze-migrate-{}", uuid::Uuid::new_v4()));
        let old = temp.join("old");
        let new = temp.join("portable/data");
        fs::create_dir_all(old.join("models")).unwrap();
        fs::create_dir_all(&new).unwrap();
        fs::write(old.join("device.json"), b"old").unwrap();
        fs::write(new.join("device.json"), b"current").unwrap();
        fs::write(old.join("models/model.bin"), b"model").unwrap();
        assert_eq!(migrate(&new, &[old.clone()]).unwrap(), 2);
        assert_eq!(fs::read(new.join("device.json")).unwrap(), b"current");
        assert_eq!(
            fs::read(new.join("migration-recovery/source-0/device.json")).unwrap(),
            b"old"
        );
        assert_eq!(fs::read(new.join("models/model.bin")).unwrap(), b"model");
        assert!(!old.exists());
        assert_eq!(migrate(&new, &[old]).unwrap(), 0);
        fs::remove_dir_all(temp).unwrap();
    }
}

/// A broken primary must never overwrite the last usable settings copy.
pub fn load_device(data: &Path) -> Result<serde_json::Value, String> {
    let primary = data.join("device.json");
    if !primary.exists() {
        return Ok(serde_json::Value::Null);
    }
    match read_device(&primary) {
        Ok(value) => Ok(value),
        Err(_) => {
            let quarantine = data.join(format!("device.corrupt-{}.json", uuid::Uuid::new_v4()));
            fs::copy(&primary, quarantine)
                .map_err(|e| format!("Cannot preserve damaged settings: {e}"))?;
            match read_device(&data.join("device.json.bak")) {
                Ok(mut value) => {
                    value["recoveryNotice"] = "Settings recovered from backup. The damaged file was preserved.".into();
                    Ok(value)
                }
                Err(_) => Err("Settings could not be read. Original and backup files were preserved; the app has opened with defaults.".into()),
            }
        }
    }
}
fn read_device(path: &Path) -> Result<serde_json::Value, String> {
    let v: serde_json::Value = serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if !v.is_object()
        || !v.get("hosts").is_some_and(|h| h.is_array())
        || !v.get("voice").is_some_and(|h| h.is_object())
    {
        return Err("Invalid settings structure".into());
    }
    if v["hosts"]
        .as_array()
        .unwrap()
        .iter()
        .any(|h| !h["id"].is_string() || !h["kind"].is_string())
    {
        return Err("Invalid host settings".into());
    }
    Ok(v)
}
pub fn save_device(data: &Path, value: &serde_json::Value) -> Result<(), String> {
    crate::private_storage::directory(data).map_err(|e| e.to_string())?;
    let primary = data.join("device.json");
    let staged = data.join(format!("device.{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let mut f = crate::private_storage::create(&staged)?;
        f.write_all(&serde_json::to_vec_pretty(value)?)?;
        f.sync_all()?;
        drop(f);
        if primary.exists() && read_device(&primary).is_ok() {
            fs::copy(&primary, data.join("device.json.bak"))?;
            crate::private_storage::protect(&data.join("device.json.bak"))?;
        }
        fs::rename(&staged, &primary)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result.map_err(|e| e.to_string())
}
#[cfg(test)]
mod device_recovery_tests {
    use super::*;
    #[test]
    fn corrupt_primary_recovers_without_destroying_evidence_or_backup() {
        let d = std::env::temp_dir().join(format!("dazedev-settings-{}", uuid::Uuid::new_v4()));
        let v = serde_json::json!({"hosts":[],"voice":{},"theme":"blue"});
        save_device(&d, &v).unwrap();
        save_device(&d, &v).unwrap();
        fs::write(d.join("device.json"), b"{truncated").unwrap();
        assert_eq!(load_device(&d).unwrap()["theme"], "blue");
        save_device(&d, &v).unwrap();
        assert_eq!(read_device(&d.join("device.json.bak")).unwrap(), v);
        assert!(fs::read_dir(&d).unwrap().flatten().any(|e| e
            .file_name()
            .to_string_lossy()
            .starts_with("device.corrupt-")));
        fs::remove_dir_all(d).unwrap();
    }
    #[test]
    fn invalid_schema_is_preserved_and_reported() {
        let d = std::env::temp_dir().join(format!("dazedev-settings-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&d).unwrap();
        fs::write(d.join("device.json"), b"[]").unwrap();
        assert!(load_device(&d).is_err());
        assert_eq!(fs::read(d.join("device.json")).unwrap(), b"[]");
        fs::remove_dir_all(d).unwrap();
    }
}
#[cfg(test)]
mod pin_migration_tests {
    use super::*;
    fn base() -> PathBuf {
        std::env::temp_dir().join(format!("dazedev-pin-migration-{}", uuid::Uuid::new_v4()))
    }
    fn disabled(directory: &Path) {
        fs::create_dir_all(directory).unwrap();
        fs::write(directory.join("startup-pin.marker"), r#"{"schema":1}"#).unwrap();
        fs::write(
            directory.join("startup-pin-rate.json"),
            r#"{"failures":0,"retry_at":0}"#,
        )
        .unwrap();
        fs::write(
            directory.join("startup-pin.json"),
            r#"{"schema":1,"enabled":false,"verifier":null}"#,
        )
        .unwrap();
        fs::write(directory.join("device.json"), "{}").unwrap();
    }
    #[test]
    fn partial_source_never_moves_unprotected_device_data() {
        let root = base();
        let source = root.join("old");
        let target = root.join("new");
        disabled(&source);
        fs::remove_file(source.join("startup-pin.json")).unwrap();
        assert!(migrate(&target, &[source.clone()]).is_err());
        assert!(source.join("device.json").exists());
        assert!(!target.join("device.json").exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn complete_pin_settings_migrate_together_and_differing_sources_are_rejected() {
        let root = base();
        let a = root.join("a");
        let b = root.join("b");
        let target = root.join("new");
        disabled(&a);
        disabled(&b);
        fs::write(
            b.join("startup-pin-rate.json"),
            r#"{"failures":1,"retry_at":10}"#,
        )
        .unwrap();
        assert!(migrate(&target, &[a.clone(), b]).is_err());
        assert!(a.join("device.json").exists());
        migrate(&target, &[a]).unwrap();
        for name in PIN_FILES {
            assert!(target.join(name).is_file());
        }
        assert!(!crate::pin::PinGate::open(target).status().locked);
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn dangling_pin_symlink_does_not_disappear_during_migration() {
        let root = base();
        let source = root.join("old");
        let target = root.join("new");
        fs::create_dir_all(&source).unwrap();
        fs::write(source.join("device.json"), "{}").unwrap();
        std::os::unix::fs::symlink(source.join("missing"), source.join("startup-pin.marker"))
            .unwrap();
        assert!(migrate(&target, &[source.clone()]).is_err());
        assert!(source.join("device.json").exists());
        assert!(!target.join("device.json").exists());
        fs::remove_dir_all(root).unwrap();
    }
}
