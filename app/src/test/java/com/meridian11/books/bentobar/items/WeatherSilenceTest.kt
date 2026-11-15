package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the settings window says, over Weather's options and in its row, that the service gave no answer. */
class WeatherSilenceTest {
    private val read = Reading("37.77,-122.42", fetchedAt = 1_000L, current = Current(at = 1L, temp = 20.0))

    @Test fun anAnswerIsNoSilence() {
        assertNull(WeatherRules.silence(Status.Live(read)))
        assertNull(WeatherRules.silence(Status.Loading))
        assertNull(WeatherRules.silence(Status.NotSetUp))
        assertNull(WeatherRules.silence(Status.Off(everOn = true)))
    }

    @Test fun noAnswerWithNothingToShowNamesTheFailureAndNoTime() {
        assertEquals(Failure.NO_ANSWER to 0L, WeatherRules.silence(Status.Missing(Failure.NO_ANSWER)))
        assertEquals(Failure.SLOW_DOWN to 0L, WeatherRules.silence(Status.Missing(Failure.SLOW_DOWN)))
    }

    @Test fun noAnswerWhileAnOlderReadingShowsSaysWhenThatWasFetched() {
        assertEquals(Failure.NO_ANSWER to 1_000L, WeatherRules.silence(Status.Live(read.copy(failure = Failure.NO_ANSWER, misses = 1))))
    }

    @Test fun aMissingConnectionIsTheDevicesNotTheServices() {
        assertNull(WeatherRules.silence(Status.Missing(Failure.OFFLINE)))
        assertNull(WeatherRules.silence(Status.Live(read.copy(failure = Failure.OFFLINE))))
    }
}
