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

    fun reportUpdateSuccess(context: Context) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val reportedVersion = prefs.getInt("reported_version", 0)
        val currentVersion = BuildConfig.VERSION_CODE

        if (reportedVersion < currentVersion) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val userId = AuthManager.getId(context)
                    if (userId.isNotEmpty() && AuthManager.getRole(context) != "admin") {
                        val url = URL("https://vpn-license.rauter505.workers.dev/app/log_update")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true

                        val payload = JSONObject().put("id", userId).put("version", currentVersion)
                        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                        if (conn.responseCode == 200) {
                            prefs.edit().putInt("reported_version", currentVersion).apply()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun sendFileActivePing(context: Context, guid: String, deviceId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(context)
                val name = if (userId.isNotEmpty()) AuthManager.getName(context) else "مجهول الهوية"
                val pfp = if (userId.isNotEmpty()) AuthManager.getPfp(context) else ""

                val conn = URL("https://vpn-license.rauter505.workers.dev/file/ping").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = JSONObject()
                    .put("guid", guid)
                    .put("deviceId", deviceId)
                    .put("userId", userId)
                    .put("name", name)
                    .put("pfp", pfp)

                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    fun sendAppActivePing(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(context)
                if (userId.isNotEmpty() && AuthManager.getRole(context) != "admin") {
                    val conn = URL("https://vpn-license.rauter505.workers.dev/admin/ping_active").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray()) }
                    conn.responseCode
                }
            } catch (e: Exception) {}
        }
    }
}
