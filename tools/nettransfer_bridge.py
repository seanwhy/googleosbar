#!/usr/bin/env python3
"""
Local Tailscale & NetTransfer Agent manager bridge for BentoBar VM daemon.

Exposes read-only Tailscale / agent status (`GET /tailnet/status`) and
strictly localhost/VM-LAN guarded lifecycle actions (`POST /nettransfer/action`)
without ever returning private keys or public keys.
"""

from __future__ import annotations

import datetime
import glob
import hashlib
import ipaddress
import json
import os
import re
import subprocess
import threading
import time
import urllib.parse

AGENTD_BIN = "/usr/local/bin/nettransfer-agentd"
AGENT_CONFIG = "/etc/nettransfer/agent.json"
AGENT_STATE_FILE = "/etc/nettransfer/updater/state-agentd.json"
AGENT_SERVICE = "nettransfer-agentd"
DEFAULT_VPS_URL = "https://vps.why.band"
ALIYUN_TAILSCALE_IP = "100.79.26.61"
MANIFEST_PATH = "/download/agent-manifest.json"
MAX_BINARY_BYTES = 128 * 1024 * 1024
AUTO_UPDATE_NOTE = "v0.4.0+ Agent 已内置随服务端自动静默更新；此处保留启停/重启/注册/手动触发与日志排查"

_action_lock = threading.Lock()
_SEMVER_RE = re.compile(r"\b(?:nettransfer-agent[d]?\s+)?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\b", re.IGNORECASE)
_ENROLL_CODE_RE = re.compile(r"^[A-Za-z0-9._-]{6,128}$")

_ALLOWED_NETS = (
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("fe80::/10"),
    ipaddress.ip_network("fc00::/7"),
)
_TAILSCALE_CGNAT = ipaddress.ip_network("100.64.0.0/10")


def is_local_management_client(ip_str: str) -> bool:
    """Allow only loopback and local Android-VM/LAN peers; block Tailscale & public IPs."""
    raw = (ip_str or "").strip()
    if "%" in raw:
        raw = raw.split("%", 1)[0]
    try:
        addr = ipaddress.ip_address(raw)
    except ValueError:
        return False

    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        addr = addr.ipv4_mapped

    if isinstance(addr, ipaddress.IPv4Address) and addr in _TAILSCALE_CGNAT:
        return False

    return any(addr in net for net in _ALLOWED_NETS)


def short_node_name(node: dict) -> str:
    """Prefer Tailscale MagicDNS short name over raw OS HostName."""
    if not isinstance(node, dict):
        return ""
    dns = str(node.get("DNSName") or "").strip().rstrip(".")
    if dns:
        label = dns.split(".", 1)[0].strip()
        if label:
            return label
        return dns
    host = str(node.get("HostName") or "").strip()
    if host:
        return host
    return str(node.get("ID") or "").strip()


def parse_version_output(output: str | None) -> str:
    if not output:
        return ""
    m = _SEMVER_RE.search(output)
    return m.group(1) if m else ""


def _parse_semver_parts(ver: str | None) -> tuple[int, int, int] | None:
    if not ver:
        return None
    clean = ver.strip()
    if clean.startswith(("v", "V")):
        clean = clean[1:]
    clean = clean.split("-", 1)[0]
    parts = clean.split(".")
    if len(parts) < 2:
        return None
    try:
        maj = int(parts[0])
        minor = int(parts[1])
        pat = int(parts[2]) if len(parts) >= 3 else 0
        return (maj, minor, pat)
    except ValueError:
        return None


def compare_semver(left: str | None, right: str | None) -> int:
    lp = _parse_semver_parts(left)
    rp = _parse_semver_parts(right)
    if lp is None or rp is None:
        return 0
    if lp < rp:
        return -1
    if lp > rp:
        return 1
    return 0


def has_valid_elf_header(file_path: str) -> bool:
    try:
        with open(file_path, "rb") as f:
            magic = f.read(4)
        return magic == b"\x7fELF"
    except OSError:
        return False


def compute_sha256_hex(file_path: str) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest().lower()


def _detect_arch() -> str:
    m = os.uname().machine.lower()
    if "aarch64" in m or "arm64" in m:
        return "arm64"
    return "amd64"


def _run(argv: list[str], timeout: float = 5.0) -> tuple[int, str, str]:
    try:
        res = subprocess.run(
            argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            check=False,
        )
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return 124, "", "command timed out"
    except Exception as exc:
        return 1, "", str(exc)


def _pick_ipv4(ips: object) -> str:
    if not isinstance(ips, list):
        return ""
    for item in ips:
        s = str(item or "").strip()
        if "." in s:
            return s
    return str(ips[0]).strip() if ips else ""


def parse_tailscale_status_json(raw_json: str) -> dict:
    data = json.loads(raw_json)
    backend_state = str(data.get("BackendState") or "").strip()
    running = backend_state.lower() == "running"

    ct = data.get("CurrentTailnet") if isinstance(data.get("CurrentTailnet"), dict) else {}
    tailnet_name = str(ct.get("Name") or ct.get("MagicDNSSuffix") or data.get("MagicDNSSuffix") or "").strip()

    nodes = []
    self_node = data.get("Self")
    primary_derp = ""
    if isinstance(self_node, dict):
        primary_derp = str(self_node.get("Relay") or "").strip()
        self_online = running or bool(self_node.get("Online"))
        self_name = short_node_name(self_node)
        self_ip = _pick_ipv4(self_node.get("TailscaleIPs") or data.get("TailscaleIPs"))
        nodes.append({
            "id": str(self_node.get("ID") or self_name),
            "name": self_name,
            "display_name": self_name,
            "ip": self_ip,
            "os": str(self_node.get("OS") or "").strip().lower(),
            "online": self_online,
            "mode": "self" if self_online else "offline",
            "relay": primary_derp,
        })

    peers_raw = data.get("Peer") if isinstance(data.get("Peer"), dict) else {}
    peer_list = []
    for key, p in peers_raw.items():
        if not isinstance(p, dict):
            continue
        if str(p.get("OS") or "").strip().lower() == "js":
            continue
        name = short_node_name(p)
        online = bool(p.get("Online"))
        cur_addr = str(p.get("CurAddr") or "").strip()
        relay = str(p.get("Relay") or "").strip()
        mode = "offline" if not online else ("direct" if cur_addr else "derp")
        peer_list.append({
            "id": str(p.get("ID") or name or key),
            "name": name,
            "display_name": name,
            "ip": _pick_ipv4(p.get("TailscaleIPs")),
            "os": str(p.get("OS") or "").strip().lower(),
            "online": online,
            "mode": mode,
            "relay": relay,
        })

    peer_list.sort(key=lambda item: (not item["online"], item["name"].lower(), item["ip"]))
    nodes.extend(peer_list)

    online_count = sum(1 for n in nodes if n["online"])
    remote_online = [n for n in peer_list if n["online"]]
    direct_remote = sum(1 for n in remote_online if n["mode"] == "direct")
    direct_ratio = round(direct_remote / len(remote_online), 2) if remote_online else 1.0

    return {
        "available": len(nodes) > 0,
        "tailnet_name": tailnet_name,
        "total_nodes": len(nodes),
        "online_nodes": online_count,
        "direct_ratio": direct_ratio,
        "primary_derp": primary_derp,
        "nodes": nodes,
    }


def read_tailscale_status() -> dict:
    rc, out, err = _run(["tailscale", "status", "--json"], timeout=4.0)
    if rc != 0 or not out:
        return {
            "available": False,
            "tailnet_name": "",
            "total_nodes": 0,
            "online_nodes": 0,
            "direct_ratio": 0.0,
            "primary_derp": "",
            "nodes": [],
            "error": err or "tailscale status failed",
        }
    try:
        return parse_tailscale_status_json(out)
    except Exception as exc:
        return {
            "available": False,
            "tailnet_name": "",
            "total_nodes": 0,
            "online_nodes": 0,
            "direct_ratio": 0.0,
            "primary_derp": "",
            "nodes": [],
            "error": str(exc),
        }


def _read_safe_agent_config() -> dict:
    """Read only non-secret identity metadata from /etc/nettransfer/agent.json."""
    py = (
        "import json, os; p=%r\n"
        "if not os.path.exists(p): print('{}')\n"
        "else:\n"
        "  d=json.load(open(p,'r',encoding='utf-8'))\n"
        "  print(json.dumps({'deviceId': d.get('deviceId',''), 'vpsUrl': d.get('vpsUrl',''), 'revoked': bool(d.get('revoked', False))}))"
        % AGENT_CONFIG
    )
    rc, out, _ = _run(["sudo", "-n", "python3", "-c", py], timeout=3.0)
    if rc != 0 or not out:
        return {"deviceId": "", "vpsUrl": DEFAULT_VPS_URL, "revoked": False}
    try:
        parsed = json.loads(out)
        if not parsed.get("vpsUrl"):
            parsed["vpsUrl"] = DEFAULT_VPS_URL
        return parsed
    except Exception:
        return {"deviceId": "", "vpsUrl": DEFAULT_VPS_URL, "revoked": False}


def _find_latest_backup(bin_path: str = AGENTD_BIN) -> str:
    candidates = []
    exact = bin_path + ".bak"
    if os.path.exists(exact):
        candidates.append(exact)
    candidates.extend(glob.glob(bin_path + ".bak.*"))
    if not candidates:
        return ""
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def _format_short_iso(raw: str) -> str:
    text = (raw or "").strip()
    if not text:
        return ""
    try:
        iso = text[:-1] + "+00:00" if text.endswith("Z") else text
        dt = datetime.datetime.fromisoformat(iso)
        if dt.tzinfo is not None:
            dt = dt.astimezone()
        return dt.strftime("%m-%d %H:%M")
    except Exception:
        return text[:16]


def parse_built_in_updater_state_json(raw_json: str | None) -> dict | None:
    if not raw_json or not raw_json.strip():
        return None
    try:
        data = json.loads(raw_json)
    except Exception:
        return None
    if not isinstance(data, dict):
        return None

    pending = str(data.get("pendingTargetVersion") or "").strip()
    previous = str(data.get("previousVersion") or "").strip()
    last_check = str(data.get("lastCheckAt") or "").strip()
    last_error = str(data.get("lastError") or "").strip()

    failure_count = 0
    next_retry = ""
    failure_error = ""
    failures = data.get("failures")
    if isinstance(failures, dict):
        for entry in failures.values():
            if not isinstance(entry, dict):
                continue
            try:
                cnt = int(entry.get("count") or 0)
            except (TypeError, ValueError):
                cnt = 0
            if cnt >= failure_count:
                failure_count = cnt
                next_retry = str(entry.get("nextRetryAt") or "").strip()
                failure_error = str(entry.get("lastError") or "").strip()

    if not last_error:
        last_error = failure_error

    if pending:
        summary = f"待重启确认 v{pending}"
    elif failure_count > 0:
        retry_short = _format_short_iso(next_retry)
        summary = (
            f"自动更新退避中（失败 {failure_count} 次，下次 {retry_short}）"
            if retry_short
            else f"自动更新退避中（失败 {failure_count} 次）"
        )
    elif last_error:
        summary = f"最近检查错误: {last_error}"
    elif last_check:
        summary = f"已启用内置自更新（上次检查 {_format_short_iso(last_check)}）"
    else:
        summary = "已启用内置自更新"

    return {
        "pending_target_version": pending,
        "previous_version": previous,
        "last_check_at": last_check,
        "last_error": last_error,
        "failure_count": failure_count,
        "next_retry_at": next_retry,
        "summary": summary,
    }


def _read_built_in_updater_state(state_path: str = AGENT_STATE_FILE) -> dict | None:
    try:
        if os.path.exists(state_path):
            with open(state_path, "r", encoding="utf-8") as f:
                return parse_built_in_updater_state_json(f.read())
    except OSError:
        pass

    py = (
        "import os, sys; p=%r\n"
        "if os.path.exists(p):\n"
        "  sys.stdout.write(open(p,'r',encoding='utf-8').read())"
        % state_path
    )
    rc, out, _ = _run(["sudo", "-n", "python3", "-c", py], timeout=3.0)
    if rc != 0 or not out:
        return None
    return parse_built_in_updater_state_json(out)


def read_nettransfer_agent_status() -> dict:
    bin_exists = os.path.exists(AGENTD_BIN)
    rc_load, load_state, _ = _run(["systemctl", "show", "-p", "LoadState", "--value", AGENT_SERVICE], timeout=3.0)
    unit_loaded = rc_load == 0 and load_state == "loaded"
    installed = bin_exists and unit_loaded

    _, active_state, _ = _run(["systemctl", "is-active", AGENT_SERVICE], timeout=3.0)
    _, enabled_state, _ = _run(["systemctl", "is-enabled", AGENT_SERVICE], timeout=3.0)
    _, pid_raw, _ = _run(["systemctl", "show", "-p", "MainPID", "--value", AGENT_SERVICE], timeout=3.0)
    pid = int(pid_raw) if pid_raw.isdigit() and int(pid_raw) > 0 else None

    version = ""
    if bin_exists:
        rc_ver, ver_out, ver_err = _run([AGENTD_BIN, "--version"], timeout=4.0)
        if rc_ver == 0:
            version = parse_version_output(ver_out or ver_err)

    cfg = _read_safe_agent_config()
    device_id = str(cfg.get("deviceId") or "").strip()
    vps_url = str(cfg.get("vpsUrl") or DEFAULT_VPS_URL).strip().rstrip("/")
    revoked = bool(cfg.get("revoked", False))

    latest_backup = _find_latest_backup()
    supports_auto_update = bool(version) and compare_semver(version, "0.4.0") >= 0
    built_in_updater = _read_built_in_updater_state()
    _, log_out, _ = _run(
        ["sudo", "-n", "journalctl", "-u", AGENT_SERVICE, "-n", "12", "--no-pager", "--output=short-iso"],
        timeout=4.0,
    )
    recent_logs = [line for line in log_out.splitlines() if line.strip()] if log_out else []

    return {
        "installed": installed,
        "binary_exists": bin_exists,
        "active": active_state == "active",
        "enabled": enabled_state == "enabled",
        "state": active_state if installed else "not-installed",
        "pid": pid,
        "version": version,
        "arch": _detect_arch(),
        "vps_url": vps_url or DEFAULT_VPS_URL,
        "registered": bool(device_id) and not revoked,
        "device_id": device_id,
        "revoked": revoked,
        "has_backup": bool(latest_backup),
        "latest_backup": os.path.basename(latest_backup) if latest_backup else "",
        "supports_auto_update": supports_auto_update,
        "built_in_updater": built_in_updater,
        "auto_update_note": AUTO_UPDATE_NOTE,
        "recent_logs": recent_logs,
    }


def read_combined_status() -> dict:
    return {
        "updated_at": int(time.time()),
        "tailnet": read_tailscale_status(),
        "agent": read_nettransfer_agent_status(),
    }


def _curl_fetch_bytes(url: str, timeout: float = 20.0, resolve_aliyun: bool = False) -> bytes:
    cmd = ["curl", "-fsSL", "--connect-timeout", "8", "--max-time", str(int(timeout))]
    if resolve_aliyun:
        parsed = urllib.parse.urlparse(url)
        if parsed.hostname == "vps.why.band":
            port = parsed.port or (443 if parsed.scheme == "https" else 80)
            cmd.extend(["--noproxy", "*", "--resolve", f"vps.why.band:{port}:{ALIYUN_TAILSCALE_IP}"])
    cmd.append(url)
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 2, check=False)
    if res.returncode != 0:
        raise RuntimeError(res.stderr.decode("utf-8", "replace").strip() or f"curl exited {res.returncode}")
    return res.stdout


def _fetch_with_fallback(url: str, timeout: float = 20.0) -> bytes:
    try:
        return _curl_fetch_bytes(url, timeout=timeout, resolve_aliyun=False)
    except Exception:
        return _curl_fetch_bytes(url, timeout=timeout, resolve_aliyun=True)


def _resolve_safe_url(raw_url: str, base_url: str) -> str:
    raw = (raw_url or "").strip()
    if raw.startswith("/"):
        raw = base_url.rstrip("/") + raw
    parsed = urllib.parse.urlparse(raw)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError(f"不安全的下载地址: {raw}")
    return raw


def check_update(status: dict | None = None) -> dict:
    st = status or read_nettransfer_agent_status()
    vps_url = str(st.get("vps_url") or DEFAULT_VPS_URL).rstrip("/")
    arch = str(st.get("arch") or _detect_arch())
    current_version = str(st.get("version") or "")
    manifest_url = vps_url + MANIFEST_PATH

    try:
        raw = _fetch_with_fallback(manifest_url, timeout=15.0)
        manifest = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        return {"ok": False, "message": f"拉取版本清单失败: {exc}", "current_version": current_version}

    latest_version = str(manifest.get("version") or "").strip()
    if not latest_version:
        return {"ok": False, "message": "版本清单缺少 version 字段", "current_version": current_version}

    artifacts = manifest.get("artifacts") if isinstance(manifest.get("artifacts"), dict) else {}
    key = f"agentd-linux-{arch}"
    entry = artifacts.get(key) if isinstance(artifacts.get(key), dict) else {}
    raw_url = str(entry.get("url") or f"/download/nettransfer-agentd-linux-{arch}").strip()
    try:
        resolved_url = _resolve_safe_url(raw_url, vps_url)
    except ValueError as exc:
        return {"ok": False, "message": str(exc), "current_version": current_version}

    has_update = bool(current_version) and compare_semver(latest_version, current_version) > 0
    return {
        "ok": True,
        "message": f"发现新版本 v{latest_version}" if has_update else f"已是最新版本 v{latest_version}",
        "current_version": current_version,
        "latest_version": latest_version,
        "has_update": has_update,
        "released_at": str(manifest.get("releasedAt") or ""),
        "notes": str(manifest.get("notes") or ""),
        "url": resolved_url,
        "sha256": str(entry.get("sha256") or "").strip().lower(),
        "size": int(entry.get("size") or 0),
        "artifact_key": key,
    }


def _run_upgrade() -> dict:
    st = read_nettransfer_agent_status()
    chk = check_update(st)
    if not chk.get("ok"):
        return {"ok": False, "message": chk.get("message") or "检查更新失败", "agent": st}

    tmp_path = f"/tmp/nettransfer-agentd.upgrade.{os.getpid()}"
    backup_path = ""
    try:
        data = _fetch_with_fallback(chk["url"], timeout=60.0)
        if len(data) > MAX_BINARY_BYTES:
            return {"ok": False, "message": "下载文件超过 128MB 限制", "agent": st}
        with open(tmp_path, "wb") as f:
            f.write(data)
        os.chmod(tmp_path, 0o755)

        if not has_valid_elf_header(tmp_path):
            return {"ok": False, "message": "校验失败：下载文件不是有效的 Linux ELF 可执行文件", "agent": st}
        expected_size = int(chk.get("size") or 0)
        if expected_size > 0 and os.path.getsize(tmp_path) != expected_size:
            return {
                "ok": False,
                "message": f"文件大小不匹配（预期 {expected_size}，实际 {os.path.getsize(tmp_path)}）",
                "agent": st,
            }
        expected_sha = str(chk.get("sha256") or "").strip().lower()
        if expected_sha:
            actual_sha = compute_sha256_hex(tmp_path)
            if actual_sha != expected_sha:
                return {"ok": False, "message": f"SHA-256 校验失败（实际 {actual_sha[:12]}...）", "agent": st}

        rc_ver, ver_out, _ = _run([tmp_path, "--version"], timeout=5.0)
        new_ver = parse_version_output(ver_out)
        if rc_ver != 0 or not new_ver:
            return {"ok": False, "message": "新版本二进制 --version 预检失败，已中止安装", "agent": st}

        _run(["sudo", "-n", "systemctl", "stop", AGENT_SERVICE], timeout=15.0)
        if os.path.exists(AGENTD_BIN):
            ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            backup_path = f"{AGENTD_BIN}.bak.{ts}"
            rc_bk, _, bk_err = _run(["sudo", "-n", "install", "-m", "755", AGENTD_BIN, backup_path], timeout=5.0)
            if rc_bk != 0:
                _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
                return {"ok": False, "message": f"备份旧版失败: {bk_err}", "agent": read_nettransfer_agent_status()}

        rc_inst, _, inst_err = _run(["sudo", "-n", "install", "-m", "755", tmp_path, AGENTD_BIN], timeout=10.0)
        if rc_inst != 0:
            if backup_path and os.path.exists(backup_path):
                _run(["sudo", "-n", "install", "-m", "755", backup_path, AGENTD_BIN], timeout=10.0)
            _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
            return {"ok": False, "message": f"安装新版本失败: {inst_err}", "agent": read_nettransfer_agent_status()}

        rc_start, _, start_err = _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
        time.sleep(1.0)
        _, is_act, _ = _run(["systemctl", "is-active", AGENT_SERVICE], timeout=3.0)
        if rc_start != 0 or is_act != "active":
            if backup_path and os.path.exists(backup_path):
                _run(["sudo", "-n", "install", "-m", "755", backup_path, AGENTD_BIN], timeout=10.0)
                _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
                return {
                    "ok": False,
                    "message": f"新版启动失败（{start_err or is_act}），已自动回滚至上一版本",
                    "agent": read_nettransfer_agent_status(),
                }
            return {"ok": False, "message": f"新版启动失败: {start_err or is_act}", "agent": read_nettransfer_agent_status()}

        return {
            "ok": True,
            "message": f"NetTransfer Agent 已升级到 v{new_ver} 并正常运行",
            "agent": read_nettransfer_agent_status(),
        }
    except Exception as exc:
        return {"ok": False, "message": f"升级异常: {exc}", "agent": read_nettransfer_agent_status()}
    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass


def _run_rollback() -> dict:
    backup_path = _find_latest_backup()
    if not backup_path or not os.path.exists(backup_path):
        return {"ok": False, "message": "未找到可回滚的历史备份", "agent": read_nettransfer_agent_status()}
    if not has_valid_elf_header(backup_path):
        return {"ok": False, "message": "备份文件 ELF 校验无效", "agent": read_nettransfer_agent_status()}
    rc_ver, ver_out, _ = _run([backup_path, "--version"], timeout=5.0)
    old_ver = parse_version_output(ver_out)
    if rc_ver != 0 or not old_ver:
        return {"ok": False, "message": "备份文件 --version 预检失败", "agent": read_nettransfer_agent_status()}

    _run(["sudo", "-n", "systemctl", "stop", AGENT_SERVICE], timeout=15.0)
    rc_inst, _, inst_err = _run(["sudo", "-n", "install", "-m", "755", backup_path, AGENTD_BIN], timeout=10.0)
    if rc_inst != 0:
        _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
        return {"ok": False, "message": f"恢复备份失败: {inst_err}", "agent": read_nettransfer_agent_status()}

    rc_start, _, start_err = _run(["sudo", "-n", "systemctl", "start", AGENT_SERVICE], timeout=15.0)
    if rc_start != 0:
        return {"ok": False, "message": f"回滚后服务启动失败: {start_err}", "agent": read_nettransfer_agent_status()}
    return {
        "ok": True,
        "message": f"已回滚至 v{old_ver} ({os.path.basename(backup_path)}) 并启动服务",
        "agent": read_nettransfer_agent_status(),
    }


def _run_enroll(code: str) -> dict:
    clean_code = (code or "").strip()
    if not _ENROLL_CODE_RE.fullmatch(clean_code):
        return {"ok": False, "message": "注册码格式无效", "agent": read_nettransfer_agent_status()}
    if not os.path.exists(AGENTD_BIN):
        return {"ok": False, "message": "未安装 nettransfer-agentd", "agent": read_nettransfer_agent_status()}

    st = read_nettransfer_agent_status()
    vps_url = str(st.get("vps_url") or DEFAULT_VPS_URL)
    _run(["sudo", "-n", "mkdir", "-p", "/etc/nettransfer"], timeout=3.0)
    _run(["sudo", "-n", "chmod", "700", "/etc/nettransfer"], timeout=3.0)
    rc, out, err = _run(
        ["sudo", "-n", AGENTD_BIN, "--config", AGENT_CONFIG, "--vps-url", vps_url, "--enroll", clean_code, "--enroll-only"],
        timeout=25.0,
    )
    if rc != 0:
        return {"ok": False, "message": f"注册失败: {err or out}", "agent": read_nettransfer_agent_status()}

    _run(["sudo", "-n", "chmod", "600", AGENT_CONFIG], timeout=3.0)
    _run(["sudo", "-n", "systemctl", "enable", "--now", AGENT_SERVICE], timeout=15.0)
    return {
        "ok": True,
        "message": "设备注册成功并已启动 NetTransfer 服务",
        "agent": read_nettransfer_agent_status(),
    }


def perform_action(action: str, code: str = "") -> dict:
    act = (action or "").strip().lower()
    with _action_lock:
        if act in ("start", "stop", "restart"):
            rc, out, err = _run(["sudo", "-n", "systemctl", act, AGENT_SERVICE], timeout=20.0)
            st = read_nettransfer_agent_status()
            if rc != 0:
                return {"ok": False, "message": f"{act} 失败: {err or out}", "agent": st}
            label = {"start": "已启动", "stop": "已停止", "restart": "已重启"}[act]
            return {"ok": True, "message": f"NetTransfer 服务{label}", "agent": st}
        if act == "check_update":
            st = read_nettransfer_agent_status()
            res = check_update(st)
            res["agent"] = st
            return res
        if act == "upgrade":
            return _run_upgrade()
        if act == "rollback":
            return _run_rollback()
        if act == "enroll":
            return _run_enroll(code)
        return {"ok": False, "message": f"不支持的操作: {act}", "agent": read_nettransfer_agent_status()}
