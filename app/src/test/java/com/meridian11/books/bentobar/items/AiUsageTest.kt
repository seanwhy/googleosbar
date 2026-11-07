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
}
