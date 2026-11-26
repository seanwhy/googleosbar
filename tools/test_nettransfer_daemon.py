#!/usr/bin/env python3
import os
import tempfile
import unittest

import nettransfer_bridge as nb


class NetTransferBridgeTests(unittest.TestCase):
    def test_is_local_management_client_allows_local_and_blocks_tailscale_or_public(self):
        for allowed in ("127.0.0.1", "::1", "10.192.190.115", "192.168.1.10", "::ffff:127.0.0.1"):
            self.assertTrue(nb.is_local_management_client(allowed), allowed)
        for blocked in ("100.64.0.1", "100.92.145.3", "100.102.26.122", "8.8.8.8", "invalid"):
            self.assertFalse(nb.is_local_management_client(blocked), blocked)

    def test_short_node_name_prefers_magicdns_short_label(self):
        self.assertEqual(
            nb.short_node_name({"HostName": "wuhaoyangdeMac-mini.local", "DNSName": "mac-mini.whydah-silverside.ts.net."}),
            "mac-mini",
        )
        self.assertEqual(
            nb.short_node_name({"HostName": "AMD9950X3D", "DNSName": "amd9950x3d.whydah-silverside.ts.net."}),
            "amd9950x3d",
        )
        self.assertEqual(nb.short_node_name({"HostName": "only-host", "DNSName": ""}), "only-host")

    def test_parse_version_and_compare_semver(self):
        self.assertEqual(nb.parse_version_output("nettransfer-agentd 0.3.3\n"), "0.3.3")
        self.assertEqual(
            nb.parse_version_output("nettransfer-agentd 0.4.0 (3ec0bc7, 2026-11-25T03:33:16Z)\n"),
            "0.4.0",
        )
        self.assertGreater(nb.compare_semver("0.4.0", "0.3.3"), 0)
        self.assertGreaterEqual(nb.compare_semver("0.4.0", "0.4.0"), 0)
        self.assertEqual(nb.compare_semver("v0.3.3", "0.3.3-beta"), 0)
        self.assertLess(nb.compare_semver("0.1.1", "0.3.3"), 0)

    def test_find_latest_backup_supports_dot_bak_and_timestamped(self):
        with tempfile.TemporaryDirectory() as tmp:
            bin_path = os.path.join(tmp, "nettransfer-agentd")
            with open(bin_path, "wb") as f:
                f.write(b"\x7fELF")
            dot_bak = bin_path + ".bak"
            with open(dot_bak, "wb") as f:
                f.write(b"\x7fELF")
            os.utime(dot_bak, (1000, 1000))
            self.assertEqual(nb._find_latest_backup(bin_path), dot_bak)

            stamped_bak = bin_path + ".bak.20261125-020000"
            with open(stamped_bak, "wb") as f:
                f.write(b"\x7fELF")
            os.utime(stamped_bak, (2000, 2000))
            self.assertEqual(nb._find_latest_backup(bin_path), stamped_bak)

    def test_parse_built_in_updater_state_json(self):
        self.assertIsNone(nb.parse_built_in_updater_state_json(""))
        self.assertIsNone(nb.parse_built_in_updater_state_json("{bad json"))

        pending = nb.parse_built_in_updater_state_json(
            '{"pendingTargetVersion":"0.4.1","previousVersion":"0.4.0"}'
        )
        self.assertEqual(pending["pending_target_version"], "0.4.1")
        self.assertIn("0.4.1", pending["summary"])

        backoff = nb.parse_built_in_updater_state_json(
            '{"lastCheckAt":"2026-11-25T03:00:00Z","failures":{"0.4.1":{"count":2,"nextRetryAt":"2026-11-25T03:15:00Z","lastError":"timeout"}}}'
        )
        self.assertEqual(backoff["failure_count"], 2)
        self.assertEqual(backoff["last_error"], "timeout")
        self.assertIn("退避中", backoff["summary"])

        healthy = nb.parse_built_in_updater_state_json('{"lastCheckAt":"2026-11-25T03:30:00Z"}')
        self.assertIn("已启用内置自更新", healthy["summary"])

    def test_elf_and_sha256_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = os.path.join(tmp, "good")
            bad = os.path.join(tmp, "bad")
            with open(good, "wb") as f:
                f.write(b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 32)
            with open(bad, "wb") as f:
                f.write(b"MZ" + b"\x00" * 32)
            self.assertTrue(nb.has_valid_elf_header(good))
            self.assertFalse(nb.has_valid_elf_header(bad))
            self.assertEqual(len(nb.compute_sha256_hex(good)), 64)

    def test_parse_tailscale_status_json_filters_js_and_sorts(self):
        sample = """{
          "BackendState": "Running",
          "CurrentTailnet": {"Name": "xseanwhy@gmail.com", "MagicDNSSuffix": "whydah-silverside.ts.net"},
          "Self": {
            "ID": "self-1",
            "HostName": "debian",
            "DNSName": "googlebook.whydah-silverside.ts.net.",
            "OS": "linux",
            "TailscaleIPs": ["100.92.145.3"],
            "Relay": "ali-hz",
            "Online": true
          },
          "Peer": {
            "p1": {
              "ID": "p1",
              "HostName": "iZ123",
              "DNSName": "ali-derp.whydah-silverside.ts.net.",
              "OS": "linux",
              "TailscaleIPs": ["100.79.26.61"],
              "CurAddr": "47.97.116.235:41641",
              "Relay": "ali-hz",
              "Online": true
            },
            "p2": {
              "ID": "p2",
              "HostName": "tailscale-ssh-console-1",
              "OS": "js",
              "TailscaleIPs": ["100.116.0.1"],
              "Online": false
            }
          }
        }"""
        parsed = nb.parse_tailscale_status_json(sample)
        self.assertTrue(parsed["available"])
        self.assertEqual(parsed["total_nodes"], 2)
        self.assertEqual(parsed["online_nodes"], 2)
        self.assertEqual(parsed["nodes"][0]["name"], "googlebook")
        self.assertEqual(parsed["nodes"][1]["name"], "ali-derp")
        self.assertEqual(parsed["nodes"][1]["mode"], "direct")


if __name__ == "__main__":
    unittest.main()
