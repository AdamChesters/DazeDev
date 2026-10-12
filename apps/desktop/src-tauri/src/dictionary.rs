//! Separate local dictation dictionary, with an atomic primary and last-good backup.
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{fs, io::Write, path::Path};
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 80
        && !["__proto__", "constructor", "prototype"].contains(&value)
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}
fn phrase(value: &Value) -> bool {
    value.as_str().is_some_and(|s| {
        !s.trim().is_empty()
            && s.chars().count() <= 160
            && !s.chars().any(|c| c <= '\u{1f}' || c == '\u{7f}')
    })
}
fn valid(value: &Value) -> bool {
    if value["schema"] != 1
        || !value["deviceId"].as_str().is_some_and(identifier)
        || value["dictionary"]["schema"] != 1
        || !value["syncHost"].is_string()
        || !value["adopted"].is_boolean()
    {
        return false;
    }
    let Some(entries) = value["dictionary"]["entries"].as_object() else {
        return false;
    };
    entries.len() <= 2000
        && entries.iter().all(|(id, versions)| {
            identifier(id)
                && versions.as_array().is_some_and(|versions| {
                    !versions.is_empty()
                        && versions.len() <= 30
                        && versions.iter().all(|v| {
                            v["deleted"].is_boolean()
                                && (v.get("disabled").is_none() || v["disabled"].is_boolean())
                                && phrase(&v["preferred"])
                                && v["aliases"].as_array().is_some_and(|a| {
                                    !a.is_empty() && a.len() <= 30 && a.iter().all(phrase)
                                })
                                && v["clock"].as_object().is_some_and(|c| {
                                    !c.is_empty()
                                        && c.len() <= 100
                                        && c.iter().all(|(k, n)| {
                                            identifier(k)
                                                && n.as_u64().is_some_and(|n| {
                                                    n > 0 && n <= 9_007_199_254_740_991
                                                })
                                        })
                                })
                        })
                })
        })
}
fn regular(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(m) if !m.file_type().is_file() => {
            Err("Dictionary paths must be regular files, not symlinks".into())
        }
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}
fn read(path: &Path) -> Result<Value, String> {
    regular(path)?;
    let meta = fs::metadata(path).map_err(|e| e.to_string())?;
    if meta.len() > 2_000_000 {
        return Err("Dictionary file is too large".into());
    }
    let v: Value = serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if !valid(&v) {
        return Err("Invalid dictionary file".into());
    }
    Ok(v)
}
pub fn load(data: &Path) -> Result<Value, String> {
    let path = data.join("dictionary.json");
    regular(&path)?;
    regular(&data.join("dictionary.json.bak"))?;
    if !path.exists() {
        return Ok(Value::Null);
    }
    match read(&path) {
        Ok(v) => Ok(v),
        Err(_) => {
            fs::copy(
                &path,
                data.join(format!("dictionary.corrupt-{}.json", uuid::Uuid::new_v4())),
            )
            .map_err(|e| e.to_string())?;
            let mut v = read(&data.join("dictionary.json.bak")).map_err(|_| {
                "Dictionary and backup could not be read. Both files were preserved.".to_string()
            })?;
            v["recoveryNotice"] =
                "Dictionary recovered from backup. Damaged file preserved.".into();
            Ok(v)
        }
    }
}
pub fn save(data: &Path, value: &Value) -> Result<(), String> {
    if !valid(value) {
        return Err("Invalid dictionary".into());
    }
    let bytes = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    if bytes.len() > 2_000_000 {
        return Err("Dictionary is too large".into());
    }
    crate::private_storage::directory(data).map_err(|e| e.to_string())?;
    let path = data.join("dictionary.json");
    regular(&path)?;
    regular(&data.join("dictionary.json.bak"))?;
    let temp = data.join(format!("dictionary.{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let mut f = crate::private_storage::create(&temp)?;
        f.write_all(&bytes)?;
        f.sync_all()?;
        drop(f);
        if read(&path).is_ok() {
            let backup = data.join(format!("dictionary-backup.{}.tmp", uuid::Uuid::new_v4()));
            fs::copy(&path, &backup)?;
            crate::private_storage::protect(&backup)?;
            fs::rename(backup, data.join("dictionary.json.bak"))?;
        }
        fs::rename(&temp, &path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temp);
    }
    result.map_err(|e| e.to_string())
}
// Kept outside portable data: restored/copied snapshots receive a new writer identity.
fn fingerprint(value: &Value) -> String {
    format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(value).unwrap_or_default())
    )
}
fn identity_path(root: &Path) -> std::path::PathBuf {
    root.join("dictionary-identities.json")
}
fn identities(root: &Path) -> Result<Value, String> {
    let path = identity_path(root);
    regular(&path)?;
    if !path.exists() {
        return Ok(serde_json::json!({}));
    }
    let bytes = fs::read(&path).map_err(|e| e.to_string())?;
    if bytes.len() > 2_000_000 {
        return Err("Dictionary identity metadata is too large".into());
    }
    match serde_json::from_slice::<Value>(&bytes) {
        Ok(v) if v.is_object() => Ok(v),
        _ => {
            fs::rename(
                &path,
                root.join(format!(
                    "dictionary-identities.corrupt-{}.json",
                    uuid::Uuid::new_v4()
                )),
            )
            .map_err(|e| e.to_string())?;
            Ok(serde_json::json!({}))
        }
    }
}
fn remember(data: &Path, root: &Path, value: &Value) -> Result<(), String> {
    let mut all = identities(root)?;
    all[data.to_string_lossy().as_ref()] = fingerprint(value).into();
    crate::private_storage::directory(root).map_err(|e| e.to_string())?;
    let path = identity_path(root);
    let staged = root.join(format!(
        "dictionary-identities.{}.tmp",
        uuid::Uuid::new_v4()
    ));
    let result = (|| -> std::io::Result<()> {
        let mut f = crate::private_storage::create(&staged)?;
        f.write_all(&serde_json::to_vec(&all)?)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&staged, &path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result.map_err(|e| e.to_string())
}
pub fn save_for_device(data: &Path, root: &Path, value: &Value) -> Result<(), String> {
    if !valid(value) {
        return Err("Invalid dictionary".into());
    }
    regular(&data.join("dictionary.json"))?;
    regular(&data.join("dictionary.json.bak"))?;
    // If the subsequent save fails or a crash intervenes, next load rotates safely.
    remember(data, root, value)?;
    save(data, value)
}
pub fn load_for_device(data: &Path, root: &Path) -> Result<Value, String> {
    let mut value = load(data)?;
    if value.is_null() {
        return Ok(value);
    }
    let all = identities(root)?;
    if all[data.to_string_lossy().as_ref()].as_str() != Some(&fingerprint(&value)) {
        value["deviceId"] = uuid::Uuid::new_v4().to_string().into();
        value["adopted"] = false.into();
        save_for_device(data, root, &value)?;
    }
    Ok(value)
}
pub fn vocabulary(value: &Value) -> Option<String> {
    let entries = value["dictionary"]["entries"].as_object()?;
    let mut words = Vec::new();
    let mut bytes = 0;
    for versions in entries.values() {
        let Some(v) = versions.as_array().filter(|v| v.len() == 1).map(|v| &v[0]) else {
            continue;
        };
        if v["deleted"] == true || v["disabled"] == true {
            continue;
        }
        let Some(term) = v["preferred"].as_str() else {
            continue;
        };
        if bytes + term.len() + 2 > 512 || words.len() >= 24 {
            continue;
        }
        if !words.contains(&term) {
            words.push(term);
            bytes += term.len() + 2;
        }
    }
    if words.is_empty() {
        None
    } else {
        Some(words.join(", "))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identity_is_stable_but_changes_for_restore_and_copy() {
        let base = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let data = base.join("data");
        let identity = base.join("identity");
        let old = serde_json::json!({"schema":1,"deviceId":"a","dictionary":{"schema":1,"entries":{}},"syncHost":"","adopted":false});
        save_for_device(&data, &identity, &old).unwrap();
        for _ in 0..110 {
            assert_eq!(load_for_device(&data, &identity).unwrap()["deviceId"], "a");
        }
        let mut later = old.clone();
        later["syncHost"] = "host".into();
        later["adopted"] = true.into();
        save_for_device(&data, &identity, &later).unwrap();
        save(&data, &old).unwrap();
        let recovered = load_for_device(&data, &identity).unwrap();
        assert_ne!(recovered["deviceId"], "a");
        assert_eq!(
            load_for_device(&data, &identity).unwrap()["deviceId"],
            recovered["deviceId"]
        );
        let mut adopted = recovered.clone();
        adopted["adopted"] = true.into();
        save_for_device(&data, &identity, &adopted).unwrap();
        let copied = load_for_device(&data, &base.join("other-machine")).unwrap();
        assert_ne!(copied["deviceId"], recovered["deviceId"]);
        assert_eq!(copied["adopted"], false);
        fs::write(identity_path(&identity), "broken").unwrap();
        assert_ne!(
            load_for_device(&data, &identity).unwrap()["deviceId"],
            copied["deviceId"]
        );
        fs::remove_dir_all(base).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn refuses_backup_symlink() {
        let base = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        fs::create_dir_all(&base).unwrap();
        let target = base.join("unrelated");
        fs::write(&target, "preserved").unwrap();
        std::os::unix::fs::symlink(&target, base.join("dictionary.json.bak")).unwrap();
        let v = serde_json::json!({"schema":1,"deviceId":"a","dictionary":{"schema":1,"entries":{}},"syncHost":"","adopted":false});
        assert!(save(&base, &v).is_err());
        assert_eq!(fs::read_to_string(target).unwrap(), "preserved");
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn preserves_last_good_backup() {
        let d = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let mut v = serde_json::json!({"schema":1,"deviceId":"a","dictionary":{"schema":1,"entries":{}},"syncHost":"","adopted":false});
        save(&d, &v).unwrap();
        v["syncHost"] = "host".into();
        save(&d, &v).unwrap();
        fs::write(d.join("dictionary.json"), "broken").unwrap();
        assert_eq!(load(&d).unwrap()["syncHost"], "");
        assert_eq!(
            read(&d.join("dictionary.json.bak")).unwrap()["syncHost"],
            ""
        );
        fs::remove_dir_all(d).unwrap();
    }
}
