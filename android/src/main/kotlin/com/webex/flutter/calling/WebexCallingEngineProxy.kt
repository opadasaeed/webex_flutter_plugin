package com.webex.flutter.calling

import android.app.Activity

/**
 * Reflects calls into [WebexCallingEngine] loaded from the dynamic feature module so
 * the SDK module does not need a Gradle dependency on the base plugin (avoids R8 /
 * compileOnly conflicts with Play Feature Delivery).
 */
internal class WebexCallingEngineProxy(
    private val engine: Any,
) : WebexCallingBridge {
    override fun initialize(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        email: String?,
        additionalScopes: List<String>,
        activity: Activity,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        invoke(
            "initialize",
            clientId,
            clientSecret,
            redirectUri,
            email,
            additionalScopes,
            activity,
            onComplete,
        )
    }

    override fun getPhoneServicesStatus(): String =
        invoke("getPhoneServicesStatus") as String

    override fun dial(
        activity: Activity,
        phoneNumber: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    ) {
        invoke("dial", activity, phoneNumber, audioOnly, onComplete)
    }

    override fun joinMeeting(
        activity: Activity,
        address: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    ) {
        invoke("joinMeeting", activity, address, audioOnly, onComplete)
    }

    override fun hangup(callId: String?) {
        invoke("hangup", callId)
    }

    override fun setMuted(
        muted: Boolean,
        callId: String?,
    ) {
        invoke("setMuted", muted, callId)
    }

    override fun hold(
        onHold: Boolean,
        callId: String?,
    ) {
        invoke("hold", onHold, callId)
    }

    override fun answer(callId: String?) {
        invoke("answer", callId)
    }

    override fun reject(callId: String?) {
        invoke("reject", callId)
    }

    private fun invoke(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            engine.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterCount == args.size
            } ?: throw NoSuchMethodException("${engine.javaClass.name}#$name")
        return method.invoke(engine, *args)
    }
}
