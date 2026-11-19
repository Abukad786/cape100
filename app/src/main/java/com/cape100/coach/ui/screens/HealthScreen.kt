package com.cape100.coach.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap

/** Body, health snapshot, trends and the health record (activities have their own tab). */
@Composable
fun HealthScreen(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Health", style = MaterialTheme.typography.headlineMedium)
        HealthSection(vm)
        Gap(24.dp)
    }
}
