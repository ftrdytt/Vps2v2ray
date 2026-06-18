package com.v2ray.ang.handler

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.v2ray.ang.BuildConfig

object ActiveStatsHelper {

    // 🌟 الرابط الجديد الأساسي الآمن والمخفي 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    fun reportUpdateSuccess(context: Context) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val reportedVersion = prefs.getInt("reported_version", 0)
        val currentVersion = BuildConfig.VERSION_CODE

        if (reportedVersion < currentVersion) {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val userId = AuthManager.getId(context)
                    if (userId.isNotEmpty() && AuthManager.getRole(context) != "admin") {
                        // 🌟 استخدام الرابط الجديد 🌟
                        val url = URL("$BASE_API_URL/app/log_update")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true

                        val payload = JSONObject().put("id", userId).put("version", currentVersion)
                        // إضافة ترميز UTF-8
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode == 200) {
                            prefs.edit().putInt("reported_version", currentVersion).apply()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun sendFileActivePing(context: Context, guid: String, deviceId: String) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(context)
                val name = if (userId.isNotEmpty()) AuthManager.getName(context) else "مجهول الهوية"
                val pfp = if (userId.isNotEmpty()) AuthManager.getPfp(context) else ""

                // 🌟 استخدام الرابط الجديد 🌟
                val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = JSONObject()
                    .put("guid", guid)
                    .put("deviceId", deviceId)
                    .put("userId", userId)
                    .put("name", name)
                    .put("pfp", pfp)

                // إضافة ترميز UTF-8 لتجنب تشوه الأسماء العربية
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    fun sendAppActivePing(context: Context) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(context)
                if (userId.isNotEmpty() && AuthManager.getRole(context) != "admin") {
                    // 🌟 استخدام الرابط الجديد 🌟
                    val conn = URL("$BASE_API_URL/admin/ping_active").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    
                    conn.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                }
            } catch (e: Exception) {}
        }
    }
}
