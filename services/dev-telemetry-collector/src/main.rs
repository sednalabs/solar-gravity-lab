use std::env;
use std::net::SocketAddr;
use std::path::PathBuf;

use axum::extract::State;
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use tokio::fs::OpenOptions;
use tokio::io::AsyncWriteExt;
use tracing::{info, warn};

#[derive(Clone)]
struct AppState {
    auth_token: Option<String>,
    log_path: Option<PathBuf>,
}

#[derive(Debug, Deserialize, Serialize)]
struct TelemetryEnvelope {
    captured_at_unix_ms: i64,
    source: String,
    session_id: String,
    app: TelemetryAppInfo,
    device: TelemetryDeviceInfo,
    events: Vec<TelemetryEvent>,
}

#[derive(Debug, Deserialize, Serialize)]
struct TelemetryAppInfo {
    application_id: String,
    version_name: String,
    version_code: i64,
}

#[derive(Debug, Deserialize, Serialize)]
struct TelemetryDeviceInfo {
    manufacturer: String,
    model: String,
    sdk_int: i64,
}

#[derive(Debug, Deserialize, Serialize)]
struct TelemetryEvent {
    recorded_at_unix_ms: i64,
    level: String,
    category: String,
    message: String,
}

#[derive(Debug, Serialize)]
struct AcceptedResponse {
    accepted: usize,
}

#[derive(Debug, Serialize)]
struct HealthResponse<'a> {
    status: &'a str,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let bind_addr =
        env::var("SOLARLAB_DEV_TELEMETRY_BIND").unwrap_or_else(|_| "127.0.0.1:8787".to_string());
    let socket_addr: SocketAddr = bind_addr
        .parse()
        .expect("SOLARLAB_DEV_TELEMETRY_BIND must be a valid socket address");

    let state = AppState {
        auth_token: env::var("SOLARLAB_DEV_TELEMETRY_TOKEN")
            .ok()
            .and_then(|token| {
                let trimmed = token.trim().to_string();
                (!trimmed.is_empty()).then_some(trimmed)
            }),
        log_path: env::var("SOLARLAB_DEV_TELEMETRY_LOG")
            .ok()
            .and_then(|path| {
                let trimmed = path.trim().to_string();
                (!trimmed.is_empty()).then_some(PathBuf::from(trimmed))
            }),
    };

    let app = build_app(state);
    let listener = tokio::net::TcpListener::bind(socket_addr)
        .await
        .expect("failed to bind telemetry collector");

    info!(bind = %socket_addr, "developer telemetry collector listening");
    axum::serve(listener, app)
        .await
        .expect("developer telemetry collector crashed");
}

fn build_app(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route(
            "/v1/android/developer-telemetry",
            post(ingest_android_telemetry),
        )
        .with_state(state)
}

async fn healthz() -> impl IntoResponse {
    (StatusCode::OK, Json(HealthResponse { status: "ok" }))
}

async fn ingest_android_telemetry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(envelope): Json<TelemetryEnvelope>,
) -> impl IntoResponse {
    if !is_authorized(&headers, state.auth_token.as_deref()) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(AcceptedResponse { accepted: 0 }),
        );
    }

    if envelope.events.is_empty() {
        return (StatusCode::ACCEPTED, Json(AcceptedResponse { accepted: 0 }));
    }

    info!(
        session_id = %envelope.session_id,
        app = %envelope.app.application_id,
        model = %envelope.device.model,
        accepted = envelope.events.len(),
        "accepted developer telemetry batch",
    );

    if let Some(log_path) = state.log_path.as_ref() {
        if let Err(error) = append_ndjson(log_path, &envelope).await {
            warn!(path = %log_path.display(), error = %error, "failed to append telemetry batch to log");
        }
    }

    (
        StatusCode::ACCEPTED,
        Json(AcceptedResponse {
            accepted: envelope.events.len(),
        }),
    )
}

fn is_authorized(headers: &HeaderMap, expected_token: Option<&str>) -> bool {
    let Some(expected_token) = expected_token else {
        return true;
    };

    headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .and_then(|provided| provided.strip_prefix("Bearer "))
        .is_some_and(|provided_token| provided_token == expected_token)
}

async fn append_ndjson(path: &PathBuf, envelope: &TelemetryEnvelope) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let encoded = serde_json::to_vec(envelope).expect("telemetry envelope should serialize");
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .await?;
    file.write_all(&encoded).await?;
    file.write_all(b"\n").await?;
    file.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    #[tokio::test]
    async fn rejects_batches_without_expected_bearer_token() {
        let app = build_app(AppState {
            auth_token: Some("secret".to_string()),
            log_path: None,
        });

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/v1/android/developer-telemetry")
                    .method("POST")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(sample_envelope_json()))
                    .expect("request should build"),
            )
            .await
            .expect("router should respond");

        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn accepts_batches_with_expected_bearer_token() {
        let app = build_app(AppState {
            auth_token: Some("secret".to_string()),
            log_path: None,
        });

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/v1/android/developer-telemetry")
                    .method("POST")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header(header::AUTHORIZATION, "Bearer secret")
                    .body(Body::from(sample_envelope_json()))
                    .expect("request should build"),
            )
            .await
            .expect("router should respond");

        assert_eq!(response.status(), StatusCode::ACCEPTED);
    }

    fn sample_envelope_json() -> &'static str {
        r#"{
          "captured_at_unix_ms": 123,
          "source": "clients/android",
          "session_id": "session-1",
          "app": {
            "application_id": "com.sednalabs.solarlab.internal",
            "version_name": "0.1.0-alpha.10",
            "version_code": 11
          },
          "device": {
            "manufacturer": "Samsung",
            "model": "Galaxy S25 Ultra",
            "sdk_int": 35
          },
          "events": [
            {
              "recorded_at_unix_ms": 100,
              "level": "info",
              "category": "session.start",
              "message": "Opening runtime session"
            }
          ]
        }"#
    }
}
