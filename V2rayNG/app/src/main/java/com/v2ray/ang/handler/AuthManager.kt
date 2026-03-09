package com.v2ray.ang.handler

import android.content.Context
import android.content.SharedPreferences

object AuthManager {
    private const val PREFS = "VpnAuthPrefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun hasLoggedOut(context: Context): Boolean {
        return getPrefs(context).getBoolean("has_logged_out", false)
    }

    fun saveUser(context: Context, id: String, name: String, pass: String, role: String, pfp: String) {
        getPrefs(context).edit()
            .putString("id", id)
            .putString("name", name)
            .putString("pass", pass)
            .putString("role", role)
            .putString("pfp", pfp)
            .putBoolean("has_logged_out", false)
            .apply()
    }

    fun logout(context: Context) {
        getPrefs(context).edit().clear().putBoolean("has_logged_out", true).apply()
    }

    fun isLoggedIn(context: Context): Boolean = getPrefs(context).getString("id", null) != null
    fun getId(context: Context): String = getPrefs(context).getString("id", "جاري الاتصال...") ?: "جاري الاتصال..."
    fun getName(context: Context): String = getPrefs(context).getString("name", "مستخدم جديد") ?: "مستخدم جديد"
    fun getPass(context: Context): String = getPrefs(context).getString("pass", "") ?: ""
    fun getRole(context: Context): String = getPrefs(context).getString("role", "user") ?: "user"
    fun getPfp(context: Context): String = getPrefs(context).getString("pfp", "") ?: ""
}
