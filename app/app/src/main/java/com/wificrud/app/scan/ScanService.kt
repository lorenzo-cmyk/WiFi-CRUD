package com.wificrud.app.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wificrud.app.api.ApiClient
import com.wificrud.app.data.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private lateinit var creds: CredentialStore
    private lateinit var wifi: WifiScanner
    private val api = ApiClient()

    override fun onCreate() {
        super.onCreate()
        creds = CredentialStore(applicationContext)
        wifi = WifiScanner(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val interval = intent?.getIntExtra(EXTRA_INTERVAL, 30)?.coerceIn(5, 120) ?: 30
        ScanState.update { it.copy(isScanning = true, intervalSeconds = interval, error = null) }

        val notification = buildNotification("Starting scan loop...")
        startForeground(NOTIFICATION_ID, notification)

        scanJob?.cancel()
        scanJob = scope.launch { scanLoop(interval) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        ScanState.update { it.copy(isScanning = false, nextScanAt = 0L) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun scanLoop(intervalSec: Int) {
        while (currentCoroutineContext().isActive) {
            val cycleStart = System.currentTimeMillis()
            val nextCycle = cycleStart + intervalSec * 1000L
            performScan()
            ScanState.update { it.copy(nextScanAt = nextCycle) }
            updateNotification("Next scan in ${intervalSec}s")
            val remaining = nextCycle - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
        }
    }

    private suspend fun performScan() {
        try {
            val location = getLocation()
            wifi.startScan()
            delay(2500)

            val scanEntries = wifi.getScanResults()
            val scans = scanEntries.map { ApiClient.ScanEntry(it.ssid, it.bssid, it.rssi) }
            val ts = System.currentTimeMillis() / 1000

            val postResult = api.postMeasurement(
                authKey = creds.authKey,
                timestamp = ts,
                gpsLat = location?.latitude,
                gpsLon = location?.longitude,
                wifiScans = scans,
            )

            val measurement = ScanState.Measurement(
                timestamp = ts,
                gpsLat = location?.latitude,
                gpsLon = location?.longitude,
                networks = scanEntries.size,
                measurementId = postResult.measurementId,
                scans = scanEntries.map { ScanState.ScanEntry(it.ssid, it.bssid, it.rssi) },
            )
            ScanState.update { it.copy(lastMeasurement = measurement, error = null) }

            updateNotification("Sent ${scanEntries.size} networks")
        } catch (e: Exception) {
            ScanState.update { it.copy(error = e.message ?: "Unknown error") }
            updateNotification("Error: ${e.message}")
        }
    }

    private fun getLocation(): android.location.Location? {
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "WiFi Scan", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi CRUD Scan")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "wifi_scan"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_INTERVAL = "interval"

        fun start(context: Context, intervalSeconds: Int) {
            val i = Intent(context, ScanService::class.java).apply {
                putExtra(EXTRA_INTERVAL, intervalSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }
    }
}
