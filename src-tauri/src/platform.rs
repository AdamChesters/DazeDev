//! Paths for portable desktop builds. macOS data stays outside the signed app bundle.
use std::path::{Path, PathBuf};
pub fn portable_root(exe: &Path) -> PathBuf {
    let parent = exe.parent().expect("executable directory");
    if cfg!(target_os = "macos") && parent.file_name().is_some_and(|n| n == "MacOS") {
        if let Some(contents) = parent.parent() {
            if contents.file_name().is_some_and(|n| n == "Contents") {
                if let Some(bundle) = contents.parent() {
                    if bundle.extension().is_some_and(|n| n == "app") {
                        return bundle.parent().unwrap().to_path_buf();
                    }
                }
            }
        }
    }
    parent.to_path_buf()
}
pub fn onnx_library() -> &'static str {
    if cfg!(windows) {
        "onnxruntime.dll"
    } else if cfg!(target_os = "macos") {
        "libonnxruntime.dylib"
    } else {
        "libonnxruntime.so"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn portable_executable_keeps_adjacent_data_root() {
        assert_eq!(
            portable_root(Path::new("/tmp/DazeDev/DazeDev")),
            PathBuf::from("/tmp/DazeDev")
        );
    }
    #[cfg(target_os = "macos")]
    #[test]
    fn app_bundle_does_not_store_data_inside_signature() {
        assert_eq!(
            portable_root(Path::new(
                "/tmp/Portable/DazeDev.app/Contents/MacOS/DazeDev"
            )),
            PathBuf::from("/tmp/Portable")
        );
        assert_eq!(onnx_library(), "libonnxruntime.dylib");
    }
}
