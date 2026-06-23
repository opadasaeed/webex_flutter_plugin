package com.webex.flutter.calling.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import com.ciscowebex.androidsdk.CompletionHandler
import com.ciscowebex.androidsdk.auth.OAuthWebViewAuthenticator
import com.ciscowebex.androidsdk.auth.UCSSOWebViewAuthenticator

class WebexOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_UC_SSO -> startUcSso(webView)
            else -> startOAuth(webView)
        }
    }

    private fun startOAuth(webView: WebView) {
        val pending =
            WebexCallingEngine.consumePendingOAuth()
                ?: run {
                    finishWithError()
                    return
                }

        pending.authenticator.authorize(
            webView,
            CompletionHandler { result ->
                runOnUiThread {
                    if (result.isSuccessful) {
                        setResult(RESULT_OK)
                        finish()
                        pending.onComplete(Result.success(Unit))
                    } else {
                        setResult(RESULT_CANCELED)
                        finish()
                        pending.onComplete(
                            Result.failure(
                                IllegalStateException(
                                    result.error?.errorMessage ?: "OAuth authorization failed.",
                                ),
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun startUcSso(webView: WebView) {
        val ssoUrl =
            intent.getStringExtra(EXTRA_SSO_URL)
                ?: run {
                    finishWithError()
                    return
                }

        UCSSOWebViewAuthenticator.launchWebView(
            webView,
            ssoUrl,
            CompletionHandler { result ->
                runOnUiThread {
                    if (result.isSuccessful) {
                        setResult(RESULT_OK)
                    } else {
                        setResult(RESULT_CANCELED)
                    }
                    finish()
                }
            },
        )
    }

    private fun finishWithError() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SSO_URL = "ssoUrl"
        const val MODE_OAUTH = "oauth"
        const val MODE_UC_SSO = "uc_sso"

        fun launchForOAuth(context: Context) {
            context.startActivity(newIntent(context, MODE_OAUTH))
        }

        fun launchForUcSso(context: Context, ssoUrl: String) {
            context.startActivity(newIntent(context, MODE_UC_SSO, ssoUrl))
        }

        private fun newIntent(
            context: Context,
            mode: String,
            ssoUrl: String? = null,
        ): Intent =
            Intent(context, WebexOAuthActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra(EXTRA_MODE, mode)
                if (ssoUrl != null) {
                    putExtra(EXTRA_SSO_URL, ssoUrl)
                }
            }
    }
}
