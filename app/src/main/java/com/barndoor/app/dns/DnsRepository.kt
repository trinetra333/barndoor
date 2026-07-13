package com.barndoor.app.dns

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Single source of truth for the DNS list, the device-wide default selection, and
 * per-app overrides. Every app routed through the proxy uses the device-wide default
 * *unless* it has an explicit entry in [getAppAssignments], in which case it gets its
 * own resolver instead.
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
        val updated = getServers().filterNot { it.id == server.id }
        saveServers(updated)
        if (getSelectedIndex() >= updated.size) setSelectedIndex(0)
    }

    fun getServerById(id: String): DnsServer? = getServers().find { it.id == id }

    fun getSelectedIndex(): Int = prefs.getInt(KEY_SELECTED_INDEX, 0)

    fun setSelectedIndex(index: Int) {
        prefs.edit().putInt(KEY_SELECTED_INDEX, index).apply()
    }

    /** The device-wide default resolver used by the quick tile and any app without an override. */
    fun getSelectedServer(): DnsServer? {
        val servers = getServers()
        val index = getSelectedIndex()
        return servers.getOrNull(index) ?: servers.firstOrNull()
    }

    fun isProxyRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    fun setProxyRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_RUNNING, running).apply()
    }

    /** packageName -> DnsServer.id, for apps that override the device-wide default. */
    fun getAppAssignments(): Map<String, String> {
        val raw = prefs.getString(KEY_APP_ASSIGNMENTS, null) ?: return emptyMap()
        val obj = JSONObject(raw)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    fun setAppAssignment(packageName: String, dnsId: String?) {
        val current = getAppAssignments().toMutableMap()
        if (dnsId == null) current.remove(packageName) else current[packageName] = dnsId
        val obj = JSONObject()
        current.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_APP_ASSIGNMENTS, obj.toString()).apply()
    }

    /** Resolves the DnsServer a given app should use: its override, or the device-wide default. */
    fun resolveServerForApp(packageName: String?): DnsServer {
        val default = getSelectedServer() ?: DnsServer.PRESETS.first()
        val assignedId = packageName?.let { getAppAssignments()[it] } ?: return default
        return getServerById(assignedId) ?: default
    }

    companion object {
        private const val PREFS_NAME = "barndoor_dns_prefs"
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED_INDEX = "selected_index"
        private const val KEY_RUNNING = "proxy_running"
        private const val KEY_APP_ASSIGNMENTS = "app_assignments"
    }
}
