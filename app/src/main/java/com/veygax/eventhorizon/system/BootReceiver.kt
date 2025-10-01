package com.veygax.eventhorizon.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scottyab.rootbeer.RootBeer
import com.veygax.eventhorizon.system.DnsBlockerService
import com.veygax.eventhorizon.ui.activities.MainActivity
import com.veygax.eventhorizon.utils.CpuUtils
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
            val rootOnBoot = sharedPrefs.getBoolean("root_on_boot", false)
            val blockerOnBoot = sharedPrefs.getBoolean("blocker_on_boot", false)
            val rainbowLedOnBoot = sharedPrefs.getBoolean("rgb_on_boot", false)
            val customLedOnBoot = sharedPrefs.getBoolean("custom_led_on_boot", false)
            val powerLedOnBoot = sharedPrefs.getBoolean("power_led_on_boot", false)
            val minFreqOnBoot = sharedPrefs.getBoolean("min_freq_on_boot", false)
            val interceptStartupApps = sharedPrefs.getBoolean("intercept_startup_apps", false)
            val wirelessAdbOnBoot = sharedPrefs.getBoolean("wireless_adb_on_boot", false)
            val cycleWifiOnBoot = sharedPrefs.getBoolean("cycle_wifi_on_boot", false)
            val isRootBlockerEnabledOnBoot = sharedPrefs.getBoolean("root_blocker_on_boot", false)
            val usbInterceptorOnBoot = sharedPrefs.getBoolean("usb_interceptor_on_boot", false)
            val scope = CoroutineScope(Dispatchers.IO)

            // --- Activity Boot Logic ---
            // Create a single intent to be launched only if needed.
            val startIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            var shouldStartActivity = false

            if (rootOnBoot) {
                val rootBeer = RootBeer(context)
                if (!rootBeer.isRooted) {
                    // Add the auto_root instruction to our single intent
                    startIntent.putExtra("auto_root", true)
                    shouldStartActivity = true
                }
            }
            
            if (blockerOnBoot) {
                // Add the start_dns_blocker instruction to our single intent
                startIntent.putExtra("start_dns_blocker", true)
                shouldStartActivity = true
            }

            // After checking all conditions, launch the activity just once if required.
            if (shouldStartActivity) {
                context.startActivity(startIntent)
            }

            // --- LED Boot Logic --- (Using TweakService)
            if (customLedOnBoot) {
                // Use TweakService to start custom color
                val r = sharedPrefs.getInt("led_red", 255)
                val g = sharedPrefs.getInt("led_green", 255)
                val b = sharedPrefs.getInt("led_blue", 255)

                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_CUSTOM_LED
                    putExtra("RED", r)
                    putExtra("GREEN", g)
                    putExtra("BLUE", b)
                }
                context.startService(serviceIntent)

            } else if (rainbowLedOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_RGB
                }
                context.startService(serviceIntent)

            } else if (powerLedOnBoot) { // <-- This is the new, required block
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_POWER_LED
                }
                context.startService(serviceIntent)
            }

            // --- CPU Lock Boot Logic (Using TweakService) ---
            if (minFreqOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_MIN_FREQ
                }
                context.startService(serviceIntent)
            }

            // --- Wireless ADB Boot Logic ---
            if (wirelessAdbOnBoot) {
                scope.launch {
                    RootUtils.runAsRoot("setprop service.adb.tcp.port 5555")
                    RootUtils.runAsRoot("stop adbd && start adbd")
                }
            }

            // --- Intercept Startup Apps Logic (Using TweakService) ---
            if (interceptStartupApps) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_INTERCEPTOR
                }
                context.startService(serviceIntent)
            }

            // --- Wi-Fi Cycle on Boot Logic ---
            if (cycleWifiOnBoot) {
                scope.launch {
                    RootUtils.runAsRoot("svc wifi disable")
                    // Wait for a few seconds before turning it back on
                    kotlinx.coroutines.delay(3000) // 3-second delay
                    RootUtils.runAsRoot("svc wifi enable")
                }
            }

            if (isRootBlockerEnabledOnBoot) {
                Log.i("BootReceiver", "Root blocker enabled. Starting kill switch...")

                // 1. Start the kill switch immediately (blocks internet until root mount is ready)
                val serviceIntent = Intent(context, DnsBlockerService::class.java).apply {
                    action = DnsBlockerService.ACTION_START
                }
                context.startService(serviceIntent)

                // 2. Switch to root blocker only if root is available
                scope.launch {
                    delay(5000) // let system settle a bit

                    if (RootUtils.isRootAvailable()) {
                        Log.i("BootReceiver", "Root available, copying hosts file...")

                        val hostsFile = File("/data/adb/eventhorizon/hosts")
                        if (!hostsFile.exists()) {
                            try {
                                Log.d("BootReceiver", "Starting to copy hosts file from assets...")
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
                                Log.d("BootReceiver", "Hosts file successfully copied to $finalHostsPath")
                            } catch (e: Exception) {
                                Log.e("BootReceiver", "Error copying hosts file", e)
                            }
                        }

                        RootUtils.runAsRoot(
                            "umount -l /system/etc/hosts; mount -o bind /data/adb/eventhorizon/hosts /system/etc/hosts",
                            useMountMaster = true
                        )
                        Log.i("BootReceiver", "Hosts file mounted.")

                        // Stop VPN kill switch now that root blocker is active
                        val stopIntent = Intent(context, DnsBlockerService::class.java).apply {
                            action = DnsBlockerService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                        Log.i("BootReceiver", "Kill switch stopped. Root blocker active.")
                    } else {
                        Log.w("BootReceiver", "Root not available at boot. Leaving kill switch ON.")
                        // Do nothing else — kill switch stays active
                    }
                }
            }

            if (usbInterceptorOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_USB_INTERCEPTOR
                }
                context.startService(serviceIntent)
            }
        }
    }
}