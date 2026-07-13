package com.barndoor.app.dns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * Root doesn't need its own DNS mechanism — it just removes the one manual step
 * (ADB) that [SystemDnsManager] otherwise requires. Once granted via root, every
 * existing system-mode code path just works unchanged.
 *
 * Root commands are never run from a Quick Settings tile (the su prompt can take a
 * while, or wait on the user, and blocking a tile's onClick() risks an ANR) — only
 * from the app itself, off the main thread.
 */
object RootManager {

    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/su/bin/su", "/system/bin/.ext/su"
    )

    fun looksRooted(): Boolean {
        if (suPaths.any { java.io.File(it).exists() }) return true
        return try {
            val process = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            process.waitFor()
            process.inputStream.bufferedReader().readText().trim().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs `pm grant <applicationId> android.permission.WRITE_SECURE_SETTINGS` through
     * a root shell. This is what triggers the root manager's (Magisk/SuperSU/etc.)
     * permission prompt the first time; it's silent on every call after that.
     */
    suspend fun grantSystemDnsPermission(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            val process = Runtime.getRuntime().exec("su")
            val out = DataOutputStream(process.outputStream)
            out.writeBytes("$command\n")
            out.writeBytes("exit\n")
            out.flush()
            out.close()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                throw IllegalStateException(
                    error.ifBlank { "Root shell exited with code $exitCode (permission denied or no root manager?)" }
                )
            }
        }
    }
}
