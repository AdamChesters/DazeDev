//! Same-user, local-only framed IPC. No TCP listener or bearer secrets.
use serde_json::Value;
use std::{
    io::{Read, Write},
    path::Path,
};
pub const LIMIT: usize = 2 * 1024 * 1024;
pub fn receive(stream: &mut impl Read) -> Result<Value, String> {
    let mut size = [0; 4];
    stream.read_exact(&mut size).map_err(|e| e.to_string())?;
    let size = u32::from_le_bytes(size) as usize;
    if size > LIMIT {
        return Err("Session message too large".into());
    }
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes).map_err(|e| e.to_string())?;
    serde_json::from_slice(&bytes).map_err(|e| e.to_string())
}
pub fn send(stream: &mut impl Write, value: &Value) -> Result<(), String> {
    let bytes = serde_json::to_vec(value).map_err(|e| e.to_string())?;
    if bytes.len() > LIMIT {
        return Err("Session message too large".into());
    }
    stream
        .write_all(&(bytes.len() as u32).to_le_bytes())
        .and_then(|_| stream.write_all(&bytes))
        .and_then(|_| stream.flush())
        .map_err(|e| e.to_string())
}
#[cfg(unix)]
pub type Stream = std::os::unix::net::UnixStream;
#[cfg(unix)]
pub fn endpoint(root: &Path) -> String {
    root.join("sessions.sock").to_string_lossy().into_owned()
}
#[cfg(unix)]
pub fn connect(root: &Path) -> Result<Stream, String> {
    let s = Stream::connect(endpoint(root)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(std::time::Duration::from_secs(15)))
        .map_err(|e| e.to_string())?;
    s.set_write_timeout(Some(std::time::Duration::from_secs(15)))
        .map_err(|e| e.to_string())?;
    Ok(s)
}
#[cfg(unix)]
pub fn listen(root: &Path, handle: impl Fn(Stream) + Send + Sync + 'static) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let path = endpoint(root);
    let _ = std::fs::remove_file(&path);
    let l = std::os::unix::net::UnixListener::bind(&path).map_err(|e| e.to_string())?;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))
        .map_err(|e| e.to_string())?;
    for s in l.incoming() {
        handle(s.map_err(|e| e.to_string())?)
    }
    Ok(())
}
#[cfg(windows)]
pub struct Stream(std::fs::File);
#[cfg(windows)]
impl Read for Stream {
    fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
        let started = std::time::Instant::now();
        loop {
            match self.0.read(bytes) {
                Ok(0)
                    if !bytes.is_empty()
                        && started.elapsed() < std::time::Duration::from_secs(15) =>
                {
                    std::thread::sleep(std::time::Duration::from_millis(2))
                }
                Err(e)
                    if e.raw_os_error() == Some(232)
                        && started.elapsed() < std::time::Duration::from_secs(15) =>
                {
                    std::thread::sleep(std::time::Duration::from_millis(2))
                }
                result => return result,
            }
        }
    }
}
#[cfg(windows)]
impl Write for Stream {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        let started = std::time::Instant::now();
        loop {
            // Nonblocking pipe writes can return zero forever when a single
            // request exceeds the available buffer; write_all advances chunks.
            match self.0.write(&bytes[..bytes.len().min(4096)]) {
                Ok(0)
                    if !bytes.is_empty()
                        && started.elapsed() < std::time::Duration::from_secs(15) =>
                {
                    std::thread::sleep(std::time::Duration::from_millis(2))
                }
                Err(e)
                    if matches!(e.raw_os_error(), Some(232 | 231))
                        && started.elapsed() < std::time::Duration::from_secs(15) =>
                {
                    std::thread::sleep(std::time::Duration::from_millis(2))
                }
                result => return result,
            }
        }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
#[cfg(windows)]
pub(crate) fn sid() -> Result<String, String> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, LocalFree},
        Security::{
            Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
            TOKEN_USER,
        },
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };
    unsafe {
        let mut token = std::ptr::null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let mut n = 0;
        GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut n);
        // usize allocation supplies alignment required by TOKEN_USER.
        let mut buf = vec![
            0usize;
            (n as usize + std::mem::size_of::<usize>() - 1)
                / std::mem::size_of::<usize>()
        ];
        let ok = GetTokenInformation(token, TokenUser, buf.as_mut_ptr().cast(), n, &mut n);
        CloseHandle(token);
        if ok == 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let mut ptr = std::ptr::null_mut();
        if ConvertSidToStringSidW((*(buf.as_ptr() as *const TOKEN_USER)).User.Sid, &mut ptr) == 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let mut len = 0;
        while *ptr.add(len) != 0 {
            len += 1
        }
        let s = String::from_utf16_lossy(std::slice::from_raw_parts(ptr, len));
        LocalFree(ptr.cast());
        Ok(s)
    }
}
#[cfg(windows)]
pub fn endpoint(root: &Path) -> String {
    use sha2::{Digest, Sha256};
    format!(
        r"\\.\pipe\DazeDev-sessions-v1-{:x}",
        Sha256::digest(format!("{}:{}", root.display(), sid().unwrap_or_default()))
    )
}
#[cfg(windows)]
pub fn connect(root: &Path) -> Result<Stream, String> {
    use windows_sys::Win32::System::Pipes::WaitNamedPipeW;
    let path = endpoint(root);
    let wide: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::Pipes::{SetNamedPipeHandleState, PIPE_NOWAIT};
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
    let file = loop {
        match std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
        {
            Ok(file) => break file,
            Err(error)
                if error.raw_os_error() == Some(231) && std::time::Instant::now() < deadline =>
            {
                // Another caller can take the instance after WaitNamedPipe returns.
                unsafe {
                    WaitNamedPipeW(wide.as_ptr(), 100);
                }
                std::thread::sleep(std::time::Duration::from_millis(2));
            }
            Err(error) => return Err(error.to_string()),
        }
    };
    let mode = PIPE_NOWAIT;
    if unsafe {
        SetNamedPipeHandleState(
            file.as_raw_handle(),
            &mode,
            std::ptr::null(),
            std::ptr::null(),
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(Stream(file))
}
#[cfg(windows)]
pub fn listen(root: &Path, handle: impl Fn(Stream) + Send + Sync + 'static) -> Result<(), String> {
    use std::os::windows::io::FromRawHandle;
    use windows_sys::Win32::{
        Foundation::{
            CloseHandle, GetLastError, LocalFree, ERROR_PIPE_CONNECTED, INVALID_HANDLE_VALUE,
        },
        Security::{
            Authorization::{
                ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
            },
            SECURITY_ATTRIBUTES,
        },
        Storage::FileSystem::PIPE_ACCESS_DUPLEX,
        System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS,
            PIPE_TYPE_BYTE, PIPE_WAIT,
        },
    };
    let name: Vec<u16> = endpoint(root).encode_utf16().chain(Some(0)).collect();
    let sddl: Vec<u16> = format!("D:P(A;;GA;;;{})", sid()?)
        .encode_utf16()
        .chain(Some(0))
        .collect();
    unsafe {
        let mut descriptor = std::ptr::null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            std::ptr::null_mut(),
        ) == 0
        {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor,
            bInheritHandle: 0,
        };
        loop {
            let h = CreateNamedPipeW(
                name.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                32,
                65536,
                65536,
                3000,
                &sa,
            );
            if h == INVALID_HANDLE_VALUE {
                LocalFree(descriptor);
                return Err(std::io::Error::last_os_error().to_string());
            }
            if ConnectNamedPipe(h, std::ptr::null_mut()) == 0
                && GetLastError() != ERROR_PIPE_CONNECTED
            {
                CloseHandle(h);
                continue;
            }
            let mode = windows_sys::Win32::System::Pipes::PIPE_NOWAIT;
            if windows_sys::Win32::System::Pipes::SetNamedPipeHandleState(
                h,
                &mode,
                std::ptr::null(),
                std::ptr::null(),
            ) == 0
            {
                CloseHandle(h);
                continue;
            }
            handle(Stream(std::fs::File::from_raw_handle(h)));
        }
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
    use windows_sys::Win32::{
        Foundation::{CloseHandle, INVALID_HANDLE_VALUE},
        Storage::FileSystem::PIPE_ACCESS_DUPLEX,
        System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_NOWAIT, PIPE_TYPE_BYTE,
        },
    };

    #[test]
    fn reconnect_waits_for_busy_pipe_instance() {
        let root = std::env::temp_dir().join(format!("pipe-busy-{}", uuid::Uuid::new_v4()));
        let path = endpoint(&root);
        let name: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
        let handle = unsafe {
            CreateNamedPipeW(
                name.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_NOWAIT,
                1,
                4096,
                4096,
                100,
                std::ptr::null(),
            )
        };
        assert_ne!(handle, INVALID_HANDLE_VALUE);
        let occupied = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .unwrap();
        assert_eq!(
            std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(&path)
                .unwrap_err()
                .raw_os_error(),
            Some(231)
        );
        let raw = handle as usize;
        let (done, wait) = std::sync::mpsc::channel();
        let server = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(200));
            drop(occupied);
            unsafe {
                assert_ne!(DisconnectNamedPipe(raw as _), 0);
                ConnectNamedPipe(raw as _, std::ptr::null_mut());
            }
            // Keep the released instance available until the client has opened it.
            let _ = wait.recv_timeout(std::time::Duration::from_secs(4));
            unsafe {
                CloseHandle(raw as _);
            }
        });
        let started = std::time::Instant::now();
        let reopened = connect(&root);
        let _ = done.send(());
        server.join().unwrap();
        assert!(reopened.is_ok(), "{}", reopened.err().unwrap_or_default());
        assert!(started.elapsed() >= std::time::Duration::from_millis(150));
    }
}
