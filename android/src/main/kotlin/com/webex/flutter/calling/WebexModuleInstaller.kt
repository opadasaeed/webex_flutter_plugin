package com.webex.flutter.calling

import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import io.flutter.plugin.common.EventChannel

class WebexModuleInstaller(
    private val context: Context,
    private val moduleName: String = MODULE_NAME,
) {
    private val installManager = SplitInstallManagerFactory.create(context)
    private var activeSessionId: Int? = null
    private var eventSink: EventChannel.EventSink? = null

    private val listener =
        SplitInstallStateUpdatedListener { state ->
            if (activeSessionId != null && state.sessionId() != activeSessionId) {
                return@SplitInstallStateUpdatedListener
            }

            val payload =
                when (state.status()) {
                    SplitInstallSessionStatus.PENDING ->
                        mapOf("status" to "pending", "progress" to 0.0)
                    SplitInstallSessionStatus.DOWNLOADING -> {
                        val total = state.totalBytesToDownload().coerceAtLeast(1L)
                        val progress = state.bytesDownloaded().toDouble() / total.toDouble()
                        mapOf("status" to "downloading", "progress" to progress)
                    }
                    SplitInstallSessionStatus.INSTALLING ->
                        mapOf("status" to "installing", "progress" to 0.95)
                    SplitInstallSessionStatus.INSTALLED ->
                        mapOf("status" to "installed", "progress" to 1.0)
                    SplitInstallSessionStatus.FAILED ->
                        mapOf(
                            "status" to "failed",
                            "progress" to 0.0,
                            "errorMessage" to errorMessage(state.errorCode()),
                        )
                    SplitInstallSessionStatus.CANCELED ->
                        mapOf("status" to "cancelled", "progress" to 0.0)
                    else -> null
                }

            payload?.let { eventSink?.success(it) }
        }

    fun setEventSink(sink: EventChannel.EventSink?) {
        eventSink = sink
    }

    fun isInstalled(): Boolean {
        if (WebexCallingBridgeLoader.getBridge() != null) {
            return true
        }
        return installManager.installedModules.contains(moduleName)
    }

    fun startInstall(onComplete: (Result<Unit>) -> Unit) {
        if (isInstalled()) {
            eventSink?.success(mapOf("status" to "installed", "progress" to 1.0))
            onComplete(Result.success(Unit))
            return
        }

        installManager.registerListener(listener)

        val request =
            SplitInstallRequest
                .newBuilder()
                .addModule(moduleName)
                .build()

        installManager
            .startInstall(request)
            .addOnSuccessListener { sessionId ->
                activeSessionId = sessionId
                onComplete(Result.success(Unit))
            }            .addOnFailureListener { error ->
                val message =
                    when (error) {
                        is SplitInstallException -> errorMessage(error.errorCode)
                        else -> error.message ?: "Module install failed."
                    }
                eventSink?.success(
                    mapOf(
                        "status" to "failed",
                        "progress" to 0.0,
                        "errorMessage" to message,
                    ),
                )
                onComplete(Result.failure(IllegalStateException(message)))
            }
    }

    fun cancelInstall() {
        activeSessionId?.let { installManager.cancelInstall(it) }
    }

    fun dispose() {
        installManager.unregisterListener(listener)
        eventSink = null
    }

    private fun errorMessage(errorCode: Int): String =
        when (errorCode) {
            SplitInstallErrorCode.NETWORK_ERROR -> "Network error while downloading module."
            SplitInstallErrorCode.MODULE_UNAVAILABLE -> "Webex Calling module is unavailable."
            SplitInstallErrorCode.INSUFFICIENT_STORAGE -> "Not enough storage to install module."
            SplitInstallErrorCode.ACTIVE_SESSIONS_LIMIT_EXCEEDED ->
                "Too many module installs in progress."
            SplitInstallErrorCode.API_NOT_AVAILABLE ->
                "Play Feature Delivery is unavailable because the app was not installed " +
                "from Google Play. Use flutter run for local debug, or install a build from " +
                "Play internal testing / bundletool --local-testing."
            SplitInstallErrorCode.APP_NOT_OWNED ->
                "This app build is not owned by the Play Store account on this device."
            else -> "Module install failed with code $errorCode."
        }

    companion object {
        const val MODULE_NAME = "webex_calling_feature"
    }
}
