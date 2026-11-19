package com.cape100.coach.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cape100.coach.MainActivity
import com.cape100.coach.R
import com.cape100.coach.data.CoachRepository
import com.cape100.coach.data.Settings
import com.cape100.coach.data.SessionType
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object Reminders {
    private const val MORNING = "morning-briefing"
    private const val EVENING = "evening-nudge"

    fun schedule(context: Context, s: Settings, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        if (s.morningEnabled) {
            wm.enqueueUniquePeriodicWork(MORNING, policy,
                PeriodicWorkRequestBuilder<MorningWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayUntil(s.morningHour, s.morningMinute), TimeUnit.MILLISECONDS).build())
        } else wm.cancelUniqueWork(MORNING)
        if (s.eveningEnabled) {
            wm.enqueueUniquePeriodicWork(EVENING, policy,
                PeriodicWorkRequestBuilder<EveningWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayUntil(s.eveningHour, s.eveningMinute), TimeUnit.MILLISECONDS).build())
        } else wm.cancelUniqueWork(EVENING)
        // Weigh-in reminders: after the weekday run, and after the weekend long run.
        if (s.weighInReminder) {
            wm.enqueueUniquePeriodicWork(WEIGH_IN, policy,
                PeriodicWorkRequestBuilder<WeighInReminderWorker>(1, TimeUnit.DAYS)
                    .setInputData(androidx.work.workDataOf("weekend" to false))
                    .setInitialDelay(delayUntil(s.weighInHour, s.weighInMinute), TimeUnit.MILLISECONDS).build())
            wm.enqueueUniquePeriodicWork(WEIGH_IN_WEEKEND, policy,
                PeriodicWorkRequestBuilder<WeighInReminderWorker>(1, TimeUnit.DAYS)
                    .setInputData(androidx.work.workDataOf("weekend" to true))
                    .setInitialDelay(delayUntil(s.weighInWeekendHour, s.weighInWeekendMinute), TimeUnit.MILLISECONDS).build())
        } else { wm.cancelUniqueWork(WEIGH_IN); wm.cancelUniqueWork(WEIGH_IN_WEEKEND) }
        // The post-run stretch rides along with the weigh-in reminder (one notification, not two); evenings get their own.
        listOf(STRETCH_POST, STRETCH_POST_WEEKEND).forEach { wm.cancelUniqueWork(it) }
        if (s.stretchReminders) {
            wm.enqueueUniquePeriodicWork(STRETCH_EVENING, policy, PeriodicWorkRequestBuilder<StretchReminderWorker>(1, TimeUnit.DAYS)
                .setInputData(androidx.work.workDataOf("slot" to "evening"))
                .setInitialDelay(delayUntil(s.stretchEveningHour, s.stretchEveningMinute), TimeUnit.MILLISECONDS).build())
        } else wm.cancelUniqueWork(STRETCH_EVENING)
        // Checks Garmin for new activities roughly every hour and sends a breakdown when one appears.
        wm.enqueueUniquePeriodicWork(ACTIVITY_WATCH, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ActivityWatchWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private const val ACTIVITY_WATCH = "activity-watch"
    private const val STRETCH_POST = "stretch-post"
    private const val STRETCH_POST_WEEKEND = "stretch-post-weekend"
    private const val STRETCH_EVENING = "stretch-evening"

    private fun plus(h: Int, m: Int, add: Int): Pair<Int, Int> { val t = (h * 60 + m + add) % (24 * 60); return t / 60 to t % 60 }
    private const val WEIGH_IN = "weigh-in-reminder"
    private const val WEIGH_IN_WEEKEND = "weigh-in-reminder-weekend"

    private fun delayUntil(h: Int, m: Int): Long {
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(h, m))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}

object Notifier {
    private const val CHANNEL = "coach"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Coach reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Daily training briefings and reminders" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /**
     * [tab] opens a specific tab when tapped (3 = Health). Each reminder shows at most once per [once] key – by default
     * once a day – so a job that runs again (phones like OPPO restart killed background work) can't repeat it.
     */
    fun show(context: Context, id: Int, title: String, text: String, tab: Int = -1, once: String = LocalDate.now().toString()) {
        val log = context.getSharedPreferences("cape100_notified", Context.MODE_PRIVATE)
        if (log.getString("n$id", null) == once) return
        log.edit().putString("n$id", once).commit()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** Stretch reminder with today's routine (post-run slot only on run days; evening every day). */
class StretchReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.store.profile.onboarded) return Result.success()
        val slot = inputData.getString("slot") ?: "evening"
        // Already ticked off on Today: on run days that was the post-run stretch, otherwise the evening one.
        val ticked = repo.store.stretchDoneDate == LocalDate.now().toString()
        if (ticked && (slot == "post" || !repo.runPlannedToday())) return Result.success()
        if (slot == "post") {
            val weekendDay = LocalDate.now().dayOfWeek.value >= 6
            if (inputData.getBoolean("weekend", false) != weekendDay || !repo.runPlannedToday()) return Result.success()
        }
        val plan = runCatching { repo.refreshStretchPlan() }.getOrDefault(repo.stretchPlanToday())
        val r = if (slot == "post") plan.postRun else plan.evening
        Notifier.show(
            applicationContext, if (slot == "post") 7 else 8,
            "🧘 ${r.title} · ${r.minutes} min",
            r.moves.joinToString("\n") { "• ${it.name} – ${it.hold}" } + "\nTap for how to do each one.",
            tab = 0,
        )
        return Result.success()
    }
}

/** Daily: reminds you to weigh in (before your shower) unless you already have today. */
class WeighInReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.store.profile.onboarded || repo.weighedInToday()) return Result.success()
        val weekendDay = LocalDate.now().dayOfWeek.value >= 6
        if (inputData.getBoolean("weekend", false) != weekendDay) return Result.success()
        val coach = repo.store.aiAdvice?.takeIf { it.date == LocalDate.now().toString() && it.requestWeighIn }?.weighInNote
        Notifier.show(
            applicationContext, 6, "⚖️ Weigh-in time, babe",
            (coach?.let { "Coach: $it\n" } ?: "") +
                "Weigh in now – after your run, before your shower and before eating or drinking. " +
                (if (repo.runPlannedToday() && repo.store.settings.stretchReminders)
                    repo.stretchPlanToday().postRun.let { "Then shower and do your ${it.minutes}-min stretch – it's on the Today screen. " } else "") +
                "Open the app and tap Weigh in.",
            tab = 0,
        )
        return Result.success()
    }
}

/** Morning: sync Garmin, decide, optionally ask the AI coach, then notify. */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.store.profile.onboarded) return Result.success()
        if (repo.garminConnected) repo.syncGarmin()
        runCatching { repo.breakdownLatestIfNew() }.getOrNull()?.let { notifyBreakdown(applicationContext, it) }
        val d = repo.todayDecision()
        val days = repo.daysToRace()
        var body = d.session?.let { s ->
            buildString {
                append(s.title)
                if (s.durationMin > 0) append(" (~${s.durationMin} min)")
                append("\n").append(d.verdict.label)
                d.reasons.firstOrNull()?.let { append(" · ").append(it) }
            }
        } ?: "No session planned."
        if (repo.store.settings.aiInMorning && repo.aiConfigured) {
            runCatching { repo.runDailyAi() }.getOrNull()?.let { a ->
                if (a.headline.isNotBlank()) body += "\n\nCoach: ${a.headline}"
                if (a.requestWeighIn && !repo.weighedInToday()) body += "\n⚖️ Coach wants a weigh-in today: ${a.weighInNote}"
            }
        }
        val readiness = repo.todaySnapshot()?.readinessScore?.let { " · readiness $it" } ?: ""
        Notifier.show(applicationContext, 1, "${d.verdict.name} – $days days to go$readiness", body)
        return Result.success()
    }
}

/** One-off: builds the overall health record from a year of Garmin history (resumable). */
class HealthBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.garminConnected) return Result.success()
        repo.backfillHistory()
        return if (repo.store.backfillState == "done") Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context, restart: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "health-backfill",
                if (restart) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<HealthBackfillWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}

/** Hourly: sync Garmin and, if there's a new activity, send the AI coach's breakdown. */
class ActivityWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.store.profile.onboarded || !repo.garminConnected) return Result.success()
        val before = repo.store.activities.maxOfOrNull { it.id }
        repo.syncGarmin()
        // Been sitting still? A gentle nudge with a quick desk stretch.
        repo.movementNudge()?.let { m ->
            Notifier.show(applicationContext, 9, "🚶 Time to move, babe",
                "Your watch says you've been still for a while. Stand up, walk for a minute, then: ${m.name} – ${m.hold}. ${m.how}")
        }
        if (!repo.aiConfigured) return Result.success()
        if (repo.store.activities.maxOfOrNull { it.id } == before && repo.store.breakdown?.activityId == before) return Result.success()
        runCatching { repo.breakdownLatestIfNew() }.getOrNull()?.let { notifyBreakdown(applicationContext, it) }
        return Result.success()
    }
}

fun notifyBreakdown(context: Context, b: com.cape100.coach.data.ActivityBreakdown) {
    Notifier.show(context, 5, "💕 " + b.headline.ifBlank { "Your ${b.activityName} breakdown" }, once = b.activityId.toString(), text =
        b.breakdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() + "\n\nOpen the app for the full breakdown.")
}

/** Evening: nudge if today's session isn't logged; on Sundays preview the week ahead. */
class EveningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CoachRepository(applicationContext)
        if (!repo.store.profile.onboarded) return Result.success()
        if (repo.garminConnected) repo.syncGarmin()
        runCatching { repo.breakdownLatestIfNew() }.getOrNull()?.let { notifyBreakdown(applicationContext, it) }
        val today = LocalDate.now()
        val session = repo.todayDecision().session
        val done = repo.store.completions[today.toString()]
        if (session != null && session.type != SessionType.REST && done == null) {
            Notifier.show(applicationContext, 2, "Still on the plan today?",
                "${session.title} isn't logged yet. Even a shorter version keeps the streak alive – or mark it skipped and rest well.")
        }
        if (today.dayOfWeek == DayOfWeek.SUNDAY) {
            repo.planCheck()?.let { c ->
                Notifier.show(applicationContext, 4, "Time to update your plan?", c.message + "\nOpen the app to rebuild it from your real training.")
            }
            repo.weekFor(today.plusDays(1))?.let { w ->
                val long = w.sessions.maxBy { it.distanceKm }
                Notifier.show(applicationContext, 3, "Next week: ${w.phase.label}" + if (w.isRecovery) " (recovery)" else "",
                    "${w.targetKm.roundToInt()} km planned. Key session: ${long.title}.\n${w.focus}")
            }
        }
        return Result.success()
    }
}
