package com.cape100.coach.plan

import com.cape100.coach.data.ActivitySummary
import com.cape100.coach.data.PlanWeek
import com.cape100.coach.data.Profile
import java.time.LocalDate
import kotlin.math.roundToInt

data class LoadCheck(
    val actualHoursPerWeek: Double,
    val plannedHoursPerWeek: Double,
    val actualKmPerWeek: Double,
    val plannedKmPerWeek: Double,
    val longestRunKm: Double,
    val trainingMore: Boolean,
) {
    val ratio: Double get() = if (plannedHoursPerWeek > 0) actualHoursPerWeek / plannedHoursPerWeek else 1.0
    val headline: String
        get() = if (trainingMore) "You're training more than your plan"
        else "You're training less than your plan"
    val message: String
        get() = "Last 4 weeks: %.1f h/week (%d km) vs %.1f h/week (%d km) planned. ".format(
            actualHoursPerWeek, actualKmPerWeek.roundToInt(), plannedHoursPerWeek, plannedKmPerWeek.roundToInt(),
        ) + if (trainingMore) "Update the plan so it builds from where you really are, not where you were."
        else "Update the plan so it ramps up from your real volume and avoids a risky jump."
}

/**
 * Compares the last 28 days of Garmin running against what the plan expected for those days.
 * Suggests a plan update when you're consistently ≥20% above or ≥25% below plan.
 */
object LoadChecker {
    fun check(plan: List<PlanWeek>, acts: List<ActivitySummary>, p: Profile, today: LocalDate = LocalDate.now()): LoadCheck? {
        val from = today.minusDays(28)
        val window = acts.filter { a ->
            a.isRunLike && runCatching { LocalDate.parse(a.date) }.getOrNull()?.let { !it.isBefore(from) && it.isBefore(today) } == true
        }
        if (window.size < 4) return null

        val sessions = plan.flatMap { it.sessions }.associateBy { it.date }
        val baselineMinPerDay = p.currentWeeklyKm * PlanGenerator.Paces(p).easy / 7.0
        var plannedMin = 0.0
        var plannedKm = 0.0
        var d = from
        while (d.isBefore(today)) {
            val s = sessions[d.toString()]
            if (s != null) { if (s.distanceKm > 0) { plannedMin += s.durationMin; plannedKm += s.distanceKm } }
            else { plannedMin += baselineMinPerDay; plannedKm += p.currentWeeklyKm / 7.0 }
            d = d.plusDays(1)
        }
        val c = LoadCheck(
            actualHoursPerWeek = window.sumOf { it.durationMin } / 60.0 / 4,
            plannedHoursPerWeek = plannedMin / 60.0 / 4,
            actualKmPerWeek = window.sumOf { it.distanceKm } / 4,
            plannedKmPerWeek = plannedKm / 4,
            longestRunKm = window.maxOf { it.distanceKm },
            trainingMore = true,
        )
        return when {
            c.ratio >= 1.2 -> c
            c.ratio <= 0.75 -> c.copy(trainingMore = false)
            else -> null
        }
    }

    /** New profile that rebuilds the plan from this week using real recent volume. */
    fun updatedProfile(p: Profile, c: LoadCheck, today: LocalDate = LocalDate.now()): Profile = p.copy(
        currentWeeklyKm = c.actualKmPerWeek.roundToInt().coerceAtLeast(15),
        longestRecentRunKm = c.longestRunKm.roundToInt().coerceAtLeast(8),
        baselineFromGarmin = true,
        planStart = today.toString(),
        journeyStart = p.journeyStart.ifBlank { p.planStart },
    )
}
