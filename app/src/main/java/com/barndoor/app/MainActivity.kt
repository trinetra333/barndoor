package com.barndoor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var hotspotManager: HotspotManager
    private lateinit var hotspotSwitch: Switch
    private lateinit var hotspotStatusText: TextView
    private lateinit var blockedDomainsInput: EditText
    private lateinit var alertsSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hotspotManager = HotspotManager(this)

        hotspotSwitch = findViewById(R.id.hotspotSwitch)
        hotspotStatusText = findViewById(R.id.hotspotStatusText)
        blockedDomainsInput = findViewById(R.id.blockedDomainsInput)
        alertsSwitch = findViewById(R.id.alertsSwitch)

        requestRuntimePermissions()

        hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startHotspotFlow()
            } else {
                hotspotManager.stopLocalOnlyHotspot()
                hotspotStatusText.text = "Status: off"
            }
        }

        findViewById<android.widget.Button>(R.id.saveFilterButton).setOnClickListener {
            saveFilterList()
        }

        findViewById<Switch>(R.id.youtubeOnlySwitch).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startFilterService(
                    domains = BarndoorVpnService.YOUTUBE_ONLY_DOMAINS,
                    mode = BarndoorVpnService.Mode.ALLOW_LIST
                )
                Toast.makeText(this, "This device is now restricted to YouTube only", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, BarndoorVpnService::class.java))
            }
        }

        alertsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startService(Intent(this, DeviceMonitorService::class.java))
            } else {
                stopService(Intent(this, DeviceMonitorService::class.java))
            }
        }
    }

    private fun startHotspotFlow() {
        // Prefer LocalOnlyHotspot when we have permission; otherwise send the
        // user to the system settings screen. Either way the user is always
        // the one who actually turns the radio on - Android does not allow a
        // silent/hidden hotspot, by design.
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocation) {
            hotspotManager.startLocalOnlyHotspot(
                onStarted = { ssid, _ ->
                    hotspotStatusText.text = "Status: on (${ssid ?: "hotspot active"})"
                },
                onFailed = { reason ->
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                    hotspotSwitch.isChecked = false
                }
            )
        } else {
            hotspotManager.openSystemHotspotSettings()
            hotspotStatusText.text = "Status: opened system settings"
        }
    }

    private fun saveFilterList() {
        val domains = blockedDomainsInput.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (domains.isEmpty()) {
            Toast.makeText(this, "Enter at least one domain", Toast.LENGTH_SHORT).show()
            return
        }

        startFilterService(domains, BarndoorVpnService.Mode.BLOCK_LIST)
        Toast.makeText(this, "Filter list saved (${domains.size} domains blocked)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Starts (or restarts) the on-device filter in either BLOCK_LIST mode
     * (block just these domains) or ALLOW_LIST mode (block everything
     * except these domains - used for the YouTube-only focus mode).
     */
    private fun startFilterService(domains: List<String>, mode: BarndoorVpnService.Mode) {
        val intent = Intent(this, BarndoorVpnService::class.java)
        intent.putStringArrayListExtra(BarndoorVpnService.EXTRA_DOMAIN_LIST, ArrayList(domains))
        intent.putExtra(BarndoorVpnService.EXTRA_MODE, mode.name)

        val prepareIntent = android.net.VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
            pendingVpnIntent = intent
        } else {
            startService(intent)
        }
    }

    private var pendingVpnIntent: Intent? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            pendingVpnIntent?.let { startService(it) }
            pendingVpnIntent = null
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val VPN_REQUEST_CODE = 1002
    }
}
