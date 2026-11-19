package com.cape100.coach.plan

import com.cape100.coach.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanGeneratorTest {
    @Test
    fun planEndsOnRaceDayAndIncludesQualifier() {
        val plan = PlanGenerator.generate(Profile(planStart = "2026-10-19", onboarded = true), LocalDate.of(2026, 10, 18))
        plan.forEach { w ->
            val long = w.sessions.maxBy { it.distanceKm }
            println("W%02d %s %-15s %s %5.1f km  long %4.1f  %s".format(w.index + 1, w.start, w.phase.label,
                if (w.isRecovery) "R" else " ", w.targetKm, long.distanceKm, long.title))
        }
        val all = plan.flatMap { it.sessions }
        assertEquals("2027-09-05", all.single { it.type.name == "RACE" }.date)
        assertTrue(all.any { it.date == "2027-03-21" && it.title.contains("QUALIFIER") })
        assertTrue(plan.all { it.sessions.size == 7 })
    }
}

class LowVolumePlanTest {
    @Test
    fun lowVolumeRunnerBuildsGradually() {
        val p = Profile(planStart = "2026-10-18", currentWeeklyKm = 10, longestRecentRunKm = 14, runDaysPerWeek = 5, onboarded = true)
        val plan = PlanGenerator.generate(p, LocalDate.of(2026, 10, 18))
        plan.forEach { w ->
            val long = w.sessions.maxBy { it.distanceKm }
            println("W%02d %s %-15s %s %5.1f km  runs %d  long %4.1f".format(w.index + 1, w.start, w.phase.label,
                if (w.isRecovery) "R" else " ", w.targetKm, w.sessions.count { it.distanceKm > 0 }, long.distanceKm))
        }
        assertTrue("week 1 should be close to current volume", plan[0].targetKm <= 14.0)
        // No non-recovery week jumps more than ~15% over the previous non-recovery, non-race week during base.
        val base = plan.filter { it.phase.name == "BASE" && !it.isRecovery }
        base.zipWithNext().forEach { (a, b) -> assertTrue("${a.targetKm} -> ${b.targetKm}", b.targetKm <= a.targetKm * 1.2 + 1.5) }
    }
}

class RoutinePlanTest {
    @Test
    fun weekdayRunsFitThirtyMinutesAndHomeStrengthAppears() {
        val p = Profile(planStart = "2026-10-18", currentWeeklyKm = 15, longestRecentRunKm = 6, runDaysPerWeek = 6,
            weekdayMaxMin = 30, dumbbellKg = 3.0, foamRoller = true, onboarded = true)
        val plan = PlanGenerator.generate(p, LocalDate.of(2026, 10, 18))
        plan.filter { it.index % 6 == 0 || it.index >= plan.size - 9 }.forEach { w ->
            println("W%02d %-15s %5.1f km | ".format(w.index + 1, w.phase.label, w.targetKm) +
                w.sessions.joinToString(" | ") { "${it.date.drop(8)} ${it.title.take(26)} ${it.durationMin}m" })
        }
        val weekdayRuns = plan.flatMap { it.sessions }.filter {
            val dow = LocalDate.parse(it.date).dayOfWeek.value
            dow in 1..5 && it.distanceKm > 0 && it.type.name !in setOf("RACE", "TUNEUP_RACE")
        }
        // Race week and the qualifier week have their own fixed sessions; everything else must fit 30 minutes.
        val raceWeeks = plan.filter { w -> w.sessions.any { it.type.name in setOf("RACE", "TUNEUP_RACE") } }.flatMap { it.sessions }.map { it.date }.toSet()
        weekdayRuns.filter { it.date !in raceWeeks }.forEach { assertTrue("${it.date} ${it.title} ${it.durationMin} min", it.durationMin <= 30) }
        assertTrue(plan.flatMap { it.sessions }.any { it.title.startsWith("Home upper body") && it.details.contains("3 kg") })
    }
}

class YourPlanLongRunsTest {
    @Test
    fun printLongRuns() {
        val p = Profile(planStart = "2026-10-18", currentWeeklyKm = 20, longestRecentRunKm = 10, runDaysPerWeek = 6,
            weekdayMaxMin = 30, dumbbellKg = 3.0, foamRoller = true, easyPaceMinPerKm = 8.0, onboarded = true,
            longRunGoals = listOf(com.cape100.coach.data.LongRunGoal(15.0, "2026-11-01"), com.cape100.coach.data.LongRunGoal(20.0, "2026-11-15")))
        PlanGenerator.generate(p, LocalDate.of(2026, 10, 18)).forEach { w ->
            val sun = w.sessions.last(); val sat = w.sessions[5]; val wed = w.sessions[2]
            println("W%02d %s %-15s %s %5.1f km/wk  Wed %3.1f km/%dmin  Sat %4.1f  Sun %4.1f".format(w.index + 1, w.start, w.phase.label,
                if (w.isRecovery) "R" else " ", w.targetKm, wed.distanceKm, wed.durationMin, sat.distanceKm, sun.distanceKm))
        }
    }
}

class GoalsAfterRestartTest {
    @Test
    fun hitsFifteenOnFirstNovemberAfterRestartingToday() {
        val p = Profile(planStart = "2026-10-19", currentWeeklyKm = 20, longestRecentRunKm = 10, runDaysPerWeek = 6,
            weekdayMaxMin = 30, dumbbellKg = 3.0, foamRoller = true, easyPaceMinPerKm = 8.0, onboarded = true,
            longRunGoals = listOf(com.cape100.coach.data.LongRunGoal(15.0, "2026-11-01"), com.cape100.coach.data.LongRunGoal(20.0, "2026-11-15")))
        val sundays = PlanGenerator.generate(p, LocalDate.of(2026, 10, 19)).take(6).map { it.sessions.last() }
        sundays.forEach { println("${it.date} ${it.distanceKm} km ${it.title}") }
        assertEquals(15.0, sundays.first { it.date == "2026-11-01" }.distanceKm, 0.01)
        assertEquals(20.0, sundays.first { it.date == "2026-11-15" }.distanceKm, 0.01)
    }
}
