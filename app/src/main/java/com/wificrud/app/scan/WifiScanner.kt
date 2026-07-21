package com.wificrud.app.scan

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isWifiEnabled(): Boolean = wifiManager?.isWifiEnabled == true

    fun startScan(): Boolean {
        return wifiManager?.startScan() ?: false
    }

    fun getScanResults(): List<ScanResult> {
        @Suppress("DEPRECATION")
        return wifiManager?.scanResults ?: emptyList()
    }

    fun isThrottleWarningNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val enabled = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                "wifi_scan_throttle_enabled",
                1
            ) != 0
        } catch (_: Exception) {
            true
        }
        return enabled
    }
}
