//! Restrict app-owned data without replacing or deleting existing settings.
use std::{fs, io, path::Path};

pub fn protect(path: &Path) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if meta.file_type().is_symlink() {
        return Err(io::Error::other(
            "Private storage cannot contain symbolic links",
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(
            path,
            fs::Permissions::from_mode(if meta.is_dir() { 0o700 } else { 0o600 }),
        )?;
        if fs::metadata(path)?.permissions().mode() & 0o077 != 0 {
            return Err(io::Error::other(
                "Filesystem did not enforce private permissions",
            ));
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if meta.file_attributes() & 0x400 != 0 {
            return Err(io::Error::other(
                "Private storage cannot contain reparse points",
            ));
        }
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
        let inheritance = if meta.is_dir() { "OICI" } else { "" };
        let sddl: Vec<u16> = format!("D:P(A;{inheritance};FA;;;{sid})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
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
pub fn directory(path: &Path) -> io::Result<()> {
    fs::create_dir_all(path)?;
    protect(path)
}
pub fn tree(path: &Path) -> io::Result<()> {
    protect(path)?;
    if fs::symlink_metadata(path)?.is_dir() {
        for child in fs::read_dir(path)? {
            tree(&child?.path())?;
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
