package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.MirrorRules.Cam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRulesTest {
    private val back = Cam("0", front = false, external = false)
    private val front = Cam("1", front = true, external = false)
    private val usb = Cam("5", front = false, external = true)

    @Test fun theFrontCameraIsTaken() {
        assertEquals("1", MirrorRules.pick(listOf(back, usb, front)))
    }

    @Test fun anExternalCameraBeforeTheBack() {
        assertEquals("5", MirrorRules.pick(listOf(back, usb)))
        assertEquals("0", MirrorRules.pick(listOf(back)))
    }

    @Test fun noCameraIsNone() {
        assertNull(MirrorRules.pick(emptyList()))
        assertNull(MirrorRules.size(emptyList()))
    }

    @Test fun thePreviewIsNoLargerThan720p() {
        val sizes = listOf(1920 to 1080, 1280 to 720, 1280 to 960, 960 to 720, 640 to 480, 320 to 240)
        assertEquals(1280 to 720, MirrorRules.size(sizes))
        // 4:3 counts as much as 16:9; the largest that fits wins.
        assertEquals(960 to 720, MirrorRules.size(listOf(1920 to 1080, 960 to 720, 640 to 360)))
        // Neither shape under the cap: the smallest there is.
        assertEquals(1600 to 1200, MirrorRules.size(listOf(2560 to 1440, 1600 to 1200, 4000 to 3000)))
        assertEquals(1280 to 720, MirrorRules.size(listOf(1280 to 720, 1080 to 1080)))
        assertEquals(720 to 720, MirrorRules.size(listOf(1080 to 1080, 720 to 720)))
    }

    @Test fun theSizeOptionIsTheMirrorsWidth() {
        assertEquals(240, MirrorRules.widthDp("s"))
        assertEquals(320, MirrorRules.widthDp("m"))
        assertEquals(420, MirrorRules.widthDp("l"))
        assertEquals("anything else is the default", 320, MirrorRules.widthDp("huge"))
    }

    @Test fun aMirrorThatLetsGoLateDoesNotEndANewersHold() {
        val first = MirrorOpen.take()
        assertTrue(MirrorOpen.now)
        val second = MirrorOpen.take()
        // The first popup's camera is let go a moment after it closed, while the next popup already holds one.
        MirrorOpen.leave(first)
        assertTrue("the camera and mic item still doesn't count the mirror's own camera", MirrorOpen.now)
        MirrorOpen.leave(second)
        assertFalse(MirrorOpen.now)
    }

    @Test fun aPictureOfAnotherShapeFillsTheBoxWithoutBeingStretched() {
        assertEquals(1f to 1f, MirrorRules.fill(1280, 720, 320f, 180f))
        // A 4:3 picture in a 16:9 box: as wide as the box, cut at the top and bottom, never squeezed.
        val (x, y) = MirrorRules.fill(640, 480, 320f, 180f)
        assertEquals(1f, x, 0.001f)
        assertEquals(240f / 180f, y, 0.001f)
        // An upright picture: as high as it must be to fill the width.
        val (ux, uy) = MirrorRules.fill(720, 1280, 320f, 180f)
        assertEquals(1f, ux, 0.001f)
        assertEquals(320f * 1280f / 720f / 180f, uy, 0.001f)
        assertEquals("nothing to measure yet", 1f to 1f, MirrorRules.fill(0, 0, 320f, 180f))
    }
}
