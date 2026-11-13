package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.MenuWindow
import com.meridian11.books.bentobar.items.Env
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Back in a popup does what Esc does; a screen reader's global Back is the same press. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class MenuWindowBackTest {
    @Test fun backClosesThePopupAsEscapeDoes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Env.init(context)
        var escapes = 0
        val window = MenuWindow(context, "test", onOutside = {}, onEscape = { escapes++ })
        window.dialog.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, escapes)
    }
}
