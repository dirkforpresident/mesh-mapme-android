package sh.mapme.mapper.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.mapme.mapper.data.GhostHuntManager

/**
 * CatchToast — Fang-Meldung während der Fahrt (Task 5).
 * PENDING: „+10? wird bestätigt…" — der Server-Job läuft alle ~2 min.
 * CONFIRMED: Punkte fest (können höher sein: rx-Fang = ×1,5).
 * MISSED: kein Empfang / jemand war schneller — ehrlich bleiben.
 */
@Composable
fun CatchToastHost(hunt: GhostHuntManager, modifier: Modifier = Modifier) {
    val event by hunt.catchEvent.collectAsState()
    val e = event ?: return

    LaunchedEffect(e) {
        delay(if (e.status == GhostHuntManager.CatchStatus.PENDING) 240_000 else 6_000)
        hunt.dismissToast()
    }

    Card(
        modifier = modifier.padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (e.status) {
                GhostHuntManager.CatchStatus.CONFIRMED -> Color(0xFF14532D)
                GhostHuntManager.CatchStatus.MISSED -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }.copy(alpha = 0.95f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(e.ghost.kind.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Column {
                when (e.status) {
                    GhostHuntManager.CatchStatus.PENDING -> {
                        Text("${e.ghost.kind.labelDe} — +${e.ghost.points}?",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                        Text("wird bestätigt…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GhostHuntManager.CatchStatus.CONFIRMED -> {
                        Text("${e.ghost.kind.labelDe} gefangen! +${e.confirmedPoints ?: e.ghost.points} P",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF86EFAC),
                            style = MaterialTheme.typography.titleSmall)
                        Text("ins Album gelegt",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF86EFAC).copy(alpha = 0.8f))
                    }
                    GhostHuntManager.CatchStatus.MISSED -> {
                        Text("${e.ghost.kind.labelDe} — noch nicht",
                            style = MaterialTheme.typography.titleSmall)
                        Text("kein Empfang bestätigt oder jemand war schneller",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
