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
/// Free bytes available to this user on the volume holding `path`.
pub fn available_space(path: &Path) -> std::io::Result<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let mut free = 0u64;
        let ok = unsafe {
            windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW(
                wide.as_ptr(),
                &mut free,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if ok == 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(free)
    }
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let name = std::ffi::CString::new(path.as_os_str().as_bytes())
            .map_err(|_| std::io::Error::other("Path contains a NUL byte"))?;
        let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
        if unsafe { libc::statvfs(name.as_ptr(), &mut stat) } != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok((stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64))
    }
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
    #[test]
    fn free_space_is_reported_for_existing_folders() {
        assert!(available_space(&std::env::temp_dir()).unwrap() > 0);
        assert!(available_space(Path::new("/definitely/missing/dazedev")).is_err());
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
