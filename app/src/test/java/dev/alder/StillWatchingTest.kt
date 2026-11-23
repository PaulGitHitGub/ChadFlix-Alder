package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class StillWatchingTest {
    @Test fun asksBeforeTheFourthEpisodeWithNoKeyPressed() {
        assertEquals(false, askStillWatching(1))
        assertEquals(false, askStillWatching(2))
        assertEquals(true, askStillWatching(3))
    }

    @Test fun sleepRowNamesTheChoice() {
        assertEquals("Off", sleepLabel(stopAfter = false, sleepAt = 0, now = 1_000))
        assertEquals("After this episode", sleepLabel(stopAfter = true, sleepAt = 0, now = 1_000))
        assertEquals("In 60 min", sleepLabel(stopAfter = false, sleepAt = 1_000 + SLEEP_TIMER, now = 1_000))
        assertEquals("In 1 min", sleepLabel(stopAfter = false, sleepAt = 1_001, now = 1_000))
        assertEquals("Off", sleepLabel(stopAfter = false, sleepAt = 1_000, now = 1_000)) // ran out
    }

    private val now = 1_000_000L
    private fun cycle(episodic: Boolean, live: Boolean, from: SleepState = SleepState(false, 0)) =
        nextSleepState(from.stopAfter, from.sleepAt, episodic, live, now)

    @Test fun sleepTimerCyclesOffAfterInSixtyOffForASeries() {
        val after = cycle(episodic = true, live = false)
        assertEquals(SleepState(true, 0), after)
        val timed = cycle(true, false, after)
        assertEquals(SleepState(false, now + SLEEP_TIMER), timed)
        assertEquals(SleepState(false, 0), cycle(true, false, timed))
    }

    @Test fun sleepTimerCyclesTheSameForAFilm() {
        val after = cycle(episodic = false, live = false)
        assertEquals(SleepState(true, 0), after)
        assertEquals(SleepState(false, now + SLEEP_TIMER), cycle(false, false, after))
        assertEquals(SleepState(false, 0), cycle(false, false, SleepState(false, now + 5)))
    }

    @Test fun liveTvHasNoAfterStep() {
        val timed = cycle(episodic = false, live = true)
        assertEquals(SleepState(false, now + SLEEP_TIMER), timed)
        assertEquals(SleepState(false, 0), cycle(false, true, timed))
    }

    @Test fun aFilmNotYetKnownToBeLiveGetsAfterThisMovie() {
        assertEquals(SleepState(true, 0), cycle(episodic = false, live = false))
    }

    @Test fun sleepRowNamesAfterThisMovieForFilms() {
        assertEquals("After this movie", sleepLabel(stopAfter = true, sleepAt = 0, now = 1_000, episodic = false))
        assertEquals("After this episode", sleepLabel(stopAfter = true, sleepAt = 0, now = 1_000, episodic = true))
        assertEquals("Off", sleepLabel(stopAfter = false, sleepAt = 0, now = 1_000, episodic = false))
    }
}
