package com.meridian11.books.bentobar.items

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class CloudClipboardTest {
    companion object {
        // 32-byte seed 1..32 followed by its Ed25519 public key (matches NetTransfer protocol test vector)
        private const val FIXTURE_KEY =
            "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyB5tVYuj+ZU+UB4sRLoqYunkB+FOuaVvtfg45ELrQSWZA=="
    }

    @Test
    fun ed25519PublicKeyDerivedFromSeedMatchesFixture() {
        val fullKey = Base64.getDecoder().decode(FIXTURE_KEY)
        assertEquals(64, fullKey.size)
        val seed = fullKey.copyOfRange(0, 32)
        val expectedPub = fullKey.copyOfRange(32, 64)
        val derivedPub = Ed25519.publicKeyFromSeed(seed)
        assertArrayEquals(expectedPub, derivedPub)
    }

    @Test
    fun createBodyMatchesGoDigest() {
        val body = CloudClipboardProtocol.createBody("你好", "pc")
        val json = String(body, StandardCharsets.UTF_8)
        assertEquals("""{"apiVersion":1,"text":"你好","hostname":"pc"}""", json)
        val digest = CloudClipboardProtocol.sha256Base64(body)
        assertEquals("4bUku16HrAmGhBc1QJkHKxzZc0+jKKeFPzcnZ3zMQVI=", digest)
    }

    @Test
    fun buildAgentHeadersMatchesGoSignatureVector() {
        val identity = NetTransferIdentity(
            vpsUrl = "https://panel.example.com/",
            deviceId = "device-1",
            privateKey = FIXTURE_KEY,
        )
        val body = CloudClipboardProtocol.createBody("你好", "pc")
        val headers = CloudClipboardProtocol.buildAgentHeaders(
            identity = identity,
            method = "POST",
            bodyBytes = body,
            unixSeconds = 1_700_000_000L,
            nonce = "nonce",
        )
        assertEquals("device-1", headers["X-NetTransfer-Device"])
        assertEquals("1", headers["X-NetTransfer-Agent-API"])
        assertEquals("1700000000", headers["X-NetTransfer-Timestamp"])
        assertEquals("nonce", headers["X-NetTransfer-Nonce"])
        assertEquals("4bUku16HrAmGhBc1QJkHKxzZc0+jKKeFPzcnZ3zMQVI=", headers["X-NetTransfer-Body-Digest"])
        assertEquals(
            "xuDLMCmTv9QpdjU3Du+igpP92Pcrk833EL+APlxAxplcAXA9aqBtVCblV3PJmD911rSgz9sM5YvN7rvYCHziBQ==",
            headers["X-NetTransfer-Signature"],
        )
    }

    @Test
    fun createBodyRejectsEmptyAndOversizedInput() {
        try {
            CloudClipboardProtocol.createBody("   ")
            fail("Expected IllegalArgumentException for blank text")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val oversized = "a".repeat(CloudClipboardProtocol.MAX_TEXT_BYTES + 1)
        try {
            CloudClipboardProtocol.createBody(oversized)
            fail("Expected IllegalArgumentException for oversized text")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun normalizeFileNameStripsPathsAndDangerousCharacters() {
        assertEquals("clip.png", CloudClipboardProtocol.normalizeFileName("""C:\temp\clip.png"""))
        assertEquals("a_b.txt", CloudClipboardProtocol.normalizeFileName("a_b:?.txt"))
        assertEquals("clipboard.bin", CloudClipboardProtocol.normalizeFileName("   "))
    }

    @Test
    fun validateVpsUrlNormalizesAndRejectsInvalidSchemes() {
        assertEquals("https://vps.why.band", CloudClipboardProtocol.validateVpsUrl(" https://vps.why.band/ "))
        assertEquals("http://127.0.0.1:8080", CloudClipboardProtocol.validateVpsUrl("http://127.0.0.1:8080/"))

        for (bad in listOf("", "   ", "ftp://vps.why.band", "not-a-url")) {
            try {
                CloudClipboardProtocol.validateVpsUrl(bad)
                fail("Expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun panelHandshakeOutputStreamSplitsFirstWriteOnly() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val recordingStream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                writes.add(b.copyOfRange(off, off + len))
                super.write(b, off, len)
            }
        }
        val handshakeOut = PanelHandshakeOutputStream(recordingStream, splitDelayMs = 0L)
        val firstPayload = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x20)
        val secondPayload = byteArrayOf(0x14, 0x03, 0x03)

        handshakeOut.write(firstPayload, 0, firstPayload.size)
        handshakeOut.write(secondPayload, 0, secondPayload.size)

        assertEquals(3, writes.size)
        assertArrayEquals(byteArrayOf(0x16), writes[0])
        assertArrayEquals(byteArrayOf(0x03, 0x01, 0x00, 0x20), writes[1])
        assertArrayEquals(secondPayload, writes[2])
        assertArrayEquals(firstPayload + secondPayload, recordingStream.toByteArray())
    }

    @Test
    fun shortcutParseAndFormatRoundTrips() {
        val defaultSc = CloudClipboardShortcut.parse(null)
        assertEquals(CloudClipboardShortcut.DEFAULT, defaultSc)
        assertEquals("Ctrl + Alt + C", defaultSc.format())
        assertEquals("Ctrl+Alt+C", defaultSc.serialize())

        val shiftSc = CloudClipboardShortcut.parse("Ctrl + Shift + V")
        assertTrue(shiftSc.ctrl)
        assertFalse(shiftSc.alt)
        assertTrue(shiftSc.shift)
        assertFalse(shiftSc.meta)
        assertEquals(KeyEvent.KEYCODE_V, shiftSc.keyCode)
        assertEquals("Ctrl + Shift + V", shiftSc.format())

        val fnSc = CloudClipboardShortcut.parse("Alt+F8")
        assertFalse(fnSc.ctrl)
        assertTrue(fnSc.alt)
        assertEquals(KeyEvent.KEYCODE_F8, fnSc.keyCode)
        assertEquals("Alt + F8", fnSc.format())

        // Without any modifier, falls back to DEFAULT
        val noMod = CloudClipboardShortcut.parse("C")
        assertEquals(CloudClipboardShortcut.DEFAULT, noMod)
    }

    @Test
    fun mergeEntriesDeduplicatesByIdAndContent() {
        val remote = listOf(
            CloudClipboardEntry(id = "1", text = "hello", deviceName = "PC"),
            CloudClipboardEntry(id = "2", text = "world", deviceName = "PC"),
        )
        val local = listOf(
            CloudClipboardEntry(id = "local-99", text = "hello", deviceName = "Googlebook"),
            CloudClipboardEntry(id = "1", text = "hello", deviceName = "PC"),
            CloudClipboardEntry(id = "local-100", text = "local-only", deviceName = "Googlebook"),
        )
        val merged = CloudClipboardStore.mergeEntries(remote, local)
        assertEquals(3, merged.size)
        assertEquals(listOf("hello", "world", "local-only"), merged.map { it.text })
        assertEquals("1", merged[0].id)
    }

    @Test
    fun clientEnrollPushAndFetchFallbackAgainstLocalServer() {
        val requests = CopyOnWriteArrayList<String>()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val serverThread = Thread {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine() ?: return@use
                        requests.add(requestLine)
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("content-length:")) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyChars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(bodyChars, read, contentLength - read)
                            if (n == -1) break
                            read += n
                        }

                        val (status, respJson) = when {
                            requestLine.startsWith("POST /api/agent/v1/enroll ") ->
                                "201 Created" to """{"deviceId":"dev-test-1","createdAt":"2026-11-09T10:00:00Z"}"""
                            requestLine.startsWith("POST /api/agent/v1/clipboard ") ->
                                "201 Created" to """{"id":"clip-101","kind":"text","text":"pushed text","deviceId":"dev-test-1","deviceName":"Book","createdAt":"2026-11-09T10:01:00Z"}"""
                            requestLine.startsWith("GET /api/agent/v1/clipboard ") ->
                                "405 Method Not Allowed" to """{"error":"method not allowed"}"""
                            requestLine.startsWith("GET /api/clipboard ") ->
                                "200 OK" to """{"items":[{"id":"clip-101","kind":"text","text":"pushed text","deviceName":"Book"},{"id":"clip-102","kind":"text","text":"from server","deviceName":"VPS"}]}"""
                            else ->
                                "404 Not Found" to """{"error":"not found"}"""
                        }
                        val bodyBytes = respJson.toByteArray(StandardCharsets.UTF_8)
                        val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                        val out = sock.getOutputStream()
                        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                        out.write(bodyBytes)
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val baseUrl = "http://127.0.0.1:$port"
            val enrolled = CloudClipboardClient.enroll(
                vpsUrl = baseUrl,
                displayName = "Book",
                registrationCode = "CODE-123",
                sessionCookie = "sess-abc",
            )
            assertTrue(enrolled.isRegistered)
            assertEquals("dev-test-1", enrolled.deviceId)
            assertNotNull(enrolled.privateKey)

            val pushed = CloudClipboardClient.pushText(enrolled, "pushed text", "Book")
            assertEquals("clip-101", pushed.id)
            assertEquals("pushed text", pushed.text)

            val fetched = CloudClipboardClient.fetchItems(enrolled)
            assertEquals(2, fetched.size)
            assertEquals("pushed text", fetched[0].text)
            assertEquals("from server", fetched[1].text)

            assertTrue(requests.any { it.startsWith("POST /api/agent/v1/enroll ") })
            assertTrue(requests.any { it.startsWith("POST /api/agent/v1/clipboard ") })
            assertTrue(requests.any { it.startsWith("GET /api/agent/v1/clipboard ") })
            assertTrue(requests.any { it.startsWith("GET /api/clipboard ") })
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun clientFetchAndDeleteDirectlyViaSignedAgentEndpoint() {
        val requests = CopyOnWriteArrayList<String>()
        val capturedHeaders = CopyOnWriteArrayList<Map<String, String>>()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val serverThread = Thread {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine() ?: return@use
                        requests.add(requestLine)
                        val hdrs = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) {
                                hdrs[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                            }
                        }
                        capturedHeaders.add(hdrs)

                        val (status, respJson) = when {
                            requestLine.startsWith("GET /api/agent/v1/clipboard ") ->
                                "200 OK" to """{"items":[{"id":"clip-201","kind":"text","text":"direct agent pull","deviceId":"dev-1","deviceName":"PC","createdAt":"2026-11-09T11:00:00Z"}]}"""
                            requestLine.startsWith("DELETE /api/agent/v1/clipboard/clip-201 ") ->
                                "204 No Content" to ""
                            else ->
                                "404 Not Found" to """{"error":"not found"}"""
                        }
                        val bodyBytes = respJson.toByteArray(StandardCharsets.UTF_8)
                        val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                        val out = sock.getOutputStream()
                        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val identity = NetTransferIdentity(
                vpsUrl = "http://127.0.0.1:$port",
                displayName = "Googlebook",
                deviceId = "device-1",
                privateKey = FIXTURE_KEY,
                sessionCookie = "",
            )

            val items = CloudClipboardClient.fetchItems(identity)
            assertEquals(1, items.size)
            assertEquals("clip-201", items[0].id)
            assertEquals("direct agent pull", items[0].text)

            CloudClipboardClient.deleteItem(identity, "clip-201")

            assertEquals(2, requests.size)
            assertTrue(requests[0].startsWith("GET /api/agent/v1/clipboard "))
            assertTrue(requests[1].startsWith("DELETE /api/agent/v1/clipboard/clip-201 "))
            for (hdrs in capturedHeaders) {
                assertEquals("device-1", hdrs["x-nettransfer-device"])
                assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", hdrs["x-nettransfer-body-digest"])
                assertNotNull(hdrs["x-nettransfer-signature"])
                assertFalse(hdrs.containsKey("cookie"))
            }

            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun normalizeFileNameMatchesRobbonAndPanelRules() {
        assertEquals("report.pdf", CloudClipboardProtocol.normalizeFileNameOrNull("""C:\temp\..\report.pdf"""))
        assertEquals("a b.txt", CloudClipboardProtocol.normalizeFileNameOrNull("  a:*?\"<>| b.txt... "))
        val longRunes = "文".repeat(140) + ".txt"
        val normalizedLong = CloudClipboardProtocol.normalizeFileNameOrNull(longRunes)
        assertNotNull(normalizedLong)
        assertEquals(120, normalizedLong!!.codePointCount(0, normalizedLong.length))
        assertEquals(null, CloudClipboardProtocol.normalizeFileNameOrNull("..."))
        assertEquals(null, CloudClipboardProtocol.normalizeFileNameOrNull("   "))
        assertEquals(null, CloudClipboardProtocol.normalizeFileNameOrNull("\uD800"))
    }

    @Test
    fun buildFileHeadersMatchesRobbonAndPanelTestVector() {
        val identity = NetTransferIdentity(
            vpsUrl = "https://panel.example.com/",
            deviceId = "device-1",
            privateKey = FIXTURE_KEY,
        )
        val headers = CloudClipboardProtocol.buildFileHeaders(
            identity = identity,
            fileName = """C:\temp\notes.txt""",
            size = 9L,
            digestBase64 = "p/8XAtF3oLT04toToSsahXUlao1AHChnG4OzGAwifYg=",
            unixSeconds = 1_700_000_000L,
            nonce = "nonce",
        )
        assertEquals("device-1", headers["X-NetTransfer-Device"])
        assertEquals("1", headers["X-NetTransfer-Agent-API"])
        assertEquals("1700000000", headers["X-NetTransfer-Timestamp"])
        assertEquals("nonce", headers["X-NetTransfer-Nonce"])
        assertEquals("p/8XAtF3oLT04toToSsahXUlao1AHChnG4OzGAwifYg=", headers["X-NetTransfer-Body-Digest"])
        assertEquals("bm90ZXMudHh0", headers["X-NetTransfer-File-Name"])
        assertEquals("9", headers["X-NetTransfer-File-Size"])
        assertEquals(
            "tuGzipxCJnmrDt6uvQWN2qN86oUxPA62WaQlhS4LXembwU8phWtfNz9hshcdwWioqJ/4ggzXdGfz7G+9ug4LCA==",
            headers["X-NetTransfer-Signature"],
        )
    }

    @Test
    fun hashFileMatchesRobbonDigestAndDetectsSizeChange() {
        val tempFile = java.io.File.createTempFile("robbon-clip-", ".txt")
        try {
            tempFile.writeBytes("file-body".toByteArray(StandardCharsets.UTF_8))
            val digest = CloudClipboardProtocol.hashFile(tempFile, 9L)
            assertEquals("p/8XAtF3oLT04toToSsahXUlao1AHChnG4OzGAwifYg=", digest)

            try {
                CloudClipboardProtocol.hashFile(tempFile, 8L)
                fail("Expected IOException when file size does not match expected size")
            } catch (e: java.io.IOException) {
                assertEquals("文件大小在读取时变了。", e.message)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun cloudClipboardFilesPlanMatchesRobbonRules() {
        val root = java.nio.file.Files.createTempDirectory("bentobar-clip-plan-").toFile()
        try {
            val good = java.io.File(root, "good.txt").apply { writeText("hello") }
            val empty = java.io.File(root, "empty.txt").apply { writeText("") }
            val subDir = java.io.File(root, "folder").apply { mkdirs() }
            val missing = java.io.File(root, "missing.txt")

            val result = CloudClipboardFiles.planPaths(
                listOf(
                    good.absolutePath,
                    good.absolutePath, // duplicate
                    empty.absolutePath,
                    subDir.absolutePath,
                    missing.absolutePath,
                )
            )
            assertEquals(1, result.accepted.size)
            assertEquals("good.txt", result.accepted[0].name)
            assertEquals(5L, result.accepted[0].size)
            assertEquals(
                listOf(
                    "empty.txt：文件是空的",
                    "folder：是文件夹",
                    "missing.txt：找不到文件",
                ),
                result.skipped,
            )
            assertEquals(
                "跳过 empty.txt：文件是空的；folder：是文件夹；missing.txt：找不到文件",
                result.skipSummary(),
            )

            // Quoted and NUL-wrapped paths
            val quotedResult = CloudClipboardFiles.planPaths(
                listOf("  \"${good.absolutePath}\"\u0000 ")
            )
            assertEquals(1, quotedResult.accepted.size)
            assertEquals(0, quotedResult.skipped.size)

            // Upload result messages matching robbon
            assertEquals(
                "已送到 VPS 面板。",
                CloudClipboardFiles.uploadResultMessage(1, 1, canceled = false, error = null, skips = ""),
            )
            assertEquals(
                "已把 3 个文件送到 VPS 面板。",
                CloudClipboardFiles.uploadResultMessage(3, 3, canceled = false, error = null, skips = ""),
            )
            assertEquals(
                "已取消，已上传 2 个。",
                CloudClipboardFiles.uploadResultMessage(2, 3, canceled = true, error = null, skips = ""),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clientPushFileStreamsBinaryBodyWithSignedFileHeaders() {
        val capturedRequestLines = CopyOnWriteArrayList<String>()
        val capturedHeaders = CopyOnWriteArrayList<Map<String, String>>()
        val capturedBodies = CopyOnWriteArrayList<ByteArray>()
        val progressEvents = CopyOnWriteArrayList<Long>()

        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val serverThread = Thread {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                client.use { sock ->
                    val input = sock.getInputStream()
                    fun readLine(): String? {
                        val buf = ByteArrayOutputStream()
                        var saw = false
                        while (true) {
                            val b = input.read()
                            if (b == -1) return if (saw) buf.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r') else null
                            saw = true
                            if (b == '\n'.code) break
                            buf.write(b)
                        }
                        return buf.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r')
                    }
                    val reqLine = readLine() ?: return@use
                    capturedRequestLines.add(reqLine)
                    val hdrs = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            hdrs[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    capturedHeaders.add(hdrs)
                    val len = hdrs["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val n = input.read(body, off, len - off)
                        if (n <= 0) break
                        off += n
                    }
                    capturedBodies.add(body)

                    val respJson = """{"id":"file-clip-1","kind":"file","fileName":"notes.txt","size":9,"url":"/api/clipboard/files/f1","fileId":"f1","deviceId":"device-1","deviceName":"Googlebook"}"""
                    val respBytes = respJson.toByteArray(StandardCharsets.UTF_8)
                    val respHeader = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                    val out = sock.getOutputStream()
                    out.write(respHeader.toByteArray(StandardCharsets.ISO_8859_1))
                    out.write(respBytes)
                    out.flush()
                }
            }.apply { isDaemon = true; start() }

            val tempFile = java.io.File.createTempFile("upload-", ".txt")
            try {
                tempFile.writeBytes("file-body".toByteArray(StandardCharsets.UTF_8))
                val identity = NetTransferIdentity(
                    vpsUrl = "http://127.0.0.1:$port",
                    displayName = "Googlebook",
                    deviceId = "device-1",
                    privateKey = FIXTURE_KEY,
                )
                val entry = CloudClipboardClient.pushFile(
                    identity = identity,
                    fileName = "notes.txt",
                    size = 9L,
                    streamProvider = { tempFile.inputStream() },
                    onProgress = { progressEvents.add(it) },
                )
                assertEquals("file-clip-1", entry.id)
                assertTrue(entry.isFile)
                assertEquals("notes.txt", entry.fileName)
                assertEquals(9L, entry.size)
                assertEquals("[文件] notes.txt (9 B)", entry.preview())
                assertTrue(progressEvents.contains(0L))
                assertEquals(9L, progressEvents.last())

                assertEquals(1, capturedRequestLines.size)
                assertEquals("POST /api/agent/v1/clipboard/file HTTP/1.1", capturedRequestLines[0])
                val hdrs = capturedHeaders[0]
                assertEquals("application/octet-stream", hdrs["content-type"])
                assertEquals("bm90ZXMudHh0", hdrs["x-nettransfer-file-name"])
                assertEquals("9", hdrs["x-nettransfer-file-size"])
                assertEquals("p/8XAtF3oLT04toToSsahXUlao1AHChnG4OzGAwifYg=", hdrs["x-nettransfer-body-digest"])
                assertArrayEquals("file-body".toByteArray(StandardCharsets.UTF_8), capturedBodies[0])
            } finally {
                tempFile.delete()
                server.close()
                serverThread.join(1000)
            }
        }
    }

    @Test
    fun cloudClipboardItemStateIsIconOnlyWithoutTrailingBarText() {
        assertTrue(CloudClipboardItem.iconOnly)
        CloudClipboardItem.entriesState.value = listOf(
            CloudClipboardEntry(id = "1", kind = "text", text = "should not appear on taskbar"),
        )
        val cfg = com.meridian11.books.bentobar.data.ItemConfig(id = "cc1", type = "cloud_clipboard")
        val state = CloudClipboardItem.state(cfg)
        assertEquals("brand:cloud_clipboard", state.icon)
        assertEquals(null, state.text)
        assertEquals(0, state.textLimit)
        assertEquals(null, state.widthKey)
    }

    @Test
    fun extractFileSourcesFromTextAndFormatPreview() {
        val tempFile = java.io.File.createTempFile("input-box-file-", ".txt")
        try {
            tempFile.writeText("copied file content")
            val fromPath = CloudClipboardItem.extractFileSourcesFromText(tempFile.absolutePath)
            assertEquals(1, fromPath.size)
            assertEquals(tempFile.name, fromPath[0].displayName)
            assertEquals(19L, fromPath[0].size)
            assertEquals("[文件] ${tempFile.name} (19 B)", CloudClipboardItem.formatFilesPreview(fromPath))

            val fromFileUri = CloudClipboardItem.extractFileSourcesFromText("file://${tempFile.absolutePath}")
            assertEquals(1, fromFileUri.size)
            assertEquals(tempFile.name, fromFileUri[0].displayName)

            val fromGnomeCopied = CloudClipboardItem.extractFileSourcesFromText("copy\nfile://${tempFile.absolutePath}")
            assertEquals(1, fromGnomeCopied.size)
            assertEquals(tempFile.name, fromGnomeCopied[0].displayName)

            val plainText = CloudClipboardItem.extractFileSourcesFromText("just normal text /api/v1/status")
            assertTrue(plainText.isEmpty())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun formatBytesAndUploadResultMessageMatchRobbonExactBehavior() {
        assertEquals("0 B", CloudClipboardFiles.formatBytes(0L))
        assertEquals("512 B", CloudClipboardFiles.formatBytes(512L))
        assertEquals("1.5 KB", CloudClipboardFiles.formatBytes(1536L))
        assertEquals("12 KB", CloudClipboardFiles.formatBytes(12L * 1024L))
        assertEquals("2.5 MB", CloudClipboardFiles.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("15 MB", CloudClipboardFiles.formatBytes(15L * 1024L * 1024L))
        assertEquals("1.5 GB", CloudClipboardFiles.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))

        // When canceled or error is set, skips should NOT be appended (matching robbon CloudClipboardFiles.UploadResultMessage)
        assertEquals(
            "已取消。",
            CloudClipboardFiles.uploadResultMessage(
                done = 0,
                total = 2,
                canceled = true,
                error = null,
                skips = "跳过 empty.txt：文件是空的",
            ),
        )
        assertEquals(
            "notes.txt：HTTP 500",
            CloudClipboardFiles.uploadResultMessage(
                done = 0,
                total = 2,
                canceled = false,
                error = "notes.txt：HTTP 500",
                skips = "跳过 empty.txt：文件是空的",
            ),
        )
        assertEquals(
            "notes.txt：HTTP 500 已上传 1 个。",
            CloudClipboardFiles.uploadResultMessage(
                done = 1,
                total = 2,
                canceled = false,
                error = "notes.txt：HTTP 500",
                skips = "跳过 empty.txt：文件是空的",
            ),
        )
        assertEquals(
            "已送到 VPS 面板。 跳过 empty.txt：文件是空的",
            CloudClipboardFiles.uploadResultMessage(
                done = 1,
                total = 1,
                canceled = false,
                error = null,
                skips = "跳过 empty.txt：文件是空的",
            ),
        )
    }

    @Test
    fun cleanPathAndEntryIsFileAndMultiFileProgressMatchRobbonAndPanel() {
        assertEquals("/storage/emulated/10/My Notes.txt", CloudClipboardFiles.cleanPath("file:///storage/emulated/10/My%20Notes.txt"))
        assertEquals("/storage/emulated/10/My Notes.txt", CloudClipboardFiles.cleanPath("file:///storage/emulated/10/My Notes.txt"))

        // VPSPanel sets Text = fileURL alongside FileName / FileID / URL on file entries
        val panelFileEntry = CloudClipboardEntry(
            id = "clip-f1",
            kind = "",
            text = "/api/clipboard/files/f1",
            fileName = "archive.zip",
            fileId = "f1",
            url = "/api/clipboard/files/f1",
            size = 2048L,
        )
        assertTrue(panelFileEntry.isFile)
        assertEquals("[文件] archive.zip (2.0 KB)", panelFileEntry.preview())

        val progress = CloudClipboardItem.UploadProgressState(
            fileIndex = 1,
            fileCount = 2,
            fileName = "part2.bin",
            fileSent = 50L,
            fileSize = 100L,
            sentOverall = 150L,
            totalBytes = 200L,
            reading = false,
            canceling = false,
        )
        assertEquals(75, progress.percent)
        assertEquals(0.75f, progress.fraction, 0.001f)
    }

    @Test
    fun remoteKeyboardKeyMapperMapsLettersModifiersAndNavigationKeysToWindowsVk() {
        val keyA = RemoteKeyboardKeyMapper.mapAndroidKey(KeyEvent.KEYCODE_A)
        assertNotNull(keyA)
        assertEquals(0x41, keyA!!.vkCode)
        assertEquals(0x1E, keyA.scanCode)
        assertFalse(keyA.extended)
        assertEquals("A", keyA.keyName)

        val keyF5 = RemoteKeyboardKeyMapper.mapAndroidKey(KeyEvent.KEYCODE_F5)
        assertNotNull(keyF5)
        assertEquals(0x74, keyF5!!.vkCode)
        assertEquals("F5", keyF5.keyName)

        val rCtrl = RemoteKeyboardKeyMapper.mapAndroidKey(KeyEvent.KEYCODE_CTRL_RIGHT)
        assertNotNull(rCtrl)
        assertEquals(0xA3, rCtrl!!.vkCode)
        assertTrue(rCtrl.extended)
        assertEquals("RCtrl", rCtrl.keyName)

        val leftArrow = RemoteKeyboardKeyMapper.mapAndroidKey(KeyEvent.KEYCODE_DPAD_LEFT)
        assertNotNull(leftArrow)
        assertEquals(0x25, leftArrow!!.vkCode)
        assertTrue(leftArrow.extended)

        val releaseAll = RemoteKeyboardKeyMapper.createReleaseAllEvent(42L, 1000L)
        assertEquals("release_all", releaseAll.action)
        assertEquals(42L, releaseAll.seq)
    }

    @Test
    fun remoteKeyboardDeviceParsingAndDirectEndpoints() {
        val rawJson = """
            {
              "devices": [
                {
                  "id": "self-android",
                  "name": "HP Googlebook 14",
                  "platform": "android",
                  "os": "Android",
                  "online": true
                },
                {
                  "id": "win-pc-1",
                  "displayName": "Workstation",
                  "platform": "windows",
                  "os": "Windows 10 Pro",
                  "sourceIp": "198.51.100.10",
                  "online": true,
                  "directPort": 19878,
                  "keyboardActive": true,
                  "inventory": {
                    "hostname": "WORKSTATION",
                    "networks": [
                      {"name": "eth0", "addresses": ["192.168.1.50/24", "127.0.0.1/8", "fe80::1"]}
                    ]
                  }
                },
                {
                  "id": "revoked-pc",
                  "displayName": "Old PC",
                  "platform": "windows",
                  "revoked": true
                }
              ]
            }
        """.trimIndent()

        val parsed = RemoteKeyboardClient.parseDevicesPayload(rawJson, selfDeviceId = "self-android")
        assertEquals(1, parsed.size)
        val win = parsed[0]
        assertEquals("win-pc-1", win.id)
        assertEquals("Workstation", win.displayTitle)
        assertEquals("Windows", win.platformLabel)
        assertTrue(win.isDesktopTarget)
        assertEquals(listOf("192.168.1.50"), win.lanIpv4Addresses)
        assertEquals(
            listOf(
                DirectTargetEndpoint("192.168.1.50", 19878),
                DirectTargetEndpoint("198.51.100.10", 19878),
            ),
            win.candidateDirectEndpoints,
        )
        assertEquals(
            DirectTargetEndpoint("127.0.0.1", 19878),
            RemoteKeyboardProtocol.parseDirectEndpoint("127.0.0.1:19878"),
        )
        assertEquals(
            DirectTargetEndpoint("10.0.0.8", 19878),
            RemoteKeyboardProtocol.parseDirectEndpoint("10.0.0.8"),
        )
    }

    @Test
    fun remoteKeyboardRelayAndDirectTcpAgainstLocalServer() {
        val requests = CopyOnWriteArrayList<String>()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val serverThread = Thread {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine() ?: return@use
                        requests.add(requestLine)
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("content-length:")) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyChars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(bodyChars, read, contentLength - read)
                            if (n == -1) break
                            read += n
                        }
                        val (status, respJson) = when {
                            requestLine.startsWith("GET /api/agent/v1/devices ") ->
                                "200 OK" to """{"devices":[{"id":"win-1","name":"WinPC","platform":"windows","online":true,"keyboardActive":true}]}"""
                            requestLine.startsWith("POST /api/agent/v1/keyboard/send ") ->
                                "200 OK" to """{"accepted":1,"targetOnline":true,"targetReceiverReady":true,"targetDirectPort":19878}"""
                            requestLine.startsWith("GET /api/keyboard/ping ") ->
                                "200 OK" to """{"ok":true}"""
                            requestLine.startsWith("POST /api/keyboard/direct ") ->
                                "200 OK" to """{"accepted":1}"""
                            else ->
                                "404 Not Found" to """{"error":"not found"}"""
                        }
                        val bodyBytes = respJson.toByteArray(StandardCharsets.UTF_8)
                        val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                        val out = sock.getOutputStream()
                        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                        out.write(bodyBytes)
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val identity = NetTransferIdentity(
                vpsUrl = "http://127.0.0.1:$port",
                displayName = "Googlebook",
                deviceId = "self-book",
                privateKey = FIXTURE_KEY,
            )

            val devices = RemoteKeyboardClient.fetchDevices(identity)
            assertEquals(1, devices.size)
            assertEquals("win-1", devices[0].id)

            val sendReq = RemoteKeyboardSendRequest(
                targetDeviceId = "win-1",
                sourceDeviceId = "self-book",
                sourceName = "Googlebook",
                events = listOf(RemoteKeyboardEvent(seq = 1L, action = "down", vkCode = 0x41, keyName = "A")),
            )
            val relayResp = RemoteKeyboardClient.sendRelay(identity, sendReq)
            assertEquals(1, relayResp.accepted)
            assertTrue(relayResp.targetOnline)
            assertTrue(relayResp.targetReceiverReady)

            val directEp = DirectTargetEndpoint("127.0.0.1", port)
            assertTrue(RemoteKeyboardClient.probeDirectTcpEndpoint(directEp))
            val rawBytes = CloudClipboardProtocol.json.encodeToString(
                RemoteKeyboardSendRequest.serializer(),
                sendReq,
            ).toByteArray(StandardCharsets.UTF_8)
            assertTrue(RemoteKeyboardClient.sendDirectTcp(directEp, rawBytes))

            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun resolveDocumentUriStringToLocalFileHandlesExternalStorageAndFileProviderUris() {
        val primaryDoc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.pdf"
        )
        assertNotNull(primaryDoc)
        assertTrue(primaryDoc!!.path.endsWith("/Download/report.pdf"))

        val user10Doc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.externalstorage.documents/document/10%3ACodes%2Fgoogleosbar%2FREADME.md"
        )
        assertNotNull(user10Doc)
        assertTrue(user10Doc!!.path.endsWith("/Codes/googleosbar/README.md"))

        val treeChildDoc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.externalstorage.documents/tree/primary%3ACodes/document/primary%3ACodes%2Fgoogleosbar%2FAGENTS.md"
        )
        assertNotNull(treeChildDoc)
        assertTrue(treeChildDoc!!.path.endsWith("/Codes/googleosbar/AGENTS.md"))

        val homeDoc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.externalstorage.documents/document/home%3Anotes%2Ftodo.txt"
        )
        assertNotNull(homeDoc)
        assertTrue(homeDoc!!.path.endsWith("/Documents/notes/todo.txt"))

        val rawDownloadsDoc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F10%2FDownload%2Farchive.zip"
        )
        assertNotNull(rawDownloadsDoc)
        assertEquals("/storage/emulated/10/Download/archive.zip", rawDownloadsDoc!!.path)

        val msfDownloadsDoc = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.android.providers.downloads.documents/document/msf%3A1000012345",
            hintDisplayName = "invoice.pdf",
        )
        assertNotNull(msfDownloadsDoc)
        assertTrue(msfDownloadsDoc!!.path.endsWith("/Download/invoice.pdf"))

        val fileProviderUri = CloudClipboardFiles.resolveDocumentUriStringToLocalFile(
            "content://com.google.android.apps.nbu.files.provider/external_files/Codes/test.txt"
        )
        assertNotNull(fileProviderUri)
        assertTrue(fileProviderUri!!.path.endsWith("/Codes/test.txt"))

        val tempFile = java.io.File.createTempFile("refresh-perm-", ".txt")
        try {
            tempFile.writeText("after-permission-grant")
            val unreadableBeforeGrant = CloudClipboardFiles.ClipboardFileSource(
                displayName = tempFile.name,
                size = 0L,
                exists = false,
                unreadable = true,
                localPath = tempFile.absolutePath,
                sourceUri = "content://com.android.providers.downloads.documents/document/msf%3A99999",
                streamProvider = null,
            )
            val beforePlan = CloudClipboardFiles.planSources(listOf(unreadableBeforeGrant))
            assertTrue(beforePlan.accepted.isEmpty())

            val refreshedAfterGrant = CloudClipboardItem.fileToSource(tempFile).copy(sourceUri = unreadableBeforeGrant.sourceUri)
            assertFalse(refreshedAfterGrant.unreadable)
            assertTrue(refreshedAfterGrant.exists)
            assertEquals(22L, refreshedAfterGrant.size)
            assertEquals(unreadableBeforeGrant.sourceUri, refreshedAfterGrant.sourceUri)
            val afterPlan = CloudClipboardFiles.planSources(listOf(refreshedAfterGrant))
            assertEquals(1, afterPlan.accepted.size)
            assertEquals(tempFile.name, afterPlan.accepted[0].name)
        } finally {
            tempFile.delete()
        }
    }
}

