//! Feedback goes to a relay. Never distribute a Slack webhook secret with the app.
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::time::Duration;

const SUPPORT_CONTENT: &str = include_str!("../../donate/content.json");

fn default_endpoint() -> Result<String, String> {
    let content: serde_json::Value = serde_json::from_str(SUPPORT_CONTENT)
        .map_err(|_| "Feedback service is not configured correctly.")?;
    content["feedback"]["endpoint"]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "Feedback service is not configured correctly.".into())
}
const MAX_ACK_BYTES: u64 = 1024;
const UNCONFIRMED: &str = "Delivery could not be confirmed. Please try again later.";

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Feedback {
    pub name: String,
    pub email: String,
    pub message: String,
}

impl Feedback {
    fn validate(&mut self) -> Result<(), String> {
        self.name = self.name.trim().to_owned();
        self.email = self.email.trim().to_owned();
        self.message = self.message.trim().to_owned();
        if self.name.is_empty()
            || self.name.chars().count() > 100
            || self.name.chars().any(char::is_control)
        {
            return Err("Enter a name of up to 100 characters.".into());
        }
        let parts: Vec<_> = self.email.split('@').collect();
        if self.email.len() > 254
            || parts.len() != 2
            || parts.iter().any(|part| part.is_empty())
            || self
                .email
                .chars()
                .any(|c| c.is_whitespace() || c.is_control())
        {
            return Err("Enter a valid email address.".into());
        }
        if self.message.is_empty() || self.message.chars().count() > 4000 {
            return Err("Enter a message of up to 4,000 characters.".into());
        }
        Ok(())
    }
}

fn relay_url(endpoint: &str) -> Result<reqwest::Url, String> {
    let url = reqwest::Url::parse(endpoint)
        .map_err(|_| "Feedback service is not configured correctly.")?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.host_str().is_some_and(|host| {
            matches!(
                host.trim_end_matches('.'),
                "hooks.slack.com" | "hooks.slack-gov.com"
            )
        })
    {
        return Err("Feedback service is not configured correctly.".into());
    }
    Ok(url)
}

fn relay_client() -> Result<reqwest::blocking::Client, String> {
    let builder = crate::local_http::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(20))
        .user_agent(concat!("DazeDev/", env!("CARGO_PKG_VERSION")));
    // Loopback fixtures must not send test data through an inherited proxy.
    #[cfg(test)]
    let builder = builder.no_proxy();
    builder
        .build()
        .map_err(|_| "Feedback service could not be reached.".into())
}

fn payload(feedback: &Feedback) -> serde_json::Value {
    serde_json::json!({
        "app": "DazeDev",
        "name": feedback.name,
        "email": feedback.email,
        "message": feedback.message,
        "version": env!("CARGO_PKG_VERSION"),
    })
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Acknowledgment {
    ok: bool,
}

fn confirm(status: u16, content_type: Option<&str>, body: impl Read) -> Result<(), String> {
    match status {
        200 => {}
        400 => {
            return Err("Feedback was not accepted. Check your contact details and message.".into())
        }
        413 => return Err("Feedback is too large. Please shorten your message.".into()),
        429 => return Err("Too many requests. Please wait a minute before retrying.".into()),
        404 | 503 => {
            return Err("Feedback delivery is not available yet. Please try again later.".into())
        }
        _ => return Err(UNCONFIRMED.into()),
    }
    if !content_type.is_some_and(|value| {
        value
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .eq_ignore_ascii_case("application/json")
    }) {
        return Err(UNCONFIRMED.into());
    }
    let mut bytes = Vec::new();
    body.take(MAX_ACK_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| UNCONFIRMED)?;
    if bytes.len() as u64 > MAX_ACK_BYTES {
        return Err(UNCONFIRMED.into());
    }
    match serde_json::from_slice::<Acknowledgment>(&bytes) {
        Ok(Acknowledgment { ok: true }) => Ok(()),
        _ => Err(UNCONFIRMED.into()),
    }
}

fn deliver(
    client: reqwest::blocking::Client,
    url: reqwest::Url,
    feedback: &Feedback,
) -> Result<(), String> {
    let response = client
        .post(url)
        .json(&payload(feedback))
        .send()
        .map_err(|_| "Delivery could not be confirmed. Check your connection before retrying.")?;
    let status = response.status().as_u16();
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(str::to_owned);
    confirm(status, content_type.as_deref(), response)
}

pub fn send(mut feedback: Feedback) -> Result<(), String> {
    feedback.validate()?;
    // The override selects a public HTTPS relay, never a webhook or retry destination.
    let endpoint = match option_env!("DAZEDEV_FEEDBACK_URL") {
        Some(endpoint) => endpoint.to_owned(),
        None => default_endpoint()?,
    };
    let url = relay_url(&endpoint)?;
    deliver(relay_client()?, url, &feedback)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_content_matches_native_feedback_contract() {
        let content: serde_json::Value = serde_json::from_str(SUPPORT_CONTENT).unwrap();
        assert_eq!(content["schemaVersion"], 2);
        assert_eq!(content["feedback"]["ack"]["maxBytes"], MAX_ACK_BYTES);
        assert_eq!(content["feedback"]["ack"]["httpStatus"], 200);
        assert_eq!(
            content["feedback"]["ack"]["contentType"],
            "application/json"
        );
        assert_eq!(
            content["feedback"]["ack"]["body"],
            serde_json::json!({"ok": true})
        );
        for (field, limit) in [("name", 100), ("email", 254), ("message", 4000)] {
            let configured = content["feedback"]["fields"]
                .as_array()
                .unwrap()
                .iter()
                .find(|item| item["id"] == field)
                .unwrap();
            assert_eq!(configured["maxLength"], limit);
            assert_eq!(configured["required"], true);
        }
        assert!(relay_url(&default_endpoint().unwrap()).is_ok());
    }

    #[test]
    fn relay_address_requires_https_without_credentials_or_slack_destination() {
        assert!(relay_url(&default_endpoint().unwrap()).is_ok());
        for endpoint in [
            "http://relay.example/feedback",
            "https://user:password@relay.example/feedback",
            "https://relay.example/feedback?token=private",
            "https://relay.example/feedback#fragment",
            "https://hooks.slack.com/services/private",
            "https://HOOKS.SLACK.COM./services/private",
            "https://hooks.slack-gov.com/services/private",
        ] {
            assert!(relay_url(endpoint).is_err(), "accepted {endpoint}");
        }
    }

    #[test]
    fn acknowledgment_requires_json_and_exact_true_object() {
        assert!(confirm(
            200,
            Some("application/json; charset=utf-8"),
            &b"{\"ok\":true}"[..]
        )
        .is_ok());
        for body in [
            "",
            "ok",
            "{}",
            "{\"ok\":false}",
            "{\"ok\":\"true\"}",
            "{\"ok\":1}",
            "{\"ok\":true,\"unexpected\":1}",
            "{\"ok\":true,\"ok\":true}",
            "[{\"ok\":true}]",
        ] {
            assert_eq!(
                confirm(200, Some("application/json"), body.as_bytes()).unwrap_err(),
                UNCONFIRMED
            );
        }
        for kind in [None, Some("text/html"), Some("text/plain")] {
            assert!(confirm(200, kind, &b"{\"ok\":true}"[..]).is_err());
        }
        for status in [201, 204, 301, 302, 403, 500, 502] {
            assert!(confirm(status, Some("application/json"), &b"{\"ok\":true}"[..]).is_err());
        }
    }

    #[test]
    fn acknowledgment_read_is_bounded_and_hides_upstream_errors() {
        use std::cell::Cell;
        struct Count<'a>(&'a Cell<usize>);
        impl Read for Count<'_> {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                bytes.fill(b' ');
                self.0.set(self.0.get() + bytes.len());
                Ok(bytes.len())
            }
        }
        let count = Cell::new(0);
        assert!(confirm(200, Some("application/json"), Count(&count)).is_err());
        assert_eq!(count.get(), MAX_ACK_BYTES as usize + 1);
        struct Failed;
        impl Read for Failed {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::Error::other("private upstream diagnostic"))
            }
        }
        assert_eq!(
            confirm(200, Some("application/json"), Failed).unwrap_err(),
            UNCONFIRMED
        );
        let mut exact = b"{\"ok\":true}".to_vec();
        exact.resize(MAX_ACK_BYTES as usize, b' ');
        assert!(confirm(200, Some("application/json"), exact.as_slice()).is_ok());
        exact.push(b' ');
        assert!(confirm(200, Some("application/json"), exact.as_slice()).is_err());
    }

    #[test]
    fn useful_errors_do_not_consume_or_disclose_response_bodies() {
        let cases = [
            (400, "Check your contact details"),
            (413, "shorten your message"),
            (429, "wait a minute"),
            (404, "not available"),
            (503, "not available"),
        ];
        for (status, expected) in cases {
            let message = confirm(status, None, &b"private details"[..]).unwrap_err();
            assert!(message.contains(expected));
            assert!(!message.contains("private details") && !message.contains("Tailscale"));
        }
    }

    #[test]
    fn outgoing_identity_is_fixed_and_frontend_cannot_supply_it() {
        let input = r#"{"name":"Tester","email":"tester@example.test","message":"Hello"}"#;
        let feedback: Feedback = serde_json::from_str(input).unwrap();
        let body = payload(&feedback);
        assert_eq!(
            body,
            serde_json::json!({
                "app": "DazeDev", "version": env!("CARGO_PKG_VERSION"),
                "name": "Tester", "email": "tester@example.test", "message": "Hello",
            })
        );
        assert!(
            serde_json::from_str::<Feedback>(&input.replace("{", "{\"app\":\"other\",")).is_err()
        );
    }

    #[test]
    fn loopback_transport_sends_identity_and_user_agent_without_retries_or_redirects() {
        use std::io::{BufRead, BufReader, Write};
        use std::net::TcpListener;
        use std::time::Instant;
        for (status, body, success) in [
            (200, "{\"ok\":true}", true),
            (200, "{\"ok\":false}", false),
            (204, "", false),
            (302, "", false),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            listener.set_nonblocking(true).unwrap();
            let server = std::thread::spawn(move || {
                let mut requests = Vec::new();
                let deadline = Instant::now() + Duration::from_secs(3);
                let mut quiet_since = None;
                while Instant::now() < deadline {
                    if quiet_since
                        .is_some_and(|since: Instant| since.elapsed() > Duration::from_millis(250))
                    {
                        break;
                    }
                    let (mut stream, _) = match listener.accept() {
                        Ok(value) => value,
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(10));
                            continue;
                        }
                        Err(e) => panic!("loopback accept: {e}"),
                    };
                    // Windows hands accepted sockets the listener's non-blocking mode.
                    stream.set_nonblocking(false).unwrap();
                    stream
                        .set_read_timeout(Some(Duration::from_secs(2)))
                        .unwrap();
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    let mut headers = String::new();
                    loop {
                        let mut line = String::new();
                        assert!(reader.read_line(&mut line).unwrap() > 0);
                        if line == "\r\n" {
                            break;
                        }
                        headers.push_str(&line);
                    }
                    let length: usize = headers
                        .lines()
                        .find_map(|line| {
                            line.to_ascii_lowercase()
                                .strip_prefix("content-length:")
                                .map(|value| value.trim().parse().unwrap())
                        })
                        .unwrap_or(0);
                    let mut received = vec![0; length];
                    reader.read_exact(&mut received).unwrap();
                    requests.push((headers, received));
                    write!(stream, "HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nLocation: http://{address}/other\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
                    quiet_since = Some(Instant::now());
                }
                requests
            });
            let feedback = Feedback {
                name: "Tester".into(),
                email: "tester@example.test".into(),
                message: "Hello".into(),
            };
            // Only this private test path bypasses HTTPS validation for the loopback fixture.
            let result = deliver(
                relay_client().unwrap(),
                reqwest::Url::parse(&format!("http://{address}/feedback")).unwrap(),
                &feedback,
            );
            assert_eq!(result.is_ok(), success);
            let requests = server.join().unwrap();
            assert_eq!(requests.len(), 1, "retry or redirect for {status}");
            assert!(requests[0].0.to_ascii_lowercase().contains(&format!(
                "user-agent: dazedev/{}",
                env!("CARGO_PKG_VERSION")
            )));
            assert_eq!(
                serde_json::from_slice::<serde_json::Value>(&requests[0].1).unwrap(),
                payload(&feedback)
            );
        }
    }

    #[test]
    fn validates_before_sending_and_keeps_multiline_messages() {
        let mut feedback = Feedback {
            name: " Tester ".into(),
            email: " tester@example.test ".into(),
            message: " First line\nSecond line ".into(),
        };
        feedback.validate().unwrap();
        assert_eq!(feedback.message, "First line\nSecond line");
        assert_eq!(feedback.email, "tester@example.test");
        feedback.email = "x@example.test\nBcc:other@example.test".into();
        assert!(feedback.validate().is_err());
        feedback.email = "x@example.test".into();
        feedback.message = "x".repeat(4001);
        assert!(feedback.validate().is_err());
        feedback.message = " \n ".into();
        assert!(feedback.validate().is_err());
    }
}
