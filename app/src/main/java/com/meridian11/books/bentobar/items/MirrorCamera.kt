package com.meridian11.books.bentobar.items

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** How the Mirror's camera stands: opening, showing, or why not (none there, Android refused it, another app has it, it broke). */
enum class MirrorState { OPENING, SHOWING, NO_CAMERA, REFUSED, BUSY, FAILED }

/**
 * The Mirror's camera: [Camera2Mirror] on a device, a fake in a test (`MirrorItem.camera`). The camera opens as the popup
 * does, before its preview has a surface to draw on (which a view gets only once it is drawn), so the light and the
 * popup come together and a test can count opens and closes in a host that draws nothing.
 */
interface MirrorCamera {
    /** Opens the camera [MirrorRules.pick] names, now. How it goes reaches [onState] on the main thread. Never throws. */
    fun open(onState: (MirrorState) -> Unit)

    /**
     * The picture goes to [texture], at the size [MirrorRules.size] chose, from when the camera is open too. [onSize] is told
     * that size as the picture stands upright in the device's natural way up, on the main thread, before the first frame.
     */
    fun show(texture: SurfaceTexture, onSize: (width: Int, height: Int) -> Unit)

    /** Closes the camera at once, on the caller's thread: nothing reaches onState afterwards. May be called twice, and before [open]. */
    fun close()
}

/**
 * Whether the Mirror holds a camera: the Camera and mic item doesn't count that one as a call ([InUseRules.step]). It holds
 * from just before the camera is asked for, so the camera going unavailable for it is never seen as another app's, until a
 * moment after it is closed, since Android says the camera is free again a little after the close returns.
 */
object MirrorOpen {
    @Volatile var now = false
        private set
    private var holder = 0

    /** The Mirror is about to open a camera: the token to [leave] with. */
    @Synchronized internal fun take(): Int { holder++; now = true; return holder }

    /** The Mirror that took [token] has let go; a newer one that took it since still holds. */
    @Synchronized internal fun leave(token: Int) { if (token == holder) now = false }
}

/**
 * The Mirror's camera on Android's Camera2: the camera [MirrorRules.pick] chooses, opened on a thread of its own, one repeating
 * preview request into the popup's texture, unmirrored (the popup mirrors the picture itself, so that left and right are the
 * screen's). Nothing is recorded or kept: frames go to the screen and nowhere else. What Android refuses becomes a
 * [MirrorState], never an exception.
 */
internal class Camera2Mirror(private val context: Context) : MirrorCamera {
    private class Chosen(val id: String, val width: Int, val height: Int, val sensorDegrees: Int)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var onState: ((MirrorState) -> Unit)? = null
    /** Main thread: what [open] chose. */
    private var chosen: Chosen? = null
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    /** The camera's thread: the surface the picture goes to, once [show] gave one. */
    private var surface: Surface? = null
    private var starting = false
    /** The camera was ours at some point, so Android will say a moment after the close that it is free again. */
    @Volatile private var held = false
    /** Asked for, and Android hasn't answered yet: it may still open, and be closed again at once. */
    @Volatile private var pending = false
    private var token = 0
    private val left = AtomicBoolean(false)

    private fun say(state: MirrorState) {
        main.post { if (!closed) onState?.invoke(state) }
    }

    override fun open(onState: (MirrorState) -> Unit) {
        if (closed || this.onState != null) return
        this.onState = onState
        val cm = context.getSystemService(CameraManager::class.java) ?: return say(MirrorState.FAILED)
        val pick = try {
            choose(cm)
        } catch (e: Exception) {
            Log.w(Env.TAG, "mirror: can't read the cameras: ${e.javaClass.simpleName}")
            return say(if (e is SecurityException) MirrorState.REFUSED else MirrorState.FAILED)
        }
        if (pick == null) return say(MirrorState.NO_CAMERA)
        chosen = pick
        // Asked again here, where the camera is opened: the popup read Setup's word, which may be a moment old.
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return say(MirrorState.REFUSED)
        val t = HandlerThread("BentoBar-mirror").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        token = MirrorOpen.take()
        say(MirrorState.OPENING)
        pending = true
        try {
            cm.openCamera(pick.id, deviceCallback, h)
        } catch (e: SecurityException) {
            ended(MirrorState.REFUSED)
        } catch (e: CameraAccessException) {
            ended(failure(e))
        } catch (e: Exception) {
            Log.w(Env.TAG, "mirror: the camera didn't open: ${e.javaClass.simpleName}")
            ended(MirrorState.FAILED)
        }
    }

    /** The camera that shows the user, and the size its preview takes; null when there is no camera. */
    private fun choose(cm: CameraManager): Chosen? {
        val all = cm.cameraIdList.associateWith { cm.getCameraCharacteristics(it) }
        val id = MirrorRules.pick(all.map { (id, c) ->
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            MirrorRules.Cam(id, front = facing == CameraCharacteristics.LENS_FACING_FRONT, external = facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
        }) ?: return null
        val c = all.getValue(id)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = MirrorRules.size(sizes.map { it.width to it.height }) ?: (1280 to 720)
        return Chosen(id, size.first, size.second, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
    }

    override fun show(texture: SurfaceTexture, onSize: (width: Int, height: Int) -> Unit) {
        val pick = chosen
        val h = handler
        if (closed || pick == null || h == null) return
        texture.setDefaultBufferSize(pick.width, pick.height)
        // Android turns the picture by the sensor's angle to the device's natural way up: a sensor at a quarter turn gives a picture on its side.
        if (pick.sensorDegrees % 180 != 0) onSize(pick.height, pick.width) else onSize(pick.width, pick.height)
        val s = Surface(texture)
        h.post {
            if (closed) { s.release(); return@post }
            surface = s
            start()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            held = true
            pending = false
            device = camera
            // Closed while it opened: it goes at once.
            if (closed) { device = null; runCatching { camera.close() }; leave(); return }
            start()
        }

        override fun onDisconnected(camera: CameraDevice) {
            // Taken by an app Android ranks higher, or unplugged.
            runCatching { camera.close() }
            ended(MirrorState.BUSY)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            runCatching { camera.close() }
            ended(when (error) {
                ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> MirrorState.BUSY
                ERROR_CAMERA_DISABLED -> MirrorState.REFUSED
                else -> MirrorState.FAILED
            })
        }
    }

    /** The camera's thread: once the camera is open and the popup's surface is there, the preview starts. */
    private fun start() {
        val d = device ?: return
        val s = surface ?: return
        val h = handler ?: return
        if (closed || starting) return
        starting = true
        try {
            // Unmirrored whatever way the camera faces: the popup mirrors it on the screen, as its "Mirrored" option says.
            val out = OutputConfiguration(s).apply { mirrorMode = OutputConfiguration.MIRROR_MODE_NONE }
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(out), Executor { h.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cs: CameraCaptureSession) {
                        if (closed) { runCatching { cs.close() }; return }
                        session = cs
                        try {
                            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(s) }.build()
                            cs.setRepeatingRequest(request, null, h)
                            say(MirrorState.SHOWING)
                        } catch (e: Exception) {
                            ended(failure(e))
                        }
                    }

                    override fun onConfigureFailed(cs: CameraCaptureSession) = ended(MirrorState.FAILED)
                }))
        } catch (e: Exception) {
            ended(failure(e))
        }
    }

    private fun failure(e: Exception): MirrorState = when (e) {
        is SecurityException -> MirrorState.REFUSED
        is CameraAccessException -> when (e.reason) {
            CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> MirrorState.BUSY
            CameraAccessException.CAMERA_DISABLED -> MirrorState.REFUSED
            else -> MirrorState.FAILED
        }
        else -> MirrorState.FAILED
    }

    /** The camera can show no more ([state] says why): it is let go, and the popup says so. Any thread. */
    private fun ended(state: MirrorState) {
        pending = false
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        leave()
        say(state)
    }

    /**
     * Lets [MirrorOpen] go, once: a moment later if the camera was ours or may still open (Android says it is free a little
     * after it is closed), else now.
     */
    private fun leave() {
        if (token == 0 || !left.compareAndSet(false, true)) return
        val t = token
        if (held || pending) main.postDelayed({ MirrorOpen.leave(t) }, FREE_AGAIN_MS) else MirrorOpen.leave(t)
    }

    override fun close() {
        if (closed) return
        closed = true
        onState = null
        // Here and now, not on the camera's thread: the light goes out the moment the popup starts to close.
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        leave()
        val h = handler
        val t = thread
        h?.post { runCatching { surface?.release() }; surface = null }
        t?.quitSafely()
    }

    private companion object {
        /** How long after a close the Mirror still counts as holding its camera: Android's "free again" comes after it. */
        const val FREE_AGAIN_MS = 1_000L
    }
}
