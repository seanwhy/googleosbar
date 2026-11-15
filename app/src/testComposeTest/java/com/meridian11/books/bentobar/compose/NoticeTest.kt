package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.Notice
import com.meridian11.books.bentobar.ui.rememberNotices
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The settings window's one snackbar: a new message takes the place of the one that is up, and its action still answers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class NoticeTest {
    @get:Rule val rule = createComposeRule()

    @Before fun env() = Env.init(ApplicationProvider.getApplicationContext<Application>())

    @Test fun aMessagePostedAsTheWindowComposesIsNotLost() {
        // A recreated window: a page can post as it composes, in the same pass as the one that starts listening.
        rule.setContent {
            val notices = rememberNotices()
            LaunchedEffect(Unit) { Notice.post("back", "Undo") }
            SnackbarHost(notices)
        }
        rule.onNodeWithText("back").assertIsDisplayed()
    }

    @Test fun aNewMessageReplacesTheOneThatIsUp() {
        val undone = mutableListOf<String>()
        rule.setContent { SnackbarHost(rememberNotices()) }
        rule.waitForIdle()
        Notice.post("first", "Undo", onAction = { undone += "first" })
        rule.waitForIdle()
        rule.onNodeWithText("first").assertIsDisplayed()
        Notice.post("second", "Undo", onAction = { undone += "second" })
        rule.waitForIdle()
        rule.onNodeWithText("second").assertIsDisplayed()
        rule.onNodeWithText("first").assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals("the Undo on the screen is the latest's", listOf("second"), undone)
    }
}
