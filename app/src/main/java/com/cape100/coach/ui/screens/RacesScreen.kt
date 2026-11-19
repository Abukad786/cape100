package com.cape100.coach.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cape100.coach.races.RaceEntry
import com.cape100.coach.races.RaceKind
import com.cape100.coach.races.RacePurpose
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Chip
import com.cape100.coach.ui.components.FlipCard
import com.cape100.coach.ui.components.FlipHint
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.theme.Red
import java.time.format.TextStyle
import java.util.Locale

private fun RacePurpose.color(): Color = when (this) {
    RacePurpose.QUALIFIER -> Color(0xFFFF3B4E)
    RacePurpose.BACKUP_QUALIFIER -> Color(0xFFF87171)
    RacePurpose.TUNE_UP_ULTRA -> Color(0xFFFF1F3D)
    RacePurpose.LONG_RUN_MARATHON -> Color(0xFFDC2626)
    RacePurpose.FITNESS_HALF -> Color(0xFFFCA5A5)
}

private val PLAIN = Color(0xFF8E8E96)

@Composable
fun RacesScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val context = LocalContext.current
    val p = vm.profile
    LaunchedEffect(Unit) { vm.refreshRaces() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setHomeFromDevice() else vm.message = "Location denied – type your town instead"
    }

    // Filters (kept across tab switches).
    var query by rememberSaveable { mutableStateOf("") }
    var kinds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var forPlanOnly by rememberSaveable { mutableStateOf(false) }
    var purpose by rememberSaveable { mutableStateOf<String?>(null) }
    var maxKm by rememberSaveable { mutableStateOf<Int?>(null) }
    var province by rememberSaveable { mutableStateOf<String?>(null) }
    var roadOnly by rememberSaveable { mutableStateOf(true) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val all = vm.repo.raceEntries()
    val provinces = all.map { it.province }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    val q = query.trim().lowercase()
    val filtered = all.filter { e ->
        (q.isEmpty() || e.race.name.lowercase().contains(q) || e.race.location.lowercase().contains(q)) &&
            (kinds.isEmpty() || e.kinds.any { it.name in kinds }) &&
            (!forPlanOnly || e.suggestion != null) &&
            (purpose == null || e.suggestion?.purpose?.name == purpose) &&
            (maxKm == null || (e.kmFromHome != null && e.kmFromHome <= maxKm!!)) &&
            (province == null || e.province == province) &&
            (!roadOnly || !e.trailOnly)
    }
    val activeFilters = listOf(kinds.isNotEmpty(), forPlanOnly, purpose != null, maxKm != null, province != null, !roadOnly).count { it }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Races", style = MaterialTheme.typography.headlineMedium)
                    Text("${filtered.size} of ${all.size} upcoming · soonest first", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (vm.racesLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.refreshRaces(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh races") }
            }
            Gap(4.dp)
            YourRacesSection(vm)
            Gap(4.dp)
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Search race or town") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                        IconButton(onClick = { showFilters = !showFilters }) {
                            BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
                                Icon(if (showFilters) Icons.Filled.ExpandLess else Icons.Filled.FilterList, "Filters")
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            if (showFilters) {
                Gap(8.dp)
                FilterPanel(
                    kinds = kinds, onKind = { k -> kinds = if (k in kinds) kinds - k else kinds + k },
                    forPlanOnly = forPlanOnly, onForPlan = { forPlanOnly = it; if (!it) purpose = null },
                    purpose = purpose, onPurpose = { purpose = if (purpose == it) null else it; if (purpose != null) forPlanOnly = true },
                    maxKm = maxKm, onMaxKm = { maxKm = if (maxKm == it) null else it }, hasHome = vm.repo.home() != null,
                    provinces = provinces, province = province, onProvince = { province = if (province == it) null else it },
                    roadOnly = roadOnly, onRoadOnly = { roadOnly = it },
                    onClear = { kinds = emptySet(); forPlanOnly = false; purpose = null; maxKm = null; province = null; roadOnly = true; query = "" },
                )
                Gap(8.dp)
                HomeCard(
                    homeName = p.homeName,
                    onUseLocation = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.setHomeFromDevice()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    onTown = vm::setHomeFromTown,
                )
            }
        }
        if (filtered.isEmpty() && !vm.racesLoading) {
            item { Text(if (all.isEmpty()) "No races loaded yet – tap refresh." else "No races match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        var lastMonth = ""
        filtered.forEach { e ->
            val month = e.date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + e.date.year
            if (month != lastMonth) {
                lastMonth = month
                val count = filtered.count { it.date.year == e.date.year && it.date.month == e.date.month }
                item(key = "m-$month") {
                    Text("${month.uppercase()} · $count", style = MaterialTheme.typography.labelSmall, color = Red,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            item(key = "r-${e.race.slug}-${e.race.date}") {
                val isQualifier = e.race.slug == p.qualifierRace && e.race.date == p.qualifierDate && !p.qualified
                val isTuneUp = e.race.slug == p.tuneUpRace && e.race.date == p.tuneUpDate
                RaceCard(e, isQualifier, isTuneUp)
                var entering by remember { mutableStateOf(false) }
                val entered = p.enteredRaces.any { it.date == e.race.date && it.id.startsWith(e.race.slug) }
                if (entering) EnterRaceDialog(vm, e.race) { entering = false }
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entered) Chip("Entered ✓", Red)
                    else if (!isQualifier && !isTuneUp) FilledTonalButton(onClick = { entering = true }) { Text("I’m entered") }
                    when (e.suggestion?.purpose) {
                        RacePurpose.QUALIFIER, RacePurpose.BACKUP_QUALIFIER -> if (!isQualifier)
                            FilledTonalButton(onClick = { vm.useAsQualifier(e.suggestion) }) { Text("Make my qualifier") }
                        RacePurpose.TUNE_UP_ULTRA -> if (!isTuneUp)
                            FilledTonalButton(onClick = { vm.useAsTuneUp(e.suggestion) }) { Text("Make my tune-up") }
                        else -> {}
                    }
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.race.url))) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Race page")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(
    kinds: Set<String>, onKind: (String) -> Unit,
    forPlanOnly: Boolean, onForPlan: (Boolean) -> Unit,
    purpose: String?, onPurpose: (String) -> Unit,
    maxKm: Int?, onMaxKm: (Int) -> Unit, hasHome: Boolean,
    provinces: List<String>, province: String?, onProvince: (String) -> Unit,
    roadOnly: Boolean, onRoadOnly: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Red, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White,
    )
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            FilterRow("Distance") {
                RaceKind.entries.forEach { k -> FilterChip(k.name in kinds, { onKind(k.name) }, { Text(k.label) }, colors = chipColors) }
                FilterChip(roadOnly, { onRoadOnly(!roadOnly) }, { Text("Road only") }, colors = chipColors)
            }
            FilterRow("For my plan") {
                FilterChip(forPlanOnly, { onForPlan(!forPlanOnly) }, { Text("Suggested") }, colors = chipColors,
                    leadingIcon = { Icon(Icons.Filled.EmojiEvents, null, Modifier.size(16.dp)) })
                RacePurpose.entries.forEach { rp ->
                    FilterChip(purpose == rp.name, { onPurpose(rp.name) }, { Text(rp.title) }, colors = chipColors)
                }
            }
            FilterRow(if (hasHome) "Distance from home" else "Distance from home (set home below)") {
                listOf(50, 150, 400, 1000).forEach { km ->
                    FilterChip(maxKm == km, { onMaxKm(km) }, { Text("≤ $km km") }, enabled = hasHome, colors = chipColors)
                }
            }
            FilterRow("Province") {
                provinces.forEach { pr -> FilterChip(province == pr, { onProvince(pr) }, { Text(pr) }, colors = chipColors) }
            }
            TextButton(onClick = onClear) { Text("Clear all filters", color = Red) }
        }
    }
}

@Composable
private fun FilterRow(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun HomeCard(homeName: String, onUseLocation: () -> Unit, onTown: (String) -> Unit) {
    var town by rememberSaveable { mutableStateOf("") }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, null, tint = Red)
                Spacer(Modifier.width(6.dp))
                Text(if (homeName.isBlank()) "Set your home to see how far each race is" else "Distances from $homeName",
                    Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            }
            Gap(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(town, { town = it }, Modifier.weight(1f), placeholder = { Text("Town, e.g. Durbanville") }, singleLine = true)
                IconButton(onClick = { if (town.isNotBlank()) onTown(town) }) { Icon(Icons.Filled.Flag, "Use town") }
                IconButton(onClick = onUseLocation) { Icon(Icons.Filled.MyLocation, "Use my location") }
            }
        }
    }
}

@Composable
private fun RaceCard(e: RaceEntry, isQualifier: Boolean, isTuneUp: Boolean) {
    val s = e.suggestion
    val c = s?.purpose?.color() ?: PLAIN
    val d = e.date
    FlipCard(
        Modifier.fillMaxWidth(), key = "race-${e.race.slug}-${e.race.date}",
        frontBrush = Brush.linearGradient(listOf(c.copy(alpha = if (s != null) 0.30f else 0.14f), Color(0xFF0F0F11))),
        backColor = Color(0xFF1B1B1F),
        front = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(66.dp).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase(), fontSize = 10.sp, color = c, fontWeight = FontWeight.Bold)
                            Text("${d.dayOfMonth}", fontSize = 18.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp)
                            Text(d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() + " " + (d.year % 100), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.race.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(e.race.location + (e.kmFromHome?.let { " · $it km away" } ?: ""), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isQualifier || isTuneUp) Icon(Icons.Filled.EmojiEvents, "Selected", tint = c)
                }
                Gap(8.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s != null) {
                        Chip(s.purpose.title, c)
                        Chip("Fit ${s.score}/100", if (s.score >= 70) Color(0xFF22C55E) else if (s.score >= 50) Color(0xFFF59E0B) else PLAIN)
                    }
                    if (isQualifier) Chip("Your qualifier", c)
                    if (isTuneUp) Chip("Your tune-up", c)
                    e.race.distances.take(4).forEach { Chip(it, Color(0xFFD4D4D8)) }
                }
                if (s != null) { Gap(6.dp); Text(s.reason, fontSize = 13.sp, maxLines = 2) }
                Gap(4.dp)
                FlipHint()
            }
        },
        back = {
            Column {
                Text("How to run it", style = MaterialTheme.typography.titleMedium)
                Text(s?.howToRun ?: "Not one of your plan's key races. If it lines up with a weekend long run, run it easy at ultra pace " +
                    "and count it as that run – don't race it, and skip it during your taper.", fontSize = 14.sp)
                Gap(8.dp)
                Text("Distances on offer: " + e.race.distances.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
