package com.webex.flutter.calling

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class WebexCallingPlugin :
    FlutterPlugin,
    MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventsChannel: EventChannel
    private lateinit var moduleEventsChannel: EventChannel
    private lateinit var appContext: Context
    private lateinit var moduleInstaller: WebexModuleInstaller
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermissionResult: Result? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        moduleInstaller = WebexModuleInstaller(appContext)

        methodChannel = MethodChannel(binding.binaryMessenger, "webex_calling")
        methodChannel.setMethodCallHandler(this)

        eventsChannel = EventChannel(binding.binaryMessenger, "webex_calling/events")
        eventsChannel.setStreamHandler(this)

        moduleEventsChannel =
            EventChannel(binding.binaryMessenger, "webex_calling/module_install")
        moduleEventsChannel.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(
                    arguments: Any?,
                    events: EventChannel.EventSink?,
                ) {
                    moduleInstaller.setEventSink(events)
                }

                override fun onCancel(arguments: Any?) {
                    moduleInstaller.setEventSink(null)
                }
            },
        )
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result,
    ) {
        when (call.method) {
            "isModuleInstalled" -> result.success(moduleInstaller.isInstalled())
            "installModule" ->
                moduleInstaller.startInstall { installResult ->
                    installResult
                        .onSuccess { result.success(null) }
                        .onFailure { result.error("INSTALL_FAILED", it.message, null) }
                }
            "cancelModuleInstall" -> {
                moduleInstaller.cancelInstall()
                result.success(null)
            }
            "initialize" ->
                withBridge(result) { bridge ->
                    val hostActivity = activity
                    if (hostActivity == null) {
                        result.error(
                            "NO_ACTIVITY",
                            "Cannot sign in without a foreground Activity.",
                            null,
                        )
                        return@withBridge
                    }

                    val clientId = call.argument<String>("clientId").orEmpty()
                    val clientSecret = call.argument<String>("clientSecret").orEmpty()
                    val redirectUri = call.argument<String>("redirectUri").orEmpty()
                    val email = call.argument<String>("email")
                    val scopes = call.argument<List<String>>("additionalScopes").orEmpty()

                    bridge.initialize(
                        clientId,
                        clientSecret,
                        redirectUri,
                        email,
                        scopes,
                        hostActivity,
                    ) { initResult ->
                        hostActivity.runOnUiThread {
                            initResult
                                .onSuccess { result.success(true) }
                                .onFailure {
                                    result.error(
                                        "INIT_FAILED",
                                        it.message ?: "Webex initialization failed.",
                                        null,
                                    )
                                }
                        }
                    }
                }
            "getPhoneServicesStatus" ->
                withBridge(result) { bridge ->
                    result.success(bridge.getPhoneServicesStatus())
                }
            "dial" ->
                withBridge(result) { bridge ->
                    val hostActivity = activity
                    if (hostActivity == null) {
                        result.error(
                            "NO_ACTIVITY",
                            "Cannot start a call without a foreground Activity.",
                            null,
                        )
                        return@withBridge
                    }

                    val phoneNumber = call.argument<String>("phoneNumber").orEmpty()
                    val audioOnly = call.argument<Boolean>("audioOnly") ?: true
                    withCallPermissions(result, audioOnly) {
                        bridge.dial(hostActivity, phoneNumber, audioOnly) { dialResult ->
                            hostActivity.runOnUiThread {
                                dialResult
                                    .onSuccess { result.success(it) }
                                    .onFailure {
                                        result.error(
                                            "DIAL_FAILED",
                                            it.message ?: "Unable to start call.",
                                            null,
                                        )
                                    }
                            }
                        }
                    }
                }
            "joinMeeting" ->
                withBridge(result) { bridge ->
                    val hostActivity = activity
                    if (hostActivity == null) {
                        result.error(
                            "NO_ACTIVITY",
                            "Cannot join a meeting without a foreground Activity.",
                            null,
                        )
                        return@withBridge
                    }

                    val address = call.argument<String>("address").orEmpty()
                    val audioOnly = call.argument<Boolean>("audioOnly") ?: false
                    withCallPermissions(result, audioOnly) {
                        bridge.joinMeeting(hostActivity, address, audioOnly) { joinResult ->
                            hostActivity.runOnUiThread {
                                joinResult
                                    .onSuccess { result.success(it) }
                                    .onFailure {
                                        result.error(
                                            "JOIN_FAILED",
                                            it.message ?: "Unable to join meeting.",
                                            null,
                                        )
                                    }
                            }
                        }
                    }
                }
            "hangup" ->
                withBridge(result) { bridge ->
                    bridge.hangup(call.argument("callId"))
                    result.success(null)
                }
            "setMuted" ->
                withBridge(result) { bridge ->
                    val muted = call.argument<Boolean>("muted") ?: false
                    bridge.setMuted(muted, call.argument("callId"))
                    result.success(null)
                }
            "hold" ->
                withBridge(result) { bridge ->
                    val onHold = call.argument<Boolean>("onHold") ?: false
                    bridge.hold(onHold, call.argument("callId"))
                    result.success(null)
                }
            "answer" ->
                withBridge(result) { bridge ->
                    withCallPermissions(result, audioOnly = true) {
                        bridge.answer(call.argument("callId"))
                        result.success(null)
                    }
                }
            "reject" ->
                withBridge(result) { bridge ->
                    bridge.reject(call.argument("callId"))
                    result.success(null)
                }
            else -> result.notImplemented()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != CALL_PERMISSIONS_REQUEST_CODE) {
            return false
        }

        val result = pendingPermissionResult
        val action = pendingPermissionAction
        pendingPermissionResult = null
        pendingPermissionAction = null

        if (result == null) {
            return true
        }

        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            action?.invoke()
        } else {
            result.error(
                "PERMISSION_DENIED",
                "Microphone and phone state permissions are required for calls and meetings.",
                null,
            )
        }
        return true
    }

    private fun withCallPermissions(
        result: Result,
        audioOnly: Boolean,
        block: () -> Unit,
    ) {
        val host = activity
        if (host == null) {
            result.error(
                "NO_ACTIVITY",
                "Cannot request permissions without a foreground Activity.",
                null,
            )
            return
        }

        val missing =
            requiredCallPermissions(audioOnly).filter {
                ContextCompat.checkSelfPermission(host, it) !=
                    PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            block()
            return
        }

        pendingPermissionResult = result
        pendingPermissionAction = block
        ActivityCompat.requestPermissions(
            host,
            missing.toTypedArray(),
            CALL_PERMISSIONS_REQUEST_CODE,
        )
    }

    private fun withBridge(
        result: Result,
        block: (WebexCallingBridge) -> Unit,
    ) {
        val bridge = WebexCallingBridgeLoader.getBridge()
        if (bridge == null) {
            result.error(
                "MODULE_NOT_INSTALLED",
                "Webex Calling module is not installed. Call installModule() first.",
                null,
            )
            return
        }
        block(bridge)
    }

    override fun onListen(
        arguments: Any?,
        events: EventChannel.EventSink?,
    ) {
        WebexCallingBridgeLoader.getBridge()?.let {
            // Event streaming is wired inside the dynamic feature module in future iterations.
        }
        events?.success(
            mapOf(
                "type" to "unknown",
                "reason" to "Event bridge not connected yet.",
            ),
        )
    }

    override fun onCancel(arguments: Any?) = Unit

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventsChannel.setStreamHandler(null)
        moduleEventsChannel.setStreamHandler(null)
        moduleInstaller.dispose()
        WebexCallingBridgeLoader.clear()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivityBinding()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        detachActivityBinding()
    }

    private fun detachActivityBinding() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
        pendingPermissionResult = null
        pendingPermissionAction = null
    }

    companion object {
        private const val CALL_PERMISSIONS_REQUEST_CODE = 991024

        private fun requiredCallPermissions(audioOnly: Boolean): Array<String> {
            val permissions =
                mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                )
            if (!audioOnly) {
                permissions.add(Manifest.permission.CAMERA)
            }
            return permissions.toTypedArray()
        }
    }
}
