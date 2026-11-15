package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.Bento
import com.meridian11.books.bentobar.ui.BentoButton
import com.meridian11.books.bentobar.ui.ButtonKind
import com.meridian11.books.bentobar.ui.Keycap
import com.meridian11.books.bentobar.ui.NoticeLine
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.Palette
import com.meridian11.books.bentobar.ui.Segment
import com.meridian11.books.bentobar.ui.SegmentPart
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Shell
import com.meridian11.books.bentobar.ui.SwitchMark
import com.meridian11.books.bentobar.util.Sym
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The settings window as it is drawn: the header's ground runs up under the system's caption and the page's ground
 * is the palette's, in light and dark, at each layout's width. With the environment variable `BENTOBAR_PICTURES`
 * naming a directory, each picture is also written there as a PNG, to look at beside the design.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ShellPicturesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig

    @Before fun env() { Env.init(app); before = Store.config.value }
    @After fun forget() = Store.update { before }

    private var dark by mutableStateOf(false)
    private var running by mutableStateOf(true)
    private var page by mutableStateOf(Page.BAR)

    private lateinit var view: View

    /**
     * What the window's content draws, asked of the view itself. A capture of the window is not used: in a run of every
     * Compose test it came out one change behind (the dark sheet where the light one was asked for), or with nothing of
     * the content when no state had changed since the content was set.
     */
    private fun picture(name: String): Bitmap {
        val bitmap = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        System.getenv("BENTOBAR_PICTURES")?.takeIf { it.isNotBlank() }?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun assertColor(what: String, want: Color, bitmap: Bitmap, x: Int, y: Int) =
        assertEquals(what, Integer.toHexString(want.toArgb()), Integer.toHexString(bitmap.getPixel(x, y)))

    /** The window with today's Bar page in it, under a caption 36 high that only the header's ground reaches. */
    private fun window(width: Int) {
        Store.update {
            BarConfig(items = listOf(ItemConfig("a", "clock", Section.SHOWN, area = Position.RIGHT), ItemConfig("b", "cpu", Section.SHOWN, area = Position.RIGHT),
                ItemConfig("c", "battery", Section.HIDDEN, area = Position.RIGHT)))
        }
        rule.setContent {
            view = LocalView.current
            SettingsTheme(dark) {
                Shell(page, { page = it }, running, enabled = true, status = BarStatus.SHOWN, onEnabled = {}, onTurnOn = {}, insets = WindowInsets(top = CAPTION.dp)) {
                    BarPage(selected = null, onSelect = {})
                }
            }
        }
        for (isDark in THEMES) for (on in listOf(true, false)) {
            dark = isDark; running = on
            val palette = if (isDark) Palette.Dark else Palette.Light
            val bitmap = picture("header-$width-${if (isDark) "dark" else "light"}${if (on) "" else "-off"}")
            assertEquals(width, bitmap.width)
            // The caption's ground and the header's are one; the page's starts under the header (two rows below 760).
            val header = CAPTION + if (width < 760) 52 + 44 else 52
            for (x in listOf(2, width / 2, width - 3)) {
                assertColor("the caption's ground at $x", palette.chrome, bitmap, x, 2)
                assertColor("the header's last line at $x", palette.chrome, bitmap, x, header - 1)
                assertColor("the page's first line at $x", palette.surface, bitmap, x, header)
            }
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun at1180() = window(1180)

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun at960() = window(960)

    @Config(qualifiers = "w700dp-h780dp-mdpi")
    @Test fun at700() = window(700)

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun at640() = window(640)

    /** The controls on the tokens, each on the ground it is made for. */
    @Config(qualifiers = "w720dp-h420dp-mdpi")
    @Test fun theControls() {
        rule.setContent {
            view = LocalView.current
            SettingsTheme(dark) {
                val c = Bento.colors
                Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Segment(listOf(SegmentPart("Your bar", Sym.WYSIWYG), SegmentPart("Add items", Sym.ADD), SegmentPart("Clock", enabled = false)), chosen = 0, onChoose = {},
                        modifier = Modifier.width(420.dp))
                    Row(Modifier.background(c.panel).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Segment(listOf(SegmentPart("Left"), SegmentPart("Middle"), SegmentPart("Right")), chosen = 2, onChoose = {}, modifier = Modifier.width(300.dp))
                        SwitchMark(true); SwitchMark(false); SwitchMark(true, enabled = false)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BentoButton("Turn on", {}, kind = ButtonKind.FILLED)
                        BentoButton("Add", {}, icon = Sym.ADD)
                        BentoButton("Undo", {}, kind = ButtonKind.TEXT)
                        BentoButton("Remove", {}, kind = ButtonKind.DANGER)
                        BentoButton("Add", {}, enabled = false)
                        Keycap("Ctrl"); Keycap("K")
                        Text("Help in ink 3", style = Bento.type.help, color = c.ink3)
                    }
                    NoticeLine("The Middle has no room right now. Its items show again when there is.")
                    NoticeLine("Calendar needs your permission.") { BentoButton("Allow", {}, kind = ButtonKind.TEXT) }
                }
            }
        }
        for (isDark in THEMES) {
            dark = isDark
            val palette = if (isDark) Palette.Dark else Palette.Light
            val bitmap = picture("controls-${if (isDark) "dark" else "light"}")
            assertColor("the ground", palette.surface, bitmap, 2, 2)
            // The first segment's track, in its padding, and its raised part.
            assertColor("a segment's track", palette.sunk, bitmap, 24 + 1, 24 + 20)
            assertColor("its raised part", palette.raised, bitmap, 24 + 8, 24 + 8)
        }
    }

    private companion object {
        const val CAPTION = 36
        val THEMES = listOf(false, true)
    }
}
