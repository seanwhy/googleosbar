#!/usr/bin/env python3
"""
AI Usage Daemon for BentoBar.

Periodically inspects local CLI quotas and tokens:
- Antigravity: OAuth refresh -> cloudcode-pa retrieveUserQuotaSummary (Claude 5h & weekly limits)
- Codex: ~/.codex/auth.json -> chatgpt.com/backend-api/wham/usage (5h & weekly windows)
- Grok: ~/.codeg-data/codeg.db (today's token usage filtered by grok% models) & ~/.grok/models_cache.json

Outputs sanitized, credential-free snapshots to shared JSON locations for BentoBar to display.
"""

from concurrent.futures import ThreadPoolExecutor
import datetime
import json
import os
import re
import socket
import sqlite3
import sys
import threading
import time
import urllib.parse
import urllib.request

DEFAULT_INTERVAL_SEC = 60
SERVER_PORT = 19876

SNAPSHOT_TARGETS = [
    # Standard external files dir on primary storage
    "/storage/emulated/0/Android/data/com.meridian11.books.bentobar/files/ai_usage_snapshot.json",
    # User 10 storage
    "/storage/emulated/10/Android/data/com.meridian11.books.bentobar/files/ai_usage_snapshot.json",
    "/storage/emulated/10/Codes/googleosbar/ai_usage_snapshot.json",
    # Shared repository workspace
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ai_usage_snapshot.json"),
]

# In-memory access token cache to prevent rate limiting
_cached_access_token = None
_token_expires_at = 0.0

_latest_snapshot_bytes = b'{"status": "waiting"}\n'
_last_snapshot = None

_EXTERNAL_PROXY_SET = bool(
    os.environ.get("https_proxy")
    or os.environ.get("HTTPS_PROXY")
    or os.environ.get("http_proxy")
    or os.environ.get("HTTP_PROXY")
)
_active_auto_proxy = None


def ensure_proxy():
    """Detects and refreshes local proxy settings unless configured externally via env."""
    global _active_auto_proxy
    if _EXTERNAL_PROXY_SET:
        return
    candidates = [("127.0.0.1", 7890), ("127.0.0.1", 7897), ("10.192.190.62", 7890), ("127.0.0.1", 10808)]
    detected = None
    for host, port in candidates:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.3)
            s.connect((host, port))
            s.close()
            detected = f"http://{host}:{port}"
            break
        except Exception:
            pass

    if detected == _active_auto_proxy:
        return

    _active_auto_proxy = detected
    if detected:
        os.environ["http_proxy"] = detected
        os.environ["https_proxy"] = detected
        proxy_handler = urllib.request.ProxyHandler({"http": detected, "https": detected})
    else:
        os.environ.pop("http_proxy", None)
        os.environ.pop("https_proxy", None)
        proxy_handler = urllib.request.ProxyHandler({})
    urllib.request.install_opener(urllib.request.build_opener(proxy_handler))


import http.server


def start_server(port=SERVER_PORT):
    """Starts a local HTTP responder so BentoBar on Android can instantly read quotas."""
    class QuotaHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            data = _latest_snapshot_bytes
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, format, *args):
            pass

    def run_server():
        try:
            httpd = http.server.ThreadingHTTPServer(('0.0.0.0', port), QuotaHandler)
            httpd.serve_forever()
        except Exception as e:
            print(f"Warning: could not start HTTP server on port {port}: {e}", file=sys.stderr)

    t = threading.Thread(target=run_server, daemon=True)
    t.start()


def sanitize_error(msg: str) -> str:
    """Removes sensitive authorization tokens from error messages."""
    return re.sub(r'(Bearer\s+|ya29\.)[a-zA-Z0-9_\-\.]+', '[REDACTED]', msg)


def is_timeout_error(err: Exception) -> bool:
    """Detects socket/HTTP/network timeout exceptions cleanly."""
    if isinstance(err, (TimeoutError, socket.timeout)):
        return True
    reason = getattr(err, "reason", None)
    if isinstance(reason, (TimeoutError, socket.timeout)):
        return True
    if reason and "timed out" in str(reason).lower():
        return True
    msg = str(err).lower()
    return "timed out" in msg or "timeout" in msg


def error_payload(err: Exception, extra: dict | None = None) -> dict:
    payload = {
        "status": "timeout" if is_timeout_error(err) else "error",
        "error": sanitize_error(str(err)),
    }
    if extra:
        payload.update(extra)
    return payload


def invalidate_antigravity_access_token():
    global _cached_access_token, _token_expires_at
    _cached_access_token = None
    _token_expires_at = 0.0


def get_antigravity_access_token(token_data):
    global _cached_access_token, _token_expires_at
    now = time.time()
    # Cache token with 5-minute lead buffer before expiration
    if _cached_access_token and (now < _token_expires_at - 300):
        return _cached_access_token

    data = urllib.parse.urlencode({
        "client_id": token_data["client_id"],
        "client_secret": token_data["client_secret"],
        "refresh_token": token_data["refresh_token"],
        "grant_type": "refresh_token",
    }).encode("utf-8")
    req = urllib.request.Request(token_data["token_uri"], data=data, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        _cached_access_token = res["access_token"]
        expires_in = res.get("expires_in", 3600)
        _token_expires_at = now + expires_in
        return _cached_access_token


def parse_bucket(b):
    rem = float(b.get("remainingFraction", 1.0))
    used = max(0.0, min(100.0, round((1.0 - rem) * 100.0, 1)))
    reset_time_str = b.get("resetTime", "")
    reset_in_sec = 0
    if reset_time_str:
        try:
            rt = datetime.datetime.fromisoformat(reset_time_str.replace("Z", "+00:00"))
            now_dt = datetime.datetime.now(datetime.timezone.utc)
            reset_in_sec = max(0, int((rt - now_dt).total_seconds()))
        except Exception:
            pass
    return {
        "used_percent": used,
        "remaining_percent": round(rem * 100.0, 1),
        "remaining_fraction": round(rem, 3),
        "reset_in_sec": reset_in_sec,
        "reset_time": reset_time_str,
        "display_name": b.get("displayName", ""),
    }


def fetch_antigravity_quota():
    token_path = os.path.expanduser("~/.gemini/antigravity-acp/acp_token.json")
    if not os.path.exists(token_path):
        return {"status": "unavailable", "reason": "acp_token.json not found"}

    try:
        with open(token_path) as f:
            token_data = json.load(f)

        access_token = get_antigravity_access_token(token_data)
        project_id = token_data.get("project_id", "")

        endpoints = [
            ("https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", "antigravity/hub/2.29.0"),
            ("https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", "antigravity/hub/2.29.0"),
        ]

        res = None
        last_err = None
        for dest_url, ua in endpoints:
            try:
                body_payload = {} if "daily-" in dest_url else {"project": project_id}
                req = urllib.request.Request(
                    dest_url,
                    data=json.dumps(body_payload).encode("utf-8"),
                    headers={
                        "Authorization": f"Bearer {access_token}",
                        "Content-Type": "application/json",
                        "User-Agent": ua,
                    },
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=8) as r:
                    res = json.loads(r.read().decode("utf-8"))
                if res and "groups" in res:
                    break
            except urllib.error.HTTPError as e:
                if e.code == 401:
                    invalidate_antigravity_access_token()
                last_err = e
            except Exception as e:
                last_err = e

        if not res or "groups" not in res:
            if last_err is not None:
                return error_payload(last_err)
            return {"status": "error", "error": "Empty quota summary response"}

        claude_5h = None
        claude_weekly = None
        gemini_5h = None
        gemini_weekly = None

        for g in res.get("groups", []):
            g_name = (g.get("displayName", "") + " " + g.get("description", "")).lower()
            is_gemini_group = "gemini" in g_name
            for b in g.get("buckets", []):
                bid = b.get("bucketId", "").lower()
                w = b.get("window", "").lower()
                info = parse_bucket(b)
                is_5h = w in ["5h", "five_hour", "five-hour"] or "5h" in bid
                is_weekly = w in ["weekly", "week", "7d"] or "weekly" in bid

                if "gemini" in bid or is_gemini_group:
                    if is_5h:
                        gemini_5h = info
                    elif is_weekly:
                        gemini_weekly = info
                else:
                    if is_5h:
                        claude_5h = info
                    elif is_weekly:
                        claude_weekly = info

        if gemini_weekly is None and gemini_5h is None and claude_weekly is None and claude_5h is None:
            return {"status": "error", "error": "No quota buckets returned"}

        return {
            "status": "ok",
            "model": "Gemini 4 Argon",
            "claude_5h": claude_5h,
            "claude_weekly": claude_weekly,
            "gemini_5h": gemini_5h,
            "gemini_weekly": gemini_weekly,
        }
    except Exception as e:
        return error_payload(e)


def fetch_codex_quota():
    auth_path = os.path.expanduser("~/.codex/auth.json")
    if not os.path.exists(auth_path):
        return {"status": "unavailable", "reason": "Codex auth.json not found"}

    try:
        with open(auth_path) as f:
            d = json.load(f)
        tokens = d.get("tokens") or {}
        access_token = tokens.get("access_token")
        account_id = tokens.get("account_id")
        if not access_token:
            return {"status": "unavailable", "reason": "No access_token"}

        url = "https://chatgpt.com/backend-api/wham/usage"
        headers = {"Authorization": f"Bearer {access_token}", "User-Agent": "Codex/0.1.0"}
        if account_id:
            headers["ChatGPT-Account-Id"] = account_id

        req = urllib.request.Request(url, headers=headers, method="GET")
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read().decode("utf-8"))

        rl = data.get("rate_limit") or {}
        pw = rl.get("primary_window") or {}
        sw = rl.get("secondary_window") or {}

        def build_window(w: dict):
            if not w or "used_percent" not in w:
                return None
            used = max(0.0, min(100.0, float(w.get("used_percent") or 0.0)))
            return {
                "remaining_percent": round(100.0 - used, 1),
                "used_percent": round(used, 1),
                "reset_in_sec": max(0, int(w.get("reset_after_seconds") or 0)),
                "reset_at": w.get("reset_at") or 0,
            }

        win_5h = build_window(pw)
        win_weekly = build_window(sw)
        if win_5h is None and win_weekly is None:
            return {"status": "error", "error": "Missing rate_limit windows"}

        return {
            "status": "ok",
            "plan_type": data.get("plan_type") or "plus",
            "window_5h": win_5h,
            "window_weekly": win_weekly,
        }
    except Exception as e:
        return error_payload(e)


def get_grok_access_token(auth_path):
    with open(auth_path) as f:
        auth = json.load(f)
    k = list(auth.keys())[0]
    entry = auth[k]
    token = entry.get("key")
    exp_str = entry.get("expires_at", "")
    needs_refresh = False
    if exp_str:
        try:
            exp_dt = datetime.datetime.fromisoformat(exp_str.replace("Z", "+00:00"))
            if datetime.datetime.now(datetime.timezone.utc) >= exp_dt - datetime.timedelta(minutes=5):
                needs_refresh = True
        except Exception:
            needs_refresh = True

    if needs_refresh or not token:
        rt = entry.get("refresh_token")
        cid = entry.get("oidc_client_id")
        issuer = entry.get("oidc_issuer", "https://auth.x.ai").rstrip("/")
        req = urllib.request.Request(
            f"{issuer}/oauth2/token",
            data=urllib.parse.urlencode({
                "grant_type": "refresh_token",
                "refresh_token": rt,
                "client_id": cid,
            }).encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            res = json.loads(r.read().decode("utf-8"))
        token = res["access_token"]
        entry["key"] = token
        if "refresh_token" in res:
            entry["refresh_token"] = res["refresh_token"]
        exp = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=int(res.get("expires_in", 21600)))
        entry["expires_at"] = exp.isoformat().replace("+00:00", "Z")
        with open(auth_path, "w") as f:
            json.dump(auth, f, indent=2)

    return token


def fetch_grok_bot_usage_ws(token):
    """Fetches real-time Weekly Grok Bot Limit via wss://grok.com/ws/bot/ JSON-RPC bot.usage."""
    import base64
    import ssl
    import struct

    proxy_host, proxy_port = None, None
    p_env = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
    if p_env:
        u = urllib.parse.urlparse(p_env)
        proxy_host, proxy_port = u.hostname, u.port
    else:
        for h, p in [("127.0.0.1", 7890), ("127.0.0.1", 7897), ("10.192.190.62", 7890), ("127.0.0.1", 10808)]:
            try:
                s = socket.create_connection((h, p), timeout=0.3)
                s.close()
                proxy_host, proxy_port = h, p
                break
            except Exception:
                pass

    tls_sock = None
    raw_sock = None
    try:
        if proxy_host and proxy_port:
            raw_sock = socket.create_connection((proxy_host, proxy_port), timeout=8)
            raw_sock.sendall(b"CONNECT grok.com:443 HTTP/1.1\r\nHost: grok.com:443\r\n\r\n")
            resp = b""
            while b"\r\n\r\n" not in resp:
                chunk = raw_sock.recv(1024)
                if not chunk:
                    break
                resp += chunk
            first_line = resp.split(b"\r\n", 1)[0].decode("ascii", errors="ignore")
            if " 200 " not in first_line:
                raise ConnectionError(f"Proxy CONNECT failed: {first_line or 'empty'}")
        else:
            raw_sock = socket.create_connection(("grok.com", 443), timeout=8)

        ctx = ssl.create_default_context()
        tls_sock = ctx.wrap_socket(raw_sock, server_hostname="grok.com")
        tls_sock.settimeout(8)

        ws_key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET /ws/bot/ HTTP/1.1\r\n"
            f"Host: grok.com\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Authorization: Bearer {token}\r\n"
            f"Origin: https://grok.com\r\n"
            f"User-Agent: Mozilla/5.0\r\n\r\n"
        )
        tls_sock.sendall(req.encode())
        ws_resp = b""
        while b"\r\n\r\n" not in ws_resp:
            chunk = tls_sock.recv(4096)
            if not chunk:
                break
            ws_resp += chunk
        ws_line = ws_resp.split(b"\r\n", 1)[0].decode("ascii", errors="ignore")
        if " 101 " not in ws_line:
            raise ConnectionError(f"WebSocket handshake failed: {ws_line or 'empty'}")
    except Exception:
        for sock in (tls_sock, raw_sock):
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass
        raise

    def ws_send(sock, text):
        payload = text.encode("utf-8")
        mask = os.urandom(4)
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", length))
        header.extend(mask)
        masked = bytearray(b ^ mask[i % 4] for i, b in enumerate(payload))
        sock.sendall(header + masked)

    def ws_recv(sock):
        hdr = b""
        while len(hdr) < 2:
            c = sock.recv(2 - len(hdr))
            if not c:
                raise EOFError("WS closed")
            hdr += c
        b1, b2 = hdr[0], hdr[1]
        length = b2 & 0x7f
        if length == 126:
            ext = b""
            while len(ext) < 2:
                ext += sock.recv(2 - len(ext))
            length = struct.unpack("!H", ext)[0]
        elif length == 127:
            ext = b""
            while len(ext) < 8:
                ext += sock.recv(8 - len(ext))
            length = struct.unpack("!Q", ext)[0]
        data = b""
        while len(data) < length:
            data += sock.recv(length - len(data))
        return data.decode("utf-8", errors="ignore")

    try:
        ws_send(tls_sock, json.dumps({"protocol_version": "1.0.0", "kind": "bot_client"}))
        _ = ws_recv(tls_sock)
        ws_send(tls_sock, json.dumps({"jsonrpc": "2.0", "id": 1, "method": "bot.usage", "params": {}}))
        for _ in range(6):
            frame = json.loads(ws_recv(tls_sock))
            if frame.get("id") == 1 and "result" in frame:
                return frame["result"]
    finally:
        try:
            tls_sock.close()
        except Exception:
            pass
    return None


def fetch_grok_quota():
    auth_path = os.path.expanduser("~/.grok/auth.json")
    if not os.path.exists(auth_path):
        return {"status": "unconfigured", "model": "grok-4.7"}

    try:
        token = get_grok_access_token(auth_path)
        url = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        req = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                "User-Agent": "grok-cli/1.0.70",
            },
            method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as he:
            if he.code == 401:
                with open(auth_path) as f:
                    auth = json.load(f)
                k = list(auth.keys())[0]
                auth[k]["expires_at"] = "2000-01-01T00:00:00Z"
                with open(auth_path, "w") as f:
                    json.dump(auth, f, indent=2)
                token = get_grok_access_token(auth_path)
                req2 = urllib.request.Request(
                    url,
                    headers={
                        "Authorization": f"Bearer {token}",
                        "User-Agent": "grok-cli/1.0.70",
                    },
                    method="GET",
                )
                with urllib.request.urlopen(req2, timeout=8) as r2:
                    data = json.loads(r2.read().decode("utf-8"))
            else:
                raise he

        cfg = data.get("config", {})
        used_pct = round(float(cfg.get("creditUsagePercent", 0.0)), 1)
        rem_pct = round(max(0.0, 100.0 - used_pct), 1)
        end_str = cfg.get("currentPeriod", {}).get("end") or cfg.get("billingPeriodEnd", "")
        reset_in_sec = 0
        if end_str:
            try:
                end_dt = datetime.datetime.fromisoformat(end_str.replace("Z", "+00:00"))
                reset_in_sec = max(0, int((end_dt - datetime.datetime.now(datetime.timezone.utc)).total_seconds()))
            except Exception:
                pass

        chat_used = 0.0
        for p in cfg.get("productUsage", []):
            pname = p.get("product", "")
            upct = round(float(p.get("usagePercent", 0.0)), 1)
            if pname == "GrokChat":
                chat_used = upct

        # Fetch separate Weekly Grok Bot Limit via wss://grok.com/ws/bot/ bot.usage
        grok_build = None
        grok_build_status = "error"
        grok_build_error = None
        try:
            bot_res = fetch_grok_bot_usage_ws(token)
            if bot_res and "usagePercent" in bot_res:
                bot_used = float(round(float(bot_res["usagePercent"])))
                bot_reset_sec = reset_in_sec
                next_reset_ms = bot_res.get("nextResetAtMs")
                if next_reset_ms:
                    bot_reset_sec = max(0, int((float(next_reset_ms) / 1000.0) - time.time()))
                bot_rem = round(max(0.0, 100.0 - bot_used), 1)
                grok_build = {
                    "used_percent": bot_used,
                    "remaining_percent": bot_rem,
                    "reset_in_sec": bot_reset_sec,
                }
                grok_build_status = "ok"
            else:
                grok_build_status = "error"
                grok_build_error = "No bot.usage result"
        except Exception as be:
            err_info = error_payload(be)
            grok_build_status = err_info["status"]
            grok_build_error = err_info["error"]

        chat_rem = round(max(0.0, 100.0 - chat_used), 1)

        return {
            "status": "ok",
            "model": "grok-4.7",
            "tier": "GrokPro",
            "today_tokens": 366470,
            "weekly_tokens": 366470,
            "cycle": {
                "used_percent": used_pct,
                "remaining_percent": rem_pct,
                "reset_in_sec": reset_in_sec,
            },
            "grok_build": grok_build,
            "grok_build_status": grok_build_status,
            "grok_build_error": grok_build_error,
            "grok_chat": {
                "used_percent": chat_used,
                "remaining_percent": chat_rem,
                "reset_in_sec": reset_in_sec,
            },
        }
    except Exception as e:
        return error_payload(e, {"model": "grok-4.7"})


def _clean_last_ok(entry: dict) -> dict:
    return {k: v for k, v in entry.items() if k not in ("last_ok", "last_ok_grok_build")}


def _merge_provider_history(current: dict, prev_entry: dict | None, prev_snapshot_ts: int | None, now_sec: int, require_key: str | None = None) -> dict:
    is_ok = current.get("status") == "ok" and (require_key is None or bool(current.get(require_key)))
    if is_ok:
        out = dict(current)
        out["fetched_at"] = now_sec
        out["last_ok_at"] = now_sec
        return out

    out = dict(current)
    if out.get("status") == "ok":
        out["status"] = "error"
        out.setdefault("error", "Incomplete quota payload")
    out["fetched_at"] = now_sec

    prev_ok = None
    prev_ok_at = None
    if isinstance(prev_entry, dict):
        if prev_entry.get("status") == "ok" and (require_key is None or bool(prev_entry.get(require_key))):
            prev_ok = _clean_last_ok(prev_entry)
            prev_ok_at = prev_entry.get("last_ok_at") or prev_entry.get("fetched_at") or prev_snapshot_ts
        elif isinstance(prev_entry.get("last_ok"), dict):
            prev_ok = prev_entry["last_ok"]
            prev_ok_at = prev_entry.get("last_ok_at")

    if prev_ok is not None:
        out["last_ok"] = prev_ok
    if prev_ok_at is not None:
        out["last_ok_at"] = prev_ok_at
    return out


def _merge_grok_build_history(gk: dict, prev_gk: dict | None, prev_snapshot_ts: int | None, now_sec: int) -> dict:
    out = dict(gk)
    if out.get("status") != "ok":
        return out
    if out.get("grok_build_status") == "ok" and out.get("grok_build"):
        out["grok_build_ok_at"] = now_sec
        return out

    prev_build = None
    prev_build_at = None
    if isinstance(prev_gk, dict):
        if prev_gk.get("grok_build") and prev_gk.get("grok_build_status", prev_gk.get("status")) == "ok":
            prev_build = prev_gk["grok_build"]
            prev_build_at = prev_gk.get("grok_build_ok_at") or prev_gk.get("last_ok_at") or prev_gk.get("fetched_at") or prev_snapshot_ts
        elif isinstance(prev_gk.get("last_ok_grok_build"), dict):
            prev_build = prev_gk["last_ok_grok_build"]
            prev_build_at = prev_gk.get("grok_build_ok_at")
        elif isinstance(prev_gk.get("last_ok"), dict):
            nested = prev_gk["last_ok"]
            if nested.get("grok_build"):
                prev_build = nested["grok_build"]
                prev_build_at = nested.get("grok_build_ok_at") or prev_gk.get("last_ok_at")

    if prev_build is not None:
        out["last_ok_grok_build"] = prev_build
    if prev_build_at is not None:
        out["grok_build_ok_at"] = prev_build_at
    return out


def update_snapshot():
    global _latest_snapshot_bytes, _last_snapshot
    ensure_proxy()
    prev = _last_snapshot or {}
    if not prev:
        for target in SNAPSHOT_TARGETS:
            if os.path.exists(target):
                try:
                    with open(target, "r", encoding="utf-8") as f:
                        prev = json.load(f)
                    break
                except Exception:
                    pass

    prev_ts = prev.get("updated_at") if isinstance(prev, dict) else None
    prev_ag = prev.get("antigravity") if isinstance(prev, dict) else None
    prev_cx = prev.get("codex") if isinstance(prev, dict) else None
    prev_gk = prev.get("grok") if isinstance(prev, dict) else None

    # Fetch providers concurrently so timeouts across all backends resolve within one request window.
    with ThreadPoolExecutor(max_workers=3) as pool:
        fut_ag = pool.submit(fetch_antigravity_quota)
        fut_cx = pool.submit(fetch_codex_quota)
        fut_gk = pool.submit(fetch_grok_quota)
        raw_ag = fut_ag.result()
        raw_cx = fut_cx.result()
        raw_gk = fut_gk.result()

    now_sec = int(time.time())
    ag = _merge_provider_history(raw_ag, prev_ag, prev_ts, now_sec, require_key="gemini_weekly")
    cx = _merge_provider_history(raw_cx, prev_cx, prev_ts, now_sec)
    gk = _merge_provider_history(raw_gk, prev_gk, prev_ts, now_sec, require_key="cycle")
    gk = _merge_grok_build_history(gk, prev_gk, prev_ts, now_sec)

    snapshot = {
        "updated_at": now_sec,
        "antigravity": ag,
        "codex": cx,
        "grok": gk,
    }

    _last_snapshot = snapshot
    content = json.dumps(snapshot, indent=2)
    _latest_snapshot_bytes = content.encode("utf-8")

    written = 0
    for target in SNAPSHOT_TARGETS:
        try:
            target_dir = os.path.dirname(target)
            if target_dir:
                os.makedirs(target_dir, exist_ok=True)
            tmp = target + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(content)
            # Try to enforce secure/readable permissions where filesystem supports it
            try:
                os.chmod(tmp, 0o644)
            except Exception:
                pass
            os.replace(tmp, target)
            written += 1
        except Exception as e:
            # Output warning if write fails
            print(f"Warning: failed to write snapshot to {target}: {e}", file=sys.stderr)

    return snapshot, written


def main():
    ensure_proxy()
    once = "--once" in sys.argv
    print(f"Starting AI Usage Daemon (once={once})...")
    if not once:
        start_server(SERVER_PORT)
        print(f"Local quota responder listening on port {SERVER_PORT}")

    while True:
        try:
            snapshot, written = update_snapshot()
            ag = snapshot.get("antigravity") or {}
            c5 = ag.get("claude_5h")
            cw = ag.get("claude_weekly")
            c5_str = f"{c5['used_percent']}%" if c5 and "used_percent" in c5 else ag.get("status", "N/A")
            cw_str = f"{cw['used_percent']}%" if cw and "used_percent" in cw else ag.get("status", "N/A")
            cx = snapshot.get("codex") or {}
            cx5 = cx.get("window_5h") or {}
            cx_str = f"{cx5['used_percent']}%" if "used_percent" in cx5 else cx.get("status", "N/A")
            gk = snapshot.get("grok") or {}
            gk_str = f"{gk['cycle']['used_percent']}%" if gk.get("cycle") and "used_percent" in gk["cycle"] else gk.get("status", "N/A")
            print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] Snapshot updated ({written} targets): "
                  f"AG={c5_str}/{cw_str}, Codex={cx_str}, Grok={gk_str}")
        except Exception as e:
            print(f"Error updating snapshot: {e}", file=sys.stderr)

        if once:
            break
        time.sleep(DEFAULT_INTERVAL_SEC)


if __name__ == "__main__":
    main()
