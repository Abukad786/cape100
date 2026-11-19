package com.cape100.coach.plan

import com.cape100.coach.data.Phase
import com.cape100.coach.data.PlanWeek
import com.cape100.coach.data.Profile
import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a periodised plan for a 100 km road ultra (CAPE 100: Hermanus -> Stellenbosch,
 * ~900 m gain, 05:00 start, 15 h cut-off) including the mandatory sub-5:00 qualifying marathon.
 *
 * Shape: Aerobic Base -> Qualifier Block (12 wks) -> Qualifier marathon -> Reset (2 wks)
 *        -> Ultra Build -> Peak (5 wks) -> Taper (3 wks).
 * Long runs fall on Sunday (race day is a Sunday); back-to-backs are Sat + Sun.
 */
object PlanGenerator {

    fun generate(p: Profile, today: LocalDate = LocalDate.now()): List<PlanWeek> {
        val race = runCatching { LocalDate.parse(p.raceDate) }.getOrElse { LocalDate.of(2027, 9, 5) }
        val startDay = runCatching { LocalDate.parse(p.planStart) }.getOrElse { today }
        val start = startDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val raceWeek = race.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val n = max(1, ChronoUnit.WEEKS.between(start, raceWeek).toInt() + 1)

        val qualifier = runCatching { LocalDate.parse(p.qualifierDate) }.getOrNull()
            ?.takeIf { !p.qualified && it.isAfter(start.minusDays(1)) && it.isBefore(raceWeek.minusWeeks(8)) }
        val qi = qualifier?.let { ChronoUnit.WEEKS.between(start, it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt() }

        val taperStart = max(0, n - 3)
        val peakStart = max(0, taperStart - 5)
        val phases = Array(n) { i ->
            when {
                i >= taperStart -> Phase.TAPER
                i >= peakStart -> Phase.PEAK
                qi != null && i <= qi && i >= qi - 12 -> Phase.MARATHON
                qi != null && i < qi - 12 -> Phase.BASE
                qi != null && i <= qi + 2 -> Phase.RESET
                qi == null && i < peakStart - 14 -> Phase.BASE
                else -> Phase.ULTRA
            }
        }

        val dist = p.raceDistanceKm.coerceIn(42, 200)
        // Start from what you really run now; the 10%-per-week cap then builds volume safely.
        val startKm = max(8.0, p.currentWeeklyKm.toDouble())
        // A sub-5:00 marathon needs roughly 45+ km weeks by the end of the qualifier block.
        val marathonKm = max(startKm, min(75.0, max(45.0, startKm * 1.6)))
        val ultraPeakKm = max(marathonKm, 0.95 * dist + 10).coerceAtMost(130.0)
        val vertPerKm = p.raceElevationM.toDouble() / dist
        val chosenTuneUp = runCatching { LocalDate.parse(p.tuneUpDate) }.getOrNull()
            ?.let { ChronoUnit.WEEKS.between(start, it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt() }
        val tuneUpIdx = (chosenTuneUp ?: (n - 12)).takeIf { it in 0 until n && phases[it] in setOf(Phase.ULTRA, Phase.PEAK) }

        val paces = Paces(p)
        val weeks = mutableListOf<PlanWeek>()
        var prevLoadKm = startKm
        var prevLong = max(10.0, p.longestRecentRunKm.toDouble())
        // Your long-run targets as (week index, km), interpolated from where you start.
        val goals = p.longRunGoals.mapNotNull { g ->
            runCatching { LocalDate.parse(g.date) }.getOrNull()?.let { d ->
                ChronoUnit.WEEKS.between(start, d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt() to g.km
            }
        }.filter { it.first in 0 until n }.sortedBy { it.first }
        // Start high enough that the first target is reachable at no more than +3.5 km a week.
        val startLong = goals.firstOrNull()?.let { (w, km) -> max(prevLong, km - 3.5 * w) } ?: prevLong
        if (goals.isNotEmpty()) prevLong = startLong
        fun goalFor(week: Int): Double? {
            if (goals.isEmpty() || week > goals.last().first) return null
            var (w0, k0) = 0 to startLong
            for ((w1, k1) in goals) {
                if (week <= w1) return if (w1 == w0) k1 else k0 + (k1 - k0) * (week - w0).toDouble() / (w1 - w0)
                w0 = w1; k0 = k1
            }
            return null
        }
        var phaseWeek = 0

        for (i in 0 until n) {
            val phase = phases[i]
            phaseWeek = if (i > 0 && phases[i - 1] == phase) phaseWeek + 1 else 0
            val weekStart = start.plusWeeks(i.toLong())
            val isQualiWeek = qi == i
            val isRaceWeek = i == n - 1
            val goalSoon = goals.any { it.first == i || it.first == i + 1 }
            val recovery = phase in setOf(Phase.BASE, Phase.MARATHON, Phase.ULTRA, Phase.PEAK) &&
                (phaseWeek + 1) % 4 == 0 && !isQualiWeek && qi != i + 1 && tuneUpIdx != i && !goalSoon

            // Underlying target volume for this week, before recovery / taper modifiers.
            val target = when (phase) {
                Phase.BASE -> lerp(startKm, marathonKm * 0.9, frac(i, 0, (qi ?: peakStart) - 12))
                Phase.MARATHON -> lerp(marathonKm * 0.9, marathonKm, frac(phaseWeek, 0, 9))
                Phase.RESET -> marathonKm * (if (i == (qi ?: 0) + 1) 0.4 else 0.65)
                Phase.ULTRA -> lerp(marathonKm * 0.85, ultraPeakKm * 0.92, frac(phaseWeek, 0, max(1, peakStart - (qi?.plus(3) ?: 0) - 1)))
                Phase.PEAK -> ultraPeakKm
                Phase.TAPER -> ultraPeakKm * when (i - taperStart) { 0 -> 0.72; 1 -> 0.52; else -> 0.3 }
            }
            var km = when {
                phase == Phase.RESET || phase == Phase.TAPER -> target
                isQualiWeek -> marathonKm * 0.5 + 42.2
                qi == i + 1 -> marathonKm * 0.75
                else -> min(target, prevLoadKm * 1.1)
            }
            if (!recovery && phase != Phase.RESET && phase != Phase.TAPER && !isQualiWeek && qi != i + 1 && tuneUpIdx != i) prevLoadKm = km
            if (recovery) km *= 0.7

            val sessions = when {
                isRaceWeek -> raceWeek(weekStart, race, p, paces)
                isQualiWeek && qualifier != null -> qualifierWeek(weekStart, qualifier, km, p, paces)
                else -> {
                    // Long-run progression: ~32 km before the marathon, 45-56 km at ultra peak.
                    val scale = dist / 100.0
                    val ultraWeeks = max(1, peakStart - (qi?.plus(3) ?: (peakStart - 14)))
                    val longTarget = when (phase) {
                        Phase.BASE -> lerp(max(10.0, p.longestRecentRunKm.toDouble()), 22.0, frac(i, 0, max(1, (qi ?: peakStart) - 13)))
                        Phase.MARATHON -> lerp(22.0, 32.0, frac(phaseWeek, 0, 9))
                        Phase.RESET -> km * 0.25
                        Phase.ULTRA -> lerp(26.0, 45.0 * scale, frac(phaseWeek, 0, ultraWeeks - 1))
                        Phase.PEAK -> lerp(45.0, 56.0, frac(phaseWeek, 0, 4)) * scale
                        Phase.TAPER -> if (i == taperStart) 30.0 else 20.0
                    }
                    // With short (time-capped) weekdays the weekend long run carries more of the week.
                    val shortWeekdays = p.weekdayMaxMin in 1..45
                    val maxShare = when (phase) {
                        Phase.BASE -> if (shortWeekdays) 0.5 else if (km < 30) 0.4 else 0.35
                        Phase.MARATHON -> if (shortWeekdays) 0.65 else 0.5
                        Phase.RESET -> 0.3
                        Phase.ULTRA -> if (shortWeekdays) 0.6 else 0.5
                        Phase.PEAK -> 0.55; Phase.TAPER -> 0.4
                    }
                    var long = min(longTarget, km * maxShare)
                    if (recovery) long = min(long, prevLong * 0.65)
                    else if (phase !in setOf(Phase.RESET, Phase.TAPER)) {
                        long = min(long, prevLong + 4.0)
                        // Build towards your own targets (never more than +3.5 km on the previous long run).
                        goalFor(i)?.let { g -> long = max(long, min(g, prevLong + 3.5)) }
                        // Once you've reached a target, don't fall far back below it.
                        if (goals.isNotEmpty()) long = max(long, prevLong * 0.85)
                        prevLong = max(prevLong, long)
                    }
                    if (tuneUpIdx == i) long = min(56.0, max(50.0, long))
                    standardWeek(weekStart, phase, phaseWeek, km, roundHalf(long), recovery, tuneUpIdx == i, p, paces, vertPerKm,
                        paces.easyInWeeks(ChronoUnit.WEEKS.between(today, weekStart).coerceAtLeast(0)))
                }
            }
            val actualKm = sessions.sumOf { it.distanceKm }
            if (!recovery && phase != Phase.RESET && phase != Phase.TAPER && !isQualiWeek && qi != i + 1 && tuneUpIdx != i) {
                prevLoadKm = min(prevLoadKm, actualKm).coerceAtLeast(startKm)
            }
            weeks += PlanWeek(
                index = i,
                start = weekStart,
                phase = phase,
                isRecovery = recovery,
                targetKm = roundHalf(actualKm),
                targetVert = sessions.sumOf { it.vertM },
                focus = focusFor(phase, phaseWeek, recovery, isQualiWeek, isRaceWeek, tuneUpIdx == i),
                sessions = sessions,
            )
        }
        return withEnteredRaces(weeks, p)
    }

    /**
     * Puts the races you've entered into the plan: race day itself, a lighter day (or two) before, and easier days after.
     * Longer races get more room either side. The main race and the qualifier are left alone.
     */
    fun withEnteredRaces(weeks: List<PlanWeek>, p: Profile): List<PlanWeek> {
        if (p.enteredRaces.isEmpty()) return weeks
        val changes = mutableMapOf<String, Session>()
        val byDate = weeks.flatMap { it.sessions }.associateBy { it.date }
        val easy = if (p.easyPaceMinPerKm in 4.0..12.0) p.easyPaceMinPerKm else 7.5
        for (r in p.enteredRaces) {
            val day = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: continue
            val planned = byDate[r.date] ?: continue
            if (planned.type == SessionType.RACE || planned.type == SessionType.TUNEUP_RACE) continue
            val d = r.distanceKm
            changes[r.date] = Session(
                r.date, SessionType.EVENT, "RACE · ${r.name} · ${k(d)} km", d, (d * easy * 0.92).roundToInt(), 0,
                "Race effort" + (r.goal.takeIf { it.isNotBlank() }?.let { " · goal $it" } ?: ""),
                raceDayDetails(d, p, r.goal),
                "A race you entered: race practice for CAPE 100 (pacing, fuelling, start-line nerves) and a fitness check.",
            )
            // Before: a shake-out the day before; longer races also get an easy day two days out.
            val before = if (d >= 21) 2 else 1
            for (i in 1..before) {
                val date = day.minusDays(i.toLong()).toString()
                val s = byDate[date] ?: continue
                if (s.type == SessionType.REST || s.type == SessionType.RACE || date in changes) continue
                changes[date] = if (i == 1 && s.distanceKm > 0) Session(date, SessionType.EASY, "Shake-out before ${r.name} · 3 km", 3.0,
                    (3 * easy).roundToInt(), 0, "Very easy + 4 strides",
                    "Easy 3 km (about 20–25 min) with 4 × 15 s relaxed strides near the end. Lay out your kit, pin your number, " +
                        "plan breakfast and getting there. Early night.\n\nOriginal plan: ${s.title}",
                    "Keeps the legs fresh but awake for tomorrow.")
                else if (s.distanceKm > 0) s.copy(type = SessionType.EASY, title = "Easy · ${k(min(s.distanceKm, 5.0))} km",
                    distanceKm = min(s.distanceKm, 5.0), durationMin = (min(s.distanceKm, 5.0) * easy).roundToInt(),
                    intensity = "Easy only", details = "Race in two days – keep it short and easy.\n\nOriginal plan: ${s.details}")
                else Session(date, SessionType.REST, "Rest before ${r.name}", details = "Skip strength the day before a race. Light stretching only.\n\nOriginal plan: ${s.title}")
            }
            // After: recovery scales with distance.
            val after = when { d < 15 -> 1; d < 30 -> 2; else -> 3 }
            for (i in 1..after) {
                val date = day.plusDays(i.toLong()).toString()
                val s = byDate[date] ?: continue
                if (s.type == SessionType.REST || s.type == SessionType.RACE || s.type == SessionType.TUNEUP_RACE || date in changes) continue
                changes[date] = when {
                    // A short race on Saturday: keep Sunday's long run, but shorter and fully easy.
                    d < 15 && s.type == SessionType.LONG -> {
                        val km = roundHalf(s.distanceKm * 0.7)
                        s.copy(title = "Easy long run · ${k(km)} km", distanceKm = km, durationMin = (km * easy).roundToInt(),
                            vertM = (s.vertM * 0.7).roundToInt(), intensity = "Easy only – walk breaks are fine",
                            details = "Yesterday was a race, so today's long run is ~70% and fully easy. Time on feet, not pace.\n\nOriginal plan: ${s.details}")
                    }
                    i == 1 -> Session(date, SessionType.RECOVERY, "Recovery after ${r.name}", min(4.0, s.distanceKm), 30, 0,
                        "Very easy, or walk", "30 min easy walk or very easy jog, then a foam roll. Eat and drink well.\n\nOriginal plan: ${s.title}",
                        "Gets blood moving so you bounce back faster.")
                    else -> if (s.distanceKm > 0) s.copy(type = SessionType.EASY, title = "Easy · ${k(min(s.distanceKm, 6.0))} km",
                        distanceKm = min(s.distanceKm, 6.0), durationMin = (min(s.distanceKm, 6.0) * easy).roundToInt(),
                        intensity = "Easy only", details = "Still recovering from the race – keep it easy.\n\nOriginal plan: ${s.details}") else s
                }
            }
        }
        if (changes.isEmpty()) return weeks
        return weeks.map { w ->
            if (w.sessions.none { it.date in changes }) w
            else {
                val sessions = w.sessions.map { changes[it.date] ?: it }
                w.copy(sessions = sessions, targetKm = roundHalf(sessions.sumOf { it.distanceKm }), targetVert = sessions.sumOf { it.vertM })
            }
        }
    }

    private fun raceDayDetails(d: Double, p: Profile, goal: String): String {
        val cap = if (p.easyHrMax > 0) p.easyHrMax else null
        val pacing = when {
            d <= 12 -> "First 2 km: hold back in the start crowd, around your easy effort${cap?.let { " (HR near $it)" } ?: ""}. " +
                "Middle: comfortably hard – you can say a few words, not sentences. Last 2–3 km: push if you feel good."
            d <= 25 -> "First 5 km easy-plus${cap?.let { " (HR around $it)" } ?: ""}. Settle into a steady, sustainable effort. " +
                "Only pick it up after 15 km if you feel strong. Take water at the stations; a gel around 45–60 min."
            else -> "Run by heart rate, not pace${cap?.let { " – keep it near $it for the first half" } ?: ""}. Walk the steep uphills. " +
                "Fuel every 30–40 min from early on and drink to thirst."
        }
        return "Warm up with 10 min easy jogging and a few strides. $pacing" +
            (goal.takeIf { it.isNotBlank() }?.let { "\n\nYour goal: $it." } ?: "") +
            "\n\nNothing new on race day – kit, shoes and breakfast you've used before. Check your race plan on the Races tab."
    }

    // ---------------------------------------------------------------- weeks

    private fun standardWeek(
        ws: LocalDate, phase: Phase, pw: Int, km: Double, longIn: Double, recovery: Boolean,
        tuneUp: Boolean, p: Profile, pc: Paces, vertPerKm: Double, weekEasyPace: Double = pc.easy,
    ): List<Session> {
        // Low-volume weeks use fewer, longer runs instead of many tiny ones.
        val days = when {
            km < 20 -> 3
            km < 32 -> min(4, p.runDaysPerWeek)
            else -> p.runDaysPerWeek
        }.coerceIn(3, 6)
        val runDays = when (days) { 3 -> setOf(1, 3, 6); 4 -> setOf(1, 3, 5, 6); 5 -> setOf(1, 2, 3, 5, 6); else -> setOf(1, 2, 3, 4, 5, 6) }
        val b2b = !recovery && (phase == Phase.PEAK || (phase == Phase.ULTRA && pw % 2 == 1)) && !tuneUp
        val satIn = when {
            b2b -> min(longIn * 0.5, 25.0)
            // A week built around a big long run (your targets) skips the Saturday run rather than piling on volume.
            longIn >= km * 0.55 -> 0.0
            5 in runDays -> max(5.0, km * 0.1)
            else -> 0.0
        }
        // Keep the week on target: if long + Saturday leave too little for the weekday minimums, shrink them.
        // Time-capped weekdays can be short; with open-ended weekday mornings a run is worth at least 4–5 km.
        val minDay = if (p.weekdayMaxMin > 0) 3.0 else if (km < 30) 4.0 else 5.0
        val budget = km - runDays.count { it in 1..4 } * minDay
        // Not needed with time-capped weekdays: weekday volume is limited anyway, so the weekend keeps its long run.
        // Your long-run targets win too: they are never squeezed.
        val squeeze = if (!tuneUp && p.weekdayMaxMin == 0 && p.longRunGoals.isEmpty() && budget > 0 && longIn + satIn > budget) budget / (longIn + satIn) else 1.0
        val long = roundHalf(longIn * squeeze)
        val sat = roundHalf(satIn * squeeze)
        val weights = mapOf(1 to 1.0, 2 to 1.0, 3 to 1.15, 4 to 0.6).filterKeys { it in runDays }
        val remaining = max(0.0, km - long - sat)
        val wSum = weights.values.sum().takeIf { it > 0 } ?: 1.0
        // Weekday runs must fit your time window (e.g. 30 min before work); what doesn't fit moves to Saturday.
        val capMin = p.weekdayMaxMin.takeIf { it > 0 }
        // Distance that fits the window at your (improving) easy pace for this week.
        val capKm = capMin?.let { roundHalf(it / weekEasyPace) }
        fun rawKmFor(d: Int) = roundHalf(max(minDay, remaining * (weights[d] ?: 0.0) / wSum))
        // With a time window, normal weeks use the whole window (easy running is cheap); recovery/taper weeks stay lighter.
        val fillWindow = capKm != null && !recovery && phase != Phase.RESET && phase != Phase.TAPER && km >= 20
        fun kmFor(d: Int) = if (capKm != null) (if (fillWindow) capKm else min(rawKmFor(d), capKm)) else rawKmFor(d)
        val overflow = if (capKm != null) weights.keys.sumOf { d -> max(0.0, rawKmFor(d) - kmFor(d)) } else 0.0
        val satFinal = if (overflow > 0 && !tuneUp) roundHalf(min(sat + overflow, max(sat, min(long * 0.75, 32.0)))) else sat
        val satCapped = if (phase == Phase.TAPER) min(satFinal, 12.0) else satFinal
        val satIsRun = satCapped >= 3.0 && (5 in runDays || overflow > 0)
        val strength = phase in setOf(Phase.BASE, Phase.MARATHON, Phase.ULTRA) ||
            (phase == Phase.PEAK && pw % 2 == 0)
        val z = zones(p)

        // A tune-up picked from the Races tab sits on its real weekday; otherwise Sunday.
        val tuneUpDay = runCatching { LocalDate.parse(p.tuneUpDate) }.getOrNull()
            ?.takeIf { !it.isBefore(ws) && it.isBefore(ws.plusDays(7)) }?.dayOfWeek?.value?.minus(1) ?: 6

        return (0..6).map { d ->
            val date = ws.plusDays(d.toLong()).toString()
            when {
                tuneUp && d == tuneUpDay - 1 -> Session(date, SessionType.REST, "Rest – race tomorrow",
                    details = "Rest, lay out kit, carb-load and get to bed early.", purpose = "Arrive fresh.")
                tuneUp && d > tuneUpDay -> Session(date, SessionType.RECOVERY, "Post-race recovery",
                    details = "Walk or very easy 20–30 min jog only if your legs feel fine.", purpose = "Recover from the tune-up.")
                d == 0 && homeKit(p) && strength -> Session(date, SessionType.STRENGTH, "Home upper body A · 25 min", durationMin = 25,
                    details = homeUpperBody(p, phase, 'A'), purpose = "Strong arms, shoulders and core keep your posture and arm drive going late in a 100 km race.")
                d == 0 -> Session(date, SessionType.REST, "Rest + mobility", details =
                    "Full rest from running. 10–15 min mobility: hips, calves, glutes, foam-roll quads and ITB.",
                    purpose = "Adaptation happens during rest. Protect the next session.")
                d == tuneUpDay && tuneUp -> Session(date, SessionType.TUNEUP_RACE, "Tune-up ultra ${k(long)} km",
                    long, pc.durMin(long, pc.ultra), (long * vertPerKm).roundToInt(), "Ultra race pace ${pc.fmt(pc.ultra)}/km",
                    "Enter a 50–56 km road ultra (or run this as a supported solo effort). Treat it as a dress rehearsal: " +
                        "same shoes, kit, breakfast and gels as CAPE 100. Start conservatively, use your planned run-walk " +
                        "ratio and take 60–90 g carbs per hour.",
                    "Test everything you will use on 5 September under race conditions.")
                d == 6 -> longRun(date, phase, pw, long, recovery, pc, z, vertPerKm)
                d == 5 && b2b -> Session(date, SessionType.BACK_TO_BACK, "Back-to-back day 1 · ${k(satFinal)} km",
                    satFinal, pc.durMin(satFinal, pc.easy), (satFinal * vertPerKm).roundToInt(), "Easy $z2Label${z.z2}",
                    "Steady easy road run. Eat well afterwards and go to bed early – tomorrow's long run starts on tired legs. " +
                        if (phase == Phase.PEAK) "Start at 05:00 with a headlamp like race morning." else "",
                    "Back-to-backs teach your legs to keep running when fatigued without the damage of one huge run.")
                d == 5 && satIsRun -> weekdayRun(date, d, phase, pw, satCapped, recovery, false, pc, z, vertPerKm, null, p)
                d in runDays && d != 5 -> weekdayRun(date, d, phase, pw, kmFor(d), recovery, strength && d == 3 && homeKit(p), pc, z, vertPerKm, capMin, p)
                (d == 4 || (d == 2 && strength)) && homeKit(p) -> Session(date, SessionType.REST, "Rest + foam roll",
                    durationMin = 10, details = foamRollText(p), purpose = "Recovery between runs.")
                d == 4 || (d == 2 && strength) -> Session(date, SessionType.STRENGTH, "Strength & conditioning",
                    durationMin = 40, details = strengthText(phase), purpose = "Strong legs and core keep your form together in the last 30 km.")
                else -> Session(date, SessionType.REST, "Rest", details = "Rest day. Walk, stretch, sleep.", purpose = "Recovery.")
            }
        }
    }

    private fun weekdayRun(
        date: String, d: Int, phase: Phase, pw: Int, km: Double, recovery: Boolean, strength: Boolean,
        pc: Paces, z: Zones, vertPerKm: Double, capMin: Int? = null, p: Profile? = null,
    ): Session {
        val s = weekdayRunFull(date, d, phase, pw, km, recovery, pc, z, vertPerKm, capMin)
        val dark = if (capMin != null) "\n\nPre-dawn run: headlamp, reflective vest, well-lit route – great practice for the 05:00 race start." else ""
        val extra = if (strength && p != null) "\n\nLater today (or straight after): ${homeUpperBody(p, phase, 'B')}" else ""
        return s.copy(
            durationMin = capMin?.let { min(s.durationMin, it) } ?: s.durationMin,
            details = s.details + dark + extra,
            withStrength = strength,
        )
    }

    private fun weekdayRunFull(
        date: String, d: Int, phase: Phase, pw: Int, km: Double, recovery: Boolean,
        pc: Paces, z: Zones, vertPerKm: Double, capMin: Int?,
    ): Session {
        val short = capMin != null && capMin <= 40
        val easy = { title: String, extra: String ->
            Session(date, SessionType.EASY, "$title · ${k(km)} km", km, pc.durMin(km, pc.easy),
                (km * vertPerKm).roundToInt(), "Easy $z2Label${z.z2}",
                "Conversational pace (about ${pc.fmt(pc.easy)}/km or slower). $extra",
                "Aerobic volume – the foundation of every ultra.")
        }
        if (short && !recovery && phase != Phase.RESET && d in setOf(1, 3)) shortWorkout(date, d, phase, pw, km, pc, z, capMin!!)?.let { return it }
        if (recovery || phase == Phase.RESET) return if (d == 4) Session(date, SessionType.RECOVERY, "Recovery jog · ${k(km)} km",
            km, pc.durMin(km, pc.easy + 0.3), 0, "Very easy, Z1", "Shuffle. If anything hurts, walk instead.", "Blood flow, nothing more.")
        else easy("Easy run", if (d == 1) "Finish with 6 × 20 s relaxed strides." else "")

        return when (d) {
            1 -> when (phase) {
                Phase.BASE -> easy("Easy + strides", "Finish with 8 × 20 s strides (fast but relaxed, full recovery).")
                Phase.MARATHON -> if (pw % 2 == 0)
                    Session(date, SessionType.INTERVALS, "Intervals · ${k(km)} km", km, pc.durMin(km, pc.easy) - 5, 0,
                        "10K effort $z4Label${z.z4}",
                        "15 min warm-up, then ${4 + min(3, pw / 3)} × 1 km at ${pc.fmt(pc.tenK)}/km with 2 min jog. 10 min cool-down.",
                        "Raises your speed ceiling so marathon pace feels easier.")
                else Session(date, SessionType.HILLS, "Hill repeats · ${k(km)} km", km, pc.durMin(km, pc.easy), (km * 12).roundToInt(),
                    "Hard uphill $z4Label${z.z4}",
                    "Warm up 15 min. ${6 + min(4, pw / 2)} × 90 s up a moderate hill at hard effort, jog down. Cool down.",
                    "Strength-speed for the rolling Overberg roads.")
                Phase.ULTRA, Phase.PEAK -> Session(date, SessionType.TEMPO, "Threshold tempo · ${k(km)} km", km, pc.durMin(km, pc.easy) - 3, 0,
                    "Threshold $z4Label${z.z4}",
                    "Warm up 15 min, then ${if (phase == Phase.PEAK) "2 × 15" else "3 × 10"} min at ${pc.fmt(pc.threshold)}/km (comfortably hard), 2 min easy between. Cool down.",
                    "Keeps your running economy sharp while volume is high.")
                Phase.TAPER -> Session(date, SessionType.INTERVALS, "Sharpener · ${k(km)} km", km, pc.durMin(km, pc.easy), 0,
                    "Short & quick", "Warm up, 6 × 2 min at ${pc.fmt(pc.threshold)}/km with 2 min jog. Stay fresh – stop early if heavy.",
                    "Keep the legs snappy while volume drops.")
                else -> easy("Easy run", "")
            }
            3 -> when (phase) {
                Phase.BASE -> if (pw >= 3) Session(date, SessionType.TEMPO, "Fartlek · ${k(km)} km", km, pc.durMin(km, pc.easy), 0,
                    "Mixed", "Easy running with ${6 + min(4, pw / 2)} × 1 min at 10K effort / 2 min easy in the middle.",
                    "Introduces faster running gently.")
                else easy("Steady run", "Rolling route, relaxed.")
                Phase.MARATHON -> {
                    val reps = min(4, 2 + pw / 3)
                    Session(date, SessionType.TEMPO, "Marathon pace · ${k(km)} km", km, pc.durMin(km, pc.marathon), 0,
                        "Goal marathon pace ${pc.fmt(pc.marathon)}/km",
                        "Warm up 2 km, then $reps × 4 km at ${pc.fmt(pc.marathon)}/km (your sub-4:45 qualifier pace) with 1 km easy between. Cool down.",
                        "Locks in qualifier pace so race day feels routine.")
                }
                Phase.ULTRA, Phase.PEAK -> Session(date, SessionType.TEMPO, "Ultra-pace medium-long · ${k(km)} km", km, pc.durMin(km, pc.ultra), (km * vertPerKm).roundToInt(),
                    "Race pace ${pc.fmt(pc.ultra)}/km",
                    "Run at goal CAPE 100 pace including your run-walk pattern (e.g. run 9 min / walk 1 min). Practise drinking on the move.",
                    "Make race pace automatic and efficient.")
                else -> easy("Easy run", "Include 4 × 1 min at marathon effort.")
            }
            else -> easy("Easy run", "")
        }
    }

    /** 30-minute versions of the quality sessions for time-capped weekday mornings. */
    private fun shortWorkout(date: String, d: Int, phase: Phase, pw: Int, km: Double, pc: Paces, z: Zones, cap: Int): Session? {
        val main = cap - 15 // 8 min warm-up + 7 min cool-down
        return when {
            d == 1 && phase == Phase.BASE -> null
            d == 1 && phase == Phase.MARATHON && pw % 2 == 0 -> Session(date, SessionType.INTERVALS, "Short intervals · ${cap} min", km, cap, 0,
                "Hard${z.z4}", "8 min easy warm-up, then ${main / 3} × 2 min hard (${pc.fmt(pc.tenK)}/km) with 1 min easy jog. 7 min easy cool-down.",
                "Speed in a tight time window – makes marathon pace feel easier.")
            d == 1 && phase == Phase.MARATHON -> Session(date, SessionType.HILLS, "Short hill repeats · ${cap} min", km, cap, (km * 12).roundToInt(),
                "Hard uphill${z.z4}", "8 min easy to a hill, then ${main / 2} × 60 s uphill hard, walk/jog down. 7 min easy home.",
                "Strength-speed for the rolling Overberg roads.")
            d == 1 && (phase == Phase.ULTRA || phase == Phase.PEAK) -> Session(date, SessionType.TEMPO, "Tempo · ${cap} min", km, cap, 0,
                "Threshold${z.z4}", "8 min easy, then ${main} min continuous at ${pc.fmt(pc.threshold)}/km (comfortably hard), 7 min easy.",
                "Keeps your running economy sharp.")
            d == 1 && phase == Phase.TAPER -> Session(date, SessionType.INTERVALS, "Sharpener · ${cap} min", km, cap, 0,
                "Short & quick", "8 min easy, 5 × 1 min quick / 1 min easy, then easy to finish. Stay fresh.", "Snappy legs for race day.")
            d == 3 && phase == Phase.BASE && pw >= 3 -> Session(date, SessionType.TEMPO, "Fartlek · ${cap} min", km, cap, 0,
                "Mixed", "8 min easy, then ${main / 3} × (1 min at 10K effort / 2 min easy), easy to finish.", "Introduces faster running gently.")
            d == 3 && phase == Phase.MARATHON -> Session(date, SessionType.TEMPO, "Marathon pace · ${cap} min", km, cap, 0,
                "Goal marathon pace ${pc.fmt(pc.marathon)}/km", "8 min easy, then ${main} min at ${pc.fmt(pc.marathon)}/km (your sub-4:45 qualifier pace), 7 min easy.",
                "Locks in qualifier pace.")
            d == 3 && (phase == Phase.ULTRA || phase == Phase.PEAK) -> Session(date, SessionType.TEMPO, "Ultra pace · ${cap} min", km, cap, 0,
                "Race pace ${pc.fmt(pc.ultra)}/km", "Whole run at CAPE 100 pace with your run-walk pattern (run 9 / walk 1). Sip water on the move.",
                "Makes race pace automatic.")
            else -> null
        }
    }

    private fun homeKit(p: Profile) = p.dumbbellKg > 0

    /** Upper-body circuit for light dumbbells at home (high reps, slow lowering), plus a foam-roll finish. */
    private fun homeUpperBody(p: Profile, phase: Phase, variant: Char): String {
        val kg = if (p.dumbbellKg % 1.0 == 0.0) p.dumbbellKg.toInt().toString() else p.dumbbellKg.toString()
        val rounds = when (phase) { Phase.PEAK -> 2; Phase.TAPER -> 1; else -> 3 }
        val circuit = if (variant == 'A')
            "Push-ups 8–15 (knees if needed) · Bent-over row 15/arm · Overhead press 12–15 · Floor press 15 · Plank 40 s"
        else
            "Renegade row 8/side · Curl-to-press 12 · Triceps kickback 15 · Reverse fly 15 · Side plank 30 s/side · Dead bug 10/side"
        val legs = if (variant == 'B') "\nRunner add-on (5 min, bodyweight): 2 × 15 single-leg calf raises, 2 × 12 glute bridges." else ""
        return "Home upper body $variant with your ${kg} kg dumbbells: $rounds rounds of\n$circuit\n" +
            "Lower slowly (3 s down) – with light weights, tempo and reps do the work. 45–60 s rest between rounds.$legs" +
            (if (p.foamRoller) "\n\n${foamRollText(p)}" else "")
    }

    private fun foamRollText(p: Profile) =
        if (p.foamRoller) "Foam roller 5–10 min: upper back (arms crossed), lats, calves, quads, glutes – 60 s each, slow."
        else "Mobility 10 min: hips, calves, glutes, upper back."

    private fun longRun(date: String, phase: Phase, pw: Int, km: Double, recovery: Boolean, pc: Paces, z: Zones, vertPerKm: Double): Session {
        val title = "Long run · ${k(km)} km"
        val vert = (km * vertPerKm).roundToInt()
        val (details, purpose) = when {
            recovery -> "Easy long run, keep it truly relaxed. Walk the hills if you like." to "Lighter week – absorb the training."
            phase == Phase.BASE -> "Easy, conversational. Practise taking a gel or drink every 30–40 min once over 90 min." to
                "Builds aerobic capacity and durability."
            phase == Phase.MARATHON && pw >= 4 && pw % 2 == 0 -> "First ${(km * 0.6).roundToInt()} km easy, last ${(km * 0.4).roundToInt()} km at marathon pace ${pc.fmt(pc.marathon)}/km. Fuel as in the race: gel every 30–35 min." to
                "Marathon-specific endurance for your qualifier."
            phase == Phase.MARATHON -> "Easy long run on roads at ${pc.fmt(pc.easy)}/km. Fuel every 30–40 min." to "Endurance base for the marathon."
            phase == Phase.ULTRA || phase == Phase.PEAK ->
                ("Road long run at ${pc.fmt(pc.ultra)}/km average using run-walk (e.g. run 9 / walk 1). " +
                    "Take 60–90 g carbs and 500–750 ml fluid per hour, salt in heat. " +
                    (if (phase == Phase.PEAK) "Start at 05:00 in the dark (headlamp + reflective vest) and wear full race kit. " else "") +
                    "Include some hills – the course has ~900 m of climbing.") to
                    "Time on feet, gut training and race rehearsal."
            phase == Phase.TAPER -> "Comfortable long run at easy pace. Nothing to prove." to "Maintain without fatigue."
            else -> "Easy long run." to "Endurance."
        }
        val type = if (recovery || phase == Phase.BASE || phase == Phase.RESET) SessionType.LONG else SessionType.LONG
        return Session(date, type, title, km, pc.durMin(km, if (phase in setOf(Phase.ULTRA, Phase.PEAK)) pc.ultra else pc.easy), vert,
            "Easy $z2Label${z.z2}", details, purpose)
    }

    private fun qualifierWeek(ws: LocalDate, q: LocalDate, km: Double, p: Profile, pc: Paces): List<Session> =
        (0..6).map { d ->
            val day = ws.plusDays(d.toLong())
            val date = day.toString()
            val toRace = ChronoUnit.DAYS.between(day, q).toInt()
            when {
                toRace == 0 -> Session(date, SessionType.TUNEUP_RACE, "QUALIFIER MARATHON", 42.2, (42.2 * pc.marathon).roundToInt(), 0,
                    "Goal ${pc.fmt(pc.marathon)}/km (≈4:45)",
                    "Officially timed road marathon – you need under 4:59:59. Aim for even splits around ${pc.fmt(pc.marathon)}/km; " +
                        "bank nothing in the first half. Gel every 30–35 min. Afterwards submit your official result on the CAPE 100 entry platform (deadline 7 June 2027).",
                    "Earns your start line at CAPE 100.")
                toRace < 0 -> Session(date, SessionType.RECOVERY, "Post-marathon recovery", details = "Walk, eat, sleep. No running.", purpose = "Recover.")
                toRace == 1 -> Session(date, SessionType.REST, "Rest – race kit ready", details = "Lay out kit, pin your number, carb-load, early night.", purpose = "Arrive fresh.")
                toRace == 2 -> Session(date, SessionType.EASY, "Shakeout 5 km", 5.0, 30, 0, "Easy", "Easy 5 km with 4 strides.", "Stay loose.")
                toRace == 4 -> Session(date, SessionType.TEMPO, "Pace check 8 km", 8.0, 50, 0, "Marathon pace", "2 km easy, 3 km at ${pc.fmt(pc.marathon)}/km, easy back.", "Feel the pace.")
                toRace == 5 -> Session(date, SessionType.EASY, "Easy 8 km", 8.0, (8 * pc.easy).roundToInt(), 0, "Easy", "Relaxed.", "Taper.")
                else -> Session(date, SessionType.REST, "Rest", details = "Rest.", purpose = "Taper.")
            }
        }

    private fun raceWeek(ws: LocalDate, race: LocalDate, p: Profile, pc: Paces): List<Session> =
        (0..6).map { d ->
            val day = ws.plusDays(d.toLong())
            val date = day.toString()
            when (ChronoUnit.DAYS.between(day, race).toInt()) {
                0 -> Session(date, SessionType.RACE, "${p.raceName} – ${p.raceDistanceKm} km", p.raceDistanceKm.toDouble(),
                    (p.goalFinishHours * 60).roundToInt(), p.raceElevationM, "Goal ${pc.fmt(pc.ultra)}/km average",
                    "05:00 start in Hermanus, finish in Stellenbosch (cut-off 20:00). Start slower than feels right – the first 50 km " +
                        "should feel easy. Stick to your run-walk ratio from the first kilometre. 60–90 g carbs/h, drink to thirst, salt. " +
                        "Headlamp for the dark start. Break the race into aid-station chunks. You've done the work – enjoy it!",
                    "The day everything was built for.")
                1 -> Session(date, SessionType.REST, "Rest – travel & kit check", details =
                    "Travel to Hermanus, registration, final kit check (headlamp, reflective, nutrition, vaseline). High-carb meals, early night.",
                    purpose = "Calm and ready.")
                2 -> Session(date, SessionType.EASY, "Shakeout 20 min", 3.0, 20, 0, "Easy", "20 min easy + 3 strides.", "Stay loose.")
                3 -> Session(date, SessionType.REST, "Rest", details = "Rest. Start carb-loading (8–10 g/kg on the last 2 days).", purpose = "Top up glycogen.")
                4 -> Session(date, SessionType.EASY, "Easy 30 min + strides", 5.0, 30, 0, "Easy", "30 min easy, 4 × 20 s strides.", "Sharpness.")
                5 -> Session(date, SessionType.REST, "Rest", details = "Rest and sleep.", purpose = "Recovery.")
                6 -> Session(date, SessionType.EASY, "Easy 30 min", 4.0, 30, 0, "Easy", "Relaxed 30 min.", "Maintain.")
                else -> Session(date, SessionType.RECOVERY, "Recovery", details = "Walk and celebrate.", purpose = "You did it.")
            }
        }

    // ---------------------------------------------------------------- helpers

    private fun focusFor(phase: Phase, pw: Int, recovery: Boolean, quali: Boolean, race: Boolean, tuneUp: Boolean) = when {
        race -> "Race week. Rest, carb-load, trust your training."
        quali -> "Qualifier week – sub-5:00 marathon on Sunday."
        tuneUp -> "Tune-up ultra – full dress rehearsal."
        recovery -> "Recovery week: ~30% less volume. Sleep and absorb."
        else -> phase.blurb
    }

    private fun strengthText(phase: Phase) = when (phase) {
        Phase.PEAK -> "Light strength 25 min: 2 × 10 single-leg squats, calf raises, side planks. Nothing heavy."
        else -> "Strength 35–40 min: 3 × 8 squats or split squats, 3 × 10 step-ups, 3 × 15 single-leg calf raises, " +
            "3 × 10 Romanian deadlifts, 3 × 40 s plank + side plank, glute bridges."
    }

    private fun frac(i: Int, from: Int, to: Int): Double = if (to <= from) 1.0 else ((i - from).toDouble() / (to - from)).coerceIn(0.0, 1.0)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    fun roundHalf(x: Double) = (x * 2).roundToInt() / 2.0
    fun k(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else String.format(java.util.Locale.US, "%.1f", x)

    private const val z2Label = ""
    private const val z4Label = ""

    data class Zones(val z2: String, val z4: String)

    fun zones(p: Profile): Zones = if (p.maxHr > 0)
        Zones(" · HR ${(p.maxHr * 0.6).roundToInt()}–${if (p.easyHrMax > 0) p.easyHrMax else (p.maxHr * 0.72).roundToInt()}", " · HR ${(p.maxHr * 0.84).roundToInt()}–${(p.maxHr * 0.9).roundToInt()}")
    else Zones(" · RPE 3–4/10", " · RPE 7–8/10")

    /** Pace guide in min/km, derived from the goal finish time and the sub-4:45 qualifier target. */
    class Paces(p: Profile) {
        val ultra = (p.goalFinishHours * 60.0) / p.raceDistanceKm.coerceAtLeast(1)
        val marathon = 285.0 / 42.195              // 4:45 marathon = safe margin under the 5:00 cut
        private val goalEasy = max(marathon + 0.9, ultra - 0.3)
        /** Your measured easy pace if known (from Garmin), otherwise the goal-based one. */
        val easy = if (p.easyPaceMinPerKm in 4.0..12.0) p.easyPaceMinPerKm else goalEasy
        /** Expected easy pace [weeks] from now: ~0.6% faster per week as fitness builds, never quicker than 6:30/km. */
        fun easyInWeeks(weeks: Long): Double = max(6.5, easy * (1 - 0.006 * weeks)).coerceAtMost(easy)
        val threshold = marathon - 0.6
        val tenK = marathon - 0.9
        fun fmt(minPerKm: Double): String {
            val total = (minPerKm * 60).roundToInt()
            return "%d:%02d".format(total / 60, total % 60)
        }
        fun durMin(km: Double, pace: Double) = (km * pace).roundToInt()
    }
}
