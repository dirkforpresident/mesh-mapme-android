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
import sh.mapme.mapper.service.MapperService
import sh.mapme.mapper.util.FeedbackManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * BleManager.kt - Direct port of BLEManager.swift
 * Handles all Bluetooth LE communication with MeshCore devices
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val TX_SIGNATURE = "=]"  // Signature to verify our own TX bounced back
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

    // Battery state
    private val _batteryPercent = MutableStateFlow(0)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _batteryMillivolts = MutableStateFlow(0)
    val batteryMillivolts: StateFlow<Int> = _batteryMillivolts.asStateFlow()

    private var batteryTimerJob: Job? = null

    private val _isVerified = MutableStateFlow(false)
    val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

    private val _sessionVerified = MutableStateFlow(false)
    val sessionVerified: StateFlow<Boolean> = _sessionVerified.asStateFlow()

    // Signing state
    private var pendingChallenge: String? = null
    private var pendingSignature: ByteArray? = null
    private var maxSignDataLen: Int = 0
    private var signingInProgress = false  // Prevent duplicate signing flows
    private var signingRetryCount = 0  // Retry counter for signing
    private var appStartSent = false  // Prevent duplicate AppStart

    // Auto-reconnect for verification
    private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectCount = 0  // Prevent infinite reconnect loops
    private var isFirstConnect = true   // For automatic double-connect

    // Session token (for API uploads)
    private var _sessionToken: String? = null
    val sessionToken: String? get() = _sessionToken

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
    private var coverageChannelIndex: Int = 0
    private var sessionChannelKey: ByteArray = ByteArray(16)
    private var pendingChannelCheck: Int = 0

    // Timing
    private var lastRxTime: Date? = null
    private var lastTxTime: Date? = null
    private var lastManualPingTime: Date? = null
    private var lastManualDiscoverTime: Date? = null

    // TX confirmation tracking
    private var pendingTx: Boolean = false
    private var pendingTxTimeout: Job? = null

    // Write queue - Android BLE only allows one write at a time
    private val writeQueue = mutableListOf<ByteArray>()
    private var writeInProgress = false

    // Timer jobs for auto-discovery and coverage TX (only in LIVE mode)
    private var discoverTimerJob: Job? = null
    private var coverageTxDelayJob: Job? = null
    private var coverageTxIntervalJob: Job? = null

    // Current H3 (set by LocationService)
    var currentH3: String? = null

    // Sample store reference
    var sampleRepository: SampleRepository? = null

    // Feedback manager for audio/haptic feedback
    var feedbackManager: FeedbackManager? = null

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
        lastConnectedDevice = device
        log("BLE", "Connecting to ${device.name ?: device.address}...", "blue")

        // Start foreground service for background operation
        MapperService.start(context)

        connectInternal(device)
    }

    private fun connectInternal(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        // Stop all timers
        stopTxTimers()
        stopBatteryTimer()
        mtuTimeoutJob?.cancel()
        mtuTimeoutJob = null

        // Clear write queue
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInProgress = false
        }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        _isConnected.value = false
        _connectedDeviceName.value = null
        _selfInfo.value = null
        _deviceInfo.value = null
        _batteryPercent.value = 0
        _batteryMillivolts.value = 0
        resetSessionStats()
        isFirstConnect = true  // Reset for next connection

        // Stop foreground service
        MapperService.stop(context)

        log("BLE", "Disconnected", "orange")
    }

    // Current MTU (default is 23, but we request higher)
    private var currentMtu: Int = 23
    private var mtuTimeoutJob: Job? = null
    private var serviceDiscoveryStarted = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _isConnected.value = true
                    _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
                    serviceDiscoveryStarted = false

                    // Request larger MTU before service discovery (important for SignData!)
                    Log.d(TAG, "Requesting MTU 128...")
                    gatt.requestMtu(128)
                    log("BLE", "Connected!", "green")

                    // Fallback: if onMtuChanged doesn't fire within 2s, start service discovery anyway
                    mtuTimeoutJob?.cancel()
                    mtuTimeoutJob = scope.launch {
                        delay(2000)
                        if (!serviceDiscoveryStarted && _isConnected.value) {
                            Log.w(TAG, "MTU callback timeout - starting service discovery anyway")
                            log("BLE", "MTU timeout, discovering...", "orange")
                            serviceDiscoveryStarted = true
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    scope.launch(Dispatchers.Main) {
                        disconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Cancel the fallback timeout
            mtuTimeoutJob?.cancel()
            mtuTimeoutJob = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.d(TAG, "MTU changed to $mtu bytes")
                log("BLE", "MTU: $mtu", "green")
            } else {
                Log.w(TAG, "MTU request failed with status $status, using default")
            }

            // Discover services after MTU negotiation
            if (!serviceDiscoveryStarted) {
                serviceDiscoveryStarted = true
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered called, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(Constants.SERVICE_UUID)
                Log.d(TAG, "Service lookup result: ${service != null}")
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(Constants.RX_CHARACTERISTIC)
                    txCharacteristic = service.getCharacteristic(Constants.TX_CHARACTERISTIC)
                    Log.d(TAG, "RX characteristic: ${rxCharacteristic != null}, TX characteristic: ${txCharacteristic != null}")

                    // Enable notifications on TX characteristic
                    txCharacteristic?.let { tx ->
                        val notifyResult = gatt.setCharacteristicNotification(tx, true)
                        Log.d(TAG, "setCharacteristicNotification result: $notifyResult")

                        val descriptor = tx.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        Log.d(TAG, "Descriptor found: ${descriptor != null}")
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val writeResult = gatt.writeDescriptor(it)
                            Log.d(TAG, "Descriptor write initiated: $writeResult")
                        }
                    } ?: run {
                        Log.e(TAG, "TX characteristic is null!")
                    }

                    log("BLE", "Services ready", "green")
                    // NOTE: AppStart is sent from onDescriptorWrite callback
                    // Do NOT send it here to avoid race conditions with duplicate signing flows
                } else {
                    Log.e(TAG, "Nordic UART Service NOT FOUND!")
                    log("BLE", "Nordic UART Service not found!", "red")
                }
            } else {
                Log.e(TAG, "onServicesDiscovered failed with status: $status")
                log("BLE", "Service discovery failed: $status", "red")
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite called, status=$status, descriptor=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("BLE", "Notifications enabled", "green")
                // Send AppStart after notifications are set up
                scope.launch {
                    delay(200)
                    Log.d(TAG, "Sending AppStart after descriptor write...")
                    sendAppStart()
                }
            } else {
                Log.e(TAG, "Descriptor write FAILED with status: $status")
                // Try sending AppStart anyway
                scope.launch {
                    delay(200)
                    Log.w(TAG, "Sending AppStart despite descriptor write failure...")
                    sendAppStart()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write completed successfully")
            } else {
                Log.e(TAG, "Write failed with status: $status")
            }
            // Process next queued write
            processWriteQueue()
        }
    }

    // MARK: - TX (Send data)

    private fun sendCommand(command: CommandCode, payload: ByteArray = byteArrayOf()) {
        val data = byteArrayOf(command.value) + payload
        sendRawData(data)
    }

    private fun sendRawData(data: ByteArray) {
        synchronized(writeQueue) {
            if (writeInProgress) {
                // Queue the write for later
                writeQueue.add(data)
                Log.d(TAG, "TX queued (${writeQueue.size} pending): ${data.toHexString()}")
                return
            }
            writeInProgress = true
        }
        performWrite(data)
    }

    private fun performWrite(data: ByteArray) {
        val char = rxCharacteristic
        val gatt = bluetoothGatt

        if (char == null || gatt == null) {
            Log.e(TAG, "TX FAILED: not connected")
            synchronized(writeQueue) { writeInProgress = false }
            processWriteQueue()
            return
        }

        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val result = gatt.writeCharacteristic(char)

        if (result) {
            Log.d(TAG, "TX: ${data.toHexString()}")
        } else {
            Log.e(TAG, "TX FAILED: writeCharacteristic returned false for ${data.toHexString()}")
            // Try next in queue
            synchronized(writeQueue) { writeInProgress = false }
            processWriteQueue()
        }
    }

    private fun processWriteQueue() {
        val nextData: ByteArray?
        synchronized(writeQueue) {
            if (writeQueue.isEmpty()) {
                writeInProgress = false
                return
            }
            nextData = writeQueue.removeAt(0)
            writeInProgress = true
        }
        nextData?.let {
            Log.d(TAG, "Processing queued write (${writeQueue.size} remaining)")
            performWrite(it)
        }
    }

    private fun sendAppStart() {
        // Prevent duplicate AppStart
        if (appStartSent) {
            Log.d(TAG, "AppStart already sent, skipping duplicate")
            return
        }
        appStartSent = true

        // AppStart Command Format (from iOS):
        // Byte 0: 0x01 (CommandCode.AppStart)
        // Byte 1: 0x01 (appVer)
        // Bytes 2-7: 0x00 (reserved, 6 bytes)
        // Bytes 8+: appName as string
        val appName = "MeshMapper"
        val command = byteArrayOf(
            CommandCode.APP_START.value,  // 0x01
            0x01,                          // appVer
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // reserved (6 bytes)
        ) + appName.toByteArray(Charsets.UTF_8)

        sendRawData(command)
        log("TX", "AppStart", "blue")
        Log.d(TAG, "AppStart sent (${command.size} bytes): ${command.toHexString()}")
    }

    // MARK: - Signing Commands

    private fun sendSignStart() {
        sendCommand(CommandCode.SIGN_START)
        Log.d(TAG, "SignStart sent (0x21)")
    }

    private fun sendSignData(data: ByteArray) {
        sendCommand(CommandCode.SIGN_DATA, data)
        Log.d(TAG, "SignData sent (${data.size} bytes)")
    }

    private fun sendSignFinish() {
        sendCommand(CommandCode.SIGN_FINISH)
        Log.d(TAG, "SignFinish sent (0x23)")
    }

    private fun sendDeviceQuery() {
        // DeviceQuery Command: [0x16][appTargetVer: 1 byte]
        val payload = byteArrayOf(0x01) // protocol version
        sendCommand(CommandCode.DEVICE_QUERY, payload)
        Log.d(TAG, "DeviceQuery sent (0x16 0x01)")
    }

    // MARK: - Signing Flow

    /**
     * Manually trigger verification flow.
     * Call this after connect when user presses START button.
     */
    fun startVerification() {
        Log.d(TAG, "=== MANUAL VERIFICATION START ===")
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot verify - not connected")
            log("BLE", "Not connected", "red")
            return
        }
        if (_selfInfo.value == null) {
            Log.e(TAG, "Cannot verify - no SelfInfo yet, sending AppStart")
            log("BLE", "Initializing...", "blue")
            // Send AppStart first, signing will start after SelfInfo received
            appStartSent = false
            sendAppStart()
            return
        }
        if (_isVerified.value && _sessionVerified.value) {
            Log.d(TAG, "Already verified")
            log("BLE", "Already verified", "green")
            return
        }
        // Reset counters for fresh attempt
        signingRetryCount = 0
        autoReconnectCount = 0
        signingInProgress = false
        startSigningFlow()
    }

    private fun startSigningFlow() {
        // Prevent duplicate signing flows
        if (signingInProgress) return

        val info = _selfInfo.value ?: run {
            Log.e(TAG, "Cannot start signing - no SelfInfo")
            return
        }
        if (_isVerified.value) return

        signingInProgress = true
        Log.d(TAG, "Starting signing flow...")
        log("TX", "Signing...", "orange")

        // Step 1: Send SignStart (0x21)
        sendSignStart()

        // Timeout for SignatureReady response - retry once, then create unverified session
        scope.launch {
            delay(3000)
            if (signingInProgress && !waitingForSignature && !_isVerified.value) {
                signingInProgress = false

                if (signingRetryCount < 1) {
                    // Retry once
                    signingRetryCount++
                    Log.d(TAG, "Signing timeout - retrying (attempt $signingRetryCount)")
                    log("TX", "Retrying sign...", "orange")
                    delay(500)
                    startSigningFlow()
                } else if (autoReconnectCount < 1) {
                    // Auto-reconnect once to retry verification
                    autoReconnectCount++
                    Log.d(TAG, "Signing failed - auto-reconnecting (attempt $autoReconnectCount)")
                    log("BLE", "Reconnecting for verify...", "orange")
                    val device = lastConnectedDevice
                    if (device != null) {
                        // Disconnect completely first
                        bluetoothGatt?.disconnect()
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        rxCharacteristic = null
                        txCharacteristic = null
                        _isConnected.value = false
                        resetSessionStats()

                        // Wait for BLE stack to settle
                        delay(2000)

                        // Reconnect
                        Log.d(TAG, "Reconnecting to ${device.name ?: device.address}")
                        connect(device)
                    } else {
                        Log.e(TAG, "Cannot reconnect - no lastConnectedDevice")
                        log("BLE", "Reconnect failed", "red")
                    }
                } else {
                    // Give up and create unverified session
                    Log.w(TAG, "Signing failed after reconnect - creating unverified session")
                    log("RX", "Sign not supported", "orange")
                    val selfInfo = _selfInfo.value
                    if (selfInfo != null) {
                        createServerSession(selfInfo.publicKey.toHexString(), "", "")
                    }
                }
            }
        }
    }

    private fun continueSigningWithData() {
        // Generate challenge string: "meshmap:{timestamp_ms}:{random}"
        val timestampMs = System.currentTimeMillis()
        val randomChars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val randomString = (1..9).map { randomChars.random() }.joinToString("")
        val challengeString = "meshmap:$timestampMs:$randomString"

        // Store challenge for server verification later
        pendingChallenge = challengeString

        // Convert to UTF-8 bytes for signing
        val challengeData = challengeString.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Challenge: $challengeString")

        // Send SignData
        sendSignData(challengeData)

        // Send SignFinish after a short delay
        scope.launch {
            delay(300)
            sendSignFinish()

            // Wait for signature with timeout
            delay(8000)
            if (!_isVerified.value && pendingChallenge != null) {
                waitingForSignature = false
                signingInProgress = false
                log("RX", "Signature timeout", "orange")
                // Create unverified session
                val info = _selfInfo.value
                if (info != null) {
                    createServerSession(info.publicKey.toHexString(), pendingChallenge!!, "")
                }
            }
        }
    }

    private fun verifySignatureAndCreateSession(signature: ByteArray) {
        val info = _selfInfo.value ?: run {
            Log.d(TAG, "Cannot verify - no SelfInfo")
            signingInProgress = false
            return
        }
        val challenge = pendingChallenge ?: run {
            Log.d(TAG, "Cannot verify - no pending challenge")
            signingInProgress = false
            return
        }

        val pubKeyHex = info.publicKey.toHexString()
        val signatureHex = signature.toHexString()

        Log.d(TAG, "Signature received, verifying with server...")

        // Mark as locally verified
        _isVerified.value = true
        pendingSignature = signature
        signingInProgress = false  // Signing flow complete
        log("RX", "Device signed", "green")
        feedbackManager?.playConnected()

        // Start battery monitoring
        scope.launch {
            delay(500)
            startBatteryTimer()
        }

        // Now call server to create session
        createServerSession(pubKeyHex, challenge, signatureHex)
    }

    private fun createServerSession(pubkey: String, challenge: String, signature: String) {
        val hardware = _deviceInfo.value?.hardware ?: ""
        val isUnverifiedRequest = challenge.isEmpty() || signature.isEmpty()

        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("${Constants.API_BASE_URL}/api/session")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = org.json.JSONObject().apply {
                    put("pubkey", pubkey)
                    put("challenge", challenge)
                    put("signature", signature)
                    put("hardware", hardware)
                }

                Log.d(TAG, "POST /api/session (${if (isUnverifiedRequest) "unverified" else "with signature"})")

                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(response)

                    val token = json.optString("token", null)
                    val verified = json.optBoolean("verified", false)

                    Log.d(TAG, "Session Token: ${token?.take(16)}...")
                    Log.d(TAG, "Verified: $verified")

                    scope.launch(Dispatchers.Main) {
                        _sessionToken = token
                        _sessionVerified.value = verified
                        pendingChallenge = null  // Clear challenge after server response

                        if (verified) {
                            autoReconnectCount = 0  // Reset on successful verification
                            log("RX", "Session verified!", "green")
                            feedbackManager?.playConnected()
                        } else {
                            log("RX", "Session unverified", "orange")
                        }

                        // Setup coverage channel regardless of verification
                        // This allows TX/RX to work even without server verification
                        if (token != null) {
                            setupCoverageChannel()
                        }
                    }
                } else {
                    Log.e(TAG, "Session creation failed: $responseCode")
                    scope.launch(Dispatchers.Main) {
                        pendingChallenge = null  // Clear on error too
                        log("RX", "Session error: $responseCode", "red")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session creation error", e)
                scope.launch(Dispatchers.Main) {
                    pendingChallenge = null  // Clear on error too
                    log("RX", "Connection error", "red")
                }
            }
        }
    }

    fun sendDiscover() {
        // CMD_SEND_CONTROL_DATA (55) with DISCOVER_REQ (0x80)
        // Format: [55][0x80][filter=4][0xDEADBEEF LE]
        val payload = byteArrayOf(
            0x80.toByte(),  // DISCOVER_REQ
            4,              // filter = REPEATER
            0xEF.toByte(),  // 0xDEADBEEF in Little Endian
            0xBE.toByte(),
            0xAD.toByte(),
            0xDE.toByte()
        )
        sendCommand(CommandCode.SEND_CONTROL_DATA, payload)
        Log.d(TAG, "Discover sent: ${(byteArrayOf(CommandCode.SEND_CONTROL_DATA.value) + payload).toHexString()}")
        log("TX", "Discover", "blue")
    }

    fun sendManualDiscover() {
        if (!canSendManualDiscover()) return
        lastManualDiscoverTime = Date()
        sendDiscover()
    }

    fun canSendManualDiscover(): Boolean {
        val last = lastManualDiscoverTime ?: return true
        return Date().time - last.time > Constants.MANUAL_DISCOVER_COOLDOWN
    }

    fun canSendManualPing(): Boolean {
        // iOS: guard privacyMode == "live", isConnected, coverageChannelReady, currentH3 != nil
        if (_privacyMode.value != "live") return false
        if (!_isConnected.value) return false
        if (!_coverageChannelReady.value) return false
        if (currentH3 == null) return false
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

    // MARK: - Coverage Channel Setup

    fun setupCoverageChannel() {
        if (!_isConnected.value) {
            Log.d(TAG, "Cannot setup coverage channel - not connected")
            return
        }

        // Generate random 16-byte key for this session
        sessionChannelKey = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val keyPrefix = sessionChannelKey.take(4).joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Generated session key: $keyPrefix...")

        // Start checking from channel 0
        pendingChannelCheck = 0
        sendGetChannel(0)
    }

    private fun sendGetChannel(index: Int) {
        pendingChannelCheck = index
        val payload = byteArrayOf(index.toByte())
        sendCommand(CommandCode.GET_CHANNELS, payload)
        Log.d(TAG, "GetChannel sent (idx: $index)")
    }

    private fun sendSetChannel(index: Int, name: String, secret: ByteArray) {
        // SetChannel: [0x20][channelIdx: 1 byte][name: 32 bytes null-padded][secret: 16 bytes]
        val nameBytes = ByteArray(32)
        name.toByteArray().copyInto(nameBytes, 0, 0, minOf(name.length, 32))

        val payload = byteArrayOf(index.toByte()) + nameBytes + secret.copyOf(16)
        sendCommand(CommandCode.SET_CHANNEL, payload)
        Log.d(TAG, "SetChannel sent (idx: $index, name: $name)")
    }

    private fun sendChannelMessage(channelIdx: Int, message: String) {
        // SendChannelTxtMsg: [0x03][txtType: 1B][channelIdx: 1B][timestamp: 4B LE][message]
        // This matches the connect.html reference implementation
        Log.d(TAG, "sendChannelMessage(idx=$channelIdx, msg=$message)")
        if (!_coverageChannelReady.value) {
            Log.e(TAG, "BLOCKED: coverage channel not ready!")
            log("TX", "Channel not ready", "red")
            return
        }

        // Build payload: [txtType][channelIdx][timestamp 4B LE][message]
        val timestamp = (System.currentTimeMillis() / 1000).toInt()
        val payload = ByteBuffer.allocate(1 + 1 + 4 + message.length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0.toByte())  // txtType = 0 (Plain)
            .put(channelIdx.toByte())
            .putInt(timestamp)
            .put(message.toByteArray())
            .array()

        val fullCommand = byteArrayOf(CommandCode.SEND_CHANNEL_TXT_MSG.value) + payload
        Log.d(TAG, "TX bytes: ${fullCommand.toHexString()}")
        sendCommand(CommandCode.SEND_CHANNEL_TXT_MSG, payload)
        Log.d(TAG, "Channel message sent OK (idx: $channelIdx, ${fullCommand.size} bytes)")
        feedbackManager?.playTx()

        // Start waiting for TX confirmation (our message bounced back from repeater)
        pendingTx = true
        pendingTxTimeout?.cancel()
        pendingTxTimeout = scope.launch {
            delay(10000)  // 10s timeout
            if (pendingTx) {
                pendingTx = false
                Log.d(TAG, "TX confirmation timeout - no repeater response")
            }
        }
    }

    // MARK: - TX Timers (only in LIVE mode)

    private fun startTxTimers() {
        if (_privacyMode.value != "live") return
        Log.d(TAG, "Starting TX timers (LIVE mode)")
        startDiscoverTimer()
        startCoverageTxDelayTimer()
    }

    private fun stopTxTimers() {
        discoverTimerJob?.cancel()
        discoverTimerJob = null
        coverageTxDelayJob?.cancel()
        coverageTxDelayJob = null
        coverageTxIntervalJob?.cancel()
        coverageTxIntervalJob = null
        _isTxActive.value = false
        Log.d(TAG, "TX timers stopped")
    }

    private fun resetTxTimers() {
        lastRxTime = Date()

        // Stop coverage TX if active
        if (_isTxActive.value) {
            Log.d(TAG, "RX received - stopping coverage TX")
            coverageTxIntervalJob?.cancel()
            coverageTxIntervalJob = null
            _isTxActive.value = false
        }

        // Only restart timers if in LIVE mode
        if (_privacyMode.value != "live") return

        startDiscoverTimer()
        startCoverageTxDelayTimer()
    }

    private fun startDiscoverTimer() {
        discoverTimerJob?.cancel()
        discoverTimerJob = scope.launch {
            delay(Constants.DISCOVER_INTERVAL)
            onDiscoverTimerFired()
        }
    }

    private fun onDiscoverTimerFired() {
        if (!_isConnected.value || _privacyMode.value != "live") return
        Log.d(TAG, "No RX for 30s - sending discover")
        sendDiscover()
        log("TX", "Auto discover", "blue")

        // Restart timer for next interval
        startDiscoverTimer()
    }

    private fun startCoverageTxDelayTimer() {
        coverageTxDelayJob?.cancel()
        coverageTxDelayJob = scope.launch {
            delay(Constants.COVERAGE_TX_DELAY)  // 5 minutes
            startCoverageTxInterval()
        }
    }

    private fun startCoverageTxInterval() {
        if (!_isConnected.value || !_coverageChannelReady.value || _privacyMode.value != "live") {
            Log.d(TAG, "Cannot start coverage TX - not ready")
            return
        }

        // Stop discover timer - switching to ping mode
        discoverTimerJob?.cancel()
        discoverTimerJob = null

        _isTxActive.value = true
        log("TX", "TX Mode started (3min idle)", "orange")
        feedbackManager?.playTxModeStarted()
        Log.d(TAG, "Starting coverage channel TX")

        // Send first message immediately
        sendCoverageMessage()

        // Schedule next with random interval
        scheduleNextCoverageTx()
    }

    private fun scheduleNextCoverageTx() {
        coverageTxIntervalJob?.cancel()
        val randomInterval = (Constants.COVERAGE_TX_MIN_INTERVAL..Constants.COVERAGE_TX_MAX_INTERVAL).random()
        Log.d(TAG, "Next ping in ${randomInterval / 1000}s")

        coverageTxIntervalJob = scope.launch {
            delay(randomInterval)
            if (_isTxActive.value) {
                sendCoverageMessage()
                scheduleNextCoverageTx()
            }
        }
    }

    private fun sendCoverageMessage() {
        if (!_isConnected.value || !_coverageChannelReady.value) return
        if (currentH3 == null) {
            Log.d(TAG, "No GPS - skipping coverage message")
            return
        }

        // Send TX_SIGNATURE so we can verify our own message when it bounces back
        sendChannelMessage(coverageChannelIndex, TX_SIGNATURE)
        _txCount.value++
        lastTxTime = Date()
        log("TX", "Auto TX #${_txCount.value}", "orange")
    }

    fun sendManualPing() {
        Log.d(TAG, "=== MANUAL PING START ===")
        Log.d(TAG, "  privacyMode: ${_privacyMode.value}")
        Log.d(TAG, "  isConnected: ${_isConnected.value}")
        Log.d(TAG, "  coverageChannelReady: ${_coverageChannelReady.value}")
        Log.d(TAG, "  coverageChannelIndex: $coverageChannelIndex")
        Log.d(TAG, "  currentH3: $currentH3")
        Log.d(TAG, "  cooldownRemaining: $manualPingCooldownRemaining s")

        if (!canSendManualPing()) {
            Log.d(TAG, "Manual ping blocked - cooldown: $manualPingCooldownRemaining s")
            log("TX", "Ping blocked (cooldown)", "orange")
            return
        }
        if (currentH3 == null) {
            Log.d(TAG, "Manual ping blocked - no GPS")
            log("TX", "Ping blocked (no GPS)", "red")
            return
        }

        // Send TX_SIGNATURE so we can verify our own message when it bounces back
        Log.d(TAG, "Sending to channel $coverageChannelIndex: $TX_SIGNATURE")
        sendChannelMessage(coverageChannelIndex, TX_SIGNATURE)
        lastManualPingTime = Date()
        _txCount.value++
        log("TX", "Ping sent...", "orange")
        Log.d(TAG, "=== MANUAL PING DONE ===")
    }

    // MARK: - RX (Receive data)

    // Buffer timeout job for fragmented messages
    private var bufferTimeoutJob: Job? = null
    private var lastFragmentSize: Int = 0
    private var bufferTimedOut: Boolean = false  // Flag to force processing after timeout

    private fun handleRxData(data: ByteArray) {
        if (data.isEmpty()) return

        // Add to buffer
        rxBuffer += data
        lastFragmentSize = data.size
        bufferTimedOut = false  // Reset timeout flag on new data

        // Enhanced logging for signature debugging
        if (waitingForSignature) {
            // Log.d(TAG, "DEBUG: Received while waiting for signature. First byte: 0x${"%02x".format(data[0])}")
            if (rxBuffer.isNotEmpty()) {
                // Log.d(TAG, "DEBUG: Buffer first byte: 0x${"%02x".format(rxBuffer[0])}, total size: ${rxBuffer.size}")
            }
        }

        // Cancel pending timeout
        bufferTimeoutJob?.cancel()

        // BLE MTU is typically 20 bytes. If we get less than 20 bytes,
        // this is likely the end of the message. If we get exactly 20,
        // more data might be coming.
        if (data.size < 20) {
            // End of message - process immediately
            processBuffer()
        } else {
            // Might have more data coming - wait a bit
            startBufferTimeout()
        }
    }

    private fun processBuffer() {
        if (rxBuffer.isEmpty()) return

        val firstByte = rxBuffer[0]
        val code = firstByte.toInt() and 0xFF

        // Debug logging for signature detection
        if (waitingForSignature) {
            // Log.d(TAG, "DEBUG: processBuffer() code=0x${"%02x".format(code)}, size=${rxBuffer.size}, waiting=true")
        }

        // Push codes (0x80+) - process entire buffer as one message
        val pushCode = PushCode.fromByte(firstByte)
        if (pushCode != null) {
            handlePush(pushCode, rxBuffer)
            rxBuffer = byteArrayOf()
            return
        }

        // IMPORTANT: Check for SelfInfo without prefix BEFORE ResponseCodes!
        // SelfInfo Type 1=Person, 2=Repeater, 3=Room - but 0x01 is also ResponseCode.ERR
        // Differentiate by buffer size: SelfInfo is always 57+ bytes, ERR is 1-2 bytes
        // Variable-length nodeName field requires waiting for end of message
        if (code in listOf(0x01, 0x02, 0x03)) {
            if (rxBuffer.size >= 57 && (lastFragmentSize < 20 || bufferTimedOut)) {
                // Complete message (end fragment received or timeout) - parse now
                Log.d(TAG, "Parsing as SelfInfo (type=$code, size=${rxBuffer.size}, complete)")
                parseSelfInfoRaw(rxBuffer)
                rxBuffer = byteArrayOf()
                return
            } else if (rxBuffer.size >= 57) {
                // Have minimum bytes but might have more coming (nodeName continues)
                Log.d(TAG, "SelfInfo buffer ${rxBuffer.size} bytes, waiting for end (lastFrag=$lastFragmentSize)")
                startBufferTimeout()
                return
            } else if (code == 0x01 && rxBuffer.size <= 2 && (lastFragmentSize < 20 || bufferTimedOut)) {
                // ERR response: 1 byte (just 0x01) or 2 bytes (0x01 + error code)
                // iOS ignores these - they're normal responses to channel messages
                val errCode = if (rxBuffer.size >= 2) rxBuffer[1].toInt() and 0xFF else 0
                Log.d(TAG, "ERR response (code=$errCode, bytes=${rxBuffer.toHexString()}) - ignored like iOS")
                // Don't show in UI - iOS doesn't either
                rxBuffer = byteArrayOf()
                return
            } else if (rxBuffer.size < 10 && (lastFragmentSize < 20 || bufferTimedOut)) {
                // Small response that's not SelfInfo - likely an error or ACK
                Log.d(TAG, "Small response with code 0x${"%02x".format(code)}, size=${rxBuffer.size} - treating as response")
                rxBuffer = byteArrayOf()
                return
            } else {
                // Waiting for more SelfInfo data
                Log.d(TAG, "Waiting for more SelfInfo data (have ${rxBuffer.size}, need 57)")
                startBufferTimeout()
                return
            }
        }

        // SelfInfo with 0x05 prefix
        if (code == 0x05) {
            // SelfInfo has variable length (nodeName at end)
            // Wait for end of message (fragment < 20 bytes) or timeout
            if (rxBuffer.size >= 58 && (lastFragmentSize < 20 || bufferTimedOut)) {
                Log.d(TAG, "Parsing SelfInfo with 0x05 prefix (${rxBuffer.size} bytes, complete)")
                parseSelfInfo(rxBuffer)
                rxBuffer = byteArrayOf()
                return
            } else if (rxBuffer.size >= 58) {
                Log.d(TAG, "SelfInfo buffer ${rxBuffer.size} bytes, waiting for end (lastFrag=$lastFragmentSize)")
                startBufferTimeout()
                return
            } else {
                Log.d(TAG, "Waiting for more SelfInfo data with 0x05 prefix (have ${rxBuffer.size}, need 58+)")
                startBufferTimeout()
                return
            }
        }

        // Other response codes
        val responseCode = ResponseCode.fromByte(firstByte)
        if (waitingForSignature) {
            // Log.d(TAG, "DEBUG: Checking ResponseCode for 0x${"%02x".format(code)}, result=$responseCode")
        }
        if (responseCode != null) {
            val minSize = when (responseCode) {
                ResponseCode.OK -> 1
                ResponseCode.ERR -> 1  // Should not reach here (handled above)
                ResponseCode.SELF_INFO -> 58  // Should not reach here (handled above)
                ResponseCode.DEVICE_INFO -> 20  // Has variable model field
                ResponseCode.CHANNEL_INFO -> 50
                ResponseCode.SIGNATURE_READY -> 6
                ResponseCode.SIGNATURE -> 65
                else -> 1
            }

            // DeviceInfo has variable length (model at end) - wait for end of message
            if (responseCode == ResponseCode.DEVICE_INFO) {
                if (rxBuffer.size >= minSize && (lastFragmentSize < 20 || bufferTimedOut)) {
                    Log.d(TAG, "Parsing DeviceInfo (${rxBuffer.size} bytes, complete)")
                    handleResponse(responseCode, rxBuffer)
                    rxBuffer = byteArrayOf()
                    return
                } else if (rxBuffer.size >= minSize) {
                    Log.d(TAG, "DeviceInfo buffer ${rxBuffer.size} bytes, waiting for end (lastFrag=$lastFragmentSize)")
                    startBufferTimeout()
                    return
                }
            }

            // SIGNATURE is exactly 65 bytes - wait for complete message
            if (responseCode == ResponseCode.SIGNATURE) {
                // Log.d(TAG, "DEBUG: *** SIGNATURE CODE 0x14 DETECTED! *** Buffer: ${rxBuffer.size} bytes")
                // Log.d(TAG, "DEBUG: Buffer hex: ${rxBuffer.sliceArray(0 until minOf(10, rxBuffer.size)).toHexString()}...")
                if (rxBuffer.size >= 65) {
                    // Log.d(TAG, "DEBUG: Signature complete (${rxBuffer.size}B) - calling handleResponse")
                    handleResponse(responseCode, rxBuffer)
                    rxBuffer = byteArrayOf()
                    return
                } else {
                    // Log.d(TAG, "DEBUG: Signature incomplete (have ${rxBuffer.size}, need 65) - waiting for more")
                    startBufferTimeout()
                    return
                }
            }

            if (rxBuffer.size >= minSize) {
                handleResponse(responseCode, rxBuffer)
                rxBuffer = byteArrayOf()
                return
            } else {
                Log.d(TAG, "Waiting for more data (have ${rxBuffer.size}, need $minSize for $responseCode)")
                startBufferTimeout()
                return
            }
        }

        // Discover ACK (0x09) - acknowledgment for repeatersRequest
        if (code == 0x09) {
            Log.d(TAG, "=== DISCOVER ACK ===")
            Log.d(TAG, "  bytes: ${rxBuffer.toHexString()}")
            log("RX", "Discover ACK - waiting for repeaters...", "blue")
            rxBuffer = byteArrayOf()
            return
        }

        // Mesh/sync messages (0x89) - routing or sync packets
        if (code == 0x89) {
            Log.d(TAG, "Mesh sync: ${rxBuffer.sliceArray(0 until minOf(20, rxBuffer.size)).toHexString()}")
            rxBuffer = byteArrayOf()
            return
        }

        // Known ignorable codes (fragments, routing, etc.)
        val ignorableCodes = setOf(
            0x8D, 0x99, 0xC1, 0xB0, 0xF2, 0x7E, 0x34, 0x67, 0xDC, 0xD3,
            0x6C, 0x74, 0x61, 0x55, 0x9F, 0x35
        )
        if (code in ignorableCodes) {
            // Silently ignore - these are mesh routing fragments
            rxBuffer = byteArrayOf()
            return
        }

        // Unknown code - log and clear
        Log.w(TAG, "Unknown code: 0x${"%02x".format(firstByte)} (${rxBuffer.size} bytes)")
        if (waitingForSignature) {
            // Log.w(TAG, "DEBUG: Unknown data while waiting: ${rxBuffer.sliceArray(0 until minOf(20, rxBuffer.size)).toHexString()}")
        }
        rxBuffer = byteArrayOf()
    }

    private fun startBufferTimeout() {
        bufferTimeoutJob = scope.launch {
            delay(150) // Short wait for more fragments (increased from 100ms)
            if (rxBuffer.isNotEmpty()) {
                Log.d(TAG, "Buffer timeout - processing ${rxBuffer.size} bytes")
                bufferTimedOut = true  // Mark as timed out to force processing
                processBuffer()
            }
        }
    }

    // Track if we're waiting for signature
    private var waitingForSignature = false

    private fun handleResponse(code: ResponseCode, data: ByteArray) {
        Log.d(TAG, "Response: $code (0x${"%02x".format(code.value)}), data[${data.size}]: ${data.sliceArray(0 until minOf(20, data.size)).toHexString()}...")

        when (code) {
            ResponseCode.OK -> {
                log("RX", "OK", "green")
                // If we're waiting for signature and got OK, signature should follow in next message
                if (waitingForSignature) {
                    // Log.d(TAG, "DEBUG: Got OK response, signature (0x14) should follow in next BLE notification")
                }
            }
            ResponseCode.ERR -> log("RX", "Error", "red")
            ResponseCode.SELF_INFO -> parseSelfInfo(data)
            ResponseCode.DEVICE_INFO -> parseDeviceInfo(data)
            ResponseCode.CHANNEL_INFO -> parseChannelInfo(data)
            ResponseCode.CHANNEL_MSG_RECV_V3 -> parseChannelMessageV3(data)
            ResponseCode.CHANNEL_MSG_RECV -> parseChannelMessage(data)
            ResponseCode.SIGNATURE_READY -> parseSignatureReady(data)
            ResponseCode.SIGNATURE -> parseSignature(data)
            ResponseCode.BATTERY_AND_STORAGE -> parseBatteryResponse(data)
            else -> log("RX", "Response: $code", "gray")
        }
    }

    private fun handlePush(code: PushCode, data: ByteArray) {
        Log.d(TAG, "Push: $code, data: ${data.toHexString()}")

        when (code) {
            PushCode.ADVERT, PushCode.NEW_ADVERT -> parseAdvert(data)
            PushCode.LOG_RX_DATA -> parseLogRxData(data)
            PushCode.DISCOVER_REPLY -> parseDiscoverReply(data)
            PushCode.MSG_WAITING -> {
                log("RX", "Message waiting", "blue")
                // Request the message
                sendCommand(CommandCode.SYNC_NEXT_MESSAGE)
            }
        }
    }

    // MARK: - Parsing

    private fun parseSelfInfo(data: ByteArray) {
        // SelfInfo with 0x05 code prefix
        if (data.size < 50) return
        parseSelfInfoRaw(data.sliceArray(1 until data.size))
    }

    /**
     * Parse SelfInfo without code prefix (raw format matching iOS exactly)
     * Format:
     * - Bytes 0: type (1)
     * - Bytes 1: txPower (1)
     * - Bytes 2: maxTxPower (1)
     * - Bytes 3-34: publicKey (32)
     * - Bytes 35-38: advLat (4, LE)
     * - Bytes 39-42: advLon (4, LE)
     * - Bytes 43-46: reserved (4)
     * - Bytes 47-50: radioFreq (4, LE)
     * - Bytes 51-54: radioBw (4, LE)
     * - Byte 55: radioSf (1)
     * - Byte 56: radioCr (1)
     * - Bytes 57+: nodeName (variable, null-terminated)
     */
    private fun parseSelfInfoRaw(data: ByteArray) {
        if (data.size < 35) {
            Log.d(TAG, "SelfInfo too short: ${data.size} bytes")
            return
        }

        try {
            // Parse using direct array access (like iOS) for accuracy
            val type = data[0].toInt() and 0xFF
            val txPower = data[1].toInt() and 0xFF
            val maxTxPower = data[2].toInt() and 0xFF
            val publicKey = data.sliceArray(3 until 35)

            val pubkeyHex = publicKey.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "SelfInfo (${data.size} bytes) - Type: $type, PubKey: ${pubkeyHex.take(16)}...")

            // Parse remaining fields if available
            var advLat = 0
            var advLon = 0
            var radioFreq = 0
            var radioBw = 0
            var radioSf = 0
            var radioCr = 0
            var nodeName = ""

            if (data.size >= 43) {
                advLat = ByteBuffer.wrap(data, 35, 4).order(ByteOrder.LITTLE_ENDIAN).int
                advLon = ByteBuffer.wrap(data, 39, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }

            if (data.size >= 55) {
                // Bytes 43-46 are reserved, skip
                radioFreq = ByteBuffer.wrap(data, 47, 4).order(ByteOrder.LITTLE_ENDIAN).int
                radioBw = ByteBuffer.wrap(data, 51, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }

            if (data.size >= 57) {
                radioSf = data[55].toInt() and 0xFF
                radioCr = data[56].toInt() and 0xFF
            }

            if (data.size > 57) {
                val nameBytes = data.sliceArray(57 until data.size)
                nodeName = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                    .trim { it <= ' ' || it.code == 0 }
            }

            Log.d(TAG, "SelfInfo parsed: nodeName='$nodeName', freq=$radioFreq, sf=$radioSf, cr=$radioCr")

            val info = SelfInfo(
                nodeName = nodeName,
                publicKey = publicKey,
                radioFreq = radioFreq,
                radioSf = radioSf,
                radioCr = radioCr,
                txPower = txPower,
                maxTxPower = maxTxPower,
                advLat = advLat,
                advLon = advLon
            )

            _selfInfo.value = info
            log("RX", "Device: ${if (nodeName.isEmpty()) "Node" else nodeName}", "green")

            // Request DeviceInfo for hardware model
            scope.launch {
                delay(200)
                sendDeviceQuery()
            }

            // Start signing flow after SelfInfo (give device time to be ready)
            scope.launch {
                delay(1000)
                if (!_isVerified.value) {
                    startSigningFlow()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SelfInfo: ${e.message}", e)
        }
    }

    private fun parseSignatureReady(data: ByteArray) {
        // SignatureReady Response (0x13):
        // - Byte 0: Response Code (0x13)
        // - Byte 1: reserved
        // - Bytes 2-5: maxSignDataLen (uint32 LE)
        if (data.size < 6) {
            Log.d(TAG, "SignatureReady too short: ${data.size} bytes")
            return
        }

        // Ignore if signing not in progress (unexpected SignatureReady)
        if (!signingInProgress) {
            // Log.d(TAG, "DEBUG: SignatureReady ignored - signing not in progress")
            return
        }

        // Ignore if we already started processing SignatureReady
        if (waitingForSignature) {
            // Log.d(TAG, "DEBUG: SignatureReady ignored - already processing")
            return
        }

        // Mark as processing IMMEDIATELY to prevent duplicate SignatureReady handling
        waitingForSignature = true

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // Skip code
        buffer.get() // Skip reserved
        maxSignDataLen = buffer.int
        // Log.d(TAG, "DEBUG: SignatureReady received! maxSignDataLen=$maxSignDataLen")
        log("RX", "Sign ready", "green")

        // Continue signing flow with challenge data
        scope.launch {
            delay(300)  // Delay to give device time
            // Log.d(TAG, "DEBUG: Continuing with challenge data...")
            continueSigningWithData()
        }
    }

    private fun parseDeviceInfo(data: ByteArray) {
        // DeviceInfo Response Format (from iOS/MeshCore library):
        // Byte 0: Response Code (0x0D)
        // Byte 1: firmwareVer (int8)
        // Bytes 2-7: reserved (6 bytes)
        // Bytes 8-19: firmware_build_date (12 bytes, C-String, e.g. "19 Feb 2025")
        // Bytes 20+: manufacturerModel (String, remainder of frame = Hardware!)
        if (data.size < 20) {
            Log.d(TAG, "DeviceInfo too short: ${data.size} bytes")
            return
        }

        try {
            val fwVer = data[1].toInt() and 0xFF
            // Bytes 2-7 are reserved, skip them

            // Extract firmware_build_date (bytes 8-19, null-terminated)
            val fwBuildData = data.sliceArray(8 until 20)
            val fwBuild = fwBuildData.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

            // Extract manufacturerModel (bytes 20+, remainder of frame)
            val model = if (data.size > 20) {
                val modelData = data.sliceArray(20 until data.size)
                modelData.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
            } else ""

            Log.d(TAG, "DeviceInfo - fwVer: $fwVer, fwBuild: '$fwBuild', model: '$model'")

            _deviceInfo.value = DeviceInfo(hardware = model.ifEmpty { fwBuild })
            if (_deviceInfo.value?.hardware?.isNotEmpty() == true) {
                log("RX", "Hardware: ${_deviceInfo.value?.hardware}", "gray")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DeviceInfo", e)
        }
    }

    private fun parseChannelInfo(data: ByteArray) {
        // ChannelInfo Response (0x12):
        // - Byte 0: Response Code (0x12)
        // - Byte 1: channelIdx
        // - Bytes 2-33: name (32 bytes null-terminated)
        // - Bytes 34-49: secret (16 bytes)
        if (data.size < 50) {
            Log.d(TAG, "ChannelInfo too short: ${data.size} bytes")
            return
        }

        val channelIdx = data[1].toInt() and 0xFF
        val nameBytes = data.sliceArray(2 until 34)
        val name = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        val secret = data.sliceArray(34 until 50)

        val secretPrefix = secret.take(4).joinToString("") { "%02x".format(it) }
        Log.d(TAG, "ChannelInfo - idx: $channelIdx, name: '$name', secret: $secretPrefix...")

        // Check if this is the channel we're checking during setup
        if (channelIdx != pendingChannelCheck) {
            Log.d(TAG, "Unexpected channel index $channelIdx, expected $pendingChannelCheck")
            return
        }

        // Check if slot is empty or has "coverage" name
        val isEmptyChannel = name.isEmpty() && secret.all { it == 0.toByte() }
        val isCoverageChannel = name == "coverage"
        val keyPrefix = sessionChannelKey.take(4).joinToString("") { "%02x".format(it) }

        if (isEmptyChannel || isCoverageChannel) {
            // Use this slot
            Log.d(TAG, "Using slot $channelIdx for coverage channel")
            sendSetChannel(channelIdx, "coverage", sessionChannelKey)

            scope.launch {
                delay(300)
                coverageChannelIndex = channelIdx
                _coverageChannelReady.value = true
                val action = if (isEmptyChannel) "Created" else "Updated"
                log("TX", "$action Ch$channelIdx ($keyPrefix...)", "green")

                // Start TX timers if in LIVE mode
                if (_privacyMode.value == "live") {
                    startTxTimers()
                }
            }
        } else {
            // Slot has different channel - check next slot
            Log.d(TAG, "Slot $channelIdx has channel '$name' - checking next")
            if (channelIdx < 7) {
                scope.launch {
                    delay(200)
                    sendGetChannel(channelIdx + 1)
                }
            } else {
                // All slots full - overwrite slot 7
                Log.d(TAG, "All slots full - overwriting slot 7")
                sendSetChannel(7, "coverage", sessionChannelKey)

                scope.launch {
                    delay(300)
                    coverageChannelIndex = 7
                    _coverageChannelReady.value = true
                    log("TX", "Overwrote Ch7 ($keyPrefix...)", "orange")

                    if (_privacyMode.value == "live") {
                        startTxTimers()
                    }
                }
            }
        }
    }

    private fun parseChannelMessageV3(data: ByteArray) {
        // iOS ignores this message type (chat feature removed)
        // Real coverage RX data comes via LOG_RX_DATA (0x88)
        Log.d(TAG, "ChannelMsgV3 ignored (${data.size} bytes) - coverage data via LOG_RX_DATA")
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
        // iOS format:
        // [0x88][SNR*4: 1 byte signed][RSSI: 1 byte signed][header: 1 byte][pathLen: 1 byte][path: pathLen bytes][payload...]
        if (data.size < 5) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip code byte (0x88)

            // SNR is first (multiplied by 4), then RSSI
            val snrRaw = buffer.get().toInt()  // Signed byte
            val snr = snrRaw / 4.0f
            val rssi = normalizeRssi(buffer.get().toInt())  // Signed byte
            val header = buffer.get().toInt() and 0xFF
            val pathLen = buffer.get().toInt() and 0xFF

            if (data.size < 5 + pathLen) return

            // Parse path - EACH HOP IS 1 BYTE! (not 4 bytes)
            val path = mutableListOf<String>()
            repeat(pathLen) {
                if (buffer.remaining() >= 1) {
                    val hopByte = buffer.get().toInt() and 0xFF
                    if (hopByte != 0) {  // Filter out zeros like iOS
                        path.add("%02x".format(hopByte))
                    }
                }
            }

            // Only process RX with at least 1 hop (not direct reception)
            if (path.isEmpty()) {
                Log.d(TAG, "LogRxData: path empty (pathLen=$pathLen), skipping")
                return
            }

            // Extract payload (after path)
            val payloadStart = 5 + pathLen
            val payload = if (data.size > payloadStart) {
                data.sliceArray(payloadStart until data.size)
            } else {
                byteArrayOf()
            }

            // Get payload type from header: (header >> 2) & 0x0F
            val payloadType = (header shr 2) and 0x0F
            Log.d(TAG, "LogRxData: rssi=$rssi snr=$snr path=${path.joinToString("→")} payloadType=$payloadType payload=${payload.size}B")

            // Check if this is our TX bounced back from a repeater (GRP_TXT = 0x05)
            // Decrypt the payload and verify it contains our signature
            if (payloadType == 0x05 && payload.isNotEmpty()) {
                Log.d(TAG, "GRP_TXT received! pendingTx=$pendingTx path=$path payload=${payload.size}B")
                if (pendingTx && path.isNotEmpty()) {
                    val verified = verifyTxResponse(payload)
                    Log.d(TAG, "TX verification result: $verified")
                    if (verified) {
                        pendingTx = false
                        pendingTxTimeout?.cancel()
                        val repeater = path.last()  // Last hop is the repeater that sent it back
                        Log.d(TAG, "=== TX CONFIRMED by repeater $repeater @ $rssi dBm ===")
                        log("TX", "Confirmed via $repeater ($rssi dBm)", "green")
                        feedbackManager?.playTxConfirm()
                        return  // Don't process as normal RX
                    }
                }
            }

            // Check if payload is an Advert (payloadType 0x00 or 0x01, min 101 bytes)
            // Only parse as advert if it's NOT a GRP_TXT and has the right size
            if (payloadType != 0x05 && payload.size >= 101) {
                parseAdvertFromPayload(payload, rssi, path)
            }

            // Create RX packet
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

            // Show payload type in activity log for debugging
            val typeLabel = when (payloadType) {
                0x05 -> "TXT"
                0x00, 0x01 -> "ADV"
                else -> "T$payloadType"
            }
            log("RX", "$typeLabel ${path.joinToString("→")} $rssi dBm", "green")
            feedbackManager?.playRx()

            // Reset TX timers on RX
            resetTxTimers()

            // Create sample for upload
            createRxSample(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LogRxData", e)
        }
    }

    private fun parseAdvertFromPayload(payload: ByteArray, rssi: Int, path: List<String>) {
        // Advert payload format (matches iOS):
        // [0-31]    publicKey (32 bytes)
        // [32-35]   timestamp (UInt32 LE)
        // [36-99]   signature (64 bytes)
        // [100+]    appData:
        //           [0] flags: bits 0-3 = type, bit 4 = has lat/lon, bit 7 = has name
        //           [1-4] lat (Int32 LE) if flag 0x10
        //           [5-8] lon (Int32 LE) if flag 0x10
        //           [9+] name (string) if flag 0x80

        Log.d(TAG, "=== PARSING ADVERT FROM PAYLOAD ===")
        Log.d(TAG, "  payload size: ${payload.size}")
        Log.d(TAG, "  path: ${path.joinToString("→")}")
        Log.d(TAG, "  rssi: $rssi")

        try {
            val publicKey = payload.sliceArray(0 until 32)
            val pubkeyHex = publicKey.toHexString()
            val shortId = pubkeyHex.take(4)

            // appData starts at byte 100
            val flags = payload[100].toInt() and 0xFF
            val nodeType = flags and 0x0F  // bits 0-3
            val hasLatLon = (flags and 0x10) != 0  // bit 4
            val hasName = (flags and 0x80) != 0    // bit 7

            Log.d(TAG, "  flags: 0x${"%02x".format(flags)}, type: $nodeType, hasLatLon: $hasLatLon, hasName: $hasName")

            val typeNames = mapOf(1 to "Person", 2 to "Repeater", 3 to "Room")
            val typeName = typeNames[nodeType] ?: "Type$nodeType"

            var name = ""
            var lat = 0.0
            var lon = 0.0

            var offset = 101
            if (hasLatLon && payload.size >= offset + 8) {
                val buffer = ByteBuffer.wrap(payload, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
                lat = buffer.int / 1e7
                lon = buffer.int / 1e7
                offset += 8
                Log.d(TAG, "  lat/lon: $lat, $lon")
            }

            if (hasName && payload.size > offset) {
                val nameBytes = payload.sliceArray(offset until payload.size)
                name = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                Log.d(TAG, "  name: '$name'")
            }

            val displayName = if (name.isNotEmpty()) name else shortId
            Log.d(TAG, "=== ADVERT PARSED: $typeName '$displayName' via ${path.joinToString("→")} @ $rssi dBm ===")

            // Log repeater discovery - make it visible!
            if (nodeType == 2) {
                log("RX", "📡 Repeater: $displayName ($rssi dBm)", "green")
                feedbackManager?.playAdvert()
            } else {
                log("RX", "$typeName: $displayName ($rssi dBm)", "blue")
            }

            // TODO: Upload advert to server
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Advert from payload", e)
        }
    }

    /**
     * Normalize RSSI to always be negative.
     * LoRa RSSI is always negative (typically -20 to -120 dBm).
     * The firmware may send it as:
     * - Signed byte: -53 comes as 0xCB = -53 (correct)
     * - Magnitude: 53 comes as 0x35 = 53 (needs negation)
     */
    private fun normalizeRssi(rawValue: Int): Int {
        return if (rawValue > 0) -rawValue else rawValue
    }

    private fun parseSignature(data: ByteArray) {
        // Signature Response (0x14):
        // - Byte 0: Response Code (0x14)
        // - Bytes 1-64: signature (64 bytes Ed25519)
        // Log.d(TAG, "DEBUG: parseSignature called with ${data.size} bytes!")
        waitingForSignature = false

        if (data.size < 65) {
            // Log.d(TAG, "DEBUG: Signature too short: ${data.size} bytes, expected 65")
            log("RX", "Signature incomplete (${data.size}B)", "red")
            return
        }

        val signature = data.sliceArray(1 until 65)
        val sigHex = signature.joinToString("") { "%02x".format(it) }
        // Log.d(TAG, "DEBUG: SIGNATURE RECEIVED! ${data.size}B")
        // Log.d(TAG, "DEBUG: Signature hex: $sigHex")
        log("RX", "Signature OK!", "green")

        // Verify and create server session
        verifySignatureAndCreateSession(signature)
    }

    // MARK: - Battery

    private fun parseBatteryResponse(data: ByteArray) {
        // Format:
        // [0] code (12)
        // [1-2] battery_millivolts (uint16 LE)
        // [3-6] storage_used_kb (uint32 LE)
        // [7-10] storage_total_kb (uint32 LE)
        if (data.size < 11) return

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // Skip code
        val batteryMv = buffer.short.toInt() and 0xFFFF
        val storageUsedKb = buffer.int
        val storageTotalKb = buffer.int

        // Calculate percentage: 3200mV = 0%, 4200mV = 100%
        val minMv = 3200
        val maxMv = 4200
        var percent = ((batteryMv - minMv) * 100) / (maxMv - minMv)
        percent = percent.coerceIn(0, 100)

        Log.d(TAG, "BATTERY: ${batteryMv}mV = $percent% | Storage: $storageUsedKb/$storageTotalKb KB")

        _batteryMillivolts.value = batteryMv
        _batteryPercent.value = percent
    }

    fun sendBatteryRequest() {
        sendCommand(CommandCode.GET_BATTERY_AND_STORAGE)
        Log.d(TAG, "Battery request sent")
    }

    private fun startBatteryTimer() {
        batteryTimerJob?.cancel()
        // Request immediately
        sendBatteryRequest()
        // Then every 60 seconds
        batteryTimerJob = scope.launch {
            while (isActive) {
                delay(60_000)
                if (_isConnected.value) {
                    sendBatteryRequest()
                }
            }
        }
    }

    private fun stopBatteryTimer() {
        batteryTimerJob?.cancel()
        batteryTimerJob = null
    }

    // MARK: - Discover Reply

    private fun parseDiscoverReply(data: ByteArray) {
        // Format (from connect.html):
        // [0x8E][RSSI: 1 byte signed][SNR: 1 byte signed][...][pubkey: 32 bytes at offset 10-41]
        Log.d(TAG, "Discover reply received (${data.size} bytes)")

        if (data.size < 42) {
            Log.d(TAG, "Discover reply too short: ${data.size} bytes")
            return
        }

        // Parse RSSI and SNR (signed bytes)
        val rssiRaw = data[1].toInt()
        val rssi = if (rssiRaw > 127) rssiRaw - 256 else -rssiRaw
        val snrRaw = data[2].toInt()
        val snr = if (snrRaw > 127) snrRaw - 256 else snrRaw

        // Parse pubkey (bytes 10-41)
        val pubkeyBytes = data.sliceArray(10 until 42)
        val pubkeyHex = pubkeyBytes.joinToString("") { "%02x".format(it) }
        val shortId = pubkeyHex.take(4)

        Log.d(TAG, "Repeater found: $shortId RSSI=${rssi}dBm SNR=${snr}dB")
        log("RX", "Repeater $shortId (${rssi}dBm, ${snr}dB)", "green")
        feedbackManager?.playDiscoverReply()

        // Add sample for upload
        currentH3?.let { h3 ->
            val sample = Sample(
                type = "rx",
                h3 = h3,
                rssi = rssi,
                snr = snr.toFloat(),
                path = listOf(shortId),
                timestamp = System.currentTimeMillis(),
                privacyMode = _privacyMode.value
            )
            sampleRepository?.addSample(sample)
        }
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

    /**
     * Decrypt a channel message payload using AES-ECB.
     * Payload format: [1 byte skip][2 bytes skip][encrypted data...]
     * Returns decrypted text or null if decryption fails.
     */
    private fun decryptChannelPayload(payload: ByteArray): String? {
        try {
            if (payload.size < 3) return null

            // Skip first 3 bytes (channel_hash: 1B, MAC: 2B)
            val encrypted = payload.sliceArray(3 until payload.size)
            Log.d(TAG, "AES: encrypted=${encrypted.size}B key=${sessionChannelKey.take(4).toHexString()}")

            // AES-ECB requires data to be multiple of 16 bytes
            if (encrypted.isEmpty() || encrypted.size % 16 != 0) {
                Log.d(TAG, "AES: invalid length ${encrypted.size}")
                return null
            }

            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(sessionChannelKey, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)

            val decrypted = cipher.doFinal(encrypted)
            Log.d(TAG, "AES: decrypted raw=${decrypted.take(20).toHexString()}")

            // Decrypted format: [timestamp: 4 bytes][null][text...]
            // Skip first 4 bytes (timestamp), then skip leading nulls, then extract text
            var textStart = 4  // Skip timestamp
            while (textStart < decrypted.size && decrypted[textStart] == 0.toByte()) {
                textStart++  // Skip nulls after timestamp
            }
            var textEnd = decrypted.size
            while (textEnd > textStart && decrypted[textEnd - 1] == 0.toByte()) {
                textEnd--  // Remove trailing nulls
            }

            val textBytes = decrypted.sliceArray(textStart until textEnd)
            val result = textBytes.decodeToString()
            Log.d(TAG, "AES: text='$result' (bytes $textStart..$textEnd)")
            return result
        } catch (e: Exception) {
            Log.d(TAG, "Decrypt failed: ${e.message}")
            return null
        }
    }

    /**
     * Verify if a received GRP_TXT payload is our own TX bounced back.
     * Checks if decrypted content contains our TX_SIGNATURE.
     */
    private fun verifyTxResponse(payload: ByteArray): Boolean {
        val decrypted = decryptChannelPayload(payload) ?: return false
        Log.d(TAG, "Decrypted payload: '$decrypted'")
        // Check for both auto TX signature (=]) and manual ping signature (=/)
        return decrypted.contains(TX_SIGNATURE) || decrypted.contains("=/")
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
        coverageChannelIndex = 0
        _isVerified.value = false
        _sessionVerified.value = false
        // Keep session token - it's valid for 24h even after disconnect
        // _sessionToken = null
        pendingChallenge = null
        pendingSignature = null
        maxSignDataLen = 0
        signingInProgress = false
        signingRetryCount = 0
        // Note: autoReconnectCount is NOT reset here - it persists across reconnects
        // and is only reset on successful verification
        appStartSent = false
        waitingForSignature = false
        lastRxTime = null
        lastTxTime = null
        lastManualPingTime = null
        lastManualDiscoverTime = null
    }

    fun setPrivacyMode(mode: String) {
        val oldMode = _privacyMode.value
        _privacyMode.value = mode

        if (mode == oldMode) return

        Log.d(TAG, "Privacy mode changed to: $mode")

        if (mode == "live") {
            // Entering LIVE mode - start TX timers if channel is ready
            if (_isConnected.value && _coverageChannelReady.value) {
                log("TX", "Live mode - TX enabled", "green")
                startTxTimers()
            }
        } else {
            // Leaving LIVE mode - stop all TX activity
            if (_isTxActive.value || discoverTimerJob != null) {
                log("TX", "TX disabled ($mode mode)", "gray")
            }
            stopTxTimers()
        }
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
