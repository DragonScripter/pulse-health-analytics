package com.jma.pulsehealthanalytics

import android.R
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "secure_prefs",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun saveToken(token: String) = prefs.edit().putString("jwt", token).apply()
    fun getToken(): String? = prefs.getString("jwt", null)
    fun clear() = prefs.edit().remove("jwt").apply()
}