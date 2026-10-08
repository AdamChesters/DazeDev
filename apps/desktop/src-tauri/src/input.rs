//! Only the configured shortcut is sampled. No typing history is collected.
use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Binding {
    pub key: i32,
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
    pub meta: bool,
}
impl Binding {
    pub fn validate(&self) -> Result<(), String> {
        if !(1..=254).contains(&self.key) || [16, 17, 18, 91, 92].contains(&self.key) {
            return Err("Choose a key or mouse button, not just a modifier.".into());
        }
        Ok(())
    }
    pub fn pressed(&self, down: impl Fn(i32) -> bool) -> bool {
        down(self.key)
            && down(17) == self.ctrl
            && down(18) == self.alt
            && down(16) == self.shift
            && (down(91) || down(92)) == self.meta
    }
}
pub struct Monitor {
    binding: Arc<Mutex<(u64, Option<Binding>)>>,
    stop: Arc<AtomicBool>,
}
impl Monitor {
    pub fn start(callback: impl Fn(bool) + Send + 'static) -> Self {
        let monitor = Self {
            binding: Default::default(),
            stop: Default::default(),
        };
        #[cfg(windows)]
        {
            let binding = monitor.binding.clone();
            let stop = monitor.stop.clone();
            std::thread::spawn(move || {
                let mut held = true;
                let mut emitted_down = false;
                let mut generation = 0;
                while !stop.load(Ordering::Relaxed) {
                    let (version, active) = {
                        let value = binding.lock().unwrap();
                        (
                            value.0,
                            value.1.as_ref().is_some_and(|b| {
                                b.pressed(|k| unsafe {
                            windows_sys::Win32::UI::Input::KeyboardAndMouse::GetAsyncKeyState(k) < 0
                        })
                            }),
                        )
                    };
                    if version != generation {
                        generation = version;
                        held = true;
                    }
                    if active && !held {
                        callback(true);
                        emitted_down = true;
                    }
                    if !active && emitted_down {
                        callback(false);
                        emitted_down = false;
                    }
                    held = active;
                    std::thread::sleep(std::time::Duration::from_millis(15));
                }
            });
        }
        #[cfg(not(windows))]
        let _ = callback;
        monitor
    }
    pub fn configure(&self, binding: Option<Binding>) -> Result<bool, String> {
        if let Some(b) = &binding {
            b.validate()?;
        }
        let mut value = self.binding.lock().unwrap();
        value.0 += 1;
        value.1 = binding;
        Ok(cfg!(windows))
    }
    pub fn shutdown(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_modifiers_and_mouse_buttons() {
        let b = Binding {
            key: 5,
            ctrl: true,
            alt: false,
            shift: false,
            meta: false,
        };
        assert!(b.pressed(|k| [5, 17].contains(&k)));
        assert!(!b.pressed(|k| [5, 17, 16].contains(&k)));
        assert!(!b.pressed(|k| k == 17));
        assert!(b.validate().is_ok());
        assert!(Binding { key: 17, ..b }.validate().is_err());
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        keybd_event, mouse_event, KEYEVENTF_KEYUP, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP,
    };
    #[test]
    fn real_global_keyboard_and_mouse_shortcuts() {
        let (tx, rx) = std::sync::mpsc::channel();
        let monitor = Monitor::start(move |pressed| {
            let _ = tx.send(pressed);
        });
        let pause = || std::thread::sleep(std::time::Duration::from_millis(90));
        monitor
            .configure(Some(Binding {
                key: 135,
                ctrl: false,
                alt: false,
                shift: false,
                meta: false,
            }))
            .unwrap();
        pause();
        unsafe {
            keybd_event(135, 0, 0, 0);
        }
        pause();
        unsafe {
            keybd_event(135, 0, KEYEVENTF_KEYUP, 0);
        }
        assert_eq!(
            rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
            true
        );
        assert_eq!(
            rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
            false
        );
        assert!(rx.try_recv().is_err());
        monitor
            .configure(Some(Binding {
                key: 5,
                ctrl: false,
                alt: false,
                shift: false,
                meta: false,
            }))
            .unwrap();
        pause();
        unsafe {
            mouse_event(MOUSEEVENTF_XDOWN, 0, 0, 1, 0);
        }
        pause();
        unsafe {
            mouse_event(MOUSEEVENTF_XUP, 0, 0, 1, 0);
        }
        assert_eq!(
            rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
            true
        );
        assert_eq!(
            rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
            false
        );
        assert!(rx.try_recv().is_err());
        monitor.configure(None).unwrap();
        monitor.shutdown();
    }
}
