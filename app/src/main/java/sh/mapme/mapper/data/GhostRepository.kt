package sh.mapme.mapper.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import sh.mapme.mapper.H3Native
import sh.mapme.mapper.util.FeedbackManager

/**
 * GhostRepository.kt — pure Radar-Mathematik (Task 2).
 *
 * H3 steckt hinter [H3Cells], damit Unit-Tests ohne JNI laufen —
 * Default ruft H3Native mit resolution 7 (Samples bleiben res 10).
 */

fun interface H3Cells {
    fun h7Of(lat: Double, lon: Double): String
}

object NativeH3Cells : H3Cells {
    override fun h7Of(lat: Double, lon: Double): String =
        H3Native.latLngToCell(lat, lon, 7)
}

object GhostMath {

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /** Peilung 0..360° (0 = Nord). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
        val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
            Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun compass(bearing: Double): String =
        COMPASS[((bearing + 11.25) % 360.0 / 22.5).toInt()]

    /** Steht der Mapper in der Zelle des Geists? Server-h7 gegen eigenes h7. */
    fun inCell(meLat: Double, meLon: Double, ghost: Ghost, h3: H3Cells = NativeH3Cells): Boolean =
        h3.h7Of(meLat, meLon) == ghost.h7

    /** Nächster Geist mit Distanz in Metern. */
    data class Nearest(val ghost: Ghost, val distanceM: Double, val bearing: Double)

    /** Je Typ der nächste Geist — Basis fürs Radar-HUD. */
    fun nearestByKind(meLat: Double, meLon: Double, ghosts: List<Ghost>): Map<GhostKind, Nearest> {
        val out = mutableMapOf<GhostKind, Nearest>()
        for (g in ghosts) {
            val d = haversineMeters(meLat, meLon, g.lat, g.lon)
            val cur = out[g.kind]
            if (cur == null || d < cur.distanceM) {
                out[g.kind] = Nearest(g, d, bearingDegrees(meLat, meLon, g.lat, g.lon))
            }
        }
        return out
    }
}


/**
 * GhostHuntManager — Fang-Erkennung waehrend der Fahrt (Task 5).
 *
 * Optimistischer Toast: GPS in der Geister-Zelle + neues Sample dieser
 * Session dort (rx-only-Typen brauchen ein rx). Wahrheit bleibt der Server:
 * innerhalb 3 min /api/game/feed pollen und den eigenen Pubkey + ghost_id
 * matchen — erst dann ist der Fang fest ("jemand war schneller" ist moeglich,
 * der Serverjob laeuft alle ~2 min).
 */
class GhostHuntManager(
    private val hexService: HexService,
    private val sampleRepository: SampleRepository,
    private val bleManager: BleManager,
    private val locationService: LocationService,
    private val feedbackManager: FeedbackManager,
    private val h3: H3Cells = NativeH3Cells
) {
    enum class CatchStatus { PENDING, CONFIRMED, MISSED }
    data class CatchEvent(val ghost: Ghost, val status: CatchStatus, val confirmedPoints: Int? = null)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _catchEvent = MutableStateFlow<CatchEvent?>(null)
    val catchEvent: StateFlow<CatchEvent?> = _catchEvent.asStateFlow()

    /** Wird in Task 6 gesetzt — bestaetigte Faenge landen im Album. */
    var onConfirmedCatch: ((Ghost, Int) -> Unit)? = null

    private val toastedIds = mutableSetOf<Long>()
    private var currentCellId: String? = null
    private var cellSampleBaseline = 0
    private var cellRxBaseline = 0

    init {
        scope.launch {
            combine(
                locationService.currentLocation,
                sampleRepository.samples,
                sampleRepository.totalUploaded,
                hexService.ghosts,
                bleManager.privacyMode
            ) { loc, pending, uploaded, ghosts, pm ->
                Snapshot(loc?.latitude, loc?.longitude, pending.size + uploaded,
                    bleManager.rxCount.value, ghosts, pm)
            }.collect { snap -> check(snap) }
        }
    }

    private data class Snapshot(
        val lat: Double?, val lon: Double?, val sampleTotal: Int,
        val rxTotal: Int, val ghosts: List<Ghost>, val privacy: String)

    private fun check(s: Snapshot) {
        if (s.lat == null || s.lon == null) return
        if (s.privacy == "anonym") return   // anonym faengt serverseitig nichts

        val myCell = try { h3.h7Of(s.lat, s.lon) } catch (e: Exception) { return }
        if (myCell != currentCellId) {
            // Zellwechsel: Baseline neu — nur Samples AUS dieser Zelle zaehlen
            currentCellId = myCell
            cellSampleBaseline = s.sampleTotal
            cellRxBaseline = s.rxTotal
        }

        val ghost = s.ghosts.firstOrNull { it.h7 == myCell && it.id !in toastedIds } ?: return
        val sampled = s.sampleTotal > cellSampleBaseline
        val rxHere = s.rxTotal > cellRxBaseline
        if (!sampled) return
        if (ghost.kind.rxOnly && !rxHere) return

        toastedIds.add(ghost.id)
        _catchEvent.value = CatchEvent(ghost, CatchStatus.PENDING)
        scope.launch { confirmViaFeed(ghost) }
    }

    private suspend fun confirmViaFeed(ghost: Ghost) {
        val myPubkey = bleManager.selfInfo.value?.publicKey?.toHexString() ?: return
        repeat(7) {
            delay(30_000)
            hexService.fetchGameFeed()
            delay(2_000)   // Fetch ist async — kurz auf den Flow warten
            val hit = hexService.gameFeed.value.firstOrNull {
                it.ghostId == ghost.id && it.pubkey == myPubkey
            }
            if (hit != null) {
                feedbackManager.playCatch()
                _catchEvent.value = CatchEvent(ghost, CatchStatus.CONFIRMED, hit.points)
                onConfirmedCatch?.invoke(ghost, hit.points)
                return
            }
        }
        _catchEvent.value = CatchEvent(ghost, CatchStatus.MISSED)
    }

    fun dismissToast() { _catchEvent.value = null }

    fun cleanup() { /* scope lebt so lange wie die App */ }
}
