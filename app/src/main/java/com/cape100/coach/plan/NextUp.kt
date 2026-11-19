package com.cape100.coach.plan

import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import java.time.LocalTime

/** The one thing to do next today, walking through the morning routine: run → weigh in → shower → stretch → done. */
object NextUp {

    enum class Action { NONE, MARK_DONE, WEIGH_IN, STRETCH_DONE }

    data class Step(val label: String, val title: String, val detail: String, val action: Action = Action.NONE)

    data class Input(
        val now: LocalTime,
        val wakeTime: String,
        val runTime: String,
        val routineNote: String,
        val today: Session?,
        val tomorrow: Session?,
        val sessionDone: Boolean,
        val weighedIn: Boolean,
        val coachWantsWeighIn: Boolean,
        val stretchDone: Boolean,
        val stretchTitle: String,
        val stretchMinutes: Int,
        val sick: Boolean = false,
    )

    fun parse(t: String): LocalTime? = runCatching {
        val (h, m) = t.trim().split(":").map { it.toInt() }
        LocalTime.of(h, m)
    }.getOrNull()

    private fun LocalTime.hm() = "%02d:%02d".format(hour, minute)

    fun compute(i: Input): Step {
        if (i.sick) return Step("", "Rest and recover", "Fluids, sleep and your meds. Check in with your coach below when you feel better.")
        val hasWorkout = i.today != null && i.today.type != SessionType.REST
        val runAt = parse(i.runTime) ?: LocalTime.of(6, 0)
        // From 19:00 the card looks at tomorrow – unless today's session is still open (then from 21:00).
        val evening = i.now >= LocalTime.of(21, 0) || (i.now >= LocalTime.of(19, 0) && (i.sessionDone || i.today == null || i.today.type == SessionType.REST))
        val strength = i.today?.type == SessionType.STRENGTH
        val verb = if (strength) "Strength" else "Run"

        // Evenings belong to tomorrow: what's on, and when to get to bed.
        if (evening) return tomorrow(i)

        if (hasWorkout && !i.sessionDone) {
            return if (i.now < runAt) Step(
                label = runAt.hm(),
                title = "$verb at ${runAt.hm()}",
                detail = i.today!!.title + (i.routineNote.takeIf { it.isNotBlank() && wakeRightBefore(i, runAt) }?.let { " · after $it" } ?: ""),
            ) else Step(
                label = "Now",
                title = i.today!!.title,
                detail = if (strength) "Tick it off when you're done." else "It ticks off when Garmin syncs, or mark it done yourself.",
                action = Action.MARK_DONE,
            )
        }

        if (!i.weighedIn && (i.now < LocalTime.NOON || i.coachWantsWeighIn)) return Step(
            label = "",
            title = "Weigh in, then shower",
            detail = "Barefoot, before eating or drinking.",
            action = Action.WEIGH_IN,
        )

        // Post-run stretch on workout days; the evening routine on rest days from midday.
        if (!i.stretchDone && i.stretchMinutes > 0 && (hasWorkout || i.now >= LocalTime.NOON)) return Step(
            label = "",
            title = "Stretch · ${i.stretchMinutes} min",
            detail = i.stretchTitle,
            action = Action.STRETCH_DONE,
        )

        val tomorrowTitle = i.tomorrow?.takeIf { it.type != SessionType.REST }?.title ?: "Rest day"
        return Step(
            label = "",
            title = if (hasWorkout) "All done for today 💪" else "Rest day – enjoy it",
            detail = "Tomorrow: $tomorrowTitle",
        )
    }

    // The pre-run routine (e.g. Tahajjud) only belongs with the run when you go straight out after it.
    private fun wakeRightBefore(i: Input, runAt: LocalTime): Boolean =
        parse(i.wakeTime)?.let { w -> !runAt.isBefore(w) && java.time.Duration.between(w, runAt).toMinutes() <= 60 } ?: true

    private fun tomorrow(i: Input): Step {
        val t = i.tomorrow
        val rest = t == null || t.type == SessionType.REST
        val wake = parse(i.wakeTime)
        val runAt = parse(i.runTime)
        val times = listOfNotNull(wake?.let { "up at ${it.hm()}" }, if (!rest) runAt?.let { "go at ${it.hm()}" } else null)
        // Seven hours' sleep before the alarm.
        val bed = wake?.minusHours(7)?.let { "Lights out by ${it.hm()}." }
        return Step(
            label = "Tomorrow",
            title = if (rest) "Rest day" else t!!.title,
            detail = listOfNotNull(
                times.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }?.plus("."),
                bed,
            ).joinToString(" "),
        )
    }
}
