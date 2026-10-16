//! Start a local agent CLI in PowerShell without cmd.exe re-reading its prompt.
//!
//! npm installs agents as `.cmd` shims. PowerShell runs those through cmd.exe,
//! which expands `%`, `!` and splits on `&`. So a shim is resolved to node and
//! its script, and the prompt reaches PowerShell only through an environment
//! variable, never inside the command text.
#![cfg_attr(not(windows), allow(dead_code))]
use std::path::{Path, PathBuf};

/// Environment variable that carries the prompt into the session shell.
pub(super) const BRIEF_VARIABLE: &str = "DAZEDEV_AGENT_BRIEF";

pub(super) struct Launch {
    pub program: PathBuf,
    pub args: Vec<PathBuf>,
    /// The program is a batch file that cmd.exe will parse.
    pub batch: bool,
}

/// The script an npm `.cmd` shim runs, relative to the shim's folder.
pub(super) fn npm_shim_script(shim: &str) -> Option<String> {
    // Current shims run `"%_prog%"  "%dp0%\path\cli.js" %*`; older ones
    // `"%~dp0\node.exe"  "%~dp0\path\cli.js" %*`. The script is the last one.
    let (at, marker) = ["\"%dp0%\\", "\"%~dp0\\"]
        .iter()
        .filter_map(|m| shim.rfind(m).map(|at| (at, *m)))
        .max_by_key(|(at, _)| *at)?;
    let rest = &shim[at + marker.len()..];
    let script = &rest[..rest.find('"')?];
    let plain = !script.is_empty()
        && !script.contains(['%', '!', ':', '*', '?', '<', '>', '|'])
        && !script
            .split(['\\', '/'])
            .any(|part| part.is_empty() || part == "..")
        && [".js", ".cjs", ".mjs"]
            .iter()
            .any(|ext| script.to_ascii_lowercase().ends_with(ext));
    plain.then(|| script.to_string())
}

/// True when cmd.exe would read `text` exactly as written.
pub(super) fn batch_safe(text: &str) -> bool {
    !text
        .chars()
        .any(|c| c.is_control() || "%!^&|<>\"".contains(c))
}

fn is_batch(path: &Path) -> bool {
    path.extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("cmd") || ext.eq_ignore_ascii_case("bat"))
}

/// How to start an agent found at `agent`.
pub(super) fn resolve(agent: &Path, node: impl Fn() -> Option<PathBuf>) -> Launch {
    if is_batch(agent) {
        let folder = agent.parent().unwrap_or(Path::new(""));
        let script = std::fs::read(agent)
            .ok()
            .filter(|bytes| bytes.len() <= 65536)
            .and_then(|bytes| npm_shim_script(&String::from_utf8_lossy(&bytes)))
            .map(|script| folder.join(script.replace('\\', std::path::MAIN_SEPARATOR_STR)))
            .filter(|script| script.is_file());
        let local = folder.join(if cfg!(windows) { "node.exe" } else { "node" });
        let node = if local.is_file() { Some(local) } else { node() };
        if let (Some(script), Some(node)) = (script, node) {
            return Launch {
                program: node,
                args: vec![script],
                batch: false,
            };
        }
    }
    Launch {
        program: agent.to_path_buf(),
        args: vec![],
        batch: is_batch(agent),
    }
}

fn ps_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}

/// PowerShell that starts the agent. With a prompt, it reads it from
/// `BRIEF_VARIABLE`. PowerShell before 7.3 passes embedded quotes to native
/// programs unescaped, so the script escapes them the way programs expect.
pub(super) fn script(launch: &Launch, brief: bool) -> String {
    let mut script = String::new();
    if brief {
        script.push_str(&format!(
            "$dazeBrief = $env:{BRIEF_VARIABLE}; Remove-Item Env:{BRIEF_VARIABLE}; "
        ));
        script.push_str(
            r#"if (-not (Get-Variable PSNativeCommandArgumentPassing -ErrorAction Ignore) -or $PSNativeCommandArgumentPassing -eq 'Legacy') { $dazeBrief = $dazeBrief -replace '(\\*)"', '$1$1\"'; if ($dazeBrief -match '\s') { $dazeBrief = $dazeBrief -replace '(\\+)$', '$1$1' } }; "#,
        );
    }
    script.push_str("& ");
    script.push_str(&ps_quote(&launch.program.to_string_lossy()));
    for arg in &launch.args {
        script.push(' ');
        script.push_str(&ps_quote(&arg.to_string_lossy()));
    }
    if brief {
        script.push_str(" $dazeBrief");
    }
    script
}

#[cfg(test)]
mod tests {
    use super::*;
    const SHIM: &str = "@ECHO off\r\nGOTO start\r\n:find_dp0\r\nSET dp0=%~dp0\r\nEXIT /b\r\n:start\r\nSETLOCAL\r\nCALL :find_dp0\r\n\r\nIF EXIST \"%dp0%\\node.exe\" (\r\n  SET \"_prog=%dp0%\\node.exe\"\r\n) ELSE (\r\n  SET \"_prog=node\"\r\n  SET PATHEXT=%PATHEXT:;.JS;=;%\r\n)\r\n\r\nendLocal & goto #_undefined_# 2>NUL || title %COMSPEC% & \"%_prog%\"  \"%dp0%\\node_modules\\@openai\\codex\\bin\\codex.js\" %*\r\n";
    #[test]
    fn npm_shims_resolve_to_their_script() {
        assert_eq!(
            npm_shim_script(SHIM).as_deref(),
            Some(r"node_modules\@openai\codex\bin\codex.js")
        );
        let old = "@IF EXIST \"%~dp0\\node.exe\" (\r\n  \"%~dp0\\node.exe\"  \"%~dp0\\node_modules\\x\\cli.js\" %*\r\n)";
        assert_eq!(
            npm_shim_script(old).as_deref(),
            Some(r"node_modules\x\cli.js")
        );
        for bad in [
            "\"%dp0%\\..\\evil.js\" %*",
            "\"%dp0%\\%OTHER%\\cli.js\"",
            "\"%dp0%\\cli.exe\" %*",
            "echo hello",
        ] {
            assert_eq!(npm_shim_script(bad), None, "{bad}");
        }
    }
    #[test]
    fn shim_runs_node_with_script_and_others_stay_as_found() {
        let root = std::env::temp_dir().join(format!("daze-shim-{}", uuid::Uuid::new_v4()));
        let script = root.join("node_modules/@openai/codex/bin");
        std::fs::create_dir_all(&script).unwrap();
        std::fs::write(script.join("codex.js"), b"").unwrap();
        std::fs::write(root.join("codex.cmd"), SHIM).unwrap();
        let launch = resolve(&root.join("codex.cmd"), || Some("/usr/bin/node".into()));
        assert_eq!(launch.program, PathBuf::from("/usr/bin/node"));
        assert_eq!(launch.args, [script.join("codex.js")]);
        assert!(!launch.batch);
        let missing = resolve(&root.join("codex.cmd"), || None);
        assert!(missing.batch);
        std::fs::write(root.join("other.cmd"), b"@echo off\r\nother.exe %*").unwrap();
        assert!(resolve(&root.join("other.cmd"), || Some("node".into())).batch);
        assert!(!resolve(&root.join("claude.exe"), || None).batch);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn prompt_never_enters_the_command_text() {
        let launch = Launch {
            program: "C:/Program Files/node's/node.exe".into(),
            args: vec!["C:/npm/cli.js".into()],
            batch: false,
        };
        let text = script(&launch, true);
        assert!(text.contains("$env:DAZEDEV_AGENT_BRIEF"));
        assert!(text.ends_with("& 'C:/Program Files/node''s/node.exe' 'C:/npm/cli.js' $dazeBrief"));
        assert_eq!(
            script(&launch, false),
            "& 'C:/Program Files/node''s/node.exe' 'C:/npm/cli.js'"
        );
    }
    #[test]
    fn batch_safety_rejects_cmd_syntax() {
        assert!(batch_safe("Fix the login page, then run tests."));
        for bad in [
            "100%",
            "a & b",
            "say \"hi\"",
            "wow!",
            "a|b",
            "x^y",
            "line\nbreak",
        ] {
            assert!(!batch_safe(bad), "{bad}");
        }
    }
}
