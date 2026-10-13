package com.alatta.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google web sign-in hand-off, bound to this installation with a one-time PKCE-style verifier.
 * No Google ID token or app session is placed in browser URLs or Android deep links.
 * Authentication still completes on the Cloudflare server after Google's ID token verification.
 */
internal object GoogleWebBridge {
    private const val DOMAIN = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
    const val SCHEME = "alatta-native"
    const val HOST = "google-auth"

    private fun nonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun begin(context: Context, createAccount: Boolean) {
        val verifier = nonce()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val state = nonce()
        context.getSharedPreferences("alatta_google_handoff", Context.MODE_PRIVATE).edit()
            .putString("verifier", verifier)
            .putString("state", state)
            .putString("mode", if (createAccount) "signup" else "login")
            .putLong("startedAt", System.currentTimeMillis())
            .apply()
        val uri = Uri.parse("$DOMAIN/android-google-auth.html").buildUpon()
            .appendQueryParameter("challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("mode", if (createAccount) "signup" else "login")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    data class Handoff(val code: String, val verifier: String, val mode: String)

    fun takeHandoff(context: Context, redirect: Uri): Handoff? {
        if (redirect.scheme != SCHEME || redirect.host != HOST) return null
        val prefs = context.getSharedPreferences("alatta_google_handoff", Context.MODE_PRIVATE)
        val expectedState = prefs.getString("state", "") ?: ""
        val state = redirect.getQueryParameter("state") ?: ""
        val code = redirect.getQueryParameter("code") ?: ""
        val verifier = prefs.getString("verifier", "") ?: ""
        val mode = prefs.getString("mode", "login") ?: "login"
        val started = prefs.getLong("startedAt", 0L)
        // Consume the verifier exactly once, even on invalid callbacks.
        prefs.edit().clear().apply()
        if (expectedState.length < 32 || state != expectedState || verifier.length < 32 ||
            System.currentTimeMillis() - started !in 0L..300_000L ||
            !Regex("[A-Za-z0-9_-]{32,100}").matches(code)
        ) throw IllegalStateException("انتهت صلاحية اختيار حساب Google. حاول مرة أخرى.")
        return Handoff(code, verifier, mode)
    }
}
