package com.webex.flutter.calling.feature

import android.app.Activity
import android.app.Application
import com.ciscowebex.androidsdk.CompletionHandler
import com.ciscowebex.androidsdk.Webex
import com.ciscowebex.androidsdk.WebexUCLoginDelegate
import com.ciscowebex.androidsdk.auth.OAuthWebViewAuthenticator
import com.ciscowebex.androidsdk.auth.PhoneServiceRegistrationFailureReason
import com.ciscowebex.androidsdk.auth.UCLoginServerConnectionStatus
import com.ciscowebex.androidsdk.auth.UCSSOFailureReason
import com.ciscowebex.androidsdk.phone.Call
import com.ciscowebex.androidsdk.phone.CallObserver
import com.ciscowebex.androidsdk.phone.MediaOption
import com.ciscowebex.androidsdk.phone.Phone
import java.lang.ref.WeakReference

class WebexCallingEngine : WebexUCLoginDelegate {
    private var webex: Webex? = null
    private var authenticator: OAuthWebViewAuthenticator? = null
    private var activeCall: Call? = null
    private var hostActivityRef: WeakReference<Activity>? = null
    private var lastPhoneFailureReason: PhoneServiceRegistrationFailureReason? = null

    val webexPhone: Phone?
        get() = webex?.phone

    init {
        instance = this
    }

    fun initialize(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        email: String?,
        additionalScopes: List<String>,
        activity: Activity,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (email.isNullOrBlank()) {
            onComplete(
                Result.failure(
                    IllegalArgumentException(
                        "email is required on Android for Webex OAuth cluster discovery.",
                    ),
                ),
            )
            return
        }

        hostActivityRef = WeakReference(activity)
        val application = currentApplication()
        val scope = additionalScopes.joinToString(" ").ifBlank { "spark:all" }
        val oauth =
            OAuthWebViewAuthenticator(
                clientId,
                clientSecret,
                scope,
                redirectUri,
                email,
            )
        authenticator = oauth

        val instance = Webex(application, oauth)
        instance.delegate = this
        webex = instance

        instance.initialize(
            CompletionHandler { _ ->
                activity.runOnUiThread {
                    if (oauth.isAuthorized()) {
                        onSignedIn(onComplete)
                        return@runOnUiThread
                    }

                    pendingOAuth =
                        PendingOAuth(oauth) { authResult ->
                            authResult
                                .onSuccess { onSignedIn(onComplete) }
                                .onFailure { onComplete(Result.failure(it)) }
                        }

                    WebexOAuthActivity.launchForOAuth(activity)
                }
            },
        )
    }

    fun getPhoneServicesStatus(): String {
        val instance = webex ?: return UCLoginServerConnectionStatus.Disconnected.name
        val status = instance.getUCServerConnectionStatus()
        val callingType = instance.phone.getCallingType()

        val statusLabel =
            if (status == UCLoginServerConnectionStatus.Failed &&
                lastPhoneFailureReason != null
            ) {
                "${status.name} ($lastPhoneFailureReason)"
            } else {
                status.name
            }
        return "$statusLabel [$callingType]"
    }

    fun dial(
        activity: Activity,
        phoneNumber: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    ) {
        launchCallScreen(
            activity = activity,
            pending =
                PendingCall(
                    type = CallType.PHONE,
                    address = null,
                    phoneNumber = phoneNumber,
                    audioOnly = audioOnly,
                    displayTitle = phoneNumber,
                    onComplete = onComplete,
                ),
        )
    }

    fun joinMeeting(
        activity: Activity,
        address: String,
        audioOnly: Boolean,
        onComplete: (Result<String?>) -> Unit,
    ) {
        launchCallScreen(
            activity = activity,
            pending =
                PendingCall(
                    type = CallType.MEETING,
                    address = address,
                    phoneNumber = null,
                    audioOnly = audioOnly,
                    displayTitle = "Webex Meeting",
                    onComplete = onComplete,
                ),
        )
    }

    fun hangup(callId: String?) {
        val call = resolveCall(callId) ?: return
        call.hangup(CompletionHandler { })
        if (activeCall?.getCallId() == call.getCallId()) {
            activeCall = null
        }
    }

    fun setMuted(
        muted: Boolean,
        callId: String?,
    ) {
        resolveCall(callId)?.setSendingAudio(!muted)
    }

    fun hold(
        onHold: Boolean,
        callId: String?,
    ) {
        resolveCall(callId)?.holdCall(onHold, CompletionHandler { })
    }

    fun answer(callId: String?) {
        resolveCall(callId)?.answer(MediaOption.audioOnly(), CompletionHandler { })
    }

    fun reject(callId: String?) {
        resolveCall(callId)?.reject(CompletionHandler { })
    }

    override fun loadUCSSOViewInBackground(ssoUrl: String) {
        val context = hostActivityRef?.get() ?: currentApplication()
        if (context is Activity) {
            context.runOnUiThread {
                WebexOAuthActivity.launchForUcSso(context, ssoUrl)
            }
        } else {
            WebexOAuthActivity.launchForUcSso(context, ssoUrl)
        }
    }

    override fun onUCSSOLoginFailed(failureReason: UCSSOFailureReason) {
        webex?.retryUCSSOLogin()
    }

    override fun onUCServerConnectionStateChanged(
        status: UCLoginServerConnectionStatus,
        failureReason: PhoneServiceRegistrationFailureReason,
    ) {
        if (status == UCLoginServerConnectionStatus.Failed) {
            lastPhoneFailureReason = failureReason
        }
    }

    private fun launchCallScreen(
        activity: Activity,
        pending: PendingCall,
    ) {
        if (webex == null) {
            pending.onComplete(
                Result.failure(IllegalStateException("Webex is not initialized.")),
            )
            return
        }

        pendingCall = pending
        WebexCallActivity.launch(activity)
    }

    private fun onSignedIn(onComplete: (Result<Unit>) -> Unit) {
        onComplete(Result.success(Unit))
        schedulePhoneServicesInBackground()
    }

    private fun schedulePhoneServicesInBackground() {
        val host = hostActivityRef?.get()
        val register = { startPhoneServicesInBackground() }
        if (host == null) {
            register()
        } else {
            host.window.decorView.postDelayed(register, PHONE_SERVICES_START_DELAY_MS)
        }
    }

    private fun startPhoneServicesInBackground() {
        val instance = webex ?: return
        lastPhoneFailureReason = null

        when (instance.phone.getCallingType()) {
            Phone.CallingType.CUCM -> instance.startUCServices()
            Phone.CallingType.WebexCalling,
            Phone.CallingType.WebexForBroadworks,
            ->
                instance.phone.connectPhoneServices(CompletionHandler { })
            else -> Unit
        }
    }

    fun trackCall(call: Call) {
        attachCallObserver(call)
    }

    private fun attachCallObserver(call: Call?) {
        if (call == null) {
            return
        }
        activeCall = call
        call.setObserver(
            object : CallObserver {
                override fun onDisconnected(
                    event: CallObserver.CallDisconnectedEvent?,
                ) {
                    super.onDisconnected(event)
                    if (activeCall?.getCallId() == call.getCallId()) {
                        activeCall = null
                    }
                }
            },
        )
    }

    private fun resolveCall(callId: String?): Call? {
        if (callId == null) {
            return activeCall
        }
        return if (activeCall?.getCallId() == callId) activeCall else null
    }

    private fun currentApplication(): Application {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.getMethod("currentApplication").invoke(null) as Application
        } catch (error: Exception) {
            throw IllegalStateException("Unable to access Application context.", error)
        }
    }

    enum class CallType {
        MEETING,
        PHONE,
    }

    data class PendingCall(
        val type: CallType,
        val address: String?,
        val phoneNumber: String?,
        val audioOnly: Boolean,
        val displayTitle: String,
        val onComplete: (Result<String?>) -> Unit,
    )

    internal data class PendingOAuth(
        val authenticator: OAuthWebViewAuthenticator,
        val onComplete: (Result<Unit>) -> Unit,
    )

    companion object {
        private const val PHONE_SERVICES_START_DELAY_MS = 750L

        @Volatile
        var instance: WebexCallingEngine? = null
            private set

        private var pendingOAuth: PendingOAuth? = null
        private var pendingCall: PendingCall? = null

        internal fun consumePendingOAuth(): PendingOAuth? {
            val pending = pendingOAuth
            pendingOAuth = null
            return pending
        }

        internal fun consumePendingCall(): PendingCall? {
            val pending = pendingCall
            pendingCall = null
            return pending
        }

        internal fun peekPendingCall(): PendingCall? = pendingCall

        internal fun clearPendingCall() {
            pendingCall = null
        }
    }
}
