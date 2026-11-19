package com.cape100.coach.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.EnteredRace
import com.cape100.coach.data.Race
import com.cape100.coach.plan.PlanGenerator
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.theme.Red
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** "10km", "21.1 km", "Half Marathon", "5K" → km; null if it can't tell. */
fun parseRaceKm(s: String): Double? {
    val t = s.lowercase()
    Regex("""(\d+(?:[.,]\d+)?)\s*(km|k)\b""").find(t)?.let { return it.groupValues[1].replace(',', '.').toDoubleOrNull() }
    return when {
        "half" in t -> 21.1
        "marathon" in t -> 42.2
        else -> null
    }
}

/** Your races at the top of the Races tab: countdown, the coach's race plan, and "add a race myself". */
@Composable
fun YourRacesSection(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val races = vm.repo.enteredRaces().filter { !LocalDate.parse(it.date).isBefore(LocalDate.now().minusDays(1)) }
    var adding by remember { mutableStateOf(false) }
    Text("YOUR RACES", style = MaterialTheme.typography.labelSmall, color = Red, modifier = Modifier.padding(top = 4.dp))
    if (races.isEmpty()) Text("Entered a race? Tap “I’m entered” on it below, or add it yourself. It goes into your plan and the coach " +
        "gets you ready for it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    races.forEach { r -> Gap(6.dp); EnteredRaceCard(vm, r) }
    TextButton(onClick = { adding = true }) { Text("+ Add a race myself") }
    if (adding) EnterRaceDialog(vm, null) { adding = false }
}

@Composable
private fun EnteredRaceCard(vm: AppViewModel, r: EnteredRace) {
    val date = LocalDate.parse(r.date)
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    var showPlan by rememberSaveable(r.id) { mutableStateOf(days <= 7) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Red.copy(alpha = 0.16f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Flag, null, tint = Red)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " · ${PlanGenerator.k(r.distanceKm)} km" +
                        (if (r.goal.isNotBlank()) " · goal ${r.goal}" else ""), fontSize = 13.sp)
                }
                Text(when { days == 0L -> "TODAY"; days == 1L -> "Tomorrow"; days < 0 -> "Done"; else -> "in $days days" },
                    fontWeight = FontWeight.Bold, color = Red, fontSize = 13.sp)
            }
            Gap(6.dp)
            when {
                vm.racePlanLoading == r.id -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Coach is writing your race plan…", fontSize = 13.sp)
                }
                r.racePlan.isNotBlank() -> {
                    TextButton(onClick = { showPlan = !showPlan }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(if (showPlan) "Hide race plan" else "Show race plan from your coach")
                    }
                    if (showPlan) Text(r.racePlan.replace("**", "").replace("## ", "").replace("# ", ""), fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.racePlan(r.id) }, enabled = vm.racePlanLoading == null) {
                    Text(if (r.racePlan.isBlank()) "Get my race plan" else "New race plan")
                }
                TextButton(onClick = { vm.removeEnteredRace(r.id) }) { Text("Remove") }
            }
        }
    }
}

/** Enter a race from the list (prefilled) or add your own. */
@Composable
fun EnterRaceDialog(vm: AppViewModel, race: Race?, onClose: () -> Unit) {
    val options = race?.distances.orEmpty().mapNotNull { parseRaceKm(it) }.distinct().sorted()
    var name by rememberSaveable { mutableStateOf(race?.name ?: "") }
    var date by rememberSaveable { mutableStateOf(race?.date ?: "") }
    var km by rememberSaveable { mutableStateOf(options.singleOrNull()?.let { PlanGenerator.k(it) } ?: "") }
    var goal by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (race == null) "Add a race" else "I’m entered") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (race == null) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Race name") }, singleLine = true)
                    OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                } else Text("${race.name} · ${race.date}", fontWeight = FontWeight.SemiBold)
                if (options.size > 1) {
                    Text("Which distance?", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { o -> FilterChip(km == PlanGenerator.k(o), { km = PlanGenerator.k(o) }, { Text("${PlanGenerator.k(o)} km") }) }
                    }
                }
                OutlinedTextField(km, { km = it }, Modifier.fillMaxWidth(), label = { Text("Distance (km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("Goal (optional, e.g. under 1:15 or just enjoy it)") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Text("The plan eases off the day before, makes room after, and your coach writes you a race plan.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                val dist = km.replace(',', '.').toDoubleOrNull()
                when {
                    name.isBlank() -> error = "Add the race name"
                    d == null -> error = "Date must look like 2026-11-07"
                    dist == null || dist !in 1.0..250.0 -> error = "Add the distance in km"
                    else -> {
                        vm.enterRace(EnteredRace(
                            id = (race?.slug ?: name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")) + "-" + d,
                            name = name.trim(), date = d.toString(), distanceKm = dist,
                            location = race?.location.orEmpty(), url = race?.url.orEmpty(), goal = goal.trim(),
                        ))
                        onClose()
                    }
                }
            }) { Text("Add to my plan") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
