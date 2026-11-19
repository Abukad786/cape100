package com.cape100.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cape100.coach.data.BodyReading
import com.cape100.coach.scale.OkokProtocol
import com.cape100.coach.scale.OkokScanner
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.theme.Red
import kotlinx.coroutines.delay

private val BT_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** "Weigh in now" button that asks for Bluetooth permission, then opens the live weigh-in dialog. */
@Composable
fun WeighInButton(vm: AppViewModel, modifier: Modifier = Modifier, label: String = "Weigh in now", onOpenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) open = true else vm.message = "Bluetooth permission is needed to read your scale"
    }
    Button(onClick = {
        if (BT_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) open = true
        else launcher.launch(BT_PERMISSIONS)
    }, modifier = modifier) {
        Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp)); Text("  $label")
    }
    if (open) WeighInDialog(vm) { open = false }
}

@Composable
private fun WeighInDialog(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { OkokScanner(context) }
    var live by remember { mutableStateOf<OkokProtocol.Frame?>(null) }
    var stable by remember { mutableStateOf<OkokProtocol.Frame?>(null) }
    var saved by remember { mutableStateOf<BodyReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        if (!scanner.bluetoothOn) error = "Turn on Bluetooth, then try again."
        else scanner.start(
            onFrame = { f ->
                live = f
                // Keep the best stable frame; prefer one that carries impedance (needed for body fat).
                if (f.stable && (stable == null || (stable?.impedance == null && f.impedance != null))) stable = f
            },
            onError = { error = it },
        )
        onDispose { scanner.stop() }
    }
    // After the first stable reading, wait a few seconds for the impedance frame, then save once.
    LaunchedEffect(stable != null) {
        if (stable == null || saved != null) return@LaunchedEffect
        if (stable?.impedance == null) delay(6000)
        scanner.stop()
        val s = stable ?: return@LaunchedEffect
        saved = vm.saveScaleReading(s.weightKg, s.impedance)
    }
    LaunchedEffect(Unit) { delay(90_000); if (stable == null) { scanner.stop(); error = error ?: "No scale found. Step on it barefoot to wake it, close to your phone." } }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.MonitorWeight, null, tint = Red) },
        title = { Text(if (saved != null) "Saved" else "Weigh in") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    error != null -> Text(error!!, textAlign = TextAlign.Center)
                    saved != null -> {
                        val r = saved!!
                        Text("%.1f kg".format(r.weightKg ?: 0.0), fontSize = 40.sp, fontWeight = FontWeight.Black)
                        Gap(6.dp)
                        if (r.bodyFatPct != null) {
                            Text("Body fat %.1f%% · Muscle %.1f kg · Water %.0f%%".format(r.bodyFatPct, r.muscleKg ?: 0.0, r.waterPct ?: 0.0), textAlign = TextAlign.Center)
                            Text("Visceral fat %.0f · BMR %.0f kcal · Body age %d".format(r.visceralFat ?: 0.0, r.bmrKcal ?: 0.0, r.bodyAge ?: 0),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        } else if (r.impedance != null) {
                            Text("Add your height, birth year and sex in Settings to see body fat, muscle and water.", fontSize = 13.sp, textAlign = TextAlign.Center)
                        } else {
                            Text("Weight only – stand barefoot on the metal pads for body composition.", fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                        // Coach feedback on this weigh-in.
                        Gap(10.dp)
                        val fb = vm.repo.store.weighInFeedback?.takeIf { it.readingTime == r.time }
                        when {
                            fb != null -> Text("💕 " + fb.text, fontSize = 14.sp)
                            vm.weighInFeedbackLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Red)
                                Text("  Your coach is looking at this…", fontSize = 13.sp)
                            }
                        }
                    }
                    live != null -> {
                        Text("%.1f kg".format(live!!.weightKg), fontSize = 40.sp, fontWeight = FontWeight.Black)
                        Text(if (stable != null) "Measuring body composition… stay still" else "Hold still…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        CircularProgressIndicator(color = Red)
                        Gap(10.dp)
                        Text("Step onto your OKOK scale barefoot.\nKeep your phone nearby.", textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (saved != null) "Done" else "Cancel", color = Red) } },
    )
}
