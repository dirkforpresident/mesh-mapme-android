package sh.mapme.mapper.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import sh.mapme.mapper.Constants
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HexService.kt - Direct port of HexService.swift
 * Handles API communication with mapme.sh server
 */
class HexService {

    companion object {
        private const val TAG = "HexService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _serverHexes = MutableStateFlow<List<ServerHex>>(emptyList())
    val serverHexes: StateFlow<List<ServerHex>> = _serverHexes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _liveMappers = MutableStateFlow<List<LiveMapper>>(emptyList())
    val liveMappers: StateFlow<List<LiveMapper>> = _liveMappers.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val leaderboard: StateFlow<List<LeaderboardEntry>> = _leaderboard.asStateFlow()

    private val _ownRank = MutableStateFlow<OwnRank?>(null)
    val ownRank: StateFlow<OwnRank?> = _ownRank.asStateFlow()

    // Cache
    private var lastFetchLocation: Pair<Double, Double>? = null
    private var lastFetchTime: Long = 0

    // MARK: - Fetch Hexes

    // Coverage filter: 0 = all, 7 = 7 days, 30 = 30 days
    private val _coverageDays = MutableStateFlow(0)
    val coverageDays: StateFlow<Int> = _coverageDays.asStateFlow()

    fun setCoverageDays(days: Int) {
        _coverageDays.value = days
        // Force refresh by clearing cache
        lastFetchTime = 0
        lastFetchLocation = null
    }

    fun fetchHexes(lat: Double, lon: Double, radiusKm: Double = 5.0) {
        // Don't fetch too often
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < 10_000) return

        // Don't fetch if location hasn't changed much
        lastFetchLocation?.let { (lastLat, lastLon) ->
            val distance = haversineDistance(lat, lon, lastLat, lastLon)
            if (distance < 100) return // Less than 100m movement
        }

        lastFetchTime = now
        lastFetchLocation = Pair(lat, lon)
        _isLoading.value = true

        scope.launch {
            try {
                // Calculate bounding box (~5km radius)
                val latDelta = radiusKm / 111.0  // 1 degree lat ~ 111km
                val lonDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)))

                val minLat = lat - latDelta
                val maxLat = lat + latDelta
                val minLon = lon - lonDelta
                val maxLon = lon + lonDelta

                val days = _coverageDays.value
                val daysParam = if (days > 0) "&days=$days" else ""
                val url = "${Constants.API_BASE_URL}/api/hexes?minLat=$minLat&maxLat=$maxLat&minLon=$minLon&maxLon=$maxLon$daysParam"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        Log.d(TAG, "Hexes API response: ${body.take(200)}")
                        val hexes = try {
                            // Parse as JSON object with "hexes" array
                            val jsonObject = org.json.JSONObject(body)
                            val hexesArray = jsonObject.getJSONArray("hexes")
                            val result = mutableListOf<ServerHex>()
                            for (i in 0 until hexesArray.length()) {
                                val obj = hexesArray.getJSONObject(i)
                                result.add(ServerHex(
                                    h = obj.getString("h"),
                                    rssi = if (obj.isNull("rssi")) null else obj.optInt("rssi"),
                                    n = if (obj.isNull("n")) null else obj.optInt("n"),
                                    m = if (obj.isNull("m")) null else obj.optInt("m"),
                                    v = if (obj.isNull("v")) null else obj.optInt("v"),
                                    lat = if (obj.isNull("lat")) null else obj.optDouble("lat"),
                                    lon = if (obj.isNull("lon")) null else obj.optDouble("lon"),
                                    elev = if (obj.isNull("elev")) null else obj.optInt("elev")
                                ))
                            }
                            result
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse hexes response: ${e.message}")
                            emptyList()
                        }
                        _serverHexes.value = hexes
                        Log.d(TAG, "Fetched ${hexes.size} hexes")
                    } else {
                        Log.e(TAG, "Failed to fetch hexes: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching hexes", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // MARK: - Upload Samples

    /**
     * Upload samples to the server.
     * Requires a verified session token from BLE signing flow.
     *
     * Body format:
     * {
     *   "d": "pubkey_hex",
     *   "n": "node_name",
     *   "dt": "and",  // device type: android
     *   "hw": "hardware_model",
     *   "freq": 869.525,  // MHz
     *   "sf": 12,  // spreading factor
     *   "cr": 5,   // coding rate
     *   "samples": [{ "h", "t", "p", "r", "s", "m", "pm" }, ...]
     * }
     */
    suspend fun uploadSamples(
        samples: List<Sample>,
        sessionToken: String?,
        selfInfo: SelfInfo?,
        deviceInfo: DeviceInfo?,
        fallbackPubkeyHex: String = "",
        fallbackNodeName: String = ""
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (sessionToken == null) {
                Log.e(TAG, "Upload failed: No session token")
                return@withContext false
            }

            try {
                val url = "${Constants.API_BASE_URL}/api/samples"

                // Build request body matching iOS format
                val pubkeyHex = (selfInfo?.publicKey?.joinToString("") { "%02x".format(it) } ?: "")
                    .ifEmpty { fallbackPubkeyHex }
                if (pubkeyHex.isEmpty()) {
                    // Lieber im Puffer behalten als herrenlos hochladen — Identität
                    // kommt spätestens mit der nächsten BLE-Verbindung
                    Log.e(TAG, "Upload skipped: no node identity known yet")
                    return@withContext false
                }
                val nodeName = (selfInfo?.nodeName ?: "").ifEmpty { fallbackNodeName }
                val hardware = deviceInfo?.hardware ?: ""
                // Convert kHz to MHz. Some firmware (LilyGo T-Echo, Seeed Wio Tracker L1) puts
                // garbage into the radioFreq slot — drop to 0 (server treats as NULL) instead of
                // uploading bogus presets.
                val rawFreqMHz = (selfInfo?.radioFreq ?: 0) / 1000.0
                val freqMHz = if (rawFreqMHz in 100.0..1100.0) rawFreqMHz else 0.0

                val bodyJson = org.json.JSONObject().apply {
                    put("d", pubkeyHex)
                    put("n", nodeName)
                    put("dt", "and")  // Android device type
                    put("hw", hardware)
                    put("freq", freqMHz)
                    put("sf", selfInfo?.radioSf ?: 0)
                    put("cr", selfInfo?.radioCr ?: 0)

                    val samplesArray = org.json.JSONArray()
                    samples.forEach { sample ->
                        val sampleJson = org.json.JSONObject().apply {
                            put("h", sample.h3)
                            put("t", sample.timestamp)
                            put("p", org.json.JSONArray(sample.path))
                            // Mode: "rx" for RX with RSSI, "tx" for TX, "v" for visit
                            put("m", when (sample.type) {
                                "rx" -> "rx"
                                "tx" -> "tx"
                                else -> "v"  // visit
                            })
                            put("pm", sample.privacyMode)
                            // Only include RSSI if present and valid (-160 to 0)
                            sample.rssi?.let { rssi ->
                                if (rssi in -160..0) {
                                    put("r", rssi)
                                }
                            }
                            // SNR as integer
                            sample.snr?.let { snr ->
                                put("s", snr.toInt())
                            }
                        }
                        samplesArray.put(sampleJson)
                    }
                    put("samples", samplesArray)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("X-Session-Token", sessionToken)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: "{}"
                        val responseJson = org.json.JSONObject(responseBody)
                        val inserted = responseJson.optInt("inserted", 0)
                        Log.d(TAG, "Uploaded ${samples.size} samples, inserted: $inserted")
                        true
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code}")
                        if (response.code == 403) {
                            Log.e(TAG, "Session not verified - need to re-verify")
                        }
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading samples", e)
                false
            }
        }
    }

    // MARK: - Fetch Live Mappers

    fun fetchLiveMappers() {
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/online"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val mappers = try {
                            // Try parsing as wrapped response first
                            val wrapped = gson.fromJson(body, LiveMappersResponse::class.java)
                            wrapped.mappers ?: emptyList()
                        } catch (e: Exception) {
                            // Fallback to direct array
                            try {
                                gson.fromJson(body, Array<LiveMapper>::class.java).toList()
                            } catch (e2: Exception) {
                                emptyList()
                            }
                        }
                        _liveMappers.value = mappers
                        Log.d(TAG, "Fetched ${mappers.size} live mappers")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching live mappers", e)
            }
        }
    }

    // MARK: - MeshMonstis (Radar/Album)

    private val _ghosts = MutableStateFlow<List<Ghost>>(emptyList())
    val ghosts: StateFlow<List<Ghost>> = _ghosts.asStateFlow()

    /** Selbst gefangene Geister sofort ausblenden. Der Server erfährt vom Fang
     *  erst mit dem nächsten Upload und die Liste wird nur alle paar Minuten
     *  geholt — bis 2026-08-16 stand der Geist deshalb minutenlang weiter auf
     *  der Karte und der Spieler wusste nicht, ob er ihn hat (Tester-Rückmeldung).
     *  Stellt sich der Fang als Irrtum heraus, kommt er beim nächsten Abruf zurück. */
    private val versteckt = mutableSetOf<Long>()
    fun verstecke(id: Long) {
        versteckt.add(id)
        _ghosts.value = _ghosts.value.filterNot { it.id == id }
    }

    private val _gameFeed = MutableStateFlow<List<GameFeedEntry>>(emptyList())
    val gameFeed: StateFlow<List<GameFeedEntry>> = _gameFeed.asStateFlow()

    private var lastGhostFetch: Long = 0

    /**
     * Aktive Geister — GET /api/ghosts liefert ALLE (~1000, kein Bbox-Filter),
     * Client filtert. Server cacht 2 min, wir auch. JSONObject statt Gson:
     * details.name fehlt bei den meisten Typen, unbekannte kinds werden
     * uebersprungen statt zu crashen.
     */
    fun fetchGhosts(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastGhostFetch < 120_000) return
        lastGhostFetch = now

        scope.launch {
            try {
                val request = Request.Builder()
                    .url("${Constants.API_BASE_URL}/api/ghosts")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: "{}"
                    val arr = org.json.JSONObject(body).optJSONArray("ghosts") ?: return@use
                    val result = mutableListOf<Ghost>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val kind = GhostKind.fromWire(o.optString("kind")) ?: continue
                        val h7 = o.optString("h7", "")
                        if (h7.isEmpty()) continue
                        val details = o.optJSONObject("details")
                        result.add(Ghost(
                            id = o.getLong("id"),
                            kind = kind,
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            points = o.optInt("points", 0),
                            name = details?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" },
                            h7 = h7,
                            site = details?.optString("site")?.takeIf { it.isNotEmpty() },
                            hinweis = details?.optString("hinweis")?.takeIf { it.isNotEmpty() },
                            hinweisRx = details?.optString("hinweis_rx")?.takeIf { it.isNotEmpty() }
                        ))
                    }
                    _ghosts.value = result.filterNot { it.id in versteckt }
                    Log.d(TAG, "Fetched ${result.size} ghosts")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching ghosts", e)
            }
        }
    }

    /** Letzte 30 Faenge/Gipfel global — Fang-Bestaetigung + Album-Seed. */
    fun fetchGameFeed() {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("${Constants.API_BASE_URL}/api/game/feed")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: "{}"
                    val arr = org.json.JSONObject(body).optJSONArray("feed") ?: return@use
                    val result = mutableListOf<GameFeedEntry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        result.add(GameFeedEntry(
                            type = o.optString("type"),
                            what = o.optString("what"),
                            points = o.optInt("points", 0),
                            isFirst = o.optBoolean("is_first", false),
                            caughtAt = o.optString("caught_at"),
                            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                            lon = if (o.isNull("lon")) null else o.optDouble("lon"),
                            name = if (o.isNull("name")) null else o.optString("name"),
                            pubkey = if (o.isNull("pubkey")) null else o.optString("pubkey"),
                            ghostId = if (o.isNull("ghost_id")) null else o.optLong("ghost_id")
                        ))
                    }
                    _gameFeed.value = result
                    Log.d(TAG, "Fetched ${result.size} feed entries")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching game feed", e)
            }
        }
    }

    // MARK: - Fetch Leaderboard

    fun fetchLeaderboard() {
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/leaderboard?limit=100"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val entries = try {
                            val wrapped = gson.fromJson(body, LeaderboardResponse::class.java)
                            wrapped.leaderboard ?: emptyList()
                        } catch (e: Exception) {
                            try {
                                gson.fromJson(body, Array<LeaderboardEntry>::class.java).toList()
                            } catch (e2: Exception) {
                                emptyList()
                            }
                        }
                        _leaderboard.value = entries
                        Log.d(TAG, "Fetched ${entries.size} leaderboard entries")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching leaderboard", e)
            }
        }
    }

    fun fetchOwnRank(pubkey: String) {
        if (pubkey.isBlank()) return
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/rank/$pubkey"
                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val json = org.json.JSONObject(body)
                        val rank = if (json.isNull("rank")) null else json.optInt("rank")
                        val points = json.optInt("points", 0)
                        val hexes = json.optInt("hexes", 0)
                        _ownRank.value = OwnRank(rank, points, hexes)
                        Log.d(TAG, "Own rank: #$rank ($points points, $hexes hexes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching own rank", e)
            }
        }
    }

    // MARK: - Upload Node (repeater contacts, adverts)

    fun uploadNode(
        pubkey: String,
        type: Int,
        name: String,
        lat: Double?,
        lon: Double?,
        sessionToken: String?
    ) {
        if (sessionToken == null) return
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/node"
                val body = org.json.JSONObject().apply {
                    put("pk", pubkey)
                    put("sid", pubkey.take(8))
                    put("t", type)
                    put("n", name)
                    lat?.let { put("lat", it) }
                    lon?.let { put("lon", it) }
                }

                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("X-Session-Token", sessionToken)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded node: $name (type $type)")
                    } else {
                        Log.e(TAG, "Node upload failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading node", e)
            }
        }
    }

    // MARK: - Helpers

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun cleanup() {
        scope.cancel()
    }
}

// MARK: - Data Classes

data class ServerHex(
    @SerializedName("h") val h: String,
    @SerializedName("rssi") val rssi: Int? = null,
    @SerializedName("n") val n: Int? = null,       // Node count
    @SerializedName("m") val m: Int? = null,       // Mapper count
    @SerializedName("v") val v: Int? = null,       // Visit count
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lon") val lon: Double? = null,
    @SerializedName("elev") val elev: Int? = null
) {
    // Generate color based on RSSI — 6-tier scale matching web (mapme.sh)
    fun getColor(): HexColor {
        return HexColor.fromRssi(rssi)
    }
}

data class HexColor(
    val r: Float,
    val g: Float,
    val b: Float
) {
    companion object {
        // Exact colors from mapme.sh web frontend
        val EXCELLENT = HexColor(0.133f, 0.773f, 0.369f)  // #22c55e — > -80 dBm
        val GOOD = HexColor(0.518f, 0.800f, 0.086f)       // #84cc16 — > -100 dBm
        val OK = HexColor(0.918f, 0.702f, 0.031f)         // #eab308 — > -115 dBm
        val WEAK = HexColor(0.961f, 0.620f, 0.043f)       // #f59e0b — > -125 dBm
        val MARGINAL = HexColor(0.420f, 0.447f, 0.498f)   // #6b7280 — <= -125 dBm
        val VISITED = HexColor(0.216f, 0.255f, 0.318f)    // #374151 — no signal data

        fun fromRssi(rssi: Int?): HexColor {
            return when {
                rssi != null && rssi > -80 -> EXCELLENT
                rssi != null && rssi > -100 -> GOOD
                rssi != null && rssi > -115 -> OK
                rssi != null && rssi > -125 -> WEAK
                rssi != null -> MARGINAL
                else -> VISITED
            }
        }
    }
}

data class LiveMapper(
    @SerializedName("pubkey") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("ago") val ago: Int = 0,  // Minutes since last seen
    @SerializedName("hex") val hex: String? = null
)

data class LeaderboardEntry(
    @SerializedName("rank") val rank: Int,
    @SerializedName("pubkey") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("points") val hexes: Int = 0,
    @SerializedName("days") val days: Int = 0,
    @SerializedName("online") val online: Boolean = false
)

// Wrapper classes for API responses
data class LeaderboardResponse(
    @SerializedName("leaderboard") val leaderboard: List<LeaderboardEntry>? = null
)

data class LiveMappersResponse(
    @SerializedName("count") val count: Int = 0,
    @SerializedName("mappers") val mappers: List<LiveMapper>? = null
)

data class HexesResponse(
    @SerializedName("hexes") val hexes: List<ServerHex>? = null
)

data class OwnRank(
    val rank: Int?,
    val points: Int,
    val hexes: Int
)
