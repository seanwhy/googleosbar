import http.client
import http.server
import json
import socket
import threading
import unittest

import keyboard_bridge


class _CountingHTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, server_address, RequestHandlerClass):
        self.accept_count = 0
        self.close_after_first = False
        self.requests_seen = []
        super().__init__(server_address, RequestHandlerClass)

    def get_request(self):
        sock, addr = super().get_request()
        self.accept_count += 1
        return sock, addr


def _start_mock_target(close_after_first=False, device_id="dev-mac-1"):
    class MockAgentHandler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path == "/api/keyboard/ping":
                self.server.requests_seen.append(("GET", self.path, None))
                body = json.dumps({"ok": True, "deviceId": device_id}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                if self.server.close_after_first and len(self.server.requests_seen) == 1:
                    self.send_header("Connection", "close")
                    self.close_connection = True
                else:
                    self.send_header("Connection", "keep-alive")
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or "0")
            raw = self.rfile.read(length) if length > 0 else b""
            payload = json.loads(raw.decode("utf-8")) if raw else {}
            self.server.requests_seen.append(("POST", self.path, payload))
            if self.path == "/api/keyboard/direct":
                events = payload.get("events") if isinstance(payload, dict) else []
                body = json.dumps({"ok": True, "accepted": len(events or []), "lastSeq": 42}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format, *args):
            pass

    srv = _CountingHTTPServer(("127.0.0.1", 0), MockAgentHandler)
    srv.close_after_first = close_after_first
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, t


class TestKeyboardBridge(unittest.TestCase):
    def _post_json(self, port, path, obj):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        try:
            body = json.dumps(obj).encode("utf-8")
            conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            data = json.loads(resp.read().decode("utf-8"))
            return resp.status, data
        finally:
            conn.close()

    def _get_json(self, port, path):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        try:
            conn.request("GET", path)
            resp = conn.getresponse()
            data = json.loads(resp.read().decode("utf-8"))
            return resp.status, data
        finally:
            conn.close()

    def test_health_ping_forward_and_keepalive_reuse(self):
        target, _ = _start_mock_target(device_id="mac-mini")
        target_port = target.server_address[1]
        peer_cache = keyboard_bridge.TailscalePeerCache(
            ttl_sec=60.0,
            status_fetcher=lambda: {
                "available": True,
                "nodes": [
                    {"ip": "127.0.0.1", "mode": "direct", "relay": ""},
                    {"ip": "100.64.0.9", "mode": "derp", "relay": "hkg"},
                ],
            },
        )
        svc = keyboard_bridge.KeyboardBridgeService(pool=keyboard_bridge.TargetConnectionPool(), peer_cache=peer_cache)
        bridge, _ = keyboard_bridge.start_keyboard_bridge_thread(port=0, bind_host="127.0.0.1", service=svc)
        self.assertIsNotNone(bridge)
        bridge_port = bridge.server_address[1]
        try:
            st, health = self._get_json(bridge_port, "/health")
            self.assertEqual(st, 200)
            self.assertTrue(health["ok"])
            self.assertEqual(health["tailscalePeers"], 2)

            st, ping_res = self._post_json(bridge_port, "/keyboard/ping", {
                "host": "127.0.0.1",
                "port": target_port,
                "expectedDeviceId": "mac-mini",
            })
            self.assertEqual(st, 200)
            self.assertTrue(ping_res["ok"])
            self.assertEqual(ping_res["deviceId"], "mac-mini")
            self.assertEqual(ping_res["via"], "tailscale")
            self.assertTrue(ping_res["direct"])
            self.assertEqual(ping_res["relay"], "")

            # Forward with dict payload reuses same pooled socket
            st, fwd1 = self._post_json(bridge_port, "/keyboard/forward", {
                "host": "127.0.0.1",
                "port": target_port,
                "payload": {"deviceId": "android-1", "events": [{"type": "text", "text": "a"}]},
            })
            self.assertEqual(st, 200)
            self.assertTrue(fwd1["ok"])
            self.assertEqual(fwd1["accepted"], 1)
            self.assertEqual(fwd1["lastSeq"], 42)

            # Forward with raw JSON string payload also reuses same pooled socket
            raw_payload = json.dumps({"deviceId": "android-1", "events": [{"type": "text", "text": "b"}, {"type": "text", "text": "c"}]})
            st, fwd2 = self._post_json(bridge_port, "/keyboard/forward", {
                "host": "127.0.0.1",
                "port": target_port,
                "payload": raw_payload,
            })
            self.assertEqual(st, 200)
            self.assertTrue(fwd2["ok"])
            self.assertEqual(fwd2["accepted"], 2)
            self.assertEqual(len(target.requests_seen), 3)
            self.assertEqual(target.accept_count, 1)

            # Device ID mismatch test
            st, mismatch = self._post_json(bridge_port, "/keyboard/ping", {
                "host": "127.0.0.1",
                "port": target_port,
                "expectedDeviceId": "other-device",
            })
            self.assertEqual(st, 200)
            self.assertFalse(mismatch["ok"])
            self.assertIn("mismatch", mismatch["error"])
        finally:
            svc.pool.close_all()
            bridge.shutdown()
            bridge.server_close()
            target.shutdown()
            target.server_close()

    def test_derp_relay_classification(self):
        target, _ = _start_mock_target(device_id="win-box")
        target_port = target.server_address[1]
        # Intercept pool target call to inspect cache lookup for DERP peer IP
        peer_cache = keyboard_bridge.TailscalePeerCache(
            status_fetcher=lambda: {
                "available": True,
                "nodes": [{"ip": "100.77.88.99", "mode": "derp", "relay": "tok"}],
            }
        )
        svc = keyboard_bridge.KeyboardBridgeService(peer_cache=peer_cache)
        orig_open = svc.pool._open_connection
        svc.pool._open_connection = lambda _h, _p: orig_open("127.0.0.1", target_port)
        try:
            res = svc.ping_target("100.77.88.99", target_port, "win-box")
            self.assertTrue(res["ok"])
            self.assertFalse(res["direct"])
            self.assertEqual(res["relay"], "tok")
        finally:
            svc.pool.close_all()
            target.shutdown()
            target.server_close()

    def test_broken_pipe_reconnect_retry(self):
        # Accept first persistent socket, then kill it from server side before second request.
        accepts = {"count": 0}
        reqs = []
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(4)
        port = listener.getsockname()[1]
        stop_flag = threading.Event()

        def serve():
            while not stop_flag.is_set():
                try:
                    listener.settimeout(0.2)
                    conn, _ = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                accepts["count"] += 1
                idx = accepts["count"]
                conn.settimeout(2.0)
                if idx == 1:
                    # First request succeeds, then server drops socket silently so pool keeps stale conn
                    _ = conn.recv(4096)
                    reqs.append(1)
                    body = b'{"ok":true,"deviceId":"mac-1"}'
                    conn.sendall(
                        b"HTTP/1.1 200 OK\r\n"
                        b"Content-Type: application/json\r\n"
                        b"Connection: keep-alive\r\n"
                        b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body
                    )
                    time_to_drop = conn
                    time_to_drop.close()
                else:
                    _ = conn.recv(4096)
                    reqs.append(2)
                    body = b'{"ok":true,"accepted":1,"lastSeq":9}'
                    conn.sendall(
                        b"HTTP/1.1 200 OK\r\n"
                        b"Content-Type: application/json\r\n"
                        b"Connection: keep-alive\r\n"
                        b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body
                    )
                    conn.close()

        t = threading.Thread(target=serve, daemon=True)
        t.start()
        svc = keyboard_bridge.KeyboardBridgeService()
        try:
            r1 = svc.ping_target("127.0.0.1", port, "mac-1")
            self.assertTrue(r1["ok"])
            r2 = svc.forward_payload("127.0.0.1", port, {"events": [{"type": "text", "text": "hi"}]})
            self.assertTrue(r2["ok"])
            self.assertEqual(r2["accepted"], 1)
            self.assertEqual(accepts["count"], 2)
            self.assertEqual(reqs, [1, 2])
        finally:
            stop_flag.set()
            svc.pool.close_all()
            listener.close()

    def test_rejects_non_local_client_ip(self):
        svc = keyboard_bridge.KeyboardBridgeService()
        srv = keyboard_bridge.ThreadingKeyboardBridgeServer(
            ("127.0.0.1", 0),
            keyboard_bridge.make_handler_class(svc, client_check=lambda _ip: False),
        )
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            st, body = self._get_json(srv.server_address[1], "/health")
            self.assertEqual(st, 403)
            self.assertFalse(body["ok"])
        finally:
            srv.shutdown()
            srv.server_close()


    def test_ed25519_headers_match_go_and_kotlin_vector(self):
        fixture_key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyB5tVYuj+ZU+UB4sRLoqYunkB+FOuaVvtfg45ELrQSWZA=="
        body_utf8 = '{"apiVersion":1,"text":"你好","hostname":"pc"}'.encode("utf-8")
        headers = keyboard_bridge.build_agent_headers(
            {"deviceId": "device-1", "privateKey": fixture_key},
            "POST",
            body_utf8,
            unix_seconds=1_700_000_000,
            nonce="nonce",
        )
        self.assertEqual(headers["X-NetTransfer-Device"], "device-1")
        self.assertEqual(headers["X-NetTransfer-Agent-API"], "1")
        self.assertEqual(headers["X-NetTransfer-Timestamp"], "1700000000")
        self.assertEqual(headers["X-NetTransfer-Nonce"], "nonce")
        self.assertEqual(headers["X-NetTransfer-Body-Digest"], "4bUku16HrAmGhBc1QJkHKxzZc0+jKKeFPzcnZ3zMQVI=")
        self.assertEqual(
            headers["X-NetTransfer-Signature"],
            "xuDLMCmTv9QpdjU3Du+igpP92Pcrk833EL+APlxAxplcAXA9aqBtVCblV3PJmD911rSgz9sM5YvN7rvYCHziBQ==",
        )

    def test_signed_devices_and_relay_endpoints_without_leaking_key(self):
        import tempfile
        fixture_key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyB5tVYuj+ZU+UB4sRLoqYunkB+FOuaVvtfg45ELrQSWZA=="
        seen_vps_calls = []

        def fake_vps(method, url, headers, body):
            seen_vps_calls.append((method, url, dict(headers), body))
            if url.endswith("/api/agent/v1/devices"):
                return 200, json.dumps({
                    "devices": [
                        {"id": "win-1", "displayName": "PC", "tailscaleName": "amd9950x3d", "platform": "windows", "online": True, "keyboardSupported": True, "keyboardActive": True}
                    ]
                }).encode("utf-8")
            if url.endswith("/api/agent/v1/keyboard/send"):
                return 200, json.dumps({"accepted": 1, "targetOnline": True, "queued": 1}).encode("utf-8")
            return 404, b'{"error":"not found"}'

        with tempfile.TemporaryDirectory() as td:
            id_file = f"{td}/agent.json"
            with open(id_file, "w", encoding="utf-8") as f:
                json.dump({
                    "vpsUrl": "https://vps.example.com/",
                    "displayName": "googlebook",
                    "deviceId": "vm-book-1",
                    "privateKey": fixture_key,
                }, f)

            svc = keyboard_bridge.KeyboardBridgeService(
                identity_paths=(id_file,),
                peer_cache=keyboard_bridge.TailscalePeerCache(status_fetcher=lambda: {"nodes": []}),
                vps_request=fake_vps,
            )
            srv, _ = keyboard_bridge.start_keyboard_bridge_thread(port=0, bind_host="127.0.0.1", service=svc)
            port = srv.server_address[1]
            try:
                st, health = self._get_json(port, "/health")
                self.assertEqual(st, 200)
                self.assertTrue(health["identityReady"])
                self.assertEqual(health["selfDeviceId"], "vm-book-1")
                self.assertEqual(health["selfDisplayName"], "googlebook")
                self.assertNotIn("privateKey", json.dumps(health))

                st, devs = self._get_json(port, "/keyboard/devices")
                self.assertEqual(st, 200)
                self.assertTrue(devs["ok"])
                self.assertEqual(devs["selfDeviceId"], "vm-book-1")
                self.assertEqual(len(devs["devices"]), 1)
                self.assertEqual(devs["devices"][0]["tailscaleName"], "amd9950x3d")
                self.assertEqual(seen_vps_calls[0][2]["X-NetTransfer-Device"], "vm-book-1")

                st, relay = self._post_json(port, "/keyboard/relay", {
                    "targetDeviceId": "win-1",
                    "events": [{"seq": 1, "action": "text", "text": "ok"}],
                })
                self.assertEqual(st, 200)
                self.assertTrue(relay["ok"])
                self.assertEqual(relay["accepted"], 1)
                self.assertTrue(relay["targetOnline"])
                sent_body = json.loads(seen_vps_calls[1][3].decode("utf-8"))
                self.assertEqual(sent_body["sourceDeviceId"], "vm-book-1")
                self.assertEqual(sent_body["sourceName"], "googlebook")
            finally:
                srv.shutdown()
                srv.server_close()
                svc.pool.close_all()
                svc.vps_pool.close_all()

    def test_missing_vm_identity_returns_503(self):
        svc = keyboard_bridge.KeyboardBridgeService(
            identity_paths=("/nonexistent/agent.json",),
            peer_cache=keyboard_bridge.TailscalePeerCache(status_fetcher=lambda: {"nodes": []}),
        )
        srv, _ = keyboard_bridge.start_keyboard_bridge_thread(port=0, bind_host="127.0.0.1", service=svc)
        port = srv.server_address[1]
        try:
            st, health = self._get_json(port, "/health")
            self.assertEqual(st, 200)
            self.assertFalse(health["identityReady"])
            st, devs = self._get_json(port, "/keyboard/devices")
            self.assertEqual(st, 503)
            self.assertFalse(devs["ok"])
        finally:
            srv.shutdown()
            srv.server_close()

if __name__ == "__main__":
    unittest.main()
