//! Lock helpers for long-running workers.
use std::sync::{Mutex, MutexGuard};

/// A panic in one worker must not stop every later user of shared state.
pub trait LockExt<T> {
    fn locked(&self) -> MutexGuard<'_, T>;
}
impl<T> LockExt<T> for Mutex<T> {
    fn locked(&self) -> MutexGuard<'_, T> {
        self.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn poisoned_lock_stays_usable() {
        let value = std::sync::Arc::new(Mutex::new(1));
        let worker = value.clone();
        let _ = std::thread::spawn(move || {
            let _guard = worker.lock().unwrap();
            panic!("worker failed");
        })
        .join();
        assert!(value.is_poisoned());
        *value.locked() += 1;
        assert_eq!(*value.locked(), 2);
    }
}
