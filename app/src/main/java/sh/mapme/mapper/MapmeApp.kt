package sh.mapme.mapper

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import sh.mapme.mapper.data.*
import sh.mapme.mapper.util.FeedbackManager

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

    lateinit var feedbackManager: FeedbackManager
        private set

    lateinit var ghostHunt: GhostHuntManager
        private set

    lateinit var albumStore: AlbumStore
        private set

    lateinit var tradeManager: TradeManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize services
        feedbackManager = FeedbackManager(this)
        hexService = HexService()
        sampleRepository = SampleRepository(this)
        bleManager = BleManager(this)
        locationService = LocationService(this)

        // Wire feedback to BLE manager
        bleManager.feedbackManager = feedbackManager

        // MeshMonstis: Fang-Erkennung (beobachtet Location + Samples + Geister)
        ghostHunt = GhostHuntManager(hexService, sampleRepository, bleManager,
            locationService, feedbackManager)
        albumStore = AlbumStore(this)
        ghostHunt.appContext = this
        ghostHunt.onConfirmedCatch = { ghost, points -> albumStore.addCaught(ghost, points) }
        // Offene Fänge aus früheren Touren nachträglich auflösen (Gipfel ohne
        // Netz: der Upload kommt erst Stunden später) — einmal beim Start und
        // danach nach jedem erfolgreichen Upload.
        ghostHunt.pruefeOffeneFaenge()
        CoroutineScope(Dispatchers.Default).launch {
            sampleRepository.totalUploaded.drop(1).distinctUntilChanged().collect {
                delay(5_000)
                ghostHunt.pruefeOffeneFaenge()
            }
        }
        tradeManager = TradeManager(bleManager, albumStore)

        // Wire up dependencies
        sampleRepository.hexService = hexService
        sampleRepository.bleManager = bleManager
        bleManager.sampleRepository = sampleRepository
        bleManager.hexService = hexService

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
        feedbackManager.destroy()
    }

    companion object {
        lateinit var instance: MapmeApp
            private set
    }
}
