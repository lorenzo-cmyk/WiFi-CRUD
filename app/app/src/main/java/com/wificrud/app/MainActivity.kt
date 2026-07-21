package com.wificrud.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wificrud.app.api.ApiClient
import com.wificrud.app.data.CredentialStore
import com.wificrud.app.scan.ScanService
import com.wificrud.app.scan.ScanState
import com.wificrud.app.scan.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var creds: CredentialStore
    private val api = ApiClient()
    private lateinit var scanner: WifiScanner

    private lateinit var setupView: LinearLayout
    private lateinit var dashboardView: LinearLayout

    private lateinit var btnPermissions: Button
    private lateinit var btnRegister: Button
    private lateinit var btnStartScan: Button
    private lateinit var btnStopScan: Button

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvThrottleWarning: TextView
    private lateinit var tvSetupStatus: TextView
    private lateinit var setupSpinner: ProgressBar

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var tvScanError: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvLastTimestamp: TextView
    private lateinit var tvLastGps: TextView
    private lateinit var tvLastNetworks: TextView
    private lateinit var tvNetworksTitle: TextView
    private lateinit var tvNetworksList: TextView
    private lateinit var etInterval: EditText

    private var countdownJob: Job? = null
    private var scanObserverJob: Job? = null

    private val requiredPermissions: List<String> by lazy {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        creds = CredentialStore(applicationContext)
        scanner = WifiScanner(this)

        bindViews()
        setupViews()
        observeScanState()

        if (creds.isRegistered) showDashboard()
        else showSetup()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        scanObserverJob?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        setupView = findViewById(R.id.setupView)
        dashboardView = findViewById(R.id.dashboardView)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnRegister = findViewById(R.id.btnRegister)
        btnStartScan = findViewById(R.id.btnStartScan)
        btnStopScan = findViewById(R.id.btnStopScan)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvThrottleWarning = findViewById(R.id.tvThrottleWarning)
        tvSetupStatus = findViewById(R.id.tvSetupStatus)
        setupSpinner = findViewById(R.id.setupSpinner)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvScanError = findViewById(R.id.tvScanError)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvLastTimestamp = findViewById(R.id.tvLastTimestamp)
        tvLastGps = findViewById(R.id.tvLastGps)
        tvLastNetworks = findViewById(R.id.tvLastNetworks)
        tvNetworksTitle = findViewById(R.id.tvNetworksTitle)
        tvNetworksList = findViewById(R.id.tvNetworksList)
        etInterval = findViewById(R.id.etInterval)
    }

    private fun setupViews() {
        btnPermissions.setOnClickListener {
            val needed = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        }

        btnRegister.setOnClickListener { registerDevice() }

        etInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ScanState.state.value.isScanning) {
                    btnStartScan.isEnabled = false
                    return
                }
                val v = s?.toString()?.toIntOrNull() ?: 0
                btnStartScan.isEnabled = v in 5..120
            }
        })

        btnStartScan.setOnClickListener {
            val interval = etInterval.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 30
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return@setOnClickListener
            }
            ScanService.start(this, interval)
            btnStartScan.isEnabled = false
            btnStopScan.isEnabled = true
        }

        btnStopScan.setOnClickListener {
            ScanService.stop(this)
            btnStartScan.isEnabled = true
            btnStopScan.isEnabled = false
        }
    }

    private fun showSetup() {
        dashboardView.visibility = android.view.View.GONE
        setupView.visibility = android.view.View.VISIBLE
        updatePermissionStatus()
    }

    private fun showDashboard() {
        setupView.visibility = android.view.View.GONE
        dashboardView.visibility = android.view.View.VISIBLE
        tvDeviceInfo.text = "Device: ${creds.deviceName}\nID: ${creds.deviceId}"
    }

    private fun updatePermissionStatus() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            tvPermissionStatus.setTextColor(0xFF2E7D32.toInt())
            tvPermissionStatus.text = "All permissions granted"
            btnPermissions.isEnabled = false
            btnPermissions.text = "Permissions OK"
            checkThrottleAndEnableRegister()
        } else {
            tvPermissionStatus.setTextColor(0xFFC62828.toInt())
            val missing = requiredPermissions.count {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            tvPermissionStatus.text = "$missing permission(s) still required"
            btnPermissions.isEnabled = true
            btnPermissions.text = "Grant Permissions"
            btnRegister.isEnabled = false
        }
    }

    private fun checkThrottleAndEnableRegister() {
        if (scanner.isThrottleWarningNeeded()) {
            tvThrottleWarning.visibility = android.view.View.VISIBLE
            tvThrottleWarning.text = buildString {
                append("Warning: WiFi scan throttling is enabled.\n")
                append("On Android 9+, apps are limited to 4 WiFi scans every 2 minutes.\n")
                append("To disable: Developer Options \u2192 WiFi scan throttling \u2192 OFF.\n\n")
                append("The app will still work but scans may be rate-limited.")
            }
        } else {
            tvThrottleWarning.visibility = android.view.View.GONE
        }
        btnRegister.isEnabled = true
    }

    private fun registerDevice() {
        btnRegister.isEnabled = false
        setupSpinner.visibility = android.view.View.VISIBLE
        tvSetupStatus.setTextColor(0xFF666666.toInt())
        tvSetupStatus.text = "Registering device..."
        val deviceName = Build.MODEL.take(64)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.register(deviceName) }
            setupSpinner.visibility = android.view.View.GONE
            if (result.error != null) {
                tvSetupStatus.setTextColor(0xFFC62828.toInt())
                tvSetupStatus.text = "Error: ${result.error}"
                btnRegister.isEnabled = true
            } else {
                creds.deviceId = result.deviceId
                creds.authKey = result.authKey
                creds.deviceName = deviceName
                tvSetupStatus.setTextColor(0xFF2E7D32.toInt())
                tvSetupStatus.text = "Device registered!"
                showDashboard()
                Toast.makeText(this@MainActivity, "Device registered", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeScanState() {
        scanObserverJob = lifecycleScope.launch {
            ScanState.state.collectLatest { state ->
                updateDashboard(state)
            }
        }
    }

    private fun updateDashboard(state: ScanState.State) {
        if (state.isScanning) {
            tvScanStatus.visibility = android.view.View.VISIBLE
            tvScanStatus.text = "Scanning every ${state.intervalSeconds}s \u2014 active"
            btnStartScan.isEnabled = false
            btnStopScan.isEnabled = true
            etInterval.isEnabled = false

            countdownJob?.cancel()
            countdownJob = lifecycleScope.launch { countdownLoop(state.nextScanAt) }
        } else {
            tvScanStatus.visibility = android.view.View.GONE
            tvCountdown.text = ""
            btnStartScan.isEnabled = true
            btnStopScan.isEnabled = false
            etInterval.isEnabled = true
            countdownJob?.cancel()
        }

        if (state.error != null) {
            tvScanError.visibility = android.view.View.VISIBLE
            tvScanError.text = state.error
        } else {
            tvScanError.visibility = android.view.View.GONE
        }

        val m = state.lastMeasurement
        if (m != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            tvLastTimestamp.text = "Timestamp: ${fmt.format(Date(m.timestamp * 1000))}"
            tvLastGps.text = "GPS: ${m.gpsLat?.let { "%.5f".format(it) } ?: "\u2014"}, ${m.gpsLon?.let { "%.5f".format(it) } ?: "\u2014"}"
            tvLastNetworks.text = "WiFi networks found: ${m.networks}"

            if (m.scans.isNotEmpty()) {
                tvNetworksTitle.visibility = android.view.View.VISIBLE
                tvNetworksList.visibility = android.view.View.VISIBLE
                tvNetworksList.text = m.scans.joinToString("\n") { s ->
                    "  ${s.ssid ?: "\u2014"}  |  ${s.bssid}  |  ${s.rssi} dBm"
                }
            } else {
                tvNetworksTitle.visibility = android.view.View.GONE
                tvNetworksList.visibility = android.view.View.GONE
            }
        }
    }

    private suspend fun countdownLoop(nextScanAt: Long) {
        while (true) {
            val remaining = nextScanAt - System.currentTimeMillis()
            if (remaining <= 0) {
                tvCountdown.text = "Scanning now..."
                delay(500)
                continue
            }
            val secs = remaining / 1000
            val label = when {
                secs > 60 -> "${secs / 60}m ${secs % 60}s"
                else -> "${secs}s"
            }
            tvCountdown.text = "Next scan in: $label"
            delay(500)
        }
    }
}
