//! Local inference transport must never inherit proxy routing or follow redirects.
use std::time::Duration;
pub fn client(timeout: Duration) -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(timeout)
        .build()
        .map_err(|e| e.to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{Read, Write},
        net::TcpListener,
    };
    #[test]
    fn proxy_and_redirect_regression() {
        // A subprocess isolates proxy environment changes from other tests.
        if std::env::var_os("DAZEDEV_PROXY_FIXTURE").is_none() {
            let status = std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "local_http::tests::proxy_and_redirect_regression",
                    "--nocapture",
                ])
                .env("DAZEDEV_PROXY_FIXTURE", "1")
                .status()
                .unwrap();
            assert!(status.success());
            return;
        }
        let proxy = TcpListener::bind("127.0.0.1:0").unwrap();
        proxy.set_nonblocking(true).unwrap();
        for key in ["HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"] {
            std::env::set_var(key, format!("http://{}", proxy.local_addr().unwrap()));
        }
        std::env::remove_var("NO_PROXY");
        std::env::remove_var("no_proxy");
        let local = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", local.local_addr().unwrap());
        let target = TcpListener::bind("127.0.0.1:0").unwrap();
        target.set_nonblocking(true).unwrap();
        let redirect = target.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            for response in ["HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".to_owned(), format!("HTTP/1.1 302 Found\r\nLocation: http://{redirect}/external\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")] {
                let (mut socket, _) = local.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                let mut request = [0; 4096]; let n = socket.read(&mut request).unwrap();
                assert!(String::from_utf8_lossy(&request[..n]).contains("fixture-token"));
                socket.write_all(response.as_bytes()).unwrap();
            }
        });
        let http = client(Duration::from_secs(2)).unwrap();
        assert_eq!(
            http.get(&url)
                .bearer_auth("fixture-token")
                .send()
                .unwrap()
                .status(),
            200
        );
        assert_eq!(
            http.post(&url)
                .bearer_auth("fixture-token")
                .body("synthetic transcript")
                .send()
                .unwrap()
                .status(),
            302
        );
        server.join().unwrap();
        assert_eq!(
            proxy.accept().unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
        assert_eq!(
            target.accept().unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
    }
}
