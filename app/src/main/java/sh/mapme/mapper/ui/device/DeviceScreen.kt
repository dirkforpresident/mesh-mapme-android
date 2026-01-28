package sh.mapme.mapper.ui.device

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import sh.mapme.mapper.Constants
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.MapmeApp
import sh.mapme.mapper.R
import sh.mapme.mapper.data.BleManager

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    viewModel: MainViewModel = viewModel()
) {
    val isConnected by viewModel.isConnected.collectAsState()

    // BLE and Notification Permissions
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    if (!permissionState.allPermissionsGranted) {
        PermissionScreen(
            onRequestPermission = { permissionState.launchMultiplePermissionRequest() }
        )
    } else if (isConnected) {
        ConnectedScreen(viewModel)
    } else {
        ScanningScreen(viewModel)
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_device),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Bluetooth Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "We need Bluetooth access to connect to your MeshCore device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun ScanningScreen(viewModel: MainViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(20.dp))

            // Animated antenna icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) {
                if (isScanning) {
                    repeat(3) { index ->
                        Surface(
                            modifier = Modifier
                                .size(120.dp)
                                .scale(scale + index * 0.3f),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = (0.3f - index * 0.1f).coerceAtLeast(0f)
                            )
                        ) {}
                    }
                }

                Surface(
                    modifier = Modifier.size(100.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_device),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxSize(),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Text(
                text = if (isScanning) "Scanning..." else if (discoveredDevices.isEmpty()) "No Devices Found" else "Devices Found",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isScanning) "Looking for MeshCore devices nearby"
                else "Make sure your MeshCore device is powered on",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Button(
                onClick = {
                    if (isScanning) viewModel.stopScanning() else viewModel.startScanning()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(if (isScanning) "Stop Scanning" else "Start Scanning")
            }
        }

        // Device list
        items(discoveredDevices) { device ->
            DeviceCard(device = device) {
                viewModel.connect(device)
            }
        }
    }
}

@Composable
fun DeviceCard(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_device),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Tap to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_home), // Use chevron if available
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(viewModel: MainViewModel) {
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val selfInfo by viewModel.selfInfo.collectAsState()
    val rxCount by viewModel.rxCount.collectAsState()
    val visitedHexes by viewModel.visitedHexes.collectAsState()
    val sessionUploaded by viewModel.sessionUploaded.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val privacyMode by viewModel.privacyMode.collectAsState()
    val recentRxPackets by viewModel.recentRxPackets.collectAsState()
    val debugLog by viewModel.debugLog.collectAsState()
    val pendingSamples by viewModel.pendingSamples.collectAsState()
    val sessionVerified by viewModel.sessionVerified.collectAsState()
    val isTxActive by viewModel.isTxActive.collectAsState()
    val coverageChannelReady by viewModel.coverageChannelReady.collectAsState()

    var showDetails by remember { mutableStateOf(false) }
    var showActivityLog by remember { mutableStateOf(false) }

    // Feedback settings
    val feedbackManager = MapmeApp.instance.feedbackManager
    var soundEnabled by remember { mutableStateOf(feedbackManager.soundEnabled) }
    var vibrateEnabled by remember { mutableStateOf(feedbackManager.vibrateEnabled) }

    // Auto-start GPS tracking when connected
    LaunchedEffect(Unit) {
        if (!isTracking) {
            viewModel.startTracking()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Device Header
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(50.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_device),
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selfInfo?.nodeName?.ifEmpty { null } ?: connectedDeviceName ?: "MeshCore",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Connected", style = MaterialTheme.typography.labelSmall) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            if (sessionVerified) "Verified" else "Unverified",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (sessionVerified)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else
                                            MaterialTheme.colorScheme.errorContainer
                                    )
                                )
                            }
                        }

                        IconButton(onClick = { showDetails = !showDetails }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_home),
                                contentDescription = "Details"
                            )
                        }
                    }

                    if (showDetails) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        selfInfo?.let { info ->
                            val freqMHz = info.radioFreq / 1000.0
                            DetailRow("Frequency", "%.3f MHz".format(freqMHz))
                            DetailRow("Radio", "SF${info.radioSf} / CR${info.radioCr}")
                            DetailRow("TX Power", "${info.txPower} / ${info.maxTxPower} dBm")
                        }

                        val deviceInfo = viewModel.deviceInfo.collectAsState().value
                        deviceInfo?.let { info ->
                            if (info.hardware.isNotEmpty()) {
                                DetailRow("Hardware", info.hardware)
                            }
                        }
                    }
                }
            }
        }

        // START Button (when not verified)
        if (!sessionVerified) {
            item {
                Button(
                    onClick = { viewModel.startVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("START", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Feedback Settings
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Feedback",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sound")
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = {
                                soundEnabled = it
                                feedbackManager.soundEnabled = it
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Vibration")
                        Switch(
                            checked = vibrateEnabled,
                            onCheckedChange = {
                                vibrateEnabled = it
                                feedbackManager.vibrateEnabled = it
                            }
                        )
                    }
                }
            }
        }

        // Disconnect Button (always visible)
        item {
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
        }

        // Stats Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(Modifier.weight(1f), "location.fill", if (isTracking) "Live" else "Off", "GPS",
                    if (isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                StatusCard(Modifier.weight(1f), "arrow.down", "$rxCount", "RX",
                    if (rxCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                StatusCard(Modifier.weight(1f), "hexagon", "${visitedHexes.size}", "Hexes",
                    MaterialTheme.colorScheme.secondary)
                StatusCard(Modifier.weight(1f), "arrow.up", "$sessionUploaded", "Up",
                    if (sessionUploaded > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Privacy Mode
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Privacy Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("live" to "Live", "normal" to "Normal", "anonym" to "Ghost").forEach { (mode, label) ->
                            FilterChip(
                                selected = privacyMode == mode,
                                onClick = { viewModel.setPrivacyMode(mode) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = when (privacyMode) {
                            "live" -> "Real-time TX - actively pings to discover coverage"
                            "normal" -> "Data uploaded with 3 hour delay"
                            else -> "Data uploaded with 24 hour delay"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // TX Controls (Live mode)
        if (privacyMode == "live") {
            item {
                // Cooldown state that updates every second
                var discoverCooldown by remember { mutableStateOf(0) }
                var pingCooldown by remember { mutableStateOf(0) }

                LaunchedEffect(Unit) {
                    while (true) {
                        discoverCooldown = viewModel.getDiscoverCooldown()
                        pingCooldown = viewModel.getPingCooldown()
                        kotlinx.coroutines.delay(1000)
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Status row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TX Controls",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Auto-Discover indicator
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                ) {}
                                Text(
                                    text = "Auto 30s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // TX Active indicator
                                if (isTxActive) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        modifier = Modifier.size(8.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.tertiary
                                    ) {}
                                    Text(
                                        text = "TX Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Info text
                        Text(
                            text = if (isTxActive)
                                "Auto-TX running (23-42s interval) - stops on RX"
                            else
                                "Auto-TX starts after 5 min without RX",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Buttons with cooldown
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Discover button
                            Button(
                                onClick = { viewModel.sendDiscover() },
                                enabled = discoverCooldown == 0,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (discoverCooldown > 0) {
                                    Text("DISCOVER (${discoverCooldown}s)")
                                } else {
                                    Text("DISCOVER")
                                }
                            }

                            // Ping button - show why disabled
                            val currentH3 = viewModel.currentH3.collectAsState().value
                            val canPing = coverageChannelReady && currentH3 != null && pingCooldown == 0

                            Button(
                                onClick = { viewModel.sendPing() },
                                enabled = canPing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                when {
                                    !coverageChannelReady -> Text("NO CHANNEL")
                                    currentH3 == null -> Text("NO GPS")
                                    pingCooldown > 0 -> Text("PING (${pingCooldown}s)")
                                    else -> Text("PING H3")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pending Samples
        if (pendingSamples.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${pendingSamples.size} samples waiting",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (sessionVerified) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        TextButton(
                            onClick = { viewModel.clearPendingSamples() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // Recent RX
        if (recentRxPackets.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Recent RX",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        recentRxPackets.take(3).forEach { packet ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = rssiColor(packet.rssi)
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = packet.path.joinToString("→"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${packet.rssi} dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Activity Log
        if (debugLog.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showActivityLog = !showActivityLog }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Activity Log",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (showActivityLog) "▲" else "▼",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        if (showActivityLog) {
                            Spacer(modifier = Modifier.height(8.dp))
                            debugLog.take(8).forEach { entry ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.timeString,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = logColor(entry.color)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    modifier: Modifier,
    icon: String,
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun rssiColor(rssi: Int): androidx.compose.ui.graphics.Color {
    return when {
        rssi > -80 -> MaterialTheme.colorScheme.primary
        rssi > -100 -> MaterialTheme.colorScheme.secondary
        rssi > -115 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
fun logColor(color: String): androidx.compose.ui.graphics.Color {
    return when (color) {
        "green" -> MaterialTheme.colorScheme.primary
        "orange" -> MaterialTheme.colorScheme.tertiary
        "blue" -> MaterialTheme.colorScheme.secondary
        "red" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
}
