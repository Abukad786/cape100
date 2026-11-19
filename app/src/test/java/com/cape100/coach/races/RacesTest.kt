package com.cape100.coach.races

import com.cape100.coach.data.ActivitySummary
import com.cape100.coach.data.GeoPoint
import com.cape100.coach.data.Profile
import com.cape100.coach.plan.LoadChecker
import com.cape100.coach.plan.PlanGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RacesTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name).readText()
    private val today = LocalDate.of(2026, 10, 18)
    private val profile = Profile(planStart = "2026-10-19", onboarded = true)

    @Test
    fun parsesCalendarPages() {
        val races = RaceFetcher().parse(res("calendar_marathon_p3.html")) + RaceFetcher().parse(res("calendar_ultra.html"))
        races.take(8).forEach { println("${it.date} ${it.name} | ${it.location} | ${it.distances}") }
        assertTrue(races.size > 20)
        assertTrue(races.all { it.name.isNotBlank() && it.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")) && it.url.startsWith("https://") })
        assertTrue(races.any { it.distances.contains("Marathon") })
    }

    @Test
    fun suggestsRoadRacesInTheRightWindows() {
        val races = RaceFetcher().parse(res("calendar_marathon_p3.html")) + RaceFetcher().parse(res("calendar_ultra.html"))
        val capeTown = GeoPoint(-33.92, 18.42)
        val s = RaceAdvisor.suggest(races, profile, capeTown, { if (it.contains("Western Cape")) GeoPoint(-33.9, 18.6) else GeoPoint(-26.2, 28.0) }, today)
        s.forEach { println("${it.purpose} ${it.score} ${it.race.date} ${it.race.name} | ${it.race.location} ${it.kmFromHome}km | ${it.option.label}") }
        assertTrue(s.isNotEmpty())
        assertTrue(s.none { it.option.trail })
        s.filter { it.purpose == RacePurpose.QUALIFIER }.forEach { assertTrue(!it.date.isAfter(LocalDate.of(2027, 5, 31))) }
        s.forEach { assertTrue(it.date.isBefore(LocalDate.of(2027, 8, 15))) }
    }

    @Test
    fun planCheckFlagsBigDifferences() {
        val plan = PlanGenerator.generate(profile.copy(planStart = "2026-09-01"), LocalDate.of(2026, 9, 1))
        // Activities that exactly match the plan's sessions for the last 28 days.
        val planned = plan.flatMap { it.sessions }.filter {
            val d = LocalDate.parse(it.date); !d.isBefore(today.minusDays(28)) && d.isBefore(today) && it.distanceKm > 0
        }
        fun acts(scale: Double) = planned.mapIndexed { i, s ->
            ActivitySummary(i.toLong(), "Run", "running", s.date + "T06:00:00", s.distanceKm * scale, (s.durationMin * scale).toInt(), 50)
        }
        assertNull(LoadChecker.check(plan, acts(1.0), profile, today))
        val more = LoadChecker.check(plan, acts(1.4), profile, today)
        assertNotNull(more); assertTrue(more!!.trainingMore)
        println(more.message)
        val updated = LoadChecker.updatedProfile(profile, more, today)
        assertEquals(today.toString(), updated.planStart)
        assertTrue(updated.currentWeeklyKm > profile.currentWeeklyKm)
        val less = LoadChecker.check(plan, acts(0.5), profile, today)
        assertNotNull(less); assertTrue(!less!!.trainingMore)
    }
}
