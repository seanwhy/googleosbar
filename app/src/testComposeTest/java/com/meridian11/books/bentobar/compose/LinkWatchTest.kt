package com.meridian11.books.bentobar.compose

import android.app.Application
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.LinkWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Network menu's link is followed only while a Network item is live: one callback on the default network however
 * often it goes live, and none once it is idle, with what was read forgotten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LinkWatchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val callbacks get() = shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks

    @Before fun env() = Env.init(app)
    @After fun end() = LinkWatch.idle()

    @Test fun oneCallbackWhileLiveAndNoneOnceIdle() {
        assertEquals(0, callbacks.size)
        LinkWatch.live()
        assertEquals(1, callbacks.size)
        // A second Network item in the layout, or live() again: still the one callback.
        LinkWatch.live()
        assertEquals(1, callbacks.size)
        LinkWatch.idle()
        assertEquals(0, callbacks.size)
        assertNull(LinkWatch.current())
        // Idle twice is idle.
        LinkWatch.idle()
        assertEquals(0, callbacks.size)
    }
}
