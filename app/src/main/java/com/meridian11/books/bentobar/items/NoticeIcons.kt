package com.meridian11.books.bentobar.items

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.meridian11.books.bentobar.util.Sym

/**
 * The pictures of Notification icons: a notification's small icon as a mask of the strip's size, the strip of them the
 * bar draws in the item's icon place (one picture, as wide as its icons; `ItemState.tintImage` gives it the strip's
 * colour), and the apps' own icons for the popup's rows. An icon says which app has something to say, never what.
 */
internal object NoticeIcons {
    /** A small icon's side in the strip's picture: the bar draws it 13.5 to 17 dp high, so this stays sharp to about 3× density. */
    const val PX = 48
    /** Between two icons in the strip: about 4 dp at the bar's size. */
    const val GAP = 12
    /** An app's icon in a popup row: 20 dp at up to three times the density. */
    private const val APP_PX = 60

    /** How wide the strip of [n] icons is, in pixels. */
    fun width(n: Int): Int = if (n <= 0) 0 else n * PX + (n - 1) * GAP

    /**
     * [icon] (a notification's small icon) drawn as a [PX] square, or null where it can't be loaded: the app's resources
     * out of reach, or nothing drawn. Slow (another app's resources): the listener's thread, never the main one.
     */
    fun mask(context: Context, icon: Icon?): Bitmap? = runCatching {
        val drawable = icon?.loadDrawable(context) ?: return@runCatching null
        val b = createBitmap(PX, PX)
        drawable.setBounds(0, 0, PX, PX)
        drawable.draw(Canvas(b))
        b
    }.getOrNull()

    /** The bell that stands for an icon that couldn't be loaded. Main thread (the item's state). */
    private var bell: Bitmap? = null

    /**
     * One picture of [icons] side by side, [GAP] apart, a bell for each that is null; an alpha mask that takes the strip's
     * colour. Main thread: the item's state builds it only when its icons change.
     */
    fun strip(icons: List<Bitmap?>): Bitmap {
        val out = createBitmap(width(icons.size).coerceAtLeast(1), PX)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        icons.forEachIndexed { i, icon ->
            val picture = icon ?: bell ?: Glyphs.bitmap(Sym.NOTIFICATIONS, PX).also { bell = it }
            val x = i * (PX + GAP)
            canvas.drawBitmap(picture, null, Rect(x, 0, x + PX, PX), paint)
        }
        return out
    }

    // ---- the apps' own icons, for the popup's rows

    private val apps = HashMap<String, ImageBitmap>()

    /** [pkg]'s icon if it was drawn already: what a row shows at once. */
    fun drawn(pkg: String): ImageBitmap? = synchronized(apps) { apps[pkg] }

    /** [pkg]'s launcher icon, drawn small once; null for an app Android doesn't show BentoBar. Off the main thread. */
    fun draw(pkg: String): ImageBitmap? = runCatching {
        val icon = Env.app.packageManager.getApplicationIcon(pkg)
        val b = createBitmap(APP_PX, APP_PX)
        icon.setBounds(0, 0, APP_PX, APP_PX)
        icon.draw(Canvas(b))
        b.asImageBitmap()
    }.getOrNull()?.also { synchronized(apps) { apps[pkg] = it } }

    /** Nothing shows notifications any more: no picture of them stays. Main thread. */
    fun forget() {
        bell = null
        synchronized(apps) { apps.clear() }
    }
}
