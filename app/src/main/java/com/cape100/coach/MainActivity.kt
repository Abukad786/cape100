package com.cape100.coach

import androidx.compose.ui.unit.sp
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.screens.ActivitiesScreen
import com.cape100.coach.ui.screens.HealthScreen
import com.cape100.coach.ui.screens.OnboardingScreen
import com.cape100.coach.ui.screens.PlanScreen
import com.cape100.coach.ui.screens.RacesScreen
import com.cape100.coach.ui.screens.SettingsScreen
import com.cape100.coach.ui.screens.TodayScreen
import com.cape100.coach.ui.theme.CoachTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Every time you open the app: pull from Garmin if the last sync is more than 10 minutes old. */
    override fun onResume() {
        super.onResume()
        vm.refresh()
        vm.syncIfStale()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra("tab", -1).takeIf { it >= 0 }?.let { vm.requestedTab = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getIntExtra("tab", -1)?.takeIf { it >= 0 }?.let { vm.requestedTab = it }
        setContent {
            CoachTheme {
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.sync()
                }
                @Suppress("UNUSED_VARIABLE") val v = vm.version
                if (!vm.profile.onboarded) {
                    Scaffold { pad -> Box(Modifier.padding(pad)) { OnboardingScreen(vm) } }
                } else {
                    AppScaffold(vm)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(vm.requestedTab) { vm.requestedTab?.let { tab = it; vm.requestedTab = null } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    val tabs = listOf(
        "Today" to Icons.Filled.Today,
        "Plan" to Icons.Filled.CalendarMonth,
        "Races" to Icons.Filled.EmojiEvents,
        "Health" to Icons.Filled.MonitorHeart,
        "Activity" to Icons.AutoMirrored.Filled.DirectionsRun,
        "Settings" to Icons.Filled.Settings,
    )
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, containerColor = Color(0xFF1B1B1F), contentColor = Color.White, actionColor = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1, softWrap = false, fontSize = 11.sp) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                0 -> TodayScreen(vm)
                1 -> PlanScreen(vm)
                2 -> RacesScreen(vm)
                3 -> HealthScreen(vm)
                4 -> ActivitiesScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
