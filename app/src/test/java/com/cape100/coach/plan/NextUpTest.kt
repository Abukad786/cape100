package com.cape100.coach.plan

import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NextUpTest {
    private val run = Session(date = "2026-10-21", type = SessionType.EASY, title = "Easy run · 5 km", distanceKm = 5.0, durationMin = 30)
    private val long = Session(date = "2026-10-22", type = SessionType.LONG, title = "Long run · 15 km", distanceKm = 15.0, durationMin = 100)

    private fun at(h: Int, m: Int = 0, done: Boolean = false, weighed: Boolean = false, stretched: Boolean = false, today: Session? = run) =
        NextUp.compute(NextUp.Input(
            now = LocalTime.of(h, m), wakeTime = "03:20", runTime = "03:30", routineNote = "Tahajjud",
            today = today, tomorrow = long, sessionDone = done, weighedIn = weighed, coachWantsWeighIn = false,
            stretchDone = stretched, stretchTitle = "Post-run stretch", stretchMinutes = 10,
        ))

    @Test fun walksThroughTheMorning() {
        at(3, 20).let { assertEquals("03:30", it.label); assertTrue(it.detail.contains("Tahajjud")) }
        assertEquals(NextUp.Action.MARK_DONE, at(3, 45).action)
        assertEquals(NextUp.Action.WEIGH_IN, at(4, 10, done = true).action)
        assertEquals(NextUp.Action.STRETCH_DONE, at(4, 20, done = true, weighed = true).action)
        at(9, 0, done = true, weighed = true, stretched = true).let { assertTrue(it.detail.contains("Long run")) }
    }

    @Test fun sickMeansRest() {
        val s = NextUp.compute(NextUp.Input(LocalTime.of(6, 0), "03:20", "06:50", "", run, long, false, false, false, false, "Stretch", 10, sick = true))
        assertEquals("Rest and recover", s.title)
        assertEquals(NextUp.Action.NONE, s.action)
    }

    @Test fun eveningShowsTomorrowAndBedtime() {
        val s = at(19, 30, done = true, weighed = true, stretched = true)
        assertEquals("Tomorrow", s.label)
        assertEquals("Long run · 15 km", s.title)
        assertTrue(s.detail.contains("20:20"))
        // A session moved to the afternoon and still open stays up until 21:00.
        assertEquals(NextUp.Action.MARK_DONE, at(19, 30).action)
        assertEquals("Tomorrow", at(21, 5).label)
    }

    @Test fun restDayGoesStraightToWeighIn() {
        val rest = run.copy(type = SessionType.REST, title = "Rest day", distanceKm = 0.0, durationMin = 0)
        assertEquals(NextUp.Action.WEIGH_IN, at(6, today = rest).action)
        assertEquals(NextUp.Action.NONE, at(9, weighed = true, today = rest).action)
        assertEquals(NextUp.Action.STRETCH_DONE, at(13, weighed = true, today = rest).action)
    }
}
