//! Sample-bounded audio buffering. The producer never waits for inference.
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
pub const MAX_BUFFERED_SAMPLES: usize = 16000 * 60 * 15; // 57.6 MB, 15 minutes mono f32.
#[derive(Clone, Default)]
pub struct AudioBudget(Arc<AtomicUsize>);
pub struct AudioFrame {
    pub samples: Vec<f32>,
    budget: AudioBudget,
}
impl AudioBudget {
    pub fn pending(&self) -> usize {
        self.0.load(Ordering::Relaxed)
    }
    pub fn reserve(&self, samples: Vec<f32>) -> Result<AudioFrame, String> {
        let len = samples.len();
        self.0.fetch_update(Ordering::AcqRel, Ordering::Relaxed, |n| n.checked_add(len).filter(|n| *n <= MAX_BUFFERED_SAMPLES))
            .map_err(|_| "Audio buffer is full (15 minutes pending). Stop recording to finish the buffered transcription.".to_string())?;
        Ok(AudioFrame {
            samples,
            budget: self.clone(),
        })
    }
}
impl Drop for AudioFrame {
    fn drop(&mut self) {
        self.budget
            .0
            .fetch_sub(self.samples.len(), Ordering::AcqRel);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn slow_consumer_preserves_over_fifteen_seconds() {
        let budget = AudioBudget::default();
        let frames: Vec<_> = (0..600)
            .map(|i| budget.reserve(vec![i as f32; 3200]).unwrap())
            .collect();
        assert_eq!(budget.pending(), 16000 * 120);
        for (i, frame) in frames.iter().enumerate() {
            assert!(frame.samples.iter().all(|s| *s == i as f32));
        }
        drop(frames);
        assert_eq!(budget.pending(), 0);
    }
    #[test]
    fn bounded_reservations_are_released_on_failed_send_or_cancel() {
        let budget = AudioBudget::default();
        let frame = budget.reserve(vec![0.; MAX_BUFFERED_SAMPLES]).unwrap();
        assert!(budget.reserve(vec![0.]).is_err());
        drop(frame);
        assert!(budget.reserve(vec![0.]).is_ok());
        assert_eq!(budget.pending(), 0);
    }
}
