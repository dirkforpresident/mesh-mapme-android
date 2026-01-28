package sh.mapme.mapper.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import sh.mapme.mapper.H3Native
import kotlinx.coroutines.flow.*
import sh.mapme.mapper.Constants

/**
 * LocationService.kt - Direct port of LocationManager.swift
 * Handles GPS tracking and H3 hexagonal indexing
 */
@SuppressLint("MissingPermission")
class LocationService(private val context: Context) {

    companion object {
        private const val TAG = "LocationService"
    }

    // H3 via native JNI library

    // Fused Location Provider
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    // State flows
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _currentH3 = MutableStateFlow<String?>(null)
    val currentH3: StateFlow<String?> = _currentH3.asStateFlow()

    private val _visitedHexes = MutableStateFlow<Set<String>>(emptySet())
    val visitedHexes: StateFlow<Set<String>> = _visitedHexes.asStateFlow()

    private val _authorizationStatus = MutableStateFlow(checkPermissions())
    val authorizationStatus: StateFlow<PermissionStatus> = _authorizationStatus.asStateFlow()

    // Callbacks
    var onH3Update: ((String) -> Unit)? = null
    var onNewHexVisited: ((String) -> Unit)? = null

    enum class PermissionStatus {
        GRANTED,
        DENIED,
        NOT_DETERMINED
    }

    private fun checkPermissions(): PermissionStatus {
        val fineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        )
        return if (fineLocation == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    fun updatePermissionStatus() {
        _authorizationStatus.value = checkPermissions()
    }

    fun startTracking() {
        if (_isTracking.value) return

        // Re-check permissions
        updatePermissionStatus()

        if (_authorizationStatus.value != PermissionStatus.GRANTED) {
            Log.w(TAG, "Location permission not granted, status: ${_authorizationStatus.value}")
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .setMinUpdateDistanceMeters(3f)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        updateLocation(location)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            _isTracking.value = true
            Log.d(TAG, "Started tracking successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tracking", e)
        }
    }

    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        _isTracking.value = false
        Log.d(TAG, "Stopped tracking")
    }

    private fun updateLocation(location: Location) {
        _currentLocation.value = location

        try {
            // Calculate H3 index using native library
            val h3Index = H3Native.latLngToCell(
                location.latitude,
                location.longitude,
                Constants.H3_RESOLUTION
            )

            if (h3Index.isEmpty()) {
                Log.w(TAG, "H3 returned empty index")
                return
            }

            val previousH3 = _currentH3.value
            _currentH3.value = h3Index

            // Notify BLE Manager
            onH3Update?.invoke(h3Index)

            // Check if this is a new hex
            if (previousH3 != h3Index) {
                val currentVisited = _visitedHexes.value.toMutableSet()
                if (!currentVisited.contains(h3Index)) {
                    currentVisited.add(h3Index)
                    _visitedHexes.value = currentVisited
                    onNewHexVisited?.invoke(h3Index)
                    Log.d(TAG, "New hex visited: $h3Index (total: ${currentVisited.size})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate H3 index", e)
        }
    }

    fun resetSessionStats() {
        _visitedHexes.value = emptySet()
    }

    /**
     * Get the boundary coordinates for an H3 hex (for drawing on map)
     */
    fun getH3Boundary(h3Index: String): List<Pair<Double, Double>>? {
        return try {
            H3Native.getCellBoundary(h3Index)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get H3 boundary for $h3Index", e)
            null
        }
    }

    /**
     * Get center of an H3 hex
     */
    fun getH3Center(h3Index: String): Pair<Double, Double>? {
        return try {
            H3Native.getCellCenter(h3Index)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get H3 center for $h3Index", e)
            null
        }
    }

    fun cleanup() {
        stopTracking()
    }
}
