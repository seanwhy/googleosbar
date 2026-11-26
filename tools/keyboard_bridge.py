#!/usr/bin/env python3
"""Persistent HTTP/1.1 Keep-Alive Tailscale keyboard bridge for Debian VM (port 19879)."""

from __future__ import annotations

import base64
import hashlib
import http.client
import http.server
import ipaddress
import json
import os
import re
import secrets
import socket
import sys
import threading
import time
import urllib.parse
from typing import Any, Callable

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

import nettransfer_bridge

BRIDGE_PORT = 19879
DEFAULT_TARGET_PORT = 19878
TAILSCALE_CACHE_TTL_SEC = 15.0
CONNECT_TIMEOUT_SEC = 0.5
READ_TIMEOUT_SEC = 1.5
VPS_READ_TIMEOUT_SEC = 6.0
POOL_MAX_IDLE_SEC = 60.0
POOL_MAX_PER_TARGET = 4
CLIENT_IDLE_TIMEOUT_SEC = 60.0
MAX_BODY_BYTES = 1024 * 1024

AGENT_API_VERSION = 1
SIGNATURE_VERSION = "v1"
DEFAULT_IDENTITY_PATHS = (
    "/home/droid/.config/nettransfer/agent.json",
    "/mnt/shared/Codes/.nettransfer/agent.json",
    nettransfer_bridge.AGENT_CONFIG,
)


class VpsIdentityError(RuntimeError):
    pass


class VpsUpstreamError(RuntimeError):
    pass


def load_vm_identity(paths: tuple[str, ...] | list[str] = DEFAULT_IDENTITY_PATHS) -> dict[str, Any] | None:
    for path in paths:
        if not path or not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        if bool(data.get("revoked")):
            continue
        device_id = str(data.get("deviceId") or "").strip()
        private_key = str(data.get("privateKey") or "").strip()
        if not device_id or not private_key:
            continue
        try:
            key_bytes = base64.b64decode(private_key, validate=True)
        except Exception:
            continue
        if len(key_bytes) not in (32, 64):
            continue
        vps_url = str(data.get("vpsUrl") or nettransfer_bridge.DEFAULT_VPS_URL).strip().rstrip("/")
        if not (vps_url.startswith("http://") or vps_url.startswith("https://")):
            vps_url = nettransfer_bridge.DEFAULT_VPS_URL
        display_name = str(data.get("displayName") or "").strip() or "googlebook"
        return {
            "vpsUrl": vps_url,
            "displayName": display_name,
            "deviceId": device_id,
            "privateKey": private_key,
        }
    return None


def sign_digest(
    identity: dict[str, Any],
    method: str,
    digest_base64: str,
    unix_seconds: int,
    nonce: str,
    scope: str | None = None,
) -> str:
    device_id = str(identity.get("deviceId") or "").strip()
    priv_b64 = str(identity.get("privateKey") or "").strip()
    if not device_id or not priv_b64:
        raise VpsIdentityError("NetTransfer identity is missing deviceId or privateKey")
    key_bytes = base64.b64decode(priv_b64)
    if len(key_bytes) not in (32, 64):
        raise ValueError("NetTransfer privateKey must be 32 or 64 bytes")
    priv = Ed25519PrivateKey.from_private_bytes(key_bytes[:32])
    upper_method = str(method or "GET").strip().upper()
    clean_digest = digest_base64.strip()
    msg = f"{SIGNATURE_VERSION}:{device_id}:{int(unix_seconds)}:{nonce}:{upper_method}:{clean_digest}"
    if scope and scope.strip():
        msg = f"{msg}:{scope.strip()}"
    sig = priv.sign(msg.encode("utf-8"))
    return base64.b64encode(sig).decode("ascii")


def build_agent_headers(
    identity: dict[str, Any],
    method: str,
    body_bytes: bytes,
    unix_seconds: int | None = None,
    nonce: str | None = None,
    scope: str | None = None,
) -> dict[str, str]:
    ts = int(time.time()) if unix_seconds is None else int(unix_seconds)
    req_nonce = nonce if nonce is not None else secrets.token_hex(12)
    digest = base64.b64encode(hashlib.sha256(body_bytes).digest()).decode("ascii")
    sig = sign_digest(identity, method, digest, ts, req_nonce, scope)
    return {
        "X-NetTransfer-Device": str(identity["deviceId"]).strip(),
        "X-NetTransfer-Agent-API": str(AGENT_API_VERSION),
        "X-NetTransfer-Timestamp": str(ts),
        "X-NetTransfer-Nonce": req_nonce,
        "X-NetTransfer-Body-Digest": digest,
        "X-NetTransfer-Signature": sig,
        "User-Agent": f"NetTransfer-Agent/{AGENT_API_VERSION}",
    }


def _read_vps_error_detail(body_bytes: bytes, status: int) -> str:
    text = body_bytes.decode("utf-8", "replace").strip() if body_bytes else ""
    if text:
        try:
            obj = json.loads(text)
            if isinstance(obj, dict) and obj.get("error"):
                return f"HTTP {status}: {obj['error']}"
        except Exception:
            pass
        return f"HTTP {status}: {text[:200]}"
    return f"HTTP {status}"


class VpsConnectionPool:
    """Thread-safe HTTP/HTTPS keep-alive connection pool for VPS relay requests."""

    def __init__(self, timeout_sec: float = VPS_READ_TIMEOUT_SEC, max_idle_sec: float = POOL_MAX_IDLE_SEC):
        self.timeout_sec = timeout_sec
        self.max_idle_sec = max_idle_sec
        self._lock = threading.Lock()
        self._pool: dict[tuple[str, str, int], list[tuple[http.client.HTTPConnection, float]]] = {}

    def _open(self, scheme: str, host: str, port: int) -> http.client.HTTPConnection:
        if scheme == "https":
            conn: http.client.HTTPConnection = http.client.HTTPSConnection(host, port, timeout=self.timeout_sec)
        else:
            conn = http.client.HTTPConnection(host, port, timeout=self.timeout_sec)
        conn.connect()
        if conn.sock is not None:
            try:
                conn.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            conn.sock.settimeout(self.timeout_sec)
        return conn

    def request(
        self,
        method: str,
        url: str,
        headers: dict[str, str] | None = None,
        body: bytes | None = None,
    ) -> tuple[int, bytes]:
        parsed = urllib.parse.urlparse(url)
        scheme = (parsed.scheme or "https").lower()
        host = parsed.hostname or ""
        if not host:
            raise ValueError(f"invalid VPS URL: {url}")
        port = parsed.port or (443 if scheme == "https" else 80)
        path = parsed.path or "/"
        if parsed.query:
            path = f"{path}?{parsed.query}"

        key = (scheme, host, port)
        now = time.monotonic()
        conn: http.client.HTTPConnection | None = None
        was_reused = False
        stale: list[http.client.HTTPConnection] = []
        with self._lock:
            bucket = self._pool.get(key)
            while bucket:
                candidate, last_used = bucket.pop()
                if (now - last_used) > self.max_idle_sec or candidate.sock is None:
                    stale.append(candidate)
                    continue
                conn = candidate
                was_reused = True
                break
        for s in stale:
            TargetConnectionPool._close_quietly(s)

        req_headers = {"Connection": "keep-alive"}
        if headers:
            req_headers.update(headers)

        for attempt in (0, 1):
            if conn is None:
                conn = self._open(scheme, host, port)
                was_reused = False
            try:
                conn.request(method, path, body=body, headers=req_headers)
                resp = conn.getresponse()
                resp_body = resp.read()
                conn_hdr = str(resp.getheader("Connection") or "").lower()
                if resp.will_close or "close" in conn_hdr:
                    TargetConnectionPool._close_quietly(conn)
                else:
                    with self._lock:
                        bucket = self._pool.setdefault(key, [])
                        if len(bucket) >= 4:
                            dropped, _ = bucket.pop(0)
                            TargetConnectionPool._close_quietly(dropped)
                        bucket.append((conn, time.monotonic()))
                return resp.status, resp_body
            except (http.client.HTTPException, OSError, EOFError):
                TargetConnectionPool._close_quietly(conn)
                conn = None
                if attempt == 0 and was_reused:
                    continue
                raise

        raise RuntimeError("unreachable VPS connection retry state")

    def close_all(self) -> None:
        with self._lock:
            conns = [c for bucket in self._pool.values() for c, _ in bucket]
            self._pool.clear()
        for c in conns:
            TargetConnectionPool._close_quietly(c)


_SAFE_HOSTNAME_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")


def validate_target_host_port(host: Any, port: Any = DEFAULT_TARGET_PORT) -> tuple[str, int]:
    clean_host = str(host or "").strip()
    if not clean_host or len(clean_host) > 253:
        raise ValueError("missing or invalid target host")
    try:
        ipaddress.ip_address(clean_host)
    except ValueError:
        if not _SAFE_HOSTNAME_RE.fullmatch(clean_host):
            raise ValueError(f"invalid target host: {clean_host}")

    if port is None or port == "":
        clean_port = DEFAULT_TARGET_PORT
    else:
        try:
            clean_port = int(port)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"invalid target port: {port}") from exc
    if not (1 <= clean_port <= 65535):
        raise ValueError(f"target port out of range: {clean_port}")
    return clean_host, clean_port


class TailscalePeerCache:
    """Caches `tailscale status --json` peer metadata to classify Direct vs DERP."""

    def __init__(
        self,
        ttl_sec: float = TAILSCALE_CACHE_TTL_SEC,
        status_fetcher: Callable[[], dict] | None = None,
        clock: Callable[[], float] | None = None,
    ):
        self.ttl_sec = ttl_sec
        self._fetcher = status_fetcher or nettransfer_bridge.read_tailscale_status
        self._clock = clock or time.monotonic
        self._lock = threading.Lock()
        self._loaded_at = 0.0
        self._peers_by_ip: dict[str, dict] = {}
        self._peer_count = 0

    def _refresh_locked(self) -> None:
        try:
            status = self._fetcher()
        except Exception:
            status = {}
        by_ip: dict[str, dict] = {}
        nodes = status.get("nodes") if isinstance(status, dict) else None
        if isinstance(nodes, list):
            for n in nodes:
                if not isinstance(n, dict):
                    continue
                ip = str(n.get("ip") or "").strip()
                if ip:
                    by_ip[ip] = dict(n)
        self._peers_by_ip = by_ip
        self._peer_count = len(by_ip)
        self._loaded_at = self._clock()

    def lookup_ip(self, ip: str) -> dict | None:
        now = self._clock()
        with self._lock:
            if self._loaded_at == 0.0 or (now - self._loaded_at) >= self.ttl_sec:
                self._refresh_locked()
            node = self._peers_by_ip.get(ip)
            return dict(node) if node else None

    def peer_count(self) -> int:
        now = self._clock()
        with self._lock:
            if self._loaded_at == 0.0 or (now - self._loaded_at) >= self.ttl_sec:
                self._refresh_locked()
            return self._peer_count


class TargetConnectionPool:
    """Thread-safe HTTP/1.1 Keep-Alive + TCP_NODELAY connection pool per (host, port)."""

    def __init__(
        self,
        connect_timeout: float = CONNECT_TIMEOUT_SEC,
        read_timeout: float = READ_TIMEOUT_SEC,
        max_idle_sec: float = POOL_MAX_IDLE_SEC,
        max_per_target: int = POOL_MAX_PER_TARGET,
        clock: Callable[[], float] | None = None,
    ):
        self.connect_timeout = connect_timeout
        self.read_timeout = read_timeout
        self.max_idle_sec = max_idle_sec
        self.max_per_target = max(1, int(max_per_target))
        self._clock = clock or time.monotonic
        self._lock = threading.Lock()
        self._pool: dict[tuple[str, int], list[tuple[http.client.HTTPConnection, float]]] = {}

    def _open_connection(self, host: str, port: int) -> http.client.HTTPConnection:
        conn = http.client.HTTPConnection(host, port, timeout=self.connect_timeout)
        conn.connect()
        if conn.sock is not None:
            try:
                conn.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            try:
                conn.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            except OSError:
                pass
            conn.sock.settimeout(self.read_timeout)
        return conn

    def _checkout(self, key: tuple[str, int]) -> http.client.HTTPConnection | None:
        now = self._clock()
        stale: list[http.client.HTTPConnection] = []
        chosen: http.client.HTTPConnection | None = None
        with self._lock:
            bucket = self._pool.get(key)
            while bucket:
                conn, last_used = bucket.pop()
                if (now - last_used) > self.max_idle_sec or conn.sock is None:
                    stale.append(conn)
                    continue
                chosen = conn
                break
            if bucket is not None and len(bucket) == 0:
                self._pool.pop(key, None)
        for conn in stale:
            self._close_quietly(conn)
        return chosen

    def _checkin(self, key: tuple[str, int], conn: http.client.HTTPConnection) -> None:
        now = self._clock()
        dropped: http.client.HTTPConnection | None = None
        with self._lock:
            bucket = self._pool.setdefault(key, [])
            if len(bucket) >= self.max_per_target:
                dropped, _ = bucket.pop(0)
            bucket.append((conn, now))
        if dropped is not None:
            self._close_quietly(dropped)

    @staticmethod
    def _close_quietly(conn: http.client.HTTPConnection | None) -> None:
        if conn is None:
            return
        try:
            conn.close()
        except Exception:
            pass

    def pool_size(self) -> int:
        with self._lock:
            return sum(len(bucket) for bucket in self._pool.values())

    def close_all(self) -> None:
        with self._lock:
            conns = [conn for bucket in self._pool.values() for conn, _ in bucket]
            self._pool.clear()
        for conn in conns:
            self._close_quietly(conn)

    def request(
        self,
        method: str,
        host: str,
        port: int,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        key = (host, port)
        conn = self._checkout(key)
        was_reused = conn is not None

        req_headers = {"Connection": "keep-alive"}
        if headers:
            req_headers.update(headers)

        for attempt in (0, 1):
            if conn is None:
                conn = self._open_connection(host, port)
                was_reused = False
            try:
                conn.request(method, path, body=body, headers=req_headers)
                resp = conn.getresponse()
                resp_body = resp.read()
                conn_hdr = str(resp.getheader("Connection") or "").lower()
                should_close = resp.will_close or ("close" in conn_hdr)
                if should_close:
                    self._close_quietly(conn)
                else:
                    self._checkin(key, conn)
                return resp.status, resp_body
            except (http.client.HTTPException, OSError, EOFError):
                self._close_quietly(conn)
                conn = None
                if attempt == 0 and was_reused:
                    continue
                raise

        raise RuntimeError("unreachable connection retry state")


class KeyboardBridgeService:
    """Implements /health, /keyboard/ping, /keyboard/forward, /keyboard/devices, and /keyboard/relay."""

    def __init__(
        self,
        port: int = BRIDGE_PORT,
        pool: TargetConnectionPool | None = None,
        peer_cache: TailscalePeerCache | None = None,
        identity_paths: tuple[str, ...] | list[str] = DEFAULT_IDENTITY_PATHS,
        vps_request: Callable[[str, str, dict[str, str], bytes | None], tuple[int, bytes]] | None = None,
    ):
        self.port = port
        self.pool = pool or TargetConnectionPool()
        self.peer_cache = peer_cache or TailscalePeerCache()
        self.identity_paths = tuple(identity_paths)
        self.vps_pool = VpsConnectionPool()
        self._vps_request = vps_request or self.vps_pool.request

    def _require_identity(self) -> dict[str, Any]:
        ident = load_vm_identity(self.identity_paths)
        if ident is None:
            raise VpsIdentityError("VM NetTransfer identity is not enrolled")
        return ident

    def health(self) -> dict[str, Any]:
        ident = load_vm_identity(self.identity_paths)
        return {
            "ok": True,
            "port": self.port,
            "tailscalePeers": self.peer_cache.peer_count(),
            "poolSize": self.pool.pool_size(),
            "identityReady": ident is not None,
            "selfDeviceId": ident["deviceId"] if ident else "",
            "selfDisplayName": ident["displayName"] if ident else "",
            "vpsUrl": ident["vpsUrl"] if ident else nettransfer_bridge.DEFAULT_VPS_URL,
        }

    def fetch_devices(self) -> dict[str, Any]:
        ident = self._require_identity()
        vps_url = ident["vpsUrl"]
        headers = build_agent_headers(ident, "GET", b"")
        try:
            status, body = self._vps_request("GET", f"{vps_url}/api/agent/v1/devices", headers, None)
            if status in (404, 405):
                headers = build_agent_headers(ident, "GET", b"")
                status, body = self._vps_request("GET", f"{vps_url}/api/devices", headers, None)
        except Exception as exc:
            raise VpsUpstreamError(str(exc) or exc.__class__.__name__) from exc

        if not (200 <= status < 300):
            raise VpsUpstreamError(_read_vps_error_detail(body, status))

        try:
            parsed = json.loads(body.decode("utf-8")) if body else {}
        except Exception as exc:
            raise VpsUpstreamError("invalid devices JSON from VPS") from exc

        if isinstance(parsed, list):
            devices = parsed
        elif isinstance(parsed, dict) and isinstance(parsed.get("devices"), list):
            devices = parsed["devices"]
        else:
            devices = []

        return {
            "ok": True,
            "selfDeviceId": ident["deviceId"],
            "selfDisplayName": ident["displayName"],
            "devices": devices,
        }

    def relay_keyboard(self, req: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(req, dict):
            raise ValueError("relay body must be JSON object")
        ident = self._require_identity()
        target_id = str(req.get("targetDeviceId") or "").strip()
        if not target_id:
            raise ValueError("targetDeviceId is required")
        events = req.get("events")
        if not isinstance(events, list) or len(events) == 0:
            raise ValueError("events list must be non-empty")

        relay_payload = dict(req)
        relay_payload["targetDeviceId"] = target_id
        relay_payload["sourceDeviceId"] = ident["deviceId"]
        relay_payload["sourceName"] = str(req.get("sourceName") or "").strip() or ident["displayName"] or "googlebook"
        body_bytes = json.dumps(relay_payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")

        vps_url = ident["vpsUrl"]
        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
        }
        headers.update(build_agent_headers(ident, "POST", body_bytes))
        try:
            status, resp_body = self._vps_request("POST", f"{vps_url}/api/agent/v1/keyboard/send", headers, body_bytes)
        except Exception as exc:
            raise VpsUpstreamError(str(exc) or exc.__class__.__name__) from exc

        if not (200 <= status < 300):
            raise VpsUpstreamError(_read_vps_error_detail(resp_body, status))

        try:
            ack = json.loads(resp_body.decode("utf-8")) if resp_body else {}
        except Exception:
            ack = {}
        if not isinstance(ack, dict):
            ack = {}

        return {
            "ok": True,
            "accepted": int(ack.get("accepted") or len(events)),
            "targetOnline": bool(ack.get("targetOnline", True)),
            "queued": int(ack.get("queued") or 0),
        }

    def ping_target(self, host: Any, port: Any = DEFAULT_TARGET_PORT, expected_device_id: Any = "") -> dict[str, Any]:
        clean_host, clean_port = validate_target_host_port(host, port)
        expected_id = str(expected_device_id or "").strip()

        peer = self.peer_cache.lookup_ip(clean_host)
        direct = bool(peer and peer.get("mode") == "direct")
        relay = str(peer.get("relay") or "").strip() if peer else ""

        t0 = time.monotonic()
        try:
            status, body = self.pool.request(
                "GET",
                clean_host,
                clean_port,
                "/api/keyboard/ping",
                headers={"Accept": "application/json"},
            )
            rtt_ms = round((time.monotonic() - t0) * 1000.0, 2)
        except Exception as exc:
            rtt_ms = round((time.monotonic() - t0) * 1000.0, 2)
            return {
                "ok": False,
                "host": clean_host,
                "port": clean_port,
                "deviceId": "",
                "rttMs": rtt_ms,
                "via": "tailscale",
                "direct": direct,
                "relay": relay,
                "error": str(exc) or exc.__class__.__name__,
            }

        try:
            pong = json.loads(body.decode("utf-8", "replace")) if body else {}
        except Exception:
            pong = {}

        if not (200 <= status < 300):
            err_msg = str(pong.get("error") or f"HTTP {status}") if isinstance(pong, dict) else f"HTTP {status}"
            return {
                "ok": False,
                "host": clean_host,
                "port": clean_port,
                "deviceId": "",
                "rttMs": rtt_ms,
                "via": "tailscale",
                "direct": direct,
                "relay": relay,
                "error": err_msg,
            }

        if not isinstance(pong, dict) or not pong.get("ok"):
            return {
                "ok": False,
                "host": clean_host,
                "port": clean_port,
                "deviceId": "",
                "rttMs": rtt_ms,
                "via": "tailscale",
                "direct": direct,
                "relay": relay,
                "error": "invalid ping reply",
            }

        reported_id = str(pong.get("deviceId") or "").strip()
        if expected_id and reported_id and reported_id != expected_id:
            return {
                "ok": False,
                "host": clean_host,
                "port": clean_port,
                "deviceId": reported_id,
                "rttMs": rtt_ms,
                "via": "tailscale",
                "direct": direct,
                "relay": relay,
                "error": f"device mismatch: {reported_id}",
            }

        return {
            "ok": True,
            "host": clean_host,
            "port": clean_port,
            "deviceId": reported_id,
            "rttMs": rtt_ms,
            "via": "tailscale",
            "direct": direct,
            "relay": relay,
            "error": "",
        }

    def forward_payload(self, host: Any, port: Any, payload: Any) -> dict[str, Any]:
        clean_host, clean_port = validate_target_host_port(host, port)
        if isinstance(payload, str):
            clean_str = payload.strip()
            if not clean_str:
                raise ValueError("empty payload string")
            body_bytes = clean_str.encode("utf-8")
        elif isinstance(payload, dict):
            body_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        else:
            raise ValueError("payload must be JSON object or string")

        t0 = time.monotonic()
        try:
            status, body = self.pool.request(
                "POST",
                clean_host,
                clean_port,
                "/api/keyboard/direct",
                body=body_bytes,
                headers={
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                },
            )
            rtt_ms = round((time.monotonic() - t0) * 1000.0, 2)
        except Exception as exc:
            rtt_ms = round((time.monotonic() - t0) * 1000.0, 2)
            return {
                "ok": False,
                "accepted": 0,
                "lastSeq": 0,
                "rttMs": rtt_ms,
                "error": str(exc) or exc.__class__.__name__,
            }

        try:
            ack = json.loads(body.decode("utf-8", "replace")) if body else {}
        except Exception:
            ack = {}
        if not isinstance(ack, dict):
            ack = {}

        if 200 <= status < 300:
            return {
                "ok": True,
                "accepted": int(ack.get("accepted") or 0),
                "lastSeq": int(ack.get("lastSeq") or 0),
                "rttMs": rtt_ms,
                "error": "",
            }

        return {
            "ok": False,
            "accepted": 0,
            "lastSeq": 0,
            "rttMs": rtt_ms,
            "error": str(ack.get("error") or f"HTTP {status}"),
        }


class ThreadingKeyboardBridgeServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def make_handler_class(
    service: KeyboardBridgeService,
    client_check: Callable[[str], bool] | None = None,
) -> type[http.server.BaseHTTPRequestHandler]:
    allow_client = client_check or nettransfer_bridge.is_local_management_client

    class KeyboardBridgeHandler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def setup(self) -> None:
            super().setup()
            try:
                self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            try:
                self.connection.settimeout(CLIENT_IDLE_TIMEOUT_SEC)
            except OSError:
                pass

        def _send_json(self, code: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            if str(self.headers.get("Connection") or "").lower() == "close":
                self.send_header("Connection", "close")
                self.close_connection = True
            else:
                self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.wfile.write(body)

        def _read_json_body(self) -> dict[str, Any]:
            try:
                length = int(self.headers.get("Content-Length") or "0")
            except ValueError as exc:
                raise ValueError("invalid Content-Length") from exc
            if length <= 0 or length > MAX_BODY_BYTES:
                raise ValueError("invalid body length")
            raw = self.rfile.read(length)
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("JSON body must be object")
            return data

        def _authorize(self) -> bool:
            if allow_client(self.client_address[0]):
                return True
            self._send_json(403, {"ok": False, "error": "forbidden"})
            return False

        def do_OPTIONS(self) -> None:
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

        def do_GET(self) -> None:
            if not self._authorize():
                return
            path = urllib.parse.urlparse(self.path).path
            if path == "/health":
                self._send_json(200, service.health())
                return
            if path == "/keyboard/devices":
                try:
                    self._send_json(200, service.fetch_devices())
                except VpsIdentityError as exc:
                    self._send_json(503, {"ok": False, "error": str(exc)})
                except VpsUpstreamError as exc:
                    self._send_json(502, {"ok": False, "error": str(exc)})
                except Exception as exc:
                    self._send_json(500, {"ok": False, "error": str(exc)})
                return
            self._send_json(404, {"ok": False, "error": "not found"})

        def do_POST(self) -> None:
            if not self._authorize():
                return
            path = urllib.parse.urlparse(self.path).path
            if path not in ("/keyboard/ping", "/keyboard/forward", "/keyboard/relay"):
                self._send_json(404, {"ok": False, "error": "not found"})
                return
            try:
                req = self._read_json_body()
            except Exception as exc:
                self._send_json(400, {"ok": False, "error": str(exc)})
                return

            try:
                if path == "/keyboard/ping":
                    res = service.ping_target(
                        req.get("host"),
                        req.get("port", DEFAULT_TARGET_PORT),
                        req.get("expectedDeviceId", ""),
                    )
                elif path == "/keyboard/forward":
                    res = service.forward_payload(
                        req.get("host"),
                        req.get("port", DEFAULT_TARGET_PORT),
                        req.get("payload"),
                    )
                else:
                    res = service.relay_keyboard(req)
                self._send_json(200, res)
            except ValueError as exc:
                self._send_json(400, {"ok": False, "error": str(exc)})
            except VpsIdentityError as exc:
                self._send_json(503, {"ok": False, "error": str(exc)})
            except VpsUpstreamError as exc:
                self._send_json(502, {"ok": False, "error": str(exc)})
            except Exception as exc:
                self._send_json(500, {"ok": False, "error": str(exc)})

        def log_message(self, format: str, *args: Any) -> None:
            pass

    return KeyboardBridgeHandler


def start_keyboard_bridge_thread(
    port: int = BRIDGE_PORT,
    bind_host: str = "0.0.0.0",
    service: KeyboardBridgeService | None = None,
) -> tuple[ThreadingKeyboardBridgeServer | None, threading.Thread | None]:
    svc = service or KeyboardBridgeService(port=port)
    try:
        httpd = ThreadingKeyboardBridgeServer((bind_host, port), make_handler_class(svc))
        svc.port = httpd.server_address[1]
    except Exception as exc:
        print(f"Warning: could not start keyboard bridge on {bind_host}:{port}: {exc}", file=sys.stderr)
        return None, None

    t = threading.Thread(target=httpd.serve_forever, name="keyboard-bridge", daemon=True)
    t.start()
    return httpd, t


if __name__ == "__main__":
    srv, thread = start_keyboard_bridge_thread(BRIDGE_PORT)
    if srv is None or thread is None:
        sys.exit(1)
    print(f"Keyboard Tailscale bridge listening on port {BRIDGE_PORT}")
    try:
        thread.join()
    except KeyboardInterrupt:
        srv.shutdown()
        srv.server_close()
