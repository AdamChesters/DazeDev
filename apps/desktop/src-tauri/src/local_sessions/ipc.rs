//! Same-user, local-only framed IPC. No TCP listener or bearer secrets.
use serde_json::Value;
use std::{
    io::{Read, Write},
    path::Path,
    time::Duration,
};
pub const LIMIT: usize = 2 * 1024 * 1024;
/// How long a request may wait for the other side before it times out.
pub const TIMEOUT: Duration = Duration::from_secs(15);

/// What went wrong, so callers can tell an ended session from a missing broker.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    /// The session ended or no longer exists.
    Ended,
    /// The broker is not running, or closed the connection.
    Unavailable,
    /// The broker did not answer in time.
    Timeout,
    /// Any other failure, including errors the broker reports.
    Failed,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Error {
    pub kind: ErrorKind,
    pub message: String,
}
impl Error {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for Error {}
impl From<Error> for String {
    fn from(error: Error) -> Self {
        error.message
    }
}
impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        use std::io::ErrorKind as Io;
        let kind = match (error.kind(), error.raw_os_error()) {
            // Windows: file not found, pipe busy, broken pipe, pipe closing,
            // pipe not connected.
            (_, Some(2 | 231 | 109 | 232 | 233)) if cfg!(windows) => ErrorKind::Unavailable,
            (
                Io::NotFound
                | Io::ConnectionRefused
                | Io::ConnectionReset
                | Io::ConnectionAborted
                | Io::BrokenPipe
                | Io::UnexpectedEof
                | Io::NotConnected,
                _,
            ) => ErrorKind::Unavailable,
            (Io::TimedOut | Io::WouldBlock, _) => ErrorKind::Timeout,
            _ => ErrorKind::Failed,
        };
        Self::new(kind, error.to_string())
    }
}

pub fn receive(stream: &mut impl Read) -> Result<Value, Error> {
    let mut size = [0; 4];
    stream.read_exact(&mut size)?;
    let size = u32::from_le_bytes(size) as usize;
    if size > LIMIT {
        return Err(Error::new(ErrorKind::Failed, "Session message too large"));
    }
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes)?;
    serde_json::from_slice(&bytes).map_err(|e| Error::new(ErrorKind::Failed, e.to_string()))
}
pub fn send(stream: &mut impl Write, value: &Value) -> Result<(), Error> {
    let bytes =
        serde_json::to_vec(value).map_err(|e| Error::new(ErrorKind::Failed, e.to_string()))?;
    if bytes.len() > LIMIT {
        return Err(Error::new(ErrorKind::Failed, "Session message too large"));
    }
    stream
        .write_all(&(bytes.len() as u32).to_le_bytes())
        .and_then(|_| stream.write_all(&bytes))
        .and_then(|_| stream.flush())?;
    Ok(())
}

#[cfg(unix)]
pub type Stream = std::os::unix::net::UnixStream;
#[cfg(unix)]
pub fn endpoint(root: &Path) -> String {
    endpoint_version(root, super::PROTOCOL)
}
/// Each protocol version has its own endpoint, so an older broker can keep
/// serving its sessions next to a newer one.
#[cfg(unix)]
pub fn endpoint_version(root: &Path, version: u64) -> String {
    let name = if version == super::PROTOCOL {
        "sessions.sock".to_string()
    } else {
        format!("sessions-v{version}.sock")
    };
    root.join(name).to_string_lossy().into_owned()
}
#[cfg(unix)]
pub fn connect_version(root: &Path, version: u64) -> Result<Stream, Error> {
    let mut s = Stream::connect(endpoint_version(root, version))?;
    set_blocking(&mut s, false)?;
    Ok(s)
}
/// Blocking streams wait for data without a deadline; others time out.
#[cfg(unix)]
pub fn set_blocking(stream: &mut Stream, blocking: bool) -> Result<(), Error> {
    let timeout = (!blocking).then_some(TIMEOUT);
    stream.set_read_timeout(timeout)?;
    stream.set_write_timeout(timeout)?;
    Ok(())
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
        let mut s = s.map_err(|e| e.to_string())?;
        if set_blocking(&mut s, false).is_ok() {
            handle(s)
        }
    }
    Ok(())
}

#[cfg(windows)]
pub struct Stream {
    file: std::fs::File,
    blocking: bool,
}
#[cfg(windows)]
const ERROR_NO_DATA: i32 = 232;
#[cfg(windows)]
impl Read for Stream {
    fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
        if self.blocking {
            // Ok(0) is end of file: the other side closed the pipe.
            return self.file.read(bytes);
        }
        let started = std::time::Instant::now();
        loop {
            match self.file.read(bytes) {
                // A nonblocking pipe with nothing to read reports ERROR_NO_DATA.
                Err(e) if e.raw_os_error() == Some(ERROR_NO_DATA) => {
                    if started.elapsed() >= TIMEOUT {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "Local session manager did not answer",
                        ));
                    }
                    std::thread::sleep(Duration::from_millis(2))
                }
                result => return result,
            }
        }
    }
}
#[cfg(windows)]
impl Write for Stream {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if self.blocking {
            return self.file.write(bytes);
        }
        let started = std::time::Instant::now();
        loop {
            // Nonblocking pipe writes can return zero forever when a single
            // request exceeds the available buffer; write_all advances chunks.
            match self.file.write(&bytes[..bytes.len().min(4096)]) {
                Ok(0) if !bytes.is_empty() => {
                    if started.elapsed() >= TIMEOUT {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "Local session manager is not reading",
                        ));
                    }
                    std::thread::sleep(Duration::from_millis(2))
                }
                // On writes, ERROR_NO_DATA means the pipe is being closed.
                Err(e) if e.raw_os_error() == Some(ERROR_NO_DATA) => {
                    return Err(std::io::Error::new(std::io::ErrorKind::BrokenPipe, e))
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
pub fn set_blocking(stream: &mut Stream, blocking: bool) -> Result<(), Error> {
    set_mode(&stream.file, blocking)?;
    stream.blocking = blocking;
    Ok(())
}
#[cfg(windows)]
fn set_mode(file: &std::fs::File, blocking: bool) -> std::io::Result<()> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::Pipes::{
        SetNamedPipeHandleState, PIPE_NOWAIT, PIPE_READMODE_BYTE, PIPE_WAIT,
    };
    let mode = PIPE_READMODE_BYTE | if blocking { PIPE_WAIT } else { PIPE_NOWAIT };
    if unsafe {
        SetNamedPipeHandleState(
            file.as_raw_handle(),
            &mode,
            std::ptr::null(),
            std::ptr::null(),
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}
/// The TOKEN_USER of a token, in a usize buffer for alignment.
#[cfg(windows)]
unsafe fn token_user(token: windows_sys::Win32::Foundation::HANDLE) -> Result<Vec<usize>, String> {
    use windows_sys::Win32::Security::{GetTokenInformation, TokenUser};
    let mut n = 0;
    GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut n);
    let mut buf = vec![
        0usize;
        (n as usize + std::mem::size_of::<usize>() - 1)
            / std::mem::size_of::<usize>()
    ];
    if GetTokenInformation(token, TokenUser, buf.as_mut_ptr().cast(), n, &mut n) == 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(buf)
}
#[cfg(windows)]
unsafe fn process_user(
    process: windows_sys::Win32::Foundation::HANDLE,
) -> Result<Vec<usize>, String> {
    use windows_sys::Win32::{
        Foundation::CloseHandle, Security::TOKEN_QUERY, System::Threading::OpenProcessToken,
    };
    let mut token = std::ptr::null_mut();
    if OpenProcessToken(process, TOKEN_QUERY, &mut token) == 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    let user = token_user(token);
    CloseHandle(token);
    user
}
#[cfg(windows)]
pub(crate) fn sid() -> Result<String, String> {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{Authorization::ConvertSidToStringSidW, TOKEN_USER},
        System::Threading::GetCurrentProcess,
    };
    unsafe {
        let buf = process_user(GetCurrentProcess())?;
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
/// Refuse a pipe whose server process runs as another user.
#[cfg(windows)]
fn verify_server_owner(file: &std::fs::File) -> Result<(), Error> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        Security::{EqualSid, TOKEN_USER},
        System::{
            Pipes::GetNamedPipeServerProcessId,
            Threading::{GetCurrentProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
        },
    };
    let refused = |detail: String| {
        Error::new(
            ErrorKind::Failed,
            format!("Local session pipe is not owned by you; refusing to use it ({detail})"),
        )
    };
    unsafe {
        let mut pid = 0u32;
        if GetNamedPipeServerProcessId(file.as_raw_handle(), &mut pid) == 0 {
            return Err(refused(std::io::Error::last_os_error().to_string()));
        }
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return Err(refused(std::io::Error::last_os_error().to_string()));
        }
        let server = process_user(process);
        CloseHandle(process);
        let server = server.map_err(refused)?;
        let current = process_user(GetCurrentProcess()).map_err(refused)?;
        let same = EqualSid(
            (*(server.as_ptr() as *const TOKEN_USER)).User.Sid,
            (*(current.as_ptr() as *const TOKEN_USER)).User.Sid,
        );
        if same == 0 {
            return Err(refused("different user".into()));
        }
    }
    Ok(())
}
#[cfg(windows)]
pub fn endpoint(root: &Path) -> String {
    endpoint_version(root, super::PROTOCOL)
}
/// Each protocol version has its own pipe, so an older broker can keep
/// serving its sessions next to a newer one.
#[cfg(windows)]
pub fn endpoint_version(root: &Path, version: u64) -> String {
    use sha2::{Digest, Sha256};
    format!(
        r"\\.\pipe\DazeDev-sessions-v{version}-{:x}",
        Sha256::digest(format!("{}:{}", root.display(), sid().unwrap_or_default()))
    )
}
#[cfg(windows)]
pub fn connect_version(root: &Path, version: u64) -> Result<Stream, Error> {
    use windows_sys::Win32::System::Pipes::WaitNamedPipeW;
    let path = endpoint_version(root, version);
    let wide: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
    let deadline = std::time::Instant::now() + Duration::from_secs(3);
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
                std::thread::sleep(Duration::from_millis(2));
            }
            Err(error) => return Err(error.into()),
        }
    };
    verify_server_owner(&file)?;
    set_mode(&file, false)?;
    Ok(Stream {
        file,
        blocking: false,
    })
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
        Storage::FileSystem::{FILE_FLAG_FIRST_PIPE_INSTANCE, PIPE_ACCESS_DUPLEX},
        System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS,
            PIPE_TYPE_BYTE, PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
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
        // The first instance must create the pipe. If the name already
        // exists, another process got there first and we must not join it.
        let mut first = true;
        loop {
            let h = CreateNamedPipeW(
                name.as_ptr(),
                PIPE_ACCESS_DUPLEX
                    | if first {
                        FILE_FLAG_FIRST_PIPE_INSTANCE
                    } else {
                        0
                    },
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                PIPE_UNLIMITED_INSTANCES,
                65536,
                65536,
                3000,
                &sa,
            );
            if h == INVALID_HANDLE_VALUE {
                let error = std::io::Error::last_os_error();
                LocalFree(descriptor);
                return Err(if first {
                    format!("Local session pipe is already in use by another program: {error}")
                } else {
                    error.to_string()
                });
            }
            first = false;
            if ConnectNamedPipe(h, std::ptr::null_mut()) == 0
                && GetLastError() != ERROR_PIPE_CONNECTED
            {
                CloseHandle(h);
                continue;
            }
            let file = std::fs::File::from_raw_handle(h);
            if set_mode(&file, false).is_err() {
                continue;
            }
            handle(Stream {
                file,
                blocking: false,
            });
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
        let reopened = connect_version(&root, crate::local_sessions::PROTOCOL);
        let _ = done.send(());
        server.join().unwrap();
        assert!(reopened.is_ok(), "{:?}", reopened.err());
        assert!(started.elapsed() >= std::time::Duration::from_millis(150));
    }
}
