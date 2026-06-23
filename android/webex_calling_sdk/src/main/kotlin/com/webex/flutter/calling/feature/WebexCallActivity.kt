package com.webex.flutter.calling.feature

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ciscowebex.androidsdk.CompletionHandler
import com.ciscowebex.androidsdk.phone.Call
import com.ciscowebex.androidsdk.phone.CallObserver
import com.ciscowebex.androidsdk.phone.MediaOption
import com.ciscowebex.androidsdk.phone.MediaRenderView
import com.ciscowebex.androidsdk.phone.Phone

class WebexCallActivity : Activity() {
    private lateinit var remoteView: MediaRenderView
    private lateinit var localView: MediaRenderView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var muteButton: Button
    private lateinit var hangupButton: Button

    private var activeCall: Call? = null
    private var isMuted = false
    private var completionSent = false
    private var pendingCall: WebexCallingEngine.PendingCall? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webex_call)

        remoteView = findViewById(R.id.remoteView)
        localView = findViewById(R.id.localView)
        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)
        muteButton = findViewById(R.id.muteButton)
        hangupButton = findViewById(R.id.hangupButton)

        pendingCall =
            WebexCallingEngine.peekPendingCall()
                ?: run {
                    finishWithError("No pending call request.")
                    return
                }

        titleText.text = pendingCall!!.displayTitle
        statusText.text = "Connecting..."

        muteButton.setOnClickListener {
            val call = activeCall ?: return@setOnClickListener
            isMuted = !isMuted
            call.setSendingAudio(!isMuted)
            muteButton.text = if (isMuted) "Unmute" else "Mute"
        }

        hangupButton.setOnClickListener {
            activeCall?.hangup(CompletionHandler { })
            finish()
        }

        if (hasRequiredPermissions(pendingCall!!.audioOnly)) {
            startCall(pendingCall!!)
        } else {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions(pendingCall!!.audioOnly),
                CAMERA_PERMISSION_REQUEST_CODE,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) {
            return
        }

        val pending = pendingCall
        if (pending == null) {
            finishWithError("Call request expired.")
            return
        }

        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startCall(pending)
        } else {
            finishWithError("Camera permission is required for video meetings.")
        }
    }

    override fun onDestroy() {
        if (!isFinishing && activeCall != null) {
            activeCall?.hangup(CompletionHandler { })
        }
        super.onDestroy()
    }

    private fun startCall(pending: WebexCallingEngine.PendingCall) {
        val engine =
            WebexCallingEngine.instance
                ?: run {
                    finishWithError("Webex is not initialized.")
                    return
                }

        val phone = engine.webexPhone ?: run {
            finishWithError("Webex phone is unavailable.")
            return
        }

        val mediaOption =
            if (pending.audioOnly) {
                MediaOption.audioOnly()
            } else {
                remoteView.visibility = View.VISIBLE
                localView.visibility = View.VISIBLE
                MediaOption.audioVideo(localView, remoteView)
            }

        val dialHandler =
            CompletionHandler<Call> { result ->
                runOnUiThread {
                    if (!result.isSuccessful) {
                        finishWithError(
                            result.error?.errorMessage ?: "Unable to start call.",
                        )
                        return@runOnUiThread
                    }

                    val call = result.data ?: run {
                        finishWithError("Call object was not returned.")
                        return@runOnUiThread
                    }

                    activeCall = call
                    WebexCallingEngine.instance?.trackCall(call)
                    attachCallObserver(call, pending)
                }
            }

        when (pending.type) {
            WebexCallingEngine.CallType.MEETING ->
                phone.dial(pending.address.orEmpty(), mediaOption, dialHandler)
            WebexCallingEngine.CallType.PHONE ->
                phone.dialPhoneNumber(pending.phoneNumber.orEmpty(), mediaOption, dialHandler)
        }
    }

    private fun attachCallObserver(
        call: Call,
        pending: WebexCallingEngine.PendingCall,
    ) {
        call.setObserver(
            object : CallObserver {
                override fun onConnected(call: Call?) {
                    super.onConnected(call)
                    runOnUiThread {
                        statusText.text = "Connected"
                        completeOnce(pending, Result.success(call?.getCallId()))
                    }
                }

                override fun onRinging(call: Call?) {
                    super.onRinging(call)
                    runOnUiThread {
                        statusText.text = "Ringing..."
                    }
                }

                override fun onWaiting(
                    call: Call?,
                    reason: Call.WaitReason?,
                ) {
                    super.onWaiting(call, reason)
                    runOnUiThread {
                        statusText.text = reason?.name ?: "Waiting..."
                    }
                }

                override fun onDisconnected(event: CallObserver.CallDisconnectedEvent?) {
                    super.onDisconnected(event)
                    runOnUiThread { finish() }
                }
            },
        )
    }

    private fun completeOnce(
        pending: WebexCallingEngine.PendingCall,
        result: Result<String?>,
    ) {
        if (completionSent) {
            return
        }
        completionSent = true
        pending.onComplete(result)
        WebexCallingEngine.clearPendingCall()
        pendingCall = null
    }

    private fun finishWithError(message: String) {
        pendingCall?.let { pending ->
            if (!completionSent) {
                completionSent = true
                pending.onComplete(Result.failure(IllegalStateException(message)))
            }
        }
        WebexCallingEngine.clearPendingCall()
        pendingCall = null
        statusText.text = message
        finish()
    }

    private fun hasRequiredPermissions(audioOnly: Boolean): Boolean =
        missingPermissions(audioOnly).isEmpty()

    private fun missingPermissions(audioOnly: Boolean): Array<String> {
        if (audioOnly) {
            return emptyArray()
        }
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 991025

        fun launch(context: Context) {
            val intent =
                Intent(context, WebexCallActivity::class.java).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            context.startActivity(intent)
        }
    }
}
