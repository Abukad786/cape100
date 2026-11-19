package com.cape100.coach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.CheckIn
import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import com.cape100.coach.plan.LoadCheck
import com.cape100.coach.plan.Verdict
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Chip
import com.cape100.coach.ui.components.FlipCard
import com.cape100.coach.ui.components.FlipHint
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.components.ProgressBar
import com.cape100.coach.ui.components.ScoreRing
import com.cape100.coach.ui.components.SectionTitle
import com.cape100.coach.ui.components.StatPill
import com.cape100.coach.ui.components.color
import com.cape100.coach.ui.components.fmtDuration
import com.cape100.coach.ui.components.icon
import com.cape100.coach.ui.theme.HeroGradient
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TodayScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val repo = vm.repo
    val today = LocalDate.now()
    val p = vm.profile
    val decision = repo.todayDecision()
    val snap = repo.todaySnapshot()
    val week = repo.weekFor(today)
    val completions = repo.store.completions
    val todayKey = today.toString()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header ---------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting() + if (p.name.isNotBlank()) ", ${p.name}" else "", style = MaterialTheme.typography.headlineMedium)
                Text(today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + today.dayOfMonth + " " +
                    today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (repo.garminConnected) {
                if (vm.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::sync) { Icon(Icons.Filled.Refresh, "Sync Garmin") }
            }
        }
        Gap(8.dp)
        CountdownBanner(repo.daysToRace(), p.raceName, week?.let { "Week ${it.index + 1 + repo.weekOffset()}/${repo.plan().size + repo.weekOffset()} · ${it.phase.label}" } ?: "",
            qualifierLine(p))
        Gap(8.dp)
        NextUpCard(vm)
        if (repo.illnessActive()) { Gap(8.dp); SickCard(vm) }

        if (!repo.illnessActive()) repo.planCheck()?.let { check ->
            Gap(8.dp)
            PlanCheckCard(check, onUpdate = { vm.applyPlanUpdate(check) }, onSnooze = vm::snoozePlanCheck)
        }

        // Readiness flip card ---------------------------------------------
        SectionTitle("Should I train today?")
        val vColor = Color(decision.verdict.color)
        FlipCard(
            Modifier.fillMaxWidth(), key = "readiness-$todayKey",
            frontBrush = Brush.linearGradient(listOf(vColor.copy(alpha = 0.28f), MaterialTheme.colorScheme.surface)),
            front = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(decision.score, vColor)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(decision.verdict.name, color = vColor, fontWeight = FontWeight.Black, fontSize = 28.sp)
                        Text(decision.verdict.label, style = MaterialTheme.typography.titleMedium)
                        Gap(6.dp)
                        Text(decision.reasons.firstOrNull() ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Gap(6.dp)
                        FlipHint()
                    }
                }
            },
            back = {
                Column {
                    Text("Why", style = MaterialTheme.typography.titleMedium)
                    Gap(6.dp)
                    decision.reasons.forEach { Text("• $it", fontSize = 13.sp) }
                    Gap(10.dp)
                    if (snap != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            StatPill(Icons.Filled.MonitorHeart, snap.hrvLastNight?.toString() ?: "–", "HRV")
                            StatPill(Icons.Filled.Bedtime, snap.sleepScore?.toString() ?: "–", "Sleep")
                            StatPill(Icons.Filled.BatteryChargingFull, snap.bodyBattery?.toString() ?: "–", "Battery")
                            StatPill(Icons.Filled.Favorite, snap.restingHr?.toString() ?: "–", "RHR")
                        }
                        snap.readinessFeedback?.let { Gap(6.dp); Text("Garmin: ${it.lowercase()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        Text(if (repo.garminConnected) "No Garmin data yet today – tap refresh." else "Connect Garmin in Settings for HRV, sleep and readiness.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
        Gap(8.dp)
        CheckInCard(vm)
        if (!repo.illnessActive()) NotWellButton(vm)

        // Today's session --------------------------------------------------
        SectionTitle(if (decision.modified) "Today's session (adjusted)" else "Today's session")
        decision.session?.let { s ->
            SessionFlipCard(s, "today-$todayKey-${decision.verdict}-${repo.plannedOverrideToday}", big = true, adjusted = decision.modified)
            if (decision.coachNote != null) {
                Gap(6.dp)
                Text("✏️ ${decision.coachNote}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            } else if (decision.modified) {
                Gap(6.dp)
                Text("Adjusted from ${decision.planned?.title} because your readiness is low today (score ${decision.score}/100).",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.setPlannedOverride(true) }) {
                    Text("Feeling great? Do the planned session instead", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (repo.plannedOverrideToday) {
                TextButton(onClick = { vm.setPlannedOverride(false) }) { Text("Switch back to the coach's easier option") }
            }
            ExerciseHelp(s)
            Gap(8.dp)
            val c = completions[todayKey]
            if (s.type != SessionType.REST) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c?.status == "DONE") {
                    Chip("✓ Done${c.actualKm?.let { " · ${it} km" } ?: ""}", Color(0xFF22C55E))
                    TextButton(onClick = { vm.setCompletion(todayKey, null) }) { Text("Undo") }
                } else if (c?.status == "SKIPPED") {
                    Chip("Skipped", Color(0xFF94A3B8))
                    TextButton(onClick = { vm.setCompletion(todayKey, null) }) { Text("Undo") }
                } else {
                    Button(onClick = { vm.setCompletion(todayKey, "DONE") }) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mark done")
                    }
                    OutlinedButton(onClick = { vm.setCompletion(todayKey, "SKIPPED") }) { Text("Skip") }
                }
            }
        } ?: Text("No session planned today.")

        SectionTitle("Coach says")
        AiCoachCard(vm)

        // Update your coach -------------------------------------------------
        SectionTitle("How did today go?")
        CoachUpdateCard(vm)

        // Stretching ---------------------------------------------------------
        SectionTitle("Stretch & mobility")
        StretchCard(vm)

        // Fresh activity feedback only; older ones live on the Activity tab.
        val latest = repo.store.activities.firstOrNull()
        if (latest != null && runCatching { !LocalDate.parse(latest.date).isBefore(today.minusDays(1)) }.getOrDefault(false)) {
            SectionTitle("Your latest activity")
            BreakdownCard(vm)
        }

        // Week strip -------------------------------------------------------
        week?.let { w ->
            SectionTitle("This week · ${w.focus}")
            val doneKm = w.sessions.sumOf { completions[it.date]?.takeIf { c -> c.status == "DONE" }?.actualKm ?: 0.0 }
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${doneKm.roundToInt()} / ${w.targetKm.roundToInt()} km", fontWeight = FontWeight.Bold)
                        Text(if (w.isRecovery) "Recovery week" else w.phase.label, color = Color(w.phase.color), fontWeight = FontWeight.SemiBold)
                    }
                    Gap(8.dp)
                    ProgressBar((doneKm / w.targetKm.coerceAtLeast(1.0)).toFloat(), Color(w.phase.color), Modifier.fillMaxWidth())
                }
            }
            Gap(10.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                w.sessions.forEach { s -> MiniDayCard(s, completions[s.date]?.status, s.date == todayKey) }
            }
        }

        SectionTitle("Milestones")
        MilestonesCard(repo.milestones())

        // Ask coach ------------------------------------------------------
        SectionTitle("Ask your coach")
        AskCoach(vm)
        Gap(24.dp)
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun CountdownBanner(days: Long, race: String, weekLabel: String, qualifier: String?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(HeroGradient))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Column(Modifier.weight(1f)) {
                    Text(race.uppercase(), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                    Text("Hermanus → Stellenbosch", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(weekLabel, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    qualifier?.let { Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(days.coerceAtLeast(0).toString(), color = Color.White, style = MaterialTheme.typography.displaySmall)
                    Text("DAYS TO GO", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                }
        }
    }
}

/** Qualifier countdown for the banner, until you've qualified. */
private fun qualifierLine(p: com.cape100.coach.data.Profile): String? {
    if (p.qualified) return null
    val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(p.qualifierDate)) }.getOrNull()
    return when {
        days == null -> "🎯 Qualifier: sub-5:00 marathon by 31 May 2027"
        days >= 0 -> "🎯 Qualifier in $days days (sub-5:00)"
        else -> "🎯 Qualifier date passed – update it in Settings"
    }
}

/** The one thing to do next, moving through your morning: run → weigh in → shower → stretch → tomorrow. */
@Composable
private fun NextUpCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    // Re-check every minute so the step moves on with the clock.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); tick++ } }
    val step = remember(v, tick) { vm.repo.nextUp() }
    val todayKey = LocalDate.now().toString()
    // Keep the scale button (and its open window with the coach's feedback) while you're weighing in.
    var weighing by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NEXT UP" + step.label.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(step.title, fontWeight = FontWeight.SemiBold)
                if (step.detail.isNotBlank()) Text(step.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (weighing || step.action == com.cape100.coach.plan.NextUp.Action.WEIGH_IN) {
                WeighInButton(vm, label = "Weigh in", onOpenChange = { weighing = it })
            } else when (step.action) {
                com.cape100.coach.plan.NextUp.Action.MARK_DONE ->
                    OutlinedButton(onClick = { vm.setCompletion(todayKey, "DONE") }) { Text("Done") }
                com.cape100.coach.plan.NextUp.Action.STRETCH_DONE ->
                    OutlinedButton(onClick = { vm.setStretchDone(true) }) { Text("Done") }
                else -> {}
            }
        }
    }
}

/** "How do I do this?" buttons for each strength exercise in a session. */
@Composable
fun ExerciseHelp(s: Session) {
    val guides = com.cape100.coach.plan.ExerciseGuides.forSession(s)
    if (guides.isEmpty()) return
    var open by remember { mutableStateOf<com.cape100.coach.plan.ExerciseGuides.Guide?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Gap(6.dp)
    Text("How to do each exercise", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        guides.forEach { g -> androidx.compose.material3.AssistChip(onClick = { open = g }, label = { Text(g.name) }) }
    }
    open?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(g.name) },
            text = {
                Column {
                    g.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", fontSize = 14.sp); Gap(4.dp) }
                    Gap(6.dp)
                    Text("Avoid: ${g.mistakes}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Got it") } },
            dismissButton = {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(g.videoQuery))))
                }) { Text("Watch a video") }
            },
        )
    }
}

/** Tell the coach whether you did today's workout (or what you did instead); it can change today/tomorrow. */
@Composable
private fun CoachUpdateCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    var status by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val last = vm.repo.store.coachUpdate?.takeIf { it.date == LocalDate.now().toString() }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Tell your coach – it can change today or tomorrow for you.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(6.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Did it as planned", "Did something different", "Didn't get to it", "Not yet – still planning to").forEach { opt ->
                    FilterChip(status == opt, { status = opt }, label = { Text(opt, fontSize = 12.sp) })
                }
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. Overslept, only had 15 min – did a walk. Or: ran 6 km instead") })
            Gap(8.dp)
            Button(onClick = { vm.sendCoachUpdate(status.ifBlank { "update" }, note); note = "" }, enabled = !vm.updating && (status.isNotBlank() || note.isNotBlank())) {
                Text(if (vm.updating) "Coach is thinking…" else "Update my coach")
            }
            last?.let {
                Gap(10.dp)
                Text("💬 ${it.reply}", fontSize = 14.sp)
            }
        }
    }
}

/** Today's stretch routine (post-run in the morning on run days, evening release otherwise). */
@Composable
private fun StretchCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val plan = vm.repo.stretchPlanToday()
    LaunchedEffect(plan.date, plan.byCoach) { if (!plan.byCoach && vm.repo.aiConfigured) vm.refreshStretchPlan(false) }
    val usePost = java.time.LocalTime.now().hour < 12 && vm.repo.runPlannedToday()
    val r = if (usePost) plan.postRun else plan.evening
    FlipCard(
        Modifier.fillMaxWidth(), key = "stretch-${plan.date}-${plan.byCoach}-$usePost",
        frontBrush = Brush.linearGradient(listOf(Color(0xFF1F1F23), MaterialTheme.colorScheme.surface)),
        front = {
            Column {
                Text((if (plan.byCoach) "FROM YOUR COACH · " else "") + "${r.minutes} MIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("🧘 ${r.title}", style = MaterialTheme.typography.titleMedium)
                Gap(6.dp)
                r.moves.forEach { Text("• ${it.name} – ${it.hold}", fontSize = 13.sp) }
                Gap(4.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlipHint()
                    Spacer(Modifier.weight(1f))
                    if (vm.repo.aiConfigured) TextButton(onClick = { vm.refreshStretchPlan(true) }, enabled = !vm.stretchLoading) {
                        Text(if (vm.stretchLoading) "Writing…" else "New routine", fontSize = 12.sp)
                    }
                }
            }
        },
        back = {
            Column {
                Text("How to do each one", style = MaterialTheme.typography.titleMedium)
                Gap(4.dp)
                r.moves.forEach { m ->
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${m.hold}. ${m.how}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Gap(4.dp)
                }
                Text("Ease into each stretch, breathe slowly, no bouncing or pain.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Stepping-stone distances on the way to the race: next one up front, the full ladder on the back. */
@Composable
private fun MilestonesCard(ms: List<com.cape100.coach.data.CoachRepository.Milestone>) {
    if (ms.isEmpty()) return
    val next = ms.firstOrNull { !it.done } ?: return
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    fun kmLabel(km: Double) = if (km % 1.0 == 0.0) "${km.toInt()}" else "%.1f".format(km)
    FlipCard(
        Modifier.fillMaxWidth(), key = "milestones",
        frontBrush = Brush.linearGradient(listOf(Color(0xFF2A0B0E), MaterialTheme.colorScheme.surface)),
        front = {
            Column {
                Text("NEXT MILESTONE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${kmLabel(next.km)} km", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(next.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                next.date?.let { d ->
                    val weeks = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d) / 7
                    Text(d.format(fmt) + " · " + (if (weeks <= 0) "this week" else "in $weeks week" + if (weeks == 1L) "" else "s") +
                        (next.race?.let { " · $it" } ?: ""), fontSize = 13.sp)
                }
                Gap(10.dp)
                // Ladder: filled = done, ringed = next.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ms.forEach { m ->
                        val color = when { m.done -> MaterialTheme.colorScheme.primary; m == next -> Color.White; else -> MaterialTheme.colorScheme.outline }
                        Chip(kmLabel(m.km), color)
                    }
                }
                Gap(4.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("Road to ${ms.last().label}", style = MaterialTheme.typography.titleMedium)
                Gap(6.dp)
                ms.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text((if (m.done) "✓ " else "○ ") + "${kmLabel(m.km)} km", Modifier.width(80.dp), fontWeight = FontWeight.SemiBold,
                            color = if (m.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(m.label + (m.race?.let { " – $it" } ?: ""), Modifier.weight(1f), fontSize = 13.sp)
                        Text(m.date?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yy")) ?: "–", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

@Composable
private fun PlanCheckCard(c: LoadCheck, onUpdate: () -> Unit, onSnooze: () -> Unit) {
    val color = if (c.trainingMore) Color(0xFF22C55E) else Color(0xFFF59E0B)
    FlipCard(
        Modifier.fillMaxWidth(), key = "plancheck-${c.trainingMore}",
        frontBrush = Brush.linearGradient(listOf(color.copy(alpha = 0.28f), MaterialTheme.colorScheme.surface)),
        front = {
            Column {
                Text("PLAN CHECK", style = MaterialTheme.typography.labelSmall, color = color)
                Text(c.headline, style = MaterialTheme.typography.titleMedium)
                Gap(8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatPill(Icons.Filled.Timer, "%.1f h".format(c.actualHoursPerWeek), "you / week")
                    StatPill(Icons.Filled.Schedule, "%.1f h".format(c.plannedHoursPerWeek), "plan / week")
                    StatPill(Icons.Filled.Straighten, "${c.actualKmPerWeek.roundToInt()} km", "you / week")
                }
                Gap(6.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("Why update?", style = MaterialTheme.typography.titleMedium)
                Text(c.message, fontSize = 14.sp)
                Gap(6.dp)
                Text("The plan will rebuild from this week at ${c.actualKmPerWeek.roundToInt()} km/week with a longest run of ${c.longestRunKm.roundToInt()} km. " +
                    "Race date and qualifier stay the same.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        Button(onClick = onUpdate) { Text("Update my plan") }
        OutlinedButton(onClick = onSnooze) { Text("Not now") }
    }
}

@Composable
fun SessionFlipCard(s: Session, key: String, big: Boolean, adjusted: Boolean = false) {
    val c = s.type.color()
    FlipCard(
        Modifier.fillMaxWidth(), key = key,
        frontBrush = Brush.linearGradient(listOf(c.copy(alpha = 0.35f), c.copy(alpha = 0.08f))),
        front = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.type.icon(), null, tint = c, modifier = Modifier.size(if (big) 36.dp else 24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.type.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
                        Text(s.title, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    }
                    if (adjusted) Chip("Adjusted", Color(0xFFF59E0B))
                }
                if (s.distanceKm > 0 || s.durationMin > 0) {
                    Gap(12.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        if (s.distanceKm > 0) StatPill(Icons.Filled.Straighten, "${s.distanceKm.fmtKm()} km", "distance")
                        if (s.durationMin > 0) StatPill(Icons.Filled.Timer, "~" + fmtDuration(s.durationMin), "time")
                        if (s.vertM > 0) StatPill(Icons.Filled.Terrain, "${s.vertM} m", "climb")
                    }
                }
                if (s.intensity.isNotBlank()) { Gap(8.dp); Text(s.intensity, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Gap(6.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("How to do it", style = MaterialTheme.typography.titleMedium)
                Gap(4.dp)
                Text(s.details, fontSize = 14.sp)
                if (s.purpose.isNotBlank()) {
                    Gap(8.dp)
                    HorizontalDivider()
                    Gap(8.dp)
                    Text("Why: ${s.purpose}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

fun Double.fmtKm(): String = if (this % 1.0 == 0.0) this.roundToInt().toString() else "%.1f".format(this)

@Composable
private fun MiniDayCard(s: Session, status: String?, isToday: Boolean) {
    val c = s.type.color()
    val day = LocalDate.parse(s.date)
    FlipCard(
        Modifier.width(92.dp).height(112.dp), key = "mini-${s.date}",
        shape = RoundedCornerShape(18.dp),
        frontBrush = Brush.verticalGradient(listOf(c.copy(alpha = if (isToday) 0.55f else 0.28f), c.copy(alpha = 0.08f))),
        front = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                    style = MaterialTheme.typography.labelSmall, fontWeight = if (isToday) FontWeight.Black else FontWeight.SemiBold)
                Gap(4.dp)
                Icon(s.type.icon(), null, tint = c, modifier = Modifier.size(28.dp))
                Gap(4.dp)
                Text(when (status) { "DONE" -> "✓"; "SKIPPED" -> "–"; else -> if (s.distanceKm > 0) "${s.distanceKm.fmtKm()}k" else s.type.label },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            }
        },
        back = {
            Text(s.title, fontSize = 11.sp, maxLines = 5, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
    )
}

@Composable
private fun AiCoachCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val advice = vm.todayAdvice()
    if (advice == null) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Let Claude read your Garmin data, plan and check-in and tell you exactly what to do today.",
                        Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Gap(10.dp)
                Button(onClick = vm::runAi, enabled = !vm.aiLoading) {
                    if (vm.aiLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Get today's coaching")
                }
            }
        }
        return
    }
    val vColor = runCatching { Color(Verdict.valueOf(advice.verdict).color) }.getOrDefault(MaterialTheme.colorScheme.primary)
    FlipCard(
        Modifier.fillMaxWidth(), key = "ai-${advice.date}-${advice.headline.hashCode()}",
        frontBrush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)),
        front = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("COACH SAYS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    if (advice.verdict.isNotBlank()) Chip(advice.verdict, vColor)
                }
                Gap(8.dp)
                Text(advice.headline, style = MaterialTheme.typography.titleLarge)
                if (advice.sessionTitle.isNotBlank()) {
                    Gap(8.dp)
                    Text(advice.sessionTitle + listOfNotNull(advice.distanceKm?.let { "${it.fmtKm()} km" }, advice.durationMin?.let { fmtDuration(it) })
                        .joinToString(" · ", prefix = " — ").takeIf { advice.distanceKm != null || advice.durationMin != null }.orEmpty(),
                        fontWeight = FontWeight.SemiBold)
                    Text(advice.sessionDetails, fontSize = 14.sp)
                }
                if (advice.focus.isNotBlank()) { Gap(8.dp); Text("🧠 ${advice.focus}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlipHint()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::runAi, enabled = !vm.aiLoading) { Text(if (vm.aiLoading) "Thinking…" else "Refresh") }
                }
            }
        },
        back = {
            Column {
                Text("Reasoning", style = MaterialTheme.typography.titleMedium)
                Text(advice.reasoning, fontSize = 14.sp)
                if (advice.fueling.isNotBlank()) {
                    Gap(10.dp)
                    Text("Fuelling", style = MaterialTheme.typography.titleMedium)
                    Text(advice.fueling, fontSize = 14.sp)
                }
            }
        },
    )
}

@Composable
private fun CheckInCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val existing = vm.repo.todayCheckIn()
    var feel by remember(existing) { mutableIntStateOf(existing?.feel ?: 3) }
    var sore by remember(existing) { mutableIntStateOf(existing?.soreness ?: 2) }
    var sick by remember(existing) { mutableStateOf(existing?.sick ?: false) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var editing by remember(existing) { mutableStateOf(existing == null) }
    val faces = listOf("😫", "😕", "😐", "🙂", "🤩")
    val soreLabels = listOf("None", "Light", "Some", "Sore", "Very")
    // Once you've checked in, it shrinks to one line.
    if (!editing && existing != null) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Check-in: ${faces.getOrElse(existing.feel - 1) { "😐" }} · legs ${soreLabels.getOrElse(existing.soreness - 1) { "?" }.lowercase()}" +
                    if (existing.sick) " · sick/injured" else "", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
        }
        return
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("How do you feel this morning?", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                faces.forEachIndexed { i, e ->
                    FilterChip(selected = feel == i + 1, onClick = { feel = i + 1 }, label = { Text(e, fontSize = 20.sp) })
                }
            }
            Gap(6.dp)
            Text("Leg soreness", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                soreLabels.forEachIndexed { i, e ->
                    FilterChip(selected = sore == i + 1, onClick = { sore = i + 1 }, label = { Text(e, fontSize = 12.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sick or injured", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = sick, onCheckedChange = { sick = it })
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("Anything else? (optional)") }, singleLine = true)
            Gap(8.dp)
            Button(
                onClick = { vm.saveCheckIn(CheckIn(LocalDate.now().toString(), feel, sore, sick, note)) },
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary),
            ) { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (existing == null) "Save check-in" else "Update check-in") }
        }
    }
}

@Composable
private fun AskCoach(vm: AppViewModel) {
    var q by rememberSaveable { mutableStateOf("") }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. My calf is tight, should I still do the long run?") },
                trailingIcon = {
                    IconButton(onClick = { if (q.isNotBlank()) vm.ask(q) }, enabled = !vm.aiLoading) { Icon(Icons.AutoMirrored.Filled.Send, "Ask") }
                },
            )
            vm.askAnswer?.let { Gap(10.dp); Text(it, fontSize = 14.sp) }
        }
    }
}
