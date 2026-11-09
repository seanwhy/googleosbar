package com.meridian11.books.bentobar.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.DragEvent
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object CloudClipboardItem : ItemType(
    "cloud_clipboard",
    R.string.item_cloud_clipboard_title,
    "brand:cloud_clipboard",
    R.string.item_cloud_clipboard_desc,
) {
    override val menuWidthDp = 360
    override val iconOnly = true
    override val canBeActive = true
    override val discreet = true
    override val trigger = Trigger(R.string.trigger_cloud_clipboard, R.string.trigger_cloud_clipboard_short)

    data class LocalClipboardSnapshot(
        val text: String? = null,
        val files: List<CloudClipboardFiles.ClipboardFileSource> = emptyList(),
    ) {
        val hasFiles: Boolean get() = files.isNotEmpty()
        val hasText: Boolean get() = !text.isNullOrBlank()
        val isEmpty: Boolean get() = !hasFiles && !hasText
    }

    data class UploadProgressState(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val fileSent: Long,
        val fileSize: Long,
        val sentOverall: Long = fileSent,
        val totalBytes: Long = fileSize,
        val reading: Boolean = false,
        val canceling: Boolean = false,
    ) {
        val fraction: Float
            get() {
                val tot = if (totalBytes > 0L) totalBytes else fileSize
                val snt = if (totalBytes > 0L) sentOverall else fileSent
                return if (tot <= 0L) 0f else (snt.toDouble() / tot.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        val percent: Int
            get() {
                val tot = if (totalBytes > 0L) totalBytes else fileSize
                val snt = if (totalBytes > 0L) sentOverall else fileSent
                if (tot <= 0L || snt <= 0L) return 0
                if (snt >= tot) return 100
                return minOf(99, (snt * 100L / tot).toInt())
            }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    internal val identityState = MutableStateFlow(NetTransferIdentity())
    internal val entriesState = MutableStateFlow<List<CloudClipboardEntry>>(emptyList())
    internal val localClipState = MutableStateFlow("")
    internal val localClipFilesState = MutableStateFlow<List<CloudClipboardFiles.ClipboardFileSource>>(emptyList())
    internal val syncingState = MutableStateFlow(false)
    internal val uploadProgressState = MutableStateFlow<UploadProgressState?>(null)
    internal val statusMessageState = MutableStateFlow<String?>(null)
    internal val errorMessageState = MutableStateFlow<String?>(null)

    @Volatile private var currentUploadCancel: AtomicBoolean? = null
    @Volatile private var initialized = false
    @Volatile private var lastAutoSyncMs = 0L

    private var clipListenerRegistered = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readLocalClipboardSnapshot() }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            runCatching {
                identityState.value = CloudClipboardStore.loadIdentity()
                entriesState.value = CloudClipboardStore.loadHistory()
            }
        }
    }

    override fun onLive() {
        ensureInitialized()
        if (!clipListenerRegistered) {
            clipListenerRegistered = runCatching {
                Env.app.getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
            }.isSuccess
        }
        readLocalClipboardSnapshot()
    }

    override fun onIdle() {
        if (clipListenerRegistered) {
            runCatching {
                Env.app.getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
            }
            clipListenerRegistered = false
        }
    }

    override fun sample(now: Long) {
        ensureInitialized()
        val id = identityState.value
        if ((id.isRegistered || id.sessionCookie.isNotBlank()) && now - lastAutoSyncMs >= 60_000L) {
            lastAutoSyncMs = now
            refreshFromCloud(quiet = true)
        }
    }

    fun cancelCurrentUpload() {
        currentUploadCancel?.set(true)
        uploadProgressState.value?.let { cur ->
            if (!cur.canceling) {
                uploadProgressState.value = cur.copy(canceling = true)
            }
        }
    }

    internal fun formatFilesPreview(files: List<CloudClipboardFiles.ClipboardFileSource>): String {
        if (files.isEmpty()) return ""
        if (files.size == 1) {
            val f = files.first()
            val sizeTag = if (f.size > 0L) " (${CloudClipboardFiles.formatBytes(f.size)})" else ""
            return "[文件] ${f.displayName}$sizeTag"
        }
        val names = files.take(3).joinToString(", ") { it.displayName }
        val more = if (files.size > 3) " 等" else ""
        return "[${files.size} 个文件] $names$more"
    }

    internal fun readLocalClipboardSnapshot(): LocalClipboardSnapshot {
        val cm = runCatching { Env.app.getSystemService(ClipboardManager::class.java) }.getOrNull()
            ?: return LocalClipboardSnapshot()
        val clip: ClipData = runCatching { cm.primaryClip }.getOrNull()
            ?: return LocalClipboardSnapshot()
        return parseClipDataSnapshot(clip, updateState = true)
    }

    internal fun parseClipDataSnapshot(clip: ClipData?, updateState: Boolean = false): LocalClipboardSnapshot {
        if (clip == null || clip.itemCount <= 0) return LocalClipboardSnapshot()

        val uriFiles = ArrayList<CloudClipboardFiles.ClipboardFileSource>()
        val seenUris = LinkedHashSet<String>()
        val textParts = ArrayList<String>()

        fun collectUri(u: Uri?) {
            if (u == null) return
            val key = u.toString()
            if (key.isBlank() || !seenUris.add(key)) return
            resolveUriToFileSource(u)?.let { uriFiles.add(it) }
        }

        for (i in 0 until clip.itemCount) {
            val item = runCatching { clip.getItemAt(i) }.getOrNull() ?: continue
            collectUri(item.uri)
            val intent = item.intent
            if (intent != null) {
                collectUri(intent.data)
                val nestedClip = runCatching { intent.clipData }.getOrNull()
                if (nestedClip != null) {
                    for (j in 0 until nestedClip.itemCount) {
                        val nestedItem = runCatching { nestedClip.getItemAt(j) }.getOrNull() ?: continue
                        collectUri(nestedItem.uri)
                    }
                }
                @Suppress("DEPRECATION")
                val streamExtra = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
                collectUri(streamExtra)
                @Suppress("DEPRECATION")
                val streamList = runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
                streamList?.forEach { collectUri(it) }
            }

            val rawText = item.text?.toString()?.trim()
            if (!rawText.isNullOrEmpty() && !rawText.startsWith("""{"version":""")) {
                textParts.add(rawText)
            } else if (item.uri == null && intent?.data == null) {
                val coerced = runCatching { item.coerceToText(Env.app)?.toString()?.trim() }.getOrNull()
                if (!coerced.isNullOrEmpty() && !coerced.startsWith("""{"version":""")) {
                    textParts.add(coerced)
                }
            }
        }

        val combinedText = textParts.joinToString("\n").trim().takeIf { it.isNotEmpty() }
        val resolvedFiles = if (uriFiles.isNotEmpty()) {
            uriFiles
        } else {
            extractFileSourcesFromText(combinedText)
        }

        if (updateState) {
            if (resolvedFiles.isNotEmpty()) {
                localClipFilesState.value = resolvedFiles
            } else {
                localClipFilesState.value = emptyList()
            }
            if (!combinedText.isNullOrEmpty()) {
                localClipState.value = combinedText
            }
        }
        return LocalClipboardSnapshot(text = combinedText, files = resolvedFiles)
    }

    internal fun readLocalClipboard(): String? = readLocalClipboardSnapshot().text

    internal fun extractFileSourcesFromText(rawText: String?): List<CloudClipboardFiles.ClipboardFileSource> {
        if (rawText.isNullOrBlank()) return emptyList()
        val rawLines = rawText.lines()
            .map { it.trim().trim('\u0000') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (rawLines.isEmpty() || rawLines.size > 32) return emptyList()

        // Support x-special/gnome-copied-files / KDE / Linux desktop file managers ("copy\nfile:///..." or "cut\nfile:///...")
        val lines = if (rawLines.size > 1 && (rawLines[0].equals("copy", ignoreCase = true) || rawLines[0].equals("cut", ignoreCase = true))) {
            rawLines.drop(1)
        } else {
            rawLines
        }
        if (lines.isEmpty()) return emptyList()

        val results = ArrayList<CloudClipboardFiles.ClipboardFileSource>()
        for (line in lines) {
            val token = line.trim('"', '\'').trim()
            if (token.isEmpty()) return emptyList()
            when {
                token.startsWith("content://", ignoreCase = true) -> {
                    val src = runCatching { resolveUriToFileSource(Uri.parse(token)) }.getOrNull() ?: return emptyList()
                    results.add(src)
                }
                token.startsWith("file://", ignoreCase = true) -> {
                    val path = CloudClipboardFiles.cleanPath(token)
                    if (path.isEmpty()) return emptyList()
                    results.add(fileToSource(File(path)))
                }
                looksLikeLocalFilePath(token) -> {
                    val cleaned = CloudClipboardFiles.cleanPath(token)
                    val expanded = if (cleaned.startsWith("~/")) {
                        "/storage/emulated/10/" + cleaned.removePrefix("~/")
                    } else {
                        cleaned
                    }
                    results.add(fileToSource(File(expanded)))
                }
                else -> return emptyList()
            }
        }
        return results
    }

    private fun looksLikeLocalFilePath(token: String): Boolean {
        if (token.contains('\n') || token.contains('\r')) return false
        val knownPrefix = token.startsWith("/storage/") ||
            token.startsWith("/sdcard/") ||
            token.startsWith("/mnt/") ||
            token.startsWith("/home/") ||
            token.startsWith("/tmp/") ||
            token.startsWith("/data/") ||
            token.startsWith("~/")
        if (knownPrefix) return true
        if (token.startsWith("/") && token.length > 2 && !token.startsWith("//")) {
            return runCatching { File(token).exists() }.getOrDefault(false)
        }
        return false
    }

    fun hasAllFilesAccess(context: android.content.Context? = runCatching { Env.app }.getOrNull()): Boolean {
        if (context == null) return false
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)
    }

    fun hasMediaOrStoragePermission(context: android.content.Context? = runCatching { Env.app }.getOrNull()): Boolean {
        if (context == null) return false
        if (hasAllFilesAccess(context)) return true
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)
    }

    fun requestFileAccessPermission() {
        val ctx = runCatching { Env.app }.getOrNull() ?: return
        com.meridian11.books.bentobar.ui.MainActivity.requestFilePermissions(ctx)
    }

    internal fun fileToSource(file: File): CloudClipboardFiles.ClipboardFileSource {
        val resolved = CloudClipboardFiles.resolveExistingLocalFile(file)
        val name = resolved.name.ifBlank { file.name.ifBlank { resolved.path } }
        val absPath = runCatching { resolved.canonicalPath }.getOrElse { resolved.absolutePath }
        if (resolved.isDirectory) {
            return CloudClipboardFiles.ClipboardFileSource(
                displayName = name,
                size = 0L,
                isDirectory = true,
                exists = true,
                localPath = absPath,
            )
        }
        if (!resolved.exists() || !resolved.isFile) {
            return CloudClipboardFiles.ClipboardFileSource(
                displayName = name,
                size = 0L,
                isDirectory = false,
                exists = false,
                localPath = absPath,
            )
        }
        val readable = resolved.canRead()
        val length = runCatching { resolved.length() }.getOrDefault(0L)
        return CloudClipboardFiles.ClipboardFileSource(
            displayName = name,
            size = length,
            isDirectory = false,
            exists = true,
            unreadable = !readable,
            localPath = absPath,
            streamProvider = if (readable) { { resolved.inputStream() } } else null,
        )
    }

    private fun extractDocumentId(uri: Uri): String? {
        val fromContract = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (!fromContract.isNullOrBlank()) return fromContract
        val segments = runCatching { uri.pathSegments }.getOrNull().orEmpty()
        val docIdx = segments.indexOf("document")
        if (docIdx >= 0 && docIdx + 1 < segments.size) {
            return Uri.decode(segments[docIdx + 1])
        }
        val treeIdx = segments.indexOf("tree")
        if (treeIdx >= 0 && treeIdx + 1 < segments.size) {
            return Uri.decode(segments[treeIdx + 1])
        }
        return null
    }

    private fun queryMediaStoreFileById(numericId: Long): Pair<String?, String?> {
        val resolver = runCatching { Env.app.contentResolver }.getOrNull() ?: return null to null
        val mediaUri = runCatching {
            android.provider.MediaStore.Files.getContentUri("external", numericId)
        }.getOrNull() ?: return null to null
        var dataPath: String? = null
        var name: String? = null
        runCatching {
            resolver.query(
                mediaUri,
                arrayOf("_data", OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex("_data")
                    if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                        dataPath = cursor.getString(dataIdx)
                    }
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                }
            }
        }
        return dataPath to name
    }

    private fun resolveDocumentUriToLocalFile(uri: Uri, hintDisplayName: String? = null): File? {
        return runCatching {
            val resolver = runCatching { Env.app.contentResolver }.getOrNull()
            // 1. Try querying `_data` directly from the URI (works for MediaStore & many providers)
            if (resolver != null) {
                var directDataPath: String? = null
                runCatching {
                    resolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex("_data")
                            if (idx >= 0 && !cursor.isNull(idx)) {
                                directDataPath = cursor.getString(idx)
                            }
                        }
                    }
                }
                if (!directDataPath.isNullOrBlank() && directDataPath!!.startsWith("/")) {
                    val candidate = CloudClipboardFiles.resolveExistingLocalFile(File(directDataPath!!))
                    if (candidate.exists()) return@runCatching candidate
                }
            }

            // 2. Check MediaStore numeric IDs for DownloadsProvider (`msf:123`, `123`) and MediaDocumentsProvider (`image:123`, etc.)
            var effectiveName = hintDisplayName
            val docId = extractDocumentId(uri)
            val authority = uri.authority.orEmpty().lowercase()
            if (!docId.isNullOrBlank() &&
                (authority == "com.android.providers.downloads.documents" ||
                    authority == "com.android.providers.media.documents")
            ) {
                val numericId = docId.substringAfter(':', docId).toLongOrNull()
                if (numericId != null) {
                    val (mediaPath, mediaName) = queryMediaStoreFileById(numericId)
                    if (!mediaPath.isNullOrBlank() && mediaPath.startsWith("/")) {
                        val candidate = CloudClipboardFiles.resolveExistingLocalFile(File(mediaPath))
                        if (candidate.exists()) return@runCatching candidate
                    }
                    if (effectiveName.isNullOrBlank() && !mediaName.isNullOrBlank()) {
                        effectiveName = mediaName
                    }
                }
            }

            // 3. Pure string-based Document/FileProvider URI resolution (works without PackageManager queries)
            CloudClipboardFiles.resolveDocumentUriStringToLocalFile(uri.toString(), effectiveName)
        }.getOrNull()
    }

    private fun resolveUriToFileSource(uri: Uri): CloudClipboardFiles.ClipboardFileSource? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "file") {
            val cleaned = CloudClipboardFiles.cleanPath(uri.toString()).ifEmpty { uri.path ?: return null }
            return fileToSource(File(cleaned))
        }
        if (scheme != "content") return null

        val resolver = runCatching { Env.app.contentResolver }.getOrNull() ?: return null
        var displayName: String? = null
        var size = -1L
        var mimeType: String? = runCatching { resolver.getType(uri) }.getOrNull()

        // Query OpenableColumns first (without DocumentsContract.COLUMN_MIME_TYPE, which throws IllegalArgumentException on strict FileProviders).
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        if (mimeType == null) {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                            mimeType = cursor.getString(mimeIdx)
                        }
                    }
                }
            }
        }

        val localFallback = resolveDocumentUriToLocalFile(uri, displayName)
        val localCanonicalPath = localFallback?.let { file ->
            runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        } ?: uri.toString()

        val fallbackName = displayName?.takeIf { it.isNotBlank() }
            ?: localFallback?.name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
            ?: "clipboard.bin"
        val finalName = displayName?.takeIf { it.isNotBlank() } ?: fallbackName

        val rawUriStr = uri.toString()
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR ||
            localFallback?.isDirectory == true ||
            (rawUriStr.contains("/tree/") && !rawUriStr.contains("/document/"))
        ) {
            return CloudClipboardFiles.ClipboardFileSource(
                displayName = finalName,
                size = 0L,
                isDirectory = true,
                exists = true,
                localPath = localCanonicalPath,
            )
        }

        // If localFallback exists and is directly readable, prefer its exact metadata and fast FileInputStream.
        if (localFallback != null && localFallback.exists() && localFallback.isFile && localFallback.canRead()) {
            val localLen = runCatching { localFallback.length() }.getOrDefault(0L)
            val resolvedLen = if (localLen > 0L) localLen else size.coerceAtLeast(0L)
            if (resolvedLen > 0L) {
                return CloudClipboardFiles.ClipboardFileSource(
                    displayName = finalName,
                    size = resolvedLen,
                    isDirectory = false,
                    exists = true,
                    unreadable = false,
                    localPath = localCanonicalPath,
                    streamProvider = {
                        if (localFallback.exists() && localFallback.canRead()) {
                            localFallback.inputStream()
                        } else {
                            resolver.openInputStream(uri) ?: throw IOException("无法读取文件：$finalName")
                        }
                    },
                )
            }
        }

        if (size <= 0L) {
            val pfdLen = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
            if (pfdLen > 0L) size = pfdLen
        }

        if (size <= 0L) {
            val afdLen = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            if (afdLen > 0L) size = afdLen
        }

        if (size <= 0L && localFallback != null && localFallback.exists() && localFallback.isFile) {
            val localLen = runCatching { localFallback.length() }.getOrDefault(-1L)
            if (localLen > 0L) size = localLen
        }

        if (size <= 0L) {
            return spoolContentUriToSource(uri, finalName, localCanonicalPath)
        }

        val canOpenNow = (localFallback != null && localFallback.exists() && localFallback.canRead()) ||
            runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false) ||
            runCatching { resolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)

        return CloudClipboardFiles.ClipboardFileSource(
            displayName = finalName,
            size = size,
            isDirectory = false,
            exists = true,
            unreadable = !canOpenNow,
            localPath = localCanonicalPath,
            streamProvider = if (canOpenNow) {
                {
                    if (localFallback != null && localFallback.exists() && localFallback.canRead()) {
                        localFallback.inputStream()
                    } else {
                        resolver.openInputStream(uri) ?: throw IOException("无法读取文件：$finalName")
                    }
                }
            } else null,
        )
    }

    private fun spoolContentUriToSource(
        uri: Uri,
        displayName: String,
        dedupKey: String,
    ): CloudClipboardFiles.ClipboardFileSource {
        val resolver = runCatching { Env.app.contentResolver }.getOrNull()
            ?: return CloudClipboardFiles.ClipboardFileSource(displayName = displayName, size = 0L, unreadable = true, localPath = dedupKey)
        return try {
            val dir = File(Env.app.cacheDir, "cloud_clip_spool").apply { mkdirs() }
            // Clean up stale spool files older than 10 minutes
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > 10 * 60_000L) {
                    runCatching { f.delete() }
                }
            }
            val cleanName = CloudClipboardProtocol.normalizeFileName(displayName)
            val keyHash = Integer.toHexString(dedupKey.hashCode())
            val tempFile = File(dir, "spool_${keyHash}_$cleanName")
            if (tempFile.exists() && tempFile.length() > 0L && now - tempFile.lastModified() < 5_000L) {
                val cachedLen = tempFile.length()
                return CloudClipboardFiles.ClipboardFileSource(
                    displayName = displayName,
                    size = cachedLen,
                    exists = true,
                    localPath = dedupKey,
                    streamProvider = { tempFile.inputStream() },
                )
            }

            val input = resolver.openInputStream(uri)
                ?: return CloudClipboardFiles.ClipboardFileSource(displayName = displayName, size = 0L, exists = false, localPath = dedupKey)
            input.use { stream ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                FileOutputStream(tempFile).use { out ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        total += n
                        if (total > CloudClipboardProtocol.MAX_FILE_BYTES) {
                            break
                        }
                        out.write(buffer, 0, n)
                    }
                }
                if (total <= 0L || total > CloudClipboardProtocol.MAX_FILE_BYTES) {
                    runCatching { tempFile.delete() }
                    return CloudClipboardFiles.ClipboardFileSource(
                        displayName = displayName,
                        size = total,
                        exists = true,
                        localPath = dedupKey,
                    )
                }
                CloudClipboardFiles.ClipboardFileSource(
                    displayName = displayName,
                    size = total,
                    exists = true,
                    localPath = dedupKey,
                    streamProvider = { tempFile.inputStream() },
                )
            }
        } catch (_: FileNotFoundException) {
            CloudClipboardFiles.ClipboardFileSource(displayName = displayName, size = 0L, exists = false, localPath = dedupKey)
        } catch (_: SecurityException) {
            CloudClipboardFiles.ClipboardFileSource(displayName = displayName, size = 0L, unreadable = true, localPath = dedupKey)
        } catch (_: IOException) {
            CloudClipboardFiles.ClipboardFileSource(displayName = displayName, size = 0L, unreadable = true, localPath = dedupKey)
        }
    }

    private fun refreshFileSource(src: CloudClipboardFiles.ClipboardFileSource): CloudClipboardFiles.ClipboardFileSource {
        if (!src.unreadable && src.exists && src.size > 0L && src.streamProvider != null) return src
        val path = src.localPath?.trim().orEmpty()
        if (path.isEmpty()) return src
        return if (path.startsWith("content://", ignoreCase = true) || path.startsWith("file://", ignoreCase = true)) {
            runCatching { resolveUriToFileSource(Uri.parse(path)) }.getOrNull() ?: src
        } else {
            fileToSource(File(path))
        }
    }

    fun shortcutOf(item: ItemConfig): CloudClipboardShortcut =
        CloudClipboardShortcut.parse(item.opt("shortcut", CloudClipboardShortcut.DEFAULT.serialize()))

    fun isShortcutEnabled(item: ItemConfig): Boolean =
        item.optBool("shortcutEnabled", true)

    fun activeConfig(): ItemConfig? {
        val cfg = runCatching { Store.config.value }.getOrNull() ?: return null
        return cfg.items.firstOrNull { it.type == type && it.section != Section.OFF }
            ?: cfg.items.firstOrNull { it.type == type }
    }

    /**
     * Checks if [event] matches the configured Cloud Clipboard shortcut.
     * Returns true if matched and triggers selection capture + cloud upload.
     */
    fun matchesShortcut(event: KeyEvent): Boolean {
        val item = activeConfig() ?: return false
        if (!isShortcutEnabled(item)) return false
        val shortcut = shortcutOf(item)
        return shortcut.matches(event)
    }

    fun pushCapturedClip(snapshot: LocalClipboardSnapshot, fromShortcut: Boolean = false) {
        if (snapshot.hasFiles) {
            pushCapturedFiles(snapshot.files, fromShortcut = fromShortcut)
        } else {
            pushCapturedText(snapshot.text, fromShortcut = fromShortcut)
        }
    }

    fun pushCapturedInput(
        rawText: String?,
        stagedFiles: List<CloudClipboardFiles.ClipboardFileSource> = emptyList(),
        fromShortcut: Boolean = false,
    ) {
        if (stagedFiles.isNotEmpty()) {
            pushCapturedFiles(stagedFiles, fromShortcut = fromShortcut)
            return
        }
        val textFiles = extractFileSourcesFromText(rawText)
        if (textFiles.isNotEmpty()) {
            pushCapturedFiles(textFiles, fromShortcut = fromShortcut)
            return
        }
        pushCapturedText(rawText, fromShortcut = fromShortcut)
    }

    fun pushCapturedFiles(
        sources: List<CloudClipboardFiles.ClipboardFileSource>,
        fromShortcut: Boolean = false,
    ) {
        ensureInitialized()
        if (syncingState.value) {
            val msg = "正在上传文件，请稍候或先取消当前上传。"
            statusMessageState.value = msg
            if (fromShortcut) toastOnMain(msg)
            Ticker.refresh(type)
            return
        }

        val id = identityState.value
        if (!id.isRegistered) {
            val msg = "云剪切板尚未注册设备，请在云剪切板设置中配置"
            errorMessageState.value = msg
            if (fromShortcut) toastOnMain(msg)
            Ticker.refresh(type)
            return
        }

        val refreshedSources = sources.map { refreshFileSource(it) }
        val plan = CloudClipboardFiles.planSources(refreshedSources)
        val skips = plan.skipSummary()
        val hadPermissionOrMissingFailure = refreshedSources.any { it.unreadable || !it.exists }
        if (plan.accepted.isEmpty()) {
            if (!hasAllFilesAccess() && (hadPermissionOrMissingFailure || sources.isNotEmpty())) {
                val permMsg = "缺少文件读取权限，已为您打开「所有文件访问权限」设置，开启后即可上传文件。"
                errorMessageState.value = permMsg
                statusMessageState.value = null
                toastOnMain("请开启 BentoBar 的「所有文件访问权限」以读取并上传复制的文件")
                requestFileAccessPermission()
                Ticker.refresh(type)
                return
            }
            val msg = if (skips.isNotBlank()) "没有可上传的文件。 $skips" else "没有可上传的文件。"
            errorMessageState.value = msg
            statusMessageState.value = null
            if (fromShortcut) toastOnMain(msg)
            Ticker.refresh(type)
            return
        }

        val totalBytes = plan.accepted.sumOf { it.size.coerceAtLeast(0L) }
        val cancelFlag = AtomicBoolean(false)
        currentUploadCancel = cancelFlag
        syncingState.value = true
        errorMessageState.value = null
        val first = plan.accepted.first()
        uploadProgressState.value = UploadProgressState(
            fileIndex = 0,
            fileCount = plan.accepted.size,
            fileName = first.name,
            fileSent = 0L,
            fileSize = first.size,
            sentOverall = 0L,
            totalBytes = totalBytes,
            reading = true,
            canceling = false,
        )
        statusMessageState.value = "正在上传文件 (1/${plan.accepted.size})：${first.name}…"
        if (fromShortcut) {
            toastOnMain("开始上传文件到 VPS 面板：${first.name}")
        }
        Ticker.refresh(type)

        executor.execute {
            var done = 0
            var completedBytes = 0L
            var canceled = false
            var error: String? = null
            var needPermPrompt = false
            try {
                for ((index, fileItem) in plan.accepted.withIndex()) {
                    if (cancelFlag.get()) {
                        canceled = true
                        break
                    }
                    uploadProgressState.value = UploadProgressState(
                        fileIndex = index,
                        fileCount = plan.accepted.size,
                        fileName = fileItem.name,
                        fileSent = 0L,
                        fileSize = fileItem.size,
                        sentOverall = completedBytes,
                        totalBytes = totalBytes,
                        reading = true,
                        canceling = cancelFlag.get(),
                    )
                    statusMessageState.value = "正在上传 (${index + 1}/${plan.accepted.size})：${fileItem.name}"
                    Ticker.refresh(type)

                    var lastTickMs = 0L
                    val created = CloudClipboardClient.pushFile(
                        identity = id,
                        fileName = fileItem.name,
                        size = fileItem.size,
                        streamProvider = fileItem.streamProvider,
                        onProgress = { sent ->
                            val nowMs = System.currentTimeMillis()
                            if (sent >= fileItem.size || nowMs - lastTickMs >= 100L) {
                                lastTickMs = nowMs
                                uploadProgressState.value = UploadProgressState(
                                    fileIndex = index,
                                    fileCount = plan.accepted.size,
                                    fileName = fileItem.name,
                                    fileSent = sent,
                                    fileSize = fileItem.size,
                                    sentOverall = completedBytes + sent.coerceIn(0L, fileItem.size),
                                    totalBytes = totalBytes,
                                    reading = false,
                                    canceling = cancelFlag.get(),
                                )
                            }
                        },
                        isCancelled = { cancelFlag.get() },
                    )
                    done++
                    completedBytes += fileItem.size
                    val updated = CloudClipboardStore.mergeEntries(listOf(created), entriesState.value)
                    entriesState.value = updated
                    CloudClipboardStore.saveHistory(updated)
                }
            } catch (_: InterruptedIOException) {
                canceled = true
            } catch (e: Exception) {
                if (cancelFlag.get()) {
                    canceled = true
                } else {
                    val msg = e.message ?: "文件上传失败。"
                    if (!hasAllFilesAccess() && (e is SecurityException || msg.contains("权限") || msg.contains("无法读取"))) {
                        needPermPrompt = true
                        error = "缺少文件读取权限，请在弹出的系统设置中开启「所有文件访问权限」后重试。"
                    } else {
                        error = msg
                    }
                }
            } finally {
                currentUploadCancel = null
                uploadProgressState.value = null
                syncingState.value = false
                runCatching {
                    File(Env.app.cacheDir, "cloud_clip_spool").listFiles()?.forEach { it.delete() }
                }
                if (needPermPrompt) {
                    mainHandler.post {
                        toastOnMain("请开启 BentoBar 的「所有文件访问权限」以读取并上传文件")
                        requestFileAccessPermission()
                    }
                }
                val finalMessage = CloudClipboardFiles.uploadResultMessage(
                    done = done,
                    total = plan.accepted.size,
                    canceled = canceled,
                    error = error,
                    skips = skips,
                )
                if (error != null && !canceled) {
                    errorMessageState.value = finalMessage
                    statusMessageState.value = null
                } else {
                    statusMessageState.value = finalMessage
                    errorMessageState.value = null
                }
                if (fromShortcut && finalMessage.isNotBlank()) {
                    toastOnMain(finalMessage)
                }
                Ticker.refresh(type)
            }
        }
    }

    fun pushCapturedText(rawText: String?, fromShortcut: Boolean = false) {
        ensureInitialized()
        val cleaned = rawText?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            val msg = "未检测到选中文本或剪贴板内容"
            errorMessageState.value = msg
            if (fromShortcut) toastOnMain(msg)
            Ticker.refresh(type)
            return
        }

        val textFiles = extractFileSourcesFromText(cleaned)
        if (textFiles.isNotEmpty()) {
            pushCapturedFiles(textFiles, fromShortcut = fromShortcut)
            return
        }

        runCatching { Env.copy(cleaned) }
        localClipState.value = cleaned

        val id = identityState.value
        if (!id.isRegistered) {
            val msg = "云剪切板尚未注册设备，请在云剪切板设置中配置"
            errorMessageState.value = msg
            if (fromShortcut) toastOnMain(msg)
            Ticker.refresh(type)
            return
        }

        syncingState.value = true
        errorMessageState.value = null
        statusMessageState.value = "正在发送到云剪切板…"
        Ticker.refresh(type)

        executor.execute {
            try {
                val created = CloudClipboardClient.pushText(id, cleaned, id.displayName.ifBlank { null })
                val updated = CloudClipboardStore.mergeEntries(listOf(created), entriesState.value)
                entriesState.value = updated
                CloudClipboardStore.saveHistory(updated)
                val preview = created.preview(28)
                statusMessageState.value = "已发送到云服务器：$preview"
                errorMessageState.value = null
                if (fromShortcut) {
                    toastOnMain("已发送到云剪切板：$preview")
                }
            } catch (e: Exception) {
                val err = e.message ?: "发送失败"
                errorMessageState.value = err
                statusMessageState.value = null
                if (fromShortcut) {
                    toastOnMain(err)
                }
            } finally {
                syncingState.value = false
                Ticker.refresh(type)
            }
        }
    }

    fun refreshFromCloud(quiet: Boolean = false) {
        ensureInitialized()
        val id = identityState.value
        if (!id.isRegistered && id.sessionCookie.isBlank()) {
            if (!quiet) {
                errorMessageState.value = "请先在设置中注册设备或配置面板连接"
            }
            return
        }
        if (syncingState.value) return
        syncingState.value = true
        if (!quiet) {
            errorMessageState.value = null
            statusMessageState.value = "正在同步云端剪切板…"
        }
        Ticker.refresh(type)

        executor.execute {
            try {
                val remoteItems = CloudClipboardClient.fetchItems(id)
                val localOnly = entriesState.value.filter { it.id.isBlank() || it.id.startsWith("local-") }
                val merged = CloudClipboardStore.mergeEntries(remoteItems, localOnly)
                entriesState.value = merged
                CloudClipboardStore.saveHistory(merged)
                errorMessageState.value = null
                if (!quiet) {
                    statusMessageState.value = if (remoteItems.isEmpty()) {
                        "云端暂无剪切板内容"
                    } else {
                        "已同步 ${remoteItems.size} 条云端内容"
                    }
                }
            } catch (e: Exception) {
                if (!quiet) {
                    errorMessageState.value = e.message ?: "拉取云剪切板失败"
                    statusMessageState.value = null
                }
            } finally {
                syncingState.value = false
                Ticker.refresh(type)
            }
        }
    }

    fun deleteCloudEntry(entry: CloudClipboardEntry) {
        ensureInitialized()
        val updatedLocal = entriesState.value.filterNot {
            (entry.id.isNotBlank() && it.id == entry.id) || (!entry.isFile && it.text == entry.text)
        }
        entriesState.value = updatedLocal
        CloudClipboardStore.saveHistory(updatedLocal)
        Ticker.refresh(type)

        val id = identityState.value
        if (entry.id.isBlank() || entry.id.startsWith("local-") || (!id.isRegistered && id.sessionCookie.isBlank())) {
            return
        }
        executor.execute {
            runCatching {
                CloudClipboardClient.deleteItem(id, entry.id)
            }
        }
    }

    fun enrollDevice(
        vpsUrl: String,
        displayName: String,
        registrationCode: String,
        sessionCookie: String,
        onDone: (Result<NetTransferIdentity>) -> Unit = {},
    ) {
        ensureInitialized()
        syncingState.value = true
        errorMessageState.value = null
        statusMessageState.value = "正在向面板注册设备…"
        Ticker.refresh(type)

        executor.execute {
            val result = runCatching {
                val enrolled = CloudClipboardClient.enroll(vpsUrl, displayName, registrationCode, sessionCookie)
                CloudClipboardStore.saveIdentity(enrolled)
                identityState.value = enrolled
                enrolled
            }
            syncingState.value = false
            result.onSuccess {
                statusMessageState.value = "设备已注册 (${it.displayName})"
                errorMessageState.value = null
            }.onFailure { e ->
                errorMessageState.value = e.message ?: "注册失败"
                statusMessageState.value = null
            }
            Ticker.refresh(type)
            mainHandler.post { onDone(result) }
        }
    }

    private fun toastOnMain(message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(Env.app, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun receiveEntry(entry: CloudClipboardEntry, paste: Boolean, host: MenuHost) {
        val contentToCopy = if (entry.isFile) {
            val baseUrl = identityState.value.vpsUrl.trimEnd('/')
            when {
                entry.url.startsWith("http://") || entry.url.startsWith("https://") -> entry.url
                entry.url.startsWith("/") -> "$baseUrl${entry.url}"
                entry.fileId.isNotBlank() -> "$baseUrl/api/clipboard/files/${entry.fileId}"
                else -> entry.text.ifBlank { entry.fileName }
            }
        } else {
            entry.text
        }
        if (contentToCopy.isBlank()) return
        Env.copy(contentToCopy)
        localClipState.value = contentToCopy
        statusMessageState.value = "已复制到本机剪切板：${entry.preview(24)}"
        errorMessageState.value = null
        Ticker.refresh(type)

        if (paste) {
            host.afterClose {
                runCatching {
                    val node = Env.service?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (node != null) {
                        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contentToCopy)
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }
                }
            }
        } else {
            toastOnMain("已复制到本机剪切板")
            host.close()
        }
    }

    override fun state(item: ItemConfig): ItemState {
        ensureInitialized()
        val id = identityState.value
        val entries = entriesState.value
        val syncing = syncingState.value
        val error = errorMessageState.value
        val shortcut = shortcutOf(item)
        val shortcutText = if (isShortcutEnabled(item)) shortcut.format() else "未启用"

        val iconSym = "brand:cloud_clipboard"

        val statusSummary = when {
            syncing -> "正在同步…"
            error != null -> error
            id.isRegistered -> "已连接 (${id.displayName.ifBlank { id.deviceId }}) · ${entries.size} 条记录"
            else -> "未注册设备"
        }

        return ItemState(
            icon = iconSym,
            filled = id.isRegistered || entries.isNotEmpty(),
            text = null,
            active = id.isRegistered || entries.isNotEmpty(),
            tone = if (error != null) Tone.WARN else Tone.NORMAL,
            desc = "云剪切板: $statusSummary",
            tooltip = "云剪切板 · $statusSummary · 快捷键 $shortcutText",
            textLimit = 0,
            widthKey = null,
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        ensureInitialized()
        val id by identityState.collectAsState()
        val entries by entriesState.collectAsState()
        val localClip by localClipState.collectAsState()
        val localClipFiles by localClipFilesState.collectAsState()
        val syncing by syncingState.collectAsState()
        val uploadProgress by uploadProgressState.collectAsState()
        val statusMsg by statusMessageState.collectAsState()
        val errorMsg by errorMessageState.collectAsState()

        val pasteOnClick = item.optBool("pasteOnClick", false)
        val shortcut = remember(item) { shortcutOf(item) }
        val shortcutEnabled = remember(item) { isShortcutEnabled(item) }

        var quickText by remember { mutableStateOf("") }
        var stagedFiles by remember { mutableStateOf<List<CloudClipboardFiles.ClipboardFileSource>>(emptyList()) }
        var userEditedInput by remember { mutableStateOf(false) }
        val inputFocus = remember { FocusRequester() }
        var inputFocused by remember { mutableStateOf(false) }

        fun stageFilesIntoInput(files: List<CloudClipboardFiles.ClipboardFileSource>) {
            stagedFiles = files
            quickText = formatFilesPreview(files)
        }

        LaunchedEffect(Unit) {
            runCatching { inputFocus.requestFocus() }
            val snap1 = readLocalClipboardSnapshot()
            if (snap1.hasFiles && !userEditedInput && stagedFiles.isEmpty() && quickText.isEmpty()) {
                stageFilesIntoInput(snap1.files)
            }
            delay(100)
            val snap2 = readLocalClipboardSnapshot()
            if (snap2.hasFiles && !userEditedInput && stagedFiles.isEmpty() && quickText.isEmpty()) {
                stageFilesIntoInput(snap2.files)
            }
            if (id.isRegistered || id.sessionCookie.isNotBlank()) {
                refreshFromCloud(quiet = true)
            }
        }

        val hostView = LocalView.current
        DisposableEffect(hostView) {
            val dragListener = View.OnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DROP -> {
                        val dropSnap = parseClipDataSnapshot(event.clipData, updateState = true)
                        if (dropSnap.hasFiles) {
                            stageFilesIntoInput(dropSnap.files)
                            true
                        } else if (dropSnap.hasText) {
                            stagedFiles = emptyList()
                            quickText = dropSnap.text.orEmpty()
                            true
                        } else {
                            false
                        }
                    }
                    else -> true
                }
            }
            runCatching { hostView.setOnDragListener(dragListener) }
            onDispose {
                runCatching { hostView.setOnDragListener(null) }
            }
        }

        val baseClipboardManager = LocalClipboardManager.current
        val fileAwareClipboardManager = remember(baseClipboardManager) {
            object : androidx.compose.ui.platform.ClipboardManager {
                override fun setText(annotatedString: AnnotatedString) {
                    baseClipboardManager.setText(annotatedString)
                }

                override fun getText(): AnnotatedString? {
                    val snap = readLocalClipboardSnapshot()
                    if (snap.hasFiles) {
                        stageFilesIntoInput(snap.files)
                        return AnnotatedString(formatFilesPreview(snap.files))
                    }
                    if (snap.hasText) {
                        stagedFiles = emptyList()
                        return AnnotatedString(snap.text.orEmpty())
                    }
                    return baseClipboardManager.getText()
                }

                override fun hasText(): Boolean {
                    if (baseClipboardManager.hasText()) return true
                    if (localClipFilesState.value.isNotEmpty()) return true
                    val snap = readLocalClipboardSnapshot()
                    return !snap.isEmpty
                }
            }
        }

        fun pasteClipboardIntoInput(): Boolean {
            val snap = readLocalClipboardSnapshot()
            if (snap.hasFiles) {
                stageFilesIntoInput(snap.files)
                return true
            }
            if (snap.hasText) {
                stagedFiles = emptyList()
                quickText = snap.text.orEmpty()
                return true
            }
            return false
        }

        fun submitInput() {
            if (syncing) return
            if (stagedFiles.isNotEmpty()) {
                val toSend = stagedFiles
                stagedFiles = emptyList()
                quickText = ""
                pushCapturedFiles(toSend, fromShortcut = false)
                return
            }
            val target = quickText.trim()
            if (target.isNotEmpty()) {
                quickText = ""
                pushCapturedInput(target, emptyList(), fromShortcut = false)
                return
            }
            val snap = readLocalClipboardSnapshot()
            if (snap.hasFiles) {
                pushCapturedFiles(snap.files, fromShortcut = false)
            } else if (localClipFiles.isNotEmpty()) {
                pushCapturedFiles(localClipFiles, fromShortcut = false)
            }
        }

        val subtitle = when {
            uploadProgress != null -> "正在上传 ${uploadProgress!!.fileName} (${uploadProgress!!.percent}%)"
            syncing -> "正在与云服务器同步…"
            id.isRegistered -> buildString {
                append("已连接 · ")
                append(id.displayName.ifBlank { id.deviceId })
                if (shortcutEnabled) {
                    append(" · 快捷键 ")
                    append(shortcut.format())
                }
            }
            else -> "未注册设备 · 点击下方「云剪切板设置」配置连接"
        }

        val hasFileAccess = hasAllFilesAccess()

        MenuCard(
            icon = "brand:cloud_clipboard",
            title = stringResource(R.string.item_cloud_clipboard_title),
            subtitle = subtitle,
            subtitleColor = if (errorMsg != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            trailing = {
                SmallIconButton(
                    sym = Sym.REFRESH,
                    label = "刷新云端剪切板",
                    enabled = !syncing,
                ) {
                    refreshFromCloud(quiet = false)
                }
            },
        ) {
            if (!hasFileAccess) {
                MenuEntry(
                    icon = Sym.LOCK_OPEN,
                    label = "开启文件读取权限（上传文件必选）",
                    sub = "当前未授予「所有文件访问权限」，点击打开系统设置一键授权",
                ) {
                    host.close()
                    requestFileAccessPermission()
                }
                MenuDivider()
            }

            SectionLabel("快捷发送文字 / 文件")

            val canSubmitInput = !syncing && (stagedFiles.isNotEmpty() || quickText.isNotBlank() || localClipFiles.isNotEmpty())
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompositionLocalProvider(LocalClipboardManager provides fileAwareClipboardManager) {
                        OutlinedTextField(
                            value = quickText,
                            onValueChange = { next ->
                                userEditedInput = true
                                val expectedPreview = if (stagedFiles.isNotEmpty()) formatFilesPreview(stagedFiles) else ""
                                if (stagedFiles.isNotEmpty() && next != expectedPreview) {
                                    // Check if the new text came from a context-menu Paste that appended or replaced the preview
                                    val snap = localClipFilesState.value
                                    val snapPreview = if (snap.isNotEmpty()) formatFilesPreview(snap) else ""
                                    if (snap.isNotEmpty() && snapPreview.isNotEmpty() && next.contains(snapPreview)) {
                                        stagedFiles = snap
                                        quickText = snapPreview
                                        return@OutlinedTextField
                                    }
                                    stagedFiles = emptyList()
                                }
                                quickText = next
                            },
                            label = {
                                Text(
                                    if (stagedFiles.isNotEmpty()) {
                                        "已选复制的文件 (${stagedFiles.size} 个)，回车或点发送上传"
                                    } else {
                                        "发送文字或粘贴复制的文件到云服务器"
                                    }
                                )
                            },
                            placeholder = {
                                Text(
                                    if (id.isRegistered) {
                                        "输入内容，或按 Ctrl+V / 点右侧粘贴图标粘入复制的文件"
                                    } else {
                                        "请先在设置中完成设备注册"
                                    }
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (quickText.isNotEmpty() || stagedFiles.isNotEmpty()) {
                                        SmallIconButton(
                                            sym = Sym.CLOSE,
                                            label = "清空输入框",
                                        ) {
                                            userEditedInput = true
                                            stagedFiles = emptyList()
                                            quickText = ""
                                        }
                                    }
                                    SmallIconButton(
                                        sym = Sym.CONTENT_PASTE,
                                        label = "粘贴剪贴板文本或文件到输入框",
                                    ) {
                                        pasteClipboardIntoInput()
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { submitInput() },
                                onDone = { submitInput() },
                                onGo = { submitInput() },
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(inputFocus)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .onPreviewKeyEvent { e ->
                                    val isPasteKey = ((e.isCtrlPressed || e.isMetaPressed) && !e.isAltPressed && e.key == Key.V) ||
                                        (e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed && e.key == Key.Insert) ||
                                        e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_PASTE
                                    if (isPasteKey) {
                                        if (e.type == KeyEventType.KeyDown) {
                                            val snap = readLocalClipboardSnapshot()
                                            if (snap.hasFiles) {
                                                stageFilesIntoInput(snap.files)
                                                return@onPreviewKeyEvent true
                                            }
                                            if (stagedFiles.isNotEmpty()) {
                                                stagedFiles = emptyList()
                                                quickText = ""
                                            }
                                        }
                                        return@onPreviewKeyEvent false
                                    }
                                    if (inputFocused && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && canSubmitInput) {
                                            submitInput()
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                    FilledTonalButton(
                        onClick = { submitInput() },
                        enabled = canSubmitInput,
                    ) {
                        Text("发送", maxLines = 1)
                    }
                }
            }

            uploadProgress?.let { progress ->
                val detailText = if (progress.reading) {
                    "第 ${progress.fileIndex + 1} 个，共 ${progress.fileCount} 个 · ${CloudClipboardFiles.formatBytes(progress.fileSize)} · 正在读取…"
                } else {
                    "第 ${progress.fileIndex + 1} 个，共 ${progress.fileCount} 个 · ${CloudClipboardFiles.formatBytes(progress.fileSent)} / ${CloudClipboardFiles.formatBytes(progress.fileSize)} (${progress.percent}%)"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = progress.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { cancelCurrentUpload() },
                            enabled = !progress.canceling,
                        ) {
                            Text(
                                text = if (progress.canceling) "取消中" else "取消",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Meter(progress.fraction)
                }
            }

            val clipPreview = when {
                localClipFiles.isNotEmpty() -> formatFilesPreview(localClipFiles)
                localClip.isNotBlank() -> ClipboardRules.preview(localClip, 36)
                else -> null
            }
            MenuEntry(
                icon = Sym.SEND,
                label = if (localClipFiles.isNotEmpty()) "发送本机当前复制的文件" else "发送本机当前剪切板",
                sub = clipPreview ?: "读取本机当前复制的文本或文件并上传到云服务器",
                enabled = !syncing,
            ) {
                val latestSnap = readLocalClipboardSnapshot()
                if (!latestSnap.isEmpty) {
                    pushCapturedClip(latestSnap, fromShortcut = false)
                } else if (localClipFiles.isNotEmpty()) {
                    pushCapturedFiles(localClipFiles, fromShortcut = false)
                } else {
                    pushCapturedText(localClip, fromShortcut = false)
                }
            }

            if (errorMsg != null) {
                MenuNote(errorMsg!!)
            } else if (statusMsg != null) {
                MenuNote(statusMsg!!)
            }

            MenuDivider()
            SectionLabel("云端剪切板内容 (${entries.size})")

            if (entries.isNotEmpty()) {
                val latest = entries.first()
                MenuEntry(
                    icon = Sym.ARROW_DOWNWARD,
                    label = if (pasteOnClick) "快捷接收并粘贴最新云端内容" else "快捷接收最新云端内容",
                    sub = latest.preview(42),
                ) {
                    receiveEntry(latest, pasteOnClick, host)
                }

                entries.take(8).forEach { entry ->
                    val metaParts = mutableListOf<String>()
                    if (entry.deviceName.isNotBlank()) metaParts += entry.deviceName
                    if (entry.createdAt.isNotBlank()) {
                        metaParts += entry.createdAt.replace('T', ' ').take(19)
                    }
                    val subLine = metaParts.joinToString(" · ").takeIf { it.isNotEmpty() }
                    MenuEntry(
                        icon = if (entry.isFile) Sym.LINK else Sym.CONTENT_COPY,
                        label = entry.preview(42),
                        sub = subLine,
                        trailing = {
                            SmallIconButton(Sym.DELETE, "删除此条目") {
                                deleteCloudEntry(entry)
                            }
                        },
                    ) {
                        receiveEntry(entry, pasteOnClick, host)
                    }
                }
            } else {
                MenuNote(
                    if (id.isRegistered) {
                        "暂无云端记录。可按 ${shortcut.format()} 一键复制所选文本或文件上传，或点击右上角刷新。"
                    } else {
                        "尚未连接云服务器。请打开下方「云剪切板设置」填写面板地址与一次性注册码。"
                    }
                )
            }

            MenuDivider()
            MenuEntry(
                icon = Sym.REFRESH,
                label = "从云服务器刷新剪切板",
                detail = if (shortcutEnabled) shortcut.format() else null,
                enabled = !syncing,
            ) {
                refreshFromCloud(quiet = false)
            }
            MenuEntry(
                icon = Sym.OPEN_IN_NEW,
                label = "打开云剪切板网页端",
            ) {
                host.close()
                val targetUrl = id.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }
                Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            }
            MenuEntry(
                icon = Sym.SETTINGS,
                label = stringResource(R.string.context_item_settings),
            ) {
                host.openItemSettings(item.id)
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        rememberTick()
        ensureInitialized()
        val id by identityState.collectAsState()
        val syncing by syncingState.collectAsState()
        val statusMsg by statusMessageState.collectAsState()
        val errorMsg by errorMessageState.collectAsState()
        val hasFileAccess = hasAllFilesAccess()

        val shortcutEnabled = isShortcutEnabled(item)
        val currentShortcut = shortcutOf(item)
        var recordingShortcut by remember { mutableStateOf(false) }
        val captureFocus = remember { FocusRequester() }

        LaunchedEffect(recordingShortcut) {
            if (recordingShortcut) {
                runCatching { captureFocus.requestFocus() }
            }
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("文件读取权限 (复制本地文件上传)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (hasFileAccess) MaterialTheme.colorScheme.surfaceContainerLow
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (hasFileAccess) "已授予「所有文件访问权限」" else "尚未授予「所有文件访问权限」",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (hasFileAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = if (hasFileAccess) {
                            "可直接粘贴或使用快捷键上传文件管理器中复制的任意文件"
                        } else {
                            "上传本机复制的文件需要读取存储权限，请点击右侧按钮开启"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (hasFileAccess) {
                    OutlinedButton(onClick = { requestFileAccessPermission() }) {
                        Text("权限设置")
                    }
                } else {
                    FilledTonalButton(onClick = { requestFileAccessPermission() }) {
                        Text("开启文件权限")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("快捷键一键复制并上传", style = MaterialTheme.typography.labelLarge)
            SwitchRow(
                label = "启用全局快捷键一键复制到云服务器",
                checked = shortcutEnabled,
                help = "选中文字或复制文件后按下快捷键，自动上传到云剪切板",
            ) { enabled ->
                set(item.with("shortcutEnabled", if (enabled) null else "false"))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (recordingShortcut) 2.dp else 1.dp,
                        color = if (recordingShortcut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .focusRequester(captureFocus)
                    .onFocusChanged { if (!it.isFocused) recordingShortcut = false }
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!recordingShortcut) return@onPreviewKeyEvent false
                        val native = keyEvent.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            if (native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                                recordingShortcut = false
                                return@onPreviewKeyEvent true
                            }
                            val captured = CloudClipboardShortcut.fromKeyEvent(native)
                            if (captured != null) {
                                set(item.with("shortcut", captured.serialize()))
                                recordingShortcut = false
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .clickable { recordingShortcut = !recordingShortcut }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (recordingShortcut) "请按下组合键 (如 Ctrl + Alt + C，按 Esc 取消)…" else "当前快捷键：${currentShortcut.format()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (recordingShortcut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "点击此处可直接按键录制自定义快捷键，或在下方选择常用预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { recordingShortcut = !recordingShortcut }) {
                    Text(if (recordingShortcut) "取消录制" else "录制快捷键")
                }
            }

            val presetLabels = CloudClipboardShortcut.PRESETS.map { it.format() }
            val selectedPresetIndex = CloudClipboardShortcut.PRESETS.indexOfFirst { it == currentShortcut }.takeIf { it >= 0 }
            ChipRow(
                labels = presetLabels,
                selected = selectedPresetIndex,
            ) { idx ->
                val chosen = CloudClipboardShortcut.PRESETS[idx]
                set(item.with("shortcut", chosen.serialize().takeIf { it != CloudClipboardShortcut.DEFAULT.serialize() }))
            }
        }

        SwitchRow(
            label = stringResource(R.string.clipboard_paste_on_click),
            checked = item.optBool("pasteOnClick", false),
            help = stringResource(R.string.clipboard_paste_on_click_help),
        ) { paste ->
            set(item.with("pasteOnClick", if (paste) "true" else null))
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("云服务器连接与设备注册 (NetTransfer)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            InfoRow(
                label = "当前连接状态",
                value = if (id.isRegistered) "已注册 (${id.displayName} · ID ${id.deviceId.take(10)})" else "未注册设备",
                valueColor = if (id.isRegistered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var vpsUrlInput by remember(id.vpsUrl) { mutableStateOf(id.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }) }
            var displayNameInput by remember(id.displayName) { mutableStateOf(id.displayName) }
            var sessionCookieInput by remember(id.sessionCookie) { mutableStateOf(id.sessionCookie) }
            var enrollCodeInput by remember { mutableStateOf("") }

            TextRow(
                label = "云服务器面板地址 (VPS URL)",
                value = vpsUrlInput,
                placeholder = CloudClipboardProtocol.DEFAULT_VPS_URL,
                help = "默认 https://vps.why.band，支持自动分片握手连接",
            ) { next ->
                vpsUrlInput = next
                val updated = id.copy(vpsUrl = next.trim().trimEnd('/'))
                identityState.value = updated
                CloudClipboardStore.saveIdentity(updated)
            }

            TextRow(
                label = "本机设备显示名称",
                value = displayNameInput,
                placeholder = "Googlebook",
                help = "发送云剪切板时显示的来源设备名",
            ) { next ->
                displayNameInput = next
                val updated = id.copy(displayName = next.trim())
                identityState.value = updated
                CloudClipboardStore.saveIdentity(updated)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = enrollCodeInput,
                    onValueChange = { enrollCodeInput = it },
                    label = { Text("一次性注册码 (面板「设备」页生成)") },
                    placeholder = { Text("输入注册码以生成 Ed25519 密钥并绑定设备") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    enabled = !syncing && enrollCodeInput.isNotBlank(),
                    onClick = {
                        enrollDevice(
                            vpsUrl = vpsUrlInput,
                            displayName = displayNameInput,
                            registrationCode = enrollCodeInput,
                            sessionCookie = sessionCookieInput,
                        ) { res ->
                            if (res.isSuccess) enrollCodeInput = ""
                        }
                    },
                ) {
                    Text(if (id.isRegistered) "重新注册" else "注册设备")
                }
            }

            TextRow(
                label = "面板 Session Cookie (可选，旧版服务端备用)",
                value = sessionCookieInput,
                placeholder = "nettransfer_session=...",
                help = "新版服务端已支持 Ed25519 签名直接拉取与删除，无需填写；仅在 VPS 尚未更新重启时作为备用",
            ) { next ->
                sessionCookieInput = next
                val updated = id.copy(sessionCookie = next.trim())
                identityState.value = updated
                CloudClipboardStore.saveIdentity(updated)
            }

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else if (statusMsg != null) {
                Text(
                    text = statusMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    enabled = !syncing,
                    onClick = { refreshFromCloud(quiet = false) },
                ) {
                    Text("立即同步云端")
                }
                OutlinedButton(
                    onClick = {
                        val raw = readLocalClipboard() ?: localClipState.value
                        if (raw.isBlank()) {
                            errorMessageState.value = "请先复制 agent.json 内容到本机剪贴板"
                        } else {
                            runCatching {
                                val imported = CloudClipboardStore.importIdentityJson(raw)
                                identityState.value = imported
                                statusMessageState.value = "已导入设备凭证：${imported.displayName} (${imported.deviceId})"
                                errorMessageState.value = null
                                Ticker.refresh(type)
                            }.onFailure { e ->
                                errorMessageState.value = "导入 agent.json 失败：${e.message}"
                            }
                        }
                    },
                ) {
                    Text("从剪贴板导入 agent.json")
                }
                if (id.isRegistered) {
                    TextButton(
                        onClick = {
                            val cleared = NetTransferIdentity(
                                vpsUrl = id.vpsUrl,
                                displayName = id.displayName,
                            )
                            identityState.value = cleared
                            CloudClipboardStore.saveIdentity(cleared)
                            statusMessageState.value = "已清除本机设备注册凭证"
                            Ticker.refresh(type)
                        },
                    ) {
                        Text("清除凭证", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    override fun debug(args: List<String>): String? {
        ensureInitialized()
        return when (args.firstOrNull()) {
            null -> "registered=${identityState.value.isRegistered} items=${entriesState.value.size} syncing=${syncingState.value}"
            "stage" -> {
                val sample = listOf(
                    CloudClipboardEntry(
                        id = "clip-1",
                        kind = "text",
                        text = "ssh root@vps.why.band",
                        deviceName = "Workstation",
                        createdAt = "2026-11-09T10:30:00Z",
                    ),
                    CloudClipboardEntry(
                        id = "clip-2",
                        kind = "text",
                        text = "https://vps.why.band",
                        deviceName = "Googlebook",
                        createdAt = "2026-11-09T10:25:00Z",
                    ),
                )
                entriesState.value = sample
                Ticker.refresh(type)
                "staged 2 cloud clips"
            }
            "clear" -> {
                entriesState.value = emptyList()
                CloudClipboardStore.saveHistory(emptyList())
                Ticker.refresh(type)
                "cleared cloud clips"
            }
            "sync" -> {
                refreshFromCloud(quiet = false)
                "syncing cloud clipboard"
            }
            else -> null
        }
    }
}
