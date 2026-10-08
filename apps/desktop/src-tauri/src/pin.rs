//! Optional startup gate. This does not encrypt data or defend against an OS user editing files.
use ring::{
    pbkdf2,
    rand::{SecureRandom, SystemRandom},
};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Write,
    num::NonZeroU32,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    time::{SystemTime, UNIX_EPOCH},
};
const ITERATIONS: u32 = 600_000;
const FAILURE: &str = "PIN could not be verified. Wait and try again.";
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Verifier {
    iterations: u32,
    salt: [u8; 16],
    hash: [u8; 32],
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Record {
    schema: u8,
    enabled: bool,
    verifier: Option<Verifier>,
}
#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Rate {
    failures: u32,
    retry_at: u64,
}
struct Inner {
    record: Option<Record>,
    rate: Rate,
    unavailable: bool,
}
pub struct PinGate {
    data: PathBuf,
    unlocked: AtomicBool,
    inner: Mutex<Inner>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub enabled: bool,
    pub locked: bool,
    pub unavailable: bool,
    pub retry_after: u64,
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
fn regular(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(m) if m.is_file() && !m.file_type().is_symlink() && m.len() <= 16_384 => Ok(true),
        Ok(_) => Err(FAILURE.into()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(_) => Err(FAILURE.into()),
    }
}
fn read<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T, String> {
    if !regular(path)? {
        return Err(FAILURE.into());
    }
    serde_json::from_slice(&fs::read(path).map_err(|_| FAILURE)?).map_err(|_| FAILURE.into())
}
fn write<T: Serialize>(data: &Path, name: &str, value: &T) -> Result<(), String> {
    fs::create_dir_all(data).map_err(|_| FAILURE)?;
    let path = data.join(name);
    regular(&path)?;
    let staged = data.join(format!(".pin-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> Result<(), String> {
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&staged).map_err(|_| FAILURE)?;
        file.write_all(&serde_json::to_vec(value).map_err(|_| FAILURE)?)
            .map_err(|_| FAILURE)?;
        file.sync_all().map_err(|_| FAILURE)?;
        drop(file);
        fs::rename(&staged, &path).map_err(|_| FAILURE)?;
        #[cfg(unix)]
        {
            fs::File::open(data)
                .and_then(|f| f.sync_all())
                .map_err(|_| FAILURE)?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result
}
fn valid_pin(pin: &str) -> bool {
    (6..=12).contains(&pin.len()) && pin.bytes().all(|b| b.is_ascii_digit())
}
impl PinGate {
    pub fn open(data: PathBuf) -> Self {
        let loaded = (|| -> Result<Inner, String> {
            let primary = regular(&data.join("startup-pin.json"))?;
            let marker = regular(&data.join("startup-pin.marker"))?;
            let has_rate = regular(&data.join("startup-pin-rate.json"))?;
            if !primary && !marker && !has_rate {
                return Ok(Inner {
                    record: None,
                    rate: Rate::default(),
                    unavailable: false,
                });
            }
            if !primary || !marker || !has_rate {
                return Err(FAILURE.into());
            }
            let value: serde_json::Value = read(&data.join("startup-pin.json"))?;
            if value.get("verifier").is_none() {
                return Err(FAILURE.into());
            }
            let record: Record = serde_json::from_value(value).map_err(|_| FAILURE)?;
            let marker: serde_json::Value = read(&data.join("startup-pin.marker"))?;
            let rate: Rate = read(&data.join("startup-pin-rate.json"))?;
            if record.schema != 1
                || record.enabled != record.verifier.is_some()
                || marker != serde_json::json!({"schema":1})
                || record
                    .verifier
                    .as_ref()
                    .is_some_and(|v| v.iterations != ITERATIONS)
            {
                return Err(FAILURE.into());
            }
            Ok(Inner {
                record: Some(record),
                rate,
                unavailable: false,
            })
        })();
        let inner = loaded.unwrap_or(Inner {
            record: None,
            rate: Rate::default(),
            unavailable: true,
        });
        let unlocked =
            !inner.unavailable && inner.record.as_ref().is_none_or(|r| r.verifier.is_none());
        Self {
            data,
            unlocked: AtomicBool::new(unlocked),
            inner: Mutex::new(inner),
        }
    }
    pub fn permits(&self, command: &str) -> bool {
        matches!(command, "pin_status" | "pin_unlock") || self.unlocked.load(Ordering::Acquire)
    }
    pub fn status(&self) -> Status {
        self.status_at(now())
    }
    fn status_at(&self, time: u64) -> Status {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        Status {
            enabled: inner.unavailable
                || inner.record.as_ref().is_some_and(|r| r.verifier.is_some()),
            locked: !self.unlocked.load(Ordering::Acquire),
            unavailable: inner.unavailable,
            retry_after: inner.rate.retry_at.saturating_sub(time),
        }
    }
    fn verify(&self, inner: &mut Inner, pin: &str, time: u64) -> Result<(), String> {
        if inner.unavailable || time < inner.rate.retry_at {
            return Err(FAILURE.into());
        }
        let verifier = inner
            .record
            .as_ref()
            .and_then(|r| r.verifier.as_ref())
            .ok_or(FAILURE)?;
        // Persist the failed-attempt penalty before checking, so restarting cannot avoid it.
        inner.rate.failures = inner.rate.failures.saturating_add(1);
        inner.rate.retry_at = time.saturating_add(2u64.pow(inner.rate.failures.min(6)).min(60));
        write(&self.data, "startup-pin-rate.json", &inner.rate)?;
        if !valid_pin(pin)
            || pbkdf2::verify(
                pbkdf2::PBKDF2_HMAC_SHA256,
                NonZeroU32::new(verifier.iterations).unwrap(),
                &verifier.salt,
                pin.as_bytes(),
                &verifier.hash,
            )
            .is_err()
        {
            return Err(FAILURE.into());
        }
        inner.rate = Rate::default();
        write(&self.data, "startup-pin-rate.json", &inner.rate)
    }
    pub fn unlock(&self, pin: &str) -> Result<(), String> {
        self.unlock_at(pin, now())
    }
    fn unlock_at(&self, pin: &str, time: u64) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|_| FAILURE)?;
        self.verify(&mut inner, pin, time)?;
        self.unlocked.store(true, Ordering::Release);
        Ok(())
    }
    pub fn configure(&self, current: &str, new_pin: Option<&str>) -> Result<(), String> {
        self.configure_at(current, new_pin, now())
    }
    fn configure_at(&self, current: &str, new_pin: Option<&str>, time: u64) -> Result<(), String> {
        if !self.unlocked.load(Ordering::Acquire) {
            return Err(FAILURE.into());
        }
        if new_pin.is_some_and(|pin| !valid_pin(pin)) {
            return Err("Use a PIN of 6–12 digits.".into());
        }
        let mut inner = self.inner.lock().map_err(|_| FAILURE)?;
        if inner.unavailable {
            return Err(FAILURE.into());
        }
        if inner.record.as_ref().is_some_and(|r| r.verifier.is_some()) {
            self.verify(&mut inner, current, time)?;
        }
        let verifier = if let Some(pin) = new_pin {
            let mut salt = [0u8; 16];
            SystemRandom::new().fill(&mut salt).map_err(|_| FAILURE)?;
            let mut hash = [0u8; 32];
            pbkdf2::derive(
                pbkdf2::PBKDF2_HMAC_SHA256,
                NonZeroU32::new(ITERATIONS).unwrap(),
                &salt,
                pin.as_bytes(),
                &mut hash,
            );
            Some(Verifier {
                iterations: ITERATIONS,
                salt,
                hash,
            })
        } else {
            None
        };
        let record = Record {
            schema: 1,
            enabled: verifier.is_some(),
            verifier,
        };
        // Leave a configured marker even when disabled; missing/corrupt records never reset silently.
        write(
            &self.data,
            "startup-pin.marker",
            &serde_json::json!({"schema":1}),
        )?;
        write(&self.data, "startup-pin-rate.json", &Rate::default())?;
        write(&self.data, "startup-pin.json", &record)?;
        inner.record = Some(record);
        inner.rate = Rate::default();
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!("dazedev-pin-test-{}", uuid::Uuid::new_v4())))
        }
        fn gate(&self) -> PinGate {
            PinGate::open(self.0.clone())
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn disabled_by_default_then_locked_on_restart_and_no_plaintext() {
        let fixture = Fixture::new();
        let gate = fixture.gate();
        assert!(!gate.status().enabled);
        assert!(gate.permits("host_connect"));
        gate.configure_at("", Some("684291"), 100).unwrap();
        assert!(gate.permits("host_connect")); // No mid-session auto lock.
        let bytes = fs::read_to_string(fixture.0.join("startup-pin.json")).unwrap();
        assert!(!bytes.contains("684291"));
        let saved: Record = serde_json::from_str(&bytes).unwrap();
        assert_eq!(saved.verifier.unwrap().iterations, 600_000);
        let gate = fixture.gate();
        assert!(gate.status().locked);
        for command in [
            "device_load",
            "device_save",
            "host_connect",
            "host_rpc",
            "terminal_open",
            "terminal_input",
            "dictionary_load",
            "update_install",
            "voice_start",
            "open_external",
            "pin_configure",
            "future_command",
        ] {
            assert!(!gate.permits(command), "{command}");
        }
        assert!(gate.permits("pin_status"));
        assert!(gate.permits("pin_unlock"));
        gate.unlock_at("684291", 100).unwrap();
        assert!(gate.permits("terminal_open"));
    }
    #[test]
    fn rate_limit_survives_restart_and_verification_is_required_for_change_disable() {
        let fixture = Fixture::new();
        let gate = fixture.gate();
        gate.configure_at("", Some("684291"), 100).unwrap();
        let gate = fixture.gate();
        assert_eq!(gate.unlock_at("111111", 100).unwrap_err(), FAILURE);
        let gate = fixture.gate();
        assert_eq!(gate.status_at(100).retry_after, 2);
        assert!(gate.unlock_at("684291", 101).is_err());
        assert!(gate.status().locked);
        gate.unlock_at("684291", 102).unwrap();
        assert!(gate.configure_at("111111", None, 104).is_err());
        assert!(gate.status().enabled);
        assert!(gate.configure_at("111111", Some("739182"), 106).is_err());
        gate.configure_at("684291", Some("739182"), 110).unwrap();
        let gate = fixture.gate();
        assert!(gate.unlock_at("684291", 112).is_err());
        gate.unlock_at("739182", 114).unwrap();
        gate.configure_at("739182", None, 115).unwrap();
        let gate = fixture.gate();
        assert!(!gate.status().enabled);
        assert!(!gate.status().locked);
        assert!(fixture.0.join("startup-pin.marker").is_file());
    }
    #[test]
    fn independent_salts_and_malformed_missing_records_fail_closed() {
        let a = Fixture::new();
        let b = Fixture::new();
        a.gate().configure_at("", Some("684291"), 100).unwrap();
        b.gate().configure_at("", Some("684291"), 100).unwrap();
        let aa: Record = read(&a.0.join("startup-pin.json")).unwrap();
        let bb: Record = read(&b.0.join("startup-pin.json")).unwrap();
        assert_ne!(aa.verifier.unwrap().salt, bb.verifier.unwrap().salt);
        fs::remove_file(a.0.join("startup-pin.json")).unwrap();
        let gate = a.gate();
        assert!(gate.status().unavailable);
        assert!(!gate.permits("device_load"));
        assert!(gate.unlock_at("684291", 100).is_err());
        assert!(gate.configure_at("", None, 100).is_err());
        fs::write(b.0.join("startup-pin.json"), "broken").unwrap();
        assert!(b.gate().status().locked);
    }
    #[test]
    fn missing_or_corrupt_rate_and_marker_fail_closed() {
        for name in ["startup-pin-rate.json", "startup-pin.marker"] {
            let f = Fixture::new();
            f.gate().configure_at("", Some("684291"), 100).unwrap();
            fs::write(f.0.join(name), "broken").unwrap();
            assert!(f.gate().status().unavailable);
            fs::remove_file(f.0.join(name)).unwrap();
            assert!(f.gate().status().unavailable);
        }
    }
    #[cfg(unix)]
    #[test]
    fn symlinks_rejected_and_pin_files_are_private() {
        use std::os::unix::fs::{symlink, PermissionsExt};
        let f = Fixture::new();
        f.gate().configure_at("", Some("684291"), 100).unwrap();
        for name in [
            "startup-pin.json",
            "startup-pin.marker",
            "startup-pin-rate.json",
        ] {
            assert_eq!(
                fs::metadata(f.0.join(name)).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        let target = f.0.join("unrelated");
        fs::write(&target, "untouched").unwrap();
        fs::remove_file(f.0.join("startup-pin-rate.json")).unwrap();
        symlink(&target, f.0.join("startup-pin-rate.json")).unwrap();
        let gate = f.gate();
        assert!(gate.status().unavailable);
        assert!(gate.unlock_at("684291", 100).is_err());
        assert_eq!(fs::read_to_string(target).unwrap(), "untouched");
    }
    #[test]
    fn incomplete_or_inconsistent_valid_json_never_disables_protection() {
        let f = Fixture::new();
        fs::create_dir_all(&f.0).unwrap();
        write(&f.0, "startup-pin.marker", &serde_json::json!({"schema":1})).unwrap();
        write(&f.0, "startup-pin-rate.json", &Rate::default()).unwrap();
        for value in [
            serde_json::json!({"schema":1}),
            serde_json::json!({"schema":1,"enabled":false}),
            serde_json::json!({"schema":1,"enabled":true,"verifier":null}),
            serde_json::json!({"schema":1,"verifier":null}),
        ] {
            write(&f.0, "startup-pin.json", &value).unwrap();
            assert!(f.gate().status().unavailable);
            assert!(!f.gate().permits("device_load"));
        }
    }
    #[test]
    fn serialization_exposes_no_verifier_or_paths_and_invalid_pin_never_enables() {
        let f = Fixture::new();
        let gate = f.gate();
        for invalid in ["12345", "1234567890123", "abcdef", "123 45"] {
            assert!(gate.configure_at("", Some(invalid), 100).is_err());
        }
        assert!(!gate.status().enabled);
        let v = serde_json::to_value(gate.status()).unwrap();
        assert_eq!(v.as_object().unwrap().len(), 4);
        assert!(v.get("hash").is_none());
        assert!(v.get("data").is_none());
    }
}
