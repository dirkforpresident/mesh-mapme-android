package sh.mapme.mapper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Shared ViewModel
    val viewModel: MainViewModel = viewModel()

    // Observe connection state
    val isConnected by viewModel.isConnected.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = "Home", modifier = Modifier.size(22.dp)) },
                    label = { Text("Home", fontSize = 12.sp) },
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (isConnected) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { }
                                }
                            }
                        ) {
                            Icon(painterResource(R.drawable.ic_device), contentDescription = "Device", modifier = Modifier.size(22.dp))
                        }
                    },
                    label = { Text("Device", fontSize = 12.sp) },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        navController.navigate("device") {
                            popUpTo("home")
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_map), contentDescription = "Map", modifier = Modifier.size(22.dp)) },
                    label = { Text("Map", fontSize = 12.sp) },
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        navController.navigate("map") {
                            popUpTo("home")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToDevice = {
                        selectedTab = 1
                        navController.navigate("device") {
                            popUpTo("home")
                        }
                    }
                )
            }
            composable("device") { DeviceScreen(viewModel) }
            composable("map") { MapScreen(viewModel) }
        }
    }
}
