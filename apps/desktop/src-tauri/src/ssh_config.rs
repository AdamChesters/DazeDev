//! Alias suggestions only. OpenSSH remains authoritative for connection settings.
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};
fn words(line: &str) -> Vec<String> {
    let mut out = vec![];
    let mut word = String::new();
    let mut quote = false;
    for c in line.chars() {
        match c {
            '"' => quote = !quote,
            '#' if !quote => break,
            '=' | ' ' | '\t' if !quote => {
                if !word.is_empty() {
                    out.push(std::mem::take(&mut word));
                }
            }
            _ => word.push(c),
        }
    }
    if !word.is_empty() {
        out.push(word);
    }
    out
}
fn matches(pattern: &[u8], name: &[u8]) -> bool {
    // Linear wildcard matching with a single backtracking checkpoint.
    let (mut p, mut n, mut star, mut checkpoint) = (0, 0, None, 0);
    while n < name.len() {
        if p < pattern.len() && (pattern[p] == b'?' || pattern[p] == name[n]) {
            p += 1;
            n += 1;
        } else if p < pattern.len() && pattern[p] == b'*' {
            star = Some(p);
            p += 1;
            checkpoint = n;
        } else if let Some(s) = star {
            checkpoint += 1;
            n = checkpoint;
            p = s + 1;
        } else {
            return false;
        }
    }
    while p < pattern.len() && pattern[p] == b'*' {
        p += 1;
    }
    p == pattern.len()
}
pub fn aliases(home: &Path) -> Vec<String> {
    let mut pending = vec![home.join(".ssh/config")];
    let mut seen = BTreeSet::new();
    let mut names = BTreeSet::new();
    while let Some(path) = pending.pop() {
        if seen.len() >= 64 {
            break;
        }
        let Ok(path) = fs::canonicalize(path) else {
            continue;
        };
        if !seen.insert(path.clone()) {
            continue;
        }
        let Ok(text) = fs::read_to_string(path) else {
            continue;
        };
        if text.len() > 1024 * 1024 {
            continue;
        }
        for line in text.lines() {
            let parts = words(line);
            let Some(key) = parts.first() else {
                continue;
            };
            for value in &parts[1..] {
                if key.eq_ignore_ascii_case("host") && !value.contains(['*', '?', '!']) {
                    names.insert(value.clone());
                }
                if key.eq_ignore_ascii_case("include") {
                    let p = if let Some(v) = value.strip_prefix("~/") {
                        home.join(v)
                    } else {
                        let p = PathBuf::from(value);
                        if p.is_absolute() {
                            p
                        } else {
                            home.join(".ssh").join(p)
                        }
                    };
                    if !value.contains(['*', '?']) {
                        pending.push(p);
                        continue;
                    }
                    if let (Some(parent), Some(pattern)) = (p.parent(), p.file_name()) {
                        if let Ok(entries) = fs::read_dir(parent) {
                            for e in entries.flatten().take(1024) {
                                if matches(
                                    pattern.to_string_lossy().as_bytes(),
                                    e.file_name().to_string_lossy().as_bytes(),
                                ) {
                                    pending.push(e.path());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    names.into_iter().collect()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn follows_bounded_includes_and_ignores_wildcard_hosts() {
        let h = std::env::temp_dir().join(format!("dazedev-ssh-config-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(h.join(".ssh/hosts")).unwrap();
        fs::write(
            h.join(".ssh/config"),
            "Host direct *.wild\nInclude hosts/*.conf\nInclude config\n",
        )
        .unwrap();
        fs::write(
            h.join(".ssh/hosts/dev.conf"),
            "Host=\"dev-box\" another # comment\n",
        )
        .unwrap();
        assert_eq!(aliases(&h), vec!["another", "dev-box", "direct"]);
        fs::remove_dir_all(h).unwrap();
    }
}
