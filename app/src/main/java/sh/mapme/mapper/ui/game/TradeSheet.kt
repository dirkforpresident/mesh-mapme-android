package sh.mapme.mapper.ui.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.data.TradeManager

/**
 * TradeSheet — Tausch-UI (Task 9). Zwei Mapper, ein Parkplatz, ohne Internet.
 * NUR Direktfunk: „Ihr müsst euch hören — Repeater zählen nicht."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeSheetHost(viewModel: MainViewModel) {
    val tradeState by viewModel.tradeManager.state.collectAsState()
    val sheetCard by viewModel.tradeSheetCard.collectAsState()
    val privacyMode by viewModel.privacyMode.collectAsState()

    // Sheet: eigene Karte anbieten
    sheetCard?.let { card ->
        ModalBottomSheet(onDismissRequest = {
            viewModel.tradeManager.cancelOffer()
            viewModel.closeTradeSheet()
        }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("🔄 ${card.kind.emoji} ${card.name ?: card.kind.labelDe} tauschen",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Nur Direktfunk: Ihr müsst euch hören — Repeater zählen nicht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))

                when (val s = tradeState) {
                    is TradeManager.State.Offering -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("funke… noch ${s.secondsLeft} s", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { viewModel.tradeManager.cancelOffer() }) {
                            Text("Abbrechen")
                        }
                    }
                    is TradeManager.State.Done -> {
                        Text(if (s.gave) "✅ Karte übergeben!" else "✅ Karte erhalten!",
                            color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = {
                            viewModel.tradeManager.reset(); viewModel.closeTradeSheet()
                        }) { Text("Fertig") }
                    }
                    is TradeManager.State.Failed -> {
                        Text("✋ ${s.reason}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            TextButton(onClick = {
                                viewModel.tradeManager.startOffer(card, privacyMode)
                            }) { Text("Nochmal") }
                            TextButton(onClick = {
                                viewModel.tradeManager.reset(); viewModel.closeTradeSheet()
                            }) { Text("Schließen") }
                        }
                    }
                    else -> {
                        viewModel.tradeManager.tradeBlocker(card, privacyMode)?.let { blocker ->
                            Text("✋ $blocker", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        TextButton(
                            enabled = viewModel.tradeManager.tradeBlocker(card, privacyMode) == null,
                            onClick = { viewModel.tradeManager.startOffer(card, privacyMode) }
                        ) { Text("📡 Anbieten (90 s)") }
                    }
                }
            }
        }
    }

    // Dialog: fremdes Angebot in Rufweite
    (tradeState as? TradeManager.State.IncomingOffer)?.let { offer ->
        val kindLabel = offer.meta?.kind?.let { "${it.emoji} ${it.labelDe}" } ?: "👻 Monsti"
        val from = offer.frame.fromPubkey.joinToString("") { "%02x".format(it) }.take(8)
        AlertDialog(
            onDismissRequest = { viewModel.tradeManager.declineIncoming() },
            title = { Text("🔄 Tauschangebot!") },
            text = {
                Column {
                    Text("$kindLabel von $from…" +
                        (offer.meta?.points?.let { " ($it P)" } ?: ""))
                    Spacer(Modifier.height(4.dp))
                    Text("Direktfunk, SNR ${offer.snrX4 / 4.0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.tradeManager.acceptIncoming(privacyMode) }) {
                    Text("Annehmen")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.tradeManager.declineIncoming() }) {
                    Text("Ablehnen")
                }
            }
        )
    }

    // B-Seite: Ergebnis ohne offenes Sheet
    if (sheetCard == null) {
        (tradeState as? TradeManager.State.Done)?.let { done ->
            if (!done.gave) {
                AlertDialog(
                    onDismissRequest = { viewModel.tradeManager.reset() },
                    title = { Text("✅ Karte erhalten!") },
                    text = { Text("${done.card?.kind?.emoji ?: "👻"} " +
                        "${done.card?.kind?.labelDe ?: "Monsti"} ist jetzt in deinem Album.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.tradeManager.reset() }) { Text("Nice!") }
                    }
                )
            }
        }
    }
}
