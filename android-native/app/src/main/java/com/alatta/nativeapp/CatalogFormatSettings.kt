package com.alatta.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owner-entered configuration stays on this device; no default service value is bundled. */
internal object CatalogFormatSettings {
    private const val PREFS = "alatta_catalog_format_v1"
    private const val PAYLOAD = "encrypted"
    private const val ALIAS = "alatta.catalog.format.v1"

    fun valid(value: String): Boolean = value.length == 16 && value.all { it.code in 32..126 }
    fun configured(context: Context): Boolean = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(PAYLOAD, null).isNullOrBlank()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun save(context: Context, value: String): Boolean {
        if (!valid(value)) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = cipher.iv
            if (iv.size != 12) return false
            val payload = iv + cipher.doFinal(value.toByteArray(Charsets.US_ASCII))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()
        } catch (_: Exception) { false }
    }

    fun read(context: Context): String? = try {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAYLOAD, null)
        if (value == null) null else {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            if (payload.size != 44) null else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
                }
                String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.US_ASCII).takeIf(::valid)
            }
        }
    } catch (_: Exception) { null }

    fun clear(context: Context): Boolean {
        val removed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PAYLOAD).commit()
        if (removed) try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) } }
        catch (_: Exception) { /* Removed ciphertext cannot be restored by retaining an unused device key. */ }
        return removed
    }
}
