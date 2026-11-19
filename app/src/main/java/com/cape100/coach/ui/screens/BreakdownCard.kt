package com.cape100.coach.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.FlipCard
import com.cape100.coach.ui.components.FlipHint
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.theme.Red
import com.cape100.coach.ui.theme.RedDeep

/** AI breakdown of the newest Garmin activity. Front: headline + wins. Back: full breakdown, work-on, recovery. */
@Composable
fun BreakdownCard(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // redraw when stored data changes
    val b = vm.repo.store.breakdown
    val latest = vm.repo.store.activities.maxByOrNull { it.startLocal }
    if (latest == null) return

    if (b == null || b.activityId != latest.id) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, null, tint = Red)
                Spacer(Modifier.width(10.dp))
                Text(if (vm.repo.aiConfigured) "Want a breakdown of your ${latest.name}?" else "Add your Claude API key in Settings to get activity breakdowns.",
                    Modifier.weight(1f), fontSize = 14.sp)
                if (vm.breakdownLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (vm.repo.aiConfigured) TextButton(onClick = { vm.breakdownLatest(force = true) }) { Text("Break it down", color = Red) }
            }
        }
        return
    }

    FlipCard(
        Modifier.fillMaxWidth(), key = "breakdown-${b.activityId}-${b.createdAt}",
        frontBrush = Brush.linearGradient(listOf(RedDeep.copy(alpha = 0.85f), Color(0xFF0F0F11))),
        backColor = Color(0xFF1B1B1F),
        front = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, null, tint = Color(0xFFFF6B7A), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${b.activityName.uppercase()} · ${b.date}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB3BA))
                }
                Gap(6.dp)
                Text(b.headline, style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (b.wins.isNotEmpty()) {
                    Gap(8.dp)
                    b.wins.forEach { Text("✓ $it", fontSize = 14.sp, color = Color.White) }
                }
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlipHint(Color.White)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.breakdownLatest(force = true) }, enabled = !vm.breakdownLoading) {
                        Text(if (vm.breakdownLoading) "Thinking…" else "Redo", color = Color(0xFFFFB3BA))
                    }
                }
            }
        },
        back = {
            Column {
                Text("The breakdown", style = MaterialTheme.typography.titleMedium)
                Text(b.breakdown, fontSize = 14.sp)
                if (b.workOn.isNotBlank()) {
                    Gap(8.dp); HorizontalDivider(); Gap(8.dp)
                    Text("Work on", fontWeight = FontWeight.Bold, color = Red)
                    Text(b.workOn, fontSize = 14.sp)
                }
                if (b.recovery.isNotBlank()) {
                    Gap(8.dp)
                    Text("Next 24 hours", fontWeight = FontWeight.Bold, color = Red)
                    Text(b.recovery, fontSize = 14.sp)
                }
            }
        },
    )
}
