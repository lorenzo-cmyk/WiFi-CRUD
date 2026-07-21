package com.wificrud.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wificrud.app.api.ApiClient
import com.wificrud.app.data.CredentialStore
import com.wificrud.app.scan.ScanService
import com.wificrud.app.scan.ScanState
import com.wificrud.app.scan.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var creds: CredentialStore
    private lateinit var scanner: WifiScanner
    private val api = ApiClient()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> updatePerms() }

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> updatePerms() }

    private val permissionsDef = buildList {
        add(PermDef("ACCESS_FINE_LOCATION", Manifest.permission.ACCESS_FINE_LOCATION, "GPS location"))
        add(PermDef("ACCESS_WIFI_STATE", Manifest.permission.ACCESS_WIFI_STATE, "WiFi state"))
        add(PermDef("CHANGE_WIFI_STATE", Manifest.permission.CHANGE_WIFI_STATE, "Change WiFi"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermDef("NEARBY_WIFI_DEVICES", Manifest.permission.NEARBY_WIFI_DEVICES, "Nearby WiFi devices"))
            add(PermDef("POST_NOTIFICATIONS", Manifest.permission.POST_NOTIFICATIONS, "Notifications"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(PermDef("ACCESS_BACKGROUND_LOCATION", Manifest.permission.ACCESS_BACKGROUND_LOCATION, "Background location (optional)"))
        }
    }

    private var refreshPerms by mutableIntStateOf(0)

    private fun updatePerms() { refreshPerms++ }

    private fun checkPerm(name: String): Boolean =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creds = CredentialStore(applicationContext)
        scanner = WifiScanner(this)

        setContent {
            MaterialTheme {
                val state by ScanState.state.collectAsState()
                refreshPerms // force recomposition

                if (creds.isRegistered) {
                    DashboardScreen(
                        state = state,
                        onStart = { interval ->
                            val bg = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                checkPerm(bg) != true
                            ) {
                                bgLocationLauncher.launch(bg)
                            } else {
                                ScanService.start(this@MainActivity, interval)
                            }
                        },
                        onStop = { ScanService.stop(this@MainActivity) },
                    )
                } else {
                    SetupScreen(
                        perms = permissionsDef,
                        checkPerm = { checkPerm(it) },
                        onRequestPerms = {
                            val needed = permissionsDef
                                .filter { !checkPerm(it.manifestName) }
                                .filter { it.manifestName != Manifest.permission.ACCESS_BACKGROUND_LOCATION }
                                .map { it.manifestName }
                                .toTypedArray()
                            permLauncher.launch(needed)
                        },
                        onRequestBgLocation = {
                            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                        onRegister = { registerDevice() },
                    )
                }
            }
        }
    }

    private fun registerDevice() {
        kotlinx.coroutines.MainScope().launch {
            val name = Build.MODEL.take(64)
            val result = withContext(Dispatchers.IO) { api.register(name) }
            if (result.error != null) {
                // TODO show error
                return@launch
            }
            creds.deviceId = result.deviceId
            creds.authKey = result.authKey
            creds.deviceName = name
            updatePerms()
        }
    }
}

data class PermDef(val label: String, val manifestName: String, val display: String)

@Composable
private fun SetupScreen(
    perms: List<PermDef>,
    checkPerm: (String) -> Boolean,
    onRequestPerms: () -> Unit,
    onRequestBgLocation: () -> Unit,
    onRegister: () -> Unit,
) {
    val ctx = LocalContext.current
    val scanner = remember { WifiScanner(ctx) }
    var registering by remember { mutableStateOf(false) }
    var regError by remember { mutableStateOf<String?>(null) }

    val allGranted = perms.all { checkPerm(it.manifestName) }
    val missingCount = perms.count { !checkPerm(it.manifestName) }
    val throttleWarning = remember { scanner.isThrottleWarningNeeded() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("WiFi CRUD", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("First-time setup", fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(20.dp))

        Text("Permissions", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))

        perms.forEach { perm ->
            val granted = checkPerm(perm.manifestName)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = if (granted) "\u2705" else "\u274C",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = perm.display,
                    fontSize = 14.sp,
                    color = if (granted) Color(0xFF2E7D32) else Color.Unspecified,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRequestPerms,
            enabled = missingCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (missingCount > 0) {
                Text("Grant $missingCount missing permission(s)")
            } else {
                Text("All permissions granted")
            }
        }

        if (allGranted) {
            val bgName = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            val bgDeclared = perms.any { it.manifestName == bgName }
            if (bgDeclared && !checkPerm(bgName)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRequestBgLocation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant background location (recommended)")
                }
            }
        }

        if (allGranted && throttleWarning) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = buildString {
                        append("Warning: WiFi scan throttling is enabled.\n")
                        append("On Android 9+, apps are limited to 4 WiFi scans every 2 minutes.\n")
                        append("To disable: Developer Options \u2192 WiFi scan throttling \u2192 OFF.\n\n")
                        append("The app will still work but scans may be rate-limited.")
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF856404),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (allGranted) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    registering = true
                    regError = null
                    onRegister()
                },
                enabled = !registering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (registering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .width(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Register Device")
            }
            regError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color.Red, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: ScanState.State,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val ctx = LocalContext.current
    val creds = remember { CredentialStore(ctx) }
    var intervalText by remember { mutableStateOf("30") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("WiFi CRUD", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Device: ${creds.deviceName}\nID: ${creds.deviceId}",
            fontSize = 12.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Interval (s):", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { v ->
                    if (v.all { it.isDigit() } && v.length <= 3) intervalText = v
                },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                enabled = !state.isScanning,
            )
            Spacer(Modifier.width(8.dp))
            Text("(5\u2013120)", fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val interval = intervalText.toIntOrNull()?.coerceIn(5, 120) ?: 30
                    onStart(interval)
                },
                enabled = !state.isScanning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Start Scanning")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.isScanning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stop")
            }
        }

        if (state.isScanning) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Scanning every ${state.intervalSeconds}s \u2014 active",
                    fontSize = 14.sp,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            CountdownText(nextScanAt = state.nextScanAt)
        }

        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.error,
                    fontSize = 13.sp,
                    color = Color(0xFFC62828),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        val m = state.lastMeasurement
        if (m != null) {
            Spacer(Modifier.height(16.dp))
            Text("Last measurement", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
            Text("Timestamp: ${fmt.format(Date(m.timestamp * 1000))}", fontSize = 13.sp)
            Text(
                "GPS: ${m.gpsLat?.let { "%.5f".format(it) } ?: "\u2014"}, ${m.gpsLon?.let { "%.5f".format(it) } ?: "\u2014"}",
                fontSize = 13.sp,
            )
            Text("WiFi networks found: ${m.networks}", fontSize = 13.sp)

            if (m.scans.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("WiFi Networks", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = m.scans.joinToString("\n") { s ->
                            "  ${s.ssid ?: "\u2014"}  |  ${s.bssid}  |  ${s.rssi} dBm"
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownText(nextScanAt: Long) {
    var label by remember { mutableStateOf("") }
    LaunchedEffect(nextScanAt) {
        while (true) {
            val remaining = nextScanAt - System.currentTimeMillis()
            label = if (remaining <= 0) {
                "Scanning now..."
            } else {
                val secs = remaining / 1000
                "Next scan in: ${secs / 60}m ${secs % 60}s"
            }
            delay(500)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(12.dp),
        )
    }
}
