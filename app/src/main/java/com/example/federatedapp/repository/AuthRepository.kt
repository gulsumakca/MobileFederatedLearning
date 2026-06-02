package com.example.federatedapp.repository

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Cihaz-yerel kimlik doğrulama (login / register).
 *
 * Kullanıcılar ve şifre hash'leri SharedPreferences'ta tutulur — backend yok,
 * federe öğrenme sunucusuna dokunmaz. Şifreler SHA-256 ile hash'lenip saklanır
 * (düz metin asla yazılmaz). Tamamen eklenti; mevcut sistemden bağımsızdır.
 */
class AuthRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_CURRENT_USER = "current_user"
        private const val PREFIX_USER = "user_"
        const val GUEST_NAME = "Misafir"
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun currentUser(): String? = prefs.getString(KEY_CURRENT_USER, null)

    /** Yeni kullanıcı kaydı. Başarılıysa otomatik giriş yapar. */
    fun register(username: String, password: String): Result<Unit> {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty())
            return Result.failure(Exception("Kullanıcı adı ve şifre boş olamaz"))
        if (password.length < 4)
            return Result.failure(Exception("Şifre en az 4 karakter olmalı"))
        if (prefs.contains(PREFIX_USER + u))
            return Result.failure(Exception("Bu kullanıcı adı zaten kayıtlı"))

        prefs.edit().putString(PREFIX_USER + u, hash(password)).apply()
        setSession(u)
        return Result.success(Unit)
    }

    /** Giriş doğrulama. */
    fun login(username: String, password: String): Result<Unit> {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty())
            return Result.failure(Exception("Kullanıcı adı ve şifre boş olamaz"))
        val stored = prefs.getString(PREFIX_USER + u, null)
            ?: return Result.failure(Exception("Kullanıcı bulunamadı"))
        if (stored != hash(password))
            return Result.failure(Exception("Şifre hatalı"))

        setSession(u)
        return Result.success(Unit)
    }

    /** Loginsiz (misafir) giriş. */
    fun continueAsGuest() = setSession(GUEST_NAME)

    fun logout() {
        prefs.edit().putBoolean(KEY_LOGGED_IN, false).remove(KEY_CURRENT_USER).apply()
    }

    private fun setSession(user: String) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_CURRENT_USER, user)
            .apply()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
