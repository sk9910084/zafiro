package com.niki914.shizuku.display

import android.content.Context
import com.niki914.shizuku.ShizukuClient

/**
 * Snapshot of the three Shizuku prerequisites the virtual display needs.
 * Written to fill the gap in the Claude fix: VirtualDisplayPageRoute
 * calls [readShizukuStatus], which did not exist in the repo.
 */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val authorized: Boolean,
)

/**
 * Synchronous snapshot of the Shizuku prerequisites.
 * Every read is a cheap binder ping wrapped in runCatching, so it is
 * safe to call from the UI thread (diagnoseFailure runs in LaunchedEffect).
 */
fun readShizukuStatus(context: Context): ShizukuStatus {
    val client = runCatching { ShizukuClient.create(context) }.getOrNull()
        ?: return ShizukuStatus(installed = false, running = false, authorized = false)
    val installed = runCatching { client.isInstalled }.getOrDefault(false)
    if (!installed) return ShizukuStatus(installed = false, running = false, authorized = false)
    val running = runCatching { client.isBinderAlive }.getOrDefault(false)
    val authorized = if (running) runCatching { client.isAuthorized }.getOrDefault(false) else false
    return ShizukuStatus(installed = installed, running = running, authorized = authorized)
}
