package com.cape100.coach.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Local persistence. Plain JSON in SharedPreferences, secrets in EncryptedSharedPreferences. */
class Store private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("cape100", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = openSecure(context)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    private fun <T> read(key: String, ser: KSerializer<T>, def: T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: def

    private fun <T> write(key: String, ser: KSerializer<T>, value: T) =
        prefs.edit().putString(key, json.encodeToString(ser, value)).apply()

    var profile: Profile
        get() = read("profile", Profile.serializer(), Profile())
        set(v) = write("profile", Profile.serializer(), v)

    var settings: Settings
        get() = read("settings", Settings.serializer(), Settings())
        set(v) = write("settings", Settings.serializer(), v)

    var snapshot: GarminSnapshot?
        get() = prefs.getString("snapshot", null)?.let { runCatching { json.decodeFromString(GarminSnapshot.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("snapshot").apply() else write("snapshot", GarminSnapshot.serializer(), v)

    var activities: List<ActivitySummary>
        get() = read("activities", ListSerializer(ActivitySummary.serializer()), emptyList())
        set(v) = write("activities", ListSerializer(ActivitySummary.serializer()), v)

    /** True once the old "Cape100" workouts have been removed from Garmin. */
    var garminWorkoutsCleared: Boolean
        get() = prefs.getBoolean("garminWorkoutsCleared", false)
        set(v) = prefs.edit().putBoolean("garminWorkoutsCleared", v).apply()

    /** Current (or most recent) illness. */
    var illness: Illness?
        get() = read("illness", Illness.serializer().nullable, null)
        set(v) = write("illness", Illness.serializer().nullable, v)

    /** Ideas for improving the app, newest first. */
    var ideas: List<AppIdea>
        get() = read("ideas", ListSerializer(AppIdea.serializer()), emptyList())
        set(v) = write("ideas", ListSerializer(AppIdea.serializer()), v)

    /** Race predictions, personal bests and HR zones from your Garmin data export. */
    var garminInsights: GarminInsights?
        get() = read("garminInsights", GarminInsights.serializer().nullable, null)
        set(v) = write("garminInsights", GarminInsights.serializer().nullable, v)

    var completions: Map<String, Completion>
        get() = read("completions", MapSerializer(String.serializer(), Completion.serializer()), emptyMap())
        set(v) = write("completions", MapSerializer(String.serializer(), Completion.serializer()), v)

    var checkIn: CheckIn?
        get() = prefs.getString("checkin", null)?.let { runCatching { json.decodeFromString(CheckIn.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("checkin").apply() else write("checkin", CheckIn.serializer(), v)

    var aiAdvice: AiAdvice?
        get() = prefs.getString("ai", null)?.let { runCatching { json.decodeFromString(AiAdvice.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("ai").apply() else write("ai", AiAdvice.serializer(), v)

    var history: List<DayMetric>
        get() = read("history", ListSerializer(DayMetric.serializer()), emptyList())
        set(v) = write("history", ListSerializer(DayMetric.serializer()), v.takeLast(60))

    var races: List<Race>
        get() = read("races", ListSerializer(Race.serializer()), emptyList())
        set(v) = write("races", ListSerializer(Race.serializer()), v)

    var racesFetchedAt: Long
        get() = prefs.getLong("racesFetchedAt", 0)
        set(v) = prefs.edit().putLong("racesFetchedAt", v).apply()

    /** Town name -> coordinates (null = could not be geocoded), so each town is only looked up once. */
    var geocodes: Map<String, GeoPoint?>
        get() = read("geocodes", MapSerializer(String.serializer(), GeoPoint.serializer().nullable), emptyMap())
        set(v) = write("geocodes", MapSerializer(String.serializer(), GeoPoint.serializer().nullable), v)

    var breakdown: ActivityBreakdown?
        get() = prefs.getString("breakdown", null)?.let { runCatching { json.decodeFromString(ActivityBreakdown.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("breakdown").apply() else write("breakdown", ActivityBreakdown.serializer(), v)

    var bodyReadings: List<BodyReading>
        get() = read("bodyReadings", ListSerializer(BodyReading.serializer()), emptyList())
        set(v) = write("bodyReadings", ListSerializer(BodyReading.serializer()), v.take(1000))

    /** Read-modify-write of the weigh-in list, one caller at a time (UI and background jobs share it). */
    @Synchronized
    fun updateBodyReadings(f: (List<BodyReading>) -> List<BodyReading>) { bodyReadings = f(bodyReadings).sortedByDescending { it.time } }

    @Synchronized
    fun updateHealthHistory(f: (List<DayHealth>) -> List<DayHealth>) { healthHistory = f(healthHistory) }

    var heightM: Double
        get() = prefs.getFloat("heightM", 0f).toDouble()
        set(v) = prefs.edit().putFloat("heightM", v.toFloat()).apply()

    /** Overall daily health record, oldest first (about 2 years kept). */
    var healthHistory: List<DayHealth>
        get() = read("healthHistory", ListSerializer(DayHealth.serializer()), emptyList())
        set(v) = write("healthHistory", ListSerializer(DayHealth.serializer()), v.sortedBy { it.date }.takeLast(2200))

    /** Garmin history backfill: "" = not started, "done" when finished, otherwise a progress note. */
    var backfillState: String
        get() = prefs.getString("backfillState", "") ?: ""
        set(v) = prefs.edit().putString("backfillState", v).apply()

    var weighInFeedback: WeighInFeedback?
        get() = prefs.getString("weighInFeedback", null)?.let { runCatching { json.decodeFromString(WeighInFeedback.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("weighInFeedback").apply() else write("weighInFeedback", WeighInFeedback.serializer(), v)

    /** Date (yyyy-mm-dd) on which you chose to do the planned session instead of the readiness-adjusted one. */
    var plannedOverrideDate: String
        get() = prefs.getString("plannedOverride", "") ?: ""
        set(v) = prefs.edit().putString("plannedOverride", v).apply()

    var stretchPlan: StretchPlan?
        get() = prefs.getString("stretchPlan", null)?.let { runCatching { json.decodeFromString(StretchPlan.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("stretchPlan").apply() else write("stretchPlan", StretchPlan.serializer(), v)

    /** Movement-nudge bookkeeping: "date|count|lastMillis|lastSteps". */
    /** Date the stretch routine was ticked off (for the "what's next" card). */
    var stretchDoneDate: String
        get() = prefs.getString("stretchDone", "") ?: ""
        set(v) = prefs.edit().putString("stretchDone", v).apply()

    var nudgeState: String
        get() = prefs.getString("nudgeState", "") ?: ""
        set(v) = prefs.edit().putString("nudgeState", v).apply()

    var pushedWorkouts: List<PushedWorkout>
        get() = read("pushedWorkouts", ListSerializer(PushedWorkout.serializer()), emptyList())
        set(v) = write("pushedWorkouts", ListSerializer(PushedWorkout.serializer()), v.takeLast(30))

    var dayOverrides: List<DayOverride>
        get() = read("dayOverrides", ListSerializer(DayOverride.serializer()), emptyList())
        set(v) = write("dayOverrides", ListSerializer(DayOverride.serializer()), v.takeLast(30))

    var coachUpdate: CoachUpdate?
        get() = prefs.getString("coachUpdate", null)?.let { runCatching { json.decodeFromString(CoachUpdate.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) prefs.edit().remove("coachUpdate").apply() else write("coachUpdate", CoachUpdate.serializer(), v)

    var lastSyncError: String?
        get() = prefs.getString("syncError", null)
        set(v) = prefs.edit().putString("syncError", v).apply()

    var anthropicKey: String
        get() = secure.getString("anthropicKey", "") ?: ""
        set(v) = secure.edit().putString("anthropicKey", v.trim()).apply()

    var garminTokens: GarminTokens?
        get() = secure.getString("garmin", null)?.let { runCatching { json.decodeFromString(GarminTokens.serializer(), it) }.getOrNull() }
        set(v) = if (v == null) secure.edit().remove("garmin").apply()
        else secure.edit().putString("garmin", json.encodeToString(GarminTokens.serializer(), v)).apply()

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }

        private fun openSecure(context: Context): SharedPreferences {
            fun create() = EncryptedSharedPreferences.create(
                context,
                "cape100_secure",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                create()
            } catch (e: Exception) {
                // Keyset can become unreadable after a reinstall; start fresh rather than crash.
                context.deleteSharedPreferences("cape100_secure")
                create()
            }
        }
    }
}
