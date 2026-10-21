use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

struct ServerGuard(Child);

impl Drop for ServerGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn libreoffice_available() -> bool {
    const CANDIDATES: &[&str] = &[
        "/usr/lib/libreoffice/program/libsofficeapp.so",
        "/usr/lib/libreoffice/program/libmergedlo.so",
        "/usr/lib64/libreoffice/program/libsofficeapp.so",
        "/usr/lib64/libreoffice/program/libmergedlo.so",
    ];
    CANDIDATES.iter().any(|p| Path::new(p).exists())
}

async fn raw_http(port: u16, req: &[u8]) -> Vec<u8> {
    let mut stream = TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("connect failed");
    stream.write_all(req).await.expect("write failed");
    let mut resp = Vec::new();
    stream.read_to_end(&mut resp).await.expect("read failed");
    resp
}

#[tokio::test]
async fn end_to_end_libreoffice_conversion_and_probes() {
    if !libreoffice_available() {
        return;
    }

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);

    let bin = env!("CARGO_BIN_EXE_docx-to-pdf");
    let child = Command::new(bin)
        .arg(format!("--server.port={port}"))
        .arg("--pool.size=1")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("failed to spawn docx-to-pdf");
    let _guard = ServerGuard(child);

    // Wait for readiness probe to report 200 OK
    let mut ready = false;
    for _ in 0..100 {
        if let Ok(mut s) = TcpStream::connect(("127.0.0.1", port)).await {
            let _ = s
                .write_all(
                    b"GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                )
                .await;
            let mut buf = Vec::new();
            let _ = s.read_to_end(&mut buf).await;
            if buf.starts_with(b"HTTP/1.1 200 OK") {
                ready = true;
                break;
            }
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    assert!(ready, "server did not become ready within 10s");

    // Verify liveness probe
    let liveness = raw_http(
        port,
        b"GET /actuator/health/liveness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert!(liveness.starts_with(b"HTTP/1.1 200 OK"));

    // Verify CORS preflight echoes Authorization
    let preflight = raw_http(
        port,
        b"OPTIONS /docx-to-pdf HTTP/1.1\r\nHost: localhost\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: authorization, content-type\r\nConnection: close\r\n\r\n",
    )
    .await;
    let preflight_str = String::from_utf8_lossy(&preflight).to_lowercase();
    assert!(preflight_str.starts_with("http/1.1 204 no content"));
    assert!(preflight_str.contains("access-control-allow-headers: authorization, content-type"));

    // Convert real DOCX via POST /docx-to-pdf
    let docx = std::fs::read("examples/ex1/input.docx").expect("missing examples/ex1/input.docx");
    let boundary = "----DocxToPdfBoundary";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"input.docx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(&docx);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

    let mut req = format!(
        "POST /docx-to-pdf HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .into_bytes();
    req.extend_from_slice(&body);

    let resp = raw_http(port, &req).await;
    assert!(
        resp.starts_with(b"HTTP/1.1 200 OK"),
        "unexpected status: {}",
        String::from_utf8_lossy(&resp[..resp.len().min(200)])
    );
    assert!(
        resp.windows(5).any(|w| w == b"%PDF-"),
        "response did not contain %PDF- header"
    );

    // Verify non-ZIP payload is rejected with 422 while worker stays healthy
    let bad_body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"bad.docx\"\r\n\r\nnot-a-zip\r\n--{boundary}--\r\n"
    );
    let bad_req = format!(
        "POST /pdf HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        bad_body.len(),
        bad_body
    );
    let bad_resp = raw_http(port, bad_req.as_bytes()).await;
    assert!(bad_resp.starts_with(b"HTTP/1.1 422 Unprocessable Entity"));
}
