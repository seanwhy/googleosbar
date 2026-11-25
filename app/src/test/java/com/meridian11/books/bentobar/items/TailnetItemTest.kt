package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetItemTest {

    @Test fun registeredInCatalogUnderTools() {
        val item = Items.of("tailnet")
        assertNotNull(item)
        assertEquals(ItemGroup.TOOLS, item?.group)
        assertEquals(null, item?.online)
    }

    @Test fun parsesVpsTailnetSummaryWithMagicDnsShortNames() {
        val payload = """
            {
              "generatedAt": "2026-11-25T01:00:00Z",
              "tailnetName": "tail.ts.net",
              "dashboardUrl": "https://vps.why.band",
              "totalNodes": 13,
              "onlineNodes": 11,
              "directRatio": 0.9,
              "primaryDerp": "ali-hz",
              "primaryDerpMs": 3.2,
              "alertCount": 1,
              "topAlert": "aws-us-vps DERP latency high",
              "nodes": [
                {
                  "id": "n1",
                  "name": "ali-derp",
                  "displayName": "ali-derp",
                  "ip": "100.79.26.61",
                  "magicDns": "ali-derp.tail.ts.net",
                  "os": "linux",
                  "online": true,
                  "rttMs": 14.5,
                  "mode": "direct",
                  "serviceAlert": false,
                  "sshUrl": "/tailnet?node=n1&ssh=1"
                }
              ]
            }
        """.trimIndent()
        val parsed = CloudClipboardClient.parseTailnetSummary(payload)
        assertEquals(13, parsed.totalNodes)
        assertEquals(11, parsed.onlineNodes)
        assertEquals("ali-derp", parsed.nodes.single().displayName)
        assertEquals("/tailnet?node=n1&ssh=1", parsed.nodes.single().sshUrl)
    }

    @Test fun parsesVmStatusAndActionRepliesWithHeaders() {
        val httpRaw = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n" +
            """{"updated_at":1795500000.0,"tailnet":{"available":true,"tailnet_name":"why","total_nodes":13,"online_nodes":11,"direct_ratio":0.85,"primary_derp":"ali-hz","nodes":[{"id":"1","name":"googlebook","display_name":"googlebook","ip":"100.92.145.3","os":"linux","online":true,"mode":"direct","relay":"ali-hz"}]},"agent":{"installed":true,"binary_exists":true,"active":true,"enabled":true,"state":"active","pid":3455,"version":"0.3.3","arch":"arm64","vps_url":"https://vps.why.band","registered":true,"device_id":"b28f7bb3","revoked":false,"has_backup":false,"recent_logs":["started"]}}"""
        val status = requireNotNull(TailnetVmBridge.parseStatus(httpRaw))
        assertEquals(11, status.tailnet.onlineNodes)
        assertEquals("0.3.3", status.agent.version)
        assertTrue(status.agent.active)

        val actionRaw = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n" +
            """{"ok":true,"message":"已是最新版本 v0.3.3","current_version":"0.3.3","latest_version":"0.3.3","has_update":false}"""
        val reply = requireNotNull(TailnetVmBridge.parseActionReply(actionRaw))
        assertTrue(reply.ok)
        assertFalse(reply.hasUpdate)
        assertEquals("0.3.3", reply.latestVersion)
    }

    @Test fun buildStateReflectsNormalAlertAndStoppedAgent() {
        val baseVm = VmTailnetStatusResponse(
            tailnet = VmTailnetFallback(available = true, totalNodes = 13, onlineNodes = 11),
            agent = VmNetTransferAgentStatus(installed = true, active = true, version = "0.3.3"),
        )
        val okSnap = TailnetUiSnapshot(vmReachable = true, vmStatus = baseVm)
        val okState = TailnetItem.buildState(okSnap)
        assertEquals("11/13", okState.text)
        assertEquals(Tone.NORMAL, okState.tone)
        assertFalse(okState.active)

        val stoppedSnap = okSnap.copy(
            vmStatus = baseVm.copy(
                agent = baseVm.agent.copy(installed = true, active = false, state = "inactive"),
            ),
        )
        val stoppedState = TailnetItem.buildState(stoppedSnap)
        assertEquals(Tone.ALERT, stoppedState.tone)
        assertTrue(stoppedState.active)

        val unreachableState = TailnetUiSnapshot(vmReachable = false, vpsSummary = null)
        assertEquals(Tone.WARN, TailnetItem.buildState(unreachableState).tone)
    }
}
