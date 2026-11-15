package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.ui.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The options of Day progress, Sunset and Break reminder, in the settings window's controls: they compose, and write what they read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DayItemsOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var item by mutableStateOf(ItemConfig("x", "progress"))
    private fun str(id: Int) = Env.str(id)

    @Before fun env() { Env.init(app) }

    private fun show(first: ItemConfig) {
        item = first
        rule.setContent { SettingsTheme(dark = false) { Column { Items.of(item.type)!!.options!!(item) { item = it } } } }
        rule.waitForIdle()
    }

    @Test fun dayProgressAsksForTheWorkdaysHoursOnlyWhenItCountsOne() {
        show(ItemConfig("x", "progress"))
        rule.onNodeWithText(str(R.string.progress_from)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.progress_workday)).performClick()
        rule.waitForIdle()
        assertEquals("workday", item.options["of"])
        rule.onNodeWithText(str(R.string.progress_from)).assertExists()
        rule.onNodeWithText(str(R.string.progress_to)).assertExists()
        rule.onNodeWithText(str(R.string.progress_year)).performClick()
        rule.waitForIdle()
        assertEquals("year", item.options["of"])
        rule.onNodeWithText(str(R.string.progress_from)).assertDoesNotExist()
    }

    @Test fun sunsetChoosesWhatItCountsDownTo() {
        show(ItemConfig("x", "sun"))
        rule.onNodeWithText(str(R.string.sun_where_help)).assertExists()
        rule.onNodeWithText(str(R.string.sun_sunset)).performClick()
        rule.waitForIdle()
        assertEquals("sunset", item.options["show"])
    }

    @Test fun breakReminderHasItsTwoLengths() {
        show(ItemConfig("x", "breaks"))
        rule.onNodeWithText(str(R.string.breaks_every)).assertExists()
        rule.onNodeWithText(str(R.string.breaks_rest)).assertExists()
        // The slider's value, as written beside it (the slider hides it from the merged tree, and says it itself).
        rule.onNodeWithText(app.getString(R.string.common_bar_minutes, 50), useUnmergedTree = true).assertExists()
    }
}
