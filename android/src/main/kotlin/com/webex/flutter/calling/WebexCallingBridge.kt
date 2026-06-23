package com.webex.flutter.calling

import android.app.Activity

/**
 * Contract implemented by the on-demand dynamic feature module.
 * The base plugin loads the implementation via reflection after install.
 */
interface WebexCallingBridge {
    fun initialize(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        email: String?,
        additionalScopes: List<String>,
        activity: Activity,
        onComplete: (Result<Unit>) -> Unit,
    )

    fun getPhoneServicesStatus(): String

    fun dial(
        activity: Activity,
        phoneNumber: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    )

    fun joinMeeting(
        activity: Activity,
        address: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    )

    fun hangup(callId: String?)

    fun setMuted(muted: Boolean, callId: String?)

    fun hold(onHold: Boolean, callId: String?)

    fun answer(callId: String?)

    fun reject(callId: String?)
}
