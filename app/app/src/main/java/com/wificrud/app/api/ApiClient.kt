package com.wificrud.app.api

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ApiClient(private val baseUrl: String = "https://wifi-crud.lorenzo-chiroli.workers.dev") {

    data class RegisterResult(
        val deviceId: String,
        val authKey: String,
        val error: String? = null
    )

    data class MeasurementResult(
        val success: Boolean,
        val measurementId: Long? = null,
        val error: String? = null
    )

    fun register(name: String): RegisterResult {
        val body = JSONObject().apply { put("name", name) }
        val (code, response) = httpPost("/api/devices/register", body.toString())
        if (code != 201) {
            val msg = try { JSONObject(response).optString("error", "Registration failed") } catch (_: Exception) { "Registration failed" }
            return RegisterResult("", "", msg)
        }
        val json = JSONObject(response)
        return RegisterResult(
            deviceId = json.getString("device_id"),
            authKey = json.getString("auth_key")
        )
    }

    fun postMeasurement(
        authKey: String,
        timestamp: Long,
        gpsLat: Double?,
        gpsLon: Double?,
        wifiScans: List<ScanEntry>
    ): MeasurementResult {
        val scansArr = org.json.JSONArray()
        for (s in wifiScans) {
            scansArr.put(JSONObject().apply {
                put("ssid", s.ssid)
                put("bssid", s.bssid)
                put("rssi", s.rssi)
            })
        }
        val body = JSONObject().apply {
            put("timestamp", timestamp)
            if (gpsLat != null) put("gps_lat", gpsLat)
            if (gpsLon != null) put("gps_lon", gpsLon)
            put("wifi_scans", scansArr)
        }

        val (code, response) = httpPost("/api/measurements", body.toString(), authKey)
        if (code != 201) {
            val msg = try { JSONObject(response).optString("error", "Post failed") } catch (_: Exception) { "Post failed" }
            return MeasurementResult(false, error = msg)
        }
        val json = JSONObject(response)
        return MeasurementResult(
            success = true,
            measurementId = json.optLong("measurement_id")
        )
    }

    data class ScanEntry(val ssid: String?, val bssid: String, val rssi: Int)

    private fun httpPost(path: String, jsonBody: String, deviceKey: String? = null): Pair<Int, String> {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (deviceKey != null) {
            conn.setRequestProperty("X-Device-Key", deviceKey)
        }
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        try {
            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }
            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            return Pair(code, resp)
        } catch (_: Exception) {
            val code = conn.responseCode
            val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            return Pair(code, err)
        } finally {
            conn.disconnect()
        }
    }
}
