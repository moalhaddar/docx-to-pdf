mod lok;
mod sandbox;

use axum::Router;
use axum::extract::{DefaultBodyLimit, Multipart, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tempfile::TempDir;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::{Notify, Semaphore};
use tracing::{Instrument, error, info, info_span, warn};
use tracing_subscriber::EnvFilter;

static NEXT_REQ_ID: AtomicU64 = AtomicU64::new(1);
const ZIP_MAGIC: &[u8; 4] = b"PK\x03\x04";

fn main() {
    let mut args = std::env::args().skip(1);
    if let Some(first) = args.next()
        && first == "--worker"
    {
        let id: usize = args.next().and_then(|s| s.parse().ok()).unwrap_or(0);
        let ppid: u32 = args.next().and_then(|s| s.parse().ok()).unwrap_or(0);
        let profile = PathBuf::from(args.next().expect("missing worker profile dir"));
        lok::run_worker(id, ppid, &profile);
    }

    let config = Config::from_env_and_args();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime");
    rt.block_on(async_main(config));
}

#[derive(Clone, Debug)]
struct Config {
    port: u16,
    address: Option<String>,
    context_path: String,
    pool_size: usize,
    max_inflight: usize,
    borrow_timeout: Duration,
    conversion_timeout: Duration,
    max_file_size: Option<usize>,
    max_request_size: Option<usize>,
}

impl Config {
    fn from_env_and_args() -> Self {
        Self::from_sources(env_lookup, std::env::args().skip(1)).unwrap_or_else(|reason| {
            eprintln!("Invalid configuration: {reason}");
            std::process::exit(1);
        })
    }

    fn from_sources(
        lookup: impl Fn(&[&str]) -> Option<String>,
        args: impl IntoIterator<Item = String>,
    ) -> Result<Self, String> {
        let mut port = lookup(&["PORT", "SERVER_PORT", "server.port"])
            .and_then(|v| v.parse().ok())
            .unwrap_or(8080);
        let mut address = lookup(&["SERVER_ADDRESS", "server.address"]);
        let mut context_path =
            lookup(&["SERVER_SERVLET_CONTEXT_PATH", "server.servlet.context-path"])
                .unwrap_or_default();
        let mut pool_size = lookup(&["POOL_SIZE", "pool.size"])
            .and_then(|v| v.parse().ok())
            .unwrap_or(1usize)
            .max(1);
        let mut max_inflight =
            lookup(&["MAX_INFLIGHT", "pool.max-inflight"]).and_then(|v| v.parse().ok());
        let mut borrow_ms = lookup(&["BORROW_TIMEOUT_MS", "pool.borrow-timeout-ms"])
            .and_then(|v| v.parse().ok())
            .unwrap_or(30_000u64);
        let mut conv_ms = lookup(&["CONVERSION_TIMEOUT_MS", "pool.conversion-timeout-ms"])
            .and_then(|v| v.parse().ok())
            .unwrap_or(60_000u64);
        let max_mb = lookup(&["MAX_FILE_SIZE_MB"]);
        let mut file_limit = lookup(&[
            "SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE",
            "spring.servlet.multipart.max-file-size",
        ]);
        let mut request_limit = lookup(&[
            "SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE",
            "spring.servlet.multipart.max-request-size",
        ]);

        for arg in args {
            if let Some(v) = arg
                .strip_prefix("--pool.size=")
                .or_else(|| arg.strip_prefix("--pool-size="))
                && let Ok(n) = v.parse::<usize>()
            {
                pool_size = n.max(1);
            } else if let Some(v) = arg.strip_prefix("--pool.max-inflight=")
                && let Ok(n) = v.parse::<usize>()
            {
                max_inflight = Some(n.max(1));
            } else if let Some(v) = arg
                .strip_prefix("--server.port=")
                .or_else(|| arg.strip_prefix("--port="))
                && let Ok(p) = v.parse::<u16>()
            {
                port = p;
            } else if let Some(v) = arg.strip_prefix("--server.address=") {
                address = Some(v.to_string());
            } else if let Some(v) = arg.strip_prefix("--server.servlet.context-path=") {
                context_path = v.to_string();
            } else if let Some(v) = arg.strip_prefix("--spring.servlet.multipart.max-file-size=") {
                file_limit = Some(v.to_string());
            } else if let Some(v) = arg.strip_prefix("--spring.servlet.multipart.max-request-size=")
            {
                request_limit = Some(v.to_string());
            } else if let Some(v) = arg.strip_prefix("--pool.borrow-timeout-ms=")
                && let Ok(ms) = v.parse::<u64>()
            {
                borrow_ms = ms;
            } else if let Some(v) = arg.strip_prefix("--pool.conversion-timeout-ms=")
                && let Ok(ms) = v.parse::<u64>()
            {
                conv_ms = ms;
            }
        }

        let max_inflight = max_inflight.unwrap_or_else(|| (pool_size * 64).max(64));
        let (max_file_size, max_request_size) = if let Some(raw) = max_mb {
            // Preserve the Rust setting's existing meaning: override both bounds.
            let bytes = raw
                .parse::<usize>()
                .ok()
                .and_then(|n| n.checked_mul(1024 * 1024))
                .ok_or_else(|| format!("invalid MAX_FILE_SIZE_MB: {raw}"))?;
            (Some(bytes), Some(bytes))
        } else {
            (
                parse_upload_limit(file_limit.as_deref().unwrap_or("10MB"))?,
                parse_upload_limit(request_limit.as_deref().unwrap_or("10MB"))?,
            )
        };

        Ok(Self {
            port,
            address: address
                .map(|v| v.trim().to_string())
                .filter(|v| !v.is_empty()),
            context_path: normalize_context_path(&context_path)?,
            pool_size,
            max_inflight,
            borrow_timeout: Duration::from_millis(borrow_ms),
            conversion_timeout: Duration::from_millis(conv_ms),
            max_file_size,
            max_request_size,
        })
    }
}

fn normalize_context_path(raw: &str) -> Result<String, String> {
    let path = raw.trim().trim_end_matches('/');
    if path.is_empty() {
        return Ok(String::new());
    }
    if !path.starts_with('/')
        || path.contains(['?', '#', '{', '}', '*'])
        || path.parse::<axum::http::Uri>().is_err()
    {
        return Err(format!("invalid server.servlet.context-path: {raw}"));
    }
    Ok(path.to_string())
}

fn parse_upload_limit(raw: &str) -> Result<Option<usize>, String> {
    let value = raw.trim();
    let end = value
        .find(|c: char| !c.is_ascii_digit() && c != '-' && c != '+')
        .unwrap_or(value.len());
    let invalid = || format!("invalid multipart size: {raw}");
    let amount = value[..end].parse::<i128>().map_err(|_| invalid())?;
    let multiplier = match value[end..].to_ascii_uppercase().as_str() {
        "" | "B" => 1u128,
        "KB" => 1024,
        "MB" => 1024 * 1024,
        "GB" => 1024 * 1024 * 1024,
        "TB" => 1024u128.pow(4),
        _ => return Err(invalid()),
    };
    if amount == -1 {
        return Ok(None);
    }
    let bytes = u128::try_from(amount)
        .ok()
        .and_then(|n| n.checked_mul(multiplier))
        .and_then(|n| usize::try_from(n).ok())
        .ok_or_else(invalid)?;
    Ok(Some(bytes))
}

fn env_lookup(keys: &[&str]) -> Option<String> {
    for k in keys {
        if let Ok(v) = std::env::var(k) {
            let trimmed = v.trim();
            if !trimmed.is_empty() {
                return Some(trimmed.to_string());
            }
        }
    }
    None
}

struct Worker {
    id: usize,
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    _profile_dir: TempDir,
}

impl Worker {
    async fn spawn(id: usize) -> Result<Self, String> {
        let t0 = Instant::now();
        let profile_dir = tempfile::Builder::new()
            .prefix(&format!("docx-to-pdf-profile-{id}-"))
            .tempdir()
            .map_err(|e| format!("tempdir failed: {e}"))?;
        let exe = std::env::current_exe().map_err(|e| format!("current_exe failed: {e}"))?;
        let ppid = std::process::id();

        info!(worker = id, "[LibreOffice/{id}] Starting server instance..");
        let mut child = Command::new(exe)
            .env_clear()
            .envs(
                ["LANG", "LC_ALL", "LC_CTYPE", "TZ", "LO_PROGRAM_DIR"]
                    .into_iter()
                    .filter_map(|key| std::env::var_os(key).map(|value| (key, value))),
            )
            .arg("--worker")
            .arg(id.to_string())
            .arg(ppid.to_string())
            .arg(profile_dir.path())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| format!("failed to spawn worker {id}: {e}"))?;

        let pid = child.id().unwrap_or(0);
        info!(worker = id, pid, "[LibreOffice/{id}] PID: {pid}");

        let stdin = child.stdin.take().ok_or("missing worker stdin")?;
        let stdout = child.stdout.take().ok_or("missing worker stdout")?;
        let mut reader = BufReader::new(stdout);
        let mut line = String::new();

        match tokio::time::timeout(Duration::from_secs(30), reader.read_line(&mut line)).await {
            Ok(Ok(n)) if n > 0 && line.trim() == "READY" => {
                info!(
                    worker = id,
                    pid,
                    warmup_ms = t0.elapsed().as_millis() as u64,
                    "[LibreOffice/{id}] Ready and pre-warmed"
                );
                Ok(Self {
                    id,
                    child,
                    stdin,
                    stdout: reader,
                    _profile_dir: profile_dir,
                })
            }
            Ok(Ok(_)) => {
                let _ = child.kill().await;
                Err(format!("[LibreOffice/{id}] Exited before signaling READY"))
            }
            Ok(Err(e)) => {
                let _ = child.kill().await;
                Err(format!("[LibreOffice/{id}] Startup pipe error: {e}"))
            }
            Err(_) => {
                let _ = child.kill().await;
                Err(format!("[LibreOffice/{id}] Timed out waiting for READY"))
            }
        }
    }

    fn is_alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }

    async fn convert(&mut self, in_path: &Path, out_path: &Path) -> Result<(), ConvertError> {
        let cmd = format!("{}\t{}\n", in_path.display(), out_path.display());
        self.stdin
            .write_all(cmd.as_bytes())
            .await
            .map_err(|e| ConvertError::WorkerDied(format!("stdin write failed: {e}")))?;
        self.stdin
            .flush()
            .await
            .map_err(|e| ConvertError::WorkerDied(format!("stdin flush failed: {e}")))?;

        let mut reply = String::new();
        let n = self
            .stdout
            .read_line(&mut reply)
            .await
            .map_err(|e| ConvertError::WorkerDied(format!("stdout read failed: {e}")))?;
        if n == 0 {
            return Err(ConvertError::WorkerDied(format!(
                "[LibreOffice/{}] Process exited during conversion",
                self.id
            )));
        }

        let trimmed = reply.trim_end_matches(['\r', '\n']);
        if trimmed == "OK" {
            Ok(())
        } else if let Some(reason) = trimmed.strip_prefix("ERR\t") {
            if !self.is_alive() {
                Err(ConvertError::WorkerDied(reason.to_string()))
            } else {
                Err(ConvertError::DocumentRejected(reason.to_string()))
            }
        } else {
            Err(ConvertError::WorkerDied(format!(
                "unexpected worker reply: {trimmed}"
            )))
        }
    }
}

enum ConvertError {
    PoolTimeout,
    ConversionTimeout,
    WorkerDied(String),
    DocumentRejected(String),
}

struct ConvertOutcome {
    pdf: Vec<u8>,
    worker_id: usize,
    wait_ms: u64,
    convert_ms: u64,
}

struct Pool {
    config: Config,
    idle: Mutex<VecDeque<Worker>>,
    live: AtomicUsize,
    notify: Notify,
    request_slots: Semaphore,
    closed: AtomicBool,
}

impl Pool {
    async fn new(config: Config) -> Result<Arc<Self>, String> {
        let mut handles = Vec::with_capacity(config.pool_size);
        for id in 0..config.pool_size {
            handles.push(tokio::spawn(async move { Worker::spawn(id).await }));
        }
        let mut workers = VecDeque::with_capacity(config.pool_size);
        let mut first_err: Option<String> = None;
        for h in handles {
            match h.await.map_err(|e| e.to_string()).and_then(|r| r) {
                Ok(w) => workers.push_back(w),
                Err(e) if first_err.is_none() => first_err = Some(e),
                Err(_) => {}
            }
        }
        if let Some(err) = first_err {
            for mut w in workers {
                let _ = w.child.kill().await;
            }
            return Err(err);
        }
        let count = workers.len();
        let max_inflight = config.max_inflight;

        Ok(Arc::new(Self {
            config,
            idle: Mutex::new(workers),
            live: AtomicUsize::new(count),
            notify: Notify::new(),
            request_slots: Semaphore::new(max_inflight),
            closed: AtomicBool::new(false),
        }))
    }

    fn discard_worker(self: &Arc<Self>, mut w: Worker, job_dir: Option<TempDir>) {
        let id = w.id;
        self.live.fetch_sub(1, Ordering::SeqCst);
        let _ = w.child.start_kill();
        let pool = Arc::clone(self);
        tokio::spawn(async move {
            let _ = w.child.kill().await;
            drop(job_dir);
            drop(w);
            pool.schedule_replacement(id);
        });
    }

    fn schedule_replacement(self: &Arc<Self>, id: usize) {
        if self.closed.load(Ordering::SeqCst) {
            return;
        }
        let pool_weak = Arc::downgrade(self);
        tokio::spawn(async move {
            let mut delay = Duration::from_millis(200);
            loop {
                let Some(pool) = pool_weak.upgrade() else {
                    return;
                };
                if pool.closed.load(Ordering::SeqCst) {
                    return;
                }
                match Worker::spawn(id).await {
                    Ok(mut worker) => {
                        if pool.closed.load(Ordering::SeqCst) {
                            let _ = worker.child.kill().await;
                            return;
                        }
                        pool.live.fetch_add(1, Ordering::SeqCst);
                        pool.idle.lock().unwrap().push_back(worker);
                        pool.notify.notify_one();
                        return;
                    }
                    Err(e) => {
                        if pool.closed.load(Ordering::SeqCst) {
                            return;
                        }
                        error!(
                            worker = id,
                            retry_ms = delay.as_millis() as u64,
                            error = %e,
                            "[LibreOffice/{id}] Replacement failed, retrying"
                        );
                        tokio::time::sleep(delay).await;
                        delay = (delay * 2).min(Duration::from_secs(5));
                    }
                }
            }
        });
    }

    async fn borrow(self: &Arc<Self>) -> Result<WorkerGuard, ConvertError> {
        tokio::time::timeout(self.config.borrow_timeout, async {
            loop {
                let notified = self.notify.notified();
                tokio::pin!(notified);

                {
                    let mut idle = self.idle.lock().unwrap();
                    while let Some(mut w) = idle.pop_front() {
                        if w.is_alive() {
                            return WorkerGuard {
                                pool: Arc::clone(self),
                                worker: Some(w),
                                job_dir: None,
                            };
                        }
                        warn!(
                            worker = w.id,
                            "[LibreOffice/{}] Discarding dead idle worker", w.id
                        );
                        self.discard_worker(w, None);
                    }
                    notified.as_mut().enable();
                }

                notified.await;
            }
        })
        .await
        .map_err(|_| ConvertError::PoolTimeout)
    }

    fn live_workers(self: &Arc<Self>) -> usize {
        let mut idle = self.idle.lock().unwrap();
        let mut alive_idle = VecDeque::with_capacity(idle.len());
        while let Some(mut w) = idle.pop_front() {
            if w.is_alive() {
                alive_idle.push_back(w);
            } else {
                warn!(
                    worker = w.id,
                    "[LibreOffice/{}] Discarding dead idle worker during health check", w.id
                );
                self.discard_worker(w, None);
            }
        }
        *idle = alive_idle;
        self.live.load(Ordering::SeqCst)
    }

    async fn shutdown(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let workers: Vec<Worker> = self.idle.lock().unwrap().drain(..).collect();
        for mut w in workers {
            let _ = w.child.kill().await;
        }
    }

    async fn convert_docx(self: &Arc<Self>, docx: &[u8]) -> Result<ConvertOutcome, ConvertError> {
        let max_attempts = self.config.pool_size + 1;
        let mut last_err = ConvertError::WorkerDied("no attempts run".to_string());
        for attempt in 1..=max_attempts {
            match self.convert_once(docx).await {
                Ok(outcome) => return Ok(outcome),
                Err(ConvertError::WorkerDied(reason)) if attempt < max_attempts => {
                    warn!(
                        attempt,
                        max_attempts,
                        reason = %reason,
                        "Worker died during conversion, retrying"
                    );
                    last_err = ConvertError::WorkerDied(reason);
                }
                Err(e) => return Err(e),
            }
        }
        Err(last_err)
    }

    async fn convert_once(self: &Arc<Self>, docx: &[u8]) -> Result<ConvertOutcome, ConvertError> {
        // Borrow a worker BEFORE allocating a temp directory, so at most
        // `pool.size` temp files ever exist on disk simultaneously under load.
        let wait_start = Instant::now();
        let mut guard = self.borrow().await?;
        let wait_ms = wait_start.elapsed().as_millis() as u64;

        let job_dir = match tempfile::Builder::new()
            .prefix("docx-to-pdf-job-")
            .tempdir_in(guard.worker.as_ref().unwrap()._profile_dir.path())
        {
            Ok(dir) => dir,
            Err(e) => {
                guard.return_to_pool();
                return Err(ConvertError::DocumentRejected(format!(
                    "tempdir error: {e}"
                )));
            }
        };
        let in_path = job_dir.path().join("input.docx");
        let out_path = job_dir.path().join("output.pdf");
        guard.job_dir = Some(job_dir);

        if let Err(e) = tokio::fs::write(&in_path, docx).await {
            guard.return_to_pool();
            return Err(ConvertError::DocumentRejected(format!(
                "failed to write input.docx: {e}"
            )));
        }

        let worker = guard.worker.as_mut().unwrap();
        let worker_id = worker.id;
        let conv_start = Instant::now();
        let res = tokio::time::timeout(
            self.config.conversion_timeout,
            worker.convert(&in_path, &out_path),
        )
        .await;
        let convert_ms = conv_start.elapsed().as_millis() as u64;

        match res {
            Ok(Ok(())) => {
                let pdf = tokio::fs::read(&out_path).await.map_err(|e| {
                    ConvertError::WorkerDied(format!("failed to read output.pdf: {e}"))
                })?;
                guard.return_to_pool();
                Ok(ConvertOutcome {
                    pdf,
                    worker_id,
                    wait_ms,
                    convert_ms,
                })
            }
            Ok(Err(ConvertError::DocumentRejected(msg))) => {
                // Worker process is still healthy; return it to the pool.
                guard.return_to_pool();
                Err(ConvertError::DocumentRejected(msg))
            }
            Ok(Err(e)) => {
                // guard drops here -> kills dead worker, waits for exit, then deletes job_dir.
                Err(e)
            }
            Err(_) => {
                error!(
                    worker = worker_id,
                    timeout_ms = self.config.conversion_timeout.as_millis() as u64,
                    "[LibreOffice/{worker_id}] Conversion timed out, killing worker"
                );
                // guard drops here -> kills hung worker, waits for exit, then deletes job_dir.
                Err(ConvertError::ConversionTimeout)
            }
        }
    }
}

struct WorkerGuard {
    pool: Arc<Pool>,
    worker: Option<Worker>,
    job_dir: Option<TempDir>,
}

impl WorkerGuard {
    fn return_to_pool(mut self) {
        drop(self.job_dir.take());
        let mut idle = self.pool.idle.lock().unwrap();
        if let Some(w) = self.worker.take() {
            idle.push_back(w);
            drop(idle);
            self.pool.notify.notify_one();
        }
    }
}

impl Drop for WorkerGuard {
    fn drop(&mut self) {
        if let Some(w) = self.worker.take() {
            self.pool.discard_worker(w, self.job_dir.take());
        }
    }
}

async fn async_main(config: Config) {
    let env_filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(env_filter)
        .with_target(false)
        .init();

    let t0 = Instant::now();
    info!(
        version = env!("CARGO_PKG_VERSION"),
        pool_size = config.pool_size,
        max_inflight = config.max_inflight,
        port = config.port,
        "Starting docx-to-pdf"
    );

    let port = config.port;
    let address = config.address.clone();
    let pool = match Pool::new(config).await {
        Ok(pool) => pool,
        Err(reason) => {
            error!(error = %reason, "Failed to initialize worker pool");
            std::process::exit(1);
        }
    };

    let app = build_router(Arc::clone(&pool));

    let listener = bind_listener(address.as_deref(), port)
        .await
        .expect("Failed to bind TCP listener");
    let local_addr = listener.local_addr().unwrap();
    info!(
        addr = %local_addr,
        startup_ms = t0.elapsed().as_millis() as u64,
        "Started docx-to-pdf"
    );

    tokio::select! {
        res = axum::serve(listener, app) => {
            if let Err(e) = res {
                error!(error = %e, "HTTP server error");
            }
        }
        _ = shutdown_signal() => {}
    }

    pool.shutdown().await;
}

async fn bind_listener(
    address: Option<&str>,
    port: u16,
) -> std::io::Result<tokio::net::TcpListener> {
    if let Some(address) = address {
        // An explicit bind must fail instead of falling back to a wildcard.
        return tokio::net::TcpListener::bind((address, port)).await;
    }
    match tokio::net::TcpListener::bind(("::", port)).await {
        Ok(listener) => Ok(listener),
        Err(_) => tokio::net::TcpListener::bind(("0.0.0.0", port)).await,
    }
}

fn build_router(pool: Arc<Pool>) -> Router {
    let body_limit = match pool.config.max_request_size {
        Some(bytes) => DefaultBodyLimit::max(bytes),
        None => DefaultBodyLimit::disable(),
    };
    let context_path = pool.config.context_path.clone();
    let routes = Router::new()
        .route("/pdf", post(convert_handler).options(cors_preflight))
        .route(
            "/docx-to-pdf",
            post(convert_handler).options(cors_preflight),
        )
        .route("/health", get(health_handler))
        .route("/actuator/health", get(health_handler))
        .route("/actuator/health/liveness", get(liveness_handler))
        .route("/actuator/health/readiness", get(health_handler))
        .layer(body_limit)
        .with_state(pool);
    if context_path.is_empty() {
        routes
    } else {
        Router::new().nest(&context_path, routes)
    }
}

async fn cors_preflight(req_headers: HeaderMap) -> Response {
    let mut headers = cors_headers();
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("POST, GET, OPTIONS"),
    );
    let allow_headers = req_headers
        .get(header::ACCESS_CONTROL_REQUEST_HEADERS)
        .filter(|v| !v.is_empty())
        .cloned()
        .unwrap_or_else(|| HeaderValue::from_static("*, Authorization, Content-Type"));
    headers.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, allow_headers);
    (StatusCode::NO_CONTENT, headers).into_response()
}

fn sanitize_trace_id(raw: &str) -> Option<String> {
    let cleaned: String = raw
        .trim()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .take(64)
        .collect();
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned)
    }
}

fn extract_trace_id(headers: &HeaderMap) -> String {
    if let Some(tp) = headers.get("traceparent").and_then(|v| v.to_str().ok()) {
        let mut parts = tp.split('-');
        if let (Some(_ver), Some(trace_id)) = (parts.next(), parts.next())
            && let Some(clean) = sanitize_trace_id(trace_id)
        {
            return clean;
        }
    }
    for key in ["x-b3-traceid", "x-request-id"] {
        if let Some(val) = headers.get(key).and_then(|v| v.to_str().ok())
            && let Some(clean) = sanitize_trace_id(val)
        {
            return clean;
        }
    }
    format!("req-{}", NEXT_REQ_ID.fetch_add(1, Ordering::Relaxed))
}

async fn convert_handler(
    State(pool): State<Arc<Pool>>,
    req_headers: HeaderMap,
    mut multipart: Multipart,
) -> Response {
    let trace_id = extract_trace_id(&req_headers);
    let span = info_span!("convert", trace_id = %trace_id);

    async move {
        let Ok(_slot) = pool.request_slots.try_acquire() else {
            warn!("Rejecting request: max_inflight queue capacity reached");
            return error_response(
                StatusCode::SERVICE_UNAVAILABLE,
                "Server busy: request queue is full",
            );
        };

        let mut docx_bytes: Option<Vec<u8>> = None;
        let upload_res = tokio::time::timeout(pool.config.borrow_timeout, async {
            loop {
                match multipart.next_field().await {
                    Ok(Some(mut field)) => {
                        let is_document = field.name() == Some("document");
                        let is_file = is_document || field.file_name().is_some();
                        let keep_document = is_document && docx_bytes.is_none();
                        let mut bytes = Vec::new();
                        let mut field_size = 0usize;
                        loop {
                            match field.chunk().await {
                                Ok(Some(chunk)) => {
                                    field_size = match field_size.checked_add(chunk.len()) {
                                        Some(size) => size,
                                        None => return Err((StatusCode::PAYLOAD_TOO_LARGE, "Multipart field is too large".to_string())),
                                    };
                                    if is_file && pool.config.max_file_size.is_some_and(|limit| field_size > limit) {
                                        return Err((StatusCode::PAYLOAD_TOO_LARGE, "Multipart file exceeds configured limit".to_string()));
                                    }
                                    if keep_document {
                                        bytes.extend_from_slice(&chunk);
                                    }
                                }
                                Ok(None) => break,
                                Err(e) => {
                                    warn!(status = e.status().as_u16(), error = %e, "Multipart read failed");
                                    return Err((e.status(), e.body_text()));
                                }
                            }
                        }
                        if keep_document {
                            docx_bytes = Some(bytes);
                        }
                    }
                    Ok(None) => return Ok(()),
                    Err(e) => {
                        warn!(status = e.status().as_u16(), error = %e, "Multipart parse failed");
                        return Err((e.status(), e.body_text()));
                    }
                }
            }
        })
        .await;

        match upload_res {
            Ok(Ok(())) => {}
            Ok(Err((status, msg))) => return error_response(status, &msg),
            Err(_) => {
                warn!("Multipart upload timed out");
                return error_response(StatusCode::REQUEST_TIMEOUT, "Multipart upload timed out");
            }
        }

        let Some(docx) = docx_bytes else {
            warn!("Missing 'document' multipart field");
            return error_response(StatusCode::BAD_REQUEST, "Missing 'document' multipart field");
        };
        if docx.is_empty() {
            warn!("Uploaded 'document' is empty");
            return error_response(StatusCode::BAD_REQUEST, "Uploaded 'document' is empty");
        }
        if !docx.starts_with(ZIP_MAGIC) {
            warn!(
                docx_bytes = docx.len(),
                "Uploaded 'document' is not a valid ZIP/OOXML archive"
            );
            return error_response(
                StatusCode::UNPROCESSABLE_ENTITY,
                "Uploaded 'document' is not a valid DOCX archive",
            );
        }

        info!(
            docx_bytes = docx.len(),
            "PDF Request. Docx file size: {} bytes.",
            docx.len()
        );

        match pool.convert_docx(&docx).await {
            Ok(outcome) => {
                info!(
                    worker = outcome.worker_id,
                    docx_bytes = docx.len(),
                    pdf_bytes = outcome.pdf.len(),
                    wait_ms = outcome.wait_ms,
                    convert_ms = outcome.convert_ms,
                    "Successfully generated PDF. File Size: {} bytes.",
                    outcome.pdf.len()
                );
                let mut headers = cors_headers();
                headers.insert(
                    header::CONTENT_TYPE,
                    HeaderValue::from_static("application/pdf"),
                );
                (StatusCode::OK, headers, outcome.pdf).into_response()
            }
            Err(ConvertError::PoolTimeout) => {
                error!("No converter worker available before borrow timeout");
                error_response(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "No converter worker available before timeout",
                )
            }
            Err(ConvertError::ConversionTimeout) => {
                error!("Document conversion timed out");
                error_response(StatusCode::GATEWAY_TIMEOUT, "Document conversion timed out")
            }
            Err(ConvertError::WorkerDied(msg)) => {
                error!(error = %msg, "Conversion failed after retry");
                error_response(StatusCode::INTERNAL_SERVER_ERROR, &msg)
            }
            Err(ConvertError::DocumentRejected(msg)) => {
                warn!(error = %msg, "Document rejected");
                error_response(StatusCode::UNPROCESSABLE_ENTITY, &msg)
            }
        }
    }
    .instrument(span)
    .await
}

async fn liveness_handler(State(pool): State<Arc<Pool>>) -> Response {
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    if !pool.closed.load(Ordering::SeqCst) {
        (StatusCode::OK, headers, r#"{"status":"UP"}"#).into_response()
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            headers,
            r#"{"status":"DOWN"}"#,
        )
            .into_response()
    }
}

async fn health_handler(State(pool): State<Arc<Pool>>) -> Response {
    let live = pool.live_workers();
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    if live > 0 {
        (
            StatusCode::OK,
            headers,
            r#"{"status":"UP","groups":["liveness","readiness"]}"#,
        )
            .into_response()
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            headers,
            r#"{"status":"DOWN","groups":["liveness","readiness"]}"#,
        )
            .into_response()
    }
}

fn cors_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    headers
}

fn error_response(status: StatusCode, msg: &str) -> Response {
    let mut headers = cors_headers();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    let escaped = msg.replace('\\', "\\\\").replace('"', "\\\"");
    let body = format!(r#"{{"status":{},"error":"{escaped}"}}"#, status.as_u16());
    (status, headers, body).into_response()
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut sig) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            sig.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    info!("Shutting down docx-to-pdf..");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn empty_pool() -> Arc<Pool> {
        Arc::new(Pool {
            config: Config {
                port: 0,
                address: None,
                context_path: String::new(),
                pool_size: 1,
                max_inflight: 64,
                borrow_timeout: Duration::from_millis(200),
                conversion_timeout: Duration::from_secs(5),
                max_file_size: Some(1024 * 1024),
                max_request_size: Some(1024 * 1024),
            },
            idle: Mutex::new(VecDeque::new()),
            live: AtomicUsize::new(0),
            notify: Notify::new(),
            request_slots: Semaphore::new(64),
            closed: AtomicBool::new(false),
        })
    }

    fn config(vars: &[(&str, &str)], args: &[&str]) -> Result<Config, String> {
        Config::from_sources(
            |keys| {
                keys.iter().find_map(|key| {
                    vars.iter()
                        .find(|(name, _)| name == key)
                        .map(|(_, value)| value.to_string())
                })
            },
            args.iter().map(|arg| arg.to_string()),
        )
    }

    #[test]
    fn legacy_sizes_and_native_precedence_are_preserved() {
        for (raw, expected) in [
            ("1024", Some(1024)),
            ("1KB", Some(1024)),
            ("2MB", Some(2 * 1024 * 1024)),
            ("1GB", Some(1024 * 1024 * 1024)),
            ("-1", None),
            ("0B", Some(0)),
        ] {
            assert_eq!(parse_upload_limit(raw).unwrap(), expected);
        }
        for raw in ["", "oneMB", "1.5MB", "-2", "1PB", "999999999999999999999TB"] {
            assert!(parse_upload_limit(raw).is_err(), "accepted {raw}");
        }
        let vars = [
            ("SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE", "1KB"),
            ("SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE", "2MB"),
        ];
        let legacy = config(&vars, &[]).unwrap();
        assert_eq!(legacy.max_file_size, Some(1024));
        assert_eq!(legacy.max_request_size, Some(2 * 1024 * 1024));
        let cli = config(&vars, &["--spring.servlet.multipart.max-file-size=3KB"]).unwrap();
        assert_eq!(cli.max_file_size, Some(3 * 1024));
        assert_eq!(cli.max_request_size, legacy.max_request_size);
        let native = config(
            &[
                ("MAX_FILE_SIZE_MB", "4"),
                ("SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE", "invalid"),
            ],
            &["--spring.servlet.multipart.max-request-size=1KB"],
        )
        .unwrap();
        assert_eq!(native.max_file_size, Some(4 * 1024 * 1024));
        assert_eq!(native.max_request_size, native.max_file_size);
        assert!(config(&[("MAX_FILE_SIZE_MB", "invalid")], &[]).is_err());
    }

    #[test]
    fn address_and_context_path_support_environment_and_cli() {
        let parsed = config(
            &[
                ("SERVER_ADDRESS", "127.0.0.1"),
                ("SERVER_SERVLET_CONTEXT_PATH", "/convert"),
            ],
            &[
                "--server.address=::1",
                "--server.servlet.context-path=/api/",
            ],
        )
        .unwrap();
        assert_eq!(parsed.address.as_deref(), Some("::1"));
        assert_eq!(parsed.context_path, "/api");
        assert_eq!(normalize_context_path("/").unwrap(), "");
        for raw in ["convert", "/convert?x=1", "/{prefix}", "/bad path"] {
            assert!(normalize_context_path(raw).is_err());
        }
    }

    #[tokio::test]
    async fn explicit_bind_address_is_used() {
        let listener = bind_listener(Some("127.0.0.1"), 0).await.unwrap();
        assert_eq!(listener.local_addr().unwrap().ip().to_string(), "127.0.0.1");
        assert!(bind_listener(Some("invalid address!"), 0).await.is_err());
    }

    async fn test_http(pool: Arc<Pool>, path: &str, body: Option<&[u8]>) -> Vec<u8> {
        use tokio::io::AsyncReadExt;
        let listener = bind_listener(Some("127.0.0.1"), 0).await.unwrap();
        let addr = listener.local_addr().unwrap();
        let app = build_router(pool);
        let server = tokio::spawn(async move { axum::serve(listener, app).await });
        let mut stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        let req = match body {
            Some(body) => format!(
                "POST {path} HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=TestBoundary\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            ),
            None => format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
        };
        stream.write_all(req.as_bytes()).await.unwrap();
        if let Some(body) = body {
            stream.write_all(body).await.unwrap();
        }
        let mut response = Vec::new();
        stream.read_to_end(&mut response).await.unwrap();
        server.abort();
        let _ = server.await;
        response
    }

    #[tokio::test]
    async fn context_path_prefixes_probes_and_removes_unprefixed_routes() {
        let mut pool = empty_pool();
        Arc::get_mut(&mut pool).unwrap().config.context_path = "/convert".to_string();
        assert!(
            test_http(Arc::clone(&pool), "/convert/actuator/health/liveness", None)
                .await
                .starts_with(b"HTTP/1.1 200")
        );
        assert!(
            test_http(pool, "/actuator/health/liveness", None)
                .await
                .starts_with(b"HTTP/1.1 404")
        );
    }

    #[tokio::test]
    async fn file_and_whole_request_limits_are_independent_and_include_trailing_parts() {
        let mut pool = empty_pool();
        let config = &mut Arc::get_mut(&mut pool).unwrap().config;
        config.max_file_size = Some(2);
        config.max_request_size = Some(4096);
        let prefix = "--TestBoundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"input.docx\"\r\n\r\n";
        let body = format!("{prefix}ABC\r\n--TestBoundary--\r\n");
        assert!(
            test_http(Arc::clone(&pool), "/pdf", Some(body.as_bytes()))
                .await
                .starts_with(b"HTTP/1.1 413")
        );
        let trailing_file = format!(
            "{prefix}PK\r\n--TestBoundary\r\nContent-Disposition: form-data; name=\"ignored\"; filename=\"extra.bin\"\r\n\r\nABC\r\n--TestBoundary--\r\n"
        );
        assert!(
            test_http(Arc::clone(&pool), "/pdf", Some(trailing_file.as_bytes()))
                .await
                .starts_with(b"HTTP/1.1 413")
        );
        // A plain form field is governed by the total request bound, not the file bound.
        let body = format!(
            "{prefix}PK\r\n--TestBoundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{}\r\n--TestBoundary--\r\n",
            "x".repeat(1024)
        );
        assert!(
            test_http(Arc::clone(&pool), "/pdf", Some(body.as_bytes()))
                .await
                .starts_with(b"HTTP/1.1 422")
        );
        Arc::get_mut(&mut pool).unwrap().config.max_request_size = Some(512);
        assert!(
            test_http(Arc::clone(&pool), "/pdf", Some(body.as_bytes()))
                .await
                .starts_with(b"HTTP/1.1 413")
        );
        // Explicit unlimited request size must also disable axum's default 2 MiB cap.
        Arc::get_mut(&mut pool).unwrap().config.max_request_size = None;
        let body = format!(
            "{prefix}PK\r\n--TestBoundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{}\r\n--TestBoundary--\r\n",
            "x".repeat(3 * 1024 * 1024)
        );
        assert!(
            test_http(pool, "/pdf", Some(body.as_bytes()))
                .await
                .starts_with(b"HTTP/1.1 422")
        );
    }

    #[tokio::test]
    async fn liveness_stays_up_while_workers_recover_and_readiness_reflects_workers() {
        let pool = empty_pool();

        let liveness = liveness_handler(State(Arc::clone(&pool))).await;
        assert_eq!(liveness.status(), StatusCode::OK);

        let readiness = health_handler(State(Arc::clone(&pool))).await;
        assert_eq!(readiness.status(), StatusCode::SERVICE_UNAVAILABLE);

        pool.live.store(1, Ordering::SeqCst);
        let readiness_up = health_handler(State(Arc::clone(&pool))).await;
        assert_eq!(readiness_up.status(), StatusCode::OK);

        pool.closed.store(true, Ordering::SeqCst);
        let liveness_down = liveness_handler(State(Arc::clone(&pool))).await;
        assert_eq!(liveness_down.status(), StatusCode::SERVICE_UNAVAILABLE);
    }

    #[tokio::test]
    async fn cors_preflight_allows_authorization_and_echoes_requested_headers() {
        let res_default = cors_preflight(HeaderMap::new()).await;
        assert_eq!(res_default.status(), StatusCode::NO_CONTENT);
        let allow_default = res_default
            .headers()
            .get(header::ACCESS_CONTROL_ALLOW_HEADERS)
            .unwrap()
            .to_str()
            .unwrap();
        assert!(allow_default.contains("Authorization"));

        let mut req_headers = HeaderMap::new();
        req_headers.insert(
            header::ACCESS_CONTROL_REQUEST_HEADERS,
            HeaderValue::from_static("authorization, x-custom-header"),
        );
        let res_echo = cors_preflight(req_headers).await;
        assert_eq!(res_echo.status(), StatusCode::NO_CONTENT);
        assert_eq!(
            res_echo
                .headers()
                .get(header::ACCESS_CONTROL_ALLOW_HEADERS)
                .unwrap(),
            "authorization, x-custom-header"
        );
    }

    #[test]
    fn trace_id_sanitizes_control_characters() {
        let mut headers = HeaderMap::new();
        headers.insert("x-request-id", HeaderValue::from_static("abc-123_DEF"));
        assert_eq!(extract_trace_id(&headers), "abc-123_DEF");
        assert_eq!(
            sanitize_trace_id("trace\nforged=true"),
            Some("traceforgedtrue".to_string())
        );
    }
}
