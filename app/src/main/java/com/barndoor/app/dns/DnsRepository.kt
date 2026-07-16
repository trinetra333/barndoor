package com.barndoor.app.dns

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class DnsMode { VPN, SYSTEM }

/**
 * Single source of truth for the DNS list, the device-wide default selection, which
 * servers the quick tile cycles through, per-app overrides, and which mechanism
 * (VPN proxy vs Android's native Private DNS) is currently active.
 *
 * Selection is tracked by server ID, not list position — a positional index silently
 * goes stale (and can point at the wrong server, or crash on an out-of-range lookup)
 * the moment the list is reordered or an entry is deleted, so ID is the only thing
 * that's ever persisted here.
 */
class DnsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServers(): List<DnsServer> {
        val stored = DnsServer.listFromJson(prefs.getString(KEY_SERVERS, null))
        return if (stored.isEmpty()) DnsServer.PRESETS else stored
    }

    /** Same servers, but with the currently-selected one pinned to the top for display. */
    fun getServersForDisplay(): List<DnsServer> {
        val all = getServers()
        val selectedId = getSelectedServerId() ?: return all
        val selected = all.find { it.id == selectedId } ?: return all
        return listOf(selected) + all.filterNot { it.id == selectedId }
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
        if (getSelectedServerId() == server.id) {
            updated.firstOrNull()?.let { setSelectedServerId(it.id) }
        }
    }

    fun getServerById(id: String): DnsServer? = getServers().find { it.id == id }

    fun getSelectedServerId(): String? = prefs.getString(KEY_SELECTED_ID, null)

    fun setSelectedServerId(id: String) {
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
    }

    /** The device-wide default resolver used by the quick tile and any app without an override. */
    fun getSelectedServer(): DnsServer? {
        val servers = getServers()
        val id = getSelectedServerId()
        return servers.find { it.id == id } ?: servers.firstOrNull()
    }

    fun isProxyRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    fun setProxyRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_RUNNING, running).apply()
    }

    var activeMode: DnsMode
        get() = DnsMode.valueOf(prefs.getString(KEY_MODE, DnsMode.VPN.name)!!)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /**
     * Checks the cached "running" state against what's actually true right now and
     * self-corrects if they've drifted apart — e.g. the person turned Private DNS off
     * from Android's own Settings app instead of from here, or the VPN got revoked
     * outside the app. Cheap and local (a Settings.Global read or a ConnectivityManager
     * check, no network), safe to call on every refresh.
     */
    fun syncWithReality(context: Context) {
        if (!isProxyRunning()) return
        val selected = getSelectedServer()
        val actuallyActive = when (activeMode) {
            DnsMode.SYSTEM -> selected?.dotHostname?.let { SystemDnsManager.isCurrentlyActive(context, it) } ?: false
            DnsMode.VPN -> DnsVpnService.isActuallyRunning(context)
        }
        if (!actuallyActive) {
            setProxyRunning(false)
        }
    }

    /**
     * Which server IDs the quick tile cycles through. Defaults to "all of them" the
     * first time this is read (so nothing changes for anyone who hasn't customized
     * it yet); once set, only these are included.
     */
    fun getTileServerIds(): Set<String> {
        val raw = prefs.getStringSet(KEY_TILE_IDS, null)
        return raw ?: getServers().map { it.id }.toSet()
    }

    fun setTileServerIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_TILE_IDS, ids).apply()
    }

    /** Servers the tile should cycle through, falling back to the full list if the set is empty. */
    fun getTileServers(): List<DnsServer> {
        val ids = getTileServerIds()
        val all = getServers()
        val filtered = all.filter { it.id in ids }
        return filtered.ifEmpty { all }
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

    companion object {
        private const val PREFS_NAME = "barndoor_dns_prefs"
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED_ID = "selected_id"
        private const val KEY_RUNNING = "proxy_running"
        private const val KEY_APP_ASSIGNMENTS = "app_assignments"
        private const val KEY_MODE = "active_mode"
        private const val KEY_TILE_IDS = "tile_ids"
    }
}
