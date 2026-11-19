package com.cape100.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.Profile
import com.cape100.coach.data.Settings
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.components.SectionTitle
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Garmin Connect")
        GarminCard(vm)

        SectionTitle("AI coach (Claude)")
        AiCard(vm)

        SectionTitle("Reminders")
        BackgroundCard(vm)
        RemindersCard(vm)

        SectionTitle("Race & athlete profile")
        SettingsCard {
            ProfileForm(vm.profile, buttonText = "Save & update plan") { vm.saveProfile(it); vm.message = "Plan updated" }
            Gap(4.dp)
            Text("To change where the plan starts, use “Change the whole plan” on the Plan tab.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Ideas for the app")
        IdeasCard(vm)
        Gap(24.dp)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun GarminCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    SettingsCard {
        if (vm.repo.garminConnected) {
            val t = vm.repo.store.garminTokens
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF22C55E))
                Spacer(Modifier.width(8.dp))
                Text("Connected" + (t?.fullName?.let { " as $it" } ?: ""), fontWeight = FontWeight.SemiBold)
            }
            vm.repo.store.snapshot?.let {
                Text("Last sync: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.fetchedAt))}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::sync, enabled = !vm.syncing) { Text(if (vm.syncing) "Syncing…" else "Sync now") }
                OutlinedButton(onClick = vm::garminLogout) { Text("Disconnect") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send scale weigh-ins to Garmin Connect", Modifier.weight(1f), fontSize = 14.sp)
                Switch(vm.settings.uploadWeighInsToGarmin, { vm.saveSettings(vm.settings.copy(uploadWeighInsToGarmin = it)) })
            }
            Text(
                vm.repo.store.backfillState.let { st ->
                    if (st == "done") "Health record: ${vm.repo.store.healthHistory.size} days from Garmin + scale, updated with every sync."
                    else st.ifBlank { "Health history from Garmin: not built yet." }
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = vm::rebuildHistory) { Text("Rebuild health history from Garmin") }
        } else {
            var email by rememberSaveable { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            Text("Sign in with your Garmin Connect account. Your password goes straight to Garmin and is never stored – " +
                "the app keeps only an encrypted login token on this phone.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(8.dp)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Garmin email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Gap(8.dp)
            Button(onClick = { vm.garminLogin(email.trim(), password) }, enabled = !vm.syncing && email.isNotBlank() && password.isNotBlank()) {
                if (vm.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Connect Garmin") }
            }
        }
    }
    if (vm.needsMfa) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Garmin verification code") },
            text = {
                Column {
                    Text("Garmin sent a code to your email/phone.")
                    OutlinedTextField(code, { code = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = { TextButton(onClick = { vm.submitMfa(code) }) { Text("Verify") } },
        )
    }
}

@Composable
private fun AiCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val s = vm.settings
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(s.aiModel) }
    SettingsCard {
        Text(if (vm.repo.aiConfigured) "API key saved ✓" else "Add your Claude API key from console.anthropic.com. Each daily review costs roughly R0.50.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (vm.repo.aiConfigured) "Replace API key" else "Claude API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include AI advice in morning notification", Modifier.weight(1f), fontSize = 14.sp)
            Switch(s.aiInMorning, { vm.saveSettings(s.copy(aiInMorning = it)) })
        }
        Button(onClick = {
            if (key.isNotBlank()) { vm.saveApiKey(key); key = "" }
            vm.saveSettings(vm.settings.copy(aiModel = model.trim().ifBlank { "claude-opus-5-5" }))
            vm.message = "AI settings saved"
        }) { Text("Save") }
    }
}

/**
 * Phones like OPPO and Samsung hold back apps in the background, which delays the hourly Garmin sync and reminders.
 * Shown until the app is allowed to run in the background.
 */
@Composable
private fun BackgroundCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // re-checks when you come back from the system screen
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Let the app run in the background", fontWeight = FontWeight.SemiBold)
            Text("Your phone is holding the app back, so Garmin syncs and reminders can arrive late or all at once. " +
                "Allow it once and they'll run on time.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(8.dp)
            Button(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}")))
            }) { Text("Allow") }
        }
    }
    Gap(10.dp)
}

@Composable
private fun RemindersCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val s = vm.settings
    var morning by rememberSaveable { mutableStateOf("%02d:%02d".format(s.morningHour, s.morningMinute)) }
    var evening by rememberSaveable { mutableStateOf("%02d:%02d".format(s.eveningHour, s.eveningMinute)) }
    var weighIn by rememberSaveable { mutableStateOf("%02d:%02d".format(s.weighInHour, s.weighInMinute)) }
    var weighInWeekend by rememberSaveable { mutableStateOf("%02d:%02d".format(s.weighInWeekendHour, s.weighInWeekendMinute)) }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Morning briefing", fontWeight = FontWeight.SemiBold)
                Text("Syncs Garmin, checks readiness, tells you today's session", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.morningEnabled, { vm.saveSettings(s.copy(morningEnabled = it)) })
        }
        OutlinedTextField(morning, { morning = it }, label = { Text("Time (HH:MM)") }, singleLine = true)
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Evening nudge", fontWeight = FontWeight.SemiBold)
                Text("Reminds you if today's session isn't done yet; Sundays preview next week", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.eveningEnabled, { vm.saveSettings(s.copy(eveningEnabled = it)) })
        }
        OutlinedTextField(evening, { evening = it }, label = { Text("Time (HH:MM)") }, singleLine = true)
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weigh-in reminder", fontWeight = FontWeight.SemiBold)
                Text("After your run, before your shower – skipped if you've already weighed in", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.weighInReminder, { vm.saveSettings(s.copy(weighInReminder = it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(weighIn, { weighIn = it }, Modifier.weight(1f), label = { Text("Weekdays") }, singleLine = true)
            OutlinedTextField(weighInWeekend, { weighInWeekend = it }, Modifier.weight(1f), label = { Text("Weekends") }, singleLine = true)
        }
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stretch reminders", fontWeight = FontWeight.SemiBold)
                Text("After your run and in the evening – skipped once you've ticked it off", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.stretchReminders, { vm.saveSettings(s.copy(stretchReminders = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Move nudges", fontWeight = FontWeight.SemiBold)
                Text("When Garmin shows you've been still for a while (needs Garmin)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.movementNudges, { vm.saveSettings(s.copy(movementNudges = it)) })
        }
        Gap(8.dp)
        Button(onClick = {
            val m = parseTime(morning); val e = parseTime(evening); val w = parseTime(weighIn); val ww = parseTime(weighInWeekend)
            if (m == null || e == null || w == null || ww == null) vm.message = "Use 24h time like 06:00"
            else {
                vm.saveSettings(vm.settings.copy(morningHour = m.first, morningMinute = m.second, eveningHour = e.first, eveningMinute = e.second, weighInHour = w.first, weighInMinute = w.second, weighInWeekendHour = ww.first, weighInWeekendMinute = ww.second))
                vm.message = "Reminders updated"
            }
        }) { Text("Save times") }
    }
}

private fun parseTime(s: String): Pair<Int, Int>? {
    val parts = s.trim().split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return if (h in 0..23 && m in 0..59) h to m else null
}

/** Shared by onboarding and settings. */
@Composable
fun ProfileForm(initial: Profile, buttonText: String, onSave: (Profile) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var raceName by rememberSaveable { mutableStateOf(initial.raceName) }
    var raceDate by rememberSaveable { mutableStateOf(initial.raceDate) }
    var distance by rememberSaveable { mutableStateOf(initial.raceDistanceKm.toString()) }
    var elevation by rememberSaveable { mutableStateOf(initial.raceElevationM.toString()) }
    var goal by rememberSaveable { mutableStateOf(initial.goalFinishHours.toString()) }
    var qualiDate by rememberSaveable { mutableStateOf(initial.qualifierDate) }
    var qualified by rememberSaveable { mutableStateOf(initial.qualified) }
    var maxHr by rememberSaveable { mutableStateOf(if (initial.maxHr > 0) initial.maxHr.toString() else "") }
    var easyHr by rememberSaveable { mutableStateOf(if (initial.easyHrMax > 0) initial.easyHrMax.toString() else "") }
    var height by rememberSaveable { mutableStateOf(if (initial.heightCm > 0) initial.heightCm.toString() else "") }
    var birthYear by rememberSaveable { mutableStateOf(if (initial.birthYear > 0) initial.birthYear.toString() else "") }
    var sex by rememberSaveable { mutableStateOf(initial.sex) }
    var days by rememberSaveable { mutableStateOf(initial.runDaysPerWeek) }
    var wake by rememberSaveable { mutableStateOf(initial.wakeTime) }
    var runAt by rememberSaveable { mutableStateOf(initial.runTime) }
    var routineNote by rememberSaveable { mutableStateOf(initial.routineNote) }
    var maxMin by rememberSaveable { mutableStateOf(if (initial.weekdayMaxMin > 0) initial.weekdayMaxMin.toString() else "") }
    var dumbbell by rememberSaveable { mutableStateOf(if (initial.dumbbellKg > 0) initial.dumbbellKg.toString() else "") }
    var roller by rememberSaveable { mutableStateOf(initial.foamRoller) }
    var error by remember { mutableStateOf<String?>(null) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your first name") }, singleLine = true)
        OutlinedTextField(raceName, { raceName = it }, Modifier.fillMaxWidth(), label = { Text("Race") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(raceDate, { raceDate = it }, Modifier.weight(1f), label = { Text("Race date (YYYY-MM-DD)") }, singleLine = true)
            OutlinedTextField(goal, { goal = it }, Modifier.weight(0.6f), label = { Text("Goal hours") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(distance, { distance = it }, Modifier.weight(1f), label = { Text("Distance km") }, singleLine = true, keyboardOptions = num)
            OutlinedTextField(elevation, { elevation = it }, Modifier.weight(1f), label = { Text("Climb m") }, singleLine = true, keyboardOptions = num)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Already have my sub-5:00 qualifier", Modifier.weight(1f), fontSize = 14.sp)
            Switch(qualified, { qualified = it })
        }
        if (!qualified) OutlinedTextField(qualiDate, { qualiDate = it }, Modifier.fillMaxWidth(),
            label = { Text("Qualifying marathon date (by 2027-05-31)") }, singleLine = true)
        Text(
            "Your weekly km comes from the plan. " + when {
                initial.baselineSource == "ai" ->
                    "Your AI coach started it at ${initial.currentWeeklyKm} km/week (longest run ${initial.longestRecentRunKm} km) from your Garmin history: \"${initial.baselineNote}\""
                initial.baselineFromGarmin ->
                    "It started from your Garmin history (${initial.currentWeeklyKm} km/week, longest run ${initial.longestRecentRunKm} km). Add a Claude API key and your AI coach will choose the starting point."
                else -> "Connect Garmin and your AI coach reads your history to choose where the plan starts."
            },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(maxHr, { maxHr = it }, Modifier.weight(1f), label = { Text(if (initial.maxHrFromGarmin) "Max HR (from Garmin)" else "Max HR (optional)") }, singleLine = true, keyboardOptions = num)
            OutlinedTextField(easyHr, { easyHr = it }, Modifier.weight(1f), label = { Text("Easy HR up to") }, singleLine = true, keyboardOptions = num)
        }
        OutlinedTextField(height, { height = it }, Modifier.fillMaxWidth(), label = { Text("Height cm") }, singleLine = true, keyboardOptions = num)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(birthYear, { birthYear = it }, Modifier.weight(1f), label = { Text("Birth year") }, singleLine = true, keyboardOptions = num)
            FilterChip(sex == "M", { sex = "M" }, label = { Text("Male") })
            FilterChip(sex == "F", { sex = "F" }, label = { Text("Female") })
        }
        Text("Height, birth year and sex let the app work out body fat, muscle and water from your scale.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Daily routine", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(wake, { wake = it }, Modifier.weight(1f), label = { Text("Wake (HH:MM)") }, singleLine = true)
            OutlinedTextField(runAt, { runAt = it }, Modifier.weight(1f), label = { Text("Run at (HH:MM)") }, singleLine = true)
        }
        OutlinedTextField(routineNote, { routineNote = it }, Modifier.fillMaxWidth(), label = { Text("Before the run (e.g. Tahajjud)") }, singleLine = true)
        OutlinedTextField(maxMin, { maxMin = it }, Modifier.fillMaxWidth(), label = { Text("Weekday run limit (minutes, blank = none)") }, singleLine = true, keyboardOptions = num)
        Text("Home strength kit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(dumbbell, { dumbbell = it }, Modifier.weight(1f), label = { Text("Dumbbells (kg each)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FilterChip(roller, { roller = !roller }, label = { Text("Foam roller") })
        }
        Text("Running days per week", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(4, 5, 6).forEach { d -> FilterChip(days == d, { days = d }, label = { Text("$d days") }) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(onClick = {
            val rd = runCatching { LocalDate.parse(raceDate.trim()) }.getOrNull()
            val qd = runCatching { LocalDate.parse(qualiDate.trim()) }.getOrNull()
            when {
                rd == null -> error = "Race date must look like 2027-09-05"
                !qualified && qd == null -> error = "Qualifier date must look like 2027-03-21"
                else -> {
                    error = null
                    onSave(initial.copy(
                        name = name.trim(), raceName = raceName.trim().ifBlank { "CAPE 100" }, raceDate = rd.toString(),
                        raceDistanceKm = distance.toIntOrNull() ?: 100, raceElevationM = elevation.toIntOrNull() ?: 900,
                        goalFinishHours = goal.toDoubleOrNull()?.coerceIn(6.0, 15.0) ?: 12.0,
                        qualifierDate = qd?.toString() ?: initial.qualifierDate, qualified = qualified,
                        maxHr = maxHr.toIntOrNull() ?: 0, easyHrMax = easyHr.toIntOrNull()?.takeIf { it in 100..200 } ?: 0, heightCm = height.toIntOrNull() ?: 0,
                        birthYear = birthYear.toIntOrNull()?.takeIf { it in 1920..2010 } ?: 0, sex = sex, runDaysPerWeek = days,
                        wakeTime = wake.trim(), runTime = runAt.trim(), routineNote = routineNote.trim(),
                        weekdayMaxMin = maxMin.toIntOrNull()?.coerceIn(10, 240) ?: 0,
                        dumbbellKg = dumbbell.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 50.0) ?: 0.0, foamRoller = roller, onboarded = true,
                    ))
                }
            }
        }) { Text(buttonText) }
    }
}

/** Jot down ideas for improving the app while you're out; they're saved on the phone for the next update. */
@Composable
private fun IdeasCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val ideas = vm.repo.store.ideas
    SettingsCard {
        Text("Thought of something to improve? Write it here – it's saved on your phone and picked up at the next update.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. Show my weekly km on the Today screen") })
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.addIdea(text); text = "" }, enabled = text.isNotBlank()) { Text("Save idea") }
            if (ideas.isNotEmpty()) OutlinedButton(onClick = {
                val body = ideas.joinToString("\n") { "• ${it.text} (${it.time.take(10)})" }
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "Cape 100 Coach ideas")
                    .putExtra(android.content.Intent.EXTRA_TEXT, body), "Share ideas"))
            }) { Text("Share all") }
        }
        ideas.forEach { idea ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(idea.text, fontSize = 14.sp)
                    Text(idea.time.take(16).replace('T', ' '), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.removeIdea(idea.time) }) { Text("Remove") }
            }
        }
    }
}
