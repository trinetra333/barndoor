package com.barndoor.app.dns

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the DNS list, which entry is selected, whether the
 * DNS proxy is currently running, and which apps are routed through it.
 *
 * When [selectedApps] is empty, the proxy applies to the whole device (system-wide).
 * When it contains package names, only those apps' DNS traffic is routed through
 * the proxy (everything else keeps using the network's normal DNS).
 */
class DnsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServers(): List<DnsServer> {
        val stored = DnsServer.listFromJson(prefs.getString(KEY_SERVERS, null))
        return if (stored.isEmpty()) DnsServer.PRESETS else stored
    }

    fun saveServers(servers: List<DnsServer>) {
        prefs.edit().putString(KEY_SERVERS, DnsServer.listToJson(servers)).apply()
    }

    fun addCustomServer(server: DnsServer) {
        saveServers(getServers() + server)
    }

    fun removeServer(server: DnsServer) {
        val updated = getServers().filterNot { it.name == server.name && it.primary == server.primary }
        saveServers(updated)
        if (getSelectedIndex() >= updated.size) setSelectedIndex(0)
    }

    fun getSelectedIndex(): Int = prefs.getInt(KEY_SELECTED_INDEX, 0)

    fun setSelectedIndex(index: Int) {
        prefs.edit().putInt(KEY_SELECTED_INDEX, index).apply()
    }

    fun getSelectedServer(): DnsServer? {
        val servers = getServers()
        val index = getSelectedIndex()
        return servers.getOrNull(index) ?: servers.firstOrNull()
    }

    fun isProxyRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    fun setProxyRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun getSelectedApps(): Set<String> =
        prefs.getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun setSelectedApps(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    companion object {
        private const val PREFS_NAME = "barndoor_dns_prefs"
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED_INDEX = "selected_index"
        private const val KEY_RUNNING = "proxy_running"
        private const val KEY_SELECTED_APPS = "selected_apps"
    }
}
