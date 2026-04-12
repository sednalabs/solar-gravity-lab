use std::env;
use std::net::SocketAddr;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use aws_config::BehaviorVersion;
use aws_credential_types::Credentials;
use aws_sdk_s3::config::{Builder as S3ConfigBuilder, Region};
use aws_sdk_s3::primitives::ByteStream;
use aws_sdk_s3::Client as S3Client;
use axum::body::Body;
use axum::extract::{Path as AxumPath, Request, State};
use axum::http::header::{AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE, ETAG, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderValue, Response, StatusCode};
use axum::routing::get;
use axum::{serve, Router};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use bytes::Bytes;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

#[derive(Clone)]
struct AppState {
    cfg: Arc<Config>,
    mirror_tx: Option<mpsc::Sender<MirrorRequest>>,
    stats: Arc<CacheStats>,
}

#[derive(Clone)]
struct Config {
    bind_addr: SocketAddr,
    cache_root: PathBuf,
    max_object_bytes: usize,
    auth_username: String,
    auth_password: String,
    auth_realm: String,
    r2: Option<R2Config>,
}

#[derive(Clone)]
struct R2Config {
    bucket: String,
    key_prefix: String,
    client: S3Client,
}

#[derive(Clone)]
struct MirrorRequest {
    object_key: String,
    bytes: Bytes,
}

#[derive(Default)]
struct CacheStats {
    auth_failures: AtomicU64,
    puts: AtomicU64,
    get_local_hits: AtomicU64,
    get_r2_hits: AtomicU64,
    head_local_hits: AtomicU64,
    head_r2_hits: AtomicU64,
    misses: AtomicU64,
    mirror_enqueued: AtomicU64,
    mirror_successes: AtomicU64,
    mirror_enqueue_failures: AtomicU64,
    mirror_put_failures: AtomicU64,
    r2_read_errors: AtomicU64,
    local_read_errors: AtomicU64,
    local_write_errors: AtomicU64,
}

enum CacheReadSource {
    Local,
    R2,
}

impl CacheStats {
    fn record_hit(&self, head_only: bool, source: CacheReadSource) {
        let counter = match (head_only, source) {
            (false, CacheReadSource::Local) => &self.get_local_hits,
            (false, CacheReadSource::R2) => &self.get_r2_hits,
            (true, CacheReadSource::Local) => &self.head_local_hits,
            (true, CacheReadSource::R2) => &self.head_r2_hits,
        };
        counter.fetch_add(1, Ordering::Relaxed);
    }

    fn render(&self, r2_enabled: bool) -> String {
        format!(
            concat!(
                "r2_enabled {}\n",
                "auth_failures {}\n",
                "puts {}\n",
                "get_local_hits {}\n",
                "get_r2_hits {}\n",
                "head_local_hits {}\n",
                "head_r2_hits {}\n",
                "misses {}\n",
                "mirror_enqueued {}\n",
                "mirror_successes {}\n",
                "mirror_enqueue_failures {}\n",
                "mirror_put_failures {}\n",
                "r2_read_errors {}\n",
                "local_read_errors {}\n",
                "local_write_errors {}\n"
            ),
            if r2_enabled { 1 } else { 0 },
            self.auth_failures.load(Ordering::Relaxed),
            self.puts.load(Ordering::Relaxed),
            self.get_local_hits.load(Ordering::Relaxed),
            self.get_r2_hits.load(Ordering::Relaxed),
            self.head_local_hits.load(Ordering::Relaxed),
            self.head_r2_hits.load(Ordering::Relaxed),
            self.misses.load(Ordering::Relaxed),
            self.mirror_enqueued.load(Ordering::Relaxed),
            self.mirror_successes.load(Ordering::Relaxed),
            self.mirror_enqueue_failures.load(Ordering::Relaxed),
            self.mirror_put_failures.load(Ordering::Relaxed),
            self.r2_read_errors.load(Ordering::Relaxed),
            self.local_read_errors.load(Ordering::Relaxed),
            self.local_write_errors.load(Ordering::Relaxed),
        )
    }
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let cfg = Arc::new(
        load_config()
            .await
            .expect("failed to load cache service config"),
    );
    tokio::fs::create_dir_all(&cfg.cache_root)
        .await
        .expect("failed to create cache root");

    let stats = Arc::new(CacheStats::default());

    let mirror_tx = if let Some(r2) = cfg.r2.clone() {
        let (tx, rx) = mpsc::channel::<MirrorRequest>(64);
        tokio::spawn(mirror_worker(r2, rx, stats.clone()));
        Some(tx)
    } else {
        None
    };

    let state = AppState {
        cfg: cfg.clone(),
        mirror_tx,
        stats,
    };

    let app = Router::new()
        .route("/healthz", get(healthz))
        .route("/statsz", get(statsz))
        .route(
            "/cache/{*key}",
            get(get_cache_entry)
                .head(head_cache_entry)
                .put(put_cache_entry),
        )
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(cfg.bind_addr)
        .await
        .expect("failed to bind cache service");

    info!(
        bind = %cfg.bind_addr,
        cache_root = %cfg.cache_root.display(),
        r2_enabled = cfg.r2.is_some(),
        "solarlab-gradle-cache-service listening"
    );

    serve(listener, app)
        .with_graceful_shutdown(async {
            tokio::signal::ctrl_c().await.ok();
        })
        .await
        .expect("cache service crashed");
}

async fn healthz() -> &'static str {
    "ok"
}

async fn statsz(
    State(state): State<AppState>,
    request: Request,
) -> Result<Response<Body>, Response<Body>> {
    require_basic_auth(&request, &state.cfg, &state.stats)?;

    let mut response = Response::new(Body::from(state.stats.render(state.cfg.r2.is_some())));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    Ok(response)
}

async fn head_cache_entry(
    State(state): State<AppState>,
    AxumPath(key): AxumPath<String>,
    request: Request,
) -> Result<Response<Body>, Response<Body>> {
    require_basic_auth(&request, &state.cfg, &state.stats)?;
    respond_with_cache_entry(state, key, true).await
}

async fn get_cache_entry(
    State(state): State<AppState>,
    AxumPath(key): AxumPath<String>,
    request: Request,
) -> Result<Response<Body>, Response<Body>> {
    require_basic_auth(&request, &state.cfg, &state.stats)?;
    respond_with_cache_entry(state, key, false).await
}

async fn put_cache_entry(
    State(state): State<AppState>,
    AxumPath(key): AxumPath<String>,
    request: Request,
) -> Result<Response<Body>, Response<Body>> {
    require_basic_auth(&request, &state.cfg, &state.stats)?;

    let logical_key = sanitize_key(&key).map_err(error_response)?;
    let body = axum::body::to_bytes(request.into_body(), state.cfg.max_object_bytes)
        .await
        .map_err(|_| simple_response(StatusCode::PAYLOAD_TOO_LARGE, "Payload too large"))?;

    let local_path = local_path_for_key(&state.cfg.cache_root, &logical_key);
    write_local_cache_entry(&local_path, &body)
        .await
        .map_err(|err| {
            state
                .stats
                .local_write_errors
                .fetch_add(1, Ordering::Relaxed);
            error!(error = %err, path = %local_path.display(), "failed to write local cache entry");
            simple_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                "Failed to store cache entry",
            )
        })?;

    state.stats.puts.fetch_add(1, Ordering::Relaxed);
    info!(cache_key = %logical_key, bytes = body.len(), "cache put stored");

    if let (Some(mirror_tx), Some(r2)) = (&state.mirror_tx, &state.cfg.r2) {
        let request = MirrorRequest {
            object_key: r2_object_key(r2, &logical_key),
            bytes: body.clone(),
        };
        if let Err(err) = mirror_tx.send(request).await {
            state
                .stats
                .mirror_enqueue_failures
                .fetch_add(1, Ordering::Relaxed);
            warn!(error = %err, cache_key = %logical_key, "failed to queue R2 mirror request");
        } else {
            state.stats.mirror_enqueued.fetch_add(1, Ordering::Relaxed);
        }
    }

    Ok(Response::new(Body::empty()))
}

async fn respond_with_cache_entry(
    state: AppState,
    key: String,
    head_only: bool,
) -> Result<Response<Body>, Response<Body>> {
    let logical_key = sanitize_key(&key).map_err(error_response)?;
    let local_path = local_path_for_key(&state.cfg.cache_root, &logical_key);

    if let Some(bytes) = read_local_cache_entry(&local_path).await.map_err(|err| {
        state
            .stats
            .local_read_errors
            .fetch_add(1, Ordering::Relaxed);
        error!(error = %err, path = %local_path.display(), "failed to read local cache entry");
        simple_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Failed to read local cache entry",
        )
    })? {
        state.stats.record_hit(head_only, CacheReadSource::Local);
        info!(
            method = request_method(head_only),
            cache_key = %logical_key,
            source = "local",
            bytes = bytes.len(),
            "cache hit"
        );
        return Ok(cache_hit_response(bytes, head_only));
    }

    if let Some(r2) = &state.cfg.r2 {
        match read_from_r2(r2, &logical_key).await {
            Ok(Some(bytes)) => {
                state.stats.record_hit(head_only, CacheReadSource::R2);
                if let Err(err) = write_local_cache_entry(&local_path, &bytes).await {
                    state
                        .stats
                        .local_write_errors
                        .fetch_add(1, Ordering::Relaxed);
                    warn!(error = %err, path = %local_path.display(), "failed to rehydrate local cache entry from R2");
                }
                info!(
                    method = request_method(head_only),
                    cache_key = %logical_key,
                    source = "r2",
                    bytes = bytes.len(),
                    "cache hit"
                );
                return Ok(cache_hit_response(bytes, head_only));
            }
            Ok(None) => {}
            Err(err) => {
                state.stats.r2_read_errors.fetch_add(1, Ordering::Relaxed);
                warn!(error = %err, key = %logical_key, "failed to read cache entry from R2");
            }
        }
    }

    state.stats.misses.fetch_add(1, Ordering::Relaxed);
    info!(method = request_method(head_only), cache_key = %logical_key, "cache miss");
    Err(simple_response(StatusCode::NOT_FOUND, "Not Found"))
}

fn require_basic_auth(
    request: &Request,
    cfg: &Config,
    stats: &CacheStats,
) -> Result<(), Response<Body>> {
    if basic_auth_valid(request.headers(), cfg) {
        return Ok(());
    }

    stats.auth_failures.fetch_add(1, Ordering::Relaxed);
    warn!(
        method = %request.method(),
        path = %request.uri().path(),
        "rejected unauthorized cache request"
    );
    let mut response = simple_response(StatusCode::UNAUTHORIZED, "Unauthorized");
    let challenge = format!("Basic realm=\"{}\"", cfg.auth_realm);
    if let Ok(challenge) = HeaderValue::from_str(&challenge) {
        response.headers_mut().insert(WWW_AUTHENTICATE, challenge);
    }
    Err(response)
}

fn basic_auth_valid(headers: &HeaderMap, cfg: &Config) -> bool {
    let Some(raw) = headers
        .get(AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    let Some(encoded) = raw.strip_prefix("Basic ") else {
        return false;
    };
    let Ok(decoded) = STANDARD.decode(encoded) else {
        return false;
    };
    let Ok(decoded) = String::from_utf8(decoded) else {
        return false;
    };
    let Some((username, password)) = decoded.split_once(':') else {
        return false;
    };
    username == cfg.auth_username && password == cfg.auth_password
}

fn cache_hit_response(bytes: Bytes, head_only: bool) -> Response<Body> {
    let mut response = Response::new(if head_only {
        Body::empty()
    } else {
        Body::from(bytes.clone())
    });
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    response.headers_mut().insert(
        CONTENT_LENGTH,
        HeaderValue::from_str(&bytes.len().to_string())
            .unwrap_or_else(|_| HeaderValue::from_static("0")),
    );
    response.headers_mut().insert(
        ETAG,
        HeaderValue::from_static("W/\"solarlab-gradle-cache\""),
    );
    response
}

fn request_method(head_only: bool) -> &'static str {
    if head_only {
        "HEAD"
    } else {
        "GET"
    }
}

fn sanitize_key(input: &str) -> Result<String, &'static str> {
    let path = Path::new(input);
    let mut cleaned = Vec::new();

    for component in path.components() {
        match component {
            Component::Normal(value) => {
                let segment = value.to_string_lossy();
                if segment.is_empty() {
                    return Err("Empty cache key segment");
                }
                cleaned.push(segment.to_string());
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err("Invalid cache key");
            }
        }
    }

    if cleaned.is_empty() {
        return Err("Missing cache key");
    }

    Ok(cleaned.join("/"))
}

fn local_path_for_key(root: &Path, key: &str) -> PathBuf {
    root.join("v1").join(key)
}

async fn read_local_cache_entry(path: &Path) -> Result<Option<Bytes>, std::io::Error> {
    match tokio::fs::read(path).await {
        Ok(bytes) => Ok(Some(Bytes::from(bytes))),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(err) => Err(err),
    }
}

async fn write_local_cache_entry(path: &Path, bytes: &[u8]) -> Result<(), std::io::Error> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let temp_path = path.with_extension("tmp");
    tokio::fs::write(&temp_path, bytes).await?;
    tokio::fs::rename(temp_path, path).await
}

async fn mirror_worker(
    r2: R2Config,
    mut rx: mpsc::Receiver<MirrorRequest>,
    stats: Arc<CacheStats>,
) {
    while let Some(request) = rx.recv().await {
        let put_result = r2
            .client
            .put_object()
            .bucket(&r2.bucket)
            .key(request.object_key)
            .body(ByteStream::from(request.bytes))
            .content_type("application/octet-stream")
            .send()
            .await;

        if let Err(err) = put_result {
            stats.mirror_put_failures.fetch_add(1, Ordering::Relaxed);
            warn!(error = %err, "failed to mirror cache entry to R2");
        } else {
            stats.mirror_successes.fetch_add(1, Ordering::Relaxed);
        }
    }
}

async fn read_from_r2(r2: &R2Config, logical_key: &str) -> Result<Option<Bytes>, String> {
    let key = r2_object_key(r2, logical_key);
    let object = match r2
        .client
        .get_object()
        .bucket(&r2.bucket)
        .key(key)
        .send()
        .await
    {
        Ok(object) => object,
        Err(err) => {
            let missing = err
                .as_service_error()
                .is_some_and(|service_err| service_err.is_no_such_key());
            if missing {
                return Ok(None);
            }
            return Err(err.to_string());
        }
    };

    let bytes = object
        .body
        .collect()
        .await
        .map_err(|err| err.to_string())?
        .into_bytes();
    Ok(Some(bytes))
}

fn r2_object_key(r2: &R2Config, logical_key: &str) -> String {
    format!("{}/{}", r2.key_prefix.trim_end_matches('/'), logical_key)
}

fn error_response(message: &'static str) -> Response<Body> {
    simple_response(StatusCode::BAD_REQUEST, message)
}

fn simple_response(status: StatusCode, message: &'static str) -> Response<Body> {
    let mut response = Response::new(Body::from(message.to_string()));
    *response.status_mut() = status;
    response
}

async fn load_config() -> Result<Config, String> {
    let bind_addr = env::var("GRADLE_CACHE_BIND")
        .unwrap_or_else(|_| "127.0.0.1:8789".to_string())
        .parse()
        .map_err(|err| format!("GRADLE_CACHE_BIND must be a socket address: {err}"))?;

    let cache_root = if let Ok(value) = env::var("GRADLE_CACHE_ROOT") {
        PathBuf::from(value)
    } else {
        let home =
            env::var("HOME").map_err(|_| "HOME or GRADLE_CACHE_ROOT must be set".to_string())?;
        PathBuf::from(home).join(".cache/solarlab-gradle-cache")
    };

    let max_object_bytes = env::var("GRADLE_CACHE_MAX_OBJECT_BYTES")
        .ok()
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(268_435_456);

    let auth_username = env::var("GRADLE_CACHE_BASIC_AUTH_USER")
        .map_err(|_| "GRADLE_CACHE_BASIC_AUTH_USER is required".to_string())?;
    let auth_password = env::var("GRADLE_CACHE_BASIC_AUTH_PASS")
        .map_err(|_| "GRADLE_CACHE_BASIC_AUTH_PASS is required".to_string())?;
    let auth_realm = env::var("GRADLE_CACHE_BASIC_AUTH_REALM")
        .unwrap_or_else(|_| "solarlab-gradle-cache".to_string());

    let r2 = load_r2_config().await?;

    Ok(Config {
        bind_addr,
        cache_root,
        max_object_bytes,
        auth_username,
        auth_password,
        auth_realm,
        r2,
    })
}

async fn load_r2_config() -> Result<Option<R2Config>, String> {
    let endpoint = env::var("GRADLE_CACHE_R2_ENDPOINT").ok();
    let bucket = env::var("GRADLE_CACHE_R2_BUCKET").ok();
    let access_key_id = env::var("GRADLE_CACHE_R2_ACCESS_KEY_ID").ok();
    let secret_access_key = env::var("GRADLE_CACHE_R2_SECRET_ACCESS_KEY").ok();

    if endpoint.is_none()
        && bucket.is_none()
        && access_key_id.is_none()
        && secret_access_key.is_none()
    {
        return Ok(None);
    }

    let endpoint = endpoint.ok_or_else(|| {
        "GRADLE_CACHE_R2_ENDPOINT is required when R2 mirroring is enabled".to_string()
    })?;
    let bucket = bucket.ok_or_else(|| {
        "GRADLE_CACHE_R2_BUCKET is required when R2 mirroring is enabled".to_string()
    })?;
    let access_key_id = access_key_id.ok_or_else(|| {
        "GRADLE_CACHE_R2_ACCESS_KEY_ID is required when R2 mirroring is enabled".to_string()
    })?;
    let secret_access_key = secret_access_key.ok_or_else(|| {
        "GRADLE_CACHE_R2_SECRET_ACCESS_KEY is required when R2 mirroring is enabled".to_string()
    })?;
    let region = env::var("GRADLE_CACHE_R2_REGION").unwrap_or_else(|_| "auto".to_string());
    let key_prefix = env::var("GRADLE_CACHE_R2_KEY_PREFIX")
        .unwrap_or_else(|_| "solar-gravity-lab/gradle/v1".to_string());

    let shared_config = aws_config::defaults(BehaviorVersion::latest())
        .credentials_provider(Credentials::new(
            access_key_id,
            secret_access_key,
            None,
            None,
            "solarlab-gradle-cache-service",
        ))
        .region(Region::new(region))
        .load()
        .await;

    let sdk_config = S3ConfigBuilder::from(&shared_config)
        .endpoint_url(endpoint)
        .force_path_style(true)
        .build();

    Ok(Some(R2Config {
        bucket,
        key_prefix,
        client: S3Client::from_conf(sdk_config),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_key_rejects_parent_segments() {
        assert!(sanitize_key("../oops").is_err());
        assert!(sanitize_key("/absolute").is_err());
    }

    #[test]
    fn sanitize_key_normalizes_curdir_segments() {
        assert_eq!(sanitize_key("./abc/def").unwrap(), "abc/def");
    }

    #[test]
    fn local_path_is_versioned() {
        let path = local_path_for_key(Path::new("/tmp/cache-root"), "abc/def");
        assert_eq!(path, PathBuf::from("/tmp/cache-root/v1/abc/def"));
    }
}
