package sh.mapme.mapper.ui.device

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.mapme.mapper.R

@Composable
fun DeviceScreen() {
    // TODO: Inject BleManager via ViewModel
    var isScanning by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }

    if (isConnected) {
        ConnectedScreen()
    } else {
        ScanningScreen(
            isScanning = isScanning,
            onScanToggle = { isScanning = !isScanning }
        )
    }
}

@Composable
fun ScanningScreen(
    isScanning: Boolean,
    onScanToggle: () -> Unit
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated antenna icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(180.dp)
        ) {
            if (isScanning) {
                // Pulse rings
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

            // Center icon
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

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (isScanning) "Scanning..." else "No Device Connected",
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

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onScanToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }
    }
}

@Composable
fun ConnectedScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Device Header Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Column {
                    Text(
                        text = "MeshCore Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        AssistChip(
                            onClick = {},
                            label = { Text("Connected") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status Cards Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(modifier = Modifier.weight(1f), icon = R.drawable.ic_home, value = "Live", label = "GPS")
            StatusCard(modifier = Modifier.weight(1f), icon = R.drawable.ic_home, value = "0", label = "RX")
            StatusCard(modifier = Modifier.weight(1f), icon = R.drawable.ic_home, value = "0", label = "Hexes")
            StatusCard(modifier = Modifier.weight(1f), icon = R.drawable.ic_home, value = "0", label = "Up")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy Mode Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Privacy Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented button placeholder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Live") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("Normal") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("Ghost") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    icon: Int,
    value: String,
    label: String
) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
