package sh.mapme.mapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import sh.mapme.mapper.MainActivity
import sh.mapme.mapper.MapmeApp
import sh.mapme.mapper.R

/**
 * MapperService - Foreground Service for background operation
 *
 * Keeps the app alive when screen is off to maintain:
 * - BLE connection to MeshCore device
 * - GPS tracking
 * - Auto-discovery and TX timers
 */
class MapperService : Service() {

    companion object {
        private const val TAG = "MapperService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mapper_service_channel"

        fun start(context: Context) {
            val intent = Intent(context, MapperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MapperService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Start as foreground service - use location type only if permission granted
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notification = createNotification(0, 0, false, "Starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start updating notification with live stats
        startNotificationUpdates()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        updateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mapme Mapper Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE connection and GPS tracking active"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(rxCount: Int, txCount: Int, isConnected: Boolean, status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isConnected) "Mapme Mapper Active" else "Mapme Mapper"
        val text = if (isConnected) {
            "RX: $rxCount | TX: $txCount | $status"
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startNotificationUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            val app = MapmeApp.instance
            val bleManager = app.bleManager

            // Combine multiple flows to update notification
            combine(
                bleManager.isConnected,
                bleManager.rxCount,
                bleManager.txCount,
                bleManager.isTxActive,
                bleManager.coverageChannelReady
            ) { isConnected, rxCount, txCount, isTxActive, channelReady ->
                NotificationData(isConnected, rxCount, txCount, isTxActive, channelReady)
            }.collect { data ->
                val status = when {
                    !data.isConnected -> "Disconnected"
                    data.isTxActive -> "TX Active"
                    data.channelReady -> "Ready"
                    else -> "Connecting..."
                }

                val notification = createNotification(
                    data.rxCount,
                    data.txCount,
                    data.isConnected,
                    status
                )

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private data class NotificationData(
        val isConnected: Boolean,
        val rxCount: Int,
        val txCount: Int,
        val isTxActive: Boolean,
        val channelReady: Boolean
    )
}
