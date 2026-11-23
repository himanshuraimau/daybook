package com.daybook.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountSession(val ownerId: String, val email: String, val token: String)

class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("daybook_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("daybook_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
    fun save(session: AccountSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = JSONObject().put("id", session.ownerId).put("email", session.email).put("token", session.token).toString().toByteArray(Charsets.UTF_8)
        val stored = cipher.iv + cipher.doFinal(plain)
        prefs.edit().putString("value", Base64.encodeToString(stored, Base64.NO_WRAP)).apply()
    }
    fun get(): AccountSession? {
        val saved = prefs.getString("value", null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            val obj = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            AccountSession(obj.getString("id"), obj.getString("email"), obj.getString("token"))
        } catch (_: Exception) { clear(); null }
    }
    fun clear() { prefs.edit().remove("value").apply() }
}
