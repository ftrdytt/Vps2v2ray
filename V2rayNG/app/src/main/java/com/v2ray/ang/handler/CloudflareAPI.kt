package com.v2ray.ang.handler

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CloudflareAPI {
    // ⚠️ تأكد أن هذا الرابط هو نفس رابط السيرفر الخاص بك
    private const val BASE_URL = "https://vpn-license.rauter505.workers.dev"

    suspend fun checkLiveConfig(licenseId: String): Triple<Long, String?, Int> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/check?guid=$licenseId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val obj = JSONObject(resp)
                val expiry = obj.optLong("expiryTime", 0L)
                val configData = obj.optString("configData", "")
                val activeCount = obj.optInt("activeCount", 0)
                return@withContext Triple(expiry, if (configData.isEmpty() || configData == "null") null else configData, activeCount)
            }
        } catch (e: Exception) {}
        Triple(-1L, null, 0)
    }

    suspend fun sendActiveState(licenseId: String, isActive: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val action = if (isActive) "up" else "down"
            val url = URL("$BASE_URL/active?id=$licenseId&action=$action")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            return@withContext conn.responseCode == 200
        } catch (e: Exception) { false }
    }

    suspend fun updateExpiry(licenseId: String, expiryTime: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/admin/update_expiry")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("licenseId", licenseId)
                put("expiryTime", expiryTime)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            return@withContext conn.responseCode == 200
        } catch (e: Exception) { false }
    }

    suspend fun createOrUpdateSubscriber(licenseId: String, expiryTime: Long, configData: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/admin/upload_config")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("licenseId", licenseId)
                put("expiryTime", expiryTime)
                put("configData", Base64.encodeToString(configData.toByteArray(), Base64.NO_WRAP))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            return@withContext conn.responseCode == 200
        } catch (e: Exception) { false }
    }
}
