package com.veygax.eventhorizon.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.veygax.eventhorizon.ui.activities.TweakCommands
import com.veygax.eventhorizon.utils.CpuUtils
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.*
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

class TweakService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val TAG = "TweakService"

    // App Interceptor constants moved from AppInterceptor.kt
    private val INTERCEPTOR_SCRIPT = """
            #!/system/bin/sh
            TARGET_EXPLORE_ACTIVITY="com.oculus.explore/.ExploreActivity"
            TARGET_CONNECTIONS_ACTIVITY="com.oculus.socialplatform/com.oculus.panelapp.people.PeopleShelfActivity"

            logcat -c
            logcat -T 0 ActivityTaskManager:D *:S | while read -r line; do
                case "${'$'}line" in
                    *"START u0"*cmp=${'$'}TARGET_EXPLORE_ACTIVITY*)
                        pm disable "${'$'}TARGET_EXPLORE_ACTIVITY"
                        pm enable "${'$'}TARGET_EXPLORE_ACTIVITY"
                        ;;
                    *"START u0"*cmp=${'$'}TARGET_CONNECTIONS_ACTIVITY*)
                        pm disable "${'$'}TARGET_CONNECTIONS_ACTIVITY"
                        pm enable "${'$'}TARGET_CONNECTIONS_ACTIVITY"
                        ;;
                esac
            done
        """.trimIndent()

    private var usbInterceptorProcess: Process? = null

    private val USB_INTERCEPTOR_SCRIPT = """
        #!/system/bin/sh

        logcat -c
        logcat -T 0 OculusNotificationListenerService:D *:S | while read -r line; do
            case "${'$'}line" in
                *"Notification posted:"*"usb_connect_enable_mtp"*)
                    svc usb setFunctions mtp
                    am force-stop com.oculus.notification_proxy
                    ;;
            esac
        done
    """.trimIndent()

    companion object {
        const val ACTION_START_RGB = "com.veygax.eventhorizon.START_RGB"
        const val ACTION_STOP_RGB = "com.veygax.eventhorizon.STOP_RGB"
        const val ACTION_START_CUSTOM_LED = "com.veygax.eventhorizon.START_CUSTOM_LED"
        const val ACTION_STOP_CUSTOM_LED = "com.veygax.eventhorizon.STOP_CUSTOM_LED"
        const val ACTION_START_MIN_FREQ = "com.veygax.eventhorizon.START_MIN_FREQ"
        const val ACTION_STOP_MIN_FREQ = "com.veygax.eventhorizon.STOP_MIN_FREQ"
        const val ACTION_START_INTERCEPTOR = "com.veygax.eventhorizon.START_INTERCEPTOR"
        const val ACTION_STOP_INTERCEPTOR = "com.veygax.eventhorizon.STOP_INTERCEPTOR"
        
        const val ACTION_STOP_ALL = "com.veygax.eventhorizon.STOP_ALL"
        const val ACTION_START_POWER_LED = "com.veygax.eventhorizon.START_POWER_LED"
        const val ACTION_STOP_POWER_LED = "com.veygax.eventhorizon.STOP_POWER_LED"    

        const val ACTION_START_USB_INTERCEPTOR = "com.veygax.eventhorizon.START_USB_INTERCEPTOR"
        const val ACTION_STOP_USB_INTERCEPTOR = "com.veygax.eventhorizon.STOP_USB_INTERCEPTOR"
        
        // This is the message the Activity will listen for.
        const val BROADCAST_TWEAKS_STOPPED = "com.veygax.eventhorizon.TWEAKS_STOPPED"

        private const val NOTIFICATION_CHANNEL_ID = "tweak_service_channel"
        private const val NOTIFICATION_ID = 2
    }
    
    // Internal states to track which root tweaks are running
    private var isRgbRunning: Boolean = false
    private var isCustomLedRunning: Boolean = false
    private var isPowerLedRunning: Boolean = false
    private var isMinFreqRunning: Boolean = false
    private var isInterceptorRunning: Boolean = false
    private var isUsbInterceptorRunning = false

    
    // Files for scripts
    private lateinit var rgbScriptFile: File
    private lateinit var customLedScriptFile: File
    private lateinit var powerLedScriptFile: File
    private lateinit var minFreqScriptFile: File
    private lateinit var interceptorScriptFile: File
    private lateinit var usbInterceptorScriptFile: File

    override fun onCreate() {
        super.onCreate()
        rgbScriptFile = File(filesDir, "rgb_led.sh")
        customLedScriptFile = File(filesDir, "custom_led.sh")
        powerLedScriptFile = File(filesDir, "power_led.sh")
        minFreqScriptFile = File(filesDir, CpuUtils.SCRIPT_NAME)
        interceptorScriptFile = File(filesDir, "interceptor.sh")
        usbInterceptorScriptFile = File(filesDir, "usb_interceptor.sh")
        

        // Initialize state robustly on service creation (to catch scripts running from boot)
        runBlocking(Dispatchers.IO) {
            checkRunningScripts()
        }
        
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    // Function to perform a robust check of all running processes
    private suspend fun checkRunningScripts() {
        // We use ps -ef here as the true source of external truth (i.e., on boot)
        val runningRgb = RootUtils.runAsRoot("ps -ef | grep rgb_led.sh | grep -v grep").trim().isNotEmpty()
        val runningCustom = RootUtils.runAsRoot("ps -ef | grep custom_led.sh | grep -v grep").trim().isNotEmpty()
        val runningCpu = RootUtils.runAsRoot("ps -ef | grep ${CpuUtils.SCRIPT_NAME} | grep -v grep").trim().isNotEmpty()
        val runningInterceptor = RootUtils.runAsRoot("ps -ef | grep interceptor.sh | grep -v grep").trim().isNotEmpty()

        isRgbRunning = runningRgb
        isCustomLedRunning = runningCustom
        isMinFreqRunning = runningCpu
        isInterceptorRunning = runningInterceptor
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            serviceScope.launch { updateServiceState() } // Check state if started with no explicit action
            return START_STICKY
        }

        serviceScope.launch {
            // Process the action. The start/stop functions determine the definitive state.
            when (action) {
                ACTION_START_RGB -> startRgbLed()
                ACTION_STOP_RGB -> stopRgbLed()
                ACTION_START_CUSTOM_LED -> {
                    val r = intent.getIntExtra("RED", 255)
                    val g = intent.getIntExtra("GREEN", 255)
                    val b = intent.getIntExtra("BLUE", 255)
                    startCustomLed(r, g, b)
                }
                ACTION_STOP_CUSTOM_LED -> stopCustomLed()
                ACTION_START_POWER_LED -> startPowerLed()
                ACTION_STOP_POWER_LED -> stopPowerLed()
                ACTION_START_MIN_FREQ -> startMinFreq()
                ACTION_STOP_MIN_FREQ -> stopMinFreq()
                ACTION_START_INTERCEPTOR -> startInterceptor() 
                ACTION_STOP_INTERCEPTOR -> stopInterceptor() 
                ACTION_START_USB_INTERCEPTOR -> startUsbInterceptor()
                ACTION_STOP_USB_INTERCEPTOR -> stopUsbInterceptor()
                ACTION_STOP_ALL -> stopAllTweaksAndService()
                else -> { /* Do nothing if action is unknown or null */ }
            }
            
            // Update the notification based on the new, certain internal state.
            updateServiceState()
        }

        return START_STICKY
    }

    private suspend fun startRgbLed() {
        stopAnyLed()
        // Always write the script to ensure it's present and up-to-date
            rgbScriptFile.writeText(TweakCommands.RGB_SCRIPT)
            RootUtils.runAsRoot("chmod +x ${rgbScriptFile.absolutePath}")
        RootUtils.runAsRoot("${rgbScriptFile.absolutePath} &")
        isRgbRunning = true
        isCustomLedRunning = false
        isPowerLedRunning = false
    }

    private suspend fun stopRgbLed() {
        RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isRgbRunning = false
    }

    private suspend fun startCustomLed(r: Int, g: Int, b: Int) {
        stopAnyLed()
        val customColorScript = """
            #!/system/bin/sh
            RED_LED="/sys/class/leds/red/brightness"
            GREEN_LED="/sys/class/leds/green/brightness"
            BLUE_LED="/sys/class/leds/blue/brightness"
            while true; do
                echo $r > "${'$'}RED_LED"
                echo $g > "${'$'}GREEN_LED"
                echo $b > "${'$'}BLUE_LED"
                sleep 1
            done
        """.trimIndent()
        customLedScriptFile.writeText(customColorScript)
        RootUtils.runAsRoot("chmod +x ${customLedScriptFile.absolutePath}")
        RootUtils.runAsRoot("${customLedScriptFile.absolutePath} &")
        isCustomLedRunning = true
        isRgbRunning = false
        isPowerLedRunning = false
    }
    
    private suspend fun stopCustomLed() {
        RootUtils.runAsRoot("pkill -f custom_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isCustomLedRunning = false
    }

    private suspend fun startPowerLed() {
        stopAnyLed()
        powerLedScriptFile.writeText(TweakCommands.POWER_LED_SCRIPT) // This line creates the file
        RootUtils.runAsRoot("chmod +x ${powerLedScriptFile.absolutePath}")
        RootUtils.runAsRoot("${powerLedScriptFile.absolutePath} &")
        isPowerLedRunning = true
        isRgbRunning = false
        isCustomLedRunning = false
    }

    private suspend fun stopPowerLed() {
        RootUtils.runAsRoot("pkill -f power_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isPowerLedRunning = false
    }

    // This function likely already exists, make sure it's up to date
    private suspend fun stopAnyLed() {
        RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
        RootUtils.runAsRoot("pkill -f custom_led.sh || true")
        RootUtils.runAsRoot("pkill -f power_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isRgbRunning = false
        isCustomLedRunning = false
        isPowerLedRunning = false
    }

    private suspend fun startMinFreq() {
        RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
        val scriptContent = CpuUtils.getMinFreqScript(CpuUtils.DEFAULT_LITTLE_FREQ, CpuUtils.DEFAULT_BIG_FREQ)
        minFreqScriptFile.writeText(scriptContent)
        RootUtils.runAsRoot("chmod +x ${minFreqScriptFile.absolutePath}")
        RootUtils.runAsRoot("${minFreqScriptFile.absolutePath} &")
        isMinFreqRunning = true
    }

    private suspend fun stopMinFreq() {
        RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
        isMinFreqRunning = false
    }
    
    private suspend fun startInterceptor() {
        RootUtils.runAsRoot("pkill -f interceptor.sh || true")
        interceptorScriptFile.writeText(INTERCEPTOR_SCRIPT)
        RootUtils.runAsRoot("chmod +x ${interceptorScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${interceptorScriptFile.absolutePath} > /dev/null 2>&1 &")
        isInterceptorRunning = true
    }

    private suspend fun stopInterceptor() {
        RootUtils.runAsRoot("pkill -f interceptor.sh || true")
        isInterceptorRunning = false
    }

    private suspend fun startUsbInterceptor() {
        RootUtils.runAsRoot("pkill -f usb_interceptor.sh || true")
        usbInterceptorScriptFile.writeText(USB_INTERCEPTOR_SCRIPT)
        RootUtils.runAsRoot("chmod +x ${usbInterceptorScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${usbInterceptorScriptFile.absolutePath} > /dev/null 2>&1 &")
        isUsbInterceptorRunning = true
        updateServiceState()
    }

    private suspend fun stopUsbInterceptor() {
        RootUtils.runAsRoot("pkill -f usb_interceptor.sh || true")
        isUsbInterceptorRunning = false
        updateServiceState()
    }
    
    private fun isAnyTweakRunning(): Boolean {
        return isRgbRunning || isCustomLedRunning || isPowerLedRunning || isMinFreqRunning || isInterceptorRunning || isUsbInterceptorRunning
    }
    
    private suspend fun stopAllTweaksAndService() {
        // Run all stop commands
        stopAnyLed()
        stopMinFreq()
        stopInterceptor()
        stopUsbInterceptor()

        // Broadcast that all tweaks have been stopped before the service dies.
        // This allows the UI in TweaksActivity to update instantly.
        val intent = Intent(BROADCAST_TWEAKS_STOPPED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // This stops the service, removing the notification.
        stopSelf()
    }
    
    // Checks the state and stops the service if nothing is active
    private fun stopSelfIfInactive() {
        if (!isAnyTweakRunning()) {
            stopSelf()
        }
    }

    private fun updateServiceState() {
        // Check if anything is running before updating the UI
        if (!isAnyTweakRunning()) {
            stopSelfIfInactive() // Will stop service if no tweaks are active
            return
        }

        // Re-post the notification to update its content based on what's running
        val notification = buildNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EventHorizon Background Tweaks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs persistent root tweaks like LED, CPU frequency locks, and app interception."
            }
            val manager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        
        val contentText = if (isAnyTweakRunning()) {
            "Persistent tweaks are being managed."
        } else {
            "No persistent tweaks are running." 
        }
        
        val stopIntent = Intent(this, TweakService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Increase Notification Persistence
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EventHorizon Tweaks Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_power_off) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop All Tweaks", stopPendingIntent)
            .setOngoing(true) // Makes it non-swipeable/persistent
        
        // Use the highest available priority settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notificationBuilder.setCategory(Notification.CATEGORY_SERVICE)
        }
        
        // Correctly applying FLAG_NO_CLEAR to the built Notification object for persistence
        return notificationBuilder.build().apply {
            @Suppress("DEPRECATION")
            flags = flags or Notification.FLAG_NO_CLEAR
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        // Ensure scripts are killed on service destruction, just in case
        runBlocking(Dispatchers.IO) {
            RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
            RootUtils.runAsRoot("pkill -f custom_led.sh || true")
            RootUtils.runAsRoot("pkill -f power_led.sh || true")
            RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
            RootUtils.runAsRoot("pkill -f interceptor.sh || true")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}