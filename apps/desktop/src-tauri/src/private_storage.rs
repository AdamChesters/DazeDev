//! Restrict app-owned data without replacing or deleting existing settings.
use std::{fs, io, path::Path};

/// Folders that hold large third-party state. They inherit the data root's
/// rules and are never walked: WebView2 keeps its own sandbox entries.
const UNWALKED: [&str; 2] = ["webview", "models"];
/// Folders whose existing contents must be private too.
const PRIVATE_TREES: [&str; 2] = ["native-sessions", "migration-recovery"];

fn is_link(meta: &fs::Metadata) -> bool {
    if meta.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        // FILE_ATTRIBUTE_REPARSE_POINT: junctions, mount points, cloud files.
        if meta.file_attributes() & 0x400 != 0 {
            return true;
        }
    }
    false
}
pub fn protect(path: &Path) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if is_link(&meta) {
        return Err(io::Error::other(
            "Private storage cannot contain links or reparse points",
        ));
    }
    restrict(path, meta.is_dir())
}
/// Make `path` current-user only, unless it already is.
fn restrict(path: &Path, dir: bool) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let wanted = if dir { 0o700 } else { 0o600 };
        if fs::metadata(path)?.permissions().mode() & 0o777 == wanted {
            return Ok(());
        }
        fs::set_permissions(path, fs::Permissions::from_mode(wanted))?;
        if fs::metadata(path)?.permissions().mode() & 0o077 != 0 {
            return Err(io::Error::other(
                "Filesystem did not enforce private permissions",
            ));
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::{
            Foundation::LocalFree,
            Security::{
                Authorization::{
                    ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
                },
                SetFileSecurityW, DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION,
            },
        };
        let sid = crate::local_sessions::ipc::sid().map_err(io::Error::other)?;
        let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe { already_private(&name, &sid, dir) } {
            return Ok(());
        }
        let inheritance = if dir { "OICI" } else { "" };
        let sddl: Vec<u16> = format!("D:P(A;{inheritance};FA;;;{sid})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        unsafe {
            use windows_sys::Win32::Storage::FileSystem::{
                GetVolumeInformationW, GetVolumePathNameW,
            };
            let mut volume = vec![0u16; 32768];
            let mut flags = 0u32;
            if GetVolumePathNameW(name.as_ptr(), volume.as_mut_ptr(), volume.len() as u32) == 0
                || GetVolumeInformationW(
                    volume.as_ptr(),
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    &mut flags,
                    std::ptr::null_mut(),
                    0,
                ) == 0
            {
                return Err(io::Error::last_os_error());
            }
            // FILE_PERSISTENT_ACLS: FAT/exFAT cannot enforce per-user DACLs.
            if flags & 0x8 == 0 {
                return Err(io::Error::other(
                    "Filesystem does not support private access permissions",
                ));
            }
            let mut descriptor = std::ptr::null_mut();
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                std::ptr::null_mut(),
            ) == 0
            {
                return Err(io::Error::last_os_error());
            }
            let ok = SetFileSecurityW(
                name.as_ptr(),
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                descriptor,
            );
            let result = if ok == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            };
            LocalFree(descriptor.cast());
            result?;
        }
    }
    Ok(())
}
/// True when the DACL is protected and is exactly one full-access entry for
/// `sid`, inheritable on folders.
#[cfg(windows)]
unsafe fn already_private(name: &[u16], sid: &str, dir: bool) -> bool {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{
            Authorization::{ConvertSidToStringSidW, GetNamedSecurityInfoW, SE_FILE_OBJECT},
            GetAce, GetSecurityDescriptorControl, ACCESS_ALLOWED_ACE, DACL_SECURITY_INFORMATION,
            SE_DACL_PROTECTED,
        },
    };
    let mut acl = std::ptr::null_mut();
    let mut descriptor = std::ptr::null_mut();
    if GetNamedSecurityInfoW(
        name.as_ptr(),
        SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION,
        std::ptr::null_mut(),
        std::ptr::null_mut(),
        &mut acl,
        std::ptr::null_mut(),
        &mut descriptor,
    ) != 0
    {
        return false;
    }
    let result = (|| {
        let mut control = 0u16;
        let mut revision = 0u32;
        if GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) == 0
            || control & SE_DACL_PROTECTED == 0
            || acl.is_null()
            || (*acl).AceCount != 1
        {
            return false;
        }
        let mut ace = std::ptr::null_mut();
        if GetAce(acl, 0, &mut ace) == 0 {
            return false;
        }
        let allowed = &*(ace as *const ACCESS_ALLOWED_ACE);
        // ACCESS_ALLOWED_ACE_TYPE, OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE,
        // FILE_ALL_ACCESS.
        let flags = if dir { 0x3 } else { 0 };
        if allowed.Header.AceType != 0
            || allowed.Header.AceFlags != flags
            || allowed.Mask != 0x1F01FF
        {
            return false;
        }
        let mut text = std::ptr::null_mut();
        let sid_ptr = std::ptr::addr_of!(allowed.SidStart) as *mut std::ffi::c_void;
        if ConvertSidToStringSidW(sid_ptr, &mut text) == 0 {
            return false;
        }
        let mut n = 0;
        while *text.add(n) != 0 {
            n += 1;
        }
        let same = String::from_utf16_lossy(std::slice::from_raw_parts(text, n)) == sid;
        LocalFree(text.cast());
        same
    })();
    LocalFree(descriptor.cast());
    result
}
pub fn directory(path: &Path) -> io::Result<()> {
    fs::create_dir_all(path)?;
    protect(path)
}
/// Protect a folder and everything in it. Links and reparse points are
/// skipped, never followed.
pub fn tree(path: &Path) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if is_link(&meta) {
        return Ok(());
    }
    restrict(path, meta.is_dir())?;
    if meta.is_dir() {
        for child in fs::read_dir(path)? {
            tree(&child?.path())?;
        }
    }
    Ok(())
}
/// Protect the data root once, with rules its new files inherit, plus the
/// files and private folders already in it. Large folders are not walked.
pub fn secure_root(data: &Path) -> io::Result<()> {
    restrict(data, true)?;
    for entry in fs::read_dir(data)? {
        let entry = entry?;
        let Ok(meta) = entry.path().symlink_metadata() else {
            continue;
        };
        let name = entry.file_name();
        if is_link(&meta) || UNWALKED.iter().any(|n| name == *n) {
            continue;
        }
        if meta.is_file() {
            restrict(&entry.path(), false)?;
        } else if meta.is_dir() && PRIVATE_TREES.iter().any(|n| name == *n) {
            tree(&entry.path())?;
        }
    }
    Ok(())
}
pub fn create(path: &Path) -> io::Result<fs::File> {
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options.open(path)?;
    protect(path)?;
    Ok(file)
}
#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    #[test]
    fn legacy_settings_and_backup_survive_permission_migration() {
        let root = std::env::temp_dir().join(format!("dazedev-private-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        fs::write(root.join("device.json"), b"legacy-settings").unwrap();
        fs::write(root.join("device.json.bak"), b"backup").unwrap();
        tree(&root).unwrap();
        assert_eq!(
            fs::metadata(&root).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(root.join("device.json"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert_eq!(
            fs::read(root.join("device.json")).unwrap(),
            b"legacy-settings"
        );
        assert_eq!(fs::read(root.join("device.json.bak")).unwrap(), b"backup");
        fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(all(test, unix))]
mod root_tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    fn mode(path: &Path) -> u32 {
        fs::symlink_metadata(path).unwrap().permissions().mode() & 0o777
    }
    #[test]
    fn data_root_is_protected_without_walking_large_folders_or_links() {
        let root = std::env::temp_dir().join(format!("dazedev-root-{}", uuid::Uuid::new_v4()));
        let outside = root.with_extension("outside");
        fs::create_dir_all(root.join("webview/Default")).unwrap();
        fs::create_dir_all(root.join("models")).unwrap();
        fs::create_dir_all(root.join("native-sessions/runtime")).unwrap();
        fs::create_dir_all(&outside).unwrap();
        for file in [
            "device.json",
            "webview/Default/Cookies",
            "models/model.bin",
            "native-sessions/runtime/a",
        ] {
            fs::write(root.join(file), b"x").unwrap();
            fs::set_permissions(root.join(file), fs::Permissions::from_mode(0o644)).unwrap();
        }
        fs::set_permissions(&root, fs::Permissions::from_mode(0o755)).unwrap();
        fs::set_permissions(&outside, fs::Permissions::from_mode(0o755)).unwrap();
        std::os::unix::fs::symlink(&outside, root.join("linked")).unwrap();
        std::os::unix::fs::symlink(&outside, root.join("native-sessions/linked")).unwrap();
        secure_root(&root).unwrap();
        assert_eq!(mode(&root), 0o700);
        assert_eq!(mode(&root.join("device.json")), 0o600);
        assert_eq!(mode(&root.join("native-sessions/runtime/a")), 0o600);
        assert_eq!(mode(&root.join("webview/Default/Cookies")), 0o644);
        assert_eq!(mode(&root.join("models/model.bin")), 0o644);
        assert_eq!(mode(&outside), 0o755);
        // A second start changes nothing and still succeeds.
        secure_root(&root).unwrap();
        fs::remove_dir_all(&root).unwrap();
        fs::remove_dir_all(&outside).unwrap();
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{
            Authorization::{ConvertSidToStringSidW, GetNamedSecurityInfoW, SE_FILE_OBJECT},
            GetAce, ACCESS_ALLOWED_ACE, DACL_SECURITY_INFORMATION,
        },
    };
    #[test]
    fn legacy_data_is_preserved_with_only_current_user_acl() {
        let root = std::env::temp_dir().join(format!("dazedev-private-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let file = root.join("device.json");
        fs::write(&file, b"synthetic legacy settings").unwrap();
        tree(&root).unwrap();
        let name: Vec<u16> = file.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe {
            let mut acl = std::ptr::null_mut();
            let mut descriptor = std::ptr::null_mut();
            assert_eq!(
                GetNamedSecurityInfoW(
                    name.as_ptr(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    &mut acl,
                    std::ptr::null_mut(),
                    &mut descriptor
                ),
                0
            );
            assert!(!acl.is_null());
            assert_eq!((*acl).AceCount, 1);
            let mut ace = std::ptr::null_mut();
            assert_ne!(GetAce(acl, 0, &mut ace), 0);
            let allowed = &*(ace as *const ACCESS_ALLOWED_ACE);
            assert_eq!(allowed.Header.AceType, 0);
            let sid_ptr = std::ptr::addr_of!(allowed.SidStart) as *mut std::ffi::c_void;
            let mut text = std::ptr::null_mut();
            assert_ne!(ConvertSidToStringSidW(sid_ptr, &mut text), 0);
            let mut n = 0;
            while *text.add(n) != 0 {
                n += 1;
            }
            assert_eq!(
                String::from_utf16_lossy(std::slice::from_raw_parts(text, n)),
                crate::local_sessions::ipc::sid().unwrap()
            );
            LocalFree(text.cast());
            LocalFree(descriptor.cast());
        }
        assert_eq!(fs::read(file).unwrap(), b"synthetic legacy settings");
        fs::remove_dir_all(root).unwrap();
    }
}
