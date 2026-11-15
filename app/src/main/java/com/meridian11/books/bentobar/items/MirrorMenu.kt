package com.meridian11.books.bentobar.items

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlin.math.max

/**
 * The Mirror's popup: the camera's picture, a close button, and words for every state but showing. The camera opens with the
 * popup and closes the moment the popup starts to close (`MenuHost.onClosing`, before the fold), and once more when the
 * content goes; without the camera's permission there is the allow row and no camera at all.
 */
@Composable
internal fun MirrorMenu(item: ItemConfig, host: MenuHost) {
    val setup by Setup.state.collectAsState()
    val staged = MirrorItem.staged
    // A staged state is one with the permission given; "noaccess" is the one without.
    val allowed = (Access.CAMERA in setup.access || staged != null) && !MirrorItem.stagedNoAccess
    val live = allowed && staged == null
    var state by remember { mutableStateOf(MirrorState.OPENING) }
    val holder = remember { MirrorHolder() }
    // Once for this popup: whatever closes it, the camera goes as the closing starts, and no camera opens in it after that.
    DisposableEffect(Unit) {
        host.onClosing { holder.giveUp() }
        onDispose { holder.giveUp() }
    }
    DisposableEffect(live) {
        if (live) holder.open { state = it }
        onDispose { holder.close() }
    }
    val shown = staged ?: state
    MenuCard(Sym.PHOTO_CAMERA_FRONT, stringResource(R.string.item_mirror_title), stringResource(R.string.mirror_private),
        trailing = { SmallIconButton(Sym.CLOSE, stringResource(R.string.mirror_close)) { host.close() } }) {
        when {
            !allowed -> {
                MenuNote(stringResource(Accesses.explain(Access.CAMERA)))
                MenuEntry(Sym.PHOTO_CAMERA_FRONT, stringResource(Accesses.allow(Access.CAMERA))) { host.close(); Accesses.open(Env.app, Access.CAMERA) }
            }
            staged == MirrorState.SHOWING -> Picture { StandIn() }
            shown == MirrorState.OPENING || shown == MirrorState.SHOWING -> Picture {
                val flip = MirrorItem.flip.of(item)
                AndroidView({ MirrorView(it, holder) }, Modifier.fillMaxSize(), update = { it.flip = flip })
                if (shown == MirrorState.OPENING) Text(stringResource(R.string.mirror_opening), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(8.dp))
            }
            else -> {
                MenuNote(stringResource(when (shown) {
                    MirrorState.NO_CAMERA -> R.string.mirror_no_camera
                    MirrorState.REFUSED -> R.string.mirror_refused
                    MirrorState.BUSY -> R.string.mirror_busy
                    else -> R.string.mirror_failed
                }))
                // Refused though the permission is given: where it can be checked (given, it is App info).
                if (shown == MirrorState.REFUSED) MenuEntry(Sym.SETTINGS, stringResource(R.string.mirror_camera_settings)) {
                    host.close(); Accesses.open(Env.app, Access.CAMERA)
                }
            }
        }
    }
}

/** The picture's place: as wide as the card, 16:9, dark until the first frame. */
@Composable
private fun Picture(content: @Composable () -> Unit) {
    val words = stringResource(R.string.mirror_picture)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)
        .semantics { contentDescription = words }, contentAlignment = Alignment.Center) { content() }
}

/** A painted stand-in for the picture (debug builds' "showing"): no camera is opened for it. */
@Composable
private fun StandIn() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1C232B)))), contentAlignment = Alignment.Center) {
        SymIcon(Sym.PHOTO_CAMERA_FRONT, size = 40.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

/**
 * One popup's camera and the texture its picture goes to: the camera opens as the popup does, the texture comes once the
 * view is drawn, in either order. After [giveUp] (the popup is closing) no camera opens in it again. Main thread.
 */
private class MirrorHolder {
    private var camera: MirrorCamera? = null
    private var texture: SurfaceTexture? = null
    private var sized: (Int, Int) -> Unit = { _, _ -> }
    private var gaveUp = false

    fun open(onState: (MirrorState) -> Unit) {
        if (gaveUp || camera != null) return
        try {
            val c = MirrorItem.camera()
            camera = c
            c.open(onState)
            texture?.let { c.show(it, sized) }
        } catch (e: Exception) {
            // Compose has no error boundary: what a camera gets wrong must not take the service down.
            Log.w(Env.TAG, "mirror: ${e.javaClass.simpleName}")
            onState(MirrorState.FAILED)
        }
    }

    fun show(t: SurfaceTexture, onSize: (Int, Int) -> Unit) {
        texture = t
        sized = onSize
        camera?.show(t, onSize)
    }

    fun lost() { texture = null }

    fun close() {
        val c = camera ?: return
        camera = null
        runCatching { c.close() }
    }

    fun giveUp() {
        gaveUp = true
        close()
    }
}

/**
 * The preview: a [TextureView] whose picture fills its 16:9 box in its own shape, turned with the display, and mirrored
 * left to right on the screen while [flip] is on.
 */
// Made in code by the Mirror's popup alone, with its holder: never inflated from a layout, so it has no layout constructors.
@SuppressLint("ViewConstructor")
private class MirrorView(context: Context, private val holder: MirrorHolder) : TextureView(context), TextureView.SurfaceTextureListener {
    var flip = true
        set(value) { if (field != value) { field = value; place() } }
    /** The picture's size as it stands upright in the device's natural way up, once the camera said it. */
    private var upright: Pair<Int, Int>? = null

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
        holder.show(surface) { w, h -> upright = w to h; place() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = place()
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { holder.lost(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    private fun place() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val cx = vw / 2f
        val cy = vh / 2f
        val m = Matrix()
        val size = upright
        if (size != null) {
            val (w, h) = size
            when (val r = display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    // A quarter turn of the display, as Camera2's own sample turns its preview: the picture, upright in the
                    // device's natural way up, is turned with the display and fills the box.
                    val box = RectF(0f, 0f, vw, vh)
                    val picture = RectF(0f, 0f, w.toFloat(), h.toFloat()).apply { offset(cx - centerX(), cy - centerY()) }
                    m.setRectToRect(box, picture, Matrix.ScaleToFit.FILL)
                    val scale = max(vh / w, vw / h)
                    m.postScale(scale, scale, cx, cy)
                    m.postRotate(90f * (r - 2), cx, cy)
                }
                else -> {
                    val (sx, sy) = MirrorRules.fill(w, h, vw, vh)
                    m.setScale(sx, sy, cx, cy)
                    if (r == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
                }
            }
        }
        // On the screen, after the turn: left and right as in a mirror.
        if (flip) m.postScale(-1f, 1f, cx, cy)
        setTransform(m)
    }
}
