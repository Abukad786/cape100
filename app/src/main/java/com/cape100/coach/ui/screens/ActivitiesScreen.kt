package com.cape100.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.ActivitySummary
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.FlipCard
import com.cape100.coach.ui.components.FlipHint
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.components.SectionTitle
import com.cape100.coach.ui.components.StatPill
import com.cape100.coach.ui.components.fmtDuration
import com.cape100.coach.ui.theme.Fynbos
import com.cape100.coach.ui.theme.Ocean
import java.time.LocalDate

@Composable
fun ActivitiesScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val acts = vm.repo.store.activities
    val snap = vm.repo.store.snapshot
    val today = LocalDate.now()
    fun since(days: Long) = acts.filter { it.isRunLike && runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(today.minusDays(days)) == true }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Activities", style = MaterialTheme.typography.headlineMedium)
            if (!vm.repo.garminConnected) Text("Connect Garmin in Settings to see your activities here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.repo.store.lastSyncError?.let { Text("Last sync error: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Gap(8.dp)
            BreakdownCard(vm)
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TotalsCard("Last 7 days", since(7), Modifier.weight(1f))
                TotalsCard("Last 28 days", since(28), Modifier.weight(1f))
            }
            vm.repo.store.garminInsights?.let { Gap(10.dp); PredictionsCard(it) }
            snap?.let { s ->
                Gap(10.dp)
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Fitness", fontWeight = FontWeight.Bold)
                        Gap(6.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            StatPill(Icons.Filled.MonitorHeart, s.vo2max?.let { "%.0f".format(it) } ?: "–", "VO2max")
                            StatPill(Icons.Filled.Favorite, s.restingHr?.toString() ?: "–", "Resting HR")
                            StatPill(Icons.Filled.Timer, s.acuteLoad?.toString() ?: "–", "Acute load")
                        }
                        s.trainingStatus?.let { Gap(6.dp); Text("Training status: ${it.lowercase()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        items(acts, key = { it.id }) { ActivityCard(it) }
    }
}

private fun hms(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** Garmin's race predictions (with the sub-5:00 qualifier check) and your bests, from the data export. */
@Composable
private fun PredictionsCard(g: com.cape100.coach.data.GarminInsights) {
    FlipCard(
        Modifier.fillMaxWidth(), key = "predictions-${g.predictionsDate}", shape = RoundedCornerShape(20.dp),
        frontBrush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), MaterialTheme.colorScheme.surface)),
        front = {
            Column {
                Text("GARMIN RACE PREDICTIONS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Gap(6.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("5K" to g.race5kSec, "10K" to g.race10kSec, "Half" to g.raceHalfSec, "Marathon" to g.raceMarathonSec).forEach { (l, s) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(hms(s), fontWeight = FontWeight.Bold)
                            Text(l, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Gap(8.dp)
                val gap = g.raceMarathonSec - 5 * 3600
                Text(
                    if (gap <= 0) "✅ On track for the sub-5:00 qualifier."
                    else "Qualifier needs sub-5:00 – ${gap / 60} min to find." +
                        (g.marathonSec3MonthsAgo?.let { " You've already found ${(it - g.raceMarathonSec) / 60} min in 3 months." } ?: ""),
                    fontSize = 13.sp,
                )
                Gap(4.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("Your bests", fontWeight = FontWeight.Bold)
                Gap(4.dp)
                g.records.forEach { r ->
                    val v = if (r.type.startsWith("Farthest")) "%.1f km".format(r.value / 1000) else hms(r.value.toInt())
                    Text("${r.type.removePrefix("Best ").removeSuffix(" Run")}: $v · ${r.date}", fontSize = 13.sp)
                }
                Gap(4.dp)
                Text("Predictions from ${g.predictionsDate}. Garmin updates them as your fitness changes.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun TotalsCard(title: String, acts: List<ActivitySummary>, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall)
            Text("${acts.sumOf { it.distanceKm }.toInt()} km", style = MaterialTheme.typography.headlineMedium)
            Text("${acts.size} runs · ${fmtDuration(acts.sumOf { it.durationMin })} · +${acts.sumOf { it.elevationGainM }} m", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ActivityCard(a: ActivitySummary) {
    FlipCard(
        Modifier.fillMaxWidth(), key = "act-${a.id}", shape = RoundedCornerShape(20.dp),
        frontBrush = Brush.linearGradient(listOf(Ocean.copy(alpha = 0.25f), Fynbos.copy(alpha = 0.10f))),
        front = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsRun, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(a.startLocal.take(16).replace('T', ' ') + " · " + a.type.replace('_', ' '), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Gap(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatPill(Icons.Filled.Straighten, "${a.distanceKm.fmtKm()} km", "distance")
                    StatPill(Icons.Filled.Timer, fmtDuration(a.durationMin), "time")
                    StatPill(Icons.Filled.Landscape, "${a.elevationGainM} m", "climb")
                }
                Gap(4.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                val pace = if (a.distanceKm > 0) a.durationMin / a.distanceKm else 0.0
                Text("Details", fontWeight = FontWeight.Bold)
                Gap(4.dp)
                Text("Average pace: ${if (pace > 0) "%d:%02d /km".format(pace.toInt(), ((pace % 1) * 60).toInt()) else "–"}")
                Text("Avg HR: ${a.avgHr ?: "–"}   Max HR: ${a.maxHr ?: "–"}")
                Text("Aerobic training effect: ${a.trainingEffect?.let { "%.1f".format(it) } ?: "–"}")
                Text("Training load: ${a.load?.let { "%.0f".format(it) } ?: "–"}")
            }
        },
    )
}
