package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiUsageTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sampleJson = """
    {
      "updated_at": 1793957564,
      "antigravity": {
        "status": "ok",
        "claude_5h": {
          "used_percent": 15.0,
          "remaining_fraction": 0.85,
          "reset_in_sec": 7200,
          "reset_time": "2026-11-06T14:32:46Z",
          "display_name": "Five Hour Limit Remaining"
        },
        "claude_weekly": {
          "used_percent": 23.2,
          "remaining_fraction": 0.768,
          "reset_in_sec": 340279,
          "reset_time": "2026-11-10T08:04:06Z",
          "display_name": "Weekly Limit Remaining"
        },
        "gemini_5h": {
          "unlimited": true
        },
        "gemini_weekly": {
          "unlimited": true
        }
      },
      "codex": {
        "status": "ok",
        "plan_type": "plus",
        "window_5h": {
          "used_percent": 40.0,
          "reset_in_sec": 5400,
          "reset_at": 1793975201
        },
        "window_weekly": {
          "used_percent": 68.0,
          "reset_in_sec": 255442,
          "reset_at": 1794213009
        }
      },
      "grok": {
        "status": "ok",
        "model": "grok-4.7",
        "today_tokens": 12500
      }
    }
    """.trimIndent()

    @Test
    fun decodesSnapshotAccurately() {
        val snapshot = json.decodeFromString<AiUsageSnapshot>(sampleJson)
        assertEquals(1793957564L, snapshot.updatedAt)

        val ag = snapshot.antigravity!!
        assertEquals("ok", ag.status)
        assertNotNull(ag.claude5h)
        assertEquals(15.0f, ag.claude5h!!.usedPercent!!, 0.01f)
        assertEquals(0.85f, ag.claude5h!!.fraction, 0.01f)
        assertEquals(7200L, ag.claude5h!!.resetInSec!!)
        assertEquals("2h 0m", ag.claude5h!!.formattedCountdown())

        assertEquals(23.2f, ag.claudeWeekly!!.usedPercent!!, 0.01f)
        assertEquals("3d 22h", ag.claudeWeekly!!.formattedCountdown())

        val codex = snapshot.codex!!
        assertEquals("plus", codex.planType)
        assertEquals(40.0f, codex.window5h!!.usedPercent!!, 0.01f)
        assertEquals("1h 30m", codex.window5h!!.formattedCountdown())
        assertEquals(68.0f, codex.windowWeekly!!.usedPercent!!, 0.01f)

        val grok = snapshot.grok!!
        assertEquals("grok-4.7", grok.model)
        assertNull(grok.cycle)
        assertEquals(12500L, grok.todayTokens)
    }

    @Test
    fun handlesErrorAndMissingSnapshotsGracefully() {
        val errJson = """
        {
          "updated_at": 1793957564,
          "antigravity": { "status": "error", "error": "Connection timed out" },
          "codex": { "status": "unavailable", "reason": "auth.json not found" },
          "grok": { "status": "ok", "model": "grok-4.7", "today_tokens": 0 }
        }
        """.trimIndent()
        val snapshot = json.decodeFromString<AiUsageSnapshot>(errJson)
        assertEquals("error", snapshot.antigravity!!.status)
        assertNull(snapshot.antigravity!!.claude5h)
        assertNull(snapshot.antigravity!!.claudeWeekly)
        assertEquals("unavailable", snapshot.codex!!.status)
    }

    @Test
    fun formatsCountdownBoundaries() {
        assertEquals("Ready", formatCountdown(0))
        assertEquals("Ready", formatCountdown(-5))
        assertEquals("45m", formatCountdown(2700))
        assertEquals("1h 15m", formatCountdown(4500))
        assertEquals("2d 4h", formatCountdown(2 * 86400 + 4 * 3600))
        assertEquals("–", formatCountdown(null))
    }

    @Test
    fun formatsTokensDisplay() {
        assertEquals("0", formatTokens(0))
        assertEquals("850", formatTokens(850))
        assertEquals("1.2K", formatTokens(1234))
        assertEquals("366.5K", formatTokens(366470))
        assertEquals("2.5M", formatTokens(2500000))
    }

    @Test
    fun staleDetection() {
        val fresh = AiUsageSnapshot(updatedAt = 1000L)
        assertFalse(fresh.isStale(1200L)) // 200s < 900s
        assertTrue(fresh.isStale(2000L))  // 1000s > 900s
    }

    @Test
    fun candidateFileFallbackOnCorruptedFile() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bentobar_ai_test_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        try {
            val badFile = File(tmpDir, "bad.json")
            badFile.writeText("{ corrupted json ...")
            val goodFile = File(tmpDir, "good.json")
            goodFile.writeText(sampleJson)

            AiUsageReader.clearCacheForTesting()
            // When corrupted file is checked first, it falls back to valid file
            val readBad = AiUsageReader.read(1000L, badFile.absolutePath)
            assertNull(readBad)

            AiUsageReader.clearCacheForTesting()
            val readGood = AiUsageReader.read(1000L, goodFile.absolutePath)
            assertNotNull(readGood)
            assertEquals(1793957564L, readGood!!.updatedAt)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun decodesTimeoutAndLastOkHistory() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bentobar_ai_hist_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val file = File(tmpDir, "snapshot.json")
            file.writeText(
                """
                {
                  "updated_at": 2000,
                  "antigravity": {
                    "status": "timeout",
                    "error": "Read timed out after 15s",
                    "fetched_at": 2000,
                    "last_ok_at": 1850,
                    "last_ok": {
                      "status": "ok",
                      "model": "Gemini 4 Argon",
                      "gemini_weekly": { "remaining_percent": 84.0, "used_percent": 16.0, "reset_in_sec": 3600 }
                    }
                  },
                  "codex": {
                    "status": "error",
                    "error": "HTTP 502 Bad Gateway",
                    "fetched_at": 2000,
                    "last_ok_at": 1800,
                    "last_ok": {
                      "status": "ok",
                      "plan_type": "plus",
                      "window_5h": { "used_percent": 22.0, "remaining_percent": 78.0, "reset_in_sec": 18000 },
                      "window_weekly": { "used_percent": 40.0, "remaining_percent": 60.0, "reset_in_sec": 604800 }
                    }
                  },
                  "grok": {
                    "status": "ok",
                    "fetched_at": 2000,
                    "last_ok_at": 2000,
                    "cycle": { "used_percent": 12.5, "remaining_percent": 87.5, "reset_in_sec": 7200 },
                    "grok_build": null,
                    "grok_build_status": "timeout",
                    "grok_build_error": "ws handshake timed out",
                    "grok_build_ok_at": 1700,
                    "last_ok_grok_build": { "used_percent": 42.5, "remaining_percent": 57.5, "reset_in_sec": 7200 }
                  }
                }
                """.trimIndent()
            )
            AiUsageReader.clearCacheForTesting()
            val snap = AiUsageReader.read(2010_000L, file.absolutePath)
            assertNotNull(snap)
            snap!!
            assertEquals(2000L, snap.antigravity?.fetchedAt)
            assertEquals("timeout", snap.antigravity?.status)
            assertEquals(1850L, snap.antigravity?.lastOkAt)
            assertEquals(84.0f, snap.antigravity?.lastOk?.geminiWeekly?.remainingPercent ?: -1f, 0.01f)
            assertEquals("error", snap.codex?.status)
            assertEquals(1800L, snap.codex?.lastOkAt)
            assertEquals(22.0f, snap.codex?.lastOk?.window5h?.usedPercent ?: -1f, 0.01f)
            assertEquals("ok", snap.grok?.status)
            assertEquals("timeout", snap.grok?.grokBuildStatus)
            assertEquals("ws handshake timed out", snap.grok?.grokBuildError)
            assertEquals(1700L, snap.grok?.grokBuildOkAt)
            assertEquals(42.5f, snap.grok?.lastOkGrokBuild?.usedPercent ?: -1f, 0.01f)
        } finally {
            AiUsageReader.clearCacheForTesting()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun resolverImmediateTimeoutAndPreservesLastOk() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bentobar_ai_resolve_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val file = File(tmpDir, "snapshot.json")
            file.writeText(
                """
                {
                  "updated_at": 2000,
                  "antigravity": {
                    "status": "timeout",
                    "error": "Read timed out after 15s",
                    "fetched_at": 2000,
                    "last_ok_at": 1850,
                    "last_ok": {
                      "status": "ok",
                      "gemini_weekly": { "remaining_percent": 84.0, "used_percent": 16.0 }
                    }
                  },
                  "codex": {
                    "status": "error",
                    "error": "HTTP 502",
                    "fetched_at": 2000,
                    "last_ok_at": 1800,
                    "last_ok": {
                      "status": "ok",
                      "window_5h": { "used_percent": 18.0, "remaining_percent": 82.0 }
                    }
                  },
                  "grok": {
                    "status": "ok",
                    "fetched_at": 2000,
                    "last_ok_at": 2000,
                    "cycle": { "used_percent": 20.0, "remaining_percent": 80.0, "reset_in_sec": 7200 },
                    "grok_build": null,
                    "grok_build_status": "timeout",
                    "grok_build_error": "ws timeout",
                    "grok_build_ok_at": 1700,
                    "last_ok_grok_build": { "used_percent": 42.5, "remaining_percent": 57.5 }
                  }
                }
                """.trimIndent()
            )
            AiUsageReader.clearCacheForTesting()
            val nowMs = 2010_000L

            val ag = AiUsageReader.resolveAntigravity(nowMs, file.absolutePath)
            assertNull(ag.live)
            assertEquals(QuotaProblem.TIMEOUT, ag.problem)
            assertTrue(ag.isTimedOut)
            assertEquals("Read timed out after 15s", ag.errorMessage)
            assertEquals(1850L, ag.lastOkAtSec)
            assertEquals(84.0f, ag.lastOk?.geminiWeekly?.remainingPercent ?: -1f, 0.01f)

            val cx = AiUsageReader.resolveCodex(nowMs, file.absolutePath)
            assertNull(cx.live)
            assertEquals(QuotaProblem.ERROR, cx.problem)
            assertTrue(cx.isTimedOut)
            assertEquals("HTTP 502", cx.errorMessage)
            assertEquals(1800L, cx.lastOkAtSec)
            assertNotNull(cx.lastOk?.window5h)

            val gk = AiUsageReader.resolveGrok(nowMs, file.absolutePath)
            assertNotNull(gk.live)
            assertNull(gk.problem)
            assertFalse(gk.isTimedOut)

            val gb = AiUsageReader.resolveGrokBot(nowMs, file.absolutePath)
            assertNull(gb.live)
            assertEquals(QuotaProblem.TIMEOUT, gb.problem)
            assertTrue(gb.isTimedOut)
            assertEquals("ws timeout", gb.errorMessage)
            assertEquals(1700L, gb.lastOkAtSec)
            assertEquals(42.5f, gb.lastOk?.usedPercent ?: -1f, 0.01f)
        } finally {
            AiUsageReader.clearCacheForTesting()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun resolverStaleAfter300SecondsPreservesLiveAsLastOk() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bentobar_ai_stale_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val file = File(tmpDir, "snapshot.json")
            file.writeText(
                """
                {
                  "updated_at": 1000,
                  "antigravity": {
                    "status": "ok",
                    "fetched_at": 1000,
                    "last_ok_at": 1000,
                    "gemini_weekly": { "remaining_percent": 90.0, "used_percent": 10.0 }
                  },
                  "codex": {
                    "status": "ok",
                    "fetched_at": 1000,
                    "last_ok_at": 1000,
                    "window_5h": { "used_percent": 12.0, "remaining_percent": 88.0 }
                  },
                  "grok": {
                    "status": "ok",
                    "fetched_at": 1000,
                    "last_ok_at": 1000,
                    "cycle": { "used_percent": 10.0, "remaining_percent": 90.0, "reset_in_sec": 7200 },
                    "grok_build_status": "ok",
                    "grok_build": { "used_percent": 33.0, "remaining_percent": 67.0 }
                  }
                }
                """.trimIndent()
            )
            AiUsageReader.clearCacheForTesting()
            val freshMs = 1299_000L // 299s <= 300s
            assertFalse(AiUsageReader.resolveAntigravity(freshMs, file.absolutePath).isTimedOut)
            assertFalse(AiUsageReader.resolveCodex(freshMs, file.absolutePath).isTimedOut)
            assertFalse(AiUsageReader.resolveGrok(freshMs, file.absolutePath).isTimedOut)
            assertFalse(AiUsageReader.resolveGrokBot(freshMs, file.absolutePath).isTimedOut)

            val staleMs = 1301_000L // 301s > 300s
            AiUsageReader.clearCacheForTesting()
            val agStale = AiUsageReader.resolveAntigravity(staleMs, file.absolutePath)
            assertNull(agStale.live)
            assertEquals(QuotaProblem.STALE, agStale.problem)
            assertTrue(agStale.isTimedOut)
            assertEquals(1000L, agStale.lastOkAtSec)
            assertNotNull(agStale.lastOk)

            val gbStale = AiUsageReader.resolveGrokBot(staleMs, file.absolutePath)
            assertNull(gbStale.live)
            assertEquals(QuotaProblem.STALE, gbStale.problem)
            assertTrue(gbStale.isTimedOut)
            assertEquals(33.0f, gbStale.lastOk?.usedPercent ?: -1f, 0.01f)
            assertEquals(1000L, gbStale.lastOkAtSec)
        } finally {
            AiUsageReader.clearCacheForTesting()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun resolverUnreachableUnconfiguredAndNestedGrokHistory() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bentobar_ai_mem_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val file = File(tmpDir, "snapshot.json")
            file.writeText(
                """
                {
                  "updated_at": 1000,
                  "antigravity": {
                    "status": "ok",
                    "fetched_at": 1000,
                    "last_ok_at": 1000,
                    "gemini_weekly": { "remaining_percent": 76.0, "used_percent": 24.0 }
                  },
                  "codex": { "status": "unconfigured" },
                  "grok": {
                    "status": "timeout",
                    "error": "DNS fail",
                    "fetched_at": 1000,
                    "last_ok_at": 900,
                    "last_ok": {
                      "status": "ok",
                      "cycle": { "used_percent": 10.0, "remaining_percent": 90.0 },
                      "grok_build": { "used_percent": 12.0, "remaining_percent": 88.0 }
                    },
                    "grok_build_ok_at": 910,
                    "last_ok_grok_build": { "used_percent": 15.0, "remaining_percent": 85.0 }
                  }
                }
                """.trimIndent()
            )
            AiUsageReader.clearCacheForTesting()

            val okAg = AiUsageReader.resolveAntigravity(1001_000L, file.absolutePath)
            assertNotNull(okAg.live)

            val unconfCx = AiUsageReader.resolveCodex(1001_000L, file.absolutePath)
            assertNull(unconfCx.live)
            assertEquals(QuotaProblem.UNCONFIGURED, unconfCx.problem)
            assertFalse(unconfCx.isTimedOut)

            val timedOutBot = AiUsageReader.resolveGrokBot(1001_000L, file.absolutePath)
            assertNull(timedOutBot.live)
            assertEquals(QuotaProblem.TIMEOUT, timedOutBot.problem)
            assertTrue(timedOutBot.isTimedOut)
            assertNotNull(timedOutBot.lastOk)

            // Delete snapshot: missing customPath resolves to UNREACHABLE
            file.delete()
            val missingFileAg = AiUsageReader.resolveAntigravity(1006_000L, file.absolutePath)
            assertNull(missingFileAg.live)
            assertEquals(QuotaProblem.UNREACHABLE, missingFileAg.problem)
            assertTrue(missingFileAg.isTimedOut)

            // Pure resolver preserves in-memory lastOk when snapshot becomes unreachable
            val unreachableMemAg = AiUsageResolver.resolveAntigravity(
                snapshot = null,
                nowSec = 1006L,
                memLastOk = okAg.live,
                memLastOkAtSec = 1000L,
            )
            assertNull(unreachableMemAg.live)
            assertEquals(QuotaProblem.UNREACHABLE, unreachableMemAg.problem)
            assertTrue(unreachableMemAg.isTimedOut)
            assertEquals(1000L, unreachableMemAg.lastOkAtSec)
            assertEquals(76.0f, unreachableMemAg.lastOk?.geminiWeekly?.remainingPercent ?: -1f, 0.01f)
        } finally {
            AiUsageReader.clearCacheForTesting()
            tmpDir.deleteRecursively()
        }
    }
}
