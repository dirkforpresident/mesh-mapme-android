package sh.mapme.mapper.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import sh.mapme.mapper.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * BleManager.kt - Direct port of BLEManager.swift
 * Handles all Bluetooth LE communication with MeshCore devices
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    // Bluetooth components
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Scanning
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    // State flows (like @Published in Swift)
    private val _isBluetoothOn = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothOn: StateFlow<Boolean> = _isBluetoothOn.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    // Session data
    private val _rxCount = MutableStateFlow(0)
    val rxCount: StateFlow<Int> = _rxCount.asStateFlow()

    private val _txCount = MutableStateFlow(0)
    val txCount: StateFlow<Int> = _txCount.asStateFlow()

    private val _sessionUploaded = MutableStateFlow(0)
    val sessionUploaded: StateFlow<Int> = _sessionUploaded.asStateFlow()

    private val _isTxActive = MutableStateFlow(false)
    val isTxActive: StateFlow<Boolean> = _isTxActive.asStateFlow()

    private val _privacyMode = MutableStateFlow("live")
    val privacyMode: StateFlow<String> = _privacyMode.asStateFlow()

    // Device info
    private val _selfInfo = MutableStateFlow<SelfInfo?>(null)
    val selfInfo: StateFlow<SelfInfo?> = _selfInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _isVerified = MutableStateFlow(false)
    val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

    private val _sessionVerified = MutableStateFlow(false)
    val sessionVerified: StateFlow<Boolean> = _sessionVerified.asStateFlow()

    // RX Packets
    data class RxPacket(
        val timestamp: Date,
        val path: List<String>,
        val rssi: Int,
        val snr: Float
    )

    private val _recentRxPackets = MutableStateFlow<List<RxPacket>>(emptyList())
    val recentRxPackets: StateFlow<List<RxPacket>> = _recentRxPackets.asStateFlow()

    // Debug log
    data class LogEntry(
        val id: UUID = UUID.randomUUID(),
        val timeString: String,
        val icon: String,
        val message: String,
        val color: String
    )

    private val _debugLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val debugLog: StateFlow<List<LogEntry>> = _debugLog.asStateFlow()

    // Coverage channel
    private val _coverageChannelReady = MutableStateFlow(false)
    val coverageChannelReady: StateFlow<Boolean> = _coverageChannelReady.asStateFlow()

    // Timing
    private var lastRxTime: Date? = null
    private var lastTxTime: Date? = null
    private var lastManualPingTime: Date? = null
    private var lastManualDiscoverTime: Date? = null

    // Current H3 (set by LocationService)
    var currentH3: String? = null

    // Sample store reference
    var sampleRepository: SampleRepository? = null

    // RX buffer for fragmented messages
    private var rxBuffer = ByteArray(0)

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // MARK: - Scanning

    fun startScanning() {
        if (_isScanning.value) return
        if (bluetoothAdapter?.isEnabled != true) {
            log("BLE", "Bluetooth is off", "red")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val currentList = _discoveredDevices.value.toMutableList()
                if (currentList.none { it.address == device.address }) {
                    currentList.add(device)
                    _discoveredDevices.value = currentList
                    log("SCAN", "Found: ${device.name ?: device.address}", "blue")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _isScanning.value = false
            }
        }

        scanner?.startScan(filters, settings, scanCallback)
        log("SCAN", "Started scanning...", "blue")

        // Auto-stop after 30 seconds
        scope.launch {
            delay(30_000)
            if (_isScanning.value) {
                stopScanning()
            }
        }
    }

    fun stopScanning() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
        _isScanning.value = false
        log("SCAN", "Stopped scanning", "gray")
    }

    // MARK: - Connection

    fun connect(device: BluetoothDevice) {
        stopScanning()
        log("BLE", "Connecting to ${device.name ?: device.address}...", "blue")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        _isConnected.value = false
        _connectedDeviceName.value = null
        _selfInfo.value = null
        _deviceInfo.value = null
        resetSessionStats()
        log("BLE", "Disconnected", "orange")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _isConnected.value = true
                    _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
                    gatt.discoverServices()
                    log("BLE", "Connected!", "green")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    scope.launch(Dispatchers.Main) {
                        disconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(Constants.SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(Constants.RX_CHARACTERISTIC)
                    txCharacteristic = service.getCharacteristic(Constants.TX_CHARACTERISTIC)

                    // Enable notifications on TX characteristic
                    txCharacteristic?.let { tx ->
                        gatt.setCharacteristicNotification(tx, true)
                        val descriptor = tx.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }

                    log("BLE", "Services discovered, ready!", "green")

                    // Send app start command
                    scope.launch {
                        delay(500)
                        sendAppStart()
                    }
                } else {
                    log("BLE", "Nordic UART Service not found!", "red")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == Constants.TX_CHARACTERISTIC) {
                handleRxData(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == Constants.TX_CHARACTERISTIC) {
                characteristic.value?.let { handleRxData(it) }
            }
        }
    }

    // MARK: - TX (Send data)

    private fun sendCommand(command: CommandCode, payload: ByteArray = byteArrayOf()) {
        val data = byteArrayOf(command.value) + payload
        sendRawData(data)
    }

    private fun sendRawData(data: ByteArray) {
        rxCharacteristic?.let { char ->
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
            Log.d(TAG, "TX: ${data.toHexString()}")
        }
    }

    private fun sendAppStart() {
        // AppStart command with flags
        val payload = byteArrayOf(
            0x07, // flags: request selfInfo + deviceInfo + channels
            Constants.DEBUG_BUILD.toByte()
        )
        sendCommand(CommandCode.APP_START, payload)
        log("TX", "AppStart (b${Constants.DEBUG_BUILD})", "blue")
    }

    fun sendDiscover() {
        sendCommand(CommandCode.REPEATERS_REQUEST)
        log("TX", "Discover", "blue")
    }

    fun sendManualDiscover() {
        if (!canSendManualDiscover()) return
        lastManualDiscoverTime = Date()
        sendDiscover()
    }

    fun sendManualPing() {
        if (!canSendManualPing()) return
        lastManualPingTime = Date()
        sendCoveragePing()
    }

    fun canSendManualDiscover(): Boolean {
        val last = lastManualDiscoverTime ?: return true
        return Date().time - last.time > Constants.MANUAL_DISCOVER_COOLDOWN
    }

    fun canSendManualPing(): Boolean {
        if (currentH3 == null) return false
        if (!_coverageChannelReady.value) return false
        val last = lastManualPingTime ?: return true
        return Date().time - last.time > Constants.MANUAL_PING_COOLDOWN
    }

    val manualDiscoverCooldownRemaining: Int
        get() {
            val last = lastManualDiscoverTime ?: return 0
            val elapsed = Date().time - last.time
            val remaining = Constants.MANUAL_DISCOVER_COOLDOWN - elapsed
            return if (remaining > 0) (remaining / 1000).toInt() else 0
        }

    val manualPingCooldownRemaining: Int
        get() {
            val last = lastManualPingTime ?: return 0
            val elapsed = Date().time - last.time
            val remaining = Constants.MANUAL_PING_COOLDOWN - elapsed
            return if (remaining > 0) (remaining / 1000).toInt() else 0
        }

    private fun sendCoveragePing() {
        val h3 = currentH3 ?: return
        // Build coverage ping message
        // TODO: Implement full coverage ping with channel secret
        _txCount.value++
        _isTxActive.value = true
        lastTxTime = Date()
        log("TX", "Coverage Ping", "orange")

        scope.launch {
            delay(Constants.TX_TIMEOUT)
            _isTxActive.value = false
        }
    }

    // MARK: - RX (Receive data)

    private fun handleRxData(data: ByteArray) {
        if (data.isEmpty()) return

        // Add to buffer
        rxBuffer += data

        // Try to parse complete messages
        while (rxBuffer.isNotEmpty()) {
            val code = rxBuffer[0]

            // Check if it's a response code or push code
            val responseCode = ResponseCode.fromByte(code)
            val pushCode = PushCode.fromByte(code)

            when {
                responseCode != null -> handleResponse(responseCode, rxBuffer)
                pushCode != null -> handlePush(pushCode, rxBuffer)
                else -> {
                    Log.w(TAG, "Unknown code: 0x${code.toHexString()}")
                    rxBuffer = byteArrayOf() // Clear buffer on unknown
                }
            }

            // For now, consume entire buffer per message (simplified)
            rxBuffer = byteArrayOf()
        }
    }

    private fun handleResponse(code: ResponseCode, data: ByteArray) {
        Log.d(TAG, "Response: $code, data: ${data.toHexString()}")

        when (code) {
            ResponseCode.OK -> log("RX", "OK", "green")
            ResponseCode.ERR -> log("RX", "Error", "red")
            ResponseCode.SELF_INFO -> parseSelfInfo(data)
            ResponseCode.DEVICE_INFO -> parseDeviceInfo(data)
            ResponseCode.CHANNEL_INFO -> parseChannelInfo(data)
            ResponseCode.CHANNEL_MSG_RECV_V3 -> parseChannelMessageV3(data)
            ResponseCode.CHANNEL_MSG_RECV -> parseChannelMessage(data)
            ResponseCode.SIGNATURE_READY -> log("RX", "Signature ready", "blue")
            ResponseCode.SIGNATURE -> parseSignature(data)
            else -> log("RX", "Response: $code", "gray")
        }
    }

    private fun handlePush(code: PushCode, data: ByteArray) {
        Log.d(TAG, "Push: $code, data: ${data.toHexString()}")

        when (code) {
            PushCode.ADVERT, PushCode.NEW_ADVERT -> parseAdvert(data)
            PushCode.LOG_RX_DATA -> parseLogRxData(data)
            PushCode.MSG_WAITING -> {
                log("RX", "Message waiting", "blue")
                // Request the message
                sendCommand(CommandCode.SYNC_NEXT_MESSAGE)
            }
        }
    }

    // MARK: - Parsing

    private fun parseSelfInfo(data: ByteArray) {
        if (data.size < 50) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte

            val info = SelfInfo(
                nodeName = readString(buffer, 32),
                publicKey = ByteArray(32).also { buffer.get(it) },
                radioFreq = buffer.int,
                radioSf = buffer.get().toInt(),
                radioCr = buffer.get().toInt(),
                txPower = buffer.get().toInt(),
                maxTxPower = buffer.get().toInt(),
                advLat = if (buffer.remaining() >= 4) buffer.int else 0,
                advLon = if (buffer.remaining() >= 4) buffer.int else 0
            )

            _selfInfo.value = info
            log("RX", "SelfInfo: ${info.nodeName}", "green")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SelfInfo", e)
        }
    }

    private fun parseDeviceInfo(data: ByteArray) {
        if (data.size < 10) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte

            val model = readString(buffer, 16)
            val version = readString(buffer, 16)

            _deviceInfo.value = DeviceInfo(hardware = "$model $version".trim())
            log("RX", "DeviceInfo: ${_deviceInfo.value?.hardware}", "green")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DeviceInfo", e)
        }
    }

    private fun parseChannelInfo(data: ByteArray) {
        // Check if this is the coverage channel
        log("RX", "ChannelInfo received", "blue")
        _coverageChannelReady.value = true
    }

    private fun parseChannelMessageV3(data: ByteArray) {
        if (data.size < 20) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte

            val channelIdx = buffer.get().toInt()
            val rssi = buffer.short.toInt()
            val snr = buffer.get().toFloat() / 4f

            // Parse path
            val pathCount = buffer.get().toInt().coerceIn(0, 4)
            val path = mutableListOf<String>()
            repeat(pathCount) {
                val nodeId = ByteArray(4)
                if (buffer.remaining() >= 4) {
                    buffer.get(nodeId)
                    path.add(nodeId.toHexString().takeLast(2).uppercase())
                }
            }

            if (path.isNotEmpty()) {
                val packet = RxPacket(
                    timestamp = Date(),
                    path = path,
                    rssi = rssi,
                    snr = snr
                )

                val currentPackets = _recentRxPackets.value.toMutableList()
                currentPackets.add(0, packet)
                if (currentPackets.size > 20) currentPackets.removeLast()
                _recentRxPackets.value = currentPackets

                _rxCount.value++
                lastRxTime = Date()

                log("RX", "${path.joinToString("→")} $rssi dBm", "green")

                // Create sample for upload
                createRxSample(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ChannelMessageV3", e)
        }
    }

    private fun parseChannelMessage(data: ByteArray) {
        // Simplified version without SNR
        log("RX", "Channel message (basic)", "green")
        _rxCount.value++
        lastRxTime = Date()
    }

    private fun parseAdvert(data: ByteArray) {
        if (data.size < 10) {
            log("RX", "Advert too short: ${data.size} bytes", "orange")
            return
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte

            val advertType = buffer.get().toInt()
            val nodeId = ByteArray(4)
            buffer.get(nodeId)

            log("RX", "Advert type $advertType: ${nodeId.toHexString()}", "blue")

            // Upload advert to server
            uploadAdvert(advertType, nodeId, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Advert", e)
        }
    }

    private fun parseLogRxData(data: ByteArray) {
        if (data.size < 10) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte

            val rssi = buffer.short.toInt()
            val snr = buffer.get().toFloat() / 4f

            log("RX", "LogRxData: $rssi dBm, SNR $snr", "green")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LogRxData", e)
        }
    }

    private fun parseSignature(data: ByteArray) {
        log("RX", "Signature received", "green")
        _isVerified.value = true
    }

    // MARK: - Samples

    private fun createRxSample(packet: RxPacket) {
        val h3 = currentH3 ?: return
        val lastHop = packet.path.lastOrNull() ?: return

        val sample = Sample(
            type = "rx",
            h3 = h3,
            rssi = packet.rssi,
            snr = packet.snr,
            path = packet.path,
            timestamp = packet.timestamp.time,
            privacyMode = _privacyMode.value
        )

        sampleRepository?.addSample(sample)
    }

    fun addVisitSample() {
        val h3 = currentH3 ?: return

        val sample = Sample(
            type = "visit",
            h3 = h3,
            rssi = null,
            snr = null,
            path = emptyList(),
            timestamp = Date().time,
            privacyMode = _privacyMode.value
        )

        sampleRepository?.addSample(sample)
    }

    private fun uploadAdvert(type: Int, nodeId: ByteArray, fullData: ByteArray) {
        // TODO: Implement API upload
        scope.launch {
            // Upload to mapme.sh API
        }
    }

    // MARK: - Helpers

    private fun readString(buffer: ByteBuffer, maxLength: Int): String {
        val bytes = ByteArray(maxLength)
        buffer.get(bytes)
        return bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
    }

    private fun log(category: String, message: String, color: String) {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val entry = LogEntry(
            timeString = timeFormat.format(Date()),
            icon = when (category) {
                "TX" -> "arrow.up.circle"
                "RX" -> "arrow.down.circle"
                "BLE" -> "antenna.radiowaves.left.and.right"
                "SCAN" -> "magnifyingglass"
                else -> "info.circle"
            },
            message = message,
            color = color
        )

        val currentLog = _debugLog.value.toMutableList()
        currentLog.add(0, entry)
        if (currentLog.size > 50) currentLog.removeLast()
        _debugLog.value = currentLog

        Log.d(TAG, "[$category] $message")
    }

    private fun resetSessionStats() {
        _rxCount.value = 0
        _txCount.value = 0
        _sessionUploaded.value = 0
        _recentRxPackets.value = emptyList()
        _isTxActive.value = false
        _coverageChannelReady.value = false
        _isVerified.value = false
        _sessionVerified.value = false
        lastRxTime = null
        lastTxTime = null
    }

    fun setPrivacyMode(mode: String) {
        _privacyMode.value = mode
    }

    fun incrementUploaded(count: Int) {
        _sessionUploaded.value += count
    }

    fun setSessionVerified(verified: Boolean) {
        _sessionVerified.value = verified
    }

    fun cleanup() {
        scope.cancel()
        disconnect()
    }
}

// MARK: - Data Classes

data class SelfInfo(
    val nodeName: String,
    val publicKey: ByteArray,
    val radioFreq: Int,
    val radioSf: Int,
    val radioCr: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val advLat: Int,
    val advLon: Int
)

data class DeviceInfo(
    val hardware: String
)

data class Sample(
    val type: String,
    val h3: String,
    val rssi: Int?,
    val snr: Float?,
    val path: List<String>,
    val timestamp: Long,
    val privacyMode: String
)

// MARK: - Extensions

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
fun Byte.toHexString(): String = "%02x".format(this)
