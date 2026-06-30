package com.webex.flutter.calling.feature

/**
 * Abstraction over in-meeting chat delivery.
 *
 * Webex SDKs cannot send/receive real-time Meeting Center chat, so this seam
 * lets the host wire a real backend (e.g. SignalR) without touching the UI.
 * The default [LocalEchoChatTransport] only echoes locally for testing.
 */
interface ChatTransport {
    fun interface Listener {
        fun onMessage(sender: String, text: String, fromMe: Boolean)
    }

    fun start(meetingId: String?, listener: Listener)

    fun send(text: String)

    fun stop()
}

class LocalEchoChatTransport : ChatTransport {
    private var listener: ChatTransport.Listener? = null

    override fun start(meetingId: String?, listener: ChatTransport.Listener) {
        this.listener = listener
    }

    override fun send(text: String) {
        listener?.onMessage("You", text, true)
        listener?.onMessage("Echo", text, false)
    }

    override fun stop() {
        listener = null
    }
}
