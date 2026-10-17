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
/// Installed Linux packages (.deb under /usr or /opt, or a mounted AppImage)
/// cannot keep data beside the read-only executable. They use the user's XDG
/// data folder instead. Extracted portable folders keep adjacent `data/`, and
/// an explicit `DAZEDEV_DATA_DIR` always wins.
#[cfg(target_os = "linux")]
pub fn installed_linux_data_dir(exe: &Path) -> Option<PathBuf> {
    if std::env::var_os("DAZEDEV_DATA_DIR").is_some() {
        return None;
    }
    let appimage = std::env::var_os("APPIMAGE").is_some()
        && std::env::var_os("APPDIR").is_some_and(|dir| exe.starts_with(dir));
    let installed = exe.starts_with("/usr/") || exe.starts_with("/opt/");
    if !(appimage || installed) {
        return None;
    }
    dirs::data_dir().map(|dir| dir.join("DazeDev"))
}
/// WebKitGTK leaves media capture off by default. Enable it for the bundled
/// interface and grant microphone-only requests from it; camera, screen and
/// any other origin are denied.
#[cfg(all(target_os = "linux", feature = "desktop"))]
pub fn enable_linux_microphone<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) {
    let _ = window.with_webview(|webview| {
        use webkit2gtk::glib::prelude::{Cast, ObjectExt};
        use webkit2gtk::{
            PermissionRequestExt, SettingsExt, UserMediaPermissionRequestExt, WebViewExt,
        };
        let view = webview.inner();
        if let Some(settings) = WebViewExt::settings(&view) {
            settings.set_enable_media_stream(true);
        }
        view.connect_permission_request(|view, request| {
            let bundled = view.uri().is_some_and(|uri| {
                let uri = uri.as_str();
                uri == "tauri://localhost"
                    || uri.starts_with("tauri://localhost/")
                    || (cfg!(debug_assertions) && uri.starts_with("http://localhost:1420/"))
            });
            let allowed = if let Some(media) =
                request.downcast_ref::<webkit2gtk::UserMediaPermissionRequest>()
            {
                media.is_for_audio_device() && !media.is_for_video_device()
            } else if request.is::<webkit2gtk::DeviceInfoPermissionRequest>() {
                true // Microphone names for the device picker.
            } else {
                return false;
            };
            if bundled && allowed {
                request.allow();
            } else {
                request.deny();
            }
            true
        });
    });
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
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_package_locations_use_user_data_folder() {
        if std::env::var_os("DAZEDEV_DATA_DIR").is_some() {
            return;
        }
        let user = dirs::data_dir().unwrap().join("DazeDev");
        for exe in ["/usr/bin/DazeDev", "/opt/DazeDev/DazeDev"] {
            assert_eq!(installed_linux_data_dir(Path::new(exe)), Some(user.clone()));
        }
        if std::env::var_os("APPIMAGE").is_none() {
            assert_eq!(
                installed_linux_data_dir(Path::new("/home/u/DazeDev/DazeDev")),
                None
            );
        }
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
