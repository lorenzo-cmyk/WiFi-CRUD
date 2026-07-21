package com.wificrud.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wificrud.app.api.ApiClient
import com.wificrud.app.data.CredentialStore
import com.wificrud.app.scan.WifiScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var creds: CredentialStore
    private val api = ApiClient()
    private lateinit var scanner: WifiScanner
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var setupView: LinearLayout
    private lateinit var mainView: LinearLayout
    private lateinit var btnPermissions: Button
    private lateinit var btnRegister: Button
    private lateinit var btnScan: Button
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvThrottleWarning: TextView
    private lateinit var tvSetupStatus: TextView
    private lateinit var setupSpinner: ProgressBar
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvScanStatus: TextView

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

        if (creds.isRegistered) {
            showMainView()
        } else {
            showSetupView()
        }
    }

    private fun bindViews() {
        setupView = findViewById(R.id.setupView)
        mainView = findViewById(R.id.mainView)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnRegister = findViewById(R.id.btnRegister)
        btnScan = findViewById(R.id.btnScan)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvThrottleWarning = findViewById(R.id.tvThrottleWarning)
        tvSetupStatus = findViewById(R.id.tvSetupStatus)
        setupSpinner = findViewById(R.id.setupSpinner)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvScanStatus = findViewById(R.id.tvScanStatus)
    }

    private fun setupViews() {
        btnPermissions.setOnClickListener {
            val needed = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }

        btnRegister.setOnClickListener {
            registerDevice()
        }

        btnScan.setOnClickListener {
            performScan()
        }
    }

    private fun showSetupView() {
        mainView.visibility = android.view.View.GONE
        setupView.visibility = android.view.View.VISIBLE
        updatePermissionStatus()
    }

    private fun showMainView() {
        setupView.visibility = android.view.View.GONE
        mainView.visibility = android.view.View.VISIBLE
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
        val warning = scanner.isThrottleWarningNeeded()
        if (warning) {
            tvThrottleWarning.visibility = android.view.View.VISIBLE
            tvThrottleWarning.text = buildString {
                append("Warning: WiFi scan throttling is enabled.\n")
                append("On Android 9+, apps are limited to 4 WiFi scans every 2 minutes.\n")
                append("To disable: Developer Options → WiFi scan throttling → OFF.\n\n")
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

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                api.register(deviceName)
            }

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
                showMainView()
                Toast.makeText(
                    this@MainActivity,
                    "Device registered successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performScan() {
        btnScan.isEnabled = false
        tvScanStatus.text = "Scanning..."

        scope.launch {
            val scanStarted = withContext(Dispatchers.IO) {
                scanner.startScan()
            }

            if (!scanStarted) {
                tvScanStatus.text = "Scan failed to start. Check WiFi is enabled."
                btnScan.isEnabled = true
                return@launch
            }

            kotlinx.coroutines.delay(3000)

            val results = withContext(Dispatchers.IO) {
                scanner.getScanResults()
            }

            if (results.isEmpty()) {
                tvScanStatus.text = "No networks found. Try again."
                btnScan.isEnabled = true
                return@launch
            }

            tvScanStatus.text = "Found ${results.size} networks. Sending..."

            val now = System.currentTimeMillis() / 1000

            val scans = results.map { r ->
                ApiClient.ScanEntry(
                    ssid = r.ssid,
                    bssid = r.bssid,
                    rssi = r.level
                )
            }

            val gpsLat: Double? = null
            val gpsLon: Double? = null

            val postResult = withContext(Dispatchers.IO) {
                api.postMeasurement(
                    authKey = creds.authKey,
                    timestamp = now,
                    gpsLat = gpsLat,
                    gpsLon = gpsLon,
                    wifiScans = scans
                )
            }

            if (postResult.success) {
                tvScanStatus.text = "Sent ${results.size} networks (ID: ${postResult.measurementId})"
            } else {
                tvScanStatus.text = "Send failed: ${postResult.error}"
            }

            btnScan.isEnabled = true
        }
    }
}
