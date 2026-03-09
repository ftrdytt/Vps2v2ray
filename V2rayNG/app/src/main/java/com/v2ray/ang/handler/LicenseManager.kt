package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

object V2rayCrypt {
    private const val SECRET_KEY = "DarkTunlKey12345" 
    private const val PREFS_NAME = "V2rayProtectedConfigs"
    private const val KEY_GUIDS = "ProtectedGuids"
    private const val KEY_ADMIN_GUIDS = "AdminGuids" 
    private const val KEY_EXPIRY_PREFIX = "Expiry_"
    private const val KEY_LICENSE_PREFIX = "License_"
    private const val KEY_SUBSCRIBERS_LIST_PREFIX = "Subscribers_" 

    // حفظ وقراءة عدد النشطين محلياً لعرضه في الواجهة
    fun saveActiveCount(context: Context, guid: String, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("Active_$guid", count).apply()
    }
    fun getActiveCount(context: Context, guid: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("Active_$guid", 0)
    }

    // حفظ بصمة الكود لمنع التكرار اللانهائي
    fun saveLastConfigHash(context: Context, guid: String, hash: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("Hash_$guid", hash).apply()
    }

    fun getLastConfigHash(context: Context, guid: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("Hash_$guid", 0)
    }

    fun encrypt(data: String, expiryTimeMs: Long, licenseId: String): String {
        return try {
            val payload = "$expiryTimeMs||$licenseId||$data"
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(payload.toByteArray())
            "ENC://" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decryptAndCheckExpiry(data: String): Triple<String, Long, String>? {
        return try {
            if (!data.startsWith("ENC://")) return null
            val actualData = data.replace("ENC://", "")
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decryptedBytes = cipher.doFinal(Base64.decode(actualData, Base64.NO_WRAP))
            val decryptedString = String(decryptedBytes)

            val parts = decryptedString.split("||", limit = 3)
            if (parts.size == 3) {
                val expiryTimeMs = parts[0].toLongOrNull() ?: 0L
                val licenseId = parts[1]
                val configData = parts[2]
                return Triple(configData, expiryTimeMs, licenseId)
            } else if (parts.size == 2) {
                val expiryTimeMs = parts[0].toLongOrNull() ?: 0L
                val configData = parts[1]
                return Triple(configData, expiryTimeMs, "LEGACY")
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun saveExpiryTime(context: Context, guid: String, expiryTimeMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_EXPIRY_PREFIX + guid, expiryTimeMs).apply()
    }

    fun getExpiryTime(context: Context, guid: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_EXPIRY_PREFIX + guid, 0L)
    }

    fun saveLicenseId(context: Context, guid: String, licenseId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LICENSE_PREFIX + guid, licenseId).apply()
    }

    fun getLicenseId(context: Context, guid: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LICENSE_PREFIX + guid, "") ?: ""
    }

    fun addProtectedGuids(context: Context, newGuids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GUIDS, mutableSetOf()) ?: mutableSetOf()
        val updated = current.toMutableSet().apply { addAll(newGuids) }
        prefs.edit().putStringSet(KEY_GUIDS, updated).apply()
    }

    fun getAllProtectedGuids(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_GUIDS, emptySet()) ?: emptySet()
    }

    fun isProtected(context: Context, guid: String): Boolean {
        return getAllProtectedGuids(context).contains(guid)
    }

    fun addAdminGuid(context: Context, guid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_ADMIN_GUIDS, mutableSetOf()) ?: mutableSetOf()
        val updated = current.toMutableSet().apply { add(guid) }
        prefs.edit().putStringSet(KEY_ADMIN_GUIDS, updated).apply()
    }

    fun isAdmin(context: Context, guid: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_ADMIN_GUIDS, emptySet()) ?: emptySet()
        return current.contains(guid)
    }

    fun saveSubscriberLocally(context: Context, parentGuid: String, subscriberLicenseId: String, subscriberName: String, expiryTimeMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        val currentListJson = prefs.getString(key, "[]") ?: "[]"
        try {
            val jsonArray = org.json.JSONArray(currentListJson)
            val newSub = JSONObject().apply {
                put("licenseId", subscriberLicenseId)
                put("name", subscriberName)
                put("expiry", expiryTimeMs)
            }
            jsonArray.put(newSub)
            prefs.edit().putString(key, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("V2rayCrypt", "Failed to save subscriber", e)
        }
    }

    fun getSubscribers(context: Context, parentGuid: String): List<SubscriberData> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        val currentListJson = prefs.getString(key, "[]") ?: "[]"
        val list = mutableListOf<SubscriberData>()
        try {
            val jsonArray = org.json.JSONArray(currentListJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SubscriberData(
                        licenseId = obj.getString("licenseId"),
                        name = obj.getString("name"),
                        expiryTimeMs = obj.getLong("expiry")
                    )
                )
            }
        } catch (e: Exception) { }
        return list
    }

    fun updateSubscriberExpiryLocally(context: Context, parentGuid: String, subscriberLicenseId: String, newExpiry: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        val currentListJson = prefs.getString(key, "[]") ?: "[]"
        try {
            val jsonArray = org.json.JSONArray(currentListJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("licenseId") == subscriberLicenseId) {
                    obj.put("expiry", newExpiry)
                    break
                }
            }
            prefs.edit().putString(key, jsonArray.toString()).apply()
        } catch (e: Exception) { }
    }

    fun removeSubscriberLocally(context: Context, parentGuid: String, subscriberLicenseId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        val currentListJson = prefs.getString(key, "[]") ?: "[]"
        try {
            val jsonArray = org.json.JSONArray(currentListJson)
            val newArray = org.json.JSONArray()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("licenseId") != subscriberLicenseId) {
                    newArray.put(obj)
                }
            }
            prefs.edit().putString(key, newArray.toString()).apply()
        } catch (e: Exception) { }
    }

    data class SubscriberData(val licenseId: String, val name: String, val expiryTimeMs: Long)
}

object NetworkTime {
    var isInitialized = false
    private var networkTimeOffset: Long = 0L
    private const val PREFS_NAME = "NetworkTimePrefs"
    private const val KEY_LAST_TIME = "LastTrustedTime"

    suspend fun syncTime(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://cp.cloudflare.com/generate_204")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.connect()
                
                val serverTime = connection.date 
                if (serverTime > 0) {
                    networkTimeOffset = serverTime - android.os.SystemClock.elapsedRealtime()
                    isInitialized = true
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putLong(KEY_LAST_TIME, serverTime).apply()
                }
            } catch (e: Exception) {
                Log.e("NetworkTime", "Failed to sync internet time")
            }
        }
    }

    fun currentTimeMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTrusted = prefs.getLong(KEY_LAST_TIME, 0L)
        val calculatedTime = if (isInitialized) {
            android.os.SystemClock.elapsedRealtime() + networkTimeOffset
        } else {
            System.currentTimeMillis() 
        }
        val finalTime = max(calculatedTime, lastTrusted)
        if (finalTime > lastTrusted + 60000) {
            prefs.edit().putLong(KEY_LAST_TIME, finalTime).apply()
        }
        return finalTime
    }
}

object CloudflareAPI {
    private const val BASE_URL = "https://vpn-license.rauter505.workers.dev"
    private const val ADMIN_KEY = "ashor_vip_admin_999"

    // تم إضافة إرجاع عدد النشطين (activeCount)
    suspend fun checkLiveConfig(licenseId: String): Triple<Long, String?, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/check?guid=$licenseId")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    
                    val obj = JSONObject(response)
                    val expiry = if (obj.has("expiryTime")) obj.getLong("expiryTime") else -1L
                    val configData = if (obj.has("configData") && !obj.isNull("configData")) obj.getString("configData") else null
                    val activeCount = if (obj.has("activeCount")) obj.getInt("activeCount") else 0
                    
                    Triple(expiry, configData, activeCount)
                } else {
                    Triple(-1L, null, 0) 
                }
            } catch (e: Exception) {
                Triple(-1L, null, 0) 
            }
        }
    }

    // إرسال حالة التشغيل للسحابة لزيادة أو تنقيص عدد النشطين
    suspend fun sendActiveState(id: String, isConnecting: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val action = if (isConnecting) "up" else "down"
                val url = URL("$BASE_URL/active?id=$id&action=$action")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.responseCode // فقط ننفذ الطلب
            } catch (e: Exception) {}
        }
    }

    suspend fun updateExpiry(licenseId: String, newExpiryMs: Long): Boolean {
        return createOrUpdateSubscriber(licenseId, newExpiryMs, null)
    }

    suspend fun createOrUpdateSubscriber(licenseId: String, newExpiryMs: Long, configData: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/update")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Admin-Key", ADMIN_KEY)
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("guid", licenseId)
                    put("expiryTime", newExpiryMs)
                    if (configData != null) {
                        put("configData", Base64.encodeToString(configData.toByteArray(), Base64.NO_WRAP))
                    }
                }

                conn.outputStream.use { os ->
                    val input = payload.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                conn.responseCode == 200
            } catch (e: Exception) {
                false
            }
        }
    }
}
