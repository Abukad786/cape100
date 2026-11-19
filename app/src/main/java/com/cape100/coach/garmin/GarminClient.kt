package com.cape100.coach.garmin

import com.cape100.coach.data.ActivitySummary
import com.cape100.coach.data.GarminSnapshot
import com.cape100.coach.data.GarminTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Reads wellness + activity data from Garmin Connect using the bearer token from [GarminAuth]. */
class GarminClient(private val auth: GarminAuth = GarminAuth()) {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class SyncResult(val tokens: GarminTokens, val snapshot: GarminSnapshot, val activities: List<ActivitySummary>)

    suspend fun sync(start: GarminTokens): SyncResult = withContext(Dispatchers.IO) {
        var tokens = auth.ensureFresh(start)
        val bearer = tokens.accessToken

        if (tokens.displayName.isNullOrBlank()) {
            val profile = getJson(bearer, "/userprofile-service/socialProfile")
            tokens = tokens.copy(
                displayName = profile.at("displayName").str(),
                fullName = profile.at("fullName").str(),
            )
        }
        val name = tokens.displayName.orEmpty()
        val today = LocalDate.now().toString()

        val readiness = getJsonOrNull(bearer, "/metrics-service/metrics/trainingreadiness/$today")
            .let { if (it is JsonArray) it.firstOrNull() else it }
        val hrv = getJsonOrNull(bearer, "/hrv-service/hrv/$today").at("hrvSummary")
        val sleep = getJsonOrNull(bearer, "/wellness-service/wellness/dailySleepData/$name?date=$today&nonSleepBufferMinutes=60")
            .at("dailySleepDTO")
        val daily = getJsonOrNull(bearer, "/usersummary-service/usersummary/daily/$name?calendarDate=$today")
        val status = getJsonOrNull(bearer, "/metrics-service/metrics/trainingstatus/aggregated/$today")

        val snapshot = GarminSnapshot(
            date = today,
            fetchedAt = System.currentTimeMillis(),
            readinessScore = readiness.at("score").int(),
            readinessLevel = readiness.at("level").str(),
            readinessFeedback = readiness.at("feedbackShort").str()?.replace('_', ' '),
            recoveryTimeHours = readiness.at("recoveryTime").int()?.let { (it / 60.0).roundToInt() },
            acuteLoad = readiness.at("acuteLoad").int(),
            hrvLastNight = hrv.at("lastNightAvg").int(),
            hrvWeeklyAvg = hrv.at("weeklyAvg").int(),
            hrvStatus = hrv.at("status").str(),
            sleepScore = sleep.at("sleepScores").at("overall").at("value").int() ?: readiness.at("sleepScore").int(),
            sleepHours = sleep.at("sleepTimeSeconds").dbl()?.let { (it / 360.0).roundToInt() / 10.0 },
            bodyBattery = daily.at("bodyBatteryMostRecentValue").int() ?: daily.at("bodyBatteryHighestValue").int(),
            restingHr = daily.at("restingHeartRate").int(),
            stressAvg = daily.at("averageStressLevel").int()?.takeIf { it >= 0 },
            steps = daily.at("totalSteps").int(),
            sedentaryMin = daily.at("sedentarySeconds").int()?.div(60),
            trainingStatus = status.find("trainingStatusFeedbackPhrase").str()?.replace('_', ' '),
            vo2max = status.find("vo2MaxPreciseValue").dbl() ?: status.find("vo2MaxValue").dbl(),
        )

        val acts = (getJsonOrNull(bearer, "/activitylist-service/activities/search/activities?start=0&limit=100") as? JsonArray)
            ?.mapNotNull { a ->
                val id = a.at("activityId").long() ?: return@mapNotNull null
                ActivitySummary(
                    id = id,
                    name = a.at("activityName").str() ?: "Activity",
                    type = a.at("activityType").at("typeKey").str() ?: "other",
                    startLocal = a.at("startTimeLocal").str() ?: "",
                    distanceKm = ((a.at("distance").dbl() ?: 0.0) / 10.0).roundToInt() / 100.0,
                    durationMin = ((a.at("duration").dbl() ?: 0.0) / 60).roundToInt(),
                    elevationGainM = a.at("elevationGain").dbl()?.roundToInt() ?: 0,
                    avgHr = a.at("averageHR").int(),
                    maxHr = a.at("maxHR").int(),
                    trainingEffect = a.at("aerobicTrainingEffect").dbl(),
                    load = a.at("activityTrainingLoad").dbl(),
                )
            } ?: emptyList()

        SyncResult(tokens, snapshot, acts)
    }

    // ------------------------------------------------------------ history (for the overall health record)

    /** One day's wellness: resting HR, stress, steps, Body Battery and sleep. */
    suspend fun dayWellness(start: GarminTokens, date: LocalDate): Pair<GarminTokens, com.cape100.coach.data.DayHealth> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val name = tokens.displayName.orEmpty()
        val daily = getJsonOrNull(tokens.accessToken, "/usersummary-service/usersummary/daily/$name?calendarDate=$date")
        val sleep = getJsonOrNull(tokens.accessToken, "/wellness-service/wellness/dailySleepData/$name?date=$date&nonSleepBufferMinutes=60").at("dailySleepDTO")
        tokens to com.cape100.coach.data.DayHealth(
            date = date.toString(),
            restingHr = daily.at("restingHeartRate").int(),
            stress = daily.at("averageStressLevel").int()?.takeIf { it >= 0 },
            steps = daily.at("totalSteps").int(),
            bodyBattery = daily.at("bodyBatteryHighestValue").int(),
            sleepScore = sleep.at("sleepScores").at("overall").at("value").int(),
            sleepHours = sleep.at("sleepTimeSeconds").dbl()?.let { (it / 360.0).roundToInt() / 10.0 },
        )
    }

    /** Training readiness score for one day (null if the watch doesn't support it). */
    suspend fun dayReadiness(start: GarminTokens, date: LocalDate): Pair<GarminTokens, Int?> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val r = getJsonOrNull(tokens.accessToken, "/metrics-service/metrics/trainingreadiness/$date").let { if (it is JsonArray) it.firstOrNull() else it }
        tokens to r.at("score").int()
    }

    /** Overnight HRV per day for a range of up to 28 days. */
    suspend fun hrvRange(start: GarminTokens, from: LocalDate, to: LocalDate): Pair<GarminTokens, Map<String, Int>> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val list = getJsonOrNull(tokens.accessToken, "/hrv-service/hrv/daily/$from/$to").at("hrvSummaries") as? JsonArray
        tokens to list.orEmpty().mapNotNull { e -> e.at("calendarDate").str()?.let { d -> e.at("lastNightAvg").int()?.let { d to it } } }.toMap()
    }

    /** VO2max per day for a range. */
    suspend fun vo2Range(start: GarminTokens, from: LocalDate, to: LocalDate): Pair<GarminTokens, Map<String, Double>> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val list = getJsonOrNull(tokens.accessToken, "/metrics-service/metrics/maxmet/daily/$from/$to") as? JsonArray
        tokens to list.orEmpty().mapNotNull { e ->
            val g = e.at("generic")
            g.at("calendarDate").str()?.let { d -> (g.at("vo2MaxPreciseValue").dbl() ?: g.at("vo2MaxValue").dbl())?.let { d to it } }
        }.toMap()
    }

    /** Weigh-ins stored in Garmin Connect (from any source) for a date range. */
    suspend fun weightRange(start: GarminTokens, from: LocalDate, to: LocalDate): Pair<GarminTokens, List<com.cape100.coach.data.BodyReading>> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val list = getJsonOrNull(tokens.accessToken, "/weight-service/weight/dateRange?startDate=$from&endDate=$to").at("dateWeightList") as? JsonArray
        tokens to list.orEmpty().mapNotNull { e ->
            val grams = e.at("weight").dbl() ?: return@mapNotNull null
            val ms = e.at("timestampGMT").long() ?: e.at("date").long()
            val date = e.at("calendarDate").str() ?: return@mapNotNull null
            com.cape100.coach.data.BodyReading(
                time = ms?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "${date}T12:00:00Z",
                date = date,
                weightKg = grams / 1000.0,
                bodyFatPct = e.at("bodyFat").dbl(),
                boneMassKg = e.at("boneMass").dbl()?.div(1000.0),
                muscleKg = e.at("muscleMass").dbl()?.div(1000.0),
                waterKg = e.at("bodyWater").dbl()?.let { pct -> grams / 1000.0 * pct / 100 },
                source = "Garmin",
            )
        }
    }

    /** Creates a structured workout in Garmin Connect and puts it on the calendar for [date]. Returns (tokens, workoutId). */
    suspend fun createAndScheduleWorkout(start: GarminTokens, workout: JsonObject, date: LocalDate): Pair<GarminTokens, Long> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val created = send(tokens, "POST", "/workout-service/workout", workout.toString())
        val id = json.parseToJsonElement(created).at("workoutId").long() ?: throw GarminException("Garmin didn't return a workout id")
        send(tokens, "POST", "/workout-service/schedule/$id", """{"date":"$date"}""")
        tokens to id
    }

    /** Ids of every workout in your Garmin library that this app made (named "Cape100 · …"). */
    suspend fun appWorkoutIds(start: GarminTokens): Pair<GarminTokens, List<Long>> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val list = json.parseToJsonElement(send(tokens, "GET", "/workout-service/workouts?start=0&limit=200", null)) as? kotlinx.serialization.json.JsonArray
        tokens to list.orEmpty().filter { it.at("workoutName").str()?.startsWith("Cape100 ·") == true }.mapNotNull { it.at("workoutId").long() }
    }

    /** Removes a workout this app created earlier (also clears it from the calendar). */
    suspend fun deleteWorkout(start: GarminTokens, id: Long): GarminTokens = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        runCatching { send(tokens, "DELETE", "/workout-service/workout/$id", null) }
        tokens
    }

    private fun send(tokens: GarminTokens, method: String, path: String, body: String?): String {
        val req = Request.Builder().url(GarminAuth.API + path)
            .method(method, body?.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("User-Agent", GarminAuth.UA_API)
            .header("Accept", "application/json")
            .build()
        return http.newCall(req).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw GarminException("Garmin $method $path failed (${it.code}) ${text.take(150)}")
            text
        }
    }

    /** Adds a weigh-in to Garmin Connect. Returns updated tokens; throws on failure. */
    suspend fun uploadWeight(start: GarminTokens, kg: Double, time: java.time.Instant): GarminTokens = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SS")
        val local = time.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val gmt = time.atZone(java.time.ZoneOffset.UTC).toLocalDateTime()
        val body = """{"dateTimestamp":"${local.format(fmt)}","gmtTimestamp":"${gmt.format(fmt)}","unitKey":"kg","sourceType":"MANUAL","value":${"%.2f".format(java.util.Locale.US, kg)}}"""
        val req = Request.Builder().url(GarminAuth.API + "/weight-service/user-weight")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("User-Agent", GarminAuth.UA_API)
            .build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw GarminException("Garmin didn't accept the weigh-in (${it.code})") }
        tokens
    }

    /** Lap-by-lap splits for one activity as short text lines (empty if unavailable). */
    suspend fun activitySplits(start: GarminTokens, activityId: Long): Pair<GarminTokens, List<String>> = withContext(Dispatchers.IO) {
        val tokens = auth.ensureFresh(start)
        val laps = (getJsonOrNull(tokens.accessToken, "/activity-service/activity/$activityId/splits").at("lapDTOs") as? JsonArray).orEmpty()
        val lines = laps.mapIndexedNotNull { i, lap ->
            val km = (lap.at("distance").dbl() ?: return@mapIndexedNotNull null) / 1000.0
            val sec = lap.at("duration").dbl() ?: return@mapIndexedNotNull null
            if (km < 0.05) return@mapIndexedNotNull null
            val pace = sec / 60.0 / km
            "lap ${i + 1}: %.2f km @ %d:%02d/km, HR %s, +%sm".format(
                km, pace.toInt(), ((pace % 1) * 60).roundToInt().coerceAtMost(59),
                lap.at("averageHR").int()?.toString() ?: "-", lap.at("elevationGain").int()?.toString() ?: "0",
            )
        }
        tokens to lines.take(60)
    }

    private fun getJson(bearer: String, path: String): JsonElement? {
        val req = Request.Builder().url(GarminAuth.API + path)
            .header("Authorization", "Bearer $bearer")
            .header("User-Agent", GarminAuth.UA_API)
            .header("Accept", "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw GarminException("Garmin rejected the token. Please log in again.")
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) null else json.parseToJsonElement(body)
        }
    }

    /** Individual metrics are optional (not every watch has every feature) - never fail the sync for one. */
    private fun getJsonOrNull(bearer: String, path: String): JsonElement? =
        try { getJson(bearer, path) } catch (e: GarminException) { throw e } catch (e: Exception) { null }
}

internal fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonElement?.int(): Int? = dbl()?.roundToInt()

/** Depth-first search for the first non-null value with this key anywhere in the tree. */
internal fun JsonElement?.find(key: String): JsonElement? = when (this) {
    is JsonObject -> this[key]?.takeIf { it.str() != null || it !is JsonPrimitive }
        ?: values.firstNotNullOfOrNull { it.find(key) }
    is JsonArray -> firstNotNullOfOrNull { it.find(key) }
    else -> null
}
