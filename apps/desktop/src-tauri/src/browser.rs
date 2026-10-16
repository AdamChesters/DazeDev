use crate::sync::LockExt;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::IpAddr,
    sync::Mutex,
    time::{Duration, Instant},
};

pub fn validate_url(value: &str) -> Result<String, String> {
    if value.is_empty()
        || value.len() > 8192
        || value.chars().any(|c| c.is_control() || c.is_whitespace())
    {
        return Err("Expected one HTTP or HTTPS link without control characters".into());
    }
    let url = reqwest::Url::parse(value).map_err(|_| "Invalid web link")?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("Only HTTP and HTTPS links without embedded credentials can be opened".into());
    }
    let host = url
        .host_str()
        .unwrap()
        .trim_matches(['[', ']'])
        .trim_end_matches('.')
        .to_ascii_lowercase();
    let local = host == "localhost" || host.ends_with(".localhost") || host.parse::<IpAddr>().map(|ip| ip.is_loopback() || ip.is_unspecified() || matches!(ip, IpAddr::V6(v6) if v6.to_ipv4_mapped().map(|v4| v4.is_loopback() || v4.is_unspecified()).unwrap_or(false))).unwrap_or(false);
    if local {
        return Err("This link points to the remote host itself. Localhost/OAuth forwarding is not available; use the program's device-code or remote login flow.".into());
    }
    Ok(url.into())
}

// Windows shell handlers may require STA COM. Use a fresh thread rather than
// changing the apartment of a shared runtime worker or the UI thread.
#[cfg(any(all(windows, feature = "desktop"), test))]
fn on_opener_thread<T: Send + 'static>(
    operation: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    std::thread::Builder::new()
        .name("dazedev-browser".into())
        .spawn(move || {
            #[cfg(windows)]
            let _apartment = StaApartment::initialize()?;
            operation()
        })
        .map_err(|error| format!("Could not start browser launcher: {error}"))?
        .join()
        .map_err(|_| "Browser launcher failed".to_string())?
}

#[cfg(all(windows, any(feature = "desktop", test)))]
struct StaApartment;
#[cfg(all(windows, any(feature = "desktop", test)))]
impl StaApartment {
    fn initialize() -> Result<Self, String> {
        use windows_sys::Win32::System::Com::{
            CoInitializeEx, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE,
        };
        let result = unsafe {
            CoInitializeEx(
                std::ptr::null(),
                (COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) as u32,
            )
        };
        if result < 0 {
            return Err(format!(
                "Could not initialize browser launcher COM (0x{:08X})",
                result as u32
            ));
        }
        Ok(Self)
    }
}
#[cfg(all(windows, any(feature = "desktop", test)))]
impl Drop for StaApartment {
    fn drop(&mut self) {
        unsafe { windows_sys::Win32::System::Com::CoUninitialize() };
    }
}

#[cfg(feature = "desktop")]
pub fn launch_url(url: String) -> Result<(), String> {
    let parsed = reqwest::Url::parse(&url).map_err(|_| "Invalid link".to_string())?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err("Only web links can be opened".into());
    }
    #[cfg(windows)]
    {
        let url = parsed.to_string();
        on_opener_thread(move || {
            use windows_sys::Win32::UI::{
                Shell::{
                    ShellExecuteExW, SEE_MASK_FLAG_NO_UI, SEE_MASK_NOASYNC, SHELLEXECUTEINFOW,
                },
                WindowsAndMessaging::SW_SHOWNORMAL,
            };
            let wide: Vec<u16> = url.encode_utf16().chain(std::iter::once(0)).collect();
            let mut info = SHELLEXECUTEINFOW {
                cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
                // Report OS refusal to the app instead of showing a hidden shell
                // error dialog. Finish shell dispatch before the STA thread exits.
                fMask: SEE_MASK_FLAG_NO_UI | SEE_MASK_NOASYNC,
                lpFile: wide.as_ptr(),
                nShow: SW_SHOWNORMAL,
                ..Default::default()
            };
            if unsafe { ShellExecuteExW(&mut info) } == 0 {
                return Err(format!(
                    "Could not open link: {}",
                    std::io::Error::last_os_error()
                ));
            }
            Ok(())
        })
    }
    #[cfg(not(windows))]
    {
        tauri_plugin_opener::open_url(url, None::<&str>)
            .map_err(|error| format!("Could not open link: {error}"))
    }
}

pub struct Requests {
    pub nonce: String,
    recent: Mutex<VecDeque<(String, Instant)>>,
}
impl Requests {
    pub fn new(nonce: String) -> Self {
        Self {
            nonce,
            recent: Mutex::new(VecDeque::new()),
        }
    }
    pub fn reserve(&self, nonce: &str, id: &str) -> Result<(), String> {
        if nonce != self.nonce || uuid::Uuid::parse_str(id).is_err() {
            return Err("Browser connection has expired".into());
        }
        let mut recent = self.recent.locked();
        while recent
            .front()
            .map(|(_, at)| at.elapsed() >= Duration::from_secs(60))
            .unwrap_or(false)
        {
            recent.pop_front();
        }
        if recent.iter().any(|(seen, _)| seen == id) {
            return Err("Browser request already handled".into());
        }
        if recent.len() >= 8 {
            return Err("Too many browser requests. Wait a minute and try again.".into());
        }
        recent.push_back((id.into(), Instant::now()));
        Ok(())
    }
    pub fn claim(&self, nonce: &str, id: &str, url: &str) -> Result<String, String> {
        self.reserve(nonce, id)?;
        validate_url(url)
    }
}

/// The answer for a link the user did not allow, or did not answer in time.
pub const NOT_OPENED: &str = "The link was not opened";
/// How long a link request waits for the user.
pub const PROMPT_TIMEOUT: Duration = Duration::from_secs(120);

/// A host's request to open a link, waiting for the user's decision.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Prompt {
    pub host: String,
    pub nonce: String,
    pub id: String,
    pub url: String,
}
/// Links waiting for a decision, and hosts trusted until the app restarts.
#[derive(Default)]
pub struct Prompts {
    pending: Mutex<HashMap<String, Prompt>>,
    trusted: Mutex<HashSet<String>>,
}
impl Prompts {
    pub fn trusted(&self, host: &str) -> bool {
        self.trusted.locked().contains(host)
    }
    pub fn trust(&self, host: &str) {
        self.trusted.locked().insert(host.into());
    }
    /// Store a request and return its ID, or refuse when too many wait.
    pub fn add(&self, prompt: Prompt) -> Result<String, String> {
        let mut pending = self.pending.locked();
        if pending.len() >= 32 {
            return Err(NOT_OPENED.into());
        }
        let id = uuid::Uuid::new_v4().to_string();
        pending.insert(id.clone(), prompt);
        Ok(id)
    }
    /// Remove a request. Only the first decision or the timeout gets it.
    pub fn take(&self, request: &str) -> Option<Prompt> {
        self.pending.locked().remove(request)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prompts_are_answered_once_and_trust_is_per_host() {
        let prompts = Prompts::default();
        let prompt = Prompt {
            host: "host-a".into(),
            nonce: "nonce".into(),
            id: "id".into(),
            url: "https://example.test/".into(),
        };
        let request = prompts.add(prompt.clone()).unwrap();
        assert_eq!(prompts.take(&request), Some(prompt));
        assert_eq!(prompts.take(&request), None);
        assert!(!prompts.trusted("host-a"));
        prompts.trust("host-a");
        assert!(prompts.trusted("host-a"));
        assert!(!prompts.trusted("host-b"));
        let flood: Vec<_> = (0..40)
            .map(|_| {
                prompts.add(Prompt {
                    host: "host-b".into(),
                    nonce: String::new(),
                    id: String::new(),
                    url: String::new(),
                })
            })
            .collect();
        assert_eq!(flood.iter().filter(|r| r.is_ok()).count(), 32);
    }
    #[test]
    fn opener_thread_returns_real_failures_and_uses_another_thread() {
        let caller = std::thread::current().id();
        assert_ne!(
            on_opener_thread(|| Ok(std::thread::current().id())).unwrap(),
            caller
        );
        assert_eq!(
            on_opener_thread::<()>(|| Err("OS refused launch".into())).unwrap_err(),
            "OS refused launch"
        );
    }
    #[cfg(windows)]
    #[test]
    fn opener_thread_initializes_sta_com() {
        on_opener_thread(|| {
            use windows_sys::Win32::System::Com::{
                CoGetApartmentType, APTTYPE_MAINSTA, APTTYPE_STA,
            };
            let mut apartment = 0;
            let mut qualifier = 0;
            assert!(unsafe { CoGetApartmentType(&mut apartment, &mut qualifier) } >= 0);
            assert!(apartment == APTTYPE_STA || apartment == APTTYPE_MAINSTA);
            Ok(())
        })
        .unwrap();
    }
    #[test]
    fn accepts_web_links_but_not_commands_credentials_or_remote_loopback() {
        assert_eq!(
            validate_url("https://example.com/login?a=1&b=2").unwrap(),
            "https://example.com/login?a=1&b=2"
        );
        for value in [
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://user:secret@example.com/",
            "https://example.com/\n",
            "http://localhost:1234",
            "http://a.localhost/",
            "http://127.1/",
            "http://2130706433/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[::ffff:127.0.0.1]/",
        ] {
            assert!(validate_url(value).is_err(), "{value}");
        }
    }
    #[test]
    fn rejects_stale_duplicate_and_excess_requests() {
        let state = Requests::new("connection".into());
        let id = uuid::Uuid::new_v4().simple().to_string();
        assert!(state.claim("other", &id, "https://example.com").is_err());
        assert!(state
            .claim("connection", &id, "https://example.com")
            .is_ok());
        assert!(state
            .claim("connection", &id, "https://example.com")
            .is_err());
        for _ in 0..7 {
            assert!(state
                .claim(
                    "connection",
                    &uuid::Uuid::new_v4().to_string(),
                    "https://example.com"
                )
                .is_ok());
        }
        assert!(state
            .claim(
                "connection",
                &uuid::Uuid::new_v4().to_string(),
                "https://example.com"
            )
            .is_err());
    }
}
