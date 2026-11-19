package com.cape100.coach.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.Phase
import com.cape100.coach.data.PlanWeek
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Chip
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.components.ProgressBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun PlanScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val plan = vm.repo.plan()
    val today = LocalDate.now()
    val currentIdx = plan.indexOfFirst { !today.isBefore(it.start) && today.isBefore(it.start.plusDays(7)) }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    // Jump to the current week once you're past week 1 (keeps the header and coach box visible early on).
    LaunchedEffect(plan.size) { if (plan.isNotEmpty() && currentIdx > 0) listState.scrollToItem(currentIdx + 1) }
    val completions = vm.repo.store.completions
    val offset = vm.repo.weekOffset()
    val todayAdjusted = vm.repo.todayDecision().takeIf { it.modified }?.session?.title
    var feedback by rememberSaveable { mutableStateOf("") }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Your road to ${vm.profile.raceName}", style = MaterialTheme.typography.headlineMedium)
            Text("${plan.size} weeks · ${plan.sumOf { it.targetKm }.toInt()} km planned", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(10.dp)
            PhaseTimeline(plan, currentIdx)
            Gap(10.dp)
            // Tell the coach what feels wrong; it re-decides where the plan starts.
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Change the whole plan", style = MaterialTheme.typography.titleMedium)
                    Text("For big changes, like longer long runs or a new target. Your coach only changes the plan if you ask it to, " +
                        "and your week number stays the same. For today or tomorrow, use “How did today go?” on Today.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Gap(6.dp)
                    OutlinedTextField(feedback, { feedback = it }, Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. My long runs feel too short – I've done 14 km recently") })
                    Gap(6.dp)
                    Button(onClick = { vm.replanWithFeedback(feedback); feedback = "" }, enabled = !vm.replanning && feedback.isNotBlank()) {
                        Text(if (vm.replanning) "Coach is thinking…" else "Send to coach")
                    }
                    vm.planReply?.let { Gap(8.dp); Text("💬 $it", fontSize = 14.sp) }
                }
            }
            Gap(4.dp)
        }
        items(plan, key = { it.index }) { w ->
            WeekCard(w, w.index == currentIdx, w.index < currentIdx, completions.keys, todayAdjusted, offset)
        }
    }
}

@Composable
private fun PhaseTimeline(plan: List<PlanWeek>, current: Int) {
    Column {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50))) {
            plan.forEach { w ->
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .background(Color(w.phase.color).copy(alpha = if (w.index <= current) 1f else 0.45f)),
                )
            }
        }
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Phase.entries.filter { p -> plan.any { it.phase == p } }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(p.color)))
                    Spacer(Modifier.width(3.dp))
                    Text(p.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WeekCard(w: PlanWeek, isCurrent: Boolean, isPast: Boolean, doneDates: Set<String>, todayAdjusted: String? = null, offset: Int = 0) {
    var expanded by rememberSaveable(w.index) { mutableStateOf(isCurrent) }
    val phaseColor = Color(w.phase.color)
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    Card(
        Modifier.fillMaxWidth().then(if (isCurrent) Modifier.border(2.dp, phaseColor, RoundedCornerShape(20.dp)) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = if (isPast) 0.6f else 1f)),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(phaseColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Text("${w.index + 1 + offset}", fontWeight = FontWeight.Black, color = phaseColor)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${w.start.format(fmt)} – ${w.start.plusDays(6).format(fmt)}", fontWeight = FontWeight.Bold)
                        if (isCurrent) { Spacer(Modifier.width(6.dp)); Chip("NOW", phaseColor) }
                    }
                    Text(w.phase.label + if (w.isRecovery) " · recovery" else "", color = phaseColor, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${w.targetKm.toInt()} km", fontWeight = FontWeight.Bold)
                    Text("long ${w.sessions.maxOf { it.distanceKm }.fmtKm()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            val done = w.sessions.count { it.date in doneDates }
            val total = w.sessions.count { it.distanceKm > 0 }
            if (isPast || isCurrent) {
                Gap(8.dp)
                ProgressBar(if (total == 0) 0f else done.toFloat() / total, phaseColor, Modifier.fillMaxWidth())
            }
            AnimatedVisibility(expanded) {
                Column {
                    Gap(10.dp)
                    Text(w.focus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Gap(10.dp)
                    w.sessions.forEach { s ->
                        val d = LocalDate.parse(s.date)
                        Text(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (s.date in doneDates) "  ✓" else "",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        SessionFlipCard(s, "plan-${s.date}", big = false)
                        ExerciseHelp(s)
                        if (s.date == LocalDate.now().toString()) todayAdjusted?.let {
                            Text("Today adjusted to: $it (see Today)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
