package com.cape100.coach.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val name: String = "",
    val raceName: String = "CAPE 100",
    val raceDate: String = "2027-09-05",
    val raceDistanceKm: Int = 100,
    val raceElevationM: Int = 900,
    val goalFinishHours: Double = 12.0,
    // Official rule: officially timed road marathon under 5:00 between 1 Jan 2026 and 31 May 2027.
    val qualifierDate: String = "2027-03-21",
    val qualified: Boolean = false,
    // Optional tune-up ultra picked from the Races tab; blank = default slot ~12 weeks out.
    val tuneUpDate: String = "",
    // Slugs of races picked in the Races tab, so only that race shows as selected.
    val qualifierRace: String = "",
    val tuneUpRace: String = "",
    val homeName: String = "",
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    // Starting volume: filled in automatically from Garmin (last 4 weeks); the plan sets every week from here.
    val currentWeeklyKm: Int = 20,
    val longestRecentRunKm: Int = 10,
    val baselineFromGarmin: Boolean = false,
    // "ai" when the AI coach chose the starting volume, with its explanation.
    val baselineSource: String = "",
    val baselineNote: String = "",
    val maxHr: Int = 0,
    /** True once max HR comes from your Garmin runs rather than what was typed in. */
    val maxHrFromGarmin: Boolean = false,
    /** Your own easy-run HR ceiling (0 = work it out from max HR). */
    val easyHrMax: Int = 0,
    val heightCm: Int = 0,
    // For scale body-composition maths.
    val birthYear: Int = 0,
    val sex: String = "",   // "M" or "F"
    val runDaysPerWeek: Int = 5,
    // Daily routine: e.g. wake 03:20 (Tahajjud), run 03:30; weekday runs capped at this many minutes (0 = no cap).
    val wakeTime: String = "",
    val runTime: String = "",
    val routineNote: String = "",
    val weekdayMaxMin: Int = 0,
    // Your own long-run targets (e.g. 15 km by 1 Nov, 20 km by 15 Nov); the plan builds towards them.
    val longRunGoals: List<LongRunGoal> = emptyList(),
    // Your real easy pace (min/km) from recent Garmin runs; 0 = unknown (use goal-based pace).
    val easyPaceMinPerKm: Double = 0.0,
    // Home strength kit: dumbbell weight in kg (0 = none / use gym routine) and foam roller.
    val dumbbellKg: Double = 0.0,
    val foamRoller: Boolean = false,
    // Areas that feel tight – stretch routines focus here.
    val tightAreas: String = "hamstrings, glutes, quads, hip flexors, calves",
    val planStart: String = "",
    /** When your training began – week numbers count from here even if the plan is rebuilt later. */
    val journeyStart: String = "",
    /** Races you've entered along the way – they go into the plan. */
    val enteredRaces: List<EnteredRace> = emptyList(),
    val onboarded: Boolean = false,
)

@Serializable
data class Settings(
    val morningEnabled: Boolean = true,
    val morningHour: Int = 6,
    val morningMinute: Int = 0,
    val eveningEnabled: Boolean = true,
    val eveningHour: Int = 19,
    val eveningMinute: Int = 0,
    val aiModel: String = "claude-opus-5-5",
    val aiInMorning: Boolean = true,
    val planCheckSnoozeUntil: String = "",
    // Send new scale weigh-ins to Garmin Connect.
    val uploadWeighInsToGarmin: Boolean = true,
    // Daily "weigh in before your shower" reminder.
    val weighInReminder: Boolean = true,
    val weighInHour: Int = 6,
    val weighInMinute: Int = 0,
    val weighInWeekendHour: Int = 8,
    val weighInWeekendMinute: Int = 0,
    // Stretch reminders: after the run (weigh-in time + 15 min) and in the evening; movement nudges from Garmin steps.
    val stretchReminders: Boolean = true,
    val stretchEveningHour: Int = 20,
    val stretchEveningMinute: Int = 30,
    val movementNudges: Boolean = true,
    // Send each day's session to Garmin Connect as a workout (shows on the watch).
    val pushWorkoutsToGarmin: Boolean = true,
)

enum class SessionType(val label: String, val hard: Boolean) {
    REST("Rest", false),
    RECOVERY("Recovery", false),
    EASY("Easy run", false),
    STRENGTH("Strength", false),
    CROSS("Cross-train", false),
    HILLS("Hills", true),
    TEMPO("Tempo", true),
    INTERVALS("Intervals", true),
    LONG("Long run", true),
    BACK_TO_BACK("Back-to-back", true),
    TUNEUP_RACE("Tune-up race", true),
    RACE("RACE DAY", true),
    /** A race you have entered along the way (parkrun, 10K, half…). */
    EVENT("Race", true),
}

@Serializable
data class Session(
    val date: String,
    val type: SessionType,
    val title: String,
    val distanceKm: Double = 0.0,
    val durationMin: Int = 0,
    val vertM: Int = 0,
    val intensity: String = "",
    val details: String = "",
    val purpose: String = "",
    val withStrength: Boolean = false,
)

enum class Phase(val label: String, val color: Long, val blurb: String) {
    BASE("Aerobic Base", 0xFF7F1D1D, "Build the engine: lots of easy Z2 running, strides, hill sprints and twice-weekly strength."),
    MARATHON("Qualifier Block", 0xFFB91C1C, "Sharpen for your sub-5:00 qualifying marathon: tempo, marathon-pace and longer long runs."),
    RESET("Reset", 0xFF52525B, "Recover from the marathon. Easy running only, then rebuild."),
    ULTRA("Ultra Build", 0xFFDC2626, "Time on feet: back-to-back long runs, run-walk practice, fuelling every long run."),
    PEAK("Peak", 0xFFFF3B4E, "Biggest weeks. Pre-dawn starts, race-pace on the road, full kit + nutrition rehearsals."),
    TAPER("Taper", 0xFFFCA5A5, "Volume drops, sharpness stays. Sleep, eat, trust the training."),
}

data class PlanWeek(
    val index: Int,
    val start: java.time.LocalDate,
    val phase: Phase,
    val isRecovery: Boolean,
    val targetKm: Double,
    val targetVert: Int,
    val focus: String,
    val sessions: List<Session>,
)

@Serializable
data class GarminSnapshot(
    val date: String,
    val fetchedAt: Long,
    val readinessScore: Int? = null,
    val readinessLevel: String? = null,
    val readinessFeedback: String? = null,
    val recoveryTimeHours: Int? = null,
    val acuteLoad: Int? = null,
    val hrvLastNight: Int? = null,
    val hrvWeeklyAvg: Int? = null,
    val hrvStatus: String? = null,
    val sleepScore: Int? = null,
    val sleepHours: Double? = null,
    val bodyBattery: Int? = null,
    val restingHr: Int? = null,
    val stressAvg: Int? = null,
    val steps: Int? = null,
    val sedentaryMin: Int? = null,
    val trainingStatus: String? = null,
    val vo2max: Double? = null,
)

@Serializable
data class ActivitySummary(
    val id: Long,
    val name: String,
    val type: String,
    val startLocal: String,
    val distanceKm: Double,
    val durationMin: Int,
    val elevationGainM: Int,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val trainingEffect: Double? = null,
    val load: Double? = null,
) {
    val date: String get() = startLocal.take(10)
    val isRunLike: Boolean
        get() = type.contains("run", true) || type.contains("hik", true) || type.contains("walk", true)
}

@Serializable
data class Completion(
    val date: String,
    val status: String, // DONE or SKIPPED
    val actualKm: Double? = null,
    val source: String = "manual",
)

@Serializable
data class CheckIn(
    val date: String,
    val feel: Int = 3,      // 1 awful .. 5 great
    val soreness: Int = 2,  // 1 none .. 5 very sore
    val sick: Boolean = false,
    val note: String = "",
)

@Serializable
data class AiAdvice(
    val date: String,
    val verdict: String = "",
    val headline: String = "",
    val sessionTitle: String = "",
    val sessionDetails: String = "",
    val durationMin: Int? = null,
    val distanceKm: Double? = null,
    val reasoning: String = "",
    val fueling: String = "",
    val focus: String = "",
    val requestWeighIn: Boolean = false,
    val weighInNote: String = "",
)

@Serializable
data class DayMetric(
    val date: String,
    val readiness: Int? = null,
    val hrv: Int? = null,
    val rhr: Int? = null,
    val sleep: Int? = null,
)

@Serializable
data class GarminTokens(
    val oauthToken: String,
    val oauthTokenSecret: String,
    val mfaToken: String? = null,
    val accessToken: String = "",
    val accessExpiresAt: Long = 0,
    val displayName: String? = null,
    val fullName: String? = null,
)

@Serializable
data class Race(
    val slug: String,
    val name: String,
    val date: String,
    val location: String,
    val distances: List<String>,
    val url: String,
)

/** A race you have entered. It goes into the plan and the coach prepares you for it. */
@Serializable
data class EnteredRace(
    val id: String,
    val name: String,
    val date: String,
    val distanceKm: Double,
    val location: String = "",
    val url: String = "",
    val goal: String = "",
    /** The coach's race plan (pacing, fuelling, race week), once asked for. */
    val racePlan: String = "",
)

@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

@Serializable
data class ActivityBreakdown(
    val activityId: Long,
    val activityName: String,
    val date: String,
    val headline: String = "",
    val breakdown: String = "",
    val wins: List<String> = emptyList(),
    val workOn: String = "",
    val recovery: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One weigh-in from the scale (via Health Connect). Missing values are null. */
@Serializable
data class BodyReading(
    val time: String,           // ISO instant
    val date: String,           // local date
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val waterKg: Double? = null,
    val bmrKcal: Double? = null,
    val muscleKg: Double? = null,
    val visceralFat: Double? = null,
    val bodyAge: Int? = null,
    val impedance: Double? = null,
    val source: String = "",
    val garminUploaded: Boolean = false,
) {
    val waterPct: Double? get() = if (waterKg != null && weightKg != null && weightKg > 0) waterKg / weightKg * 100 else null
    val fatMassKg: Double? get() = if (bodyFatPct != null && weightKg != null) weightKg * bodyFatPct / 100 else null
}

/** One day's overall health record: Garmin wellness + latest scale reading + training. */
@Serializable
data class DayHealth(
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    val waterPct: Double? = null,
    val bmi: Double? = null,
    val bmrKcal: Double? = null,
    val readiness: Int? = null,
    val restingHr: Int? = null,
    val hrv: Int? = null,
    val sleepScore: Int? = null,
    val sleepHours: Double? = null,
    val bodyBattery: Int? = null,
    val stress: Int? = null,
    val vo2max: Double? = null,
    val steps: Int? = null,
    val km7d: Double? = null,
)

/** One "how do you feel today?" while sick, and the coach's answer. */
@Serializable
data class IllnessCheck(val date: String, val feeling: String, val note: String, val reply: String, val canTrain: Boolean)

/** Being sick or injured: training pauses until you're better, then eases back in over a few days. */
@Serializable
data class Illness(
    val start: String,
    val note: String = "",
    /** Day you said you're better; blank while still sick. */
    val endDate: String = "",
    val checks: List<IllnessCheck> = emptyList(),
)

/** An idea for improving the app, jotted down on the phone. */
@Serializable
data class AppIdea(val time: String, val text: String, val done: Boolean = false)

/** A personal best from Garmin (time in seconds, or metres for "Farthest Run"). */
@Serializable
data class GarminRecord(val type: String, val value: Double, val date: String)

/** What Garmin's own data export says about you: race predictions, bests, zones. */
@Serializable
data class GarminInsights(
    val predictionsDate: String = "",
    val race5kSec: Int = 0,
    val race10kSec: Int = 0,
    val raceHalfSec: Int = 0,
    val raceMarathonSec: Int = 0,
    val marathonSec3MonthsAgo: Int? = null,
    val records: List<GarminRecord> = emptyList(),
    val garminMaxHr: Int = 0,
    val zoneFloors: List<Int> = emptyList(),
    val trainingStatus: String = "",
)

/** The AI coach's feedback on one weigh-in (matched to the reading by its time). */
@Serializable
data class WeighInFeedback(val readingTime: String, val text: String)


@Serializable
data class StretchMove(val name: String, val hold: String = "", val how: String = "")

@Serializable
data class StretchRoutine(val title: String, val minutes: Int, val moves: List<StretchMove>)

/** Today's stretching: after the run, in the evening, and quick desk moves for movement breaks. */
@Serializable
data class StretchPlan(
    val date: String,
    val postRun: StretchRoutine,
    val evening: StretchRoutine,
    val desk: List<StretchMove>,
    val byCoach: Boolean = false,
)


/** Workouts this app put on your Garmin calendar for one day (so they can be replaced or cleaned up). */
@Serializable
data class PushedWorkout(val date: String, val ids: List<Long>, val title: String)


/** A long-run distance you want to reach by a date. */
@Serializable
data class LongRunGoal(val km: Double, val date: String)


/** A session the coach set for a specific day after your update (replaces the plan/readiness choice for that day). */
@Serializable
data class DayOverride(val date: String, val session: Session, val reason: String = "")

/** Your latest check-in with the coach and its reply. */
@Serializable
data class CoachUpdate(val date: String, val status: String, val note: String, val reply: String)
