package com.niki914.zafiro.app.ui.route

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.shizuku.display.ShizukuStatus
import com.niki914.shizuku.display.readShizukuStatus
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.screen.VirtualDisplayAppPicker
import com.niki914.zafiro.app.ui.model.VirtualDisplayEffect
import com.niki914.zafiro.app.ui.model.VirtualDisplayIntent
import com.niki914.zafiro.app.ui.model.VirtualDisplayViewModel
import com.niki914.zafiro.app.ui.nav.VirtualDisplayPage

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

private enum class VdFailureReason {
    ShizukuMissing,
    ShizukuNotRunning,
    ShizukuNotAuthorized,
    OverlayMissing,
    Other,
}

private data class VdFailure(
    val reason: VdFailureReason,
    val shizuku: ShizukuStatus,
    val overlayGranted: Boolean,
)

/** Re-reads the live prerequisites so the dialog names the step that is actually missing. */
private fun diagnoseFailure(context: Context): VdFailure {
    val shizuku = readShizukuStatus(context)
    val overlay = Settings.canDrawOverlays(context)
    val reason = when {
        !shizuku.installed -> VdFailureReason.ShizukuMissing
        !shizuku.running -> VdFailureReason.ShizukuNotRunning
        !shizuku.authorized -> VdFailureReason.ShizukuNotAuthorized
        !overlay -> VdFailureReason.OverlayMissing
        else -> VdFailureReason.Other
    }
    return VdFailure(reason, shizuku, overlay)
}

@Composable
internal fun VirtualDisplayPageRoute(
    page: VirtualDisplayPage,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = pageViewModel<VirtualDisplayViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val gridState = rememberLazyGridState()
    val overscrollController = rememberZafiroOverscrollController()
    var failure by remember { mutableStateOf<VdFailure?>(null) }

    ReportTitleBarCollapsed {
        gridState.firstVisibleItemIndex > 0 ||
            gridState.firstVisibleItemScrollOffset > 0 ||
            overscrollController.overscrollOffset.value < 0f
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                VirtualDisplayEffect.CreatedAndClose -> onBack()
                is VirtualDisplayEffect.Failed -> failure = diagnoseFailure(context)
            }
        }
    }

    failure?.let { current ->
        VirtualDisplayFailureDialog(
            failure = current,
            onOpenShizuku = {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
            onDismiss = { failure = null },
        )
    }

    val topPadding = zafiroScreenTopPadding(16.dp)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    VirtualDisplayAppPicker(
        recentApps = uiState.recentApps,
        allApps = uiState.allApps,
        isLoading = uiState.isLoadingApps,
        enabled = !uiState.isCreating,
        loadingPackageName = uiState.creatingPackageName,
        gridState = gridState,
        overscrollController = overscrollController,
        compact = false,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding,
            bottom = navBottom + 20.dp,
        ),
        onSelectApp = { packageName ->
            viewModel.sendIntent(
                VirtualDisplayIntent.CreateAndLaunch(
                    conversationId = page.conversationId,
                    packageName = packageName,
                )
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun VirtualDisplayFailureDialog(
    failure: VdFailure,
    onOpenShizuku: () -> Unit,
    onDismiss: () -> Unit,
) {
    val yes = stringResource(R.string.ui_vd_yes)
    val no = stringResource(R.string.ui_vd_no)
    val message = stringResource(
        when (failure.reason) {
            VdFailureReason.ShizukuMissing -> R.string.ui_vd_fail_shizuku_missing
            VdFailureReason.ShizukuNotRunning -> R.string.ui_vd_fail_shizuku_not_running
            VdFailureReason.ShizukuNotAuthorized -> R.string.ui_vd_fail_shizuku_not_authorized
            VdFailureReason.OverlayMissing -> R.string.ui_vd_fail_overlay
            VdFailureReason.Other -> R.string.ui_vd_fail_other
        }
    )
    val status = stringResource(
        R.string.ui_vd_status_fmt,
        if (failure.shizuku.installed) yes else no,
        if (failure.shizuku.running) yes else no,
        if (failure.shizuku.authorized) yes else no,
        if (failure.overlayGranted) yes else no,
    )
    val canOpenShizuku = failure.reason == VdFailureReason.ShizukuNotRunning ||
        failure.reason == VdFailureReason.ShizukuNotAuthorized

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_vd_fail_title)) },
        text = { Text("$message\n\n$status") },
        confirmButton = {
            if (canOpenShizuku) {
                TextButton(onClick = onOpenShizuku) { Text(stringResource(R.string.ui_vd_open_shizuku)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_vd_close)) }
            }
        },
        dismissButton = if (canOpenShizuku) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_vd_close)) } }
        } else {
            null
        },
    )
}
