package com.cape100.coach.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.cape100.coach.data.DayHealth
import com.cape100.coach.health.HealthConnectReader
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.FlipCard
import com.cape100.coach.ui.components.FlipHint
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.components.SectionTitle
import com.cape100.coach.ui.components.StatPill
import com.cape100.coach.ui.theme.Red
import com.cape100.coach.ui.theme.RedDeep
import java.time.LocalDate

private fun Double?.f1() = this?.let { "%.1f".format(it) } ?: "–"
private fun Double?.f0() = this?.let { "%.0f".format(it) } ?: "–"

/** Scale (OKOK via Health Connect) + overall daily health record. Shown at the top of the Health tab. */
@Composable
fun HealthSection(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val context = LocalContext.current
    val repo = vm.repo
    val granted by produceState<Boolean?>(null, vm.version) { value = runCatching { repo.health.hasPermissions() }.getOrDefault(false) }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        if (got.isNotEmpty()) vm.syncBody() else vm.message = "Health Connect access not granted"
    }
    LaunchedEffect(granted) { if (granted == true && repo.store.bodyReadings.isEmpty()) vm.syncBody() }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Body · scale", Modifier.weight(1f))
            if (granted == true) {
                if (vm.bodyLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::syncBody) { Icon(Icons.Filled.Sync, "Sync scale") }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (repo.canComputeBody()) "Step on your OKOK scale with the app open – weight and body composition save automatically."
                    else "Step on your OKOK scale with the app open. Add height, birth year and sex in Settings for body fat & muscle.",
                    Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                WeighInButton(vm)
            }
        }
        Gap(10.dp)
        if (repo.store.bodyReadings.isNotEmpty()) BodyCard(vm)
        if (repo.health.available && granted == false) {
            Gap(10.dp)
            InfoCard("Also import weigh-ins saved in Health Connect (e.g. from Garmin or Google Fit).", "Connect Health Connect") {
                permissionLauncher.launch(HealthConnectReader.PERMISSIONS)
            }
        }

        SectionTitle("Health snapshot")
        SnapshotCard(vm)
        HistoryBuildStatus(vm)
        TrendsCard(repo.store.healthHistory)
        HistoryCard(repo.store.healthHistory)
    }
}

/** One quiet line while the Garmin history is still being built; nothing once it's done. */
@Composable
private fun HistoryBuildStatus(vm: AppViewModel) {
    val state = vm.repo.store.backfillState
    if (!vm.repo.garminConnected || state == "done") return
    LaunchedEffect(state) { kotlinx.coroutines.delay(3000); vm.refresh() }
    Gap(6.dp)
    Text(state.ifBlank { "Building your health history from Garmin…" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoCard(text: String, button: String, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text, fontSize = 14.sp)
            Gap(8.dp)
            OutlinedButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun ConnectScaleCard(onConnect: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MonitorWeight, null, tint = Red, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Connect your OKOK scale", style = MaterialTheme.typography.titleMedium)
            }
            Gap(8.dp)
            Text(
                "OKOK doesn't share data directly, so it goes OKOK → Google Fit → Health Connect → here:\n" +
                    "1. OKOK app: Me → Third-party (or Settings) → Google Fit → connect.\n" +
                    "2. Google Fit: Profile → Settings → Sync Fit with Health Connect → on.\n" +
                    "3. Tap below and allow Cape 100 Coach to read weight and body composition.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(10.dp)
            Button(onClick = onConnect) { Text("Connect Health Connect") }
        }
    }
}

@Composable
private fun BodyCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val repo = vm.repo
    val latest = repo.store.bodyReadings.first()
    val comp = repo.store.bodyReadings.firstOrNull { it.bodyFatPct != null }
    // Newest weight, plus body composition from the latest weigh-in that measured it.
    val b = if (latest.bodyFatPct == null && comp != null) comp.copy(time = latest.time, date = latest.date, weightKg = latest.weightKg, source = latest.source) else latest
    val month = repo.bodyOn(LocalDate.parse(b.date).minusDays(30))
    val delta = if (b.weightKg != null && month?.weightKg != null && month.time != b.time) b.weightKg - month.weightKg else null
    val bmi = repo.bmi(b.weightKg)
    FlipCard(
        Modifier.fillMaxWidth(), key = "body-${b.time}",
        frontBrush = Brush.linearGradient(listOf(RedDeep.copy(alpha = 0.7f), Color(0xFF0F0F11))),
        backColor = Color(0xFF1B1B1F),
        front = {
            Column {
                Text("LAST WEIGH-IN · ${b.date} · ${b.source}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB3BA))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(b.weightKg.f1(), style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Text(" kg", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    delta?.let {
                        Text("%+.1f kg".format(it) + "\nvs 30 days", fontSize = 13.sp, color = if (it <= 0) Color(0xFF86EFAC) else Color(0xFFFCA5A5))
                    }
                }
                Gap(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatPill(Icons.Filled.Favorite, b.bodyFatPct.f1() + "%", "body fat")
                    StatPill(Icons.Filled.FitnessCenter, b.leanMassKg.f1(), "lean kg")
                    StatPill(Icons.Filled.Opacity, b.waterPct.f0() + "%", "water")
                    StatPill(Icons.Filled.Speed, bmi.f1(), "BMI")
                }
                if (b !== latest || (comp != null && comp.time != latest.time && latest.bodyFatPct == null)) {
                    Gap(4.dp)
                    Text("Body composition from ${comp?.source} on ${comp?.date}", fontSize = 11.sp, color = Color(0xFFFFB3BA))
                }
                val fb = repo.store.weighInFeedback?.takeIf { it.readingTime == latest.time }
                if (fb != null) {
                    Gap(8.dp)
                    Text("💕 Coach: ${fb.text}", fontSize = 13.sp, color = Color.White)
                } else if (repo.aiConfigured) {
                    TextButton(onClick = vm::feedbackForLatestWeighIn, enabled = !vm.weighInFeedbackLoading) {
                        Text(if (vm.weighInFeedbackLoading) "Coach is looking…" else "💕 Get coach feedback on this weigh-in", color = Color(0xFFFFB3BA))
                    }
                }
                Gap(6.dp)
                FlipHint(Color.White)
            }
        },
        back = {
            Column {
                Text("All body data", style = MaterialTheme.typography.titleMedium)
                Gap(6.dp)
                DataRow("Weight", "${b.weightKg.f1()} kg")
                DataRow("Body fat", "${b.bodyFatPct.f1()} %  (${b.fatMassKg.f1()} kg fat)")
                DataRow("Lean body mass", "${b.leanMassKg.f1()} kg")
                b.muscleKg?.let { DataRow("Muscle mass", "%.1f kg".format(it)) }
                b.visceralFat?.let { DataRow("Visceral fat", "%.0f".format(it)) }
                b.bodyAge?.let { DataRow("Body age", "$it") }
                b.impedance?.let { DataRow("Impedance", "%.0f Ω".format(it)) }
                DataRow("Bone mass", "${b.boneMassKg.f1()} kg")
                DataRow("Body water", "${b.waterKg.f1()} kg  (${b.waterPct.f0()} %)")
                DataRow("BMR", "${b.bmrKcal.f0()} kcal/day")
                DataRow("BMI", "${bmi.f1()}" + if (repo.heightM() == null) "  (add height in Settings)" else "")
                DataRow("Weigh-ins on record", "${repo.store.bodyReadings.size}")
            }
        },
    )
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun SnapshotCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val hist = vm.repo.store.healthHistory
    val today = hist.lastOrNull { it.date == LocalDate.now().toString() } ?: hist.lastOrNull()
    if (today == null) {
        Text("Your daily health record starts with the next Garmin sync or weigh-in.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val monthAgo = hist.lastOrNull { it.date <= LocalDate.parse(today.date).minusDays(28).toString() }
    fun avg(f: (DayHealth) -> Double?, days: Long) =
        hist.filter { it.date > LocalDate.now().minusDays(days).toString() }.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
    FlipCard(
        Modifier.fillMaxWidth(), key = "snapshot-${today.date}",
        frontBrush = Brush.linearGradient(listOf(Color(0xFF1F1F23), Color(0xFF0F0F11))),
        backColor = Color(0xFF1B1B1F),
        front = {
            Column {
                Text("TODAY · ${today.date}", style = MaterialTheme.typography.labelSmall, color = Red)
                Gap(8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatPill(Icons.Filled.Speed, today.readiness?.toString() ?: "–", "readiness")
                    StatPill(Icons.Filled.Favorite, today.restingHr?.toString() ?: "–", "rest HR")
                    StatPill(Icons.Filled.MonitorHeart, today.hrv?.toString() ?: "–", "HRV")
                    StatPill(Icons.Filled.Bedtime, today.sleepScore?.toString() ?: "–", "sleep")
                }
                Gap(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatPill(Icons.Filled.MonitorWeight, today.weightKg.f1(), "kg")
                    StatPill(Icons.Filled.Favorite, today.bodyFatPct.f1() + "%", "body fat")
                    StatPill(Icons.Filled.MonitorHeart, today.vo2max.f0(), "VO2max")
                    StatPill(Icons.Filled.Speed, today.km7d.f0(), "km / 7d")
                }
                Gap(6.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("Last 30 days", style = MaterialTheme.typography.titleMedium)
                Gap(6.dp)
                DataRow("Avg resting HR", avg({ it.restingHr?.toDouble() }, 30).f0() + " bpm")
                DataRow("Avg HRV", avg({ it.hrv?.toDouble() }, 30).f0() + " ms")
                DataRow("Avg sleep score", avg({ it.sleepScore?.toDouble() }, 30).f0())
                DataRow("Avg sleep", avg({ it.sleepHours }, 30).f1() + " h")
                DataRow("Avg readiness", avg({ it.readiness?.toDouble() }, 30).f0())
                DataRow("Avg stress", avg({ it.stress?.toDouble() }, 30).f0())
                DataRow("Weight change", if (today.weightKg != null && monthAgo?.weightKg != null) "%+.1f kg".format(today.weightKg - monthAgo.weightKg) else "–")
                DataRow("Body fat change", if (today.bodyFatPct != null && monthAgo?.bodyFatPct != null) "%+.1f %%".format(today.bodyFatPct - monthAgo.bodyFatPct) else "–")
                DataRow("Days on record", "${hist.size}")
            }
        },
    )
}

@Composable
private fun TrendsCard(hist: List<DayHealth>) {
    val recent = hist.filter { it.date > LocalDate.now().minusDays(365).toString() }
    val series = listOf(
        Triple("Weight (kg)", recent.mapNotNull { it.weightKg }, Red),
        Triple("Body fat (%)", recent.mapNotNull { it.bodyFatPct }, Color(0xFFF87171)),
        Triple("Resting HR", recent.mapNotNull { it.restingHr?.toDouble() }, Color(0xFFFCA5A5)),
        Triple("HRV", recent.mapNotNull { it.hrv?.toDouble() }, Color(0xFFD4D4D8)),
    ).filter { it.second.size >= 2 }
    if (series.isEmpty()) return
    Gap(10.dp)
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("12-month trends", style = MaterialTheme.typography.titleMedium)
            series.forEach { (label, values, color) ->
                Gap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.1f → %.1f".format(values.first(), values.last()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Sparkline(values, color, Modifier.fillMaxWidth().height(36.dp))
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - ((v - min) / span * (size.height - 6) + 3).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        val lastY = size.height - ((values.last() - min) / span * (size.height - 6) + 3).toFloat()
        drawCircle(color, 4.dp.toPx(), Offset(size.width, lastY))
    }
}

@Composable
private fun HistoryCard(hist: List<DayHealth>) {
    if (hist.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val rows = hist.reversed().let { if (showAll) it.take(365) else it.take(10) }
    Gap(10.dp)
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Health record", style = MaterialTheme.typography.titleMedium)
            Gap(6.dp)
            Row {
                listOf("Date" to 1.3f, "kg" to 1f, "fat%" to 1f, "RHR" to 0.8f, "HRV" to 0.8f, "sleep" to 0.9f, "ready" to 0.9f).forEach { (h, w) ->
                    Text(h, Modifier.weight(w), fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            rows.forEach { d ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(d.date.drop(5), Modifier.weight(1.3f), fontSize = 12.sp)
                    Text(d.weightKg.f1(), Modifier.weight(1f), fontSize = 12.sp)
                    Text(d.bodyFatPct.f1(), Modifier.weight(1f), fontSize = 12.sp)
                    Text(d.restingHr?.toString() ?: "–", Modifier.weight(0.8f), fontSize = 12.sp)
                    Text(d.hrv?.toString() ?: "–", Modifier.weight(0.8f), fontSize = 12.sp)
                    Text(d.sleepScore?.toString() ?: "–", Modifier.weight(0.9f), fontSize = 12.sp)
                    Text(d.readiness?.toString() ?: "–", Modifier.weight(0.9f), fontSize = 12.sp)
                }
            }
            if (hist.size > 10) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${hist.size} days", color = Red) }
        }
    }
}
