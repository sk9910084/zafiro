package com.niki914.shizuku.display

import android.content.Context
import android.os.Binder
import android.view.MotionEvent
import android.view.Surface
import com.niki914.logging.Logger
import com.niki914.shizuku.ShizukuClient
import com.niki914.shizuku.internal.runCatchingCancellable
import com.niki914.shizuku.service.ShizukuUserServiceConnection
import com.niki914.shizuku.service.ShizukuUserServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StartAppResult {
    data object Ok : StartAppResult
    data object InUseOnMain : StartAppResult
    data object NotFound : StartAppResult
    data object Failed : StartAppResult
}

sealed interface VirtualDisplayPrepareResult {
    data object Ready : VirtualDisplayPrepareResult

    /** Shizuku app is not installed. */
    data object NotInstalled : VirtualDisplayPrepareResult

    /** Shizuku is installed but its service is not running (binder dead). It must be (re)started. */
    data object NotRunning : VirtualDisplayPrepareResult

    /** Shizuku is running but Zafiro has not been granted permission. */
    data object Denied : VirtualDisplayPrepareResult

    /** Shizuku is running and authorized, but binding Zafiro's user service failed or timed out. */
    data object BindFailed : VirtualDisplayPrepareResult
}

/** Live snapshot of the Shizuku prerequisites, used to tell the user exactly which step is missing. */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val authorized: Boolean,
)

fun readShizukuStatus(
    context: Context,
    client: ShizukuClient = ShizukuClient.create(context),
): ShizukuStatus {
    val installed = runCatching { client.isInstalled }.getOrDefault(false)
    val running = installed && runCatching { client.isBinderAlive }.getOrDefault(false)
    val authorized = running && runCatching { client.isAuthorized }.getOrDefault(false)
    return ShizukuStatus(installed = installed, running = running, authorized = authorized)
}

/**
 * 虚拟屏控制器门面：纯 Kotlin 接口，隐藏 IPC 与 AIDL 实现细节。
 */
interface VirtualDisplayController {
    suspend fun prepare(): VirtualDisplayPrepareResult
    suspend fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int
    suspend fun release(displayId: Int)
    suspend fun startApp(displayId: Int, packageName: String): StartAppResult
    fun injectTouch(displayId: Int, event: MotionEvent)
    fun close()

    companion object {
        fun create(
            context: Context,
            client: ShizukuClient = ShizukuClient.create(context),
        ): VirtualDisplayController {
            return RealVirtualDisplayController(context.applicationContext, client)
        }
    }
}

internal class RealVirtualDisplayController(
    private val context: Context,
    private val client: ShizukuClient,
) : VirtualDisplayController {

    private val hostToken = Binder()
    private var connection: ShizukuUserServiceConnection<IShizukuVirtualDisplayService>? = null

    override suspend fun prepare(): VirtualDisplayPrepareResult {
        if (!client.isInstalled) return VirtualDisplayPrepareResult.NotInstalled
        // Check the binder BEFORE asking for permission: with a dead binder the request can only
        // fail, which used to be reported as "denied".
        if (!client.isBinderAlive) return VirtualDisplayPrepareResult.NotRunning
        if (!client.isAuthorized) {
            val granted = client.requestPermission()
            if (!granted) return VirtualDisplayPrepareResult.Denied
        }

        val active = connection
        if (active != null && active.isAlive) {
            return VirtualDisplayPrepareResult.Ready
        }

        val newConnection = ShizukuUserServiceManager.bind(
            context = context,
            serviceClass = ShizukuVirtualDisplayService::class.java,
            processSuffix = PROCESS_SUFFIX,
            asInterface = IShizukuVirtualDisplayService.Stub::asInterface,
        ) ?: return VirtualDisplayPrepareResult.BindFailed

        runCatchingCancellable { newConnection.service.registerClient(hostToken) }
        connection = newConnection
        return VirtualDisplayPrepareResult.Ready
    }

    override suspend fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        withContext(Dispatchers.IO) {
            val remote = connection?.service ?: return@withContext -1
            runCatchingCancellable {
                remote.createVirtualDisplay(surface, width, height, dpi)
            }.getOrDefault(-1)
        }

    override suspend fun release(displayId: Int) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable { connection?.service?.releaseDisplay(displayId) }
        }
    }

    override suspend fun startApp(displayId: Int, packageName: String): StartAppResult =
        withContext(Dispatchers.IO) {
            val remote = connection?.service ?: return@withContext StartAppResult.Failed
            val code = runCatchingCancellable {
                remote.startApp(displayId, packageName)
            }.getOrDefault(ShizukuVirtualDisplayService.START_ERROR)

            when (code) {
                ShizukuVirtualDisplayService.START_OK -> StartAppResult.Ok
                ShizukuVirtualDisplayService.START_IN_USE_ON_MAIN -> StartAppResult.InUseOnMain
                ShizukuVirtualDisplayService.START_NOT_FOUND -> StartAppResult.NotFound
                else -> StartAppResult.Failed
            }
        }

    override fun injectTouch(displayId: Int, event: MotionEvent) {
        val remote = connection?.service ?: return
        runCatchingCancellable {
            remote.injectMotionEvent(displayId, event)
        }
    }

    override fun close() {
        val current = connection
        connection = null
        if (current != null) {
            runCatchingCancellable { current.service.destroy() }
            runCatchingCancellable { current.close() }
        }
    }

    companion object {
        private const val TAG = "niki914_zafiro_RealVirtualDisplayController"
        private const val PROCESS_SUFFIX = "vd_service"
    }
}
