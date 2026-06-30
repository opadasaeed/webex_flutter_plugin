package com.webex.flutter.calling.feature

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ciscowebex.androidsdk.CompletionHandler
import com.ciscowebex.androidsdk.phone.Call
import com.ciscowebex.androidsdk.phone.CallMembership
import com.ciscowebex.androidsdk.phone.CallObserver
import com.ciscowebex.androidsdk.phone.MediaOption
import com.ciscowebex.androidsdk.phone.MediaRenderView
import com.ciscowebex.androidsdk.phone.Phone

class WebexCallActivity : Activity() {
    private lateinit var remoteView: MediaRenderView
    private lateinit var localView: MediaRenderView
    private lateinit var selfViewFrame: View
    private lateinit var voiceInfoContainer: View
    private lateinit var voiceTitleText: TextView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView

    private lateinit var muteButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var participantsButton: ImageButton
    private lateinit var chatButton: ImageButton
    private lateinit var hangupButton: ImageButton

    private lateinit var participantsPanel: View
    private lateinit var participantsTitle: TextView
    private lateinit var participantsContainer: LinearLayout
    private lateinit var participantsCloseButton: ImageButton

    private lateinit var chatPanel: View
    private lateinit var chatScroll: ScrollView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: ImageButton
    private lateinit var chatCloseButton: ImageButton

    private var activeCall: Call? = null
    private var isMuted = false
    private var isCameraOn = true
    private var completionSent = false
    private var pendingCall: WebexCallingEngine.PendingCall? = null
    private val chatTransport: ChatTransport = LocalEchoChatTransport()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webex_call)

        bindViews()

        pendingCall =
            WebexCallingEngine.peekPendingCall()
                ?: run {
                    finishWithError("No pending call request.")
                    return
                }

        val pending = pendingCall!!
        isCameraOn = !pending.audioOnly
        titleText.text = pending.displayTitle
        voiceTitleText.text = pending.displayTitle
        statusText.text = "Connecting..."

        configureControlsForMode(pending.audioOnly)
        wireListeners()
        startChat(pending)

        if (hasRequiredPermissions(pending.audioOnly)) {
            startCall(pending)
        } else {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions(pending.audioOnly),
                CAMERA_PERMISSION_REQUEST_CODE,
            )
        }
    }

    private fun bindViews() {
        remoteView = findViewById(R.id.remoteView)
        localView = findViewById(R.id.localView)
        selfViewFrame = findViewById(R.id.selfViewFrame)
        voiceInfoContainer = findViewById(R.id.voiceInfoContainer)
        voiceTitleText = findViewById(R.id.voiceTitleText)
        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)

        muteButton = findViewById(R.id.muteButton)
        cameraButton = findViewById(R.id.cameraButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        participantsButton = findViewById(R.id.participantsButton)
        chatButton = findViewById(R.id.chatButton)
        hangupButton = findViewById(R.id.hangupButton)

        participantsPanel = findViewById(R.id.participantsPanel)
        participantsTitle = findViewById(R.id.participantsTitle)
        participantsContainer = findViewById(R.id.participantsContainer)
        participantsCloseButton = findViewById(R.id.participantsCloseButton)

        chatPanel = findViewById(R.id.chatPanel)
        chatScroll = findViewById(R.id.chatScroll)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        chatInput = findViewById(R.id.chatInput)
        chatSendButton = findViewById(R.id.chatSendButton)
        chatCloseButton = findViewById(R.id.chatCloseButton)
    }

    private fun configureControlsForMode(audioOnly: Boolean) {
        val videoVisibility = if (audioOnly) View.GONE else View.VISIBLE
        cameraButton.visibility = videoVisibility
        switchCameraButton.visibility = videoVisibility
        voiceInfoContainer.visibility = if (audioOnly) View.VISIBLE else View.GONE
    }

    private fun wireListeners() {
        muteButton.setOnClickListener { toggleMute() }
        cameraButton.setOnClickListener { toggleCamera() }
        switchCameraButton.setOnClickListener { switchCamera() }
        participantsButton.setOnClickListener { toggleParticipants() }
        chatButton.setOnClickListener { toggleChat() }
        hangupButton.setOnClickListener {
            activeCall?.hangup(CompletionHandler { })
            finish()
        }
        participantsCloseButton.setOnClickListener {
            participantsPanel.visibility = View.GONE
        }
        chatCloseButton.setOnClickListener { chatPanel.visibility = View.GONE }
        chatSendButton.setOnClickListener { sendChat() }
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
        chatTransport.stop()
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
                selfViewFrame.visibility = View.VISIBLE
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
                        refreshRoster()
                    }
                }

                override fun onRinging(call: Call?) {
                    super.onRinging(call)
                    runOnUiThread { statusText.text = "Ringing..." }
                }

                override fun onWaiting(
                    call: Call?,
                    reason: Call.WaitReason?,
                ) {
                    super.onWaiting(call, reason)
                    runOnUiThread { statusText.text = reason?.name ?: "Waiting..." }
                }

                override fun onMediaChanged(event: CallObserver.MediaChangedEvent?) {
                    super.onMediaChanged(event)
                    runOnUiThread { refreshRoster() }
                }

                override fun onCallMembershipChanged(
                    event: CallObserver.CallMembershipChangedEvent?,
                ) {
                    super.onCallMembershipChanged(event)
                    runOnUiThread { refreshRoster() }
                }

                override fun onDisconnected(event: CallObserver.CallDisconnectedEvent?) {
                    super.onDisconnected(event)
                    runOnUiThread { finish() }
                }
            },
        )
    }

    private fun toggleMute() {
        val call = activeCall ?: return
        isMuted = !isMuted
        call.setSendingAudio(!isMuted)
        muteButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteButton.setBackgroundResource(
            if (isMuted) R.drawable.bg_circle_primary else R.drawable.bg_circle_control,
        )
    }

    private fun toggleCamera() {
        val call = activeCall ?: return
        isCameraOn = !isCameraOn
        call.setSendingVideo(isCameraOn)
        selfViewFrame.visibility = if (isCameraOn) View.VISIBLE else View.GONE
        cameraButton.setImageResource(
            if (isCameraOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
        )
        cameraButton.setBackgroundResource(
            if (isCameraOn) R.drawable.bg_circle_control else R.drawable.bg_circle_primary,
        )
    }

    private fun switchCamera() {
        val call = activeCall ?: return
        val next =
            if (call.getFacingMode() == Phone.FacingMode.USER) {
                Phone.FacingMode.ENVIROMENT
            } else {
                Phone.FacingMode.USER
            }
        call.setFacingMode(next)
    }

    private fun toggleParticipants() {
        val show = participantsPanel.visibility != View.VISIBLE
        participantsPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            chatPanel.visibility = View.GONE
            refreshRoster()
        }
    }

    private fun toggleChat() {
        val show = chatPanel.visibility != View.VISIBLE
        chatPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            participantsPanel.visibility = View.GONE
        }
    }

    private fun refreshRoster() {
        val memberships = activeCall?.getMemberships() ?: emptyList()
        participantsContainer.removeAllViews()
        participantsTitle.text = "Participants (${memberships.size})"

        if (memberships.isEmpty()) {
            participantsContainer.addView(
                makeTextView(
                    "No participants yet.",
                    0xFF9AA3AE.toInt(),
                    13f,
                ),
            )
            return
        }

        for (membership in memberships) {
            participantsContainer.addView(buildParticipantRow(membership))
        }
    }

    private fun buildParticipantRow(membership: CallMembership): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        val pad = dp(10)
        row.setPadding(pad, pad, pad, pad)

        val displayName = membership.getDisplayName()?.takeIf { it.isNotBlank() } ?: "Participant"
        val nameColor = if (membership.isActiveSpeaker()) 0xFF0ACEAE.toInt() else 0xFFFFFFFF.toInt()
        row.addView(makeTextView(displayName, nameColor, 15f))

        val mic = if (membership.isSendingAudio()) "Mic on" else "Mic off"
        val cam = if (membership.isSendingVideo()) "Cam on" else "Cam off"
        val state = membership.getState()?.name ?: ""
        row.addView(makeTextView("$mic  -  $cam  -  $state", 0xFF9AA3AE.toInt(), 12f))

        return row
    }

    private fun startChat(pending: WebexCallingEngine.PendingCall) {
        chatTransport.start(pending.address) { sender, text, fromMe ->
            runOnUiThread { appendChatMessage(sender, text, fromMe) }
        }
    }

    private fun sendChat() {
        val text = chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return
        }
        chatInput.setText("")
        chatTransport.send(text)
    }

    private fun appendChatMessage(
        sender: String,
        text: String,
        fromMe: Boolean,
    ) {
        val bubble = TextView(this)
        bubble.text = "$sender: $text"
        bubble.setTextColor(0xFFFFFFFF.toInt())
        bubble.textSize = 14f
        val pad = dp(8)
        bubble.setPadding(pad, pad, pad, pad)
        bubble.setBackgroundColor(if (fromMe) 0xFF2644BC.toInt() else 0xFF1B2233.toInt())

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        params.topMargin = dp(4)
        params.gravity = if (fromMe) Gravity.END else Gravity.START
        bubble.layoutParams = params

        chatMessagesContainer.addView(bubble)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun makeTextView(
        text: String,
        color: Int,
        size: Float,
    ): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextColor(color)
        view.textSize = size
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
