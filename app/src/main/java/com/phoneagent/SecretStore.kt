package com.phoneagent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secrets live in an EncryptedSharedPreferences file (AES-256 key in the
 * Android keystore); non-secret settings stay in the plain prefs file that
 * the adb debugging flows pre-seed.
 */
object SecretStore {

    private const val FILE = "phone_agent_secrets"

    private fun sp(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun deepseekKey(ctx: Context): String = try {
        sp(ctx).getString("deepseek_key", "") ?: ""
    } catch (_: Exception) {
        ""
    }

    fun setDeepseekKey(ctx: Context, v: String) {
        sp(ctx).edit().putString("deepseek_key", v).apply()
    }
}
