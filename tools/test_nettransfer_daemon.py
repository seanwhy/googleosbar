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
        self.assertGreater(nb.compare_semver("0.4.0", "0.3.3"), 0)
        self.assertEqual(nb.compare_semver("v0.3.3", "0.3.3-beta"), 0)
        self.assertLess(nb.compare_semver("0.1.1", "0.3.3"), 0)

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
