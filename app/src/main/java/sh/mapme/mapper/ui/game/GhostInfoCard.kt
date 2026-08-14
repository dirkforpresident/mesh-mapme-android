package sh.mapme.mapper.ui.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.mapme.mapper.data.Ghost
import sh.mapme.mapper.data.GhostKind
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Geister-Farbe als Compose-Color — gleiche Palette wie Website/iOS. */
fun ghostComposeTint(kind: GhostKind): Color = when (kind) {
    GhostKind.IRRLICHT -> Color(0xFF3B82F6)
    GhostKind.PENDLERGEIST -> Color(0xFFF43F5E)
    GhostKind.BRUECKENGEIST -> Color(0xFF0891B2)
    GhostKind.WIEDERGAENGER -> Color(0xFF65A30D)
    GhostKind.BERGGEIST -> Color(0xFFA855F7)
}

/**
 * MonstiSprites — Download-Cache für die echten Grok-Monstis (PNG vom
 * Server, wie Website/iOS). [version] tickt hoch, wenn ein Sprite fertig
 * ist — Compose/Overlay-Rebuilds hängen sich daran. Fallback bleibt der
 * Emoji-Kreis, bis das Bild da ist.
 */
object MonstiSprites {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(g: Ghost) = "${g.kind.wire}/${g.spriteIndex}"

    fun get(g: Ghost): Bitmap? {
        val k = key(g)
        cache[k]?.let { return it }
        if (loading.add(k)) {
            scope.launch {
                try {
                    URL(g.spriteUrl()).openStream().use { s ->
                        BitmapFactory.decodeStream(s)?.let {
                            cache[k] = it
                            _version.value++
                        }
                    }
                } catch (e: Exception) {
                    loading.remove(k)   // Retry beim nächsten Rebuild
                }
            }
        }
        return null
    }
}

/**
 * GhostInfoCard — Tap auf ein Monsti auf der Karte, gleiche Infos wie das
 * Popup der Webkarte und das iOS-Sheet: Monsti, Typ + Name, Standort-Art,
 * Beschreibung, Punkte, Entfernung, Fang-Regel.
 */
@Composable
fun GhostInfoCard(
    ghost: Ghost,
    distanceM: Double?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = ghostComposeTint(ghost.kind)
    val spriteVersion by MonstiSprites.version.collectAsState()
    val sprite = remember(ghost.id, spriteVersion) { MonstiSprites.get(ghost) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text("✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .padding(4.dp))
            }

            if (sprite != null) {
                Image(sprite.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(80.dp))
            } else {
                Text(ghost.kind.emoji, style = MaterialTheme.typography.displaySmall)
            }
            Spacer(Modifier.height(6.dp))

            Text("${ghost.kind.emoji} ${ghost.kind.labelDe}" +
                    (ghost.name?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tint,
                textAlign = TextAlign.Center)

            ghost.siteLabelDe?.let { site ->
                Spacer(Modifier.height(4.dp))
                Text(site,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.2f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(ghost.kind.infoDe,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+${ghost.points} Punkte",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B))
                distanceM?.let { d ->
                    Spacer(Modifier.width(12.dp))
                    Text(if (d < 1000) "${d.toInt()} m entfernt"
                         else String.format("%.1f km entfernt", d / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(ghost.kind.catchHintDe,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}
