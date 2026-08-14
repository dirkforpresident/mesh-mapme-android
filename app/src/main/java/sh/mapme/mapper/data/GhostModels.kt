package sh.mapme.mapper.data

/**
 * GhostModels.kt — MeshMonstis-Datentypen (Radar/Album/Tausch).
 *
 * Der Server liefert bei GET /api/ghosts die H7-Zelle (`h7`) direkt mit —
 * die App vergleicht Zell-Strings und rechnet NICHT aus ghost.lat/lon zurück
 * (lat/lon mancher Geister ist nicht exakt das Zellzentrum).
 */

enum class GhostKind(val wire: String, val emoji: String, val labelDe: String) {
    IRRLICHT("irrlicht", "👻", "Irrlicht"),
    WIEDERGAENGER("wiedergaenger", "🧟", "Wiedergänger"),
    BERGGEIST("berggeist", "⛰️", "Berggeist"),
    PENDLERGEIST("pendlergeist", "🚗", "Pendlergeist"),
    BRUECKENGEIST("brueckengeist", "🌉", "Brückengeist");

    /** Nur mit rx-Sample fangbar ("austreiben durch Netzausbau"). */
    val rxOnly: Boolean get() = this == PENDLERGEIST || this == BRUECKENGEIST

    /** Kurzbeschreibung fürs Info-Card — gleiche Sprache wie Webkarte/iOS. */
    val infoDe: String get() = when (this) {
        IRRLICHT -> "Hier hat noch NIE jemand gemappt — erkunde es!"
        WIEDERGAENGER -> "Alte Coverage — seit über 90 Tagen ungeprüft."
        BERGGEIST -> "Bester Funk-Standort der Umgebung — prominenter Hügel, Aussichts- oder Wasserturm, Sendeturm-Gelände oder der Sattel zwischen zwei Netz-Inseln. Wer ihn fängt, steht am idealen Platz für einen (Solar-)Repeater."
        PENDLERGEIST -> "Viel befahren, nie Empfang — hier tut die Lücke weh."
        BRUECKENGEIST -> "Würde zwei Coverage-Inseln verbinden."
    }

    val catchHintDe: String get() =
        if (rxOnly) "⚡ Austreiben: Repeater in der Nähe aufstellen — der erste Empfang hier besiegt ihn."
        else "Fang ihn: Erzeuge hier ein Sample (hinfahren & mappen)."

    companion object {
        /** Unbekannte Typen (Server kann neue erfinden) → null, nie crashen. */
        fun fromWire(s: String?): GhostKind? = entries.find { it.wire == s }
    }
}

data class Ghost(
    val id: Long,
    val kind: GhostKind,
    val lat: Double,
    val lon: Double,
    val points: Int,
    val name: String?,
    val h7: String,
    // Berggeist-Standort-Details vom Server (Defaults: alte Aufrufer/Seeds ok)
    val site: String? = null,       // gipfel|aussichtsturm|wasserturm|sendemast|bruecke
    val hinweis: String? = null,    // Nach-dem-Fang-Satz (ohne rx)
    val hinweisRx: String? = null   // Nach-dem-Fang-Satz (mit rx)
) {
    /** Sprite-Variante 0..8 — deterministisch wie auf der Website (id % 9). */
    val spriteIndex: Int get() = (id % 9).toInt()

    fun spriteUrl(base: String = "https://mapme.sh"): String =
        "$base/ghosts/${kind.wire}/$spriteIndex.png"

    val siteLabelDe: String? get() = when (site) {
        "gipfel" -> "Gipfel"
        "aussichtsturm" -> "Aussichtsturm"
        "wasserturm" -> "Wasserturm"
        "sendemast" -> "Sendeturm-Gelände"
        "bruecke" -> "Brücken-Sattel"
        else -> null
    }
}

/** Ein Eintrag aus GET /api/game/feed (Fang oder Gipfelbesteigung). */
data class GameFeedEntry(
    val type: String,          // "ghost" | "summit"
    val what: String,          // kind bzw. Gipfelname
    val points: Int,
    val isFirst: Boolean,
    val caughtAt: String,      // ISO-8601 vom Server
    val lat: Double?,
    val lon: Double?,
    val name: String?,         // Anzeigename des Fängers
    val pubkey: String?,       // Fänger-Pubkey (für "war ich das?")
    val ghostId: Long?         // nur bei type == "ghost"
)
