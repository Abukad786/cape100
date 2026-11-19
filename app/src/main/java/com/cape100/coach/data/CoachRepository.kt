package com.cape100.coach.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.cape100.coach.ai.AiCoach
import com.cape100.coach.plan.LoadCheck
import com.cape100.coach.plan.LoadChecker
import com.cape100.coach.races.RaceAdvisor
import com.cape100.coach.races.RaceEntry
import com.cape100.coach.races.RaceKind
import com.cape100.coach.races.RaceFetcher
import com.cape100.coach.races.RaceSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.cape100.coach.garmin.GarminAuth
import com.cape100.coach.health.HealthConnectReader
import com.cape100.coach.scale.BodyComposition
import com.cape100.coach.garmin.GarminClient
import com.cape100.coach.plan.Decision
import com.cape100.coach.plan.PlanGenerator
import com.cape100.coach.plan.ReadinessEngine
import com.cape100.coach.plan.Verdict
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Single place where plan, Garmin data, readiness and AI come together. Used by the UI and background workers. */
class CoachRepository(context: Context) {
    private val appContext = context.applicationContext
    val store = Store.get(context)

    companion object {
        const val SCALE_SOURCE = "OKOK scale"
        private val SA_PROVINCES = listOf(
            "Western Cape", "Eastern Cape", "Northern Cape", "Gauteng", "KwaZulu-Natal", "Free State",
            "Limpopo", "Mpumalanga", "North West",
        )
    }
    private val auth = GarminAuth()
    private val garmin = GarminClient(auth)

    private var cachedPlan: Pair<Profile, List<PlanWeek>>? = null

    fun plan(): List<PlanWeek> {
        val p = store.profile
        cachedPlan?.let { if (it.first == p) return it.second }
        return PlanGenerator.generate(p).also { cachedPlan = p to it }
    }

    /** Weeks between when your training began and where the current plan starts (rebuilds don't reset the count). */
    fun weekOffset(): Int {
        val p = store.profile
        fun monday(s: String) = runCatching { LocalDate.parse(s) }.getOrNull()
            ?.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val journey = monday(p.journeyStart) ?: return 0
        val plan = monday(p.planStart) ?: return 0
        return ChronoUnit.WEEKS.between(journey, plan).toInt().coerceAtLeast(0)
    }

    fun weekFor(date: LocalDate): PlanWeek? = plan().firstOrNull { !date.isBefore(it.start) && date.isBefore(it.start.plusDays(7)) }
    fun sessionFor(date: LocalDate): Session? = weekFor(date)?.sessions?.firstOrNull { it.date == date.toString() }

    fun daysToRace(): Long = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(store.profile.raceDate)) }.getOrDefault(0)

    fun todayCheckIn(): CheckIn? = store.checkIn?.takeIf { it.date == LocalDate.now().toString() }
    fun todaySnapshot(): GarminSnapshot? = store.snapshot?.takeIf { it.date == LocalDate.now().toString() }

    fun todayDecision(): Decision {
        val today = LocalDate.now()
        val planned = sessionFor(today)
        val override = store.dayOverrides.firstOrNull { it.date == today.toString() }
        // Sick: rest, unless today's check-in with the coach cleared you for something gentle.
        if (illnessActive()) {
            val ill = store.illness!!
            val cleared = ill.checks.lastOrNull { it.date == today.toString() }?.takeIf { it.canTrain }
            val days = ChronoUnit.DAYS.between(LocalDate.parse(ill.start), today) + 1
            val reasons = listOf("You're recovering (day $days)" + (if (ill.note.isNotBlank()) ": ${ill.note}" else ""))
            return if (cleared != null && override != null) Decision(Verdict.RECOVER, 40, reasons, planned, override.session, coachNote = override.reason)
            else Decision(Verdict.REST, 0, reasons + "Rest is training while you're sick. Check in with your coach when you feel better.", planned,
                sickRest(today))
        }
        val d = ReadinessEngine.decide(planned, todaySnapshot(), todayCheckIn(), store.history)
        // You can overrule the readiness adjustment and do the planned session anyway.
        val base0 = if (store.plannedOverrideDate == today.toString() && d.planned != null) d.copy(session = d.planned) else d
        // Just back from being sick: the first few days are easier.
        val base = easingBack(today)?.let { (day, of) -> base0.copy(session = base0.session?.let { easeBack(it, day, of) }) } ?: base0
        // A session the coach set after your update wins.
        return override?.let { base.copy(session = it.session, coachNote = it.reason) } ?: base
    }

    /** Session for a day including coach changes (used for tomorrow's Garmin workout). Rest while sick. */
    fun sessionWithOverrides(date: LocalDate): Session? {
        if (illnessActive()) return sickRest(date)
        val s = store.dayOverrides.firstOrNull { it.date == date.toString() }?.session ?: sessionFor(date)
        return easingBack(date)?.let { (day, of) -> s?.let { easeBack(it, day, of) } } ?: s
    }

    // ------------------------------------------------------------ Sick / injured

    fun illnessActive(): Boolean = store.illness?.endDate?.isBlank() == true

    private fun sickRest(date: LocalDate) = Session(date.toString(), SessionType.REST, "Sick day – rest and recover",
        details = "No training today. Sleep, fluids, eat well and take your meds as prescribed. A gentle walk only if you feel like it.",
        purpose = "Training while sick slows recovery and can be risky. Fitness holds for a week or two – you won't lose it.")

    /** Day n of the easing-back period after being sick (and its length), or null. */
    private fun easingBack(date: LocalDate): Pair<Int, Int>? {
        val ill = store.illness ?: return null
        val end = runCatching { LocalDate.parse(ill.endDate) }.getOrNull() ?: return null
        val sickDays = ChronoUnit.DAYS.between(LocalDate.parse(ill.start), end).toInt().coerceAtLeast(1)
        val length = sickDays.coerceIn(2, 5)
        val day = ChronoUnit.DAYS.between(end, date).toInt() + 1
        return if (day in 1..length) day to length else null
    }

    private fun easeBack(s: Session, day: Int, of: Int): Session {
        if (s.distanceKm <= 0) return if (s.type == SessionType.STRENGTH) s.copy(durationMin = minOf(s.durationMin, 15),
            details = "Back from being sick: 1–2 easy rounds only.\n\n" + s.details) else s
        val f = 0.5 + 0.4 * (day - 1) / of.coerceAtLeast(1)
        val km = PlanGenerator.roundHalf((s.distanceKm * f).coerceAtLeast(2.0))
        return s.copy(type = SessionType.EASY, title = "Easing back · ${PlanGenerator.k(km)} km easy", distanceKm = km,
            durationMin = (s.durationMin * f).toInt(), intensity = "Easy only – walk breaks are fine",
            details = "Day $day of $of back after being sick. Keep it truly easy and stop if you feel off, short of breath " +
                "or your heart rate is unusually high for the pace.\n\nOriginal plan: ${s.title}")
    }

    /** You're sick or injured: training pauses, workouts come off your watch. */
    suspend fun startIllness(start: LocalDate, note: String) {
        store.illness = Illness(start.toString(), note.trim())
        val today = LocalDate.now()
        store.dayOverrides = store.dayOverrides.filter { LocalDate.parse(it.date).isBefore(today) }
    }

    /** Back to training: the next few days ease you back in. */
    suspend fun endIllness() {
        val ill = store.illness ?: return
        store.illness = ill.copy(endDate = LocalDate.now().toString())
        store.dayOverrides = store.dayOverrides.filterNot { it.reason.startsWith("Sick check-in") }
    }

    /** "How do you feel today?" while sick: the coach says whether you can try something gentle, and what. */
    suspend fun illnessCheck(feeling: String, note: String): IllnessCheck {
        val ill = store.illness ?: error("Not sick")
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(LocalDate.parse(ill.start), today) + 1
        val report = "Sick since ${ill.start} (day $days): ${ill.note.ifBlank { "no details" }}. " +
            ill.checks.takeLast(4).joinToString(" ") { "On ${it.date} they felt '${it.feeling}' (${it.note})." } +
            " Today they feel: '$feeling'." + (if (note.isNotBlank()) " In their words: \"$note\"." else "")
        val parsed = runCatching {
            val text = AiCoach(store.anthropicKey, store.settings.aiModel).illnessCheckJson(buildContext(), report)
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            store.json.parseToJsonElement(text.substring(s, e + 1)) as kotlinx.serialization.json.JsonObject
        }.getOrNull()
        fun str(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }
        val canTrain: Boolean
        val reply: String
        var session: Session? = null
        if (parsed != null) {
            canTrain = str(parsed, "canTrain") == "true"
            reply = str(parsed, "reply").orEmpty()
            (parsed["session"] as? kotlinx.serialization.json.JsonObject)?.takeIf { canTrain }?.let { o ->
                val type = runCatching { SessionType.valueOf(str(o, "type") ?: "EASY") }.getOrDefault(SessionType.EASY)
                session = Session(today.toString(), type, str(o, "title") ?: "Gentle return", str(o, "distanceKm")?.toDoubleOrNull() ?: 0.0,
                    str(o, "durationMin")?.toDoubleOrNull()?.toInt() ?: 20, intensity = str(o, "intensity").orEmpty(),
                    details = str(o, "details").orEmpty(), purpose = "Set by your coach after your sick-day check-in.")
            }
        } else {
            // No coach available: a cautious rule of thumb.
            canTrain = feeling == "Almost normal"
            reply = if (canTrain) "Glad you're feeling better. Try a 20–30 min walk, or a very easy jog if it feels good, and stop if anything feels off. " +
                "No running with a fever, chest symptoms or body aches." else "Rest today – your body is still fighting it. Check in again tomorrow."
            if (canTrain) session = Session(today.toString(), SessionType.RECOVERY, "Gentle return · 20–30 min", 3.0, 25,
                intensity = "Walk or very easy jog", details = reply, purpose = "A first easy test after being sick.")
        }
        session?.let { s -> store.dayOverrides = store.dayOverrides.filterNot { it.date == today.toString() } +
            DayOverride(today.toString(), s, "Sick check-in: your coach cleared you for something gentle") }
        val check = IllnessCheck(today.toString(), feeling, note.trim(), reply.ifBlank { "Rest today." }, canTrain && session != null)
        store.illness = ill.copy(checks = ill.checks.filterNot { it.date == today.toString() } + check)
        return check
    }

    /** Tell the coach how today went; it replies and may change today/tomorrow. Returns the reply. */
    suspend fun coachUpdate(status: String, note: String): CoachUpdate {
        val today = LocalDate.now()
        val d = todayDecision()
        val report = "Status: $status. " + (if (note.isNotBlank()) "In their words: \"$note\". " else "") +
            "Today's session was: ${d.session?.let { "${it.title} – ${it.details.take(250)}" } ?: "rest"}. " +
            "Tomorrow's plan: ${sessionWithOverrides(today.plusDays(1))?.title ?: "rest"}. " +
            "Garmin activities today: " + store.activities.filter { it.date == today.toString() }
                .joinToString { "${it.type} ${it.distanceKm} km ${it.durationMin} min" }.ifBlank { "none" }
        val text = AiCoach(store.anthropicKey, store.settings.aiModel).coachUpdateJson(buildContext(), report)
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        val obj = if (s >= 0 && e > s) runCatching { store.json.parseToJsonElement(text.substring(s, e + 1)) as kotlinx.serialization.json.JsonObject }.getOrNull() else null
        fun str(o: kotlinx.serialization.json.JsonElement?, k: String) = (o as? kotlinx.serialization.json.JsonObject)?.get(k)
            ?.let { runCatching { (it as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull() }?.takeIf { it != "null" }
        fun session(key: String, date: LocalDate): Session? {
            val o = obj?.get(key) as? kotlinx.serialization.json.JsonObject ?: return null
            val type = runCatching { SessionType.valueOf(str(o, "type") ?: "EASY") }.getOrDefault(SessionType.EASY)
            return Session(
                date = date.toString(), type = type, title = str(o, "title") ?: type.label,
                distanceKm = str(o, "distanceKm")?.toDoubleOrNull() ?: 0.0, durationMin = str(o, "durationMin")?.toDoubleOrNull()?.toInt() ?: 0,
                intensity = str(o, "intensity").orEmpty(), details = str(o, "details").orEmpty(), purpose = "Set by your coach after your update.",
            )
        }
        val reply = str(obj, "reply") ?: text.trim()
        val changes = listOfNotNull(session("today", today)?.let { DayOverride(today.toString(), it, "Changed by your coach after your update") },
            session("tomorrow", today.plusDays(1))?.let { DayOverride(today.plusDays(1).toString(), it, "Changed by your coach after your update") })
        if (changes.isNotEmpty()) store.dayOverrides = store.dayOverrides.filterNot { o -> changes.any { it.date == o.date } } + changes
        when (str(obj, "completed")) {
            "DONE", "PARTIAL" -> setCompletion(today.toString(), "DONE")
            "SKIPPED" -> setCompletion(today.toString(), "SKIPPED")
        }
        return CoachUpdate(today.toString(), status, note, reply).also { store.coachUpdate = it }
    }

    val plannedOverrideToday: Boolean get() = store.plannedOverrideDate == LocalDate.now().toString()

    fun setPlannedOverride(on: Boolean) { store.plannedOverrideDate = if (on) LocalDate.now().toString() else "" }

    /** The one thing to do next today (run → weigh in → stretch → tomorrow). */
    fun nextUp(): com.cape100.coach.plan.NextUp.Step {
        val today = LocalDate.now()
        val p = store.profile
        val session = todayDecision().session
        val usePost = (session?.distanceKm ?: 0.0) > 0 || session?.type == SessionType.STRENGTH
        val stretch = stretchPlanToday().let { if (usePost) it.postRun else it.evening }
        return com.cape100.coach.plan.NextUp.compute(com.cape100.coach.plan.NextUp.Input(
            now = java.time.LocalTime.now(), wakeTime = p.wakeTime, runTime = p.runTime, routineNote = p.routineNote,
            today = session, tomorrow = sessionWithOverrides(today.plusDays(1)),
            sessionDone = store.completions[today.toString()]?.status in setOf("DONE", "SKIPPED"),
            weighedIn = weighedInToday(),
            coachWantsWeighIn = store.aiAdvice?.takeIf { it.date == today.toString() }?.requestWeighIn == true,
            stretchDone = store.stretchDoneDate == today.toString(),
            stretchTitle = stretch.title, stretchMinutes = stretch.minutes, sick = illnessActive(),
        ))
    }

    fun setStretchDone(done: Boolean) { store.stretchDoneDate = if (done) LocalDate.now().toString() else "" }

    // ------------------------------------------------------------ Milestones

    data class Milestone(val km: Double, val label: String, val date: LocalDate?, val done: Boolean, val race: String? = null)

    /** Stepping stones to race day: the first planned run that reaches each distance, plus the key races. */
    fun milestones(): List<Milestone> {
        val sessions = plan().flatMap { it.sessions }.filter { it.distanceKm > 0 }
        val longestDone = store.activities.filter { it.isRunLike }.maxOfOrNull { it.distanceKm } ?: 0.0
        fun firstReaching(km: Double) = sessions.firstOrNull { it.distanceKm >= km - 0.3 }?.let { LocalDate.parse(it.date) }
        val p = store.profile
        val out = mutableListOf<Milestone>()
        (listOf(15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 55.0) + p.longRunGoals.map { it.km }).distinct()
            .filter { km -> km !in setOf(21.1, 42.2, 50.0) && km < p.raceDistanceKm }.forEach { km ->
            val label = if (p.longRunGoals.any { it.km == km }) "${if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()} km long run – your target" else "${km.toInt()} km long run"
            out += Milestone(km, label, firstReaching(km), longestDone >= km - 0.3)
        }
        // Half marathon race near the week the long run first reaches 21 km.
        val halfDate = firstReaching(21.1)
        val half = halfDate?.let { hd ->
            raceEntries().filter { RaceKind.HALF in it.kinds && !it.trailOnly && kotlin.math.abs(ChronoUnit.DAYS.between(hd, it.date)) <= 28 }
                .minWithOrNull(compareBy<RaceEntry>({ kotlin.math.abs(ChronoUnit.DAYS.between(hd, it.date)) / 7 }, { it.kmFromHome ?: 9999 }))
        }
        out += Milestone(21.1, "Half marathon race", half?.date ?: halfDate, longestDone >= 21.0, half?.race?.name)
        val quali = sessions.firstOrNull { it.title.startsWith("QUALIFIER") }
        out += Milestone(42.2, "Qualifier marathon (sub-5:00)", quali?.let { LocalDate.parse(it.date) } ?: runCatching { LocalDate.parse(p.qualifierDate) }.getOrNull(),
            p.qualified || longestDone >= 42.0, store.races.firstOrNull { it.slug == p.qualifierRace }?.name)
        val tune = sessions.firstOrNull { it.type == SessionType.TUNEUP_RACE }
        out += Milestone(50.0, "Tune-up ultra", tune?.let { LocalDate.parse(it.date) }, longestDone >= 50.0, store.races.firstOrNull { it.slug == p.tuneUpRace }?.name)
        out += Milestone(p.raceDistanceKm.toDouble(), p.raceName, runCatching { LocalDate.parse(p.raceDate) }.getOrNull(), false)
        return out.sortedBy { it.km }
    }

    // ------------------------------------------------------------ Garmin

    val garminConnected: Boolean get() = store.garminTokens != null

    suspend fun garminLogin(email: String, password: String, askMfa: suspend () -> String) {
        store.garminTokens = auth.login(email, password, askMfa)
        syncGarmin()
    }

    fun garminLogout() {
        store.garminTokens = null
        store.snapshot = null
    }

    /** Returns null on success or an error message. */
    suspend fun syncGarmin(): String? {
        val tokens = store.garminTokens ?: return "Garmin not connected"
        return try {
            val r = garmin.sync(tokens)
            store.garminTokens = r.tokens
            store.snapshot = r.snapshot
            // Keep older history (e.g. from the Garmin export); newer copies of the same activity win.
            store.activities = (r.activities + store.activities.filter { old -> r.activities.none { it.id == old.id } }).sortedByDescending { it.startLocal }
            val s = r.snapshot
            store.history = store.history.filter { it.date != s.date } +
                DayMetric(s.date, s.readinessScore, s.hrvLastNight, s.restingHr, s.sleepScore)
            autoComplete(r.activities)
            applyGarminBaseline(r.activities)
            updateEasyPace(r.activities)
            updateMaxHr(r.activities)
            refreshRecentDays()
            pullRecentGarminWeights()
            clearGarminWorkouts()
            runCatching { if (health.available && health.hasPermissions()) syncBody() else recordDayHealth() }
            if (aiConfigured && store.profile.baselineSource != "ai") {
                runCatching { aiBaseline("Set the starting point for this athlete's plan, which began on ${store.profile.planStart}.", restartToday = false) }
            }
            store.lastSyncError = null
            null
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName).also { store.lastSyncError = it }
        }
    }

    /**
     * The first time Garmin has enough history, the plan's starting point is set from your real last 4 weeks
     * (weekly km and longest run). Weekly mileage then comes from the plan; later changes go through the plan check.
     */
    private fun applyGarminBaseline(acts: List<ActivitySummary>) {
        val p = store.profile
        if (p.baselineFromGarmin) return
        val since = LocalDate.now().minusDays(28)
        val runs = acts.filter { it.isRunLike && runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(since) == true }
        if (runs.size < 3) return
        store.profile = p.copy(
            currentWeeklyKm = (runs.sumOf { it.distanceKm } / 4).roundToInt().coerceAtLeast(8),
            longestRecentRunKm = runs.maxOf { it.distanceKm }.roundToInt().coerceAtLeast(5),
            baselineFromGarmin = true,
        )
    }

    /**
     * Asks the AI coach where the plan should start from the athlete's real Garmin history, and rebuilds the plan.
     * Returns true when the coach gave an answer.
     */
    suspend fun aiBaseline(why: String, restartToday: Boolean): Boolean {
        if (!aiConfigured) return false
        val today = LocalDate.now()
        val runs = store.activities.filter { it.isRunLike }
        val weekly = (0 until 8).map { w ->
            val from = today.minusDays(7L * (w + 1)); val to = today.minusDays(7L * w)
            val inWeek = runs.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> d.isAfter(from) && !d.isAfter(to) } == true }
            "  week ending $to: ${"%.1f".format(inWeek.sumOf { it.distanceKm })} km in ${inWeek.size} runs, longest ${"%.1f".format(inWeek.maxOfOrNull { it.distanceKm } ?: 0.0)} km"
        }
        val history = buildString {
            appendLine("Weekly running totals (most recent first):")
            weekly.forEach { appendLine(it) }
            appendLine("All recent activities:")
            store.activities.sortedByDescending { it.startLocal }.take(30).forEach {
                appendLine("  ${it.startLocal.take(10)} ${it.type} ${it.distanceKm} km ${it.durationMin} min avgHR ${it.avgHr}")
            }
        }
        val v = AiCoach(store.anthropicKey, store.settings.aiModel).startingVolume(buildContext(), history, why) ?: return false
        store.profile = store.profile.copy(
            currentWeeklyKm = v.weeklyKm,
            longestRecentRunKm = v.longestRunKm,
            baselineFromGarmin = true,
            baselineSource = "ai",
            baselineNote = v.note,
            longRunGoals = v.goals.ifEmpty { store.profile.longRunGoals },
            planStart = if (restartToday) today.toString() else store.profile.planStart,
            journeyStart = store.profile.journeyStart.ifBlank { store.profile.planStart },
        )
        return true
    }

    /** Result of a message in the Plan tab's box: the coach's reply, and whether it rebuilt the plan. */
    data class PlanFeedback(val reply: String, val changed: Boolean)

    /** Questions get answered; only a clear request to change the plan rebuilds it (week numbers carry on). */
    suspend fun planFeedback(message: String): PlanFeedback {
        val text = AiCoach(store.anthropicKey, store.settings.aiModel).planRequestJson(buildContext(), message)
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        val obj = if (s >= 0 && e > s) runCatching { store.json.parseToJsonElement(text.substring(s, e + 1)) as kotlinx.serialization.json.JsonObject }.getOrNull() else null
        fun prim(k: String) = (obj?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
        val reply = prim("reply") ?: text.trim()
        if (prim("change") != "true") return PlanFeedback(reply, false)
        val ok = aiBaseline("The athlete says: \"${message.trim()}\". Re-decide where their plan starts from this week (weekly km and long run).", restartToday = true)
        return PlanFeedback(reply, ok)
    }

    // ------------------------------------------------------------ Scale + health record

    val health = HealthConnectReader(appContext)

    /** Reads scale data from Health Connect. Returns an error message or null. */
    suspend fun syncBody(): String? {
        if (!health.available) return "Health Connect isn't available on this phone"
        if (!health.hasPermissions()) return "Allow Cape 100 Coach to read your weight in Health Connect"
        return try {
            val r = health.readBody()
            // Keep weigh-ins captured straight from the scale over Bluetooth alongside Health Connect ones.
            // Keep scale/OKOK/Garmin-API readings; add Health Connect ones that aren't already there.
            store.updateBodyReadings { existing ->
                existing + r.readings.filter { h -> existing.none { e -> e.date == h.date && kotlin.math.abs((e.weightKg ?: 0.0) - (h.weightKg ?: 0.0)) < 0.3 } }
            }
            r.heightM?.let { store.heightM = it }
            recordDayHealth()
            if (r.readings.isEmpty()) "No weigh-ins in Health Connect yet – check OKOK → Google Fit → Health Connect sync" else null
        } catch (e: Exception) {
            "Couldn't read Health Connect: ${e.message}"
        }
    }

    // ------------------------------------------------------------ Garmin <-> health record

    /** Merges partial day entries into the health record, keeping existing values where the update has none. */
    fun upsertDays(updates: Collection<DayHealth>) {
        if (updates.isEmpty()) return
        val map = store.healthHistory.associateBy { it.date }.toMutableMap()
        updates.forEach { u ->
            val o = map[u.date] ?: DayHealth(u.date)
            map[u.date] = o.copy(
                weightKg = o.weightKg ?: u.weightKg, bodyFatPct = o.bodyFatPct ?: u.bodyFatPct, leanMassKg = o.leanMassKg ?: u.leanMassKg,
                waterPct = o.waterPct ?: u.waterPct, bmi = o.bmi ?: u.bmi, bmrKcal = o.bmrKcal ?: u.bmrKcal,
                readiness = u.readiness ?: o.readiness, restingHr = u.restingHr ?: o.restingHr, hrv = u.hrv ?: o.hrv,
                sleepScore = u.sleepScore ?: o.sleepScore, sleepHours = u.sleepHours ?: o.sleepHours, bodyBattery = u.bodyBattery ?: o.bodyBattery,
                stress = u.stress ?: o.stress, vo2max = u.vo2max ?: o.vo2max, steps = u.steps ?: o.steps, km7d = u.km7d ?: o.km7d,
            )
        }
        store.healthHistory = map.values.toList()
    }


    /** Weekly running km for every day in the record, from Garmin activities. */
    private fun km7dFor(date: LocalDate): Double = PlanGenerator.roundHalf(
        store.activities.filter { it.isRunLike && runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> d.isAfter(date.minusDays(7)) && !d.isAfter(date) } == true }
            .sumOf { it.distanceKm },
    )

    /**
     * Builds the overall health record from Garmin history: daily wellness for [days] days, overnight HRV, VO2max,
     * readiness (last 90 days) and Garmin weigh-ins, merged with scale data. Resumable – already-filled days are skipped.
     */
    suspend fun backfillHistory(days: Int = 365, onProgress: (String) -> Unit = {}) {
        var tokens = store.garminTokens ?: return
        val today = LocalDate.now()
        val start = today.minusDays(days.toLong())
        try {
            // Ranges first (cheap): HRV and VO2max in 28-day windows, weigh-ins in 90-day windows.
            val ranged = mutableMapOf<String, DayHealth>()
            var from = start
            while (from.isBefore(today)) {
                val to = minOf(from.plusDays(27), today)
                garmin.hrvRange(tokens, from, to).let { (t, m) -> tokens = t; m.forEach { (d, v) -> ranged[d] = (ranged[d] ?: DayHealth(d)).copy(hrv = v) } }
                garmin.vo2Range(tokens, from, to).let { (t, m) -> tokens = t; m.forEach { (d, v) -> ranged[d] = (ranged[d] ?: DayHealth(d)).copy(vo2max = v) } }
                from = to.plusDays(1)
            }
            upsertDays(ranged.values)
            val garminWeights = mutableListOf<BodyReading>()
            from = start
            while (from.isBefore(today)) {
                val to = minOf(from.plusDays(89), today)
                garmin.weightRange(tokens, from, to).let { (t, list) -> tokens = t; garminWeights += list }
                from = to.plusDays(1)
            }
            mergeGarminWeights(garminWeights)
            store.garminTokens = tokens

            // Day by day: resting HR, sleep, stress, steps, Body Battery (+ readiness for the last 90 days).
            val have = store.healthHistory.associateBy { it.date }
            val todo = (1..days).map { today.minusDays(it.toLong()) }
                .filter { d -> have[d.toString()]?.let { it.restingHr == null || it.sleepScore == null } ?: true }
            val batch = mutableListOf<DayHealth>()
            todo.forEachIndexed { i, d ->
                var day = garmin.dayWellness(tokens, d).let { (t, v) -> tokens = t; v }
                if (d.isAfter(today.minusDays(91))) day = day.copy(readiness = garmin.dayReadiness(tokens, d).let { (t, v) -> tokens = t; v })
                batch += day.copy(km7d = km7dFor(d))
                if (batch.size >= 14 || i == todo.lastIndex) {
                    upsertDays(batch); batch.clear()
                    store.garminTokens = tokens
                    val note = "Building history from Garmin… ${i + 1}/${todo.size} days"
                    store.backfillState = note
                    onProgress(note)
                }
                kotlinx.coroutines.delay(120)
            }
            recordDayHealth()
            store.backfillState = "done"
            onProgress("done")
        } catch (e: Exception) {
            store.garminTokens = tokens
            store.backfillState = "Paused: ${e.message}"
            onProgress(store.backfillState)
        }
    }

    /** Re-reads the last few days from Garmin (sleep/HRV finalise after the fact) – runs on every sync. */
    private suspend fun refreshRecentDays(n: Int = 3) {
        var tokens = store.garminTokens ?: return
        val today = LocalDate.now()
        runCatching {
            val updates = (0 until n).map { i ->
                val d = today.minusDays(i.toLong())
                garmin.dayWellness(tokens, d).let { (t, v) -> tokens = t; v }.copy(km7d = km7dFor(d))
            }
            garmin.hrvRange(tokens, today.minusDays(n.toLong()), today).let { (t, m) -> tokens = t
                upsertDays(updates.map { u -> u.copy(hrv = m[u.date] ?: u.hrv) }) }
            store.garminTokens = tokens
        }
    }

    /** Brings in the last 30 days of Garmin Connect weigh-ins (e.g. ones made on another phone) – runs on every sync. */
    private suspend fun pullRecentGarminWeights() {
        val tokens = store.garminTokens ?: return
        runCatching {
            val today = LocalDate.now()
            garmin.weightRange(tokens, today.minusDays(30), today).let { (t, list) -> store.garminTokens = t; mergeGarminWeights(list) }
        }
    }

    /** Adds Garmin Connect weigh-ins to the body record, skipping ones that are really our own uploads of scale readings. */
    private fun mergeGarminWeights(list: List<BodyReading>) {
        if (list.isEmpty()) return
        store.updateBodyReadings { existing ->
            existing + list.filter { g -> existing.none { e -> e.date == g.date && kotlin.math.abs((e.weightKg ?: 0.0) - (g.weightKg ?: 0.0)) < 0.3 } }
        }
    }

    /** Sends one scale weigh-in to Garmin Connect and marks it as sent. */
    suspend fun uploadToGarmin(r: BodyReading): Boolean {
        val tokens = store.garminTokens ?: return false
        val kg = r.weightKg ?: return false
        return runCatching {
            store.garminTokens = garmin.uploadWeight(tokens, kg, java.time.Instant.parse(r.time))
            store.updateBodyReadings { list -> list.map { if (it.time == r.time && it.source == r.source) it.copy(garminUploaded = true) else it } }
        }.isSuccess
    }

    /** Sends imported OKOK history to Garmin, skipping days Garmin already has a matching weight for. Returns count sent. */
    suspend fun uploadOkokHistoryToGarmin(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val pending = store.bodyReadings.filter { it.source.startsWith("OKOK") && !it.garminUploaded && it.weightKg != null }
            .filter { o -> store.bodyReadings.none { g -> g.source == "Garmin" && g.date == o.date && kotlin.math.abs((g.weightKg ?: 0.0) - o.weightKg!!) < 0.3 } }
        var sent = 0
        pending.forEachIndexed { i, r ->
            if (uploadToGarmin(r)) sent++
            onProgress(i + 1, pending.size)
            kotlinx.coroutines.delay(400)
        }
        return sent
    }

    @kotlinx.serialization.Serializable
    private data class GarminImport(val activities: List<ActivitySummary>, val days: List<DayHealth>, val insights: GarminInsights? = null)

    /**
     * Imports a Garmin data export (converted to garmin_import.json in the app's files folder): every activity,
     * years of daily health (sleep, resting HR, steps, stress, Body Battery, readiness, VO2max) and Garmin's
     * race predictions and bests. Fills gaps only – nothing already recorded is overwritten. Returns activities added.
     */
    fun importGarminExport(): Int {
        val file = java.io.File(appContext.filesDir, "garmin_import.json")
        if (!file.exists()) return 0
        val data = runCatching { store.json.decodeFromString(GarminImport.serializer(), file.readText()) }.getOrNull()
        if (data == null) { file.delete(); return 0 }
        val existing = store.activities
        val known = existing.map { it.id }.toSet()
        val added = data.activities.filter { it.id !in known }
        store.activities = (existing + added).sortedByDescending { it.startLocal }
        store.updateHealthHistory { days ->
            val byDate = days.associateBy { it.date }.toMutableMap()
            data.days.forEach { g ->
                val d = byDate[g.date]
                byDate[g.date] = if (d == null) g else d.copy(
                    readiness = d.readiness ?: g.readiness, restingHr = d.restingHr ?: g.restingHr,
                    sleepScore = d.sleepScore ?: g.sleepScore, sleepHours = d.sleepHours ?: g.sleepHours,
                    bodyBattery = d.bodyBattery ?: g.bodyBattery, stress = d.stress ?: g.stress,
                    vo2max = d.vo2max ?: g.vo2max, steps = d.steps ?: g.steps,
                )
            }
            byDate.values.toList()
        }
        data.insights?.let { store.garminInsights = it }
        file.delete()
        return added.size
    }

    @kotlinx.serialization.Serializable
    private data class OkokImport(val readings: List<BodyReading>, val sex: String = "", val birthYear: Int = 0, val heightCm: Int = 0)

    /**
     * Imports OKOK history converted from its weight.xls export (dropped into the app's files folder as okok_import.json).
     * Merges by timestamp, fills in height/birth year/sex if they're not set yet, then deletes the file. Returns readings added.
     */
    fun importPendingOkok(): Int {
        val file = java.io.File(appContext.filesDir, "okok_import.json")
        if (!file.exists()) return 0
        val data = runCatching { store.json.decodeFromString(OkokImport.serializer(), file.readText()) }.getOrNull()
        if (data == null) { file.delete(); return 0 }
        var addedCount = 0
        store.updateBodyReadings { existing ->
            val known = existing.map { it.time.take(16) }.toSet()
            val added = data.readings.filter { it.time.take(16) !in known }
            addedCount = added.size
            existing + added
        }
        val p = store.profile
        store.profile = p.copy(
            heightCm = if (p.heightCm > 0) p.heightCm else data.heightCm,
            birthYear = if (p.birthYear > 0) p.birthYear else data.birthYear,
            sex = p.sex.ifBlank { data.sex },
        )
        recordDayHealth()
        file.delete()
        return addedCount
    }

    /** Asks the AI coach for warm feedback on a weigh-in (change vs last time, trends, body comp, post-run hydration). */
    suspend fun weighInFeedback(r: BodyReading): WeighInFeedback? {
        if (!aiConfigured) return null
        val date = LocalDate.parse(r.date)
        val previous = store.bodyReadings.firstOrNull { it.time < r.time && it.weightKg != null }
        val week = bodyOn(date.minusDays(7))
        val month = bodyOn(date.minusDays(30))
        val ranToday = store.activities.any { it.isRunLike && it.date == r.date }
        fun d(a: Double?, b: Double?) = if (a != null && b != null) "%+.1f".format(a - b) else "n/a"
        val text = buildString {
            appendLine("Weight ${r.weightKg?.let { "%.1f".format(it) }} kg at ${r.time.take(16)} (${r.source})" +
                (r.bodyFatPct?.let { ", body fat %.1f%%".format(it) } ?: "") + (r.muscleKg?.let { ", muscle %.1f kg".format(it) } ?: "") +
                (r.waterPct?.let { ", water %.0f%%".format(it) } ?: "") + (r.visceralFat?.let { ", visceral %.0f".format(it) } ?: ""))
            appendLine("vs last weigh-in (${previous?.date ?: "none"}): ${d(r.weightKg, previous?.weightKg)} kg, body fat ${d(r.bodyFatPct, previous?.bodyFatPct)} pts")
            appendLine("vs 7 days ago: ${d(r.weightKg, week?.weightKg)} kg · vs 30 days ago: ${d(r.weightKg, month?.weightKg)} kg, body fat ${d(r.bodyFatPct, month?.bodyFatPct)} pts")
            appendLine(if (ranToday) "They ran earlier today, so this is a post-run weight (some sweat loss)." else "No run logged yet today.")
        }
        val reply = AiCoach(store.anthropicKey, store.settings.aiModel).weighInFeedback(buildContext(), text)
        return WeighInFeedback(r.time, reply.trim()).also { store.weighInFeedback = it }
    }

    // ------------------------------------------------------------ Stretching & movement

    /** Today's stretch plan: the coach's if available, otherwise a solid default built around your tight areas. */
    fun stretchPlanToday(): StretchPlan =
        store.stretchPlan?.takeIf { it.date == LocalDate.now().toString() } ?: defaultStretchPlan()

    private fun defaultStretchPlan(): StretchPlan {
        val roller = store.profile.foamRoller
        val post = StretchRoutine("Post-run stretch", 10, listOfNotNull(
            StretchMove("Standing hamstring stretch", "2 × 40 s each leg", "Heel on a low step, leg straight, hinge forward from the hips with a flat back."),
            StretchMove("Figure-4 glute stretch", "2 × 40 s each side", "On your back, ankle over the opposite knee, pull the bottom leg towards you."),
            StretchMove("Standing quad stretch", "2 × 40 s each leg", "Hold your ankle behind you, knees together, squeeze the glute and tuck the pelvis."),
            StretchMove("Kneeling hip-flexor lunge", "2 × 40 s each side", "Back knee down, tuck the pelvis and shift forward until the front of the hip stretches."),
            StretchMove("Wall calf stretch", "40 s straight + 40 s bent knee", "Hands on the wall, heel down; then bend the back knee for the lower calf."),
            if (roller) StretchMove("Foam roll quads & glutes", "60 s each", "Slow rolls, pause on tender spots, breathe.") else null,
        ))
        val evening = StretchRoutine("Evening release", 15, listOfNotNull(
            if (roller) StretchMove("Foam roll calves, hamstrings, glutes", "60 s each", "Slow, steady pressure – don't roll joints.") else null,
            StretchMove("Lying hamstring stretch with towel", "2 × 60 s each leg", "Loop a towel round your foot, keep the other leg flat, pull gently."),
            StretchMove("Pigeon pose", "60 s each side", "Front shin across, back leg long, fold forward over the front leg."),
            StretchMove("Couch stretch (quads/hip flexors)", "60 s each side", "Back knee against a wall or couch, front foot forward, stay tall."),
            StretchMove("Child's pose", "60 s", "Knees wide, sink the hips back, arms long – breathe slowly."),
        ))
        val desk = listOf(
            StretchMove("Seated hamstring reach", "30 s each leg", "Sit on the edge of the chair, one leg straight, hinge forward."),
            StretchMove("Seated figure-4", "30 s each side", "Ankle on the opposite knee, lean forward with a flat back."),
            StretchMove("Standing quad stretch", "30 s each leg", "Hold a desk for balance."),
            StretchMove("Walk + calf raises", "1 min walk, 15 raises", "Get the blood moving."),
        )
        return StretchPlan(LocalDate.now().toString(), post, evening, desk, byCoach = false)
    }

    /** Asks the coach to write today's stretch routines (cached for the day). */
    suspend fun refreshStretchPlan(force: Boolean = false): StretchPlan {
        val cached = store.stretchPlan?.takeIf { it.date == LocalDate.now().toString() && it.byCoach }
        if (cached != null && !force) return cached
        if (!aiConfigured) return stretchPlanToday()
        val d = todayDecision()
        val today = (d.session?.let { "${it.title} (${it.type.label}, ${it.durationMin} min)" } ?: "rest day") +
            "; readiness ${d.score}; activities today: " + store.activities.filter { it.date == LocalDate.now().toString() }
                .joinToString { "${it.type} ${it.distanceKm} km" }.ifBlank { "none yet" }
        val text = AiCoach(store.anthropicKey, store.settings.aiModel).stretchPlanJson(buildContext(), today, store.profile.tightAreas, store.profile.foamRoller)
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        val parsed = if (s >= 0 && e > s) runCatching { store.json.decodeFromString(StretchPlanAi.serializer(), text.substring(s, e + 1)) }.getOrNull() else null
        val plan = parsed?.let { StretchPlan(LocalDate.now().toString(), it.postRun, it.evening, it.desk, byCoach = true) } ?: return stretchPlanToday()
        store.stretchPlan = plan
        return plan
    }

    @kotlinx.serialization.Serializable
    private data class StretchPlanAi(val postRun: StretchRoutine, val evening: StretchRoutine, val desk: List<StretchMove> = emptyList())

    /** True when today's plan has a run (for the post-run stretch reminder). */
    fun runPlannedToday(): Boolean = (todayDecision().session?.distanceKm ?: 0.0) > 0 ||
        store.activities.any { it.isRunLike && it.date == LocalDate.now().toString() }

    /**
     * Movement nudge check (hourly, 09:00–19:00): if your Garmin steps barely moved since the last check and you've been
     * sedentary, returns a desk move to suggest. At most 3 nudges a day, 2 hours apart.
     */
    fun movementNudge(): StretchMove? {
        if (!store.settings.movementNudges || illnessActive()) return null
        val now = java.time.LocalDateTime.now()
        if (now.hour !in 10..18) return null
        val steps = todaySnapshot()?.steps ?: return null
        val parts = store.nudgeState.split("|")
        val today = LocalDate.now().toString()
        var count = if (parts.getOrNull(0) == today) parts.getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val lastMs = if (parts.getOrNull(0) == today) parts.getOrNull(2)?.toLongOrNull() ?: 0L else 0L
        val lastSteps = if (parts.getOrNull(0) == today) parts.getOrNull(3)?.toIntOrNull() ?: steps else steps
        val idle = steps - lastSteps < 250
        val due = System.currentTimeMillis() - lastMs > 2 * 3600_000L && count < 2
        val move = if (idle && due && parts.getOrNull(0) == today) stretchPlanToday().desk.getOrNull(count % 4) ?: return null else null
        if (move != null) count++
        store.nudgeState = listOf(today, count, if (move != null || lastMs == 0L) System.currentTimeMillis() else lastMs, steps).joinToString("|")
        return move
    }

    // ------------------------------------------------------------ Workouts on the watch (switched off)

    /**
     * The app no longer sends workouts to Garmin – you run with the watch's normal Run activity. This removes every
     * "Cape100 · …" workout it put on your Garmin calendar earlier (once; it stays quiet after that).
     */
    suspend fun clearGarminWorkouts() {
        if (store.garminWorkoutsCleared) return
        var tokens = store.garminTokens ?: return
        runCatching {
            garmin.appWorkoutIds(tokens).let { (t, ids) -> tokens = t; ids.forEach { tokens = garmin.deleteWorkout(tokens, it) } }
            store.garminTokens = tokens
            store.pushedWorkouts = emptyList()
            store.garminWorkoutsCleared = true
        }
    }

    /** True when there is a weigh-in from today. */
    fun weighedInToday(): Boolean = store.bodyReadings.any { it.date == LocalDate.now().toString() }

    /** True when height, birth year and sex are set, so body composition can be worked out from impedance. */
    fun canComputeBody(): Boolean = heightM() != null && store.profile.birthYear > 1900 && store.profile.sex in setOf("M", "F")

    /** Saves a weigh-in captured directly from the OKOK scale over Bluetooth. */
    fun saveScaleReading(weightKg: Double, impedance: Double?): BodyReading {
        val p = store.profile
        val now = java.time.Instant.now()
        val comp = if (impedance != null && canComputeBody())
            BodyComposition.compute(weightKg, impedance, heightM()!! * 100, LocalDate.now().year - p.birthYear, p.sex == "M") else null
        val r = BodyReading(
            time = now.toString(), date = LocalDate.now().toString(),
            weightKg = PlanGenerator.roundHalf(weightKg * 10) / 10,
            bodyFatPct = comp?.bodyFatPct, leanMassKg = comp?.leanMassKg, boneMassKg = comp?.boneMassKg,
            waterKg = comp?.let { it.waterPct / 100 * weightKg }, bmrKcal = comp?.bmrKcal,
            muscleKg = comp?.muscleKg, visceralFat = comp?.visceralFat, bodyAge = comp?.bodyAge, impedance = impedance,
            source = SCALE_SOURCE,
        )
        store.updateBodyReadings { listOf(r) + it }
        recordDayHealth()
        return r
    }

    fun heightM(): Double? = store.profile.heightCm.takeIf { it > 0 }?.div(100.0) ?: store.heightM.takeIf { it > 0.5 }

    fun bmi(weightKg: Double?): Double? = heightM()?.let { h -> weightKg?.let { it / (h * h) } }

    /** Latest weigh-in on or before [date]. */
    fun bodyOn(date: LocalDate): BodyReading? = store.bodyReadings.firstOrNull { !LocalDate.parse(it.date).isAfter(date) }

    /** Writes/updates today's entry in the overall health record from Garmin + scale + training. */
    fun recordDayHealth() {
        val today = LocalDate.now()
        val g = todaySnapshot()
        val body = store.bodyReadings.firstOrNull { it.date == today.toString() } ?: bodyOn(today)
        val lastComp = store.bodyReadings.firstOrNull { it.bodyFatPct != null }
        val km7 = store.activities.filter { it.isRunLike && runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(today.minusDays(7)) == true }
            .sumOf { it.distanceKm }
        val entry = DayHealth(
            date = today.toString(),
            weightKg = body?.weightKg, bodyFatPct = body?.bodyFatPct ?: lastComp?.bodyFatPct, leanMassKg = body?.leanMassKg ?: lastComp?.leanMassKg,
            waterPct = body?.waterPct ?: lastComp?.waterPct, bmi = bmi(body?.weightKg), bmrKcal = body?.bmrKcal,
            readiness = g?.readinessScore, restingHr = g?.restingHr, hrv = g?.hrvLastNight, sleepScore = g?.sleepScore,
            sleepHours = g?.sleepHours, bodyBattery = g?.bodyBattery, stress = g?.stressAvg, vo2max = g?.vo2max,
            steps = g?.steps, km7d = PlanGenerator.roundHalf(km7),
        )
        // Backfill days that only have a weigh-in (e.g. history from before the app was installed).
        val existing = store.healthHistory.associateBy { it.date }.toMutableMap()
        store.bodyReadings.groupBy { it.date }.forEach { (d, list) ->
            val b = list.first()
            val e = existing[d]
            existing[d] = (e ?: DayHealth(d)).copy(
                weightKg = b.weightKg, bodyFatPct = b.bodyFatPct, leanMassKg = b.leanMassKg, waterPct = b.waterPct,
                bmi = bmi(b.weightKg), bmrKcal = b.bmrKcal,
            )
        }
        val old = existing[entry.date]
        existing[entry.date] = if (old == null) entry else entry.copy(
            readiness = entry.readiness ?: old.readiness, restingHr = entry.restingHr ?: old.restingHr, hrv = entry.hrv ?: old.hrv,
            sleepScore = entry.sleepScore ?: old.sleepScore, sleepHours = entry.sleepHours ?: old.sleepHours,
            bodyBattery = entry.bodyBattery ?: old.bodyBattery, stress = entry.stress ?: old.stress, vo2max = entry.vo2max ?: old.vo2max,
            steps = entry.steps ?: old.steps, weightKg = entry.weightKg ?: old.weightKg, bodyFatPct = entry.bodyFatPct ?: old.bodyFatPct,
        )
        // Garmin wellness history we already keep (readiness/HRV/RHR/sleep) for earlier days.
        store.history.forEach { m ->
            val e = existing[m.date] ?: DayHealth(m.date)
            existing[m.date] = e.copy(readiness = e.readiness ?: m.readiness, hrv = e.hrv ?: m.hrv, restingHr = e.restingHr ?: m.rhr, sleepScore = e.sleepScore ?: m.sleep)
        }
        store.healthHistory = existing.values.toList()
    }

    /**
     * Keeps the plan's easy pace in step with reality: median pace of your easy-ish runs (3+ km, running only)
     * over the last 6 weeks. Only updates when it moves by more than ~3%, so the plan doesn't jitter.
     */
    /**
     * Max HR from your runs: the second-highest reading (so one strap spike doesn't count).
     * The first time it replaces the typed-in value; after that it only goes up, so old peaks
     * dropping out of the synced window don't pull it down.
     */
    private fun updateMaxHr(acts: List<ActivitySummary>) {
        val peaks = acts.filter { it.type.contains("running", true) }.mapNotNull { it.maxHr }.filter { it in 150..215 }.sortedDescending()
        val seen = peaks.getOrNull(1) ?: return
        val p = store.profile
        if (!p.maxHrFromGarmin || seen > p.maxHr) store.profile = p.copy(maxHr = seen, maxHrFromGarmin = true)
    }

    private fun updateEasyPace(acts: List<ActivitySummary>) {
        val since = LocalDate.now().minusDays(42)
        val paces = acts.filter {
            it.type.contains("running", true) && it.distanceKm >= 3 &&
                runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(since) == true
        }.map { it.durationMin / it.distanceKm }.filter { it in 4.0..12.0 }.sorted()
        if (paces.size < 3) return
        val median = paces[paces.size / 2]
        val p = store.profile
        if (p.easyPaceMinPerKm == 0.0 || kotlin.math.abs(median - p.easyPaceMinPerKm) / p.easyPaceMinPerKm > 0.03) {
            store.profile = p.copy(easyPaceMinPerKm = (median * 100).roundToInt() / 100.0)
        }
    }

    /** Marks planned run days as done when Garmin has a run/walk recorded that day. */
    private fun autoComplete(acts: List<ActivitySummary>) {
        val byDate = acts.filter { it.isRunLike }.groupBy { it.date }
        val updated = store.completions.toMutableMap()
        byDate.forEach { (date, list) ->
            val existing = updated[date]
            if (existing == null || existing.source == "garmin") {
                updated[date] = Completion(date, "DONE", PlanGenerator.roundHalf(list.sumOf { it.distanceKm }), "garmin")
            }
        }
        store.completions = updated
    }

    fun setCompletion(date: String, status: String?) {
        val m = store.completions.toMutableMap()
        if (status == null) m.remove(date) else m[date] = Completion(date, status, sessionFor(LocalDate.parse(date))?.distanceKm)
        store.completions = m
    }

    // ------------------------------------------------------------ Races

    fun home(): GeoPoint? = store.profile.let { p -> if (p.homeLat != null && p.homeLng != null) GeoPoint(p.homeLat, p.homeLng) else null }

    fun raceSuggestions(): List<RaceSuggestion> {
        val geo = store.geocodes
        return RaceAdvisor.suggest(store.races, store.profile, home(), { geo[it] })
    }

    /** Refreshes the race list (at most daily unless forced) and geocodes towns of suggested races. Returns an error or null. */
    suspend fun refreshRaces(force: Boolean = false): String? {
        val fresh = System.currentTimeMillis() - store.racesFetchedAt < 24 * 3600_000L && store.races.isNotEmpty()
        if (!fresh || force) {
            try {
                val races = RaceFetcher().fetchAll()
                if (races.isEmpty()) return "No races found – the calendar site may have changed."
                store.races = races
                store.racesFetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                return "Couldn't load races: ${e.message}"
            }
        }
        geocodeSuggestedTowns()
        return null
    }

    fun raceEntries(): List<RaceEntry> {
        val geo = store.geocodes
        return RaceAdvisor.entries(store.races, raceSuggestions(), home(), { geo[it] })
    }

    /** Looks up coordinates for every race town (suggested races first), saving progress as it goes. */
    private suspend fun geocodeSuggestedTowns() {
        if (home() == null) return
        val known = store.geocodes
        val towns = (raceSuggestions().map { it.race.location } + store.races.map { it.location })
            .filter { it.isNotBlank() && it !in known }.distinct()
        towns.chunked(20).forEach { batch ->
            val found = withContext(Dispatchers.IO) { batch.associateWith { geocode(it) } }
            store.geocodes = store.geocodes + found
        }
    }

    @Suppress("DEPRECATION")
    private fun geocode(place: String): GeoPoint? = runCatching {
        if (!Geocoder.isPresent()) return null
        val query = if (SA_PROVINCES.any { place.contains(it, true) }) "$place, South Africa" else place
        Geocoder(appContext, Locale.ENGLISH).getFromLocationName(query, 1)?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lng: Double): String = runCatching {
        Geocoder(appContext, Locale.ENGLISH).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull() ?: "My location"

    /** Uses the phone's coarse location. Caller must hold ACCESS_COARSE_LOCATION. */
    @SuppressLint("MissingPermission")
    suspend fun setHomeFromDevice(): String? {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return "Location not available"
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val loc = last ?: withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(appContext),
                    androidx.core.util.Consumer<android.location.Location?> { cont.resume(it) },
                )
            }
        } ?: return "Couldn't get your location – is Location turned on?"
        val name = withContext(Dispatchers.IO) { placeName(loc.latitude, loc.longitude) }
        store.profile = store.profile.copy(homeName = name, homeLat = loc.latitude, homeLng = loc.longitude)
        geocodeSuggestedTowns()
        return null
    }

    suspend fun setHomeFromTown(town: String): String? {
        val p = withContext(Dispatchers.IO) { geocode(town) } ?: return "Couldn't find \"$town\""
        store.profile = store.profile.copy(homeName = town.trim(), homeLat = p.lat, homeLng = p.lng)
        geocodeSuggestedTowns()
        return null
    }

    fun useAsQualifier(r: Race) { store.profile = store.profile.copy(qualifierDate = r.date, qualifierRace = r.slug, qualified = false) }
    fun useAsTuneUp(r: Race) { store.profile = store.profile.copy(tuneUpDate = r.date, tuneUpRace = r.slug) }

    // ------------------------------------------------------------ Races you've entered

    fun enteredRaces(): List<EnteredRace> = store.profile.enteredRaces.sortedBy { it.date }

    /** Adds (or updates) a race you've entered; the plan works around it from now on. */
    fun enterRace(e: EnteredRace) {
        val p = store.profile
        store.profile = p.copy(enteredRaces = p.enteredRaces.filterNot { it.id == e.id } + e)
        // Today's/tomorrow's coach overrides would hide the race-week changes.
        val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
        if (d != null) store.dayOverrides = store.dayOverrides.filterNot { o ->
            runCatching { LocalDate.parse(o.date) }.getOrNull()?.let { !it.isBefore(d.minusDays(2)) && !it.isAfter(d.plusDays(1)) } == true
        }
    }

    fun removeEnteredRace(id: String) {
        val p = store.profile
        store.profile = p.copy(enteredRaces = p.enteredRaces.filterNot { it.id == id })
    }

    /** Asks the coach for a plan for one race: race week, pacing, fuelling, the morning itself. */
    suspend fun racePlan(id: String): String {
        val r = store.profile.enteredRaces.firstOrNull { it.id == id } ?: return ""
        val q = "I've entered ${r.name} on ${r.date}${if (r.location.isNotBlank()) " in ${r.location}" else ""}: ${PlanGenerator.k(r.distanceKm)} km" +
            (if (r.goal.isNotBlank()) ", my goal is ${r.goal}" else "") + ". Give me my plan for it: how to run the days before " +
            "(my plan already has a shake-out the day before), a realistic pacing plan by km or section with paces AND heart rate from my " +
            "Garmin data, what to eat and drink before and during, the race morning timeline, and what to do the day after. " +
            "Keep it practical, use short sections with headings, under 350 words."
        val text = AiCoach(store.anthropicKey, store.settings.aiModel).ask(buildContext(), q, words = 380)
        val p = store.profile
        store.profile = p.copy(enteredRaces = p.enteredRaces.map { if (it.id == id) it.copy(racePlan = text.trim()) else it })
        return text
    }

    // ------------------------------------------------------------ Plan check

    /** A suggestion to rebuild the plan when real training differs a lot from the plan; null if on track or snoozed. */
    fun planCheck(): LoadCheck? {
        val snooze = runCatching { LocalDate.parse(store.settings.planCheckSnoozeUntil) }.getOrNull()
        if (snooze != null && LocalDate.now().isBefore(snooze)) return null
        return LoadChecker.check(plan(), store.activities, store.profile)
    }

    /** Rebuilds the plan from this week; the AI coach picks the new starting volume when available. */
    suspend fun applyPlanUpdate(c: LoadCheck) {
        val byCoach = runCatching { aiBaseline("Rebuild the plan from this week. ${c.message}", restartToday = true) }.getOrDefault(false)
        if (!byCoach) store.profile = LoadChecker.updatedProfile(store.profile, c).copy(baselineSource = "garmin", baselineNote = "")
        snoozePlanCheck(21)
    }

    fun snoozePlanCheck(days: Long = 14) {
        store.settings = store.settings.copy(planCheckSnoozeUntil = LocalDate.now().plusDays(days).toString())
    }

    // ------------------------------------------------------------ AI

    val aiConfigured: Boolean get() = store.anthropicKey.isNotBlank()

    suspend fun runDailyAi(): AiAdvice {
        val coach = AiCoach(store.anthropicKey, store.settings.aiModel)
        return coach.dailyAdvice(buildContext()).also { store.aiAdvice = it }
    }

    /**
     * If Garmin has synced an activity we haven't broken down yet, asks the AI coach for a warm breakdown.
     * Returns the new breakdown, or null if there's nothing new (or no API key).
     */
    suspend fun breakdownLatestIfNew(force: Boolean = false): ActivityBreakdown? {
        if (!aiConfigured) return null
        val latest = store.activities.maxByOrNull { it.startLocal } ?: return null
        if (!force && store.breakdown?.activityId == latest.id) return null
        val ageDays = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(latest.date), LocalDate.now()) }.getOrDefault(0)
        if (!force && ageDays > 3) return null

        val laps = store.garminTokens?.let { t ->
            runCatching { garmin.activitySplits(t, latest.id) }.getOrNull()?.let { (tokens, lines) -> store.garminTokens = tokens; lines }
        }.orEmpty()
        val planned = runCatching { sessionFor(LocalDate.parse(latest.date)) }.getOrNull()
        val pace = if (latest.distanceKm > 0) latest.durationMin / latest.distanceKm else 0.0
        val activity = buildString {
            appendLine("${latest.name} (${latest.type}) on ${latest.startLocal.take(16)}")
            appendLine("${latest.distanceKm} km in ${latest.durationMin} min" +
                (if (pace > 0) ", avg %d:%02d/km".format(pace.toInt(), ((pace % 1) * 60).toInt()) else "") +
                ", +${latest.elevationGainM} m, avg HR ${latest.avgHr}, max HR ${latest.maxHr}, " +
                "aerobic TE ${latest.trainingEffect}, training load ${latest.load}")
            appendLine("Planned that day: ${planned?.let { "${it.title} – ${it.intensity}. ${it.details.take(300)}" } ?: "nothing specific"}")
            if (laps.isNotEmpty()) { appendLine("Laps:"); laps.forEach { appendLine("  $it") } }
        }
        val r = AiCoach(store.anthropicKey, store.settings.aiModel).activityBreakdown(buildContext(), activity)
        return ActivityBreakdown(latest.id, latest.name, latest.date, r.headline, r.breakdown, r.wins, r.workOn, r.recovery)
            .also { store.breakdown = it }
    }

    suspend fun ask(question: String): String =
        AiCoach(store.anthropicKey, store.settings.aiModel).ask(buildContext(), question)

    fun buildContext(): String {
        val p = store.profile
        val today = LocalDate.now()
        val week = weekFor(today)
        val d = todayDecision()
        val s = todaySnapshot()
        val acts = store.activities.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(today.minusDays(15)) == true }
        val done = store.completions
        val pastWeek = week?.sessions?.filter { LocalDate.parse(it.date).isBefore(today) }.orEmpty()
        val nextWeek = weekFor(today.plusDays(7))

        return buildString {
            appendLine("TODAY: $today (${today.dayOfWeek}). Days to race: ${daysToRace()}.")
            appendLine("ATHLETE: ${p.name.ifBlank { "athlete" }}, goal finish ${p.goalFinishHours} h, starting volume ${p.currentWeeklyKm} km/week (from Garmin history; the plan sets each week from here), " +
                "runs ${p.runDaysPerWeek} days/week, easy pace ${if (p.easyPaceMinPerKm > 0) "%.2f min/km (from Garmin)".format(p.easyPaceMinPerKm) else "unknown"}, max HR ${if (p.maxHr > 0) p.maxHr else "unknown"}" +
                    (if (p.easyHrMax > 0) ", easy runs up to ${p.easyHrMax} bpm (the athlete's own comfortable ceiling – use this for easy/long-run HR caps, don't set a lower one)" else "") + ".")
            appendLine("QUALIFIER: ${if (p.qualified) "already qualified" else "planned marathon on ${p.qualifierDate}"}." +
                (if (p.tuneUpDate.isNotBlank()) " TUNE-UP ULTRA: ${p.tuneUpDate}." else ""))
            planCheck()?.let { appendLine("PLAN CHECK: ${it.message}") }
            store.garminInsights?.let { g ->
                fun t(s: Int) = "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
                appendLine("GARMIN RACE PREDICTIONS (${g.predictionsDate}): 5K ${t(g.race5kSec)}, 10K ${t(g.race10kSec)}, half ${t(g.raceHalfSec)}, marathon ${t(g.raceMarathonSec)}" +
                    (g.marathonSec3MonthsAgo?.let { " (marathon was ${t(it)} three months earlier)" } ?: "") + ". Qualifier needs sub-5:00:00.")
                if (g.zoneFloors.size == 5) appendLine("GARMIN HR ZONES (max ${g.garminMaxHr}): Z1 ${g.zoneFloors[0]}, Z2 ${g.zoneFloors[1]}, Z3 ${g.zoneFloors[2]}, Z4 ${g.zoneFloors[3]}, Z5 ${g.zoneFloors[4]}+.")
            }
            p.enteredRaces.filter { it.date >= today.toString() }.sortedBy { it.date }.takeIf { it.isNotEmpty() }?.let { rs ->
                appendLine("RACES ENTERED (guide them towards these): " + rs.joinToString("; ") { r ->
                    "${r.name} on ${r.date} (in ${ChronoUnit.DAYS.between(today, LocalDate.parse(r.date))} days), ${PlanGenerator.k(r.distanceKm)} km" +
                        (if (r.goal.isNotBlank()) ", goal ${r.goal}" else "")
                })
            }
            week?.let {
                appendLine("PLAN WEEK ${it.index + 1 + weekOffset()}/${plan().size + weekOffset()}: phase ${it.phase.label}${if (it.isRecovery) " (recovery week)" else ""}, " +
                    "target ${it.targetKm} km. Focus: ${it.focus}")
                it.sessions.forEach { ss ->
                    val c = done[ss.date]?.let { c -> " [${c.status}${c.actualKm?.let { k -> " $k km" } ?: ""}]" } ?: ""
                    appendLine("  ${ss.date}: ${ss.title}$c")
                }
            }
            appendLine("COMPLIANCE this week so far: ${pastWeek.count { done[it.date]?.status == "DONE" }}/${pastWeek.count { it.type.name != "REST" }} sessions done.")
            appendLine("TODAY'S PLANNED SESSION: ${d.planned?.let { "${it.title} – ${it.details}" } ?: "none"}")
            appendLine("RULE-BASED VERDICT: ${d.verdict.name} (score ${d.score}). Reasons: ${d.reasons.joinToString("; ")}")
            appendLine("GARMIN TODAY: " + (s?.let {
                "readiness ${it.readinessScore} ${it.readinessLevel ?: ""} '${it.readinessFeedback ?: ""}', HRV last night ${it.hrvLastNight} " +
                    "(weekly ${it.hrvWeeklyAvg}, ${it.hrvStatus}), sleep score ${it.sleepScore} (${it.sleepHours} h), body battery ${it.bodyBattery}, " +
                    "resting HR ${it.restingHr}, stress ${it.stressAvg}, recovery time ${it.recoveryTimeHours} h, acute load ${it.acuteLoad}, " +
                    "training status ${it.trainingStatus}, VO2max ${it.vo2max}"
            } ?: "not available"))
            val hist = store.history.takeLast(10)
            if (hist.isNotEmpty()) appendLine("TRENDS (date readiness/hrv/rhr/sleep): " + hist.joinToString(" | ") { "${it.date.drop(5)} ${it.readiness}/${it.hrv}/${it.rhr}/${it.sleep}" })
            appendLine("LAST 14 DAYS ACTIVITIES:")
            if (acts.isEmpty()) appendLine("  none recorded")
            acts.forEach {
                appendLine("  ${it.startLocal.take(16)} ${it.type} '${it.name}' ${it.distanceKm} km, ${it.durationMin} min, +${it.elevationGainM} m, " +
                    "avgHR ${it.avgHr}, TE ${it.trainingEffect}, load ${it.load}")
            }
            todayCheckIn()?.let { appendLine("CHECK-IN: feel ${it.feel}/5, soreness ${it.soreness}/5, sick/injured ${it.sick}. Note: ${it.note}") }
            if (p.wakeTime.isNotBlank() || p.weekdayMaxMin > 0 || p.dumbbellKg > 0) appendLine(
                "ROUTINE: wakes ${p.wakeTime.ifBlank { "?" }}" + (if (p.routineNote.isNotBlank()) " (${p.routineNote})" else "") +
                    ", runs at ${p.runTime.ifBlank { "?" }}; weekday runs max ${if (p.weekdayMaxMin > 0) "${p.weekdayMaxMin} min" else "no limit"}, " +
                    "long runs at weekends; weighs in after the run, before showering. Strength at home: " +
                    (if (p.dumbbellKg > 0) "${p.dumbbellKg} kg dumbbells" else "no equipment") + (if (p.foamRoller) " + foam roller" else "") +
                    " (no commercial gym) – tailor any strength advice to this kit.")
            appendLine("LAST WEIGH-IN: " + (store.bodyReadings.firstOrNull()?.let { "${it.date} (${it.source})" } ?: "none"))
            store.bodyReadings.firstOrNull()?.let { b ->
                val month = bodyOn(today.minusDays(30))
                appendLine("BODY (scale, ${b.date}): weight ${b.weightKg?.let { "%.1f".format(it) }} kg" +
                    (month?.weightKg?.let { m -> b.weightKg?.let { w -> " (%+.1f kg vs 30 days ago)".format(w - m) } } ?: "") +
                    ", body fat ${b.bodyFatPct?.let { "%.1f".format(it) }}%, lean mass ${b.leanMassKg?.let { "%.1f".format(it) }} kg, " +
                    "water ${b.waterPct?.let { "%.0f".format(it) }}%, BMI ${bmi(b.weightKg)?.let { "%.1f".format(it) }}, BMR ${b.bmrKcal?.toInt()} kcal")
            }
            nextWeek?.let { appendLine("NEXT WEEK: ${it.phase.label}, ${it.targetKm} km, long run ${it.sessions.maxOf { s2 -> s2.distanceKm }} km.") }
        }
    }
}
