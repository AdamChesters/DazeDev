//! Persistent view and input pipes. The broker pushes output as it arrives,
//! so a view blocks on read instead of polling.
use super::{
    ipc::{self, Error, ErrorKind},
    parse_reply, reply, required, Client, Server, Session,
};
use crate::sync::LockExt;
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use std::{
    io::Read,
    sync::{atomic::Ordering, Arc},
    time::{Duration, Instant},
};
/// An idle view still gets an empty update this often.
pub const HEARTBEAT: Duration = Duration::from_secs(5);

/// One update for a view.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ViewUpdate {
    /// Terminal bytes. Empty for a heartbeat.
    pub data: Vec<u8>,
    pub seq: u64,
    /// `data` is a full screen; clear the view before writing it.
    pub reset: bool,
    /// False once the shell has exited. This is the last update.
    pub running: bool,
    pub pid: Option<u64>,
    /// The view was detached with `view_detach`. This is the last update.
    pub detached: bool,
}
impl ViewUpdate {
    fn parse(value: &Value) -> Result<Self, Error> {
        let data = STANDARD
            .decode(value["data"].as_str().unwrap_or(""))
            .map_err(|e| Error::new(ErrorKind::Failed, e.to_string()))?;
        Ok(Self {
            data,
            seq: value["seq"]
                .as_u64()
                .ok_or_else(|| Error::new(ErrorKind::Failed, "Invalid terminal output"))?,
            reset: value["reset"] == true,
            running: value["running"] != false,
            pid: value["pid"].as_u64(),
            detached: value["detached"] == true,
        })
    }
}

/// Output for one view, pushed by the broker over one pipe.
pub struct ViewStream {
    inner: ViewInner,
}
enum ViewInner {
    Pipe(ipc::Stream),
    /// An older broker without streams: poll it instead.
    Poll {
        client: Client,
        args: Value,
    },
    Done,
}
impl ViewStream {
    /// Blocks until output arrives, a heartbeat is due, or the view ends.
    /// After an update with `running == false` or `detached == true` the
    /// stream is finished and later calls fail with `ErrorKind::Ended`.
    pub fn next(&mut self) -> Result<ViewUpdate, Error> {
        let update = match &mut self.inner {
            ViewInner::Pipe(pipe) => ViewUpdate::parse(&parse_reply(ipc::receive(pipe)?)?)?,
            ViewInner::Poll { client, args } => {
                let started = Instant::now();
                loop {
                    let update = ViewUpdate::parse(&client.call("view_poll", args.clone())?)?;
                    args["since"] = json!(update.seq);
                    if !update.data.is_empty()
                        || update.reset
                        || !update.running
                        || started.elapsed() >= HEARTBEAT
                    {
                        break update;
                    }
                    std::thread::sleep(Duration::from_millis(30));
                }
            }
            ViewInner::Done => return Err(Error::new(ErrorKind::Ended, super::SESSION_ENDED)),
        };
        if !update.running || update.detached {
            self.inner = ViewInner::Done;
        }
        Ok(update)
    }
}

/// Input and resize for one session over one pipe. Each call waits for the
/// broker to accept it, so errors come back in order.
pub struct InputChannel {
    inner: InputInner,
}
enum InputInner {
    Pipe(ipc::Stream),
    Requests { client: Client, args: Value },
}
impl InputChannel {
    pub fn send(&mut self, bytes: &[u8]) -> Result<(), Error> {
        for chunk in bytes.chunks(65536) {
            let data = STANDARD.encode(chunk);
            match &mut self.inner {
                InputInner::Pipe(pipe) => {
                    ipc::send(pipe, &json!({"data":data}))?;
                    parse_reply(ipc::receive(pipe)?)?;
                }
                InputInner::Requests { client, args } => {
                    let mut args = args.clone();
                    args["data"] = json!(data);
                    client.call("input", args)?;
                }
            }
        }
        Ok(())
    }
    pub fn resize(&mut self, cols: u16, rows: u16) -> Result<(), Error> {
        match &mut self.inner {
            InputInner::Pipe(pipe) => {
                ipc::send(pipe, &json!({"resize":{"cols":cols,"rows":rows}}))?;
                parse_reply(ipc::receive(pipe)?)?;
            }
            InputInner::Requests { client, args } => {
                let mut args = args.clone();
                args["cols"] = json!(cols);
                args["rows"] = json!(rows);
                client.call("resize", args)?;
            }
        }
        Ok(())
    }
}

impl Client {
    fn open(&self, method: &str, args: &Value) -> Result<ipc::Stream, Error> {
        let mut pipe = self.connect(method)?;
        ipc::send(
            &mut pipe,
            &json!({"protocol":super::PROTOCOL,"host":self.host,"method":method,"args":args}),
        )?;
        Ok(pipe)
    }
    /// Open a persistent output stream for one view of a session.
    /// `since` is the last sequence number this view has shown.
    pub fn view_stream(
        &self,
        id: &str,
        viewer: &str,
        stamp: Option<&str>,
        since: Option<u64>,
    ) -> Result<ViewStream, Error> {
        let mut args = json!({"id":id,"viewer":viewer,"since":since});
        if let Some(stamp) = stamp {
            args["stamp"] = json!(stamp);
        }
        if self.legacy() {
            return Ok(ViewStream {
                inner: ViewInner::Poll {
                    client: self.clone(),
                    args,
                },
            });
        }
        let mut pipe = self.open("view_stream", &args)?;
        ipc::set_blocking(&mut pipe, true)?;
        Ok(ViewStream {
            inner: ViewInner::Pipe(pipe),
        })
    }
    /// Open a persistent input pipe for a session.
    pub fn input_channel(&self, id: &str, stamp: Option<&str>) -> Result<InputChannel, Error> {
        let mut args = json!({"id":id});
        if let Some(stamp) = stamp {
            args["stamp"] = json!(stamp);
        }
        if self.legacy() {
            return Ok(InputChannel {
                inner: InputInner::Requests {
                    client: self.clone(),
                    args,
                },
            });
        }
        let mut pipe = self.open("input_stream", &args)?;
        parse_reply(ipc::receive(&mut pipe)?)?;
        Ok(InputChannel {
            inner: InputInner::Pipe(pipe),
        })
    }
}

/// Serve one persistent pipe. Runs on its own thread.
pub(super) fn serve(
    server: &Arc<Server>,
    host: &str,
    method: &str,
    args: &Value,
    pipe: ipc::Stream,
) {
    match method {
        "view_stream" => view(server, host, args, pipe),
        _ => input(server, host, args, pipe),
    }
}
fn open_view(server: &Server, host: &str, args: &Value) -> Result<(Arc<Session>, String), String> {
    let session = server.session(host, args)?;
    let viewer = required(args, "viewer")?.to_string();
    session.touch(&viewer)?;
    Ok((session, viewer))
}
fn view(server: &Arc<Server>, host: &str, args: &Value, mut pipe: ipc::Stream) {
    let (session, viewer) = match open_view(server, host, args) {
        Ok(opened) => opened,
        Err(error) => return reply(&mut pipe, Err(error)),
    };
    if ipc::set_blocking(&mut pipe, true).is_err() {
        return;
    }
    let mut since = args["since"].as_u64();
    loop {
        let mut frame = {
            let mut o = session.output.locked();
            if since == Some(o.seq) && session.alive.load(Ordering::Acquire) {
                o = match session.changed.wait_timeout(o, HEARTBEAT) {
                    Ok((o, _)) => o,
                    Err(poisoned) => poisoned.into_inner().0,
                };
            }
            session.snapshot_locked(&o, since)
        };
        since = frame["seq"].as_u64();
        let detached = {
            let mut views = session.viewers.locked();
            match views.get_mut(&viewer) {
                Some(seen) => {
                    *seen = Instant::now();
                    false
                }
                None => true,
            }
        };
        if detached {
            frame["detached"] = json!(true);
        }
        let last = detached || frame["running"] == false;
        if ipc::send(&mut pipe, &json!({ "result": frame })).is_err() {
            return;
        }
        if last {
            break;
        }
    }
    // Closing a Windows pipe discards unread bytes. Wait for the client to
    // close its end after the last update.
    if ipc::set_blocking(&mut pipe, false).is_ok() {
        let _ = pipe.read(&mut [0]);
    }
}
fn input(server: &Arc<Server>, host: &str, args: &Value, mut pipe: ipc::Stream) {
    let session = match server.session(host, args) {
        Ok(session) => session,
        Err(error) => return reply(&mut pipe, Err(error)),
    };
    if ipc::send(&mut pipe, &json!({"result":null})).is_err()
        || ipc::set_blocking(&mut pipe, true).is_err()
    {
        return;
    }
    while let Ok(frame) = ipc::receive(&mut pipe) {
        let result = if let Some(data) = frame["data"].as_str() {
            session.input_base64(data)
        } else if frame["resize"].is_object() {
            session.resize(
                frame["resize"]["cols"].as_u64(),
                frame["resize"]["rows"].as_u64(),
            )
        } else {
            Err("Unknown input message".into())
        };
        let message = match result {
            Ok(()) => json!({"result":null}),
            Err(error) => super::error_reply(&error),
        };
        if ipc::send(&mut pipe, &message).is_err() {
            return;
        }
    }
}
