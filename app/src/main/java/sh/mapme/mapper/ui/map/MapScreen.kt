package sh.mapme.mapper.ui.map

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.MapmeApp

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(
    viewModel: MainViewModel = viewModel()
) {
    val currentLocation by viewModel.currentLocation.collectAsState()
    val visitedHexes by viewModel.visitedHexes.collectAsState()
    val serverHexes by viewModel.serverHexes.collectAsState()
    val rxCount by viewModel.rxCount.collectAsState()
    val sessionUploaded by viewModel.sessionUploaded.collectAsState()
    val recentRxPackets by viewModel.recentRxPackets.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()

    var showStats by remember { mutableStateOf(true) }
    var useDarkMap by remember { mutableStateOf(true) }

    // Location permission
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            viewModel.updatePermissions()
            viewModel.startTracking()
        }
    }

    // Default to Hamburg if no location
    val defaultLocation = LatLng(53.5511, 9.9937)
    val currentLatLng = currentLocation?.let { LatLng(it.latitude, it.longitude) } ?: defaultLocation

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 14f)
    }

    // Update camera when location changes
    LaunchedEffect(currentLocation) {
        currentLocation?.let {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLng(LatLng(it.latitude, it.longitude))
            )
        }
    }

    // Session hex data for tracking strongest signal
    val sessionHexData = remember { mutableStateMapOf<String, Pair<String, Int>>() }

    // Update session hex data when RX packets come in
    LaunchedEffect(recentRxPackets) {
        recentRxPackets.firstOrNull()?.let { packet ->
            val currentH3 = viewModel.currentH3.value ?: return@let
            val lastHop = packet.path.lastOrNull() ?: return@let

            val existing = sessionHexData[currentH3]
            if (existing == null || packet.rssi > existing.second) {
                sessionHexData[currentH3] = Pair(lastHop, packet.rssi)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Google Map
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = if (useDarkMap) MapType.HYBRID else MapType.NORMAL,
                isMyLocationEnabled = locationPermissions.allPermissionsGranted
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = true,
                compassEnabled = true
            )
        ) {
            // Server hexes (from API)
            serverHexes.forEach { hex ->
                val boundary = MapmeApp.instance.locationService.getH3Boundary(hex.h)
                boundary?.let { coords ->
                    val latLngs = coords.map { LatLng(it.first, it.second) }
                    Polygon(
                        points = latLngs,
                        fillColor = Color(hex.color.r, hex.color.g, hex.color.b, 0.5f),
                        strokeColor = Color(hex.color.r, hex.color.g, hex.color.b, 1f),
                        strokeWidth = 2f
                    )
                }
            }

            // Session hexes (visited this session)
            visitedHexes.forEach { hexId ->
                val boundary = MapmeApp.instance.locationService.getH3Boundary(hexId)
                boundary?.let { coords ->
                    val latLngs = coords.map { LatLng(it.first, it.second) }
                    val hasSignal = sessionHexData.containsKey(hexId)
                    Polygon(
                        points = latLngs,
                        fillColor = if (hasSignal) Color(0f, 1f, 0f, 0.3f) else Color(0f, 0f, 1f, 0.2f),
                        strokeColor = if (hasSignal) Color.Green else Color.Blue,
                        strokeWidth = 3f
                    )

                    // Show strongest ID in hex
                    sessionHexData[hexId]?.let { (nodeId, _) ->
                        val center = MapmeApp.instance.locationService.getH3Center(hexId)
                        center?.let { (lat, lng) ->
                            Marker(
                                state = MarkerState(position = LatLng(lat, lng)),
                                title = nodeId,
                                snippet = "Strongest signal"
                            )
                        }
                    }
                }
            }
        }

        // Stats overlay (top-left)
        if (showStats) {
            Card(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
                    .clickable { showStats = false },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.width(100.dp)
                    ) {
                        Text(
                            text = "Session",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("▲", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow(label = "Hexes", value = "${visitedHexes.size}")
                    StatRow(label = "RX", value = "$rxCount")
                    recentRxPackets.firstOrNull()?.let { packet ->
                        StatRow(
                            label = "dBm",
                            value = "${packet.rssi}",
                            color = rssiColor(packet.rssi)
                        )
                    }
                    StatRow(label = "Up", value = "$sessionUploaded")
                }
            }
        } else {
            // Collapsed stats
            Card(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
                    .clickable { showStats = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${visitedHexes.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("▼", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Activity feed (bottom-left)
        if (recentRxPackets.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.BottomStart),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    recentRxPackets.take(3).forEach { packet ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = MaterialTheme.shapes.small,
                                color = rssiColor(packet.rssi)
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = packet.path.joinToString("→"),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${packet.rssi}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Map style toggle (bottom-right)
        Card(
            modifier = Modifier
                .padding(12.dp)
                .align(Alignment.BottomEnd)
                .clickable { useDarkMap = !useDarkMap },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            )
        ) {
            Text(
                text = if (useDarkMap) "☀" else "🌙",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }

        // GPS indicator
        if (!isTracking) {
            Card(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .align(Alignment.TopCenter),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "GPS Paused",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun StatRow(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun rssiColor(rssi: Int): Color {
    return when {
        rssi > -80 -> Color(0xFF22C55E) // Green
        rssi > -100 -> Color(0xFF84CC16) // Lime
        rssi > -115 -> Color(0xFFF97316) // Orange
        else -> Color(0xFFEF4444) // Red
    }
}
