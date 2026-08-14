package sh.mapme.mapper.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.mapme.mapper.data.Ghost
import sh.mapme.mapper.data.GhostKind
import sh.mapme.mapper.data.GhostMath

/**
 * RadarSheet — „Monsti-Radar": nächster Geist je Typ, groß und spielig
 * (Port des iOS-RadarSheets). Öffnet über den Monsti-Button der GameShell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarSheet(
    ghosts: List<Ghost>,
    myLat: Double?,
    myLon: Double?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("📡 Monsti-Radar", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            if (myLat == null || myLon == null) {
                Text("Warte auf GPS…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val nearest = GhostMath.nearestByKind(myLat, myLon, ghosts)
                GhostKind.entries.forEach { kind ->
                    val n = nearest[kind]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(44.dp).background(
                                ghostComposeTint(kind).copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text(kind.emoji, fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(kind.labelDe, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (kind) {
                                    GhostKind.BERGGEIST -> "Bester Funk-Standort der Gegend — rauf da!"
                                    GhostKind.IRRLICHT -> "Da war noch nie jemand — erkunde es!"
                                    GhostKind.WIEDERGAENGER -> "Alte Daten — einmal drüberfahren"
                                    else -> "Funkloch — nur durch Repeater-Bau zu vertreiben"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (n != null) {
                            Text(
                                if (n.distanceM < 1000) "${n.distanceM.toInt()} m"
                                else String.format("%.1f km", n.distanceM / 1000),
                                fontWeight = FontWeight.Bold,
                                color = if (n.distanceM < 2000 && !kind.rxOnly)
                                    Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface)
                        } else {
                            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
