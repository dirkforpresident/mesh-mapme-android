package sh.mapme.mapper.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * SampleRepository.kt - Direct port of SampleStore.swift
 * Handles sample persistence and upload queue
 */

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "samples")

class SampleRepository(private val context: Context) {

    companion object {
        private const val TAG = "SampleRepository"
        private val SAMPLES_KEY = stringPreferencesKey("pending_samples")
        private val TOTAL_UPLOADED_KEY = intPreferencesKey("total_uploaded")
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    private val _totalUploaded = MutableStateFlow(0)
    val totalUploaded: StateFlow<Int> = _totalUploaded.asStateFlow()

    // API Service reference
    var hexService: HexService? = null

    // BLE Manager reference (for session token and device info)
    var bleManager: BleManager? = null

    // Getter for session verification status
    val isSessionVerified: Boolean
        get() = bleManager?.sessionVerified?.value == true

    init {
        // Load persisted samples on init
        scope.launch {
            loadSamples()
            loadTotalUploaded()
        }
    }

    private suspend fun loadSamples() {
        context.dataStore.data.first().let { prefs ->
            val json = prefs[SAMPLES_KEY] ?: "[]"
            try {
                val type = object : TypeToken<List<Sample>>() {}.type
                val loaded: List<Sample> = gson.fromJson(json, type)
                _samples.value = loaded
                Log.d(TAG, "Loaded ${loaded.size} pending samples")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load samples", e)
            }
        }
    }

    private suspend fun loadTotalUploaded() {
        context.dataStore.data.first().let { prefs ->
            _totalUploaded.value = prefs[TOTAL_UPLOADED_KEY] ?: 0
        }
    }

    private suspend fun saveSamples() {
        val json = gson.toJson(_samples.value)
        context.dataStore.edit { prefs ->
            prefs[SAMPLES_KEY] = json
        }
    }

    private suspend fun saveTotalUploaded() {
        context.dataStore.edit { prefs ->
            prefs[TOTAL_UPLOADED_KEY] = _totalUploaded.value
        }
    }

    fun addSample(sample: Sample) {
        scope.launch {
            val currentSamples = _samples.value.toMutableList()
            currentSamples.add(sample)

            // Limit to 1000 pending samples
            if (currentSamples.size > 1000) {
                currentSamples.removeAt(0)
            }

            _samples.value = currentSamples
            saveSamples()

            Log.d(TAG, "Added sample: ${sample.type} at ${sample.h3} (${currentSamples.size} pending)")

            // Try to upload if we have enough samples
            if (currentSamples.size >= 5) {
                tryUpload()
            }
        }
    }

    fun tryUpload() {
        scope.launch {
            val samplesToUpload = _samples.value.take(50)
            if (samplesToUpload.isEmpty()) return@launch

            val service = hexService ?: return@launch
            val ble = bleManager ?: return@launch

            // Check if we have a session token (verification is optional - server decides)
            val token = ble.sessionToken
            if (token == null) {
                Log.d(TAG, "Upload skipped: No session token (not connected or signing failed)")
                return@launch
            }
            // Note: We try to upload even with unverified sessions
            // The server will accept or reject based on its policy
            if (!ble.sessionVerified.value) {
                Log.d(TAG, "Uploading with unverified session (server may reject)")
            }

            try {
                val success = service.uploadSamples(
                    samples = samplesToUpload,
                    sessionToken = token,
                    selfInfo = ble.selfInfo.value,
                    deviceInfo = ble.deviceInfo.value
                )
                if (success) {
                    // Remove uploaded samples
                    val remaining = _samples.value.drop(samplesToUpload.size)
                    _samples.value = remaining
                    saveSamples()

                    // Update counters
                    _totalUploaded.value += samplesToUpload.size
                    saveTotalUploaded()

                    // Notify BLE Manager
                    ble.incrementUploaded(samplesToUpload.size)

                    Log.d(TAG, "Uploaded ${samplesToUpload.size} samples, ${remaining.size} remaining")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
            }
        }
    }

    fun clearAll() {
        scope.launch {
            _samples.value = emptyList()
            saveSamples()
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}
