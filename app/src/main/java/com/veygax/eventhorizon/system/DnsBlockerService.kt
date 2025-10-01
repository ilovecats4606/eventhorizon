package com.veygax.eventhorizon.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.veygax.eventhorizon.R

class DnsBlockerService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "KillSwitchVpnService"
        const val ACTION_START = "com.veygax.eventhorizon.START_KILL_SWITCH"
        const val ACTION_STOP = "com.veygax.eventhorizon.STOP_KILL_SWITCH"
        private const val NOTIFICATION_CHANNEL_ID = "kill_switch_channel"
        private const val NOTIFICATION_ID = 4
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startKillSwitch()
            ACTION_STOP -> stopKillSwitch()
        }
        return START_STICKY
    }

    private fun startKillSwitch() {
        if (vpnInterface != null) {
            Log.d(TAG, "Kill switch is already running.")
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // This builder creates a VPN that routes all traffic to itself.
        // Because we never read or write packets, all traffic is dropped.
        val builder = Builder()
            .setSession(getString(R.string.app_name) + " Kill Switch")
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0) // Route all traffic

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface for kill switch.")
            stopKillSwitch()
        } else {
            Log.i(TAG, "Internet kill switch is now ACTIVE.")
        }
    }

    private fun stopKillSwitch() {
        Log.i(TAG, "Internet kill switch is being deactivated.")
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface.", e)
        }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system!")
        stopKillSwitch()
        super.onRevoke()
    }
    
    // --- Notification Boilerplate ---
    private fun buildNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EventHorizon Kill Switch")
            .setContentText("Internet access is temporarily blocked.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use a suitable icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Kill Switch Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}