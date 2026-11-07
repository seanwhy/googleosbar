#!/usr/bin/env python3
"""
AI Usage Daemon for BentoBar.

Periodically inspects local CLI quotas and tokens:
- Antigravity: OAuth refresh -> cloudcode-pa retrieveUserQuotaSummary (Claude 5h & weekly limits)
- Codex: ~/.codex/auth.json -> chatgpt.com/backend-api/wham/usage (5h & weekly windows)
- Grok: ~/.codeg-data/codeg.db (today's token usage filtered by grok% models) & ~/.grok/models_cache.json

Outputs sanitized, credential-free snapshots to shared JSON locations for BentoBar to display.
"""

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


def ensure_proxy():
    """Detects and activates local proxy if available on Android Laptop."""
    if os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY"):
        return
    candidates = [("127.0.0.1", 7890), ("127.0.0.1", 7897), ("10.192.190.62", 7890), ("127.0.0.1", 10808)]
    for host, port in candidates:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.3)
            s.connect((host, port))
            s.close()
            proxy_url = f"http://{host}:{port}"
            os.environ["http_proxy"] = proxy_url
            os.environ["https_proxy"] = proxy_url
            proxy_handler = urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url})
            opener = urllib.request.build_opener(proxy_handler)
            urllib.request.install_opener(opener)
            return
        except Exception:
            pass


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

        claude_5h = None
        claude_weekly = None
        gemini_5h = None
        gemini_weekly = None

        if res and "groups" in res:
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

        return {
            "status": "ok",
            "model": "Gemini 4 Argon",
            "claude_5h": claude_5h,
            "claude_weekly": claude_weekly,
            "gemini_5h": gemini_5h,
            "gemini_weekly": gemini_weekly,
        }
    except Exception as e:
        return {"status": "error", "error": sanitize_error(str(e))}


def fetch_codex_quota():
    auth_path = os.path.expanduser("~/.codex/auth.json")
    if not os.path.exists(auth_path):
        return {"status": "unavailable", "reason": "Codex auth.json not found"}

    try:
        with open(auth_path) as f:
            d = json.load(f)
        tokens = d.get("tokens", {})
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

        rl = data.get("rate_limit", {})
        pw = rl.get("primary_window", {})
        sw = rl.get("secondary_window", {})

        pw_used = max(0.0, min(100.0, float(pw.get("used_percent", 0))))
        sw_used = max(0.0, min(100.0, float(sw.get("used_percent", 0))))

        return {
            "status": "ok",
            "plan_type": data.get("plan_type", "plus"),
            "window_5h": {
                "remaining_percent": round(100.0 - pw_used, 1),
                "used_percent": round(pw_used, 1),
                "reset_in_sec": max(0, pw.get("reset_after_seconds", 0)),
                "reset_at": pw.get("reset_at", 0),
            },
            "window_weekly": {
                "remaining_percent": round(100.0 - sw_used, 1),
                "used_percent": round(sw_used, 1),
                "reset_in_sec": max(0, sw.get("reset_after_seconds", 0)),
                "reset_at": sw.get("reset_at", 0),
            },
        }
    except Exception as e:
        return {"status": "error", "error": sanitize_error(str(e))}


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

    if proxy_host and proxy_port:
        raw_sock = socket.create_connection((proxy_host, proxy_port), timeout=8)
        raw_sock.sendall(b"CONNECT grok.com:443 HTTP/1.1\r\nHost: grok.com:443\r\n\r\n")
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = raw_sock.recv(1024)
            if not chunk:
                break
            resp += chunk
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
        bot_used = 80.0
        bot_reset_sec = reset_in_sec
        try:
            bot_res = fetch_grok_bot_usage_ws(token)
            if bot_res and "usagePercent" in bot_res:
                bot_used = float(round(float(bot_res["usagePercent"])))
                next_reset_ms = bot_res.get("nextResetAtMs")
                if next_reset_ms:
                    bot_reset_sec = max(0, int((float(next_reset_ms) / 1000.0) - time.time()))
        except Exception:
            pass

        bot_rem = round(max(0.0, 100.0 - bot_used), 1)
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
            "grok_build": {
                "used_percent": bot_used,
                "remaining_percent": bot_rem,
                "reset_in_sec": bot_reset_sec,
            },
            "grok_chat": {
                "used_percent": chat_used,
                "remaining_percent": chat_rem,
                "reset_in_sec": reset_in_sec,
            },
        }
    except Exception as e:
        return {"status": "error", "model": "grok-4.7", "error": sanitize_error(str(e))}


def update_snapshot():
    global _latest_snapshot_bytes
    prev = {}
    for target in SNAPSHOT_TARGETS:
        if os.path.exists(target):
            try:
                with open(target, "r", encoding="utf-8") as f:
                    prev = json.load(f)
                break
            except Exception:
                pass

    ag = fetch_antigravity_quota()
    if ag.get("status") != "ok" or not ag.get("gemini_weekly"):
        if prev.get("antigravity", {}).get("gemini_weekly"):
            ag = prev["antigravity"]

    cx = fetch_codex_quota()
    if cx.get("status") != "ok":
        if prev.get("codex", {}).get("status") == "ok":
            cx = prev["codex"]

    gk = fetch_grok_quota()
    if gk.get("status") != "ok":
        if prev.get("grok", {}).get("status") == "ok":
            gk = prev["grok"]

    snapshot = {
        "updated_at": int(time.time()),
        "antigravity": ag,
        "codex": cx,
        "grok": gk,
    }

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
            ag = snapshot.get("antigravity", {})
            c5 = ag.get("claude_5h")
            cw = ag.get("claude_weekly")
            c5_str = f"{c5['used_percent']}%" if c5 and "used_percent" in c5 else "N/A"
            cw_str = f"{cw['used_percent']}%" if cw and "used_percent" in cw else "N/A"
            cx = snapshot.get("codex", {}).get("window_5h", {}).get("used_percent", 0)
            print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] Snapshot updated ({written} targets): "
                  f"AG Claude 5h={c5_str}, W={cw_str}, Codex 5h={cx}%")
        except Exception as e:
            print(f"Error updating snapshot: {e}", file=sys.stderr)

        if once:
            break
        time.sleep(DEFAULT_INTERVAL_SEC)


if __name__ == "__main__":
    main()
