package sh.mapme.mapper.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AlbumStore — lokale Sammelkarten (Task 6).
 *
 * Eine Karte = ein gefangener Geist (ghostId), kein Stack nach Typ.
 * JSON in filesDir/album.json (SharedPreferences-Niveau reicht, kein Room).
 * Tausch bewegt nur die Sammelkarte, nie Season-Punkte oder Erstbesteigungen.
 */
data class AlbumCard(
    val ghostId: Long,
    val kind: GhostKind,
    val name: String?,
    val points: Int,
    val lat: Double,
    val lon: Double,
    val source: String,        // "caught" | "traded"
    val caughtAt: Long,        // epoch ms (bei traded: Zeitpunkt des Tauschs)
    val fromPubkey: String?,   // nur bei traded
    val spriteIndex: Int
) {
    /** Tauschbar: nur selbst gefangen und mindestens 24 h alt (Soulbound-Frist). */
    fun tradeable(now: Long = System.currentTimeMillis()): Boolean =
        source == "caught" && now - caughtAt >= 24 * 60 * 60 * 1000L
}

class AlbumStore(context: Context) {

    companion object { private const val TAG = "AlbumStore" }

    private val file = File(context.filesDir, "album.json")

    private val _cards = MutableStateFlow<List<AlbumCard>>(emptyList())
    val cards: StateFlow<List<AlbumCard>> = _cards.asStateFlow()

    init { load() }

    @Synchronized
    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val out = mutableListOf<AlbumCard>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = GhostKind.fromWire(o.optString("kind")) ?: continue
                out.add(AlbumCard(
                    ghostId = o.getLong("ghostId"),
                    kind = kind,
                    name = if (o.isNull("name")) null else o.optString("name"),
                    points = o.optInt("points"),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    source = o.optString("source", "caught"),
                    caughtAt = o.optLong("caughtAt"),
                    fromPubkey = if (o.isNull("fromPubkey")) null else o.optString("fromPubkey"),
                    spriteIndex = o.optInt("spriteIndex", (o.getLong("ghostId") % 9).toInt())
                ))
            }
            _cards.value = out
        } catch (e: Exception) {
            Log.e(TAG, "album.json kaputt — starte leer", e)
        }
    }

    @Synchronized
    private fun persist() {
        try {
            val arr = JSONArray()
            for (c in _cards.value) {
                arr.put(JSONObject().apply {
                    put("ghostId", c.ghostId)
                    put("kind", c.kind.wire)
                    put("name", c.name ?: JSONObject.NULL)
                    put("points", c.points)
                    put("lat", c.lat)
                    put("lon", c.lon)
                    put("source", c.source)
                    put("caughtAt", c.caughtAt)
                    put("fromPubkey", c.fromPubkey ?: JSONObject.NULL)
                    put("spriteIndex", c.spriteIndex)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "album.json schreiben fehlgeschlagen", e)
        }
    }

    fun has(ghostId: Long): Boolean = _cards.value.any { it.ghostId == ghostId }

    /** Bestätigter eigener Fang (aus GhostHuntManager oder Feed-Seed). */
    fun addCaught(ghost: Ghost, points: Int, caughtAt: Long = System.currentTimeMillis()) {
        if (has(ghost.id)) return
        _cards.value = _cards.value + AlbumCard(
            ghostId = ghost.id, kind = ghost.kind, name = ghost.name,
            points = points, lat = ghost.lat, lon = ghost.lon,
            source = "caught", caughtAt = caughtAt, fromPubkey = null,
            spriteIndex = ghost.spriteIndex)
        persist()
    }

    /** Karte per Mesh-Tausch erhalten. */
    fun addTraded(card: AlbumCard, fromPubkey: String) {
        if (has(card.ghostId)) return
        _cards.value = _cards.value + card.copy(
            source = "traded", fromPubkey = fromPubkey,
            caughtAt = System.currentTimeMillis())
        persist()
    }

    /** Karte abgegeben (Tausch): raus aus dem Album. */
    fun remove(ghostId: Long) {
        _cards.value = _cards.value.filterNot { it.ghostId == ghostId }
        persist()
    }

    /** Anzahl Tausche heute (UTC) — Limit 5/Tag prüft der TradeManager. */
    var tradesToday: Pair<String, Int>
        get() {
            val o = try { JSONObject(File(file.parent, "trades.json").readText()) }
                catch (e: Exception) { JSONObject() }
            return Pair(o.optString("day", ""), o.optInt("count", 0))
        }
        set(value) {
            try {
                File(file.parent, "trades.json").writeText(
                    JSONObject().put("day", value.first).put("count", value.second).toString())
            } catch (e: Exception) { Log.e(TAG, "trades.json schreiben fehlgeschlagen", e) }
        }
}
