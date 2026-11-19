package com.cape100.coach.plan

import com.cape100.coach.data.EnteredRace
import com.cape100.coach.data.LongRunGoal
import com.cape100.coach.data.Profile
import com.cape100.coach.data.SessionType
import com.cape100.coach.ui.screens.parseRaceKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnteredRaceTest {
    private val base = Profile(planStart = "2026-10-25", currentWeeklyKm = 18, longestRecentRunKm = 9, runDaysPerWeek = 6,
        weekdayMaxMin = 30, dumbbellKg = 3.0, foamRoller = true, easyPaceMinPerKm = 8.0, easyHrMax = 146, onboarded = true,
        longRunGoals = listOf(LongRunGoal(15.0, "2026-11-01"), LongRunGoal(20.0, "2026-11-15")))

    private fun days(p: Profile) = PlanGenerator.generate(p, LocalDate.of(2026, 11, 3)).flatMap { it.sessions }.associateBy { it.date }

    @Test fun saturdayTenKFitsIntoTheWeek() {
        val before = days(base)
        val after = days(base.copy(enteredRaces = listOf(EnteredRace("oppie-bol-2026-11-07", "Oppie Bol", "2026-11-07", 10.0))))
        listOf("2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-15").forEach { d ->
            println("$d  ${before[d]?.title}  →  ${after[d]?.title}")
        }
        assertEquals(SessionType.EVENT, after["2026-11-07"]!!.type)
        assertEquals(10.0, after["2026-11-07"]!!.distanceKm, 0.01)
        assertTrue(after["2026-11-06"]!!.title.startsWith("Shake-out") || after["2026-11-06"]!!.type == SessionType.REST)
        // Sunday after: easier, and the 20 km target on the 15th is untouched.
        assertTrue(after["2026-11-08"]!!.distanceKm < (before["2026-11-08"]?.distanceKm ?: 0.0) || before["2026-11-08"]?.type == SessionType.REST)
        assertEquals(20.0, after["2026-11-15"]!!.distanceKm, 0.01)
    }

    @Test fun readsDistances() {
        assertEquals(10.0, parseRaceKm("10km")!!, 0.01)
        assertEquals(21.1, parseRaceKm("21.1 km")!!, 0.01)
        assertEquals(5.0, parseRaceKm("5K Fun Run")!!, 0.01)
        assertEquals(21.1, parseRaceKm("Half Marathon")!!, 0.01)
    }
}
