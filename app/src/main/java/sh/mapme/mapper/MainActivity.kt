package sh.mapme.mapper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import sh.mapme.mapper.ui.theme.MapmeMapperTheme
import sh.mapme.mapper.ui.home.HomeScreen
import sh.mapme.mapper.ui.device.DeviceScreen
import sh.mapme.mapper.ui.map.MapScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Keep screen on while BLE connected and keepScreenOn enabled
            val viewModel: MainViewModel = viewModel()
            val isConnected by viewModel.isConnected.collectAsState()
            val keepScreenOn by viewModel.keepScreenOn.collectAsState()

            LaunchedEffect(isConnected, keepScreenOn) {
                if (isConnected && keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MapmeMapperTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    // GameShell: die App IST das Spiel (iOS-Prinzip) — keine Tab-Bar mehr.
    // Die Karte ist die Bühne, alles andere liegt hinter Chips/Buttons:
    // Spieler-Chip → Hub, Mesh-LED/Verbinden → Companion, Album, Monsti-Radar.
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    val isConnected by viewModel.isConnected.collectAsState()
    val selfInfo by viewModel.selfInfo.collectAsState()
    val albumCards by viewModel.albumCards.collectAsState()
    val ghosts by viewModel.ghosts.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()

    var showRadar by remember { mutableStateOf(false) }
    val backStack by navController.currentBackStackEntryAsState()
    val onMap = backStack?.destination?.route == "map" || backStack == null

    // Nächster fangbarer Geist (rx-only zählt nicht) für den Monsti-Button
    val nearestCatchable = remember(ghosts, currentLocation) {
        val loc = currentLocation ?: return@remember null
        sh.mapme.mapper.data.GhostMath
            .nearestByKind(loc.latitude, loc.longitude, ghosts.filter { !it.kind.rxOnly })
            .values.minByOrNull { it.distanceM }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "map",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("map") { MapScreen(viewModel) }
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToDevice = { navController.navigate("device") { popUpTo("map") } },
                    onNavigateToMap = { navController.popBackStack("map", false) },
                    onNavigateToAlbum = { navController.navigate("jagd") { popUpTo("map") } }
                )
            }
            composable("device") { DeviceScreen(viewModel) }
            composable("jagd") { sh.mapme.mapper.ui.game.AlbumScreen(viewModel) }
        }

        if (onMap) {
            // Oben: Spieler links, Mesh-LED rechts
            Row(
                Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                sh.mapme.mapper.ui.game.PlayerChip(
                    name = selfInfo?.nodeName?.takeIf { it.isNotBlank() } ?: "Mapper",
                    cards = albumCards.size,
                    points = albumCards.sumOf { it.points },
                    onClick = { navController.navigate("home") { popUpTo("map") } }
                )
                Spacer(Modifier.weight(1f))
                sh.mapme.mapper.ui.game.LinkChip(
                    connected = isConnected,
                    onClick = { navController.navigate("device") { popUpTo("map") } }
                )
            }

            // Unten: Album · Monsti-Button · Verbinden
            Row(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Bottom
            ) {
                sh.mapme.mapper.ui.game.SideButton("📖", "Album", badge = albumCards.size) {
                    navController.navigate("jagd") { popUpTo("map") }
                }
                Spacer(Modifier.weight(1f))
                sh.mapme.mapper.ui.game.MonstiButton(nearestCatchable) { showRadar = true }
                Spacer(Modifier.weight(1f))
                sh.mapme.mapper.ui.game.SideButton(
                    if (isConnected) "📡" else "🔌",
                    if (isConnected) "Mesh" else "Verbinden"
                ) { navController.navigate("device") { popUpTo("map") } }
            }
        } else {
            // Nicht auf der Karte: schlanker Zurück-Chip oben links
            Row(
                Modifier
                    .align(androidx.compose.ui.Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(10.dp)
            ) {
                FilledTonalButton(onClick = { navController.popBackStack("map", false) }) {
                    Text("← Karte")
                }
            }
        }

        if (showRadar) {
            sh.mapme.mapper.ui.game.RadarSheet(
                ghosts = ghosts,
                myLat = currentLocation?.latitude,
                myLon = currentLocation?.longitude,
                onDismiss = { showRadar = false }
            )
        }

        // Tausch-UI (Sheet + eingehende Angebote) global
        sh.mapme.mapper.ui.game.TradeSheetHost(viewModel)

        // Fang-Toast schwebt ueber allem
        sh.mapme.mapper.ui.game.CatchToastHost(
            hunt = viewModel.ghostHunt,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
        )
    }
}
