package sh.mapme.mapper.ui.map

import android.Manifest
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.MapmeApp
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentLocation by viewModel.currentLocation.collectAsState()
    val visitedHexes by viewModel.visitedHexes.collectAsState()
    val serverHexes by viewModel.serverHexes.collectAsState()
    val rxCount by viewModel.rxCount.collectAsState()
    val sessionUploaded by viewModel.sessionUploaded.collectAsState()
    val recentRxPackets by viewModel.recentRxPackets.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val batteryMillivolts by viewModel.batteryMillivolts.collectAsState()

    var showStats by remember { mutableStateOf(true) }
    var useDarkMap by remember { mutableStateOf(true) }
    var showActivityFeed by remember { mutableStateOf(false) }

    // Location permission
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Configure osmdroid once
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }

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
    val defaultLocation = GeoPoint(53.5511, 9.9937)

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

    // Remember the map view
    val mapView = remember {
        // Ensure config is set before creating map
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }

        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(defaultLocation)

            // Add location overlay with proper provider
            try {
                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), this)
                locationOverlay.enableMyLocation()
                overlays.add(locationOverlay)
            } catch (e: Exception) {
                // Ignore if location overlay fails
            }
        }
    }

    // Update map when dark mode changes
    LaunchedEffect(useDarkMap) {
        if (useDarkMap) {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.overlayManager.tilesOverlay.setColorFilter(
                android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        } else {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
        mapView.invalidate()
    }

    // Center map on first location fix only
    var hasInitialLocation by remember { mutableStateOf(false) }
    LaunchedEffect(currentLocation) {
        if (!hasInitialLocation && currentLocation != null) {
            hasInitialLocation = true
            mapView.controller.animateTo(GeoPoint(currentLocation!!.latitude, currentLocation!!.longitude))
            mapView.controller.setZoom(16.0)
        }
    }

    // Update hex overlays
    LaunchedEffect(serverHexes, visitedHexes, sessionHexData.keys.toList()) {
        try {
            // Remove old polygon overlays (keep location overlay)
            mapView.overlays.removeAll { it is Polygon }

            // Server hexes (from API)
            serverHexes.forEach { hex ->
                try {
                    val boundary = MapmeApp.instance.locationService.getH3Boundary(hex.h)
                    boundary?.let { coords ->
                        val hexColor = hex.getColor()
                        val polygon = Polygon().apply {
                            points = coords.map { GeoPoint(it.first, it.second) }
                            fillPaint.color = AndroidColor.argb(
                                128,
                                (hexColor.r * 255).toInt(),
                                (hexColor.g * 255).toInt(),
                                (hexColor.b * 255).toInt()
                            )
                            outlinePaint.color = AndroidColor.rgb(
                                (hexColor.r * 255).toInt(),
                                (hexColor.g * 255).toInt(),
                                (hexColor.b * 255).toInt()
                            )
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(polygon)
                    }
                } catch (e: Exception) { /* skip invalid hex */ }
            }

            // Session hexes (visited this session)
            visitedHexes.forEach { hexId ->
                try {
                    val boundary = MapmeApp.instance.locationService.getH3Boundary(hexId)
                    boundary?.let { coords ->
                        val hasSignal = sessionHexData.containsKey(hexId)
                        val polygon = Polygon().apply {
                            points = coords.map { GeoPoint(it.first, it.second) }
                            fillPaint.color = if (hasSignal) {
                                AndroidColor.argb(77, 0, 255, 0) // Green with alpha
                            } else {
                                AndroidColor.argb(51, 0, 0, 255) // Blue with alpha
                            }
                            outlinePaint.color = if (hasSignal) {
                                AndroidColor.GREEN
                            } else {
                                AndroidColor.BLUE
                            }
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(polygon)
                    }
                } catch (e: Exception) { /* skip invalid hex */ }
            }

            mapView.invalidate()
        } catch (e: Exception) { /* ignore overlay errors */ }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // OpenStreetMap
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

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
                        Text("^", style = MaterialTheme.typography.labelSmall)
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
                    if (batteryMillivolts > 0) {
                        StatRow(label = "Batt", value = "${batteryMillivolts}mV")
                    }
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
                    Text("v", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Activity feed (bottom-left) - collapsible
        if (recentRxPackets.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.BottomStart)
                    .clickable { showActivityFeed = !showActivityFeed },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showActivityFeed) "v" else ">",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "RX (${recentRxPackets.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (showActivityFeed) {
                        Spacer(modifier = Modifier.height(4.dp))
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
                                    text = packet.path.joinToString(">"),
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
        }

        // Map controls (top-right): location + dark/light toggle
        Row(
            modifier = Modifier
                .padding(12.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Center on my location
            Card(
                modifier = Modifier
                    .clickable {
                        currentLocation?.let {
                            mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                            mapView.controller.setZoom(16.0)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Text(
                    text = "GPS",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            // Map style toggle
            Card(
                modifier = Modifier
                    .clickable { useDarkMap = !useDarkMap },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Text(
                    text = if (useDarkMap) "Light" else "Dark",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
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

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            mapView.onDetach()
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
