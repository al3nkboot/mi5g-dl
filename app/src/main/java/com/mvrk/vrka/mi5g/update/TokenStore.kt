package com.mvrk.vrka.mi5g.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mi5G Dl: stores the user's GitHub token encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (non-exportable). Only the ciphertext is kept in app-private SharedPreferences
 * (app backup is disabled). The token is never synced, logged or shown again after saving.
 */
class TokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mi5g_secure", Context.MODE_PRIVATE)

    fun hasToken(): Boolean = prefs.contains(KEY_CT)

    fun get(): String? = runCatching {
        val blob = Base64.decode(prefs.getString(KEY_CT, null) ?: return null, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun set(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Empty token" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_LEN)
        val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CT, Base64.encodeToString(iv + ct, Base64.NO_WRAP))
            .putString(KEY_HINT, maskedHint(clean))
            .apply()
    }

    /** e.g. "github_pat_…x9Q2" — enough to recognise the token without revealing it. */
    fun hint(): String? = prefs.getString(KEY_HINT, null)

    fun clear() {
        prefs.edit().remove(KEY_CT).remove(KEY_HINT).apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mi5gdl_github_token_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val KEY_CT = "github_token_ct"
        private const val KEY_HINT = "github_token_hint"

        fun maskedHint(token: String): String {
            val prefix = when {
                token.startsWith("github_pat_") -> "github_pat_"
                token.startsWith("ghp_") -> "ghp_"
                else -> ""
            }
            return prefix + "…" + token.takeLast(4)
        }
    }
}
