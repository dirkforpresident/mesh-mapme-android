package sh.mapme.mapper.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.data.AlbumCard
import sh.mapme.mapper.data.GhostKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AlbumScreen — Tab „Jagd" (Task 6): Sammlung nach Typ, Detail pro Karte,
 * Tauschen-Einstieg (TradeSheet kommt in Task 9).
 */
@Composable
fun AlbumScreen(viewModel: MainViewModel) {
    val cards by viewModel.albumCards.collectAsState()
    val privacyMode by viewModel.privacyMode.collectAsState()
    var detail by remember { mutableStateOf<AlbumCard?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("👻 MeshMonstis-Album", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text(
            text = if (cards.isEmpty())
                "Fahr durch eine Geister-Zelle und fang dein erstes Monsti — das Radar auf der Karte zeigt dir den Weg."
            else
                "${cards.size} ${if (cards.size == 1) "Karte" else "Karten"} · " +
                "${cards.sumOf { it.points }} Punkte gesammelt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        // Typ-Übersicht: grau = 0, Zahl = Anzahl
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostKind.entries.forEach { kind ->
                val n = cards.count { it.kind == kind }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (n > 0) ghostTint(kind).copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .padding(vertical = 10.dp)
                ) {
                    Text(kind.emoji, fontSize = 22.sp,
                        modifier = Modifier.alpha(if (n > 0) 1f else 0.35f))
                    Text("$n", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (n > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards.sortedByDescending { it.caughtAt }) { card ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detail = card }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(card.kind.emoji, fontSize = 26.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                card.name ?: card.kind.labelDe,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${card.points} P · ${fmtDate(card.caughtAt)}" +
                                    if (card.source == "traded") " · 🔄 getauscht" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (card.tradeable()) {
                            Text("tauschbar", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF22C55E))
                        }
                    }
                }
            }
        }
    }

    detail?.let { card ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("${card.kind.emoji} ${card.name ?: card.kind.labelDe}") },
            text = {
                Column {
                    Text("Punkte: ${card.points}")
                    Text("Gefangen: ${fmtDate(card.caughtAt)}")
                    Text("Ort: %.4f, %.4f".format(card.lat, card.lon))
                    Text(
                        when {
                            card.source == "traded" ->
                                "Per Mesh-Tausch erhalten" +
                                    (card.fromPubkey?.let { " von ${it.take(8)}…" } ?: "")
                            card.tradeable() -> "Tauschbar (selbst gefangen, > 24 h)"
                            else -> "Noch nicht tauschbar (24-h-Frist ab Fang)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = card.tradeable() && privacyMode != "anonym",
                    onClick = { detail = null; viewModel.openTradeSheet(card) }
                ) { Text("🔄 Tauschen") }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) { Text("Schließen") }
            }
        )
    }
}

private fun ghostTint(kind: GhostKind): Color = when (kind) {
    GhostKind.IRRLICHT -> Color(0xFF3B82F6)
    GhostKind.PENDLERGEIST -> Color(0xFFF43F5E)
    GhostKind.BRUECKENGEIST -> Color(0xFF0891B2)
    GhostKind.WIEDERGAENGER -> Color(0xFF65A30D)
    GhostKind.BERGGEIST -> Color(0xFFA855F7)
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))
