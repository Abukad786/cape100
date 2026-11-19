package com.cape100.coach.races

import com.cape100.coach.data.GeoPoint
import com.cape100.coach.data.Profile
import com.cape100.coach.data.Race
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class RacePurpose(val title: String, val blurb: String) {
    QUALIFIER("Qualifier marathon", "Officially timed road marathon under 5:00, by 31 May 2027."),
    BACKUP_QUALIFIER("Backup qualifier", "A second chance if your first attempt doesn't go to plan."),
    TUNE_UP_ULTRA("Tune-up ultra", "50–60 km road ultra 7–17 weeks out: dress rehearsal for CAPE 100."),
    LONG_RUN_MARATHON("Supported long runs", "Marathons in your ultra build: run them easy as a long run with water tables."),
    FITNESS_HALF("Fitness-test halves", "Half marathons that check your fitness and predict your marathon time."),
}

data class RaceOption(val label: String, val km: Double?, val trail: Boolean, val ultra: Boolean)

data class RaceSuggestion(
    val race: Race,
    val purpose: RacePurpose,
    val option: RaceOption,
    val kmFromHome: Int?,
    val score: Int,
    val reason: String,
    val howToRun: String,
) {
    val date: LocalDate get() = LocalDate.parse(race.date)
}

enum class RaceKind(val label: String) { HALF("Half"), MARATHON("Marathon"), ULTRA("Ultra") }

/** Any upcoming race, with its plan suggestion if it has one. */
data class RaceEntry(val race: Race, val suggestion: RaceSuggestion?, val kmFromHome: Int?) {
    val date: LocalDate get() = LocalDate.parse(race.date)
    val province: String get() = race.location.substringAfterLast(",", "").trim()
    val town: String get() = race.location.substringBefore(",").trim()
    val kinds: Set<RaceKind>
        get() = RaceAdvisor.options(race).mapNotNull { o ->
            when {
                o.ultra -> RaceKind.ULTRA
                o.km != null && o.km in 40.0..45.0 -> RaceKind.MARATHON
                o.km != null && o.km in 20.0..22.0 -> RaceKind.HALF
                else -> null
            }
        }.toSet()
    val trailOnly: Boolean get() = RaceAdvisor.options(race).let { it.isNotEmpty() && it.all { o -> o.trail } }
}

/** Ranks races by how well they fit the training plan and how close they are to home. */
object RaceAdvisor {
    fun entries(races: List<Race>, suggestions: List<RaceSuggestion>, home: GeoPoint?, geo: (String) -> GeoPoint?, today: LocalDate = LocalDate.now()): List<RaceEntry> {
        val byKey = suggestions.associateBy { "${it.race.slug}|${it.race.date}" }
        return races.filter { runCatching { !LocalDate.parse(it.date).isBefore(today) }.getOrDefault(false) }
            .map { r ->
                val dist = if (home != null) geo(r.location)?.let { haversineKm(home, it).roundToInt() } else null
                RaceEntry(r, byKey["${r.slug}|${r.date}"], dist)
            }
            .sortedWith(compareBy<RaceEntry> { it.race.date }.thenBy { it.race.name })
    }

    private val QUAL_DEADLINE: LocalDate = LocalDate.of(2027, 5, 31)

    fun options(r: Race): List<RaceOption> = r.distances.mapNotNull { chip ->
        val trail = chip.contains("trail", true) || r.name.contains("trail", true)
        when {
            chip.equals("Marathon", true) -> RaceOption("Marathon 42.2 km", 42.2, trail, false)
            chip.equals("Half Marathon", true) -> RaceOption("Half marathon 21.1 km", 21.1, trail, false)
            chip.equals("Ultra", true) -> {
                val km = Regex("""(\d{2,3})\s?km""", RegexOption.IGNORE_CASE).find(r.name)?.groupValues?.get(1)?.toDouble()
                    ?: when {
                        r.name.contains("two oceans", true) -> 56.0
                        r.name.contains("comrades", true) -> 90.0
                        else -> null
                    }
                RaceOption(km?.let { "Ultra ${it.roundToInt()} km" } ?: "Ultra", km, trail, true)
            }
            else -> Regex("""(\d+(?:\.\d+)?)\s?km""", RegexOption.IGNORE_CASE).find(chip)?.groupValues?.get(1)?.toDouble()?.let { km ->
                RaceOption(chip, km, trail, km > 42.5)
            }
        }
    }

    fun suggest(
        races: List<Race>,
        p: Profile,
        home: GeoPoint?,
        geo: (String) -> GeoPoint?,
        today: LocalDate = LocalDate.now(),
    ): List<RaceSuggestion> {
        val raceDay = runCatching { LocalDate.parse(p.raceDate) }.getOrElse { LocalDate.of(2027, 9, 5) }
        val qual = runCatching { LocalDate.parse(p.qualifierDate) }.getOrElse { LocalDate.of(2027, 3, 21) }
        val taperStart = raceDay.minusDays(21)
        val idealTuneUp = raceDay.minusWeeks(12)

        return races.mapNotNull { r ->
            val d = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@mapNotNull null
            if (d.isBefore(today.plusDays(3)) || !d.isBefore(taperStart)) return@mapNotNull null
            if (r.name.contains("cape100", true) || r.name.contains("cape 100", true)) return@mapNotNull null

            val wQ = ChronoUnit.DAYS.between(qual, d) / 7.0
            val candidates = options(r).filter { !it.trail }.mapNotNull { o ->
                val km = o.km
                when {
                    // Road marathon
                    !o.ultra && km != null && km in 40.0..45.0 -> when {
                        !p.qualified && !d.isAfter(QUAL_DEADLINE) && d.isAfter(today.plusWeeks(6)) && wQ <= 3.5 ->
                            Triple(RacePurpose.QUALIFIER, o, (60 - 4 * abs(wQ)).roundToInt().coerceAtLeast(30))
                        !p.qualified && !d.isAfter(QUAL_DEADLINE) && wQ > 3.5 ->
                            Triple(RacePurpose.BACKUP_QUALIFIER, o, 40)
                        (p.qualified || wQ >= 4) && d.isBefore(raceDay.minusWeeks(5)) && d.isAfter(today.plusWeeks(4)) ->
                            Triple(RacePurpose.LONG_RUN_MARATHON, o, 42)
                        else -> null
                    }
                    // Road ultra
                    o.ultra && (km == null || km <= 65.0) -> {
                        val wIdeal = abs(ChronoUnit.DAYS.between(idealTuneUp, d) / 7.0)
                        when {
                            !p.qualified && abs(wQ) < 3 -> null // too close to the qualifier
                            d.isAfter(raceDay.minusWeeks(18)) -> Triple(RacePurpose.TUNE_UP_ULTRA, o, (60 - 3 * wIdeal).roundToInt().coerceAtLeast(30))
                            wQ >= 3 -> Triple(RacePurpose.TUNE_UP_ULTRA, o, 30)
                            else -> null
                        }
                    }
                    // Road half marathon
                    !o.ultra && km != null && km in 20.0..22.0 -> when {
                        !p.qualified && wQ in -10.0..-2.5 -> Triple(RacePurpose.FITNESS_HALF, o, 45)
                        wQ < -10 -> Triple(RacePurpose.FITNESS_HALF, o, 30)
                        wQ >= 3 -> Triple(RacePurpose.FITNESS_HALF, o, 22)
                        else -> null
                    }
                    else -> null
                }
            }
            val best = candidates.maxByOrNull { it.third } ?: return@mapNotNull null
            val point = if (home != null) geo(r.location) else null
            val dist = if (home != null && point != null) haversineKm(home, point).roundToInt() else null
            val distScore = when {
                dist == null -> 10
                dist <= 60 -> 40
                dist <= 150 -> 30
                dist <= 400 -> 15
                dist <= 1000 -> 5
                else -> 0
            }
            val (purpose, option, fit) = best
            RaceSuggestion(r, purpose, option, dist, fit + distScore, reason(purpose, d, qual, raceDay, today), howToRun(purpose, p))
        }.sortedWith(compareBy<RaceSuggestion> { it.purpose.ordinal }.thenByDescending { it.score })
    }

    private fun weeks(a: LocalDate, b: LocalDate) = ChronoUnit.WEEKS.between(a, b)

    private fun reason(purpose: RacePurpose, d: LocalDate, qual: LocalDate, raceDay: LocalDate, today: LocalDate): String {
        val toRace = weeks(d, raceDay)
        return when (purpose) {
            RacePurpose.QUALIFIER -> {
                val diff = ChronoUnit.DAYS.between(qual, d)
                "${weeks(today, d)} weeks of training away. " + when {
                    abs(diff) <= 3 -> "Lines up exactly with your plan's qualifier date."
                    diff < 0 -> "${-diff} days earlier than your planned qualifier – the plan will shift to match."
                    else -> "$diff days after your planned qualifier – more time to get fit, and still leaves a backup before 31 May."
                }
            }
            RacePurpose.BACKUP_QUALIFIER -> "Still inside the qualifying window (by 31 May 2027) if the first attempt fails."
            RacePurpose.TUNE_UP_ULTRA -> "$toRace weeks before CAPE 100. " +
                if (toRace in 9..15) "Ideal timing: long enough to recover, close enough to be race-specific."
                else "Usable, but further from the ideal 10–14 week window."
            RacePurpose.LONG_RUN_MARATHON -> "$toRace weeks before CAPE 100, during your ultra build – a supported long run with company and water tables."
            RacePurpose.FITNESS_HALF -> "${ChronoUnit.WEEKS.between(d, qual).let { if (it > 0) "$it weeks before your qualifier" else "${-it} weeks after your qualifier" }}. Your half time × 2.1 ≈ marathon prediction."
        }
    }

    private fun howToRun(purpose: RacePurpose, p: Profile): String = when (purpose) {
        RacePurpose.QUALIFIER, RacePurpose.BACKUP_QUALIFIER ->
            "Target ~6:45/km (≈4:45) for a safe margin under 5:00. Even pacing, gel every 30–35 min. Submit your official result on the CAPE 100 entry platform afterwards."
        RacePurpose.TUNE_UP_ULTRA ->
            "Run at CAPE 100 effort (~${"%.0f".format(p.goalFinishHours * 60 / p.raceDistanceKm.coerceAtLeast(1))} min/km average) with your run-walk ratio. Same shoes, kit and nutrition as race day. Take a recovery week after."
        RacePurpose.LONG_RUN_MARATHON ->
            "Don't race it. Run easy at ultra pace, practise fuelling at 60–90 g carbs/hour, and count it as that week's long run."
        RacePurpose.FITNESS_HALF ->
            "Race it hard but controlled; replace that week's tempo session. Easy days either side."
    }

    fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(max(0.0, h)))
    }
}
