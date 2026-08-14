package sh.mapme.mapper.data

import sh.mapme.mapper.H3Native

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
