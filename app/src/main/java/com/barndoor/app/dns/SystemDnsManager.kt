package com.barndoor.app.dns

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android's own DNS-over-TLS setting (Settings > Network > Private DNS). This is the
 * *only* way a non-root, non-system app can change the device's actual system DNS
 * with zero VPN interface involved — but writing to it requires the WRITE_SECURE_SETTINGS
 * permission, which apps can't request through a normal runtime dialog. It has to be
 * granted once via ADB:
 *
 *   adb shell pm grant <applicationId> android.permission.WRITE_SECURE_SETTINGS
 *
 * Two hard platform limits, not implementation choices:
 *  - Only works for DNS-over-TLS (hostname) resolvers — Android's Private DNS has no
 *    concept of a plain IP:port resolver.
 *  - It's a single global setting — there's no way to make it apply to one app only,
 *    so per-app DNS assignments always need the VPN-based proxy regardless.
 */
object SystemDnsManager {

    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True only when system mode is actually usable right now: permission held + a DoT hostname available. */
    fun canUseSystemMode(context: Context, server: DnsServer): Boolean =
        hasPermission(context) && !server.dotHostname.isNullOrBlank()

    fun apply(context: Context, hostname: String): Boolean {
        return try {
            Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
            Settings.Global.putString(context.contentResolver, "private_dns_specifier", hostname)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Returns Private DNS to "automatic" (opportunistic) — the normal Android default. */
    fun clear(context: Context): Boolean {
        return try {
            Settings.Global.putString(context.contentResolver, "private_dns_mode", "opportunistic")
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun currentHostname(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "private_dns_specifier")
    } catch (e: SecurityException) {
        null
    }

    private fun currentMode(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "private_dns_mode")
    } catch (e: SecurityException) {
        null
    }

    /**
     * Reads Android's *actual current* Private DNS state rather than trusting anything
     * Barndoor cached — this is what lets the app notice if Private DNS was turned off
     * (or changed) from Android's own Settings app instead of from here, and correct
     * its own "running" status instead of showing a stale state forever.
     */
    fun isCurrentlyActive(context: Context, expectedHostname: String): Boolean {
        if (!hasPermission(context)) return false
        return currentMode(context) == "hostname" && currentHostname(context) == expectedHostname
    }
}
