package com.veygax.eventhorizon.ui.activities

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.veygax.eventhorizon.core.AppInterceptor
import com.veygax.eventhorizon.system.DnsBlockerService
import com.veygax.eventhorizon.utils.CpuMonitorInfo
import com.veygax.eventhorizon.utils.CpuUtils
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TweaksActivity : ComponentActivity() {

    var isRgbExecutingState = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startDnsService()
        }
    }
    
    private val ledColorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            isRgbExecutingState.value = true
        }
    }

    fun startDnsService() {
        val intent = Intent(this, DnsBlockerService::class.java).setAction(DnsBlockerService.ACTION_START)
        startService(intent)
    }

    fun stopDnsService() {
        val intent = Intent(this, DnsBlockerService::class.java).setAction(DnsBlockerService.ACTION_STOP)
        startService(intent)
    }

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startDnsService()
        }
    }
    
    fun launchCustomColorPicker() {
        ledColorLauncher.launch(Intent(this, LedColorActivity::class.java))
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isRooted = intent.getBooleanExtra("is_rooted", false)
        setContent {
            val useDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx = LocalContext.current
                if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (useDarkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TweaksScreen(
                        activity = this,
                        isRooted = isRooted,
                        isDnsServiceRunning = { isServiceRunning(this, DnsBlockerService::class.java) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweaksScreen(
    activity: TweaksActivity,
    isRooted: Boolean,
    isDnsServiceRunning: () -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE) }
    val scriptFile = remember { File(context.filesDir, "rgb_led.sh") }

    var isRgbExecuting by activity.isRgbExecutingState
    
    var runOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("rgb_on_boot", false)) }
    var blockerOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("blocker_on_boot", false)) }
    var isBlockerEnabled by remember { mutableStateOf(false) }
    var minFreqOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("min_freq_on_boot", false)) }
    var isMinFreqExecuting by remember { mutableStateOf(false) }
    var isCpuPerfMode by remember { mutableStateOf(false) }
    var isWirelessAdbEnabled by remember { mutableStateOf(false) }
    var wifiIpAddress by remember { mutableStateOf("N/A") }
    var wirelessAdbOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("wireless_adb_on_boot", false)) }

    // --- CPU Monitor States ---
    var cpuMonitorInfo by remember { mutableStateOf(CpuMonitorInfo()) }
    var isFahrenheit by remember { mutableStateOf(sharedPrefs.getBoolean("temp_unit_is_fahrenheit", false)) }

    // --- Intercept Startup Apps ---
    var isInterceptorEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("intercept_startup_apps", false)) }

    var uiSwitchState by rememberSaveable { mutableStateOf(0) }
    var isVoidTransitionEnabled by rememberSaveable { mutableStateOf(false) }
    var isTeleportLimitDisabled by rememberSaveable { mutableStateOf(false) }
    var isNavigatorFogEnabled by rememberSaveable { mutableStateOf(false) }
    var isPanelScalingEnabled by rememberSaveable { mutableStateOf(false) }
    var isInfinitePanelsEnabled by rememberSaveable { mutableStateOf(false) }

    // Fix for vrshell hanging with domain blocker on
    val runCommandWithWifiToggleIfNeeded: suspend (String) -> Unit = { command ->
        val needsFix = isBlockerEnabled && command.contains("am force-stop com.oculus.vrshell")
        if (needsFix) {
            val chainedCommand = """
                svc wifi disable
                $command
                svc wifi enable
            """.trimIndent()
            RootUtils.runAsRoot(chainedCommand)
        } else {
            RootUtils.runAsRoot(command)
        }
    }

    // Prepare the script file in the background on startup
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            if (!scriptFile.exists()) {
                scriptFile.writeText(TweakCommands.RGB_SCRIPT)
                RootUtils.runAsRoot("chmod +x ${scriptFile.absolutePath}")
            }
        }
    }

    // --- LaunchedEffect for periodic CPU monitoring ---
    LaunchedEffect(isRooted) {
        if (isRooted) {
            while (true) {
                // Run on a background thread to avoid blocking UI
                withContext(Dispatchers.IO) {
                    cpuMonitorInfo = CpuUtils.getCpuMonitorInfo()
                }
                delay(2000) // Refresh every 2 seconds
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBlockerEnabled = isDnsServiceRunning()
                runOnBoot = sharedPrefs.getBoolean("rgb_on_boot", false)
                if (isRooted) {
                    // Launch on a background thread to keep UI responsive
                    coroutineScope.launch(Dispatchers.IO) {
                        val runningRgb = RootUtils.runAsRoot("ps -ef | grep -E 'rgb_led.sh|custom_led.sh' | grep -v grep")
                        isRgbExecuting = runningRgb.trim().isNotEmpty() && !runningRgb.contains("No such file")
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
                        wifiIpAddress = if (ip == "0.0.0.0") "Not connected to Wi-Fi" else ip
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }


    LaunchedEffect(isRooted) {
        if (isRooted) {
            // Perform all heavy operations on a background thread
            withContext(Dispatchers.IO) {
                isBlockerEnabled = isDnsServiceRunning()
                
                // Launch all checks in parallel using async for significant speed improvement
                val runningRgbDeferred = async { RootUtils.runAsRoot("ps -ef | grep -E 'rgb_led.sh|custom_led.sh' | grep -v grep") }
                val runningCpuDeferred = async { RootUtils.runAsRoot("ps -ef | grep ${CpuUtils.SCRIPT_NAME} | grep -v grep") }
                val cpuGovDeferred = async { RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") }
                val adbPortDeferred = async { RootUtils.runAsRoot("getprop service.adb.tcp.port") }
                val uiStateValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc debug_navigator_state") }
                val transitionValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc shell_immersive_transitions_enabled") }
                val teleportValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc shell_teleport_anywhere") }
                val fogValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc navigator_background_disabled") }
                val panelScalingValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc panel_scaling") }
                val infinitePanelsValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc debug_infinite_spatial_panels_enabled") }

                // Now, wait for all the results (await) and update the UI states
                isRgbExecuting = runningRgbDeferred.await().trim().isNotEmpty()
                isMinFreqExecuting = runningCpuDeferred.await().trim().isNotEmpty()
                isCpuPerfMode = cpuGovDeferred.await().trim() == "performance"
                isWirelessAdbEnabled = adbPortDeferred.await().trim() == "5555"
                uiSwitchState = if (uiStateValueDeferred.await().contains(": 1")) 1 else 0
                isVoidTransitionEnabled = transitionValueDeferred.await().contains(": false")
                isTeleportLimitDisabled = teleportValueDeferred.await().contains(": true")
                isNavigatorFogEnabled = fogValueDeferred.await().contains(": false")
                isPanelScalingEnabled = panelScalingValueDeferred.await().contains(": true")
                isInfinitePanelsEnabled = infinitePanelsValueDeferred.await().contains(": true")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("eventhorizon AIO") },
                navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TweakSection(title = "General") {
                    TweakCard("Rainbow LED", "Cycles notification LED through colors.") {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Run on Boot", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = runOnBoot,
                                    onCheckedChange = { checked ->
                                        runOnBoot = checked
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("rgb_on_boot", checked)
                                        if (checked) {
                                            editor.putBoolean("custom_led_on_boot", false)
                                        }
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "Rainbow LED on Boot Enabled" else "Rainbow LED on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        // Launch on background thread
                                        coroutineScope.launch(Dispatchers.IO) {
                                            sharedPrefs.edit().putBoolean("custom_led_active", false).apply()
                                            if (isRgbExecuting) {
                                                isRgbExecuting = false
                                                RootUtils.runAsRoot("pkill -f rgb_led.sh || pkill -f custom_led.sh")
                                                RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
                                            } else {
                                                isRgbExecuting = true
                                                RootUtils.runAsRoot("pkill -f custom_led.sh || true")
                                                RootUtils.runAsRoot("${scriptFile.absolutePath} &")
                                            }
                                        }
                                    },
                                    enabled = isRooted
                                ) { Text(if (isRgbExecuting) "Stop" else "Start") }

                                Button(
                                    onClick = {
                                        // Launch on background thread
                                        coroutineScope.launch(Dispatchers.IO) {
                                            if (isRgbExecuting) {
                                                isRgbExecuting = false
                                                RootUtils.runAsRoot("pkill -f rgb_led.sh || pkill -f custom_led.sh")
                                                RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
                                            }
                                            // UI operation should be on main thread
                                            withContext(Dispatchers.Main) {
                                                activity.launchCustomColorPicker()
                                            }
                                        }
                                    },
                                    enabled = isRooted
                                ) {
                                    Text("Custom")
                                }
                            }
                        }
                    }
                    TweakCard("Meta Domain Blocker", "Blocks Meta/Facebook domains using a DNS filter (no root).") {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = blockerOnBoot, onCheckedChange = { checked ->
                                    blockerOnBoot = checked
                                    sharedPrefs.edit().putBoolean("blocker_on_boot", checked).apply()
                                    coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "DNS Blocker on Boot Enabled" else "DNS Blocker on Boot Disabled") }
                                })
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Blocker Status", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = isBlockerEnabled, onCheckedChange = { isEnabled ->
                                    isBlockerEnabled = isEnabled
                                    if (isEnabled) {
                                        activity.requestVpnPermission()
                                    } else {
                                        activity.stopDnsService()
                                    }
                                })
                            }
                        }
                    }
                    TweakCard("Wireless ADB", "Enables connecting to ADB over Wi-Fi.") {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = wirelessAdbOnBoot,
                                    onCheckedChange = { checked ->
                                        wirelessAdbOnBoot = checked
                                        sharedPrefs.edit().putBoolean("wireless_adb_on_boot", checked).apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "Wireless ADB on Boot Enabled" else "Wireless ADB on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (isWirelessAdbEnabled) {
                                Text(
                                    text = "adb connect $wifiIpAddress:5555",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = isWirelessAdbEnabled,
                                onCheckedChange = { isEnabled ->
                                    isWirelessAdbEnabled = isEnabled
                                    // Launch on background thread
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val port = if (isEnabled) "5555" else "-1"
                                        RootUtils.runAsRoot("setprop service.adb.tcp.port $port")
                                        RootUtils.runAsRoot("stop adbd && start adbd")
                                        withContext(Dispatchers.Main) {
                                            snackbarHostState.showSnackbar(if (isEnabled) "Wireless ADB Enabled." else "Wireless ADB Disabled.")
                                        }
                                    }
                                },
                                enabled = isRooted
                            )
                        }
                    }
                    TweakCard("Intercept App Launching", "Stops Horizon Feed and Social Connections from being started.") {
                        Switch(
                            checked = isInterceptorEnabled,
                            onCheckedChange = { isEnabled ->
                                isInterceptorEnabled = isEnabled
                                sharedPrefs.edit().putBoolean("intercept_startup_apps", isEnabled).apply()

                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isEnabled) {
                                        AppInterceptor.start(context)
                                    } else {
                                        AppInterceptor.stop()
                                    }
                                    withContext(Dispatchers.Main) {
                                        snackbarHostState.showSnackbar(if (isEnabled) "App Interceptor Enabled." else "App Interceptor Disabled.")
                                    }
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard(
                        title = "Fix Double-Tap Passthrough",
                        description = "Applies fix for broken Double-Tap Passthrough feature."
                    ) {
                        Button(
                            onClick = {
                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCommandWithWifiToggleIfNeeded(TweakCommands.FIX_PASSTHROUGH)
                                }
                            },
                            enabled = isRooted
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }

            item {
                TweakSection(title = "System UI") {
                    TweakCard("UI Switching", "Switches between Navigator and Dock without rebooting") {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (uiSwitchState == 1) "Navigator UI" else "Dock UI", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = uiSwitchState == 1,
                                onCheckedChange = { isNavigator ->
                                    uiSwitchState = if (isNavigator) 1 else 0
                                    // Launch on background thread
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val command = if (isNavigator) TweakCommands.SET_UI_NAVIGATOR else TweakCommands.SET_UI_DOCK
                                        runCommandWithWifiToggleIfNeeded(command)
                                    }
                                },
                                enabled = isRooted
                            )
                        }
                    }
                    TweakCard("Void Transition", "Switches between Immersive transition and Void Transition") {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (isVoidTransitionEnabled) "Void Transition" else "Immersive Transition", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = isVoidTransitionEnabled,
                                onCheckedChange = { isEnabled ->
                                    isVoidTransitionEnabled = isEnabled
                                    // Launch on background thread
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val command = if (isEnabled) TweakCommands.SET_TRANSITION_VOID else TweakCommands.SET_TRANSITION_IMMERSIVE
                                        runCommandWithWifiToggleIfNeeded(command)
                                    }
                                },
                                enabled = isRooted
                            )
                        }
                    }
                    TweakCard("Teleport Anywhere", "Teleport anywhere in the home environment") {
                        Switch(
                            checked = isTeleportLimitDisabled,
                            onCheckedChange = { isEnabled ->
                                isTeleportLimitDisabled = isEnabled
                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    val command = if (isEnabled) TweakCommands.DISABLE_TELEPORT_LIMIT else TweakCommands.ENABLE_TELEPORT_LIMIT
                                    RootUtils.runAsRoot(command)
                                    withContext(Dispatchers.Main) {
                                        snackbarHostState.showSnackbar(if (isEnabled) "Teleport Anywhere Enabled." else "Teleport Anywhere Disabled.")
                                    }
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard("Navigator Fog", "Enables the fog effect in the navigator background.") {
                        Switch(
                            checked = isNavigatorFogEnabled,
                            onCheckedChange = { isEnabled ->
                                isNavigatorFogEnabled = isEnabled
                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    val command = if (isEnabled) TweakCommands.ENABLE_NAVIGATOR_FOG else TweakCommands.DISABLE_NAVIGATOR_FOG
                                    runCommandWithWifiToggleIfNeeded(command)
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard("Fixed Panel Scaling", "Makes panels change size with distance.") {
                        Switch(
                            checked = isPanelScalingEnabled,
                            onCheckedChange = { isEnabled ->
                                isPanelScalingEnabled = isEnabled
                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    val command = if (isEnabled) TweakCommands.ENABLE_PANEL_SCALING else TweakCommands.DISABLE_PANEL_SCALING
                                    runCommandWithWifiToggleIfNeeded(command)
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard("Infinite Floating Panels", "Enables infinite floating panels") {
                        Switch(
                            checked = isInfinitePanelsEnabled,
                            onCheckedChange = { isEnabled ->
                                isInfinitePanelsEnabled = isEnabled
                                // Launch on background thread
                                coroutineScope.launch(Dispatchers.IO) {
                                    val command = if (isEnabled) TweakCommands.ENABLE_INFINITE_PANELS else TweakCommands.DISABLE_INFINITE_PANELS
                                    runCommandWithWifiToggleIfNeeded(command)
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard("Spoof Build Type", "Spoof build type such as userdebug to enable features such as Dogfooding or ShellDebug. This will restart your device's interface.") {
                        var showCustomDialog by remember { mutableStateOf(false) }
                        var customType by remember { mutableStateOf("") }
                        val runSpoof: (String) -> Unit = { type ->
                            coroutineScope.launch(Dispatchers.IO) {
                                RootUtils.runAsRoot("magisk resetprop ro.build.type $type")
                                withContext(Dispatchers.Main) {
                                    snackbarHostState.showSnackbar("Build type spoofed to '$type'. Restarting Zygote...")
                                RootUtils.runAsRoot("setprop ctl.restart zygote")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { runSpoof("userdebug") },
                                enabled = isRooted
                            ) { Text("userdebug") }
                            Button(
                                onClick = { showCustomDialog = true },
                                enabled = isRooted
                            ) { Text("Custom") }
                        }

                        if (showCustomDialog) {
                            AlertDialog(
                                onDismissRequest = { showCustomDialog = false },
                                title = { Text("Enter Custom Build Type") },
                                text = {
                                    OutlinedTextField(
                                        value = customType,
                                        onValueChange = { customType = it },
                                        label = { Text("Build Type (default is user)") },
                                        singleLine = true
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showCustomDialog = false
                                            runSpoof(if (customType.isNotBlank()) customType else "user")
                                        }
                                    ) { Text("OK") }
                                },
                                dismissButton = {
                                    Button(onClick = { showCustomDialog = false }) { Text("Cancel") }
                                }
                            )
                        }
                    }
                }
            }
            
            item {
                TweakSection(title = "CPU Tweaks") {
                    // --- Centered and Compacted CPU Monitor Card ---
                    if (isRooted) {
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = "CPU Monitor", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                // Declare temp variables here so they are in scope for the Text below
                                val tempC = cpuMonitorInfo.tempCelsius
                                val tempF = (tempC * 9 / 5) + 32

                                // Temperature Toggle
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("°C")
                                    Switch(
                                        checked = isFahrenheit,
                                        onCheckedChange = { checked ->
                                            isFahrenheit = checked
                                            sharedPrefs.edit().putBoolean("temp_unit_is_fahrenheit", checked).apply()
                                        },
                                        modifier = Modifier.height(24.dp).padding(horizontal = 8.dp)
                                    )
                                    Text("°F")
                                }
                                Text(
                                    text = if (isFahrenheit) "$tempF °F" else "$tempC °C",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                
                                // Core Details
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Big Cores", fontWeight = FontWeight.Bold)
                                        Text("${cpuMonitorInfo.bigCoreUsagePercent}% Usage")
                                        Text("${cpuMonitorInfo.bigCoreMaxFreqMhz} - ${cpuMonitorInfo.bigCoreMinFreqMhz} MHz")
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("LITTLE Cores", fontWeight = FontWeight.Bold)
                                        Text("${cpuMonitorInfo.littleCoreUsagePercent}% Usage")
                                        Text("${cpuMonitorInfo.littleCoreMaxFreqMhz} - ${cpuMonitorInfo.littleCoreMinFreqMhz} MHz")
                                    }
                                }
                            }
                        }
                    }
                    TweakCard("Set Min Frequency", "Sets minimum CPU frequency to 691MHz (Instead of max)") {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = minFreqOnBoot,
                                    onCheckedChange = { checked ->
                                        minFreqOnBoot = checked
                                        sharedPrefs.edit().putBoolean("min_freq_on_boot", checked).apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "Min Freq on Boot Enabled" else "Min Freq on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    // Launch on background thread
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val shouldStart = !isMinFreqExecuting
                                        isMinFreqExecuting = shouldStart
                                        try {
                                            if (shouldStart) {
                                                CpuUtils.startMinFreqLock(context)
                                            } else {
                                                CpuUtils.stopMinFreqLock()
                                            }
                                        } catch (e: Exception) {
                                            isMinFreqExecuting = !shouldStart
                                        }
                                    }
                                },
                                enabled = isRooted
                            ) { Text(if (isMinFreqExecuting) "Stop" else "Start") }
                        }
                    }
                    TweakCard("CPU Governor", "Switches the CPU governor between schedutil and performance.") {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (isCpuPerfMode) "Performance" else "Schedutil", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = isCpuPerfMode,
                                onCheckedChange = { isEnabled ->
                                    isCpuPerfMode = isEnabled
                                    // Launch on background thread
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val governor = if (isEnabled) "performance" else "schedutil"
                                        val command = (0..5).joinToString("\n") {
                                            """
                                            chmod 644 /sys/devices/system/cpu/cpu$it/cpufreq/scaling_governor
                                            echo '$governor' > /sys/devices/system/cpu/cpu$it/cpufreq/scaling_governor
                                            chmod 444 /sys/devices/system/cpu/cpu$it/cpufreq/scaling_governor
                                            """.trimIndent()
                                        }
                                        RootUtils.runAsRoot(command)
                                        withContext(Dispatchers.Main) {
                                            snackbarHostState.showSnackbar("CPU Governor set to $governor.")
                                        }
                                    }
                                },
                                enabled = isRooted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TweakSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand"
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
fun TweakCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
    }
}

object TweakCommands {
    const val LEDS_OFF = "echo 0 > /sys/class/leds/red/brightness\necho 0 > /sys/class/leds/green/brightness\necho 0 > /sys/class/leds/blue/brightness"
    val RGB_SCRIPT = """
#!/system/bin/sh
RED_LED="/sys/class/leds/red/brightness"
GREEN_LED="/sys/class/leds/green/brightness"
BLUE_LED="/sys/class/leds/blue/brightness"
set_rgb() { echo "${'$'}{1}" > "${'$'}RED_LED"; echo "${'$'}{2}" > "${'$'}GREEN_LED"; echo "${'$'}{3}" > "${'$'}BLUE_LED"; }
clamp() { if [ "${'$'}1" -lt 0 ]; then echo 0; elif [ "${'$'}1" -gt 255 ]; then echo 255; else echo "${'$'}1"; fi; }
trap "set_rgb 0 0 0; exit" INT TERM
while true; do
    for i in ${'$'}(seq 0 5 255); do set_rgb ${'$'}(clamp ${'$'}((255 - i))) ${'$'}(clamp ${'$'}{i}) 0; sleep 0.005; done
    for i in ${'$'}(seq 0 5 255); do set_rgb 0 ${'$'}(clamp ${'$'}((255 - i))) ${'$'}(clamp ${'$'}{i}); sleep 0.005; done
    for i in ${'$'}(seq 0 5 255); do set_rgb ${'$'}(clamp ${'$'}{i}) 0 ${'$'}(clamp ${'$'}((255 - i))); sleep 0.005; done
done
    """.trimIndent()
    const val DISABLE_TELEPORT_LIMIT = "oculuspreferences --setc shell_teleport_anywhere true"
    const val ENABLE_TELEPORT_LIMIT = "oculuspreferences --setc shell_teleport_anywhere false"
    const val ENABLE_NAVIGATOR_FOG = "oculuspreferences --setc navigator_background_disabled false\nam force-stop com.oculus.vrshell"
    const val DISABLE_NAVIGATOR_FOG = "oculuspreferences --setc navigator_background_disabled true\nam force-stop com.oculus.vrshell"
    const val ENABLE_PANEL_SCALING = "oculuspreferences --setc panel_scaling true\nam force-stop com.oculus.vrshell"
    const val DISABLE_PANEL_SCALING = "oculuspreferences --setc panel_scaling false\nam force-stop com.oculus.vrshell"
    const val SET_UI_DOCK = "oculuspreferences --setc debug_navigator_state 0\nam force-stop com.oculus.vrshell"
    const val SET_UI_NAVIGATOR = "oculuspreferences --setc debug_navigator_state 1\nam force-stop com.oculus.vrshell"
    const val SET_TRANSITION_IMMERSIVE = "oculuspreferences --setc shell_immersive_transitions_enabled true\nam force-stop com.oculus.vrshell"
    const val SET_TRANSITION_VOID = "oculuspreferences --setc shell_immersive_transitions_enabled false\nam force-stop com.oculus.vrshell"
    const val ENABLE_INFINITE_PANELS = "oculuspreferences --setc debug_infinite_spatial_panels_enabled true\nam force-stop com.oculus.vrshell"
    const val DISABLE_INFINITE_PANELS = "oculuspreferences --setc debug_infinite_spatial_panels_enabled false\nam force-stop com.oculus.vrshell"

    // Command to fix the double-tap passthrough feature. Wifi toggle is handled by the wrapper function.
    val FIX_PASSTHROUGH = """
    PIDS=${'$'}(dumpsys sensorservice | grep -o "unknown_package_pid_[0-9]*" | sed 's/unknown_package_pid_//' | sort -u)
      for pid in ${'$'}PIDS; do
        kill ${'$'}pid
      done
    am force-stop com.oculus.vrshell
    am startservice com.oculus.guardian/com.oculus.vrguardianservice.VrGuardianService
    am start -n com.veygax.eventhorizon/.ui.activities.MainActivity
    """.trimIndent()
}

@Preview(showBackground = true)
@Composable
fun TweaksScreenPreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Preview requires Activity context.")
        }
    }
}