package com.barndoor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.barndoor.app.apps.AppsFragment
import com.barndoor.app.databinding.ActivityMainBinding
import com.barndoor.app.dns.DnsFragment
import com.barndoor.app.rotation.RotationFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnGrantCallback: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingVpnGrantCallback
        pendingVpnGrantCallback = null
        if (result.resultCode == RESULT_OK) callback?.invoke()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way; foreground notifications are best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()

        if (savedInstanceState == null) {
            showFragment(DnsFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dns -> DnsFragment()
                R.id.nav_apps -> AppsFragment()
                R.id.nav_rotation -> RotationFragment()
                else -> DnsFragment()
            }
            showFragment(fragment)
            true
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Launches the system VPN consent dialog if [intent] is non-null, invoking [onGranted]
     * immediately if consent was already given (i.e. [intent] is null) or once the user
     * accepts the dialog.
     */
    fun requestVpnConsent(intent: Intent?, onGranted: () -> Unit) {
        if (intent == null) {
            onGranted()
        } else {
            pendingVpnGrantCallback = onGranted
            vpnPermissionLauncher.launch(intent)
        }
    }
}
