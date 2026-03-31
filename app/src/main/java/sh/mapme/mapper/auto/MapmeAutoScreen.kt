package sh.mapme.mapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import sh.mapme.mapper.MapmeApp

/**
 * Android Auto screen showing live mapping stats.
 * Uses MessageTemplate for a clean status display.
 */
class MapmeAutoScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startRefreshing()
            }

            override fun onStop(owner: LifecycleOwner) {
                refreshJob?.cancel()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val app = MapmeApp.instance
            val bleManager = app.bleManager

            // Refresh screen every 3 seconds
            combine(
                bleManager.isConnected,
                bleManager.rxCount,
                bleManager.recentRxPackets,
                app.locationService.visitedHexes,
                bleManager.batteryPercent
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                AutoData(
                    isConnected = values[0] as Boolean,
                    rxCount = values[1] as Int,
                    lastRssi = (values[2] as List<*>).firstOrNull()?.let {
                        val packet = it as sh.mapme.mapper.data.BleManager.RxPacket
                        packet.rssi
                    },
                    lastRepeater = (values[2] as List<*>).firstOrNull()?.let {
                        val packet = it as sh.mapme.mapper.data.BleManager.RxPacket
                        packet.path.lastOrNull()
                    },
                    hexCount = (values[3] as Set<*>).size,
                    batteryPercent = values[4] as Int
                )
            }.collect {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val app = MapmeApp.instance
        val bleManager = app.bleManager
        val locationService = app.locationService

        val isConnected = bleManager.isConnected.value
        val rxCount = bleManager.rxCount.value
        val hexCount = locationService.visitedHexes.value.size
        val batteryPercent = bleManager.batteryPercent.value
        val lastPacket = bleManager.recentRxPackets.value.firstOrNull()
        val isReconnecting = bleManager.isReconnecting.value

        val statusText = when {
            !isConnected && isReconnecting -> "Reconnecting..."
            !isConnected -> "Not Connected"
            else -> {
                val parts = mutableListOf<String>()
                parts.add("RX: $rxCount")
                parts.add("Hexes: $hexCount")
                lastPacket?.let { parts.add("${it.rssi} dBm") }
                if (batteryPercent >= 0) parts.add("Batt: $batteryPercent%")
                parts.joinToString("  |  ")
            }
        }

        val lastActivity = if (isConnected && lastPacket != null) {
            val repeater = lastPacket.path.lastOrNull() ?: "?"
            "Last: $repeater (${lastPacket.rssi} dBm)"
        } else {
            "No activity yet"
        }

        val paneBuilder = Pane.Builder()
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(statusText)
                .addText(lastActivity)
                .build()
        )

        if (isConnected) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Session Active")
                    .addText("GPS tracking and auto-discovery running")
                    .build()
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("Mapme.sh Mapper")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private data class AutoData(
        val isConnected: Boolean,
        val rxCount: Int,
        val lastRssi: Int?,
        val lastRepeater: String?,
        val hexCount: Int,
        val batteryPercent: Int
    )
}
