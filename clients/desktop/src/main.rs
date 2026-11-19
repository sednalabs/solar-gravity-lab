//! A local desktop host. Rust owns flight; the packaged browser client only
//! renders immutable telemetry and submits bounded input commands.
mod flight;

use flight::{Action, FlightSession};
use serde_json::json;
use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_HEADER_BYTES: usize = 8192;
const MAX_DOCUMENT_BYTES: usize = 2 * 1024 * 1024;
const MAX_COMMAND_BYTES: usize = 8192;
const CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

struct Request {
    method: String,
    path: String,
    headers: BTreeMap<String, String>,
    body: Vec<u8>,
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct CommandRequest {
    generation: u64,
    command: Action,
}

impl CommandRequest {
    fn apply(self, flight: &mut FlightSession) -> Result<serde_json::Value, String> {
        if matches!(&self.command, Action::Heartbeat) {
            // The watchdog can revoke a generation after the browser sends its
            // heartbeat. Return the new state, but only a matching heartbeat
            // may renew contact. Stale input never gains authority this way.
            let accepted = self.generation == flight.generation;
            let mut state = if accepted {
                flight.handle(self.command)?
            } else {
                flight.state()?
            };
            state["heartbeatAccepted"] = json!(accepted);
            return Ok(state);
        }
        if self.generation != flight.generation {
            return Err("Flight changed; refresh its controls".into());
        }
        flight.handle(self.command)
    }
}

impl Request {
    fn read(stream: &mut TcpStream) -> Result<Self, String> {
        let mut buffer = Vec::new();
        let boundary = loop {
            let mut chunk = [0; 1024];
            let count = stream
                .read(&mut chunk)
                .map_err(|_| "Request read timed out")?;
            if count == 0 {
                return Err("Incomplete request".into());
            }
            buffer.extend_from_slice(&chunk[..count]);
            if let Some(index) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
                if index > MAX_HEADER_BYTES {
                    return Err("Request headers exceed limit".into());
                }
                break index + 4;
            }
            if buffer.len() > MAX_HEADER_BYTES {
                return Err("Request headers exceed limit".into());
            }
        };
        let header =
            std::str::from_utf8(&buffer[..boundary]).map_err(|_| "Headers must be UTF-8")?;
        let mut lines = header.split("\r\n");
        let first: Vec<_> = lines
            .next()
            .ok_or("Request line missing")?
            .split_whitespace()
            .collect();
        if first.len() != 3 || first[2] != "HTTP/1.1" {
            return Err("HTTP/1.1 request required".into());
        }
        let method = first[0].to_owned();
        let path = first[1].to_owned();
        if !matches!(method.as_str(), "GET" | "POST") {
            return Err("Only GET and POST are available".into());
        }
        let mut headers = BTreeMap::new();
        for line in lines.filter(|line| !line.is_empty()) {
            let (name, value) = line.split_once(':').ok_or("Malformed header")?;
            if headers
                .insert(name.to_ascii_lowercase(), value.trim().to_owned())
                .is_some()
            {
                return Err("Duplicate headers are not supported".into());
            }
        }
        if headers.contains_key("transfer-encoding") {
            return Err("A bounded Content-Length is required".into());
        }
        let length = headers
            .get("content-length")
            .map_or(Ok(0), |v| v.parse::<usize>())
            .map_err(|_| "Invalid Content-Length")?;
        let limit = if path == "/api/import" {
            MAX_DOCUMENT_BYTES
        } else {
            MAX_COMMAND_BYTES
        };
        if length > limit || (method == "GET" && length != 0) {
            return Err("Request body exceeds the route limit".into());
        }
        while buffer.len() < boundary + length {
            let remaining = boundary + length - buffer.len();
            let mut chunk = [0; 4096];
            let count = stream
                .read(&mut chunk[..remaining.min(4096)])
                .map_err(|_| "Body read timed out")?;
            if count == 0 {
                return Err("Incomplete body".into());
            }
            buffer.extend_from_slice(&chunk[..count]);
        }
        Ok(Self {
            method,
            path,
            headers,
            body: buffer[boundary..boundary + length].to_vec(),
        })
    }

    fn allowed(&self, host: &str, token: &str) -> bool {
        if self.headers.get("host").is_none_or(|v| v != host) {
            return false;
        }
        let origin = format!("http://{host}");
        if self.headers.get("origin").is_some_and(|v| v != &origin)
            || self
                .headers
                .get("sec-fetch-site")
                .is_some_and(|v| v == "cross-site")
        {
            return false;
        }
        if self.path.starts_with("/api/")
            && self.path != "/api/session"
            && self.headers.get("x-sgl-token").is_none_or(|v| v != token)
        {
            return false;
        }
        if self.method == "POST" {
            self.headers.get("origin") == Some(&origin)
                && self
                    .headers
                    .get("content-type")
                    .is_some_and(|v| v.split(';').next() == Some("application/json"))
        } else {
            true
        }
    }
}

fn respond(stream: &mut TcpStream, status: u16, mime: &str, body: &[u8], attachment: bool) {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        _ => "Unavailable",
    };
    let disposition = if attachment {
        "Content-Disposition: attachment; filename=sgl-desktop-flight.json\r\n"
    } else {
        ""
    };
    let header = format!("HTTP/1.1 {status} {reason}\r\nContent-Type: {mime}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {CSP}\r\n{disposition}\r\n", body.len());
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(body);
}

fn serve(mut stream: TcpStream, session: &Arc<Mutex<FlightSession>>, host: &str, token: &str) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    let request = match Request::read(&mut stream) {
        Ok(r) => r,
        Err(e) => {
            respond(
                &mut stream,
                400,
                "application/json",
                json!({"error":e}).to_string().as_bytes(),
                false,
            );
            return;
        }
    };
    if !request.allowed(host, token) {
        respond(
            &mut stream,
            403,
            "text/plain",
            b"Local flight session required",
            false,
        );
        return;
    }
    if request.method == "GET" {
        if request.path == "/assets/spacecraft.json" {
            let assets: Vec<_> = solarlab_data::SPACECRAFT_VISUAL_ASSETS
                .iter()
                .map(|asset| {
                    json!({"assetId":asset.asset_id,"meshKind":asset.mesh_kind,
                    "geometrySha256":asset.geometry_sha256,"framingRadiusM":asset.framing_radius_m,
                    "url":format!("/assets/spacecraft/{}.bin",asset.mesh_kind)})
                })
                .collect();
            respond(
                &mut stream,
                200,
                "application/json",
                json!({"vertexStrideFloats":13,"assets":assets})
                    .to_string()
                    .as_bytes(),
                false,
            );
            return;
        }
        for asset in solarlab_data::SPACECRAFT_VISUAL_ASSETS {
            if request.path == format!("/assets/spacecraft/{}.bin", asset.mesh_kind) {
                respond(
                    &mut stream,
                    200,
                    "application/octet-stream",
                    asset.vertex_payload,
                    false,
                );
                return;
            }
        }
        let asset: Option<(&str, &[u8])> = match request.path.as_str() {
            "/" => Some(("text/html; charset=utf-8", include_bytes!("../web/index.html"))),
            "/flight.css" => Some(("text/css; charset=utf-8", include_bytes!("../web/flight.css"))),
            "/flight.js" => Some(("text/javascript; charset=utf-8", include_bytes!("../web/flight.js"))),
            "/renderer.js" => Some(("text/javascript; charset=utf-8", include_bytes!("../web/renderer.js"))),
            "/favicon.svg" => Some(("image/svg+xml", br##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"><rect width="40" height="40" rx="10" fill="#0b1824"/><g fill="none" stroke="#9be6d9" stroke-width="1.5"><circle cx="20" cy="20" r="16"/><path d="M20 7 29 29 20 24 11 29Z"/></g></svg>"##)),
            "/assets/earth.png" => Some(("image/png", include_bytes!("../../../render/android-vulkan/src/main/assets/solarlab/surfaces/earth-nasa.png"))),
            "/assets/moon.jpg" => Some(("image/jpeg", include_bytes!("../../../render/android-vulkan/src/main/assets/solarlab/surfaces/moon-nasa.jpg"))),
            _ => None,
        };
        if let Some((mime, bytes)) = asset {
            respond(&mut stream, 200, mime, bytes, false);
            return;
        }
    }
    let Ok(mut flight) = session.lock() else {
        respond(&mut stream, 503, "text/plain", b"Flight unavailable", false);
        return;
    };
    if request.method == "GET" && request.path == "/api/save" {
        match flight.world.export_saved_session() {
            Ok(saved) => respond(&mut stream, 200, "application/json", &saved, true),
            Err(e) => respond(
                &mut stream,
                400,
                "application/json",
                json!({"error":format!("{e:?}")}).to_string().as_bytes(),
                false,
            ),
        }
        return;
    }
    let result = match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/api/session") => Ok(
            json!({"token":token, "nextControlSequence":flight.last_control_sequence.saturating_add(1), "maxDocumentBytes":MAX_DOCUMENT_BYTES}),
        ),
        ("GET", "/api/state") => flight.state(),
        ("POST", "/api/command") => serde_json::from_slice::<CommandRequest>(&request.body)
            .map_err(|e| e.to_string())
            .and_then(|request| request.apply(&mut flight)),
        ("POST", "/api/import") => flight.import(&request.body).and_then(|()| flight.state()),
        _ => {
            respond(&mut stream, 404, "text/plain", b"Route unavailable", false);
            return;
        }
    };
    match result {
        Ok(value) => respond(
            &mut stream,
            200,
            "application/json",
            value.to_string().as_bytes(),
            false,
        ),
        Err(error) => respond(
            &mut stream,
            400,
            "application/json",
            json!({"error":error}).to_string().as_bytes(),
            false,
        ),
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let port: u16 = match args.next().as_deref() {
        None => 8787,
        Some("--port") => args.next().ok_or("--port requires a number")?.parse()?,
        Some(_) => return Err("Usage: solarlab-desktop [--port PORT]".into()),
    };
    if args.next().is_some() {
        return Err("Usage: solarlab-desktop [--port PORT]".into());
    }
    let listener = TcpListener::bind(("127.0.0.1", port))?;
    let host = listener.local_addr()?.to_string();
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).map_err(|e| format!("Session entropy unavailable: {e}"))?;
    let token: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let session = Arc::new(Mutex::new(FlightSession::new()?));
    let ticking = Arc::clone(&session);
    std::thread::spawn(move || {
        let mut previous = Instant::now();
        loop {
            std::thread::sleep(Duration::from_millis(20));
            let now = Instant::now();
            let elapsed = now.duration_since(previous).as_secs_f64();
            previous = now;
            if let Ok(mut flight) = ticking.lock() {
                flight.tick(elapsed);
            } else {
                break;
            }
        }
    });
    let (sender, receiver) = mpsc::sync_channel::<TcpStream>(8);
    let receiver = Arc::new(Mutex::new(receiver));
    for _ in 0..4 {
        let receiver = Arc::clone(&receiver);
        let flight = Arc::clone(&session);
        let host = host.clone();
        let token = token.clone();
        std::thread::spawn(move || loop {
            let stream = receiver.lock().ok().and_then(|r| r.recv().ok());
            if let Some(stream) = stream {
                serve(stream, &flight, &host, &token);
            } else {
                break;
            }
        });
    }
    println!("Solar Gravity Lab desktop flight: http://{host}");
    println!("Native Rust flight. Open this address locally; Ctrl-C stops the host. Saves export as portable SGL documents.");
    for stream in listener.incoming() {
        if let Ok(stream) = stream {
            if let Err(mpsc::TrySendError::Full(mut stream)) = sender.try_send(stream) {
                respond(&mut stream, 503, "text/plain", b"Flight host busy", false);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests;
