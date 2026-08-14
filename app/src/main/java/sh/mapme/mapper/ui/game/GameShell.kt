package sh.mapme.mapper.ui.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.mapme.mapper.data.GhostMath

/**
 * GameShell — die App IST das Spiel (Pokémon-GO-Prinzip, Port der iOS-Shell).
 * Die Karte ist die Bühne: Spieler-Chip oben links, Mesh-LED oben rechts,
 * unten drei runde Spiel-Buttons (Album · Monsti · Verbinden). Alles
 * Technische lebt hinter den Chips/Buttons.
 */

private val Violet = Color(0xFFA855F7)
private val VioletDark = Color(0xFF6B21A8)

/** Spieler-Chip (oben links): Name, Monstis, Punkte → öffnet den Hub. */
@Composable
fun PlayerChip(name: String, cards: Int, points: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .border(1.dp, Violet.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Brush.linearGradient(listOf(Violet, VioletDark)), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("👻", fontSize = 20.sp) }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text("$cards Monstis · $points P",
                color = Color(0xFFC4B5FD), fontSize = 11.sp)
        }
    }
}

/** Verbindungs-LED (oben rechts) → öffnet den Companion-Screen. */
@Composable
fun LinkChip(connected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(
            if (connected) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "Mesh" else "kein Funk", color = Color.White, fontSize = 11.sp)
    }
}

/** Monsti-Button (unten Mitte): pulst, wenn ein Geist nah ist → Radar. */
@Composable
fun MonstiButton(nearest: GhostMath.Nearest?, onClick: () -> Unit) {
    val isClose = (nearest?.distanceM ?: Double.MAX_VALUE) < 2_000
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "pulseScale")

    Box(contentAlignment = Alignment.Center) {
        if (isClose) {
            Box(Modifier.size(84.dp).scale(scale)
                .border(3.dp, Violet.copy(alpha = (1.35f - scale).coerceIn(0f, 0.6f)), CircleShape))
        }
        Column(
            modifier = Modifier
                .size(76.dp)
                .background(Brush.verticalGradient(listOf(Violet, Color(0xFF4C1D95))), CircleShape)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val sprite = nearest?.let { MonstiSprites.get(it.ghost) }
            if (sprite != null) {
                androidx.compose.foundation.Image(
                    bitmap = sprite.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp))
            } else {
                Text(nearest?.ghost?.kind?.emoji ?: "👻", fontSize = 24.sp)
            }
            nearest?.let { n ->
                Text(
                    (if (n.distanceM < 1000) "${n.distanceM.toInt()} m"
                     else String.format("%.1f km", n.distanceM / 1000)) +
                        " " + GhostMath.compass(n.bearing),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Runder Neben-Button (Album / Verbinden) mit optionalem Badge. */
@Composable
fun SideButton(icon: String, label: String, badge: Int? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 24.sp) }
            if (badge != null && badge > 0) {
                Text("$badge", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier
                        .background(Violet, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
    }
}
