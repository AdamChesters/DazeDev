//! Built-in SSH client: one authenticated connection per host carries the
//! management channel and every terminal.
pub mod config;
pub mod known_hosts;
mod session;

pub use session::{
    runtime, tailscale_peer, ChannelIo, OutputReader, Prompts, Session, Target, SYSTEM_SSH,
};
