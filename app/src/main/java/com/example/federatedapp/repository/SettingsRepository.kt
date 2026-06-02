package com.example.federatedapp.repository

import android.content.SharedPreferences

class SettingsRepository(private val prefs: SharedPreferences) {

    companion object {
        val SOURCES = listOf("NTV", "CNN Türk", "Sabah", "TRT Haber", "Sözcü")
        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 30
        const val DEFAULT_SERVER_URL = "10.0.2.2:8080"
    }

    fun getSourceLimit(source: String): Int =
        prefs.getInt("limit_$source", DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

    fun setSourceLimit(source: String, limit: Int) {
        prefs.edit().putInt("limit_$source", limit.coerceIn(MIN_LIMIT, MAX_LIMIT)).apply()
    }

    fun getAllLimits(): Map<String, Int> = SOURCES.associateWith { getSourceLimit(it) }

    fun applyLimits(limits: Map<String, Int>) {
        limits.forEach { (source, limit) -> setSourceLimit(source, limit) }
    }

    fun getServerUrl(): String =
        prefs.getString("flower_server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun setServerUrl(url: String) {
        prefs.edit().putString("flower_server_url", url.trim()).apply()
    }
}
