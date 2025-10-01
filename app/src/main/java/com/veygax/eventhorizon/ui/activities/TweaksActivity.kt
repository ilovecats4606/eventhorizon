package com.veygax.eventhorizon.ui.activities

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.veygax.eventhorizon.system.DnsBlockerService
import com.veygax.eventhorizon.system.TweakService
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

    var isRainbowLedActiveState = mutableStateOf(false)
    var isCustomLedActiveState = mutableStateOf(false)
    var isPowerLedActiveState = mutableStateOf(false)
    var isRootBlockerManuallyEnabledState = mutableStateOf(false) 

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
            // This immediately updates the UI to show the "Stop" button.
            isCustomLedActiveState.value = true
            isRainbowLedActiveState.value = false
            isPowerLedActiveState.value = false

            // Get the color data from the result and start the service
            val data = result.data
            val r = data?.getIntExtra("RED", 255) ?: 255
            val g = data?.getIntExtra("GREEN", 255) ?: 255
            val b = data?.getIntExtra("BLUE", 255) ?: 255

            startTweakServiceAction(TweakService.ACTION_START_CUSTOM_LED) { intent ->
                intent.putExtra("RED", r)
                intent.putExtra("GREEN", g)
                intent.putExtra("BLUE", b)
            }
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

    private suspend fun copyHostsFileFromAssets(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d("RootBlocker", "Starting to copy hosts file from assets...")
            val assetPath = "hosts/hosts"
            val inputStream = context.assets.open(assetPath)
            val hostsContent = inputStream.bufferedReader().use { it.readText() }
            val tempFile = File(context.cacheDir, "hosts_temp")
            tempFile.writeText(hostsContent)
            val moduleDir = "/data/adb/eventhorizon"
            val finalHostsPath = "$moduleDir/hosts"
            val commands = """
                mkdir -p $moduleDir
                mv ${tempFile.absolutePath} $finalHostsPath
                chmod 644 $finalHostsPath
            """.trimIndent()
            RootUtils.runAsRoot(commands)
            Log.d("RootBlocker", "Hosts file successfully copied to $finalHostsPath")
        } catch (e: Exception) {
            Log.e("RootBlocker", "Error copying hosts file", e)
        }
    }

    public suspend fun enableRootBlocker(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("RootBlocker", "enableRootBlocker function called.")
            copyHostsFileFromAssets(applicationContext)
            // First, unmount any existing mount to prevent duplicates.
            // Then, mount it and set the correct SELinux context.
            val commands = """
                umount -l /system/etc/hosts
                mount -o bind /data/adb/eventhorizon/hosts /system/etc/hosts
            """.trimIndent()
            RootUtils.runAsRoot(commands, useMountMaster = true)
        
            // Check the status and return the result
            val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
            return@withContext check.isNotBlank()
        } catch (e: Exception) {
            Log.e("RootBlocker", "Error enabling root blocker", e)
            return@withContext false
        }
    }

    fun disableRootBlocker() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Unmount the hosts file and then toggle airplane mode to flush the DNS cache
            val commands = """
                umount -l /system/etc/hosts
                settings put global airplane_mode_on 1
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
                sleep 4
                settings put global airplane_mode_on 0
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
            """.trimIndent()

            val result = RootUtils.runAsRoot(commands, useMountMaster = true)
            Log.d("RootBlocker", "Disable root blocker result:\n$result")

            // After disabling, update the UI state
            withContext(Dispatchers.Main) {
                // It's good practice to re-check the state after the operation
                val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
                isRootBlockerManuallyEnabledState.value = check.isNotBlank()
            }
        }
    }

    fun launchCustomColorPicker() {
        ledColorLauncher.launch(Intent(this, LedColorActivity::class.java))
    }

    fun startTweakServiceAction(action: String, intentModifier: (Intent) -> Unit = {}) {
        val intent = Intent(this, TweakService::class.java).apply {
            this.action = action
            intentModifier(this)
        }
        startService(intent)
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

    // --- LED Tweaks ---
    var isRainbowLedActive by activity.isRainbowLedActiveState
    var isCustomLedActive by activity.isCustomLedActiveState
    var isPowerLedActive by activity.isPowerLedActiveState
    var runOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("rgb_on_boot", false)) }
    var powerLedOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("power_led_on_boot", false)) }
    var customLedOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("custom_led_on_boot", false)) }

    // --- Domain Blocker ---
    var blockerOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("blocker_on_boot", false)) }
    var isBlockerEnabled by remember { mutableStateOf(false) }
    var isRootBlockerOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("root_blocker_on_boot", false)) }
    var isRootBlockerManuallyEnabled by activity.isRootBlockerManuallyEnabledState 

    // --- CPU Tweaks ---
    var minFreqOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("min_freq_on_boot", false)) }
    var isMinFreqExecuting by remember { mutableStateOf(false) }
    var isCpuPerfMode by remember { mutableStateOf(false) }

    // --- Wireless ADB ---
    var isWirelessAdbEnabled by remember { mutableStateOf(false) }
    var wifiIpAddress by remember { mutableStateOf("N/A") }
    var wirelessAdbOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("wireless_adb_on_boot", false)) }

    // --- CPU Monitor States ---
    var cpuMonitorInfo by remember { mutableStateOf(CpuMonitorInfo()) }
    var isFahrenheit by remember { mutableStateOf(sharedPrefs.getBoolean("temp_unit_is_fahrenheit", false)) }

    // --- Intercept Startup Apps ---
    var isInterceptorEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("intercept_startup_apps", false)) }

    // --- Startup Hang/Blackscreen Fix ---
    var cycleWifiOnBoot by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("cycle_wifi_on_boot", false)) }

    // --- Usb Interceptor ---
    val usbInterceptorEnabled = remember { mutableStateOf(sharedPrefs.getBoolean("usb_interceptor_on_boot", false)) }

    // --- System UI  ---
    var uiSwitchState by rememberSaveable { mutableStateOf(0) }
    var isVoidTransitionEnabled by rememberSaveable { mutableStateOf(false) }
    var isTeleportLimitDisabled by rememberSaveable { mutableStateOf(false) }
    var isNavigatorFogEnabled by rememberSaveable { mutableStateOf(false) }
    var isPanelScalingEnabled by rememberSaveable { mutableStateOf(false) }
    var isInfinitePanelsEnabled by rememberSaveable { mutableStateOf(false) }

    // This effect listens for the "stop all" message from the TweakService.
    // It ensures the UI updates even when the action is triggered from the notification.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        // --- Broadcast Receiver for "Stop All" ---
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // When the message is received, optimistically update all UI states to "off".
                if (intent?.action == TweakService.BROADCAST_TWEAKS_STOPPED) {
                    isRainbowLedActive = false
                    isCustomLedActive = false
                    isMinFreqExecuting = false
                    isInterceptorEnabled = false
                    isPowerLedActive = false
                }
            }
        }

        // Register the receiver to listen for our specific action
        LocalBroadcastManager.getInstance(context).registerReceiver(
            broadcastReceiver, IntentFilter(TweakService.BROADCAST_TWEAKS_STOPPED)
        )

        // --- Lifecycle Observer for onResume ---
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBlockerEnabled = isDnsServiceRunning()
                runOnBoot = sharedPrefs.getBoolean("rgb_on_boot", false)
                powerLedOnBoot = sharedPrefs.getBoolean("power_led_on_boot", false)
                customLedOnBoot = sharedPrefs.getBoolean("custom_led_on_boot", false)
                if (isRooted) {
                    // Re-check all states on resume
                    coroutineScope.launch(Dispatchers.IO) {
                        val runningRgb = RootUtils.runAsRoot("ps -ef | grep rgb_led.sh | grep -v grep")
                        val runningCustom = RootUtils.runAsRoot("ps -ef | grep custom_led.sh | grep -v grep")
                        val runningPowerLed = RootUtils.runAsRoot("ps -ef | grep power_led.sh | grep -v grep")

                        // Update UI states on the main dispatcher or compose thread
                        withContext(Dispatchers.Main) {
                            isRainbowLedActive = runningRgb.trim().isNotEmpty()
                            isCustomLedActive = runningCustom.trim().isNotEmpty()
                            isPowerLedActive = runningPowerLed.trim().isNotEmpty()
                            val runningCpu = RootUtils.runAsRoot("ps -ef | grep ${CpuUtils.SCRIPT_NAME} | grep -v grep")
                            isMinFreqExecuting = runningCpu.trim().isNotEmpty()
                            val runningInterceptor = RootUtils.runAsRoot("ps -ef | grep interceptor.sh | grep -v grep")
                            isInterceptorEnabled = runningInterceptor.trim().isNotEmpty()
                        }

                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
                        wifiIpAddress = if (ip == "0.0.0.0") "Not connected to Wi-Fi" else ip
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        // The onDispose block is crucial for cleanup when the composable leaves the screen.
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastReceiver)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

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
        // Always write/overwrite the script on launch to ensure it's up-to-date
        launch(Dispatchers.IO) {
            scriptFile.writeText(TweakCommands.RGB_SCRIPT)
            RootUtils.runAsRoot("chmod +x ${scriptFile.absolutePath}")
        }
    }

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                // Initial state check on startup
                isBlockerEnabled = isDnsServiceRunning()
                // Launch all checks in parallel using async for speed
                val runningRgbDeferred = async { RootUtils.runAsRoot("ps -ef | grep rgb_led.sh | grep -v grep") }
                val runningCustomDeferred = async { RootUtils.runAsRoot("ps -ef | grep custom_led.sh | grep -v grep") }
                val runningPowerLedDeferred = async { RootUtils.runAsRoot("ps -ef | grep power_led.sh | grep -v grep") }
                val runningCpuDeferred = async { RootUtils.runAsRoot("ps -ef | grep ${CpuUtils.SCRIPT_NAME} | grep -v grep") }
                val runningInterceptorDeferred = async { RootUtils.runAsRoot("ps -ef | grep interceptor.sh | grep -v grep") }
                val cpuGovDeferred = async { RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") }
                val adbPortDeferred = async { RootUtils.runAsRoot("getprop service.adb.tcp.port") }
                val rootBlockerStatusDeferred = async { RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true) }
                val uiStateValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc debug_navigator_state") }
                val transitionValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc shell_immersive_transitions_enabled") }
                val teleportValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc shell_teleport_anywhere") }
                val fogValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc navigator_background_disabled") }
                val panelScalingValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc panel_scaling") }
                val infinitePanelsValueDeferred = async { RootUtils.runAsRoot("oculuspreferences --getc debug_infinite_spatial_panels_enabled") }

                // Wait for all the results and update the UI states
                isRainbowLedActive = runningRgbDeferred.await().trim().isNotEmpty()
                isCustomLedActive = runningCustomDeferred.await().trim().isNotEmpty()
                isPowerLedActive = runningPowerLedDeferred.await().trim().isNotEmpty()
                isMinFreqExecuting = runningCpuDeferred.await().trim().isNotEmpty()
                isInterceptorEnabled = runningInterceptorDeferred.await().trim().isNotEmpty()
                isRootBlockerManuallyEnabled = rootBlockerStatusDeferred.await().trim().isNotEmpty() 
                isCpuPerfMode = cpuGovDeferred.await().trim() == "performance"
                isWirelessAdbEnabled = adbPortDeferred.await().trim() == "5555"
                uiSwitchState = if (uiStateValueDeferred.await().contains(": 1")) 1 else 0
                isVoidTransitionEnabled = transitionValueDeferred.await().contains(": false")
                isTeleportLimitDisabled = teleportValueDeferred.await().contains(": true")
                isNavigatorFogEnabled = fogValueDeferred.await().contains(": false")
                isPanelScalingEnabled = panelScalingValueDeferred.await().contains(": true")
                isInfinitePanelsEnabled = infinitePanelsValueDeferred.await().contains(": true")
            }

            // Run on a background thread to avoid blocking UI
            while (true) {
                withContext(Dispatchers.IO) {
                    cpuMonitorInfo = CpuUtils.getCpuMonitorInfo()
                }
                delay(2000) // Refresh every 2 seconds
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
                TweakSection(title = "LED Tweaks") {
                    TweakCard("Rainbow LED", "Cycles notification LED through colors.") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // Reduced space
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Run on Boot", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = runOnBoot,
                                    onCheckedChange = { checked ->
                                        runOnBoot = checked
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("rgb_on_boot", checked)
                                        if (checked) {
                                            editor.putBoolean("custom_led_on_boot", false)
                                            editor.putBoolean("power_led_on_boot", false)
                                            powerLedOnBoot = false
                                            customLedOnBoot = false
                                        }
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "Rainbow LED on Boot Enabled" else "Rainbow LED on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Button(
                                onClick = {
                                    val shouldStart = !isRainbowLedActive
                                    if (shouldStart) {
                                        isRainbowLedActive = true
                                        isCustomLedActive = false
                                        isPowerLedActive = false
                                        activity.startTweakServiceAction(TweakService.ACTION_START_RGB)
                                    } else {
                                        isRainbowLedActive = false
                                        activity.startTweakServiceAction(TweakService.ACTION_STOP_RGB)
                                    }
                                },
                                enabled = isRooted,
                                modifier = Modifier.width(90.dp)
                            ) {
                                Text(if (isRainbowLedActive) "Stop" else "Start")
                            }
                        }
                    }
                    TweakCard("Power Indicator LED", "Shows battery level with the LED color.") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // Reduced space
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Run on Boot", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = powerLedOnBoot,
                                    onCheckedChange = { isEnabled ->
                                        powerLedOnBoot = isEnabled
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("power_led_on_boot", isEnabled)
                                        if (isEnabled) {
                                            editor.putBoolean("rgb_on_boot", false)
                                            editor.putBoolean("custom_led_on_boot", false)
                                            runOnBoot = false
                                            customLedOnBoot = false
                                        }
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (isEnabled) "Power LED on Boot Enabled" else "Power LED on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Button(
                                onClick = {
                                    val shouldStart = !isPowerLedActive
                                    if (shouldStart) {
                                        isPowerLedActive = true
                                        isRainbowLedActive = false
                                        isCustomLedActive = false
                                        activity.startTweakServiceAction(TweakService.ACTION_START_POWER_LED)
                                    } else {
                                        isPowerLedActive = false
                                        activity.startTweakServiceAction(TweakService.ACTION_STOP_POWER_LED)
                                    }
                                },
                                enabled = isRooted,
                                modifier = Modifier.width(90.dp)
                            ) {
                                Text(if (isPowerLedActive) "Stop" else "Start")
                            }
                        }
                    }
                    TweakCard("Custom LED Color", "Set a static color for the LED.") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // Reduced space
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Run on Boot", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = customLedOnBoot,
                                    onCheckedChange = { isEnabled ->
                                        customLedOnBoot = isEnabled
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("custom_led_on_boot", isEnabled)
                                        if (isEnabled) {
                                            editor.putBoolean("rgb_on_boot", false)
                                            editor.putBoolean("power_led_on_boot", false)
                                            runOnBoot = false
                                            powerLedOnBoot = false
                                        }
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (isEnabled) "Custom LED on Boot Enabled" else "Custom LED on Boot Disabled") }
                                    },
                                    enabled = isRooted
                                )
                            }
                            Button(
                                onClick = {
                                    if (isCustomLedActive) {
                                        isCustomLedActive = false
                                        activity.startTweakServiceAction(TweakService.ACTION_STOP_CUSTOM_LED)
                                    } else {
                                        // Update UI states immediately before launching the color picker
                                        activity.launchCustomColorPicker()
                                        coroutineScope.launch(Dispatchers.IO) {
                                            RootUtils.runAsRoot("pkill -f rgb_led.sh; pkill -f power_led.sh")
                                        }
                                    }
                                },
                                enabled = isRooted,
                                modifier = Modifier.width(90.dp)
                            ) {
                                Text(if (isCustomLedActive) "Stop" else "Select")
                            }
                        }
                    }
                }
            }

            item {
                TweakSection(title = "Utilities") {
                    if (!isRooted) {
                        TweakCard("Internet Kill Switch", "Blocks internet access on boot (No Root). This will also trigger the Domain Blocker after the reboot (Root).") {
                            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = blockerOnBoot, onCheckedChange = { checked ->
                                        blockerOnBoot = checked
                                        isRootBlockerOnBoot = checked
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("blocker_on_boot", checked)
                                        editor.putBoolean("root_blocker_on_boot", checked)
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (checked) "Blocker on Boot Enabled" else "Blocker on Boot Disabled") }
                                    })
                                }
                                Spacer(Modifier.height(8.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("Blocker Status", style = MaterialTheme.typography.bodyMedium)
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
                    }
                    if (isRooted) {
                        TweakCard("Meta Domain Blocker", "Blocks Meta domains using bind mounting (Root)") {
                            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = isRootBlockerOnBoot, onCheckedChange = { isEnabled ->
                                        // Two-way sync: Update both states and preferences
                                        isRootBlockerOnBoot = isEnabled
                                        blockerOnBoot = isEnabled
                                        val editor = sharedPrefs.edit()
                                        editor.putBoolean("root_blocker_on_boot", isEnabled)
                                        editor.putBoolean("blocker_on_boot", isEnabled)
                                        editor.apply()
                                        coroutineScope.launch { snackbarHostState.showSnackbar(if (isEnabled) "Blocker on Boot Enabled" else "Blocker on Boot Disabled") }
                                    })
                                }
                                Spacer(Modifier.height(8.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("Blocker Status", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = isRootBlockerManuallyEnabled, onCheckedChange = { isEnabled ->
                                        isRootBlockerManuallyEnabled = isEnabled

                                            coroutineScope.launch {
                                                if (isEnabled) {
                                                    // Call the suspend function and wait for the actual result
                                                    val isSuccess = activity.enableRootBlocker()

                                                    // Update the UI state based on the true result
                                                    isRootBlockerManuallyEnabled = isSuccess
    
                                                    if (isSuccess) {
                                                        snackbarHostState.showSnackbar("Root domain blocker enabled!")
                                                    } else {
                                                        snackbarHostState.showSnackbar("Error: Root domain blocker failed to enable.")
                                                    }
                                                } else {
                                                    activity.disableRootBlocker()
                                                    snackbarHostState.showSnackbar("Root blocker disabled. DNS cache flushed.")
                                                }
                                            }
                                        },
                                        enabled = isRooted
                                    )
                                }
                            }
                        }
                    }
                    TweakCard(
                        title = "Wireless ADB",
                        description = "Enables connecting to ADB over Wi-Fi.",
                        extraContent = {
                            if (isWirelessAdbEnabled) {
                                Text(
                                    text = "adb connect $wifiIpAddress:5555",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    ) {
                        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable on Boot", style = MaterialTheme.typography.bodyMedium)
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ADB Status", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = isWirelessAdbEnabled,
                                    onCheckedChange = { isEnabled ->
                                        isWirelessAdbEnabled = isEnabled
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
                    }
                    TweakCard("Intercept App Launching", "Stops Horizon Feed and Social Connections from being started.") {
                        Switch(
                            checked = isInterceptorEnabled,
                            onCheckedChange = { isEnabled ->
                                isInterceptorEnabled = isEnabled
                                sharedPrefs.edit().putBoolean("intercept_startup_apps", isEnabled).apply()

                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isEnabled) {
                                        activity.startTweakServiceAction(TweakService.ACTION_START_INTERCEPTOR)
                                    } else {
                                        activity.startTweakServiceAction(TweakService.ACTION_STOP_INTERCEPTOR)
                                    }
                                    withContext(Dispatchers.Main) {
                                        snackbarHostState.showSnackbar(if (isEnabled) "App Interceptor Enabled." else "App Interceptor Disabled.")
                                    }
                                }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard("System Hang Fix", "Turns Wi-Fi off and on during boot to prevent the system from handing in certain conditions") {
                        Switch(
                            checked = cycleWifiOnBoot,
                            onCheckedChange = { isEnabled ->
                                cycleWifiOnBoot = isEnabled
                                sharedPrefs.edit().putBoolean("cycle_wifi_on_boot", isEnabled).apply()
                                coroutineScope.launch { snackbarHostState.showSnackbar(if (isEnabled) "Wi-Fi Cycle on Boot Enabled" else "Wi-Fi Cycle on Boot Disabled") }
                            },
                            enabled = isRooted
                        )
                    }
                    TweakCard(
                        title = "USB Notification Interceptor",
                        description = "Listens for the Oculus MTP notification and turns on MTP mode."
                    ) {
                        Switch(
                            checked = usbInterceptorEnabled.value,
                            onCheckedChange = { isEnabled ->
                                usbInterceptorEnabled.value = isEnabled
                                sharedPrefs.edit().putBoolean("usb_interceptor_on_boot", isEnabled).apply()
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isEnabled) {
                                        activity.startTweakServiceAction(TweakService.ACTION_START_USB_INTERCEPTOR)
                                        snackbarHostState.showSnackbar("USB Interceptor Enabled.")
                                    } else {
                                        activity.startTweakServiceAction(TweakService.ACTION_STOP_USB_INTERCEPTOR)
                                        snackbarHostState.showSnackbar("USB Interceptor Disabled.")
                                    }
                                }
                            },
                            enabled = isRooted
                        )
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
                        Card(modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)) {
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Run on Boot", style = MaterialTheme.typography.bodyMedium)
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
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val shouldStart = !isMinFreqExecuting
                                        isMinFreqExecuting = shouldStart
                                        try {
                                            if (shouldStart) {
                                                activity.startTweakServiceAction(TweakService.ACTION_START_MIN_FREQ)
                                            } else {
                                                activity.startTweakServiceAction(TweakService.ACTION_STOP_MIN_FREQ)
                                            }
                                        } catch (e: Exception) {
                                            isMinFreqExecuting = !shouldStart
                                        }
                                    }
                                },
                                enabled = isRooted,
                                modifier = Modifier.widthIn(min = 80.dp)
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
fun TweakCard(
    title: String,
    description: String,
    extraContent: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)) { // Reduced padding from 16.dp to 8.dp
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
                extraContent?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    it()
                }
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

    val POWER_LED_SCRIPT = """
        #!/system/bin/sh

        RED_LED="/sys/class/leds/red/brightness"
        GREEN_LED="/sys/class/leds/green/brightness"
        BLUE_LED="/sys/class/leds/blue/brightness"
        BATTERY_PATH="/sys/class/power_supply/battery/capacity"

        set_led() {
            echo "${'$'}1" > "${'$'}RED_LED"
            echo "${'$'}2" > "${'$'}GREEN_LED"
            echo "${'$'}3" > "${'$'}BLUE_LED"
        }

        while true; do
            battery_level=${'$'}(cat "${'$'}BATTERY_PATH")

            if [ "${'$'}battery_level" -ge 95 ]; then
                set_led 0 255 0
            elif [ "${'$'}battery_level" -ge 90 ]; then
                set_led 64 255 0
            elif [ "${'$'}battery_level" -ge 80 ]; then
                set_led 128 255 0
            elif [ "${'$'}battery_level" -ge 70 ]; then
                set_led 180 255 0
            elif [ "${'$'}battery_level" -ge 60 ]; then
                set_led 220 255 0
            elif [ "${'$'}battery_level" -ge 50 ]; then
                set_led 255 255 0
            elif [ "${'$'}battery_level" -ge 40 ]; then
                set_led 255 180 0
            elif [ "${'$'}battery_level" -ge 30 ]; then
                set_led 255 128 0
            elif [ "${'$'}battery_level" -ge 20 ]; then
                set_led 255 64 0
            elif [ "${'$'}battery_level" -ge 10 ]; then
                set_led 255 32 0
            else
                set_led 255 0 0
            fi
            sleep 5
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