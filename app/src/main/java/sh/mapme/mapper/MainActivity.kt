package sh.mapme.mapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
        setContent {
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_device), contentDescription = "Device") },
                    label = { Text("Device") },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        navController.navigate("device") {
                            popUpTo("home")
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_map), contentDescription = "Map") },
                    label = { Text("Map") },
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
            composable("home") { HomeScreen() }
            composable("device") { DeviceScreen() }
            composable("map") { MapScreen() }
        }
    }
}
