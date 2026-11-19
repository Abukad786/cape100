package com.cape100.coach.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cape100.coach.data.SessionType

/**
 * A card that flips around its Y axis when tapped. Both faces are laid out together so the
 * card keeps the size of the larger face and doesn't jump while flipping.
 */
@Composable
fun FlipCard(
    modifier: Modifier = Modifier,
    key: String = "",
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    frontBrush: Brush? = null,
    backColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    front: @Composable BoxScope.() -> Unit,
    back: @Composable BoxScope.() -> Unit,
) {
    var flipped by rememberSaveable(key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "flip",
    )
    val showFront = rotation <= 90f
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .graphicsLayer { rotationY = rotation; cameraDistance = 14f * density }
            .clip(shape)
            .then(
                when {
                    !showFront -> Modifier.background(backColor)
                    frontBrush != null -> Modifier.background(frontBrush)
                    else -> Modifier.background(surface)
                },
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) { flipped = !flipped }
            .animateContentSize(),
    ) {
        // Only the visible face is laid out, so each side keeps its own height.
        if (showFront) Box(Modifier.fillMaxWidth().padding(18.dp), content = front)
        else Box(Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f }.padding(18.dp), content = back)
    }
}

@Composable
fun FlipHint(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Loop, null, Modifier.size(12.dp), tint = color.copy(alpha = 0.7f))
        Spacer(Modifier.width(4.dp))
        Text("tap to flip", fontSize = 11.sp, color = color.copy(alpha = 0.7f))
    }
}

@Composable
fun ScoreRing(score: Int?, color: Color, size: Dp = 120.dp, label: String = "READINESS") {
    val sweep by animateFloatAsState(((score ?: 0) / 100f) * 300f, tween(900), label = "ring")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 120f, 300f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 120f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "–", fontSize = (size.value / 3.2).sp, fontWeight = FontWeight.Black)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatPill(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "bar")
    Box(modifier.height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

fun SessionType.icon(): ImageVector = when (this) {
    SessionType.REST -> Icons.Filled.Hotel
    SessionType.RECOVERY -> Icons.Filled.SelfImprovement
    SessionType.EASY -> Icons.AutoMirrored.Filled.DirectionsRun
    SessionType.STRENGTH -> Icons.Filled.FitnessCenter
    SessionType.CROSS -> Icons.Filled.DirectionsBike
    SessionType.HILLS -> Icons.Filled.Terrain
    SessionType.TEMPO -> Icons.Filled.Speed
    SessionType.INTERVALS -> Icons.Filled.Bolt
    SessionType.LONG -> Icons.Filled.Landscape
    SessionType.BACK_TO_BACK -> Icons.Filled.Timer
    SessionType.TUNEUP_RACE -> Icons.Filled.Flag
    SessionType.RACE -> Icons.Filled.EmojiEvents
    SessionType.EVENT -> Icons.Filled.Flag
}

fun SessionType.color(): Color = when (this) {
    SessionType.REST -> Color(0xFF52525B)
    SessionType.RECOVERY -> Color(0xFF9CA3AF)
    SessionType.EASY -> Color(0xFFF87171)
    SessionType.STRENGTH -> Color(0xFFA1A1AA)
    SessionType.CROSS -> Color(0xFFD4D4D8)
    SessionType.HILLS -> Color(0xFFEF4444)
    SessionType.TEMPO -> Color(0xFFDC2626)
    SessionType.INTERVALS -> Color(0xFFFF1F3D)
    SessionType.LONG -> Color(0xFFE11D2E)
    SessionType.BACK_TO_BACK -> Color(0xFFB91C1C)
    SessionType.TUNEUP_RACE -> Color(0xFFFF4D6D)
    SessionType.RACE -> Color(0xFFFF0033)
    SessionType.EVENT -> Color(0xFFFF4D6D)
}

fun fmtDuration(min: Int): String = if (min >= 60) "${min / 60}h${"%02d".format(min % 60)}" else "${min}min"

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
fun RowSpaced(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { content() }
