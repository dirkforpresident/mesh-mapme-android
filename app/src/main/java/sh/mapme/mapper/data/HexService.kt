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

    // Cache
    private var lastFetchLocation: Pair<Double, Double>? = null
    private var lastFetchTime: Long = 0

    // MARK: - Fetch Hexes

    fun fetchHexes(lat: Double, lon: Double, radius: Int = 1000) {
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
                val url = "${Constants.API_BASE_URL}/api/hexes?lat=$lat&lon=$lon&radius=$radius"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val hexes = gson.fromJson(body, Array<ServerHex>::class.java).toList()
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

    suspend fun uploadSamples(samples: List<Sample>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${Constants.API_BASE_URL}/api/samples"
                val jsonBody = gson.toJson(samples)

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("X-App-Version", Constants.APP_VERSION)
                    .addHeader("X-Build", Constants.DEBUG_BUILD.toString())
                    .addHeader("X-Platform", "android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded ${samples.size} samples")
                        true
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code}")
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
                val url = "${Constants.API_BASE_URL}/api/live-mappers"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val mappers = gson.fromJson(body, Array<LiveMapper>::class.java).toList()
                        _liveMappers.value = mappers
                        Log.d(TAG, "Fetched ${mappers.size} live mappers")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching live mappers", e)
            }
        }
    }

    // MARK: - Fetch Leaderboard

    fun fetchLeaderboard() {
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/leaderboard"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val entries = gson.fromJson(body, Array<LeaderboardEntry>::class.java).toList()
                        _leaderboard.value = entries
                        Log.d(TAG, "Fetched ${entries.size} leaderboard entries")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching leaderboard", e)
            }
        }
    }

    // MARK: - Upload Advert

    fun uploadAdvert(type: Int, nodeId: ByteArray, data: ByteArray) {
        scope.launch {
            try {
                val url = "${Constants.API_BASE_URL}/api/adverts"
                val payload = mapOf(
                    "type" to type,
                    "nodeId" to nodeId.toHexString(),
                    "data" to data.toHexString()
                )
                val jsonBody = gson.toJson(payload)

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Uploaded advert type $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading advert", e)
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
    @SerializedName("color") val color: HexColor,
    @SerializedName("rssi") val rssi: Int? = null,
    @SerializedName("count") val count: Int? = null
)

data class HexColor(
    @SerializedName("r") val r: Float,
    @SerializedName("g") val g: Float,
    @SerializedName("b") val b: Float
) {
    companion object {
        val VISITED = HexColor(0.23f, 0.51f, 0.97f) // Blue
        val SIGNAL = HexColor(0.13f, 0.77f, 0.37f)  // Green
    }
}

data class LiveMapper(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("hexes") val hexes: Int = 0,
    @SerializedName("lastSeen") val lastSeen: Long? = null
)

data class LeaderboardEntry(
    @SerializedName("rank") val rank: Int,
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("hexes") val hexes: Int,
    @SerializedName("uploads") val uploads: Int
)
