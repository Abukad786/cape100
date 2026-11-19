package com.cape100.coach.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.cape100.coach.data.AiAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/** Daily coaching via the Claude API (your own API key from console.anthropic.com). */
class AiCoach(private val apiKey: String, private val model: String) {

    companion object {
        // Models that accept the server-side refusal fallback.
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

        private const val SYSTEM = """You are an experienced ultramarathon coach guiding one athlete to the CAPE 100:
a 100 km point-to-point ROAD ultra from Hermanus to the Stellenbosch Winelands, South Africa, on Sunday 5 September 2027.
Course facts: ~900 m total elevation gain, 05:00 start before sunrise, 15 h finish cut-off (20:00).
Entry requires an officially timed road marathon under 4:59:59 between 1 Jan 2026 and 31 May 2027 (submit by 7 June 2027).

You receive the athlete's Garmin data, today's planned session from their periodised plan, a rule-based readiness verdict,
recent activities and how they feel. Decide whether they should train today and exactly what the session should be.
Principles: consistency over heroics; protect the long run and the qualifier; never stack hard days on poor recovery;
illness above the neck may allow easy running, below the neck (fever, chest) means rest; flag injury warning signs.
Be specific (distance, pace or HR, structure) and encouraging, but honest. Use metric units."""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun dailyAdvice(context: String): AiAdvice = withContext(Dispatchers.IO) {
        val text = call(
            context + """

Respond with ONLY a JSON object, no markdown, with these keys:
{"verdict":"GO|ADJUST|RECOVER|REST","headline":"one punchy sentence","sessionTitle":"short title",
"sessionDetails":"exact workout: warm-up, main set, cool-down, pace/HR","durationMin":number,"distanceKm":number,
"reasoning":"2-4 sentences explaining the call using their data","fueling":"what to eat/drink before, during, after",
"focus":"one mental or technique cue for today",
"requestWeighIn":true or false (ask for a weigh-in today if it would help: none in 3+ days, a long run/heat day where a
before-and-after weigh-in shows sweat loss, or weight trending fast),"weighInNote":"why and how, e.g. before your shower"}""",
        )
        parse(text)
    }

    /** Friendly, affectionate breakdown of one just-synced activity. */
    suspend fun activityBreakdown(context: String, activity: String): ActivityBreakdownText = withContext(Dispatchers.IO) {
        val text = call(
            context + """

NEW ACTIVITY JUST SYNCED FROM GARMIN:
$activity

Break this activity down for the athlete. Tone: warm, friendly and encouraging, like a supportive partner who also
happens to be a great coach. Call them "babe" naturally once or twice (not every sentence). Be genuinely useful:
compare it to what the plan asked for that day, read the pacing and heart rate across the laps, note fade or negative
splits, effort vs. Garmin training effect, and what it means for CAPE 100. Be honest if something needs work, but kind.

Respond with ONLY a JSON object, no markdown:
{"headline":"one short warm line","breakdown":"2 short paragraphs","wins":["2-4 short bullet wins"],
"workOn":"one thing to improve next time","recovery":"what to do in the next 24 hours (food, sleep, easy day)"}""",
        )
        parseBreakdown(text)
    }

    /** Short, warm feedback straight after a weigh-in. */
    suspend fun weighInFeedback(context: String, weighIn: String): String = withContext(Dispatchers.IO) {
        call(
            context + """

NEW WEIGH-IN JUST SAVED:
$weighIn

Give the athlete feedback on this weigh-in in under 90 words. Tone: warm, friendly, encouraging, like a supportive
partner who is also a great coach; call them "babe" once. Comment on the change vs last time and the 7/30-day trend,
body fat / muscle if present, and hydration (weighing after a run reads lighter from sweat – remind them to rehydrate).
Relate it to CAPE 100 (a lighter, strong body helps over 100 km, but never under-fuel training). Plain text, no lists.""",
        )
    }

    /** Stretch routines for today, tailored to the session and tight areas. Returns raw JSON text. */
    suspend fun stretchPlanJson(context: String, today: String, tight: String, foamRoller: Boolean): String = withContext(Dispatchers.IO) {
        call(
            context + """

TODAY: $today
TIGHT AREAS: $tight. Foam roller: ${if (foamRoller) "yes" else "no"}.
Write today's stretching for this runner. Static stretches go AFTER running (never cold before); before a run only
gentle dynamic moves. Make it specific to today's load (more after long/hard runs, gentler mobility on rest days) and
prioritise the tight areas. Respond with ONLY JSON, no markdown:
{"postRun":{"title":"...","minutes":number,"moves":[{"name":"...","hold":"e.g. 2 x 45 s each side","how":"one clear sentence of form"}]},
"evening":{"title":"...","minutes":number,"moves":[...]},
"desk":[{"name":"...","hold":"...","how":"..."}]}
4-6 moves for postRun and evening, 4 quick desk moves (can be done at work in 1-2 minutes).""",
        )
    }

    /** The athlete tells the coach how today went; the coach replies and may change today and/or tomorrow. */
    suspend fun coachUpdateJson(context: String, report: String): String = withContext(Dispatchers.IO) {
        call(
            context + """

ATHLETE UPDATE (now ${java.time.LocalDateTime.now().withNano(0)}):
$report

Respond warmly (you can call them babe) and adjust the plan sensibly: if they missed the run, decide whether to do a
shorter version later today, move it, or let it go (never stack hard days; respect the 30-min weekday window unless they
say they have more time); if they did something different, count it and adjust tomorrow if needed. Only change days
that need changing. Respond with ONLY JSON, no markdown:
{"reply":"2-4 sentences","completed":"DONE|PARTIAL|SKIPPED",
"today":null or {"title":"...","type":"EASY|RECOVERY|LONG|TEMPO|INTERVALS|HILLS|STRENGTH|REST|CROSS","distanceKm":number,"durationMin":number,"intensity":"...","details":"exact instructions"},
"tomorrow":null or the same shape}""",
        )
    }

    data class StartingVolume(val weeklyKm: Int, val longestRunKm: Int, val note: String, val goals: List<com.cape100.coach.data.LongRunGoal> = emptyList())

    /** The coach decides where the plan should start (weekly km + longest run) from the athlete's real history. */
    suspend fun startingVolume(context: String, history: String, why: String): StartingVolume? = withContext(Dispatchers.IO) {
        val text = call(
            context + """

TRAINING HISTORY FROM GARMIN:
$history

TASK (take any athlete feedback in it seriously): $why
Decide the weekly running volume (km) the athlete's training plan should START at this week, and the longest-run
length it should build from. The plan then progresses about 10% per week with a recovery week every 4th week, up to a
~105 km/week peak before CAPE 100, and needs ~45 km/week before the qualifier marathon. The long run should start close to what they have recently managed (roughly 70-90% of their longest run in the
last 6-8 weeks), not far below it; with short weekday runs the weekend long run can be up to half the week. Balance ambition (time to the
qualifier and race) against injury risk from their real recent consistency, longest runs and gaps.

Respond with ONLY a JSON object, no markdown:
{"startWeeklyKm":number,"longestRunKm":number,"note":"1-2 warm sentences explaining your call (you can call them babe)",
"longRunGoals":[{"km":number,"date":"YYYY-MM-DD"}]}
Include longRunGoals only when the athlete names long-run targets with timing (use the Sunday on or before their
deadline; today is ${java.time.LocalDate.now()} (${java.time.LocalDate.now().dayOfWeek})). Keep their targets when the
build is at most ~3 km per week from their recent longest run; otherwise move the date later and say so in the note.""",
        )
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() else null
        val km = obj?.get("startWeeklyKm")?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return@withContext null
        val longest = obj["longestRunKm"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: (km * 0.4)
        val note = obj["note"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        val goals = (obj["longRunGoals"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            val gk = o["km"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return@mapNotNull null
            val gd = o["date"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
            if (runCatching { java.time.LocalDate.parse(gd) }.isSuccess && gk in 3.0..120.0) com.cape100.coach.data.LongRunGoal(gk, gd) else null
        }
        StartingVolume(km.toInt().coerceIn(5, 80), longest.toInt().coerceIn(3, 40), note, goals)
    }

    data class ActivityBreakdownText(val headline: String, val breakdown: String, val wins: List<String>, val workOn: String, val recovery: String)

    private fun parseBreakdown(text: String): ActivityBreakdownText {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() else null
        fun s(k: String) = obj?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        val wins = (obj?.get("wins") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        return if (obj == null) ActivityBreakdownText("Here's your run, babe", text, emptyList(), "", "")
        else ActivityBreakdownText(s("headline"), s("breakdown"), wins, s("workOn"), s("recovery"))
    }

    /** Sick-day check-in: can they try something gentle today, and what? */
    suspend fun illnessCheckJson(context: String, report: String): String = withContext(Dispatchers.IO) {
        call(
            context + """

SICK-DAY CHECK-IN:
$report

You're their coach, not their doctor. Decide if they can try something gentle TODAY, using their words and their Garmin data
(resting HR and HRV vs normal, sleep, Body Battery). Be cautious:
- No training with fever, chest symptoms (cough, tight chest), body aches, vomiting/diarrhoea, or while feeling worse.
- "Neck check": mild head-cold symptoms only may allow a short easy effort.
- After an injection or while on medication (especially antibiotics), keep it to a walk or very easy jog, and follow their doctor.
- Resting HR more than ~5 bpm above normal or HRV clearly down = rest.
- First session back: 20–30 min walk or very easy jog, HR well under their easy ceiling, stop if anything feels off.
If unsure, rest. If symptoms are severe or not improving after several days, suggest seeing their doctor again.
Reply warmly (you can call them babe) with JSON only:
{"canTrain": true|false, "reply": "under 90 words: your call and why, what to watch for",
 "session": null or {"type":"RECOVERY|EASY","title":"...","distanceKm":number,"durationMin":number,"intensity":"...","details":"..."}}
"""
        )
    }

    /** Message from the Plan tab's box: a question gets an answer; only a clear request changes the plan. */
    suspend fun planRequestJson(context: String, message: String): String = withContext(Dispatchers.IO) {
        call(
            context + """

The athlete typed this in the box on their Plan tab: "$message"

Decide whether they are clearly asking you to CHANGE the plan (e.g. "my long runs are too short", "make weekdays easier",
"I want 20 km by mid-November"), or whether it's a question, a comment or something you can answer without changing it.
When in doubt, do NOT change the plan – answer and tell them what you'd change if they want.
Reply with JSON only: {"change": true|false, "reply": "your answer, warm, under 120 words (you can call them babe)"}
"""
        )
    }

    suspend fun ask(context: String, question: String, words: Int = 200): String = withContext(Dispatchers.IO) {
        call("$context\n\nThe athlete asks: $question\n\nAnswer conversationally in under $words words.")
    }

    private fun call(prompt: String): String {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addUserMessage(prompt)
            if (model in FALLBACK_MODELS) {
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val message = client.messages().create(builder.build())
            val stop = message.stopReason().map { it.toString() }.orElse("")
            if (stop.contains("refusal", ignoreCase = true)) {
                throw IllegalStateException("The AI coach declined this request. Try rephrasing.")
            }
            return message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("\n").trim()
        } finally {
            client.close()
        }
    }

    private fun parse(text: String): AiAdvice {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() else null
        fun s(k: String) = obj?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        fun d(k: String) = obj?.get(k)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        return if (obj == null) AiAdvice(date = LocalDate.now().toString(), headline = "Coach's notes", reasoning = text)
        else AiAdvice(
            date = LocalDate.now().toString(),
            verdict = s("verdict").uppercase(),
            headline = s("headline"),
            sessionTitle = s("sessionTitle"),
            sessionDetails = s("sessionDetails"),
            durationMin = d("durationMin")?.toInt(),
            distanceKm = d("distanceKm"),
            reasoning = s("reasoning"),
            fueling = s("fueling"),
            focus = s("focus"),
            requestWeighIn = obj["requestWeighIn"]?.let { runCatching { it.jsonPrimitive.contentOrNull == "true" }.getOrNull() } == true,
            weighInNote = s("weighInNote"),
        )
    }
}
