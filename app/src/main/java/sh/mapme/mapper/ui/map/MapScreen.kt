package sh.mapme.mapper.ui.map

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import sh.mapme.mapper.data.Ghost
import sh.mapme.mapper.data.GhostKind
import sh.mapme.mapper.data.GhostMath
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.MapmeApp
import sh.mapme.mapper.data.HexColor
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
    val isConnected by viewModel.isConnected.collectAsState()
    val privacyMode by viewModel.privacyMode.collectAsState()
    val coverageChannelReady by viewModel.coverageChannelReady.collectAsState()
    val currentH3 by viewModel.currentH3.collectAsState()
    val ghosts by viewModel.ghosts.collectAsState()

    var showStats by remember { mutableStateOf(true) }
    // Tap auf Monsti → Info-Card (wie Webkarten-Popup / iOS-Sheet)
    val selectedGhost = remember { mutableStateOf<Ghost?>(null) }
    val spriteVersion by sh.mapme.mapper.ui.game.MonstiSprites.version.collectAsState()
    val useDarkMap by viewModel.mapUseDarkMode.collectAsState()
    var showActivityFeed by viewModel.mapShowActivityFeed
    val followLocation by viewModel.mapFollowLocation.collectAsState()

    // Location permission with prominent disclosure
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    var showLocationDisclosure by remember { mutableStateOf(false) }
    var showBackgroundLocationDisclosure by remember { mutableStateOf(false) }
    val hasBackgroundLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // Background location permission (must be requested separately on Android 11+)
    val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberPermissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else null

    // Configure osmdroid once
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }

        if (!locationPermissions.allPermissionsGranted) {
            showLocationDisclosure = true
        } else if (!hasBackgroundLocation) {
            showBackgroundLocationDisclosure = true
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            viewModel.updatePermissions()
            viewModel.startTracking()
            // After foreground location granted, ask for background
            if (!hasBackgroundLocation) {
                showBackgroundLocationDisclosure = true
            }
        }
    }

    // Prominent disclosure dialog for foreground location
    if (showLocationDisclosure) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Location Access Required") },
            text = {
                Text("MeshCore Coverage Mapper needs access to your GPS location to record signal coverage while you drive. Your location is paired with signal strength data to build a community coverage map on mapme.sh.\n\nYou can control visibility in Settings: Live (visible on map), Ghost (anonymous), or Offline (no upload).")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDisclosure = false
                    locationPermissions.launchMultiplePermissionRequest()
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDisclosure = false }) {
                    Text("Not now")
                }
            }
        )
    }

    // Prominent disclosure dialog for background location
    if (showBackgroundLocationDisclosure) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Background Location Access") },
            text = {
                Text("Coverage mapping requires GPS access while the app runs in the background. This allows you to keep mapping with the screen off or while using other apps during a drive.\n\nOn the next screen, please select \"Allow all the time\" to enable background mapping.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundLocationDisclosure = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        backgroundLocationPermission?.launchPermissionRequest()
                    }
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundLocationDisclosure = false }) {
                    Text("Not now")
                }
            }
        )
    }

    // Default to last known position (persisted) instead of hardcoded Hamburg
    val locationService = MapmeApp.instance.locationService
    val defaultLocation = GeoPoint(locationService.lastLat, locationService.lastLon)

    // MeshMonstis: Geister holen (2-min-Cache im Service), solange Tab offen
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshGhosts()
            kotlinx.coroutines.delay(125_000)
        }
    }

    // Radar-PKE: Naehe-Ton zum naechsten fangbaren Geist (nur beim Tracken,
    // nie im Anonym-Modus — anonym faengt serverseitig nichts)
    LaunchedEffect(currentLocation, ghosts, isTracking, privacyMode) {
        val loc = currentLocation ?: return@LaunchedEffect
        if (!isTracking || privacyMode == "anonym" || ghosts.isEmpty()) return@LaunchedEffect
        val nearest = ghosts.asSequence()
            .filter { !it.kind.rxOnly }
            .map { GhostMath.haversineMeters(loc.latitude, loc.longitude, it.lat, it.lon) }
            .minOrNull() ?: return@LaunchedEffect
        MapmeApp.instance.feedbackManager.playGhostNear(nearest)
    }

    // Session hex data: H3 -> (repeaterName, bestRSSI)
    val sessionHexData = remember { mutableStateMapOf<String, Pair<String, Int>>() }

    // Update session hex data when RX packets come in — process ALL packets, keep best RSSI per hex
    LaunchedEffect(recentRxPackets) {
        recentRxPackets.forEach { packet ->
            val hexId = packet.h3 ?: return@forEach
            val lastHop = packet.path.lastOrNull() ?: return@forEach

            val existing = sessionHexData[hexId]
            if (existing == null || packet.rssi > existing.second) {
                sessionHexData[hexId] = Pair(lastHop, packet.rssi)
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
            controller.setZoom(viewModel.mapZoomLevel.value)
            controller.setCenter(defaultLocation)

            // Location overlay — added here but will be moved to top layer after hex drawing
            try {
                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), this)
                locationOverlay.enableMyLocation()

                // Large red arrow with white border — visible over any hex color
                val size = 80
                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)

                // White border (thick)
                val borderPaint = android.graphics.Paint().apply {
                    color = AndroidColor.WHITE
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 6f
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                val arrowPath = android.graphics.Path().apply {
                    moveTo(size / 2f, 4f)
                    lineTo(size - 8f, size - 8f)
                    lineTo(size / 2f, size * 0.65f)
                    lineTo(8f, size - 8f)
                    close()
                }
                canvas.drawPath(arrowPath, borderPaint)

                // Red fill
                val fillPaint = android.graphics.Paint().apply {
                    color = AndroidColor.rgb(0xFF, 0x00, 0x00)
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawPath(arrowPath, fillPaint)

                locationOverlay.setPersonIcon(bitmap)
                locationOverlay.setDirectionArrow(bitmap, bitmap)
                locationOverlay.setPersonHotspot(size / 2f, size / 2f)
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

    // Follow user location on map
    LaunchedEffect(currentLocation, followLocation) {
        currentLocation?.let { loc ->
            if (!viewModel.mapHasInitialLocation.value) {
                viewModel.mapHasInitialLocation.value = true
                mapView.controller.setZoom(16.0)
            }
            if (followLocation) {
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    // Update hex overlays (spriteVersion: Monsti-PNGs laden async nach)
    LaunchedEffect(serverHexes, visitedHexes, sessionHexData.keys.toList(), ghosts, privacyMode, spriteVersion) {
        try {
            // Remove old polygon and marker overlays (keep location overlay)
            val locationOverlay = mapView.overlays.firstOrNull { it is MyLocationNewOverlay }
            mapView.overlays.removeAll { it is Polygon || it is Marker }

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

            // Session hexes (visited this session) — RSSI-based colors matching web
            visitedHexes.forEach { hexId ->
                try {
                    val boundary = MapmeApp.instance.locationService.getH3Boundary(hexId)
                    boundary?.let { coords ->
                        val signalData = sessionHexData[hexId]
                        val hexColor = if (signalData != null) {
                            HexColor.fromRssi(signalData.second)
                        } else {
                            HexColor.VISITED
                        }
                        val polygon = Polygon().apply {
                            points = coords.map { GeoPoint(it.first, it.second) }
                            fillPaint.color = AndroidColor.argb(
                                if (signalData != null) 128 else 77,
                                (hexColor.r * 255).toInt(),
                                (hexColor.g * 255).toInt(),
                                (hexColor.b * 255).toInt()
                            )
                            outlinePaint.color = AndroidColor.rgb(
                                (hexColor.r * 255).toInt(),
                                (hexColor.g * 255).toInt(),
                                (hexColor.b * 255).toInt()
                            )
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(polygon)
                    }
                } catch (e: Exception) { /* skip invalid hex */ }
            }

            // Add repeater name labels on hexes with signal
            sessionHexData.forEach { (hexId, data) ->
                try {
                    val (repeaterName, _) = data
                    val center = MapmeApp.instance.locationService.getH3Center(hexId)
                    center?.let { (lat, lon) ->
                        // Create text bitmap for label
                        val paint = android.graphics.Paint().apply {
                            color = AndroidColor.WHITE
                            textSize = 28f
                            isAntiAlias = true
                            setShadowLayer(3f, 1f, 1f, AndroidColor.BLACK)
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val textWidth = paint.measureText(repeaterName).toInt() + 16
                        val textHeight = 36
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            textWidth, textHeight, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawText(repeaterName, textWidth / 2f, 26f, paint)

                        val marker = Marker(mapView).apply {
                            position = GeoPoint(lat, lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = android.graphics.drawable.BitmapDrawable(
                                mapView.context.resources, bitmap
                            )
                            setInfoWindow(null)
                        }
                        mapView.overlays.add(marker)
                    }
                } catch (e: Exception) { /* skip */ }
            }

            // MeshMonstis-Marker: Kreis in Typ-Farbe + Emoji. Nur bis 50 km um
            // die eigene Position (osmdroid mag keine 1000 Marker), anonym aus.
            if (privacyMode != "anonym") {
                val loc = currentLocation
                ghosts.asSequence()
                    .filter { g ->
                        loc == null || GhostMath.haversineMeters(
                            loc.latitude, loc.longitude, g.lat, g.lon) < 50_000
                    }
                    .take(200)
                    .forEach { g ->
                        try {
                            // Echte Grok-Monstis wie Website/iOS; bis das PNG
                            // geladen ist, bleibt der Emoji-Kreis als Fallback
                            val sprite = sh.mapme.mapper.ui.game.MonstiSprites.get(g)
                            val bmp = if (sprite != null) {
                                android.graphics.Bitmap.createScaledBitmap(sprite, 96, 96, true)
                            } else {
                                val size = 56
                                val b = android.graphics.Bitmap.createBitmap(
                                    size, size, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(b)
                                val fill = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    color = ghostColor(g.kind)
                                    alpha = 230
                                }
                                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, fill)
                                val ring = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 3f
                                    color = AndroidColor.WHITE
                                }
                                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, ring)
                                val txt = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    textSize = 30f
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                canvas.drawText(g.kind.emoji, size / 2f, size / 2f + 11f, txt)
                                b
                            }
                            val marker = Marker(mapView).apply {
                                position = GeoPoint(g.lat, g.lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = android.graphics.drawable.BitmapDrawable(
                                    mapView.context.resources, bmp)
                                // KEIN title mehr: statt osmdroid-Bubble öffnet
                                // der Tap unsere GhostInfoCard (wie Web/iOS)
                                setOnMarkerClickListener { _, _ ->
                                    selectedGhost.value = g
                                    true
                                }
                            }
                            mapView.overlays.add(marker)
                        } catch (e: Exception) { /* skip ghost */ }
                    }
            }

            // Move location overlay to top so it's always visible above hexagons
            locationOverlay?.let {
                mapView.overlays.remove(it)
                mapView.overlays.add(it)
            }

            mapView.invalidate()
        } catch (e: Exception) { /* ignore overlay errors */ }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // OpenStreetMap
        AndroidView(
            factory = {
                mapView
            },
            modifier = Modifier.fillMaxSize()
        )

        // (Radar lebt jetzt in der GameShell: Monsti-Button + RadarSheet)

        // Monsti-Info-Card (Tap auf Geist) — wie Webkarten-Popup / iOS
        selectedGhost.value?.let { g ->
            sh.mapme.mapper.ui.game.GhostInfoCard(
                ghost = g,
                distanceM = currentLocation?.let {
                    GhostMath.haversineMeters(it.latitude, it.longitude, g.lat, g.lon)
                },
                onClose = { selectedGhost.value = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 104.dp)   // ueber den GameShell-Buttons
            )
        }

        // Stats overlay (top-left)
        if (showStats) {
            Card(
                modifier = Modifier
                    .padding(start = 12.dp, top = 104.dp)   // unter dem Spieler-Chip
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
                    .padding(start = 12.dp, top = 104.dp)   // unter dem Spieler-Chip
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
                    .padding(start = 12.dp, bottom = 96.dp)   // ueber dem Album-Button
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
                .padding(end = 12.dp, top = 64.dp)   // unter der Mesh-LED
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Toggle follow location — keep current zoom
            Card(
                modifier = Modifier
                    .clickable {
                        val newValue = !viewModel.mapFollowLocation.value
                        viewModel.mapFollowLocation.value = newValue
                        if (newValue) {
                            currentLocation?.let {
                                mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                            }
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (followLocation)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    else
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Text(
                    text = "GPS",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (followLocation)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
            // Map style toggle
            Card(
                modifier = Modifier
                    .clickable { viewModel.mapUseDarkMode.value = !viewModel.mapUseDarkMode.value },
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

            // Coverage time filter
            val coverageDays by viewModel.coverageDays.collectAsState()
            Card(
                modifier = Modifier
                    .clickable {
                        // Cycle: All -> 30d -> 7d -> All
                        val next = when (coverageDays) {
                            0 -> 30
                            30 -> 7
                            else -> 0
                        }
                        viewModel.setCoverageDays(next)
                        // Re-fetch hexes with new filter
                        currentLocation?.let {
                            viewModel.hexService.fetchHexes(it.latitude, it.longitude)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Text(
                    text = when (coverageDays) {
                        0 -> "All"
                        7 -> "7d"
                        30 -> "30d"
                        else -> "${coverageDays}d"
                    },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Discover/Ping buttons (bottom-right) — only when connected in live mode
        if (isConnected && privacyMode == "live") {
            var discoverCooldown by remember { mutableStateOf(0) }
            var pingCooldown by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                while (true) {
                    discoverCooldown = viewModel.getDiscoverCooldown()
                    pingCooldown = viewModel.getPingCooldown()
                    kotlinx.coroutines.delay(1000)
                }
            }

            Column(
                modifier = Modifier
                    .padding(end = 12.dp, bottom = 96.dp)   // ueber dem Verbinden-Button
                    .align(Alignment.BottomEnd),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Discover button
                Card(
                    modifier = Modifier.clickable(enabled = discoverCooldown == 0) {
                        viewModel.sendDiscover()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (discoverCooldown == 0)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        else
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = if (discoverCooldown > 0) "DISC ${discoverCooldown}s" else "DISC",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (discoverCooldown == 0) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Ping button
                val canPing = coverageChannelReady && currentH3 != null && pingCooldown == 0
                Card(
                    modifier = Modifier.clickable(enabled = canPing) {
                        viewModel.sendPing()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (canPing)
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                        else
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = when {
                            pingCooldown > 0 -> "PING ${pingCooldown}s"
                            !coverageChannelReady -> "NO CH"
                            currentH3 == null -> "NO GPS"
                            else -> "PING"
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (canPing) MaterialTheme.colorScheme.onTertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // GPS indicator
        if (!isTracking) {
            Card(
                modifier = Modifier
                    .padding(top = 64.dp)   // unter den Shell-Chips
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

    // Save zoom level periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            viewModel.mapZoomLevel.value = mapView.zoomLevelDouble
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            viewModel.mapZoomLevel.value = mapView.zoomLevelDouble
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
        rssi > -80 -> Color(0xFF22C55E)  // Excellent — green
        rssi > -100 -> Color(0xFF84CC16) // Good — lime
        rssi > -115 -> Color(0xFFEAB308) // OK — yellow
        rssi > -125 -> Color(0xFFF59E0B) // Weak — amber
        else -> Color(0xFF6B7280)        // Marginal — gray
    }
}


// MARK: - MeshMonstis

/** Website-Palette (dataviz-validiert) als Android-Farbwerte. */
fun ghostColor(kind: GhostKind): Int = when (kind) {
    GhostKind.IRRLICHT -> AndroidColor.rgb(0x3B, 0x82, 0xF6)
    GhostKind.PENDLERGEIST -> AndroidColor.rgb(0xF4, 0x3F, 0x5E)
    GhostKind.BRUECKENGEIST -> AndroidColor.rgb(0x08, 0x91, 0xB2)
    GhostKind.WIEDERGAENGER -> AndroidColor.rgb(0x65, 0xA3, 0x0D)
    GhostKind.BERGGEIST -> AndroidColor.rgb(0xA8, 0x55, 0xF7)
}

/**
 * Radar-HUD: naechster Geist je Typ mit Distanz + Himmelsrichtung.
 * rx-only-Typen zeigen "braucht Empfang" statt Fang-Illusion.
 */
@Composable
fun GhostRadarHud(
    ghosts: List<Ghost>,
    location: android.location.Location?,
    anonym: Boolean,
    modifier: Modifier = Modifier
) {
    if (ghosts.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (anonym) {
                Text("👻 Anonym — keine Jagd", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Card
            }
            if (location == null) {
                Text("👻 Radar wartet auf GPS…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Card
            }
            val nearest = remember(ghosts, location.latitude, location.longitude) {
                GhostMath.nearestByKind(location.latitude, location.longitude, ghosts)
            }
            if (!expanded) {
                val best = nearest.values.filter { !it.ghost.kind.rxOnly }.minByOrNull { it.distanceM }
                Text(
                    text = best?.let {
                        "${it.ghost.kind.emoji} ${fmtDistance(it.distanceM)} ${GhostMath.compass(it.bearing)}"
                    } ?: "👻 Radar",
                    style = MaterialTheme.typography.labelMedium
                )
                return@Card
            }
            GhostKind.entries.forEach { kind ->
                val n = nearest[kind]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp)
                ) {
                    Text(kind.emoji, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(kind.labelDe, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(92.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = when {
                            n == null -> "—"
                            kind.rxOnly && n.distanceM < 1_500 -> "braucht Empfang!"
                            n.distanceM > 15_000 -> "—  (keiner < 15 km)"
                            else -> "${fmtDistance(n.distanceM)}  ${GhostMath.compass(n.bearing)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (n != null && n.distanceM < 2_000 && !kind.rxOnly)
                            FontWeight.Bold else FontWeight.Normal,
                        color = if (n != null && n.distanceM < 2_000 && !kind.rxOnly)
                            Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun fmtDistance(m: Double): String =
    if (m < 1_000) "${m.toInt()} m" else "%.1f km".format(m / 1_000.0)
