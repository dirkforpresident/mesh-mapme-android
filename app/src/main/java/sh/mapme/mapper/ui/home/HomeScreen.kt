package sh.mapme.mapper.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.mapme.mapper.BuildConfig
import sh.mapme.mapper.Constants
import sh.mapme.mapper.MainViewModel
import sh.mapme.mapper.R

@Composable
fun HomeScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val isConnected by viewModel.isConnected.collectAsState()
    val liveMappers by viewModel.liveMappers.collectAsState()
    val leaderboard by viewModel.leaderboard.collectAsState()
    val ownRank by viewModel.ownRank.collectAsState()
    val selfInfo by viewModel.selfInfo.collectAsState()
    val isLoadingHexes by viewModel.isLoadingHexes.collectAsState()

    var showAllMappers by remember { mutableStateOf(false) }
    var showLeaderboard by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshLiveMappers()
        viewModel.refreshLeaderboard()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with Logo
        item {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_logo_mesh),
                    contentDescription = "Logo",
                    modifier = Modifier.size(60.dp),
                    tint = Color.Unspecified
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "MAPME.SH",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = {
                        viewModel.refreshLiveMappers()
                        viewModel.refreshLeaderboard()
                    }
                ) {
                    if (isLoadingHexes) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_home),
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Text(
                text = "MeshCore Coverage Mapper",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Connection Status Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (isConnected) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) "Connected" else "Not connected",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "Go to Device tab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateToDevice() }
                    )
                }
            }
        }

        // Live Mappers Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_device),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Mappers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${liveMappers.size}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (liveMappers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))

                        val mappersToShow = if (showAllMappers) liveMappers else liveMappers.take(5)
                        mappersToShow.forEach { mapper ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(8.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = Color(0xFF22C55E)
                                    ) {}
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = mapper.name ?: mapper.id.take(8),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = "${mapper.ago}s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (liveMappers.size > 5) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (showAllMappers) "Show less" else "Show all ${liveMappers.size} mappers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clickable { showAllMappers = !showAllMappers }
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No active mappers right now",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Leaderboard (collapsible)
                    if (leaderboard.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLeaderboard = !showLeaderboard }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_home),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Leaderboard",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = if (showLeaderboard) "^" else "v",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (showLeaderboard) {
                            // Own rank highlight
                            ownRank?.let { rank ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (rank.rank != null) "#${rank.rank}" else "--",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = "Your Ranking",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Text(
                                                    text = "${rank.points} pts / ${rank.hexes} hexes",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Get own pubkey for highlighting
                            val ownPubkey = selfInfo?.publicKey?.joinToString("") { "%02x".format(it) }

                            // Full leaderboard list
                            leaderboard.forEach { entry ->
                                val isOwnEntry = ownPubkey != null && entry.id == ownPubkey
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isOwnEntry) Modifier.padding(horizontal = 0.dp)
                                            else Modifier
                                        )
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "#${entry.rank}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                entry.rank == 1 -> Color(0xFFFFD700)
                                                entry.rank == 2 -> Color(0xFFC0C0C0)
                                                entry.rank == 3 -> Color(0xFFCD7F32)
                                                isOwnEntry -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.width(40.dp)
                                        )
                                        if (entry.online) {
                                            Surface(
                                                modifier = Modifier.size(6.dp),
                                                shape = MaterialTheme.shapes.small,
                                                color = Color(0xFF22C55E)
                                            ) {}
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = entry.name ?: entry.id.take(8),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isOwnEntry) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isOwnEntry) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "${entry.hexes} pts",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isOwnEntry) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // How It Works (collapsible)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHowItWorks = !showHowItWorks }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "?",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "How It Works",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (showHowItWorks) "^" else "v",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showHowItWorks) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // RX Packets
                        HowItWorksItem(
                            title = "RX Packets",
                            description = "Received messages are evaluated directly"
                        )
                        HowItWorksItem(
                            title = "Auto Discovery",
                            description = "Runs every 30s when there's no traffic"
                        )
                        HowItWorksItem(
                            title = "Manual Ping",
                            description = "Can be sent every 2 min additionally"
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Points System",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PointsRow("New Hex visited", "+1")
                        PointsRow("Hex with Repeater-Signal", "+3")
                        PointsRow("Per active Mapping-Day", "+5")

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Privacy Modes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            PrivacyModeItem("Live", "Visible", Color(0xFF22C55E))
                            PrivacyModeItem("Normal", "3h delay", Color(0xFFFACC15))
                            PrivacyModeItem("Ghost", "24h delay", Color(0xFF6B7280))
                        }
                    }
                }
            }
        }

        // About & Links (collapsible)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAbout = !showAbout }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "i",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "About & Links",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (showAbout) "^" else "v",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showAbout) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Help map MeshCore network coverage!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()

                        LinkRow("mapme.sh", "https://mapme.sh", context)
                        LinkRow("meshcore.co.uk", "https://meshcore.co.uk", context)
                        LinkRow("hansemesh.de", "https://hansemesh.de", context)
                        LinkRow("elektronikreich.de", "https://elektronikreich.de", context)
                        LinkRow("Privacy Policy", "https://mapme.sh/privacy", context)
                    }
                }
            }
        }

        // Version
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "v${Constants.APP_VERSION} (b${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun HowItWorksItem(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_device),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PointsRow(label: String, points: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = points,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun PrivacyModeItem(mode: String, delay: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.small,
            color = color
        ) {}
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = mode,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = delay,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LinkRow(label: String, url: String, context: android.content.Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_map),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = ">",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
