package com.barndoor.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Watches for new devices joining the hotspot so the owner can be alerted.
 *
 * Android does not expose a public API that lists hotspot clients, so this
 * is a best-effort reader of /proc/net/arp, which lists devices that have
 * recently talked to this phone on the local network. Coverage and accuracy
 * vary by manufacturer/Android version, and this reads the phone's own ARP
 * table only - it does not touch or alter other devices in any way.
 */
class DeviceMonitorService : Service() {

    private lateinit var alertNotifier: AlertNotifier
    private var running = false
    private val knownMacAddresses = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        alertNotifier = AlertNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Thread { pollLoop() }.start()
        }
        return START_STICKY
    }

    private fun pollLoop() {
        while (running) {
            try {
                val current = readArpTable()
                val newDevices = current - knownMacAddresses
                if (knownMacAddresses.isNotEmpty()) {
                    newDevices.forEach { mac ->
                        alertNotifier.notifyNewDevice(mac)
                    }
                }
                knownMacAddresses.addAll(current)
            } catch (e: Exception) {
                Log.w(TAG, "ARP read failed: ${e.message}")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun readArpTable(): Set<String> {
        val results = mutableSetOf<String>()
        val arpFile = File("/proc/net/arp")
        if (!arpFile.exists()) return results

        BufferedReader(FileReader(arpFile)).use { reader ->
            reader.readLine() // header
            reader.forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                // Columns: IP address, HW type, Flags, HW address, Mask, Device
                if (parts.size >= 4) {
                    val mac = parts[3]
                    if (mac != "00:00:00:00:00:00") {
                        results.add(mac)
                    }
                }
            }
        }
        return results
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DeviceMonitorService"
        private const val POLL_INTERVAL_MS = 15_000L
    }
}
