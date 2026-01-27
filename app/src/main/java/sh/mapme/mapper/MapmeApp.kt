package sh.mapme.mapper

import android.app.Application
import sh.mapme.mapper.data.*

/**
 * MapmeApp - Application class that initializes all services
 */
class MapmeApp : Application() {

    // Singleton services
    lateinit var bleManager: BleManager
        private set

    lateinit var locationService: LocationService
        private set

    lateinit var sampleRepository: SampleRepository
        private set

    lateinit var hexService: HexService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize services
        hexService = HexService()
        sampleRepository = SampleRepository(this)
        bleManager = BleManager(this)
        locationService = LocationService(this)

        // Wire up dependencies
        sampleRepository.hexService = hexService
        sampleRepository.bleManager = bleManager
        bleManager.sampleRepository = sampleRepository

        // Location -> BLE H3 updates
        locationService.onH3Update = { h3 ->
            bleManager.currentH3 = h3
        }

        // New hex visited -> create visit sample
        locationService.onNewHexVisited = { h3 ->
            if (bleManager.isConnected.value) {
                bleManager.addVisitSample()
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        bleManager.cleanup()
        locationService.cleanup()
        sampleRepository.cleanup()
        hexService.cleanup()
    }

    companion object {
        lateinit var instance: MapmeApp
            private set
    }
}
