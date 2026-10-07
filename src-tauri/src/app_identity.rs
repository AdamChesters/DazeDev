const APP_ID: &str = "com.adamchesters.dazedev";

/// Set our taskbar identity before creating windows, regardless of the launcher.
pub fn initialize() -> Result<(), String> {
    let id: Vec<u16> = APP_ID.encode_utf16().chain(Some(0)).collect();
    let result = unsafe {
        windows_sys::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID(id.as_ptr())
    };
    if result < 0 {
        return Err(format!("Windows taskbar identity failed: {result:#x}"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn replaces_a_launchers_identity() {
        use windows_sys::Win32::UI::Shell::{
            GetCurrentProcessExplicitAppUserModelID, SetCurrentProcessExplicitAppUserModelID,
        };
        let launcher: Vec<u16> = "DazeDev.TestLauncher"
            .encode_utf16()
            .chain(Some(0))
            .collect();
        unsafe {
            assert_eq!(
                SetCurrentProcessExplicitAppUserModelID(launcher.as_ptr()),
                0
            );
            super::initialize().unwrap();
            let mut value = std::ptr::null_mut();
            assert_eq!(GetCurrentProcessExplicitAppUserModelID(&mut value), 0);
            assert!(!value.is_null());
            let mut length = 0;
            while *value.add(length) != 0 {
                length += 1;
            }
            let actual = String::from_utf16_lossy(std::slice::from_raw_parts(value, length));
            windows_sys::Win32::System::Com::CoTaskMemFree(value.cast());
            assert_eq!(actual, super::APP_ID);
        }
    }
}
