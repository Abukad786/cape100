package com.cape100.coach.plan

import com.cape100.coach.data.EnteredRace
import com.cape100.coach.data.LongRunGoal
import com.cape100.coach.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Runs on Tue/Thu at 06:50 with no time cap, plus Sat/Sun. */
class NewScheduleTest {
    private val p = Profile(planStart = "2026-10-25", currentWeeklyKm = 18, longestRecentRunKm = 9, runDaysPerWeek = 4,
        weekdayMaxMin = 0, runTime = "06:50", dumbbellKg = 3.0, foamRoller = true, easyPaceMinPerKm = 8.0, easyHrMax = 146, onboarded = true,
        longRunGoals = listOf(LongRunGoal(15.0, "2026-11-01"), LongRunGoal(20.0, "2026-11-15")),
        enteredRaces = listOf(EnteredRace("oppie", "Oppie Bol", "2026-11-07", 10.0)))

    @Test fun runsOnlyTueThuSatSun() {
        val weeks = PlanGenerator.generate(p, LocalDate.of(2026, 11, 6))
        weeks.take(8).forEach { w ->
            println("${w.start} ${w.targetKm} km: " + w.sessions.joinToString(" | ") { "${LocalDate.parse(it.date).dayOfWeek.name.take(3)} ${it.title}" })
        }
        weeks.take(20).flatMap { it.sessions }.filter { it.distanceKm > 0 && it.type.name != "RECOVERY" }.forEach {
            val dow = LocalDate.parse(it.date).dayOfWeek.value
            assertTrue("${it.date} ${it.title}", dow in setOf(2, 4, 6, 7))
        }
        assertEquals(20.0, weeks.flatMap { it.sessions }.first { it.date == "2026-11-15" }.distanceKm, 0.01)
    }
}
