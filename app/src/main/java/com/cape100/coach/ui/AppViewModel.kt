package com.cape100.coach.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cape100.coach.data.AiAdvice
import com.cape100.coach.data.CheckIn
import com.cape100.coach.data.CoachRepository
import com.cape100.coach.data.Profile
import com.cape100.coach.data.Settings
import com.cape100.coach.plan.LoadCheck
import com.cape100.coach.races.RaceSuggestion
import com.cape100.coach.work.Reminders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = CoachRepository(app)
    private val store = repo.store

    /** Bumped whenever stored data changes so composables re-read from the repository. */
    var version by mutableStateOf(0); private set
    var syncing by mutableStateOf(false); private set
    var aiLoading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    /** Tab to open (e.g. from a notification); 3 = Health. */
    var requestedTab by mutableStateOf<Int?>(null)
    var askAnswer by mutableStateOf<String?>(null); private set
    var needsMfa by mutableStateOf(false); private set
    private var mfaDeferred: CompletableDeferred<String>? = null

    init {
        if (repo.garminConnected && repo.store.backfillState != "done") com.cape100.coach.work.HealthBackfillWorker.enqueue(app)
        repo.importPendingOkok().takeIf { it > 0 }?.let { message = "Imported $it weigh-ins from OKOK" }
        repo.importGarminExport().takeIf { it > 0 }?.let { message = "Imported $it activities and your health history from Garmin" }
    }

    val profile: Profile get() = store.profile
    val settings: Settings get() = store.settings

    private fun bump() { version++ }

    /** Re-reads stored data (used while background jobs are running). */
    fun refresh() = bump()

    fun saveProfile(p: Profile) {
        val old = store.profile
        store.profile = p.copy(planStart = old.planStart.ifBlank { LocalDate.now().toString() })
        bump()
    }

    fun regeneratePlanFromToday() {
        val p = store.profile
        store.profile = p.copy(planStart = LocalDate.now().toString(), journeyStart = p.journeyStart.ifBlank { p.planStart })
        bump()
    }

    fun saveSettings(s: Settings) {
        store.settings = s
        Reminders.schedule(getApplication(), s, replace = true)
        bump()
    }

    fun saveApiKey(key: String) { store.anthropicKey = key; bump() }

    fun saveCheckIn(c: CheckIn) {
        store.checkIn = c
        // "Sick or injured" in the check-in starts sick mode.
        if (c.sick && !repo.illnessActive()) startIllness(LocalDate.now(), c.note)
        bump()
    }

    fun setCompletion(date: String, status: String?) { repo.setCompletion(date, status); bump() }

    fun sync(quiet: Boolean = false) {
        if (syncing || !repo.garminConnected) return
        viewModelScope.launch {
            syncing = true
            val sourceBefore = repo.store.profile.baselineSource
            val err = repo.syncGarmin()
            syncing = false
            val p = repo.store.profile
            message = err?.let { "Garmin sync failed: $it" }
                ?: if (sourceBefore != "ai" && p.baselineSource == "ai") "Coach set your plan to start at ${p.currentWeeklyKm} km/week"
                else if (quiet) null else "Synced with Garmin"
            bump()
            if (err == null) breakdownLatest(force = false)
        }
    }

    /** Syncs on opening the app unless Garmin was synced in the last 10 minutes. */
    fun syncIfStale() {
        val last = repo.store.snapshot?.fetchedAt ?: 0L
        if (System.currentTimeMillis() - last > 10 * 60_000L) sync(quiet = true)
    }

    var bodyLoading by mutableStateOf(false); private set

    /** Reads the OKOK scale data from Health Connect. */
    fun syncBody() {
        if (bodyLoading) return
        viewModelScope.launch {
            bodyLoading = true
            val err = repo.syncBody()
            bodyLoading = false
            message = err ?: "Scale data updated (${repo.store.bodyReadings.size} weigh-ins)"
            bump()
        }
    }

    var weighInFeedbackLoading by mutableStateOf(false); private set

    /** Coach feedback for the latest weigh-in (e.g. one saved before feedback existed). */
    fun feedbackForLatestWeighIn() {
        val r = repo.store.bodyReadings.firstOrNull() ?: return
        if (weighInFeedbackLoading || !repo.aiConfigured) return
        viewModelScope.launch {
            weighInFeedbackLoading = true
            runCatching { repo.weighInFeedback(r) }.onFailure { message = "Coach feedback failed: ${it.message}" }
            weighInFeedbackLoading = false
            bump()
        }
    }

    fun saveScaleReading(weightKg: Double, impedance: Double?) = repo.saveScaleReading(weightKg, impedance).also { r ->
        bump()
        // Coach feedback on the weigh-in, shown in the weigh-in window and on the body card.
        if (repo.aiConfigured) viewModelScope.launch {
            weighInFeedbackLoading = true
            runCatching { repo.weighInFeedback(r) }
            weighInFeedbackLoading = false
            bump()
        }
        if (settings.uploadWeighInsToGarmin && repo.garminConnected) viewModelScope.launch {
            message = if (repo.uploadToGarmin(r)) "Weigh-in added to Garmin Connect" else "Saved here, but Garmin didn't accept the weigh-in"
            bump()
        }
    }

    var okokUploadProgress by mutableStateOf<String?>(null); private set

    fun uploadOkokHistory() {
        if (okokUploadProgress != null) return
        viewModelScope.launch {
            okokUploadProgress = "Starting…"
            val sent = repo.uploadOkokHistoryToGarmin { done, total -> okokUploadProgress = "Sending $done/$total…" }
            okokUploadProgress = null
            message = "Sent $sent OKOK weigh-ins to Garmin Connect"
            bump()
        }
    }

    fun rebuildHistory() {
        repo.store.backfillState = "Queued…"
        com.cape100.coach.work.HealthBackfillWorker.enqueue(getApplication(), restart = true)
        bump()
    }

    var breakdownLoading by mutableStateOf(false); private set

    /** Breaks down the newest Garmin activity with the AI coach (only if it's new, unless forced). */
    fun breakdownLatest(force: Boolean) {
        if (breakdownLoading || !repo.aiConfigured) {
            if (force && !repo.aiConfigured) message = "Add your Claude API key in Settings for activity breakdowns"
            return
        }
        viewModelScope.launch {
            breakdownLoading = true
            try {
                repo.breakdownLatestIfNew(force)?.let { message = "💕 New breakdown of your ${it.activityName} is ready" }
            } catch (e: Exception) {
                message = "Breakdown failed: ${e.message}"
            }
            breakdownLoading = false
            bump()
        }
    }

    fun garminLogin(email: String, password: String) {
        viewModelScope.launch {
            syncing = true
            try {
                repo.garminLogin(email, password) {
                    val d = CompletableDeferred<String>()
                    mfaDeferred = d
                    needsMfa = true
                    d.await().also { needsMfa = false }
                }
                message = "Garmin connected"
            } catch (e: Exception) {
                message = e.message ?: "Garmin login failed"
            } finally {
                syncing = false
                needsMfa = false
                bump()
            }
        }
    }

    fun submitMfa(code: String) { mfaDeferred?.complete(code) }

    fun garminLogout() { repo.garminLogout(); bump() }

    fun runAi() {
        if (!repo.aiConfigured) { message = "Add your Claude API key in Settings to use the AI coach"; return }
        viewModelScope.launch {
            aiLoading = true
            try { repo.runDailyAi() } catch (e: Exception) { message = "AI coach: ${e.message}" }
            aiLoading = false
            bump()
        }
    }

    fun ask(q: String) {
        if (!repo.aiConfigured) { message = "Add your Claude API key in Settings to use the AI coach"; return }
        viewModelScope.launch {
            aiLoading = true
            askAnswer = try { repo.ask(q) } catch (e: Exception) { "Error: ${e.message}" }
            aiLoading = false
        }
    }

    // ------------------------------------------------------------ Races

    var racesLoading by mutableStateOf(false); private set

    fun refreshRaces(force: Boolean = false) {
        if (racesLoading) return
        viewModelScope.launch {
            racesLoading = true
            repo.refreshRaces(force)?.let { message = it }
            racesLoading = false
            bump()
        }
    }

    fun setHomeFromDevice() {
        viewModelScope.launch {
            racesLoading = true
            message = repo.setHomeFromDevice() ?: "Home set to ${profile.homeName}"
            racesLoading = false
            bump()
        }
    }

    fun setHomeFromTown(town: String) {
        viewModelScope.launch {
            racesLoading = true
            message = repo.setHomeFromTown(town) ?: "Home set to ${profile.homeName}"
            racesLoading = false
            bump()
        }
    }

    fun useAsQualifier(s: RaceSuggestion) {
        repo.useAsQualifier(s.race)
        message = "${s.race.name} is now your qualifier – plan updated"
        bump()
    }

    fun useAsTuneUp(s: RaceSuggestion) {
        repo.useAsTuneUp(s.race)
        message = "${s.race.name} is now your tune-up – plan updated"
        bump()
    }

    // ------------------------------------------------------------ Sick / injured

    var illnessBusy by mutableStateOf(false); private set

    fun startIllness(start: LocalDate, note: String) {
        viewModelScope.launch {
            illnessBusy = true
            repo.startIllness(start, note)
            illnessBusy = false
            message = "Training paused – get well soon. Check in with your coach when you feel better."
            bump()
        }
    }

    fun illnessCheck(feeling: String, note: String) {
        if (illnessBusy) return
        viewModelScope.launch {
            illnessBusy = true
            runCatching { repo.illnessCheck(feeling, note) }.onFailure { message = "Check-in failed: ${it.message}" }
            illnessBusy = false
            bump()
        }
    }

    fun endIllness() {
        viewModelScope.launch {
            repo.endIllness()
            message = "Welcome back! The next few days ease you back in."
            bump()
        }
    }

    // ------------------------------------------------------------ Ideas for the app

    fun addIdea(text: String) {
        if (text.isBlank()) return
        repo.store.ideas = listOf(com.cape100.coach.data.AppIdea(java.time.LocalDateTime.now().withNano(0).toString(), text.trim())) + repo.store.ideas
        message = "Idea saved"
        bump()
    }

    fun removeIdea(time: String) { repo.store.ideas = repo.store.ideas.filterNot { it.time == time }; bump() }

    // ------------------------------------------------------------ Races you've entered

    /** Id of the race whose plan the coach is writing. */
    var racePlanLoading by mutableStateOf<String?>(null); private set

    fun enterRace(e: com.cape100.coach.data.EnteredRace) {
        repo.enterRace(e)
        message = "${e.name} is in your plan – the coach will prepare you for it"
        bump()
        if (repo.aiConfigured) racePlan(e.id)
    }

    fun removeEnteredRace(id: String) { repo.removeEnteredRace(id); message = "Race removed from your plan"; bump() }

    fun racePlan(id: String) {
        if (racePlanLoading != null) return
        if (!repo.aiConfigured) { message = "Add your Claude API key in Settings first"; return }
        viewModelScope.launch {
            racePlanLoading = id
            runCatching { repo.racePlan(id) }.onFailure { message = "Coach couldn't write the race plan: ${it.message}" }
            racePlanLoading = null
            bump()
        }
    }

    // ------------------------------------------------------------ Plan check

    fun applyPlanUpdate(c: LoadCheck) {
        viewModelScope.launch {
            syncing = true
            repo.applyPlanUpdate(c)
            syncing = false
            val p = repo.store.profile
            message = (if (p.baselineSource == "ai") "Coach rebuilt your plan from ${p.currentWeeklyKm} km/week" else "Plan rebuilt from ${p.currentWeeklyKm} km/week")
            bump()
        }
    }

    var replanning by mutableStateOf(false); private set
    /** The coach's reply to the Plan tab box (shown under it). */
    var planReply by mutableStateOf<String?>(null); private set

    /** Plan tab box: the coach answers questions and only changes the plan when you clearly ask it to. */
    fun replanWithFeedback(feedback: String) {
        if (replanning || feedback.isBlank()) return
        if (!repo.aiConfigured) { message = "Add your Claude API key in Settings first"; return }
        viewModelScope.launch {
            replanning = true
            val r = runCatching { repo.planFeedback(feedback) }.getOrNull()
            replanning = false
            planReply = r?.reply
            val p = repo.store.profile
            message = when {
                r == null -> "The coach couldn't answer – try again"
                r.changed -> "Coach updated your plan: ${p.currentWeeklyKm} km/week, long runs from ${p.longestRecentRunKm} km"
                else -> null
            }
            bump()
        }
    }

    var updating by mutableStateOf(false); private set

    /** "Update your coach": how today went, the coach replies and may change today/tomorrow. */
    fun sendCoachUpdate(status: String, note: String) {
        if (updating) return
        if (!repo.aiConfigured) { message = "Add your Claude API key in Settings first"; return }
        viewModelScope.launch {
            updating = true
            runCatching { repo.coachUpdate(status, note) }.onFailure { message = "Coach update failed: ${it.message}" }
            updating = false
            bump()
        }
    }

    var stretchLoading by mutableStateOf(false); private set

    fun refreshStretchPlan(force: Boolean) {
        if (stretchLoading) return
        viewModelScope.launch {
            stretchLoading = true
            runCatching { repo.refreshStretchPlan(force) }
            stretchLoading = false
            bump()
        }
    }

    fun setPlannedOverride(on: Boolean) { repo.setPlannedOverride(on); bump() }

    fun setStretchDone(done: Boolean) { repo.setStretchDone(done); bump() }

    fun snoozePlanCheck() { repo.snoozePlanCheck(); bump() }

    fun todayAdvice(): AiAdvice? = store.aiAdvice?.takeIf { it.date == LocalDate.now().toString() }
}
