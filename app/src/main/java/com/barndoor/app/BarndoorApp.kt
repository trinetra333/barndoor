package com.barndoor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BarndoorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        val dnsChannel = NotificationChannel(
            CHANNEL_DNS,
            "DNS proxy status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Barndoor's DNS proxy is active"
        }

        val rotationChannel = NotificationChannel(
            CHANNEL_ROTATION,
            "IP rotation status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Barndoor is rotating your VPN exit server"
        }

        manager.createNotificationChannel(dnsChannel)
        manager.createNotificationChannel(rotationChannel)
    }

    companion object {
        const val CHANNEL_DNS = "barndoor_dns_channel"
        const val CHANNEL_ROTATION = "barndoor_rotation_channel"
    }
}
