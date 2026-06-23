package com.webex.flutter.calling

object WebexCallingBridgeLoader {
    private const val ENGINE_CLASS =
        "com.webex.flutter.calling.feature.WebexCallingEngine"

    @Volatile
    private var bridge: WebexCallingBridge? = null

    fun getBridge(): WebexCallingBridge? {
        bridge?.let { return it }

        return runCatching {
            val clazz = Class.forName(ENGINE_CLASS)
            val engine = clazz.getDeclaredConstructor().newInstance()
            val proxy = WebexCallingEngineProxy(engine)
            bridge = proxy
            proxy
        }.getOrNull()
    }

    fun clear() {
        bridge = null
    }
}
