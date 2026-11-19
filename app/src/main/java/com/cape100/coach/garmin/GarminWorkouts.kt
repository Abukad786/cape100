package com.cape100.coach.garmin

import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns plan sessions into Garmin Connect structured workouts (the JSON the Garmin Connect app itself uploads),
 * so they show up on the watch under Training → Workouts and on the Garmin calendar.
 */
object GarminWorkouts {
    private const val RUN_ID = 1
    private const val STRENGTH_ID = 5

    private class Builder(private val sportId: Int, private val sportKey: String) {
        private var order = 0
        private var child = 0
        val steps = mutableListOf<JsonObject>()

        private fun type(id: Int, key: String) = buildJsonObject { put("stepTypeId", id); put("stepTypeKey", key) }

        fun step(kind: String, seconds: Int? = null, meters: Int? = null, reps: Int? = null, note: String, childId: Int? = null): JsonObject {
            val (id, key) = when (kind) { "warmup" -> 1 to "warmup"; "cooldown" -> 2 to "cooldown"; "recovery" -> 4 to "recovery"; "rest" -> 5 to "rest"; else -> 3 to "interval" }
            return buildJsonObject {
                put("type", "ExecutableStepDTO")
                put("stepOrder", ++order)
                childId?.let { put("childStepId", it) }
                put("stepType", type(id, key))
                putJsonObject("endCondition") {
                    when {
                        meters != null -> { put("conditionTypeId", 3); put("conditionTypeKey", "distance") }
                        reps != null -> { put("conditionTypeId", 10); put("conditionTypeKey", "reps") }
                        seconds != null -> { put("conditionTypeId", 2); put("conditionTypeKey", "time") }
                        else -> { put("conditionTypeId", 1); put("conditionTypeKey", "lap.button") }
                    }
                }
                (meters?.toDouble() ?: reps?.toDouble() ?: seconds?.toDouble())?.let { put("endConditionValue", it) }
                putJsonObject("targetType") { put("workoutTargetTypeId", 1); put("workoutTargetTypeKey", "no.target") }
                put("description", note.take(120))
            }
        }

        fun add(kind: String, seconds: Int? = null, meters: Int? = null, reps: Int? = null, note: String) {
            steps += step(kind, seconds, meters, reps, note)
        }

        /** A repeat group: [times] × the given (kind, seconds/reps, note) steps. */
        fun repeat(times: Int, vararg inner: Triple<String, Pair<Int?, Int?>, String>) {
            val id = ++child
            val groupOrder = ++order
            val children = inner.map { (kind, sr, note) -> step(kind, seconds = sr.first, reps = sr.second, note = note, childId = id) }
            steps += buildJsonObject {
                put("type", "RepeatGroupDTO")
                put("stepOrder", groupOrder)
                put("childStepId", id)
                put("stepType", type(6, "repeat"))
                put("numberOfIterations", times)
                put("smartRepeat", false)
                putJsonObject("endCondition") { put("conditionTypeId", 7); put("conditionTypeKey", "iterations") }
                put("endConditionValue", times.toDouble())
                put("workoutSteps", JsonArray(children))
            }
        }

        fun build(name: String, description: String) = buildJsonObject {
            put("workoutName", name.take(60))
            put("description", description.take(900))
            putJsonObject("sportType") { put("sportTypeId", sportId); put("sportTypeKey", sportKey) }
            put("workoutSegments", buildJsonArray {
                add(buildJsonObject {
                    put("segmentOrder", 1)
                    putJsonObject("sportType") { put("sportTypeId", sportId); put("sportTypeKey", sportKey) }
                    put("workoutSteps", JsonArray(steps))
                })
            })
        }
    }

    /** Running workout for a session, or null for rest/strength/race days. */
    fun run(s: Session): JsonObject? {
        if (s.distanceKm <= 0 || s.type in setOf(SessionType.REST, SessionType.STRENGTH, SessionType.RACE)) return null
        val b = Builder(RUN_ID, "running")
        val dur = max(s.durationMin, 15)
        val main = max(dur - 15, 5)
        when {
            s.type == SessionType.INTERVALS && s.title.startsWith("Sharpener") -> {
                b.add("warmup", 480, note = "Easy warm-up")
                b.repeat(5, Triple("interval", 60 to null, "Quick, relaxed"), Triple("recovery", 60 to null, "Easy jog"))
                b.add("cooldown", max(dur - 18, 4) * 60, note = "Easy")
            }
            s.type == SessionType.INTERVALS -> {
                b.add("warmup", 480, note = "Easy warm-up")
                b.repeat(max(main / 3, 3), Triple("interval", 120 to null, "Hard – 10K effort"), Triple("recovery", 60 to null, "Easy jog"))
                b.add("cooldown", 420, note = "Easy cool-down")
            }
            s.type == SessionType.HILLS -> {
                b.add("warmup", 480, note = "Easy to the hill")
                b.repeat(max(main / 2, 4), Triple("interval", 60 to null, "Uphill hard"), Triple("recovery", 60 to null, "Walk/jog down"))
                b.add("cooldown", 420, note = "Easy home")
            }
            s.type == SessionType.TEMPO && s.title.startsWith("Fartlek") -> {
                b.add("warmup", 480, note = "Easy")
                b.repeat(max(main / 3, 3), Triple("interval", 60 to null, "10K effort"), Triple("recovery", 120 to null, "Easy"))
                b.add("cooldown", 300, note = "Easy")
            }
            s.type == SessionType.TEMPO && !s.title.startsWith("Ultra") -> {
                b.add("warmup", 480, note = "Easy warm-up")
                b.add("interval", main * 60, note = s.intensity)
                b.add("cooldown", 420, note = "Easy cool-down")
            }
            s.type in setOf(SessionType.LONG, SessionType.BACK_TO_BACK, SessionType.TUNEUP_RACE, SessionType.EVENT) ->
                b.add("interval", meters = (s.distanceKm * 1000).roundToInt(), note = s.intensity.ifBlank { "Easy, conversational" })
            else -> b.add("interval", s.durationMin * 60, note = s.intensity.ifBlank { "Easy, conversational" })
        }
        return b.build("Cape100 · ${s.title}", s.details)
    }

    /** Strength as one timed block ("Strength · 25 min") – follow the app for the exercises. */
    fun strength(s: Session): JsonObject? {
        val minutes = if (s.type == SessionType.STRENGTH && s.durationMin > 0) s.durationMin else 20
        val name = Regex("""Home upper body [AB]""").find(s.details)?.value ?: "Strength"
        val b = Builder(STRENGTH_ID, "strength_training")
        b.add("interval", minutes * 60, note = "Follow today's exercises in the Cape 100 Coach app")
        return b.build("Cape100 · $name · $minutes min", "Strength session – see the app for exercises and how-tos.")
    }

    /** (Old) exercise-by-exercise version, no longer sent. */
    private fun strengthDetailed(s: Session): JsonObject? {
        val text = s.details
        val rounds = Regex("""(\d+) rounds? of""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val line = text.substringAfter("rounds of").trim().lineSequence().firstOrNull()?.trim() ?: return null
        val moves = line.split("·").map { it.trim() }.filter { it.isNotBlank() }
        if (moves.isEmpty()) return null
        val b = Builder(STRENGTH_ID, "strength_training")
        val inner = moves.map { m ->
            val secs = Regex("""(\d+)\s*s\b""").find(m)?.groupValues?.get(1)?.toIntOrNull()
            val reps = Regex("""(\d+)(?:[–-](\d+))?""").find(m)?.let { it.groupValues[2].toIntOrNull() ?: it.groupValues[1].toIntOrNull() }
            if (secs != null) Triple("interval", secs to null, m) else Triple("interval", null to (reps ?: 12), m)
        } + Triple("rest", 60 to null, "Rest between rounds")
        b.repeat(rounds, *inner.toTypedArray())
        val title = Regex("""Home upper body [AB]""").find(text)?.value ?: s.title
        return b.build("Cape100 · $title", text)
    }
}
