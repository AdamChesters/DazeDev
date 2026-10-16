//! OpenSSH known_hosts lookup and recording.
//!
//! Supports hashed names, `[host]:port`, wildcards, negation and `@revoked`.
//! `@cert-authority` lines are skipped: a host certificate is checked by its
//! plain key instead.
use base64::{engine::general_purpose::STANDARD, Engine};
use hmac::{Hmac, KeyInit, Mac};
use russh::keys::{ssh_key, PublicKey};
use std::{
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Debug, PartialEq, Eq)]
pub enum Status {
    Known,
    /// A different key of the same type is recorded for this host.
    Changed {
        file: PathBuf,
        line: usize,
    },
    Revoked,
    /// No key of this type is recorded. `other_types` lists recorded types.
    Unknown {
        other_types: Vec<String>,
    },
}

struct Entry {
    marker: Option<String>,
    hosts: String,
    key: PublicKey,
    line: usize,
}

fn entries(path: &Path) -> Vec<Entry> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for (index, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut fields = line.split_whitespace();
        let mut first = fields.next().unwrap_or("");
        let marker = if first.starts_with('@') {
            let marker = first.to_owned();
            first = fields.next().unwrap_or("");
            Some(marker)
        } else {
            None
        };
        let (Some(kind), Some(data)) = (fields.next(), fields.next()) else {
            continue;
        };
        if let Ok(key) = PublicKey::from_openssh(&format!("{kind} {data}")) {
            out.push(Entry {
                marker,
                hosts: first.to_owned(),
                key,
                line: index + 1,
            });
        }
    }
    out
}

fn wildcard(pattern: &[u8], text: &[u8]) -> bool {
    match (pattern.first(), text.first()) {
        (None, None) => true,
        (Some(b'*'), _) => {
            wildcard(&pattern[1..], text) || (!text.is_empty() && wildcard(pattern, &text[1..]))
        }
        (Some(b'?'), Some(_)) => wildcard(&pattern[1..], &text[1..]),
        (Some(p), Some(t)) if p.eq_ignore_ascii_case(t) => wildcard(&pattern[1..], &text[1..]),
        _ => false,
    }
}

fn hashed(entry: &str, name: &str) -> bool {
    let Some(rest) = entry.strip_prefix("|1|") else {
        return false;
    };
    let Some((salt, hash)) = rest.split_once('|') else {
        return false;
    };
    let (Ok(salt), Ok(hash)) = (STANDARD.decode(salt), STANDARD.decode(hash)) else {
        return false;
    };
    let Ok(mut mac) = <Hmac<sha1::Sha1> as KeyInit>::new_from_slice(&salt) else {
        return false;
    };
    mac.update(name.as_bytes());
    mac.verify_slice(&hash).is_ok()
}

/// True when `name` (already in `[host]:port` form when needed) matches the
/// host field of a known_hosts line.
pub fn host_matches(field: &str, name: &str) -> bool {
    if field.starts_with("|1|") {
        return hashed(field, name);
    }
    let mut matched = false;
    for pattern in field.split(',') {
        if let Some(negated) = pattern.strip_prefix('!') {
            if wildcard(negated.as_bytes(), name.as_bytes()) {
                return false;
            }
        } else if wildcard(pattern.as_bytes(), name.as_bytes()) {
            matched = true;
        }
    }
    matched
}

fn same_key(a: &PublicKey, b: &PublicKey) -> bool {
    a.key_data() == b.key_data()
}

/// Check `key` for any of `names` across `files`.
pub fn check(files: &[PathBuf], names: &[String], key: &PublicKey) -> Status {
    let mut changed = None;
    let mut other_types = Vec::new();
    let mut known = false;
    for file in files {
        for entry in entries(file) {
            if !names.iter().any(|n| host_matches(&entry.hosts, n)) {
                continue;
            }
            match entry.marker.as_deref() {
                Some("@revoked") => {
                    if same_key(&entry.key, key) {
                        return Status::Revoked;
                    }
                    continue;
                }
                Some(_) => continue,
                None => {}
            }
            if same_key(&entry.key, key) {
                known = true;
            } else if entry.key.algorithm().as_str() == key.algorithm().as_str() {
                changed.get_or_insert((file.clone(), entry.line));
            } else {
                let kind = entry.key.algorithm().as_str().to_owned();
                if !other_types.contains(&kind) {
                    other_types.push(kind);
                }
            }
        }
    }
    if known {
        Status::Known
    } else if let Some((file, line)) = changed {
        Status::Changed { file, line }
    } else {
        Status::Unknown { other_types }
    }
}

/// Key algorithms already recorded for these names, so negotiation can
/// prefer them and avoid a needless "unknown key" prompt.
pub fn recorded_algorithms(files: &[PathBuf], names: &[String]) -> Vec<ssh_key::Algorithm> {
    let mut out: Vec<ssh_key::Algorithm> = Vec::new();
    for file in files {
        for entry in entries(file) {
            if entry.marker.is_none()
                && names.iter().any(|n| host_matches(&entry.hosts, n))
                && !out.contains(&entry.key.algorithm())
            {
                out.push(entry.key.algorithm());
            }
        }
    }
    out
}

fn hash_name(name: &str) -> String {
    let salt: [u8; 20] = rand_bytes();
    let mut mac =
        <Hmac<sha1::Sha1> as KeyInit>::new_from_slice(&salt).expect("HMAC accepts any key size");
    mac.update(name.as_bytes());
    format!(
        "|1|{}|{}",
        STANDARD.encode(salt),
        STANDARD.encode(mac.finalize().into_bytes())
    )
}

fn rand_bytes() -> [u8; 20] {
    let mut out = [0u8; 20];
    let uuid = uuid::Uuid::new_v4();
    let more = uuid::Uuid::new_v4();
    out[..16].copy_from_slice(uuid.as_bytes());
    out[16..].copy_from_slice(&more.as_bytes()[..4]);
    out
}

/// Append a host key, creating the file (and `.ssh`) when missing.
pub fn record(file: &Path, name: &str, key: &PublicKey, hash: bool) -> std::io::Result<()> {
    if let Some(parent) = file.parent() {
        std::fs::create_dir_all(parent)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if parent.file_name().is_some_and(|n| n == ".ssh") {
                let _ = std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700));
            }
        }
    }
    let host = if hash {
        hash_name(name)
    } else {
        name.to_owned()
    };
    let line = format!(
        "{host} {}\n",
        key.to_openssh().map_err(std::io::Error::other)?
    );
    let mut options = std::fs::OpenOptions::new();
    options.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut handle = options.open(file)?;
    // Keep the new entry on its own line if the file lacks a final newline.
    let needs_newline = std::fs::read(file)
        .map(|bytes| bytes.last().is_some_and(|b| *b != b'\n'))
        .unwrap_or(false);
    if needs_newline {
        handle.write_all(b"\n")?;
    }
    handle.write_all(line.as_bytes())?;
    handle.sync_all()
}

/// The SHA256 fingerprint OpenSSH shows, e.g. `SHA256:abc...`.
pub fn fingerprint(key: &PublicKey) -> String {
    key.fingerprint(ssh_key::HashAlg::Sha256).to_string()
}

/// The key type name OpenSSH shows in prompts, e.g. `ED25519`.
pub fn kind_label(key: &PublicKey) -> String {
    match key.algorithm() {
        ssh_key::Algorithm::Ed25519 => "ED25519".into(),
        ssh_key::Algorithm::Rsa { .. } => "RSA".into(),
        ssh_key::Algorithm::Ecdsa { .. } => "ECDSA".into(),
        other => other.as_str().to_ascii_uppercase(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ED: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ48UXvX/ZaeCLfRa3a5p6NnXs2Rtn1sHjVU0Hur6FCn";
    const ED2: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM6d2K4esMUPfqLevFgDW4yL+KqVozNmImwjvRPtF+Dh";

    fn key(text: &str) -> PublicKey {
        PublicKey::from_openssh(text).unwrap()
    }

    fn file(lines: &str) -> (tempdir::Dir, PathBuf) {
        let dir = tempdir::Dir::new();
        let path = dir.0.join("known_hosts");
        std::fs::write(&path, lines).unwrap();
        (dir, path)
    }

    mod tempdir {
        pub struct Dir(pub std::path::PathBuf);
        impl Dir {
            pub fn new() -> Self {
                let path =
                    std::env::temp_dir().join(format!("dazedev-kh-{}", uuid::Uuid::new_v4()));
                std::fs::create_dir(&path).unwrap();
                Self(path)
            }
        }
        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }

    #[test]
    fn patterns_ports_and_negation() {
        assert!(host_matches("work,10.0.0.5", "10.0.0.5"));
        assert!(host_matches("*.example.com", "a.example.com"));
        assert!(!host_matches(
            "*.example.com,!bad.example.com",
            "bad.example.com"
        ));
        assert!(host_matches("[work]:2222", "[work]:2222"));
        assert!(!host_matches("work", "[work]:2222"));
        assert!(host_matches("w?rk", "WORK"));
    }

    #[test]
    fn known_changed_unknown_and_revoked() {
        let (_dir, path) = file(&format!("work {ED}\n@revoked bad {ED2}\n"));
        let files = vec![path.clone()];
        assert_eq!(check(&files, &["work".into()], &key(ED)), Status::Known);
        assert_eq!(
            check(&files, &["work".into()], &key(ED2)),
            Status::Changed {
                file: path,
                line: 1
            }
        );
        assert_eq!(
            check(&files, &["other".into()], &key(ED)),
            Status::Unknown {
                other_types: vec![]
            }
        );
        assert_eq!(check(&files, &["bad".into()], &key(ED2)), Status::Revoked);
    }

    #[test]
    fn recorded_hashed_entry_is_found_and_never_reveals_the_name() {
        let (_dir, path) = file("");
        record(&path, "[work]:2222", &key(ED), true).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.starts_with("|1|") && !text.contains("work"));
        assert_eq!(
            check(&[path.clone()], &["[work]:2222".into()], &key(ED)),
            Status::Known
        );
        assert_eq!(
            check(&[path], &["work".into()], &key(ED)),
            Status::Unknown {
                other_types: vec![]
            }
        );
    }

    #[test]
    fn record_adds_missing_newline_and_plain_name() {
        let (_dir, path) = file(&format!("other {ED2}"));
        record(&path, "work", &key(ED), false).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert_eq!(text.lines().count(), 2);
        assert!(text
            .lines()
            .nth(1)
            .unwrap()
            .starts_with("work ssh-ed25519 "));
    }

    #[test]
    fn other_key_types_are_reported_not_changed() {
        let rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQC2TtpDYySvjMxr6+WuuQmWRwzwUo97t5oU/8Tm+zdZxxZdOZMwL+uUslLN/C8zLO8MUEC8rIc27+BSaHPPpiG/toz4VEqUuij3Cufd+3syfqahHPOwsbjQSJIGfSaTVkQS+zMjuDx8/x95ifZb5/R+4jh7q8CYWLu4f6uiRln2RQ==";
        let (_dir, path) = file(&format!("work {rsa}\n"));
        assert_eq!(
            check(&[path.clone()], &["work".into()], &key(ED)),
            Status::Unknown {
                other_types: vec!["ssh-rsa".into()]
            }
        );
        assert_eq!(recorded_algorithms(&[path], &["work".into()]).len(), 1);
    }
}
