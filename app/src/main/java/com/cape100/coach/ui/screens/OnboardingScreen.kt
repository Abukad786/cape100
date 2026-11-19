package com.cape100.coach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.ui.AppViewModel
import com.cape100.coach.ui.components.Gap
import com.cape100.coach.ui.theme.HeroGradient

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(HeroGradient))
                .padding(24.dp),
        ) {
            Gap(24.dp)
            Text("CAPE 100 COACH", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            Text("Hermanus → Stellenbosch", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("100 km road ultra · ~900 m climb · 05:00 start · 15 h cut-off\nSunday 5 September 2027",
                color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Gap(12.dp)
            Text("Tell me where you are now and I'll build your plan – including the sub-5:00 qualifying marathon you need by 31 May 2027.",
                color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Card(
            Modifier.padding(16.dp), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                ProfileForm(vm.profile, buttonText = "Build my plan 🏃") { vm.saveProfile(it) }
            }
        }
    }
}
