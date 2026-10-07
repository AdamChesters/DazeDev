//! Conservative preservation checks for technical speech cleanup.
pub const CLEANUP_SYSTEM: &str = "You are a text normalizer for speech-to-text transcripts. The input begins with a control line specifying the styling, structure, and context settings; clean the transcript to match those settings and output only the cleaned text.";
pub const CLEANUP_CONTROL: &str = "[Styling: semi-casual] [Structure: prose] [Context: general]";
fn token(word: &str) -> &str {
    word.trim_matches([
        '"', '\'', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}',
    ])
    .trim_end_matches('.')
}
pub fn preserves_literals(raw: &str, cleaned: &str) -> bool {
    let output: Vec<_> = cleaned.split_whitespace().map(token).collect();
    raw.split_whitespace()
        .map(token)
        .filter(|s| !s.is_empty())
        .all(|word| {
            let lower = word.to_ascii_lowercase();
            let negation = matches!(
                lower.as_str(),
                "not" | "no" | "never" | "don't" | "cannot" | "can't" | "without"
            );
            let literal = word.chars().any(|c| c.is_ascii_digit())
                || word.contains(['/', '\\', '_'])
                || word.starts_with("--")
                || word.starts_with('.')
                || word.chars().skip(1).any(|c| c.is_uppercase());
            if negation {
                output
                    .iter()
                    .filter(|w| w.eq_ignore_ascii_case(word))
                    .count()
                    >= raw
                        .split_whitespace()
                        .map(token)
                        .filter(|w| w.eq_ignore_ascii_case(word))
                        .count()
            } else if literal {
                output.contains(&word)
            } else {
                true
            }
        })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn supported_cleanup_contract_and_identifier_fallback() {
        assert_eq!(
            CLEANUP_CONTROL,
            "[Styling: semi-casual] [Structure: prose] [Context: general]"
        );
        assert!(CLEANUP_SYSTEM.ends_with(
            "clean the transcript to match those settings and output only the cleaned text."
        ));
        assert!(!preserves_literals(
            "Use ExampleProject and getUserId",
            "Use sample project and get user id"
        ));
        assert!(preserves_literals(
            "um use ExampleProject and getUserId",
            "use ExampleProject and getUserId."
        ));
        assert!(!preserves_literals(
            "not not --dry-run 43",
            "not --dry-run 43"
        ));
    }
    #[test]
    fn cleanup_must_preserve_paths_flags_numbers_and_negation() {
        let raw = "Um do not change /tmp/config.json or --dry-run for build_2, never use 42.";
        assert!(preserves_literals(
            raw,
            "Do not change /tmp/config.json or --dry-run for build_2, never use 42."
        ));
        for pair in [
            ("/tmp/config.json", "/tmp/config"),
            ("--dry-run", "--force"),
            ("42", "43"),
            ("not ", ""),
            ("never ", ""),
        ] {
            assert!(!preserves_literals(raw, &raw.replace(pair.0, pair.1)));
        }
    }
}
