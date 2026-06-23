package com.example.webex_calling_example

import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SplitCompat.install(this)
    }
}
