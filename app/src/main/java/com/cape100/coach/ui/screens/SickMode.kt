package com.cape100.coach.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val SickColor = Color(0xFFF59E0B)

/** While sick: how long, the coach's last word, "how do you feel today?", and "I'm better". */
@Composable
fun SickCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val ill = vm.repo.store.illness ?: return
    val today = LocalDate.now()
    val start = LocalDate.parse(ill.start)
    val day = ChronoUnit.DAYS.between(start, today) + 1
    val todayCheck = ill.checks.lastOrNull { it.date == today.toString() }
    var feeling by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(SickColor.copy(alpha = 0.16f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("🤒 RECOVERING · DAY $day", style = MaterialTheme.typography.labelSmall, color = SickColor)
            Text("Sick since ${start.format(DateTimeFormatter.ofPattern("EEE d MMM"))}", fontWeight = FontWeight.SemiBold)
            if (ill.note.isNotBlank()) Text(ill.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Training is paused and nothing goes to your watch until you're better.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            todayCheck?.let {
                Gap(8.dp)
                Text("💬 ${it.reply}", fontSize = 14.sp)
                if (it.canTrain) Text("Your coach put a gentle session in Today's session below.", fontSize = 12.sp, color = SickColor)
            }
            Gap(10.dp)
            Text(if (todayCheck == null) "How do you feel today?" else "Feeling different? Check in again", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Worse", "About the same", "A bit better", "Almost normal").forEach { f ->
                    FilterChip(feeling == f, { feeling = f }, label = { Text(f, fontSize = 12.sp) })
                }
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. No fever since yesterday, still a bit of a cough, on day 3 of antibiotics") })
            Gap(8.dp)
            Button(onClick = { vm.illnessCheck(feeling, note); note = "" }, enabled = !vm.illnessBusy && feeling.isNotBlank()) {
                Text(if (vm.illnessBusy) "Coach is thinking…" else "Can I train today?")
            }
            OutlinedButton(onClick = vm::endIllness) { Text("I'm better – back to training") }
            Text("The first few days back are eased in automatically.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Not feeling well?" – starts sick mode from a chosen day. */
@Composable
fun NotWellButton(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("🤒 Sick or injured? Pause training") }
    if (!open) return
    val today = LocalDate.now()
    var daysAgo by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Pause training") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("When did it start?", fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..4).forEach { n ->
                        val label = when (n) { 0 -> "Today"; 1 -> "Yesterday"; else -> today.minusDays(n.toLong()).format(DateTimeFormatter.ofPattern("EEE d")) }
                        FilterChip(daysAgo == n, { daysAgo = n }, label = { Text(label) })
                    }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("What's going on?") },
                    placeholder = { Text("e.g. Flu – got an injection and meds from the doctor") })
                Text("Your plan pauses, workouts come off your watch, and each day you can ask your coach if you're ready to try.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.startIllness(today.minusDays(daysAgo.toLong()), note); open = false }) { Text("Pause training") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
