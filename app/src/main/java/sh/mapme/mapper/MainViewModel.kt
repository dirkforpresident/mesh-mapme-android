package sh.mapme.mapper

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import sh.mapme.mapper.data.*

/**
 * MainViewModel - Shared ViewModel for all screens
 * Provides access to all services and their state
 */
class MainViewModel : ViewModel() {

    private val app = MapmeApp.instance
    val bleManager = app.bleManager
    val locationService = app.locationService
    val sampleRepository = app.sampleRepository
    val hexService = app.hexService

    // Convenience flows
    val isConnected = bleManager.isConnected
    val isScanning = bleManager.isScanning
    val discoveredDevices = bleManager.discoveredDevices
    val connectedDeviceName = bleManager.connectedDeviceName
    val rxCount = bleManager.rxCount
    val txCount = bleManager.txCount
    val sessionUploaded = bleManager.sessionUploaded
    val recentRxPackets = bleManager.recentRxPackets
    val privacyMode = bleManager.privacyMode
    val selfInfo = bleManager.selfInfo
    val deviceInfo = bleManager.deviceInfo
    val batteryPercent = bleManager.batteryPercent
    val batteryMillivolts = bleManager.batteryMillivolts
    val debugLog = bleManager.debugLog
    val isTxActive = bleManager.isTxActive
    val coverageChannelReady = bleManager.coverageChannelReady
    val sessionVerified = bleManager.sessionVerified

    val isTracking = locationService.isTracking
    val currentLocation = locationService.currentLocation
    val currentH3 = locationService.currentH3
    val visitedHexes = locationService.visitedHexes

    val pendingSamples = sampleRepository.samples
    val totalUploaded = sampleRepository.totalUploaded

    val serverHexes = hexService.serverHexes
    val liveMappers = hexService.liveMappers
    val leaderboard = hexService.leaderboard
    val ownRank = hexService.ownRank
    val isLoadingHexes = hexService.isLoading

    // Persistent UI state (survives tab switches)
    val showActivityLog = mutableStateOf(false)
    val showDeviceDetails = mutableStateOf(false)
    val showDeviceSettings = mutableStateOf(false)
    val mapFollowLocation = MutableStateFlow(true)
    val mapUseDarkMode = MutableStateFlow(true)
    val mapHasInitialLocation = mutableStateOf(false)
    val keepScreenOn = MutableStateFlow(true)

    init {
        // Fetch initial data
        viewModelScope.launch {
            hexService.fetchLiveMappers()
            hexService.fetchLeaderboard()
        }

        // Auto-fetch hexes when location changes
        viewModelScope.launch {
            currentLocation.collect { location ->
                location?.let {
                    hexService.fetchHexes(it.latitude, it.longitude)
                }
            }
        }

        // Fetch own rank when selfInfo becomes available (has pubkey)
        viewModelScope.launch {
            selfInfo.collect { info ->
                info?.publicKey?.let { pk ->
                    val pubkeyHex = pk.joinToString("") { "%02x".format(it) }
                    hexService.fetchOwnRank(pubkeyHex)
                }
            }
        }
    }

    // MARK: - BLE Actions

    fun startScanning() = bleManager.startScanning()
    fun stopScanning() = bleManager.stopScanning()
    fun connect(device: BluetoothDevice) = bleManager.connect(device)
    fun disconnect() = bleManager.disconnect()
    fun sendDiscover() = bleManager.sendManualDiscover()
    fun sendPing() = bleManager.sendManualPing()
    fun canSendDiscover() = bleManager.canSendManualDiscover()
    fun canSendPing() = bleManager.canSendManualPing()
    fun setPrivacyMode(mode: String) = bleManager.setPrivacyMode(mode)
    fun getDiscoverCooldown() = bleManager.manualDiscoverCooldownRemaining
    fun getPingCooldown() = bleManager.manualPingCooldownRemaining
    fun startVerification() = bleManager.startVerification()

    // MARK: - Location Actions

    fun startTracking() = locationService.startTracking()
    fun stopTracking() = locationService.stopTracking()
    fun updatePermissions() = locationService.updatePermissionStatus()

    // MARK: - Data Actions

    fun refreshLiveMappers() = hexService.fetchLiveMappers()
    fun refreshLeaderboard() = hexService.fetchLeaderboard()
    fun clearPendingSamples() = sampleRepository.clearAll()
}
