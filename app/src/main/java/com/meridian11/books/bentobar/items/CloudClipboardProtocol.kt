package com.meridian11.books.bentobar.items

import android.os.Build
import android.view.KeyEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Serializable
data class NetTransferIdentity(
    val vpsUrl: String = CloudClipboardProtocol.DEFAULT_VPS_URL,
    val displayName: String = "",
    val deviceId: String = "",
    val publicKey: String = "",
    val privateKey: String = "",
    val revoked: Boolean = false,
    val sessionCookie: String = "",
) {
    val isRegistered: Boolean
        get() = !revoked &&
            vpsUrl.isNotBlank() &&
            deviceId.isNotBlank() &&
            privateKey.isNotBlank()
}

@Serializable
data class CloudClipboardEntry(
    val id: String = "",
    val kind: String = "text",
    val text: String = "",
    val fileName: String = "",
    val size: Long = 0L,
    val url: String = "",
    val fileId: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val createdAt: String = "",
) {
    val isFile: Boolean
        get() = kind.equals("file", ignoreCase = true) ||
            fileName.isNotBlank() ||
            fileId.isNotBlank() ||
            url.isNotBlank()

    fun preview(maxChars: Int = 44): String {
        val raw = if (isFile) {
            val name = fileName.ifBlank { url.substringAfterLast('/').ifBlank { "文件" } }
            val sizeTag = if (size > 0L) " (${CloudClipboardFiles.formatBytes(size)})" else ""
            "[文件] $name$sizeTag"
        } else {
            text
        }
        return ClipboardRules.preview(raw, maxChars).ifEmpty { raw.trim().take(maxChars) }
    }
}

@Serializable
internal data class CloudClipboardListResponse(
    val items: List<CloudClipboardEntry> = emptyList(),
)

@Serializable
internal data class CloudClipboardPushBody(
    val apiVersion: Int = CloudClipboardProtocol.AGENT_API_VERSION,
    val text: String,
    val hostname: String? = null,
)

@Serializable
internal data class CloudClipboardEnrollBody(
    val apiVersion: Int = CloudClipboardProtocol.AGENT_API_VERSION,
    val code: String,
    val publicKey: String,
    val hostname: String,
    val platform: String = "android",
    val os: String = "Android",
    val osVersion: String = "",
    val arch: String = "arm64",
    val agentVersion: String = CloudClipboardProtocol.AGENT_VERSION,
)

@Serializable
data class CloudClipboardEnrollReply(
    val deviceId: String = "",
    val createdAt: String = "",
)

@Serializable
internal data class CloudClipboardErrorReply(
    val error: String? = null,
    val message: String? = null,
)

/**
 * Pure RFC 8032 Ed25519 implementation so 64-byte NetTransfer keys (`seed32 || pub32`)
 * work identically on Android and JVM unit tests with zero external dependencies.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    private val BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    private data class ExtPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    private val ZERO_POINT = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE_POINT = ExtPoint(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

    private fun sha512(vararg chunks: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (chunk in chunks) md.update(chunk)
        return md.digest()
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val rev = ByteArray(bytes.size + 1)
        for (i in bytes.indices) {
            rev[bytes.size - i] = bytes[i]
        }
        return BigInteger(rev)
    }

    private fun toLittleEndian32(n: BigInteger): ByteArray {
        val out = ByteArray(32)
        var cur = n
        for (i in 0 until 32) {
            out[i] = (cur.toInt() and 0xFF).toByte()
            cur = cur.shiftRight(8)
        }
        return out
    }

    private fun pointAdd(p1: ExtPoint, p2: ExtPoint): ExtPoint {
        val a = p1.y.subtract(p1.x).mod(P).multiply(p2.y.subtract(p2.x).mod(P)).mod(P)
        val b = p1.y.add(p1.x).mod(P).multiply(p2.y.add(p2.x).mod(P)).mod(P)
        val c = p1.t.multiply(TWO_D).mod(P).multiply(p2.t).mod(P)
        val d = p1.z.shiftLeft(1).mod(P).multiply(p2.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return ExtPoint(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    private fun scalarMult(point: ExtPoint, scalar: BigInteger): ExtPoint {
        var res = ZERO_POINT
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                res = pointAdd(res, addend)
            }
            addend = pointAdd(addend, addend)
            k = k.shiftRight(1)
        }
        return res
    }

    private fun encodePoint(point: ExtPoint): ByteArray {
        val zInv = point.z.modInverse(P)
        val x = point.x.multiply(zInv).mod(P)
        val y = point.y.multiply(zInv).mod(P)
        val out = toLittleEndian32(y)
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    fun publicKeyFromSeed(seed32: ByteArray): ByteArray {
        require(seed32.size == 32) { "Ed25519 seed must be 32 bytes." }
        val h = sha512(seed32)
        val sBytes = h.copyOfRange(0, 32)
        sBytes[0] = (sBytes[0].toInt() and 248).toByte()
        sBytes[31] = ((sBytes[31].toInt() and 127) or 64).toByte()
        val a = fromLittleEndian(sBytes)
        return encodePoint(scalarMult(BASE_POINT, a))
    }

    /** Generates `(publicKey32, privateKey64)` where `privateKey64 = seed32 || publicKey32`. */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(32)
        random.nextBytes(seed)
        val pub = publicKeyFromSeed(seed)
        val priv = ByteArray(64)
        System.arraycopy(seed, 0, priv, 0, 32)
        System.arraycopy(pub, 0, priv, 32, 32)
        return pub to priv
    }

    fun sign(privateKeyOrSeed: ByteArray, message: ByteArray): ByteArray {
        require(privateKeyOrSeed.size == 32 || privateKeyOrSeed.size == 64) {
            "Ed25519 private key must be 32 or 64 bytes."
        }
        val seed = privateKeyOrSeed.copyOfRange(0, 32)
        val pub = if (privateKeyOrSeed.size == 64) {
            privateKeyOrSeed.copyOfRange(32, 64)
        } else {
            publicKeyFromSeed(seed)
        }
        val h = sha512(seed)
        val sBytes = h.copyOfRange(0, 32)
        sBytes[0] = (sBytes[0].toInt() and 248).toByte()
        sBytes[31] = ((sBytes[31].toInt() and 127) or 64).toByte()
        val a = fromLittleEndian(sBytes)
        val prefix = h.copyOfRange(32, 64)

        val r = fromLittleEndian(sha512(prefix, message)).mod(L)
        val rEncoded = encodePoint(scalarMult(BASE_POINT, r))
        val k = fromLittleEndian(sha512(rEncoded, pub, message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        val sEncoded = toLittleEndian32(s)

        val sig = ByteArray(64)
        System.arraycopy(rEncoded, 0, sig, 0, 32)
        System.arraycopy(sEncoded, 0, sig, 32, 32)
        return sig
    }
}

/**
 * Splits the very first write on a TCP socket into 1 byte + 80 ms pause + remaining bytes,
 * matching `PanelHandshakeStream` so TLS ClientHello SNI is split across two TCP segments.
 */
class PanelHandshakeOutputStream(
    out: OutputStream,
    private val splitDelayMs: Long = 80L,
) : FilterOutputStream(out) {
    private var firstWrite = true

    override fun write(b: Int) {
        firstWrite = false
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (firstWrite && len > 1) {
            firstWrite = false
            out.write(b, off, 1)
            out.flush()
            if (splitDelayMs > 0L) {
                try {
                    Thread.sleep(splitDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            out.write(b, off + 1, len - 1)
            return
        }
        firstWrite = false
        out.write(b, off, len)
    }
}

class PanelHandshakeSocket(
    private val delegate: Socket,
    private val splitDelayMs: Long = 80L,
) : Socket() {
    private var wrappedOut: OutputStream? = null

    override fun getInputStream(): InputStream = delegate.getInputStream()

    override fun getOutputStream(): OutputStream {
        var current = wrappedOut
        if (current == null) {
            current = PanelHandshakeOutputStream(delegate.getOutputStream(), splitDelayMs)
            wrappedOut = current
        }
        return current
    }

    override fun close() = delegate.close()
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isConnected(): Boolean = delegate.isConnected
    override fun getInetAddress() = delegate.inetAddress
    override fun getLocalAddress() = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun getLocalSocketAddress() = delegate.localSocketAddress
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
}

object CloudClipboardProtocol {
    const val AGENT_API_VERSION = 1
    const val AGENT_VERSION = "0.1.1"
    const val DEFAULT_VPS_URL = "https://vps.why.band"
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_FILE_NAME_RUNES = 120
    const val SIGNATURE_VERSION = "v1"
    private const val STREAM_BUFFER_SIZE = 81_920

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val random = SecureRandom()

    fun validateVpsUrl(vpsUrl: String?): String {
        val trimmed = vpsUrl?.trim().orEmpty()
        require(trimmed.isNotEmpty()) { "请填写面板地址。" }
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("面板地址必须是完整的 http:// 或 https:// URL。", e)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "面板地址必须是完整的 http:// 或 https:// URL。" }
        require(!uri.host.isNullOrBlank()) { "面板地址缺少有效主机名。" }
        return trimmed.trimEnd('/')
    }

    fun normalizeDisplayName(displayName: String?): String {
        val trimmed = displayName?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
        val model = runCatching { Build.MODEL?.trim().orEmpty() }.getOrDefault("")
        return model.ifEmpty { "Googlebook" }
    }

    fun createBody(text: String, hostname: String? = null): ByteArray {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Clipboard text is empty." }
        val bytes = cleanText.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) {
            "Clipboard text exceeds ${MAX_TEXT_BYTES / 1024} KiB."
        }
        val cleanHost = hostname?.trim()?.takeIf { it.isNotEmpty() }
        val payload = CloudClipboardPushBody(
            apiVersion = AGENT_API_VERSION,
            text = cleanText,
            hostname = cleanHost,
        )
        return json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
    }

    fun createNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Base64(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getEncoder().encodeToString(digest)
    }

    fun hashStream(
        streamProvider: () -> InputStream,
        size: Long,
        isCancelled: () -> Boolean = { false },
    ): String {
        require(size > 0L) { "文件是空的。" }
        require(size <= MAX_FILE_BYTES) { "文件超过 100 MB。" }
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var total = 0L
        streamProvider().use { input ->
            while (true) {
                if (isCancelled()) throw InterruptedIOException("已取消。")
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > size) {
                    throw IOException("文件大小在读取时变了。")
                }
                md.update(buffer, 0, read)
            }
        }
        if (total != size) {
            throw IOException("文件大小在读取时变了。")
        }
        return Base64.getEncoder().encodeToString(md.digest())
    }

    fun hashFile(
        file: File,
        size: Long = file.length(),
        isCancelled: () -> Boolean = { false },
    ): String = hashStream(
        streamProvider = { file.inputStream() },
        size = size,
        isCancelled = isCancelled,
    )

    fun fileSignatureScope(fileName: String, size: Long): String =
        "file:$fileName:$size"

    fun signDigest(
        identity: NetTransferIdentity,
        method: String,
        digestBase64: String,
        unixSeconds: Long,
        nonce: String,
        scope: String? = null,
    ): String {
        require(!identity.revoked) { "This NetTransfer device identity was revoked." }
        require(identity.deviceId.isNotBlank() && identity.privateKey.isNotBlank()) {
            "NetTransfer identity is missing deviceId or privateKey."
        }
        require(digestBase64.isNotBlank()) { "Body digest is required." }
        require(nonce.isNotBlank()) { "Nonce is required." }

        val keyBytes = try {
            Base64.getDecoder().decode(identity.privateKey.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("NetTransfer privateKey is not valid base64.", e)
        }
        require(keyBytes.size == 32 || keyBytes.size == 64) {
            "NetTransfer privateKey must be a 32-byte seed or 64-byte Ed25519 private key."
        }

        val upperMethod = method.trim().uppercase(Locale.ROOT)
        val cleanDigest = digestBase64.trim()
        val message = if (scope.isNullOrBlank()) {
            "$SIGNATURE_VERSION:${identity.deviceId.trim()}:$unixSeconds:$nonce:$upperMethod:$cleanDigest"
        } else {
            "$SIGNATURE_VERSION:${identity.deviceId.trim()}:$unixSeconds:$nonce:$upperMethod:$cleanDigest:${scope.trim()}"
        }
        val sigBytes = Ed25519.sign(keyBytes, message.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(sigBytes)
    }

    fun signMessage(
        identity: NetTransferIdentity,
        method: String,
        bodyBytes: ByteArray,
        unixSeconds: Long,
        nonce: String,
        scope: String? = null,
    ): Pair<String, String> {
        val digest = sha256Base64(bodyBytes)
        val signature = signDigest(identity, method, digest, unixSeconds, nonce, scope)
        return digest to signature
    }

    fun buildAgentHeaders(
        identity: NetTransferIdentity,
        method: String,
        bodyBytes: ByteArray,
        unixSeconds: Long = System.currentTimeMillis() / 1000L,
        nonce: String = createNonce(),
        scope: String? = null,
    ): Map<String, String> {
        val (digest, signature) = signMessage(identity, method, bodyBytes, unixSeconds, nonce, scope)
        return buildSignedHeaders(identity, digest, signature, unixSeconds, nonce)
    }

    fun buildFileHeaders(
        identity: NetTransferIdentity,
        fileName: String,
        size: Long,
        digestBase64: String,
        unixSeconds: Long = System.currentTimeMillis() / 1000L,
        nonce: String = createNonce(),
    ): Map<String, String> {
        val cleanName = normalizeFileNameOrNull(fileName)
            ?: throw IllegalArgumentException("File name is invalid.")
        require(size in 1..MAX_FILE_BYTES) {
            "File size must be between 1 and $MAX_FILE_BYTES bytes."
        }
        val scope = fileSignatureScope(cleanName, size)
        val signature = signDigest(identity, "POST", digestBase64, unixSeconds, nonce, scope)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/octet-stream"
        headers.putAll(buildSignedHeaders(identity, digestBase64.trim(), signature, unixSeconds, nonce))
        headers["X-NetTransfer-File-Name"] = Base64.getEncoder().encodeToString(cleanName.toByteArray(StandardCharsets.UTF_8))
        headers["X-NetTransfer-File-Size"] = size.toString()
        return headers
    }

    private fun buildSignedHeaders(
        identity: NetTransferIdentity,
        digest: String,
        signature: String,
        unixSeconds: Long,
        nonce: String,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["X-NetTransfer-Device"] = identity.deviceId.trim()
        headers["X-NetTransfer-Agent-API"] = AGENT_API_VERSION.toString()
        headers["X-NetTransfer-Timestamp"] = unixSeconds.toString()
        headers["X-NetTransfer-Nonce"] = nonce
        headers["X-NetTransfer-Body-Digest"] = digest
        headers["X-NetTransfer-Signature"] = signature
        headers["User-Agent"] = "NetTransfer-Agent/$AGENT_API_VERSION"
        val cookie = normalizeSessionCookie(identity.sessionCookie)
        if (cookie.isNotEmpty()) {
            headers["Cookie"] = cookie
        }
        return headers
    }

    fun normalizeSessionCookie(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains('=')) trimmed else "nettransfer_session=$trimmed"
    }

    /**
     * Exact port of `robbon` `CloudClipboardProtocol.NormalizeFileName` / `VPSPanel` `NormalizeClipboardFileName`.
     * Returns `null` when the filename is blank, contains unpaired UTF-16 surrogates, or reduces to empty / `.` / `..`.
     */
    fun normalizeFileNameOrNull(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var raw = name.trim().replace('\\', '/')
        val slash = raw.lastIndexOf('/')
        if (slash >= 0) {
            raw = raw.substring(slash + 1)
        }
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= raw.length || !Character.isLowSurrogate(raw[i + 1])) {
                    return null
                }
                sb.append(ch).append(raw[i + 1])
                i += 2
                continue
            }
            if (Character.isLowSurrogate(ch)) {
                return null
            }
            if (ch.code < 32 || ch.code == 127) {
                i++
                continue
            }
            when (ch) {
                '\\', '/', ':', '*', '?', '"', '<', '>', '|' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        var cleaned = sb.toString().trim().trimEnd('.').trim()
        cleaned = takeRunes(cleaned, MAX_FILE_NAME_RUNES).trimEnd('.').trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return null
        }
        return cleaned
    }

    fun normalizeFileName(name: String?): String =
        normalizeFileNameOrNull(name) ?: "clipboard.bin"

    private fun takeRunes(value: String, maxRunes: Int): String {
        if (value.isEmpty() || maxRunes <= 0) return ""
        var runes = 0
        var idx = 0
        while (idx < value.length && runes < maxRunes) {
            val cp = value.codePointAt(idx)
            idx += Character.charCount(cp)
            runes++
        }
        return if (idx >= value.length) value else value.substring(0, idx)
    }

    fun readErrorDetail(body: String, statusCode: Int, reasonPhrase: String = ""): String {
        val trimmed = body.trim()
        if (trimmed.isNotEmpty()) {
            runCatching {
                val parsed = json.decodeFromString<CloudClipboardErrorReply>(trimmed)
                val msg = parsed.error?.takeIf { it.isNotBlank() } ?: parsed.message?.takeIf { it.isNotBlank() }
                if (msg != null) return msg
            }
            return if (trimmed.length <= 200) trimmed else trimmed.take(200) + "..."
        }
        return if (reasonPhrase.isNotBlank()) "$statusCode $reasonPhrase" else "HTTP $statusCode"
    }
}

/**
 * File validation, planning, and human-readable status messages matching `robbon`'s
 * `CloudClipboardFiles.cs` and `CloudClipboardProgressForm.cs`.
 */
object CloudClipboardFiles {
    const val MAX_COUNT = 8

    data class ClipboardFileSource(
        val displayName: String,
        val size: Long,
        val isDirectory: Boolean = false,
        val exists: Boolean = true,
        val unreadable: Boolean = false,
        val networkMissing: Boolean = false,
        val localPath: String? = null,
        val sourceUri: String? = null,
        val streamProvider: (() -> InputStream)? = null,
    )

    data class Item(
        val name: String,
        val size: Long,
        val fullPath: String = "",
        val streamProvider: () -> InputStream,
    )

    data class PlanResult(
        val accepted: List<Item>,
        val skipped: List<String>,
    ) {
        fun skipSummary(): String {
            if (skipped.isEmpty()) return ""
            val shown = skipped.take(3).joinToString("；")
            val more = if (skipped.size > 3) " 等 ${skipped.size} 项" else ""
            return "跳过 $shown$more"
        }
    }

    fun resolveExistingLocalFile(file: File): File {
        if (file.exists() && file.canRead()) return file
        val rawPath = file.path.replace('\\', '/')
        val candidates = LinkedHashSet<String>()
        candidates.add(rawPath)
        val envRoot = runCatching { android.os.Environment.getExternalStorageDirectory()?.path?.trimEnd('/') }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        val knownPrefixes = listOfNotNull(
            "/storage/emulated/10/",
            "/storage/emulated/0/",
            "/sdcard/",
            envRoot?.let { "$it/" },
        ).distinct()
        for (prefix in knownPrefixes) {
            if (rawPath.startsWith(prefix)) {
                val rel = rawPath.removePrefix(prefix)
                candidates.add("/storage/emulated/10/$rel")
                candidates.add("/storage/emulated/0/$rel")
                if (envRoot != null) candidates.add("$envRoot/$rel")
            }
        }
        val files = candidates.map { File(it) }
        return files.firstOrNull { it.exists() && it.canRead() }
            ?: files.firstOrNull { it.exists() }
            ?: file
    }

    fun resolveDocumentUriStringToLocalFile(
        uriString: String,
        hintDisplayName: String? = null,
    ): File? {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("content://", ignoreCase = true)) return null
        val withoutScheme = trimmed.substring("content://".length)
        val slashIdx = withoutScheme.indexOf('/')
        if (slashIdx <= 0) return null
        val authority = withoutScheme.substring(0, slashIdx).lowercase(Locale.ROOT)
        val rawPath = withoutScheme.substring(slashIdx).substringBefore('?').substringBefore('#')

        fun urlDecode(s: String): String = runCatching {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(s)

        val docId: String? = when {
            rawPath.contains("/document/") -> {
                val afterDoc = rawPath.substringAfterLast("/document/").trim('/')
                afterDoc.takeIf { it.isNotEmpty() }?.let { urlDecode(it) }
            }
            rawPath.contains("/tree/") -> {
                val afterTree = rawPath.substringAfter("/tree/").substringBefore('/').trim('/')
                afterTree.takeIf { it.isNotEmpty() }?.let { urlDecode(it) }
            }
            else -> null
        }

        if (!docId.isNullOrBlank()) {
            if (docId.startsWith("raw:", ignoreCase = true)) {
                return resolveExistingLocalFile(File(docId.substring(4)))
            }
            if (docId.startsWith("/")) {
                return resolveExistingLocalFile(File(docId))
            }
            val split = docId.split(':', limit = 2)
            if (split.size == 2) {
                val type = split[0]
                val rel = split[1].trimStart('/')
                if (authority == "com.android.externalstorage.documents" ||
                    type.equals("primary", ignoreCase = true) ||
                    type.equals("home", ignoreCase = true)
                ) {
                    val candidates = when {
                        type.equals("primary", ignoreCase = true) || type == "10" || type == "0" -> listOf(
                            File("/storage/emulated/10/$rel"),
                            File("/storage/emulated/0/$rel"),
                        )
                        type.equals("home", ignoreCase = true) -> listOf(
                            File("/storage/emulated/10/Documents/$rel"),
                            File("/storage/emulated/0/Documents/$rel"),
                        )
                        else -> listOf(
                            File("/storage/$type/$rel"),
                            File("/storage/emulated/10/$rel"),
                            File("/storage/emulated/0/$rel"),
                        )
                    }
                    return candidates.firstOrNull { it.exists() && it.canRead() }
                        ?: candidates.firstOrNull { it.exists() }
                        ?: candidates.firstOrNull()
                }
            }
            if (authority == "com.android.providers.downloads.documents") {
                val name = hintDisplayName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    val candidates = listOf(
                        File("/storage/emulated/10/Download/$name"),
                        File("/storage/emulated/0/Download/$name"),
                        File("/storage/emulated/10/Downloads/$name"),
                        File("/storage/emulated/0/Downloads/$name"),
                    )
                    return candidates.firstOrNull { it.exists() && it.canRead() }
                        ?: candidates.firstOrNull { it.exists() }
                        ?: candidates.firstOrNull()
                }
            }
        }

        val decodedPath = urlDecode(rawPath)
        if (decodedPath.contains("/storage/emulated/")) {
            val sub = decodedPath.substring(decodedPath.indexOf("/storage/emulated/"))
            return resolveExistingLocalFile(File(sub))
        }
        if (decodedPath.contains("/sdcard/")) {
            val sub = decodedPath.substring(decodedPath.indexOf("/sdcard/"))
            return resolveExistingLocalFile(File(sub))
        }
        val knownRootPrefixes = listOf(
            "/external_files/",
            "/external_storage_root/",
            "/storage_root/",
            "/files_root/",
            "/external/",
            "/root/",
        )
        for (prefix in knownRootPrefixes) {
            if (decodedPath.startsWith(prefix, ignoreCase = true)) {
                val rel = decodedPath.substring(prefix.length).trimStart('/')
                if (rel.isNotEmpty()) {
                    val candidates = listOf(
                        File("/storage/emulated/10/$rel"),
                        File("/storage/emulated/0/$rel"),
                        File("/$rel"),
                    )
                    return candidates.firstOrNull { it.exists() && it.canRead() }
                        ?: candidates.firstOrNull { it.exists() }
                        ?: candidates.firstOrNull()
                }
            }
        }
        return null
    }

    fun planPaths(paths: Iterable<String?>): PlanResult {
        val accepted = ArrayList<Item>()
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()

        for (raw in paths) {
            if (raw.isNullOrBlank()) continue
            val path = cleanPath(raw)
            if (path.isEmpty()) continue
            val resolvedFile = resolveExistingLocalFile(File(path))
            val fullKey = runCatching { resolvedFile.canonicalPath }.getOrElse {
                runCatching { resolvedFile.absolutePath }.getOrDefault(path)
            }.lowercase(Locale.ROOT)
            if (!seen.add(fullKey)) continue

            val label = shortLabel(path)
            if (accepted.size >= MAX_COUNT) {
                skipped.add("$label：一次最多 $MAX_COUNT 个文件")
                continue
            }

            try {
                val file = resolvedFile
                if (file.isDirectory) {
                    skipped.add("$label：是文件夹")
                    continue
                }
                if (!file.exists() || !file.isFile) {
                    skipped.add(if (isUncPath(path)) "$label：网络磁盘上找不到这个文件" else "$label：找不到文件")
                    continue
                }
                if (!file.canRead()) {
                    skipped.add("$label：无法读取")
                    continue
                }
                val length = file.length()
                if (length <= 0L) {
                    skipped.add("$label：文件是空的")
                    continue
                }
                if (length > CloudClipboardProtocol.MAX_FILE_BYTES) {
                    skipped.add("$label：超过 100 MB")
                    continue
                }
                val cleanName = CloudClipboardProtocol.normalizeFileNameOrNull(file.name)
                if (cleanName == null) {
                    skipped.add("$label：文件名无效")
                    continue
                }
                val absPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                accepted.add(
                    Item(
                        name = cleanName,
                        size = length,
                        fullPath = absPath,
                        streamProvider = { file.inputStream() },
                    )
                )
            } catch (_: SecurityException) {
                skipped.add("$label：无法读取")
            } catch (_: IOException) {
                skipped.add("$label：无法读取")
            } catch (_: IllegalArgumentException) {
                skipped.add("$label：文件名无效")
            }
        }
        return PlanResult(accepted, skipped)
    }

    fun planSources(sources: Iterable<ClipboardFileSource>): PlanResult {
        val accepted = ArrayList<Item>()
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()

        for (src in sources) {
            val rawPath = cleanPath(src.localPath)
            val dedupKey = if (rawPath.isNotEmpty()) {
                val norm = runCatching { File(rawPath).canonicalPath }.getOrElse {
                    runCatching { File(rawPath).absolutePath }.getOrDefault(rawPath)
                }
                "path:${norm.lowercase(Locale.ROOT)}"
            } else {
                "src:${src.displayName}:${src.size}:${accepted.size + skipped.size}"
            }
            if (!seen.add(dedupKey)) continue

            val label = shortLabel(src.displayName.ifBlank { rawPath })
            if (accepted.size >= MAX_COUNT) {
                skipped.add("$label：一次最多 $MAX_COUNT 个文件")
                continue
            }
            if (src.isDirectory) {
                skipped.add("$label：是文件夹")
                continue
            }
            if (!src.exists) {
                skipped.add(if (src.networkMissing || isUncPath(rawPath)) "$label：网络磁盘上找不到这个文件" else "$label：找不到文件")
                continue
            }
            if (src.unreadable) {
                skipped.add("$label：无法读取")
                continue
            }
            if (src.size <= 0L) {
                skipped.add("$label：文件是空的")
                continue
            }
            if (src.size > CloudClipboardProtocol.MAX_FILE_BYTES) {
                skipped.add("$label：超过 100 MB")
                continue
            }
            val cleanName = CloudClipboardProtocol.normalizeFileNameOrNull(src.displayName.ifBlank { rawPath })
            if (cleanName == null) {
                skipped.add("$label：文件名无效")
                continue
            }
            val opener = src.streamProvider ?: rawPath.takeIf { it.isNotEmpty() }?.let { p -> { File(p).inputStream() } }
            if (opener == null) {
                skipped.add("$label：无法读取")
                continue
            }
            accepted.add(
                Item(
                    name = cleanName,
                    size = src.size,
                    fullPath = rawPath,
                    streamProvider = opener,
                )
            )
        }
        return PlanResult(accepted, skipped)
    }

    fun cleanPath(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var path = raw.trim()
        var start = 0
        var end = path.length
        while (start < end && path[start] == '\u0000') start++
        while (end > start && path[end - 1] == '\u0000') end--
        if (start != 0 || end != path.length) {
            path = path.substring(start, end).trim()
        }
        if (path.length >= 2 &&
            ((path.startsWith("\"") && path.endsWith("\"")) || (path.startsWith("'") && path.endsWith("'")))
        ) {
            path = path.substring(1, path.length - 1).trim()
        }
        if (path.startsWith("file://", ignoreCase = true)) {
            val parsed = runCatching { URI(path).path }.getOrNull()
            if (!parsed.isNullOrBlank()) return parsed.trim()
            val stripped = when {
                path.startsWith("file://localhost/", ignoreCase = true) -> "/" + path.substring("file://localhost/".length)
                path.startsWith("file:///", ignoreCase = true) -> "/" + path.substring("file:///".length)
                else -> path.substring("file://".length)
            }
            val decoded = runCatching {
                java.net.URLDecoder.decode(stripped.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(stripped)
            if (decoded.isNotBlank()) return decoded.trim()
        }
        return path
    }

    private fun isUncPath(path: String): Boolean =
        path.startsWith("\\\\") || path.startsWith("//")

    private fun shortLabel(path: String): String {
        val normalizedName = CloudClipboardProtocol.normalizeFileNameOrNull(path)
        if (!normalizedName.isNullOrEmpty()) return normalizedName
        val normalized = path.replace('\\', '/').trimEnd('/')
        val name = normalized.substringAfterLast('/').trim()
        return name.ifEmpty { "文件" }
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1024L) return "$b B"
        var current = b / 1024.0
        val units = arrayOf("KB", "MB", "GB")
        var unit = 0
        while (current >= 1024.0 && unit < units.size - 1) {
            current /= 1024.0
            unit++
        }
        val number = if (current >= 10.0) {
            String.format(Locale.ROOT, "%.0f", current)
        } else {
            String.format(Locale.ROOT, "%.1f", current)
        }
        return "$number ${units[unit]}"
    }

    fun uploadResultMessage(
        done: Int,
        total: Int,
        canceled: Boolean,
        error: String?,
        skips: String,
    ): String {
        var message = when {
            canceled -> if (done > 0) "已取消，已上传 $done 个。" else "已取消。"
            !error.isNullOrBlank() -> if (done > 0) "$error 已上传 $done 个。" else error
            total <= 1 -> "已送到 VPS 面板。"
            else -> "已把 $done 个文件送到 VPS 面板。"
        }
        if (!canceled && error.isNullOrBlank() && skips.isNotBlank()) {
            message = if (message.isBlank()) skips else "$message $skips"
        }
        return if (message.length <= 220) message else message.substring(0, 220)
    }
}

/**
 * Keyboard shortcut representation & matcher for Cloud Clipboard one-key copy & upload.
 * Stored in ItemConfig option `"shortcut"` as e.g. `"Ctrl+Alt+C"`.
 */
data class CloudClipboardShortcut(
    val ctrl: Boolean = true,
    val alt: Boolean = true,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val keyCode: Int = KeyEvent.KEYCODE_C,
) {
    val hasModifier: Boolean get() = ctrl || alt || shift || meta

    fun format(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts += "Ctrl"
        if (alt) parts += "Alt"
        if (shift) parts += "Shift"
        if (meta) parts += "Meta"
        parts += keyCodeToName(keyCode)
        return parts.joinToString(" + ")
    }

    fun serialize(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts += "Ctrl"
        if (alt) parts += "Alt"
        if (shift) parts += "Shift"
        if (meta) parts += "Meta"
        parts += keyCodeToName(keyCode)
        return parts.joinToString("+")
    }

    fun matches(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (!hasModifier) return false
        if (isModifierKey(event.keyCode)) return false
        return event.keyCode == keyCode &&
            event.isCtrlPressed == ctrl &&
            event.isAltPressed == alt &&
            event.isShiftPressed == shift &&
            event.isMetaPressed == meta
    }

    companion object {
        val DEFAULT = CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_C)

        val PRESETS: List<CloudClipboardShortcut> = listOf(
            DEFAULT,
            CloudClipboardShortcut(ctrl = true, alt = false, shift = true, meta = false, keyCode = KeyEvent.KEYCODE_C),
            CloudClipboardShortcut(ctrl = false, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_C),
            CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_V),
        )

        fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK -> true
            else -> false
        }

        fun fromKeyEvent(event: KeyEvent): CloudClipboardShortcut? {
            if (isModifierKey(event.keyCode)) return null
            val sc = CloudClipboardShortcut(
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                meta = event.isMetaPressed,
                keyCode = event.keyCode,
            )
            return sc.takeIf { it.hasModifier }
        }

        fun parse(raw: String?): CloudClipboardShortcut {
            if (raw.isNullOrBlank()) return DEFAULT
            val tokens = raw.split('+', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return DEFAULT
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            var keyCode: Int? = null
            for (token in tokens) {
                when (token.uppercase(Locale.ROOT)) {
                    "CTRL", "CONTROL" -> ctrl = true
                    "ALT", "OPTION" -> alt = true
                    "SHIFT" -> shift = true
                    "META", "WIN", "SUPER", "CMD", "COMMAND", "SEARCH" -> meta = true
                    else -> {
                        val parsedKey = nameToKeyCode(token)
                        if (parsedKey != null) keyCode = parsedKey
                    }
                }
            }
            val finalKey = keyCode ?: return DEFAULT
            val sc = CloudClipboardShortcut(ctrl = ctrl, alt = alt, shift = shift, meta = meta, keyCode = finalKey)
            return if (sc.hasModifier) sc else DEFAULT
        }

        fun parseOrNull(raw: String?): CloudClipboardShortcut? {
            if (raw.isNullOrBlank()) return null
            val tokens = raw.split('+', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            var keyCode: Int? = null
            for (token in tokens) {
                when (token.uppercase(Locale.ROOT)) {
                    "CTRL", "CONTROL" -> ctrl = true
                    "ALT", "OPTION" -> alt = true
                    "SHIFT" -> shift = true
                    "META", "WIN", "SUPER", "CMD", "COMMAND", "SEARCH" -> meta = true
                    else -> {
                        val parsedKey = nameToKeyCode(token)
                        if (parsedKey != null) keyCode = parsedKey
                    }
                }
            }
            val finalKey = keyCode ?: return null
            val sc = CloudClipboardShortcut(ctrl = ctrl, alt = alt, shift = shift, meta = meta, keyCode = finalKey)
            return sc.takeIf { it.hasModifier }
        }

        fun keyCodeToName(keyCode: Int): String = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                ('A'.code + (keyCode - KeyEvent.KEYCODE_A)).toChar().toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar().toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_MINUS -> "-"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            KeyEvent.KEYCODE_SEMICOLON -> ";"
            KeyEvent.KEYCODE_APOSTROPHE -> "'"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_BACKSLASH -> "\\"
            else -> "Key$keyCode"
        }

        fun nameToKeyCode(name: String): Int? {
            val u = name.trim().uppercase(Locale.ROOT)
            if (u.length == 1) {
                val ch = u[0]
                if (ch in 'A'..'Z') return KeyEvent.KEYCODE_A + (ch.code - 'A'.code)
                if (ch in '0'..'9') return KeyEvent.KEYCODE_0 + (ch.code - '0'.code)
            }
            if (u.startsWith("F") && u.length in 2..3) {
                val n = u.substring(1).toIntOrNull()
                if (n != null && n in 1..12) return KeyEvent.KEYCODE_F1 + (n - 1)
            }
            if (u.startsWith("KEY")) {
                val code = u.substring(3).toIntOrNull()
                if (code != null && code > 0) return code
            }
            return when (u) {
                "SPACE" -> KeyEvent.KEYCODE_SPACE
                "INSERT", "INS" -> KeyEvent.KEYCODE_INSERT
                "`", "GRAVE", "BACKQUOTE" -> KeyEvent.KEYCODE_GRAVE
                "-" -> KeyEvent.KEYCODE_MINUS
                "=" -> KeyEvent.KEYCODE_EQUALS
                "[" -> KeyEvent.KEYCODE_LEFT_BRACKET
                "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET
                ";" -> KeyEvent.KEYCODE_SEMICOLON
                "'" -> KeyEvent.KEYCODE_APOSTROPHE
                "," -> KeyEvent.KEYCODE_COMMA
                "." -> KeyEvent.KEYCODE_PERIOD
                "/" -> KeyEvent.KEYCODE_SLASH
                "\\" -> KeyEvent.KEYCODE_BACKSLASH
                else -> null
            }
        }
    }
}

data class RawHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = String(body, StandardCharsets.UTF_8)
}

/**
 * HTTP/1.1 client over `PanelHandshakeSocket` + `SSLSocketFactory` so TLS connections to the
 * NetTransfer VPS panel split the initial ClientHello packet just like `PanelHandshakeStream` on PC.
 */
object CloudClipboardClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val FILE_CONNECT_TIMEOUT_MS = 20_000
    private const val FILE_READ_TIMEOUT_MS = 15 * 60 * 1_000
    private const val UPLOAD_BUFFER_SIZE = 64 * 1024

    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        splitTlsFirstByte: Boolean = true,
    ): RawHttpResponse = requestStream(
        url = url,
        method = method,
        headers = headers,
        contentLength = body?.size?.toLong(),
        bodyWriter = if (body != null && body.isNotEmpty()) { out -> out.write(body) } else null,
        splitTlsFirstByte = splitTlsFirstByte,
        connectTimeoutMs = CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
    )

    fun requestStream(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        contentLength: Long? = null,
        bodyWriter: ((OutputStream) -> Unit)? = null,
        splitTlsFirstByte: Boolean = true,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): RawHttpResponse {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing URI scheme: $url")
        val isHttps = scheme == "https"
        require(isHttps || scheme == "http") { "Unsupported URI scheme: $scheme" }
        val host = uri.host ?: throw IllegalArgumentException("Missing URI host: $url")
        val port = if (uri.port > 0) uri.port else if (isHttps) 443 else 80
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val targetPath = if (uri.rawQuery.isNullOrEmpty()) rawPath else "$rawPath?${uri.rawQuery}"
        val hostHeader = if ((isHttps && port == 443) || (!isHttps && port == 80)) host else "$host:$port"

        val tcpSocket = Socket()
        try {
            tcpSocket.tcpNoDelay = true
            tcpSocket.soTimeout = readTimeoutMs
            tcpSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val activeSocket: Socket = if (isHttps) {
                val baseSocket = if (splitTlsFirstByte) PanelHandshakeSocket(tcpSocket, 80L) else tcpSocket
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(baseSocket, host, port, true) as SSLSocket
                runCatching {
                    val params: SSLParameters = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(host))
                    sslSocket.sslParameters = params
                }
                sslSocket.soTimeout = readTimeoutMs
                sslSocket.startHandshake()
                sslSocket
            } else {
                tcpSocket
            }

            activeSocket.use { sock ->
                val out = sock.getOutputStream()
                val reqBuilder = StringBuilder()
                reqBuilder.append(method.uppercase(Locale.ROOT)).append(' ').append(targetPath).append(" HTTP/1.1\r\n")
                reqBuilder.append("Host: ").append(hostHeader).append("\r\n")
                reqBuilder.append("Connection: close\r\n")
                var hasAccept = false
                for ((k, v) in headers) {
                    if (k.equals("Host", ignoreCase = true) ||
                        k.equals("Connection", ignoreCase = true) ||
                        k.equals("Content-Length", ignoreCase = true)
                    ) continue
                    if (k.equals("Accept", ignoreCase = true)) hasAccept = true
                    reqBuilder.append(k).append(": ").append(v).append("\r\n")
                }
                if (!hasAccept) {
                    reqBuilder.append("Accept: application/json\r\n")
                }
                if (contentLength != null) {
                    reqBuilder.append("Content-Length: ").append(contentLength).append("\r\n")
                } else if (method.equals("POST", ignoreCase = true) || method.equals("PUT", ignoreCase = true)) {
                    reqBuilder.append("Content-Length: 0\r\n")
                }
                reqBuilder.append("\r\n")

                val headerBytes = reqBuilder.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(headerBytes)
                if (bodyWriter != null && (contentLength == null || contentLength > 0L)) {
                    bodyWriter(out)
                }
                out.flush()

                val input = BufferedInputStream(sock.getInputStream())
                return readHttpResponse(input)
            }
        } catch (e: Exception) {
            runCatching { tcpSocket.close() }
            throw e
        }
    }

    private fun readLineCrLf(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawByte) buf.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r') else null
            }
            sawByte = true
            if (b == '\n'.code) {
                break
            }
            buf.write(b)
        }
        return buf.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r')
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n == -1) break
            offset += n
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineCrLf(input)?.trim() ?: break
            if (sizeLine.isEmpty()) continue
            val semi = sizeLine.indexOf(';')
            val hex = if (semi >= 0) sizeLine.substring(0, semi).trim() else sizeLine
            val chunkSize = hex.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                readLineCrLf(input)
                break
            }
            val chunk = readExact(input, chunkSize)
            out.write(chunk)
            readLineCrLf(input)
        }
        return out.toByteArray()
    }

    private fun readHttpResponse(input: InputStream): RawHttpResponse {
        val statusLine = readLineCrLf(input) ?: throw IOException("Empty HTTP response from server.")
        val parts = statusLine.split(' ', limit = 3)
        val statusCode = parts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed HTTP status line: $statusLine")
        val reason = parts.getOrNull(2).orEmpty()

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLineCrLf(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val transferEncoding = headers["transfer-encoding"].orEmpty()
        val contentLength = headers["content-length"]?.toIntOrNull()
        val bodyBytes = when {
            transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input)
            contentLength != null && contentLength >= 0 -> readExact(input, contentLength)
            else -> input.readBytes()
        }
        return RawHttpResponse(statusCode, reason, headers, bodyBytes)
    }

    fun enroll(
        vpsUrl: String,
        displayName: String,
        registrationCode: String,
        sessionCookie: String = "",
    ): NetTransferIdentity {
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(vpsUrl)
        val cleanName = CloudClipboardProtocol.normalizeDisplayName(displayName)
        val cleanCode = registrationCode.trim()
        require(cleanCode.isNotEmpty()) { "请输入面板生成的注册码。" }

        val (pub, priv) = Ed25519.generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pub)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(priv)

        val osVer = runCatching { "Android ${Build.VERSION.RELEASE ?: ""}".trim() }.getOrDefault("Android")
        val payload = CloudClipboardEnrollBody(
            apiVersion = CloudClipboardProtocol.AGENT_API_VERSION,
            code = cleanCode,
            publicKey = publicKeyBase64,
            hostname = cleanName,
            platform = "android",
            os = "Android",
            osVersion = osVer,
            arch = "arm64",
            agentVersion = CloudClipboardProtocol.AGENT_VERSION,
        )
        val bodyBytes = CloudClipboardProtocol.json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val resp = request(
            url = "$cleanUrl/api/agent/v1/enroll",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "User-Agent" to "NetTransfer-Agent/${CloudClipboardProtocol.AGENT_API_VERSION}",
            ),
            body = bodyBytes,
        )
        if (resp.statusCode !in 200..299) {
            val detail = CloudClipboardProtocol.readErrorDetail(resp.bodyText, resp.statusCode, resp.reasonPhrase)
            throw IllegalStateException("面板拒绝注册：$detail")
        }
        val enrolled = runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardEnrollReply>(resp.bodyText)
        }.getOrNull()
        val deviceId = enrolled?.deviceId?.trim().orEmpty()
        if (deviceId.isEmpty()) {
            throw IllegalStateException("面板未返回设备 ID。")
        }
        return NetTransferIdentity(
            vpsUrl = cleanUrl,
            displayName = cleanName,
            deviceId = deviceId,
            publicKey = publicKeyBase64,
            privateKey = privateKeyBase64,
            revoked = false,
            sessionCookie = sessionCookie.trim(),
        )
    }

    fun pushText(
        identity: NetTransferIdentity,
        text: String,
        hostname: String? = identity.displayName.ifBlank { null },
    ): CloudClipboardEntry {
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val bodyBytes = CloudClipboardProtocol.createBody(text, hostname)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json; charset=utf-8"
        headers.putAll(CloudClipboardProtocol.buildAgentHeaders(identity, "POST", bodyBytes))

        val resp = request(
            url = "$cleanUrl/api/agent/v1/clipboard",
            method = "POST",
            headers = headers,
            body = bodyBytes,
        )
        if (resp.statusCode !in 200..299) {
            val detail = CloudClipboardProtocol.readErrorDetail(resp.bodyText, resp.statusCode, resp.reasonPhrase)
            throw IllegalStateException("云剪切板发送失败：$detail")
        }
        val parsed = runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardEntry>(resp.bodyText)
        }.getOrNull()
        return parsed?.takeIf { it.id.isNotBlank() || it.text.isNotBlank() } ?: CloudClipboardEntry(
            id = "local-${System.currentTimeMillis()}",
            kind = "text",
            text = text.trim(),
            deviceId = identity.deviceId,
            deviceName = hostname.orEmpty(),
        )
    }

    fun pushFile(
        identity: NetTransferIdentity,
        file: File,
        onProgress: ((Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): CloudClipboardEntry {
        require(file.exists() && file.isFile) { "找不到要上传的文件。" }
        return pushFile(
            identity = identity,
            fileName = file.name,
            size = file.length(),
            streamProvider = { file.inputStream() },
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    fun pushFile(
        identity: NetTransferIdentity,
        fileName: String,
        size: Long,
        streamProvider: () -> InputStream,
        onProgress: ((Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): CloudClipboardEntry {
        if (identity.revoked) throw IllegalStateException("这台设备已被面板撤销。")
        if (isCancelled()) throw InterruptedIOException("已取消。")
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val cleanName = CloudClipboardProtocol.normalizeFileNameOrNull(fileName)
            ?: throw IllegalArgumentException("文件名无效。")
        require(size > 0L) { "$cleanName：文件是空的。" }
        require(size <= CloudClipboardProtocol.MAX_FILE_BYTES) { "$cleanName：超过 100 MB。" }

        val digest = try {
            CloudClipboardProtocol.hashStream(streamProvider, size, isCancelled)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: SecurityException) {
            throw IllegalStateException("$cleanName 没有读取权限。", e)
        } catch (e: IOException) {
            if (e.message == "文件大小在读取时变了。") throw e
            throw IllegalStateException("$cleanName 无法读取。", e)
        }
        val headers = CloudClipboardProtocol.buildFileHeaders(identity, cleanName, size, digest)
        onProgress?.invoke(0L)

        val resp = requestStream(
            url = "$cleanUrl/api/agent/v1/clipboard/file",
            method = "POST",
            headers = headers,
            contentLength = size,
            bodyWriter = { out ->
                streamProvider().use { input ->
                    val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
                    var sent = 0L
                    while (sent < size) {
                        if (isCancelled()) throw InterruptedIOException("已取消。")
                        val want = minOf(buffer.size.toLong(), size - sent).toInt()
                        val n = input.read(buffer, 0, want)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        onProgress?.invoke(sent)
                    }
                    if (sent != size) {
                        throw IOException("文件大小在读取时变了。")
                    }
                }
            },
            connectTimeoutMs = FILE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = FILE_READ_TIMEOUT_MS,
        )
        if (resp.statusCode !in 200..299) {
            val detail = CloudClipboardProtocol.readErrorDetail(resp.bodyText, resp.statusCode, resp.reasonPhrase)
            throw IllegalStateException("$cleanName：$detail")
        }
        val parsed = runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardEntry>(resp.bodyText)
        }.getOrNull()
        return parsed?.takeIf { it.id.isNotBlank() || it.fileName.isNotBlank() || it.url.isNotBlank() }
            ?: CloudClipboardEntry(
                id = "local-file-${System.currentTimeMillis()}",
                kind = "file",
                fileName = cleanName,
                size = size,
                deviceId = identity.deviceId,
                deviceName = identity.displayName,
            )
    }

    /**
     * Fetches clipboard items from the cloud server:
     * 1. First tries signed `GET <vpsUrl>/api/agent/v1/clipboard` (Ed25519 agent auth + optional session cookie).
     * 2. If the server returns 404 or 405 (when `/api/agent/v1/clipboard` is POST-only), falls back to
     *    `GET <vpsUrl>/api/clipboard` (with session cookie + Ed25519 headers).
     */
    fun fetchItems(identity: NetTransferIdentity): List<CloudClipboardEntry> {
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val emptyBody = ByteArray(0)

        if (identity.isRegistered) {
            val agentHeaders = CloudClipboardProtocol.buildAgentHeaders(identity, "GET", emptyBody)
            val agentResp = request(
                url = "$cleanUrl/api/agent/v1/clipboard",
                method = "GET",
                headers = agentHeaders,
                body = null,
            )
            if (agentResp.statusCode in 200..299) {
                return parseItemsPayload(agentResp.bodyText)
            }
            if (agentResp.statusCode != 404 && agentResp.statusCode != 405) {
                val detail = CloudClipboardProtocol.readErrorDetail(agentResp.bodyText, agentResp.statusCode, agentResp.reasonPhrase)
                throw IllegalStateException("云剪切板拉取失败：$detail")
            }
        }

        val panelHeaders = LinkedHashMap<String, String>()
        if (identity.isRegistered) {
            panelHeaders.putAll(CloudClipboardProtocol.buildAgentHeaders(identity, "GET", emptyBody))
        } else {
            val cookie = CloudClipboardProtocol.normalizeSessionCookie(identity.sessionCookie)
            if (cookie.isNotEmpty()) panelHeaders["Cookie"] = cookie
            panelHeaders["User-Agent"] = "NetTransfer-Agent/${CloudClipboardProtocol.AGENT_API_VERSION}"
        }

        val panelResp = request(
            url = "$cleanUrl/api/clipboard",
            method = "GET",
            headers = panelHeaders,
            body = null,
        )
        if (panelResp.statusCode in 200..299) {
            return parseItemsPayload(panelResp.bodyText)
        }
        if (panelResp.statusCode == 401 || panelResp.statusCode == 403) {
            throw IllegalStateException(
                if (identity.isRegistered) {
                    "服务端尚未启用 Agent GET 接口（请在 VPS 更新并重启 nettransfer-dashboard，或在设置中填写备用 Session Cookie）。"
                } else {
                    "拉取云端列表需先注册设备，或在设置中填写面板 Session Cookie。"
                }
            )
        }
        val detail = CloudClipboardProtocol.readErrorDetail(panelResp.bodyText, panelResp.statusCode, panelResp.reasonPhrase)
        throw IllegalStateException("云剪切板拉取失败：$detail")
    }

    fun deleteItem(identity: NetTransferIdentity, itemId: String) {
        val cleanId = itemId.trim()
        require(cleanId.isNotEmpty()) { "Clipboard item ID is empty." }
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val emptyBody = ByteArray(0)

        if (identity.isRegistered) {
            val agentHeaders = CloudClipboardProtocol.buildAgentHeaders(identity, "DELETE", emptyBody)
            val agentResp = request(
                url = "$cleanUrl/api/agent/v1/clipboard/$cleanId",
                method = "DELETE",
                headers = agentHeaders,
                body = null,
            )
            if (agentResp.statusCode in 200..299) return
            if (agentResp.statusCode != 404 && agentResp.statusCode != 405) {
                val detail = CloudClipboardProtocol.readErrorDetail(agentResp.bodyText, agentResp.statusCode, agentResp.reasonPhrase)
                throw IllegalStateException("删除云剪切板条目失败：$detail")
            }
        }

        val panelHeaders = LinkedHashMap<String, String>()
        if (identity.isRegistered) {
            panelHeaders.putAll(CloudClipboardProtocol.buildAgentHeaders(identity, "DELETE", emptyBody))
        } else {
            val cookie = CloudClipboardProtocol.normalizeSessionCookie(identity.sessionCookie)
            if (cookie.isNotEmpty()) panelHeaders["Cookie"] = cookie
        }
        val panelResp = request(
            url = "$cleanUrl/api/clipboard/$cleanId",
            method = "DELETE",
            headers = panelHeaders,
            body = null,
        )
        if (panelResp.statusCode !in 200..299) {
            val detail = CloudClipboardProtocol.readErrorDetail(panelResp.bodyText, panelResp.statusCode, panelResp.reasonPhrase)
            throw IllegalStateException("删除云剪切板条目失败：$detail")
        }
    }

    internal fun parseItemsPayload(bodyText: String): List<CloudClipboardEntry> {
        val trimmed = bodyText.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return runCatching {
                CloudClipboardProtocol.json.decodeFromString<List<CloudClipboardEntry>>(trimmed)
            }.getOrDefault(emptyList())
        }
        val wrapper = runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardListResponse>(trimmed)
        }.getOrNull()
        if (wrapper != null && (wrapper.items.isNotEmpty() || trimmed.contains("\"items\""))) return wrapper.items
        val single = runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardEntry>(trimmed)
        }.getOrNull()
        return if (single != null && (single.id.isNotBlank() || single.text.isNotBlank() || single.url.isNotBlank())) {
            listOf(single)
        } else {
            emptyList()
        }
    }
}

/**
 * Local persistence for the device's `agent.json` identity and cached cloud clipboard history
 * in Android's `noBackupFilesDir` (so private keys never leave the device in backups or layout exports),
 * plus automatic discovery of `/storage/emulated/10/.nettransfer/agent.json`.
 */
object CloudClipboardStore {
    private const val IDENTITY_FILE = "cloud_clipboard_agent.json"
    private const val HISTORY_FILE = "cloud_clipboard_items.json"
    private const val MAX_HISTORY = 25

    private val externalIdentityPaths = listOf(
        "/storage/emulated/10/.nettransfer/agent.json",
        "/storage/emulated/0/.nettransfer/agent.json",
        "/storage/emulated/10/Android/data/com.meridian11.books.bentobar/files/cloud_clipboard_agent.json",
        "/storage/emulated/0/Android/data/com.meridian11.books.bentobar/files/cloud_clipboard_agent.json",
    )

    private fun primaryIdentityFile(): File? =
        runCatching { File(Env.app.noBackupFilesDir, IDENTITY_FILE) }.getOrNull()

    private fun historyFile(): File? =
        runCatching { File(Env.app.noBackupFilesDir, HISTORY_FILE) }.getOrNull()

    fun loadIdentity(): NetTransferIdentity {
        val candidates = buildList {
            primaryIdentityFile()?.let { add(it) }
            externalIdentityPaths.forEach { add(File(it)) }
        }
        for (file in candidates) {
            if (file.exists() && file.canRead()) {
                val loaded = runCatching {
                    CloudClipboardProtocol.json.decodeFromString<NetTransferIdentity>(file.readText())
                }.getOrNull()
                if (loaded != null && (loaded.deviceId.isNotBlank() || loaded.vpsUrl.isNotBlank())) {
                    return loaded.copy(
                        vpsUrl = loaded.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }.trimEnd('/'),
                        displayName = CloudClipboardProtocol.normalizeDisplayName(loaded.displayName),
                    )
                }
            }
        }
        return NetTransferIdentity(
            vpsUrl = CloudClipboardProtocol.DEFAULT_VPS_URL,
            displayName = CloudClipboardProtocol.normalizeDisplayName(null),
        )
    }

    fun saveIdentity(identity: NetTransferIdentity) {
        val normalized = identity.copy(
            vpsUrl = identity.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }.trim().trimEnd('/'),
            displayName = CloudClipboardProtocol.normalizeDisplayName(identity.displayName),
        )
        val raw = CloudClipboardProtocol.json.encodeToString(normalized)
        primaryIdentityFile()?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(raw)
                tmp.renameTo(file)
            }
        }
    }

    fun importIdentityJson(rawJson: String): NetTransferIdentity {
        val parsed = CloudClipboardProtocol.json.decodeFromString<NetTransferIdentity>(rawJson.trim())
        require(parsed.deviceId.isNotBlank() && parsed.privateKey.isNotBlank()) {
            "agent.json 缺少 deviceId 或 privateKey。"
        }
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(parsed.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL })
        val normalized = parsed.copy(
            vpsUrl = cleanUrl,
            displayName = CloudClipboardProtocol.normalizeDisplayName(parsed.displayName),
        )
        saveIdentity(normalized)
        return normalized
    }

    fun loadHistory(): List<CloudClipboardEntry> {
        val file = historyFile() ?: return emptyList()
        if (!file.exists() || !file.canRead()) return emptyList()
        return runCatching {
            CloudClipboardProtocol.json.decodeFromString<CloudClipboardListResponse>(file.readText()).items
        }.getOrDefault(emptyList())
    }

    fun saveHistory(items: List<CloudClipboardEntry>) {
        val file = historyFile() ?: return
        val capped = items.take(MAX_HISTORY)
        runCatching {
            file.parentFile?.mkdirs()
            val raw = CloudClipboardProtocol.json.encodeToString(CloudClipboardListResponse(capped))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(raw)
            tmp.renameTo(file)
        }
    }

    fun mergeEntries(remote: List<CloudClipboardEntry>, local: List<CloudClipboardEntry>): List<CloudClipboardEntry> {
        if (remote.isEmpty()) return local.take(MAX_HISTORY)
        val seenIds = HashSet<String>()
        val seenTexts = HashSet<String>()
        val merged = ArrayList<CloudClipboardEntry>(MAX_HISTORY)
        for (item in remote + local) {
            val idKey = item.id.trim()
            val textKey = if (item.isFile) "file:${item.fileId}:${item.url}:${item.fileName}" else "text:${item.text.trim()}"
            if (idKey.isNotEmpty() && !idKey.startsWith("local-")) {
                if (!seenIds.add(idKey)) continue
            }
            if (!seenTexts.add(textKey)) continue
            merged.add(item)
            if (merged.size >= MAX_HISTORY) break
        }
        return merged
    }
}

// ============================================================================
// Remote Keyboard Mapping Protocol & Key Mapper
// ============================================================================

@Serializable
data class RemoteDeviceNetwork(
    val name: String = "",
    val addresses: List<String> = emptyList(),
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val mac: String = "",
)

@Serializable
data class RemoteDeviceInventory(
    val hostname: String = "",
    val os: String = "",
    val arch: String = "",
    val networks: List<RemoteDeviceNetwork> = emptyList(),
)

@Serializable
data class DirectTargetEndpoint(
    val host: String,
    val port: Int = RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT,
) {
    override fun toString(): String = "$host:$port"
}

@Serializable
data class RemoteDeviceSummary(
    val id: String = "",
    val displayName: String = "",
    val name: String = "",
    val hostname: String = "",
    val platform: String = "",
    val os: String = "",
    val osVersion: String = "",
    val arch: String = "",
    val agentVersion: String = "",
    val sourceIp: String = "",
    val online: Boolean = false,
    val revoked: Boolean = false,
    val directPort: Int = RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT,
    val keyboardActive: Boolean = false,
    val lastSeenAt: String = "",
    val inventory: RemoteDeviceInventory = RemoteDeviceInventory(),
) {
    val displayTitle: String
        get() = displayName.trim().ifBlank {
            inventory.hostname.trim().ifBlank {
                name.trim().ifBlank {
                    hostname.trim().ifBlank {
                        platformLabel
                    }
                }
            }
        }

    val platformLabel: String
        get() {
            val rawOs = os.ifBlank { inventory.os }
            return when {
                platform.equals("windows", ignoreCase = true) || rawOs.contains("windows", ignoreCase = true) -> "Windows"
                platform.equals("darwin", ignoreCase = true) || rawOs.contains("mac", ignoreCase = true) || rawOs.contains("darwin", ignoreCase = true) -> "macOS"
                platform.equals("linux", ignoreCase = true) || rawOs.contains("linux", ignoreCase = true) -> "Linux"
                platform.equals("android", ignoreCase = true) || rawOs.contains("android", ignoreCase = true) -> "Android"
                else -> rawOs.ifBlank { platform.ifBlank { "PC" } }
            }
        }

    val isMac: Boolean
        get() = platformLabel == "macOS"

    val isDesktopTarget: Boolean
        get() {
            val rawOs = os.ifBlank { inventory.os }
            return !revoked && !platform.equals("android", ignoreCase = true) && !rawOs.contains("android", ignoreCase = true)
        }

    val lanIpv4Addresses: List<String>
        get() {
            val out = LinkedHashSet<String>()
            for (net in inventory.networks) {
                for (raw in (net.ipv4 + net.addresses)) {
                    val ip = raw.substringBefore('/').trim()
                    if (ip.isNotEmpty() && !ip.contains(':') && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        out.add(ip)
                    }
                }
            }
            return out.toList()
        }

    val candidateDirectEndpoints: List<DirectTargetEndpoint>
        get() {
            val port = if (directPort in 1..65535) directPort else RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT
            val out = ArrayList<DirectTargetEndpoint>()
            val seen = HashSet<String>()
            for (ip in lanIpv4Addresses) {
                if (seen.add("$ip:$port")) {
                    out.add(DirectTargetEndpoint(ip, port))
                }
            }
            val src = sourceIp.trim()
            if (src.isNotEmpty() && !src.startsWith("127.") && seen.add("$src:$port")) {
                out.add(DirectTargetEndpoint(src, port))
            }
            return out
        }
}

@Serializable
data class RemoteDeviceListResponse(
    val devices: List<RemoteDeviceSummary> = emptyList(),
)

@Serializable
data class RemoteKeyboardEvent(
    val seq: Long = 0L,
    val action: String = "down", // "down" | "up" | "release_all" | "text" | "gesture"
    val vkCode: Int = 0,
    val scanCode: Int = 0,
    val extended: Boolean = false,
    val androidKeyCode: Int = 0,
    val keyName: String = "",
    val metaState: Int = 0,
    val text: String = "",
    val gesture: String = "",
    val sentAtMs: Long = 0L,
)

@Serializable
data class RemoteKeyboardSendRequest(
    val targetDeviceId: String,
    val sourceDeviceId: String = "",
    val sourceName: String = "",
    val directEndpoint: String = "",
    val events: List<RemoteKeyboardEvent> = emptyList(),
)

@Serializable
data class RemoteKeyboardSendResponse(
    val accepted: Int = 0,
    val targetOnline: Boolean = false,
    val targetReceiverReady: Boolean = false,
    val targetSourceIp: String = "",
    val targetLanAddrs: List<String> = emptyList(),
    val targetDirectPort: Int = RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT,
)

object RemoteKeyboardProtocol {
    const val DEFAULT_DIRECT_PORT = 19878
    const val MODE_AUTO = "auto"
    const val MODE_RELAY = "relay"
    const val MODE_DIRECT = "direct"

    val DEFAULT_EXIT_SHORTCUT = CloudClipboardShortcut(
        ctrl = true,
        alt = true,
        shift = false,
        meta = false,
        keyCode = KeyEvent.KEYCODE_Q,
    )

    val EXIT_SHORTCUT_PRESETS: List<CloudClipboardShortcut> = listOf(
        DEFAULT_EXIT_SHORTCUT,
        CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_ESCAPE),
        CloudClipboardShortcut(ctrl = true, alt = false, shift = true, meta = false, keyCode = KeyEvent.KEYCODE_Q),
        CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_BACKSLASH),
    )

    val DEVICE_ACTIVATION_SHORTCUT_PRESETS: List<CloudClipboardShortcut> = listOf(
        CloudClipboardShortcut(ctrl = false, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_1),
        CloudClipboardShortcut(ctrl = false, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_2),
        CloudClipboardShortcut(ctrl = false, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_3),
        CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_1),
        CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_2),
        CloudClipboardShortcut(ctrl = true, alt = true, shift = false, meta = false, keyCode = KeyEvent.KEYCODE_M),
    )

    fun parseDirectEndpoint(raw: String?, defaultPort: Int = DEFAULT_DIRECT_PORT): DirectTargetEndpoint? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        val colon = cleaned.lastIndexOf(':')
        return if (colon > 0 && colon < cleaned.length - 1) {
            val host = cleaned.substring(0, colon).trim().removeSurrounding("[", "]")
            val port = cleaned.substring(colon + 1).trim().toIntOrNull() ?: defaultPort
            if (host.isEmpty() || port !in 1..65535) null else DirectTargetEndpoint(host, port)
        } else {
            if (cleaned.isEmpty()) null else DirectTargetEndpoint(cleaned, defaultPort)
        }
    }
}

/**
 * Maps Android `KeyEvent` codes & hardware scan codes to Windows Virtual-Key (`VK_*`) codes,
 * hardware scan codes, and extended-key flags (`KEYEVENTF_EXTENDEDKEY`) so remote Windows/macOS
 * machines receive exact physical key presses and releases, including modifier combinations.
 */
object RemoteKeyboardKeyMapper {
    data class MappedKey(
        val vkCode: Int,
        val scanCode: Int,
        val extended: Boolean,
        val keyName: String,
    )

    fun mapAndroidKey(keyCode: Int, hardwareScanCode: Int = 0): MappedKey? {
        val mapped = when (keyCode) {
            // Letters A..Z -> 0x41..0x5A
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val offset = keyCode - KeyEvent.KEYCODE_A
                val vk = 0x41 + offset
                val name = ('A'.code + offset).toChar().toString()
                MappedKey(vk, winScanCodeForVk(vk, hardwareScanCode), false, name)
            }
            // Top-row digits 0..9 -> 0x30..0x39
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val offset = keyCode - KeyEvent.KEYCODE_0
                val vk = 0x30 + offset
                val name = ('0'.code + offset).toChar().toString()
                MappedKey(vk, winScanCodeForVk(vk, hardwareScanCode), false, name)
            }
            // Function keys F1..F12 -> 0x70..0x7B
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                val offset = keyCode - KeyEvent.KEYCODE_F1
                val vk = 0x70 + offset
                MappedKey(vk, winScanCodeForVk(vk, hardwareScanCode), false, "F${offset + 1}")
            }
            // Modifiers
            KeyEvent.KEYCODE_SHIFT_LEFT -> MappedKey(0xA0, 0x2A, false, "LShift")
            KeyEvent.KEYCODE_SHIFT_RIGHT -> MappedKey(0xA1, 0x36, false, "RShift")
            KeyEvent.KEYCODE_CTRL_LEFT -> MappedKey(0xA2, 0x1D, false, "LCtrl")
            KeyEvent.KEYCODE_CTRL_RIGHT -> MappedKey(0xA3, 0x1D, true, "RCtrl")
            KeyEvent.KEYCODE_ALT_LEFT -> MappedKey(0xA4, 0x38, false, "LAlt")
            KeyEvent.KEYCODE_ALT_RIGHT -> MappedKey(0xA5, 0x38, true, "RAlt")
            KeyEvent.KEYCODE_META_LEFT -> MappedKey(0x5B, 0x5B, true, "LWin")
            KeyEvent.KEYCODE_META_RIGHT -> MappedKey(0x5C, 0x5C, true, "RWin")
            KeyEvent.KEYCODE_CAPS_LOCK -> MappedKey(0x14, 0x3A, false, "CapsLock")
            KeyEvent.KEYCODE_NUM_LOCK -> MappedKey(0x90, 0x45, true, "NumLock")
            KeyEvent.KEYCODE_SCROLL_LOCK -> MappedKey(0x91, 0x46, false, "ScrollLock")

            // Editing & Control keys
            KeyEvent.KEYCODE_DEL -> MappedKey(0x08, 0x0E, false, "Backspace")
            KeyEvent.KEYCODE_TAB -> MappedKey(0x09, 0x0F, false, "Tab")
            KeyEvent.KEYCODE_ENTER -> MappedKey(0x0D, 0x1C, false, "Enter")
            KeyEvent.KEYCODE_NUMPAD_ENTER -> MappedKey(0x0D, 0x1C, true, "NumEnter")
            KeyEvent.KEYCODE_ESCAPE -> MappedKey(0x1B, 0x01, false, "Esc")
            KeyEvent.KEYCODE_SPACE -> MappedKey(0x20, 0x39, false, "Space")

            // Navigation cluster (Extended keys on Windows)
            KeyEvent.KEYCODE_PAGE_UP -> MappedKey(0x21, 0x49, true, "PageUp")
            KeyEvent.KEYCODE_PAGE_DOWN -> MappedKey(0x22, 0x51, true, "PageDown")
            KeyEvent.KEYCODE_MOVE_END -> MappedKey(0x23, 0x4F, true, "End")
            KeyEvent.KEYCODE_MOVE_HOME -> MappedKey(0x24, 0x47, true, "Home")
            KeyEvent.KEYCODE_DPAD_LEFT -> MappedKey(0x25, 0x4B, true, "Left")
            KeyEvent.KEYCODE_DPAD_UP -> MappedKey(0x26, 0x48, true, "Up")
            KeyEvent.KEYCODE_DPAD_RIGHT -> MappedKey(0x27, 0x4D, true, "Right")
            KeyEvent.KEYCODE_DPAD_DOWN -> MappedKey(0x28, 0x50, true, "Down")
            KeyEvent.KEYCODE_INSERT -> MappedKey(0x2D, 0x52, true, "Insert")
            KeyEvent.KEYCODE_FORWARD_DEL -> MappedKey(0x2E, 0x53, true, "Delete")
            KeyEvent.KEYCODE_SYSRQ -> MappedKey(0x2C, 0x37, true, "PrintScreen")
            KeyEvent.KEYCODE_BREAK -> MappedKey(0x13, 0x45, false, "Pause")
            KeyEvent.KEYCODE_MENU -> MappedKey(0x5D, 0x5D, true, "Apps")

            // US OEM Punctuation
            KeyEvent.KEYCODE_SEMICOLON -> MappedKey(0xBA, 0x27, false, ";")
            KeyEvent.KEYCODE_EQUALS -> MappedKey(0xBB, 0x0D, false, "=")
            KeyEvent.KEYCODE_COMMA -> MappedKey(0xBC, 0x33, false, ",")
            KeyEvent.KEYCODE_MINUS -> MappedKey(0xBD, 0x0C, false, "-")
            KeyEvent.KEYCODE_PERIOD -> MappedKey(0xBE, 0x34, false, ".")
            KeyEvent.KEYCODE_SLASH -> MappedKey(0xBF, 0x35, false, "/")
            KeyEvent.KEYCODE_GRAVE -> MappedKey(0xC0, 0x29, false, "`")
            KeyEvent.KEYCODE_LEFT_BRACKET -> MappedKey(0xDB, 0x1A, false, "[")
            KeyEvent.KEYCODE_BACKSLASH -> MappedKey(0xDC, 0x2B, false, "\\")
            KeyEvent.KEYCODE_RIGHT_BRACKET -> MappedKey(0xDD, 0x1B, false, "]")
            KeyEvent.KEYCODE_APOSTROPHE -> MappedKey(0xDE, 0x28, false, "'")

            // Numpad
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                val offset = keyCode - KeyEvent.KEYCODE_NUMPAD_0
                val vk = 0x60 + offset
                MappedKey(vk, winScanCodeForVk(vk, hardwareScanCode), false, "Num$offset")
            }
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> MappedKey(0x6A, 0x37, false, "Num*")
            KeyEvent.KEYCODE_NUMPAD_ADD -> MappedKey(0x6B, 0x4E, false, "Num+")
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> MappedKey(0x6D, 0x4A, false, "Num-")
            KeyEvent.KEYCODE_NUMPAD_DOT -> MappedKey(0x6E, 0x53, false, "Num.")
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> MappedKey(0x6F, 0x35, true, "Num/")

            // Media / Volume keys
            KeyEvent.KEYCODE_VOLUME_MUTE -> MappedKey(0xAD, 0x20, true, "Mute")
            KeyEvent.KEYCODE_VOLUME_DOWN -> MappedKey(0xAE, 0x2E, true, "VolDown")
            KeyEvent.KEYCODE_VOLUME_UP -> MappedKey(0xAF, 0x30, true, "VolUp")
            KeyEvent.KEYCODE_MEDIA_NEXT -> MappedKey(0xB0, 0x19, true, "NextTrack")
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MappedKey(0xB1, 0x10, true, "PrevTrack")
            KeyEvent.KEYCODE_MEDIA_STOP -> MappedKey(0xB2, 0x24, true, "MediaStop")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MappedKey(0xB3, 0x22, true, "PlayPause")
            else -> null
        }
        return mapped
    }

    private fun winScanCodeForVk(vk: Int, fallbackLinuxScanCode: Int): Int {
        if (fallbackLinuxScanCode in 1..0x7F) return fallbackLinuxScanCode
        return when (vk) {
            0x41 -> 0x1E; 0x42 -> 0x30; 0x43 -> 0x2E; 0x44 -> 0x20; 0x45 -> 0x12
            0x46 -> 0x21; 0x47 -> 0x22; 0x48 -> 0x23; 0x49 -> 0x17; 0x4A -> 0x24
            0x4B -> 0x25; 0x4C -> 0x26; 0x4D -> 0x32; 0x4E -> 0x31; 0x4F -> 0x18
            0x50 -> 0x19; 0x51 -> 0x10; 0x52 -> 0x13; 0x53 -> 0x1F; 0x54 -> 0x14
            0x55 -> 0x16; 0x56 -> 0x2F; 0x57 -> 0x11; 0x58 -> 0x2D; 0x59 -> 0x15
            0x5A -> 0x2C
            0x31 -> 0x02; 0x32 -> 0x03; 0x33 -> 0x04; 0x34 -> 0x05; 0x35 -> 0x06
            0x36 -> 0x07; 0x37 -> 0x08; 0x38 -> 0x09; 0x39 -> 0x0A; 0x30 -> 0x0B
            0x70 -> 0x3B; 0x71 -> 0x3C; 0x72 -> 0x3D; 0x73 -> 0x3E; 0x74 -> 0x3F
            0x75 -> 0x40; 0x76 -> 0x41; 0x77 -> 0x42; 0x78 -> 0x43; 0x79 -> 0x44
            0x7A -> 0x57; 0x7B -> 0x58
            else -> 0
        }
    }

    fun toProtocolEvent(event: KeyEvent, seq: Long, nowMs: Long = System.currentTimeMillis()): RemoteKeyboardEvent? {
        val actionStr = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> return null
        }
        val mapped = mapAndroidKey(event.keyCode, event.scanCode) ?: return null
        return RemoteKeyboardEvent(
            seq = seq,
            action = actionStr,
            vkCode = mapped.vkCode,
            scanCode = mapped.scanCode,
            extended = mapped.extended,
            androidKeyCode = event.keyCode,
            keyName = mapped.keyName,
            metaState = event.metaState,
            sentAtMs = nowMs,
        )
    }

    fun createReleaseAllEvent(seq: Long, nowMs: Long = System.currentTimeMillis()): RemoteKeyboardEvent =
        RemoteKeyboardEvent(
            seq = seq,
            action = "release_all",
            keyName = "ReleaseAll",
            sentAtMs = nowMs,
        )

    fun createGestureEvent(seq: Long, gesture: String, nowMs: Long = System.currentTimeMillis()): RemoteKeyboardEvent =
        RemoteKeyboardEvent(
            seq = seq,
            action = "gesture",
            gesture = gesture,
            keyName = "Gesture:$gesture",
            sentAtMs = nowMs,
        )
}

/**
 * Network transport for Remote Keyboard Mapping:
 * - Fetches registered devices from `GET /api/agent/v1/devices` (with fallback to `GET /api/devices`)
 * - Sends keyboard batches over direct P2P / LAN UDP & HTTP (`19878`) and/or VPS relay (`POST /api/agent/v1/keyboard/send`)
 */
object RemoteKeyboardClient {
    private const val DIRECT_TCP_TIMEOUT_MS = 450
    private const val RELAY_CONNECT_TIMEOUT_MS = 4_000
    private const val RELAY_READ_TIMEOUT_MS = 6_000

    @Volatile
    private var udpSocket: DatagramSocket? = null

    private fun ensureUdpSocket(): DatagramSocket {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        synchronized(this) {
            val again = udpSocket
            if (again != null && !again.isClosed) return again
            val created = DatagramSocket()
            udpSocket = created
            return created
        }
    }

    fun fetchDevices(identity: NetTransferIdentity): List<RemoteDeviceSummary> {
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val emptyBody = ByteArray(0)

        if (identity.isRegistered) {
            val agentHeaders = CloudClipboardProtocol.buildAgentHeaders(identity, "GET", emptyBody)
            val resp = CloudClipboardClient.request(
                url = "$cleanUrl/api/agent/v1/devices",
                method = "GET",
                headers = agentHeaders,
                body = null,
            )
            if (resp.statusCode in 200..299) {
                return parseDevicesPayload(resp.bodyText, identity.deviceId)
            }
            if (resp.statusCode != 404 && resp.statusCode != 405) {
                val detail = CloudClipboardProtocol.readErrorDetail(resp.bodyText, resp.statusCode, resp.reasonPhrase)
                throw IllegalStateException("获取设备列表失败：$detail")
            }
        }

        val panelHeaders = LinkedHashMap<String, String>()
        if (identity.isRegistered) {
            panelHeaders.putAll(CloudClipboardProtocol.buildAgentHeaders(identity, "GET", emptyBody))
        } else {
            val cookie = CloudClipboardProtocol.normalizeSessionCookie(identity.sessionCookie)
            if (cookie.isNotEmpty()) panelHeaders["Cookie"] = cookie
        }
        val panelResp = CloudClipboardClient.request(
            url = "$cleanUrl/api/devices",
            method = "GET",
            headers = panelHeaders,
            body = null,
        )
        if (panelResp.statusCode in 200..299) {
            return parseDevicesPayload(panelResp.bodyText, identity.deviceId)
        }
        val detail = CloudClipboardProtocol.readErrorDetail(panelResp.bodyText, panelResp.statusCode, panelResp.reasonPhrase)
        throw IllegalStateException("获取设备列表失败：$detail")
    }

    internal fun parseDevicesPayload(bodyText: String, selfDeviceId: String = ""): List<RemoteDeviceSummary> {
        val trimmed = bodyText.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rawList: List<RemoteDeviceSummary> = when {
            trimmed.startsWith("[") -> runCatching {
                CloudClipboardProtocol.json.decodeFromString<List<RemoteDeviceSummary>>(trimmed)
            }.getOrDefault(emptyList())
            else -> runCatching {
                CloudClipboardProtocol.json.decodeFromString<RemoteDeviceListResponse>(trimmed).devices
            }.getOrDefault(emptyList())
        }
        return rawList
            .filter { !it.revoked && it.id.isNotBlank() && it.id != selfDeviceId }
            .sortedWith(
                compareByDescending<RemoteDeviceSummary> { it.isDesktopTarget }
                    .thenByDescending { it.online }
                    .thenByDescending { it.keyboardActive }
                    .thenBy { it.displayTitle.lowercase(Locale.ROOT) }
            )
    }

    fun probeDirectTcpEndpoint(endpoint: DirectTargetEndpoint, timeoutMs: Int = DIRECT_TCP_TIMEOUT_MS): Boolean {
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            val out = sock.getOutputStream()
            val req = "GET /api/keyboard/ping HTTP/1.1\r\nHost: ${endpoint.host}:${endpoint.port}\r\nConnection: close\r\n\r\n"
            out.write(req.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val buf = ByteArray(64)
            val n = sock.getInputStream().read(buf)
            n > 0 && String(buf, 0, n, StandardCharsets.ISO_8859_1).contains("200")
        } catch (_: Exception) {
            false
        } finally {
            runCatching { sock.close() }
        }
    }

    fun sendDirectTcp(endpoint: DirectTargetEndpoint, payloadBytes: ByteArray, timeoutMs: Int = DIRECT_TCP_TIMEOUT_MS): Boolean {
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            val out = sock.getOutputStream()
            val header = buildString {
                append("POST /api/keyboard/direct HTTP/1.1\r\n")
                append("Host: ").append(endpoint.host).append(':').append(endpoint.port).append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(payloadBytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.UTF_8)
            out.write(header)
            out.write(payloadBytes)
            out.flush()
            val buf = ByteArray(64)
            val n = sock.getInputStream().read(buf)
            n > 0 && String(buf, 0, n, StandardCharsets.ISO_8859_1).contains("200")
        } catch (_: Exception) {
            false
        } finally {
            runCatching { sock.close() }
        }
    }

    fun sendDirectUdp(endpoint: DirectTargetEndpoint, payloadBytes: ByteArray): Boolean {
        if (payloadBytes.isEmpty() || payloadBytes.size > 60_000) return false
        return try {
            val sock = ensureUdpSocket()
            val addr = InetSocketAddress(endpoint.host, endpoint.port)
            val pkt = DatagramPacket(payloadBytes, payloadBytes.size, addr.address, addr.port)
            sock.send(pkt)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendRelay(
        identity: NetTransferIdentity,
        request: RemoteKeyboardSendRequest,
    ): RemoteKeyboardSendResponse {
        val cleanUrl = CloudClipboardProtocol.validateVpsUrl(identity.vpsUrl)
        val bodyBytes = CloudClipboardProtocol.json.encodeToString(request).toByteArray(StandardCharsets.UTF_8)

        if (identity.isRegistered) {
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers.putAll(CloudClipboardProtocol.buildAgentHeaders(identity, "POST", bodyBytes))
            val resp = CloudClipboardClient.requestStream(
                url = "$cleanUrl/api/agent/v1/keyboard/send",
                method = "POST",
                headers = headers,
                contentLength = bodyBytes.size.toLong(),
                bodyWriter = { it.write(bodyBytes) },
                splitTlsFirstByte = true,
                connectTimeoutMs = RELAY_CONNECT_TIMEOUT_MS,
                readTimeoutMs = RELAY_READ_TIMEOUT_MS,
            )
            if (resp.statusCode in 200..299) {
                return runCatching {
                    CloudClipboardProtocol.json.decodeFromString<RemoteKeyboardSendResponse>(resp.bodyText)
                }.getOrDefault(RemoteKeyboardSendResponse(accepted = request.events.size, targetOnline = true))
            }
            val detail = CloudClipboardProtocol.readErrorDetail(resp.bodyText, resp.statusCode, resp.reasonPhrase)
            throw IllegalStateException("VPS 转发失败：$detail")
        }

        val panelHeaders = LinkedHashMap<String, String>()
        panelHeaders["Content-Type"] = "application/json; charset=utf-8"
        val cookie = CloudClipboardProtocol.normalizeSessionCookie(identity.sessionCookie)
        if (cookie.isNotEmpty()) panelHeaders["Cookie"] = cookie
        val panelResp = CloudClipboardClient.requestStream(
            url = "$cleanUrl/api/keyboard/send",
            method = "POST",
            headers = panelHeaders,
            contentLength = bodyBytes.size.toLong(),
            bodyWriter = { it.write(bodyBytes) },
            splitTlsFirstByte = true,
            connectTimeoutMs = RELAY_CONNECT_TIMEOUT_MS,
            readTimeoutMs = RELAY_READ_TIMEOUT_MS,
        )
        if (panelResp.statusCode in 200..299) {
            return runCatching {
                CloudClipboardProtocol.json.decodeFromString<RemoteKeyboardSendResponse>(panelResp.bodyText)
            }.getOrDefault(RemoteKeyboardSendResponse(accepted = request.events.size, targetOnline = true))
        }
        val detail = CloudClipboardProtocol.readErrorDetail(panelResp.bodyText, panelResp.statusCode, panelResp.reasonPhrase)
        throw IllegalStateException("VPS 转发失败：$detail")
    }
}

