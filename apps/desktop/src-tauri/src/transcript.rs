//! Conservative preservation checks for technical speech cleanup.
pub const CLEANUP_SYSTEM: &str = "You are a text normalizer for speech-to-text transcripts. The input begins with a control line specifying the styling, structure, and context settings; clean the transcript to match those settings and output only the cleaned text.";
pub const CLEANUP_CONTROL: &str = "[Styling: semi-casual] [Structure: prose] [Context: general]";
const NEGATIONS: [&str; 7] = ["not", "no", "never", "don't", "cannot", "can't", "without"];
// Spoken self-corrections. Cleanup applies them, so the words just before one
// (for example "10" in "up to 10 megabytes, no wait, make that 5") may go.
const CORRECTIONS: [(&str, &str); 10] = [
    ("no", "wait"),
    ("wait", "no"),
    ("no", "sorry"),
    ("sorry", "no"),
    ("i", "mean"),
    ("make", "that"),
    ("scratch", "that"),
    ("or", "rather"),
    ("no", "actually"),
    ("actually", "no"),
];
const CORRECTION_WINDOW: usize = 6;
fn token(word: &str) -> String {
    // Cleanup may switch between straight and curly apostrophes.
    word.replace(['\u{2018}', '\u{2019}'], "'")
        .trim_matches([
            '"', '\'', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}',
        ])
        .trim_end_matches('.')
        .to_string()
}
fn literal(word: &str) -> bool {
    word.chars().any(|c| c.is_ascii_digit())
        || word.contains(['/', '\\', '_'])
        || word.starts_with("--")
        || word.starts_with('.')
        || word.chars().skip(1).any(|c| c.is_uppercase())
}
fn digits(word: &str) -> String {
    word.chars().filter(char::is_ascii_digit).collect()
}
// Plain numbers may be reformatted ("40000" as "40,000", "12" as "$12",
// "9 30" as "9:30"); their digits must still appear.
fn number(word: &str) -> bool {
    word.chars().any(|c| c.is_ascii_digit())
        && word
            .chars()
            .all(|c| c.is_ascii_digit() || ".,:$%€£".contains(c))
}
pub fn preserves_literals(raw: &str, cleaned: &str) -> bool {
    let output: Vec<_> = cleaned.split_whitespace().map(token).collect();
    let output_digits = output
        .iter()
        .map(|w| digits(w))
        .collect::<Vec<_>>()
        .join(" ");
    let words: Vec<_> = raw
        .split_whitespace()
        .map(token)
        .filter(|s| !s.is_empty())
        .collect();
    let lower: Vec<_> = words.iter().map(|w| w.to_ascii_lowercase()).collect();
    // Literals just before a correction may be replaced. Negations there still
    // count, except the "no" that is part of the correction phrase itself.
    let mut corrected = vec![false; words.len()];
    let mut cue = vec![false; words.len()];
    for i in 1..lower.len() {
        if CORRECTIONS.contains(&(lower[i - 1].as_str(), lower[i].as_str())) {
            corrected[(i - 1).saturating_sub(CORRECTION_WINDOW)..=i].fill(true);
            cue[i - 1..=i].fill(true);
        }
    }
    let mut needed: Vec<(&str, usize)> = Vec::new();
    for (i, word) in words.iter().enumerate() {
        if let Some(negation) = NEGATIONS.iter().find(|n| **n == lower[i]) {
            // A stutter such as "no no" is one negation; cleanup removes the repeat.
            // A "no" right before another negative ("no, it doesn't") only adds
            // emphasis; the following word keeps the meaning.
            let emphasis = lower[i] == "no"
                && lower[i + 1..lower.len().min(i + 4)]
                    .iter()
                    .any(|w| NEGATIONS.contains(&w.as_str()) || w.ends_with("n't"));
            if cue[i] || emphasis || (i > 0 && lower[i - 1] == lower[i]) {
                continue;
            }
            match needed.iter_mut().find(|(n, _)| n == negation) {
                Some((_, count)) => *count += 1,
                None => needed.push((*negation, 1)),
            }
        } else if !corrected[i] && literal(word) {
            let kept = if number(word) {
                output_digits.contains(&digits(word))
            } else {
                output.contains(word)
            };
            if !kept {
                return false;
            }
        }
    }
    needed.iter().all(|(negation, count)| {
        output
            .iter()
            .filter(|w| w.eq_ignore_ascii_case(negation))
            .count()
            >= *count
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
        assert!(!preserves_literals("not --dry-run 43", "--dry-run 43"));
    }
    #[test]
    fn cleanup_may_remove_stutters_and_apply_spoken_corrections() {
        assert!(preserves_literals(
            "it connects but no no it doesn't show the session and I can't scroll",
            "It connects, but no, it doesn't show the session and I can’t scroll."
        ));
        assert!(preserves_literals(
            "it connects but no no it doesn't show",
            "It connects, but it doesn't show."
        ));
        assert!(!preserves_literals(
            "no rush on that and no need to be exact",
            "No rush on that, and you need to be exact."
        ));
        assert!(preserves_literals(
            "say PDF or Word up to 10 megabytes. no, wait, make that five megabytes",
            "Say PDF or Word, up to 5 megabytes."
        ));
        // Only the words just before a correction may go.
        assert!(!preserves_literals(
            "use port 8080 for the api and the database and then the cache, no wait, the queue",
            "Use the port for the API and the database, and then the queue."
        ));
        assert!(preserves_literals(
            "the table has 40000 rows and it costs 12 dollars, around 9 30 for 10 percent",
            "The table has 40,000 rows and it costs $12, around 9:30 for 10%."
        ));
        assert!(!preserves_literals("it costs 12 dollars", "It costs $13."));
        // A correction never excuses a dropped negation.
        assert!(!preserves_literals(
            "do not delete it, I mean the backup",
            "Delete the backup."
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
