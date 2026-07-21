package com.wificrud.app.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScanState {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val isScanning: Boolean = false,
        val intervalSeconds: Int = 30,
        val nextScanAt: Long = 0L,
        val lastMeasurement: Measurement? = null,
        val error: String? = null,
    )

    data class Measurement(
        val timestamp: Long,
        val gpsLat: Double?,
        val gpsLon: Double?,
        val networks: Int,
        val measurementId: Long?,
        val scans: List<ScanEntry> = emptyList(),
    )

    data class ScanEntry(
        val ssid: String?,
        val bssid: String,
        val rssi: Int,
    )

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }
}
