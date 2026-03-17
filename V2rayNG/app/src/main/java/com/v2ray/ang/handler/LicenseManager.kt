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

    fun saveActiveCount(context: Context, guid: String, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("Active_$guid", count).apply()
    }
    
    fun getActiveCount(context: Context, guid: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("Active_$guid", 0)
    }

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
        } catch (e: Exception) { "" }
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
            if (parts.size == 3) return Triple(parts[2], parts[0].toLongOrNull() ?: 0L, parts[1])
            else if (parts.size == 2) return Triple(parts[1], parts[0].toLongOrNull() ?: 0L, "LEGACY")
            null
        } catch (e: Exception) { null }
    }

    fun saveExpiryTime(context: Context, guid: String, expiryTimeMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_EXPIRY_PREFIX + guid, expiryTimeMs).apply()
    }

    fun getExpiryTime(context: Context, guid: String): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_EXPIRY_PREFIX + guid, 0L)
    }

    fun saveLicenseId(context: Context, guid: String, licenseId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LICENSE_PREFIX + guid, licenseId).apply()
    }

    fun getLicenseId(context: Context, guid: String): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LICENSE_PREFIX + guid, "") ?: ""
    }

    fun addProtectedGuids(context: Context, newGuids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_GUIDS, mutableSetOf()) ?: mutableSetOf()).toMutableSet().apply { addAll(newGuids) }
        prefs.edit().putStringSet(KEY_GUIDS, updated).apply()
    }

    fun getAllProtectedGuids(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_GUIDS, emptySet()) ?: emptySet()
    }

    fun isProtected(context: Context, guid: String): Boolean = getAllProtectedGuids(context).contains(guid)

    fun addAdminGuid(context: Context, guid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_ADMIN_GUIDS, mutableSetOf()) ?: mutableSetOf()).toMutableSet().apply { add(guid) }
        prefs.edit().putStringSet(KEY_ADMIN_GUIDS, updated).apply()
    }

    fun isAdmin(context: Context, guid: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_ADMIN_GUIDS, emptySet())?.contains(guid) == true
    }

    fun saveSubscriberLocally(context: Context, parentGuid: String, subscriberLicenseId: String, subscriberName: String, expiryTimeMs: Long, activeCount: Int = 0) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        try {
            val jsonArray = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
            val newSub = JSONObject().apply {
                put("licenseId", subscriberLicenseId)
                put("name", subscriberName)
                put("expiry", expiryTimeMs)
                put("activeCount", activeCount) 
            }
            jsonArray.put(newSub)
            prefs.edit().putString(key, jsonArray.toString()).apply()
        } catch (e: Exception) {}
    }

    fun getSubscribers(context: Context, parentGuid: String): List<SubscriberData> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        val list = mutableListOf<SubscriberData>()
        try {
            val jsonArray = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(SubscriberData(
                    licenseId = obj.getString("licenseId"),
                    name = obj.getString("name"),
                    expiryTimeMs = obj.getLong("expiry"),
                    activeCount = obj.optInt("activeCount", 0)
                ))
            }
        } catch (e: Exception) { }
        return list
    }

    fun updateSubscriberLocally(context: Context, parentGuid: String, subscriberLicenseId: String, newExpiry: Long, activeCount: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        try {
            val jsonArray = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("licenseId") == subscriberLicenseId) {
                    obj.put("expiry", newExpiry)
                    obj.put("activeCount", activeCount)
                    break
                }
            }
            prefs.edit().putString(key, jsonArray.toString()).apply()
        } catch (e: Exception) { }
    }

    fun removeSubscriberLocally(context: Context, parentGuid: String, subscriberLicenseId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SUBSCRIBERS_LIST_PREFIX + parentGuid
        try {
            val jsonArray = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
            val newArray = org.json.JSONArray()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("licenseId") != subscriberLicenseId) newArray.put(obj)
            }
            prefs.edit().putString(key, newArray.toString()).apply()
        } catch (e: Exception) { }
    }

    data class SubscriberData(val licenseId: String, val name: String, val expiryTimeMs: Long, val activeCount: Int = 0)
}

object NetworkTime {
    var isInitialized = false
    private var networkTimeOffset: Long = 0L
    private const val PREFS_NAME = "NetworkTimePrefs"
    private const val KEY_LAST_TIME = "LastTrustedTime"

    suspend fun syncTime(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("http://cp.cloudflare.com/generate_204").openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.connect()
                val serverTime = connection.date 
                if (serverTime > 0) {
                    networkTimeOffset = serverTime - android.os.SystemClock.elapsedRealtime()
                    isInitialized = true
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_TIME, serverTime).apply()
                }
            } catch (e: Exception) {}
        }
    }

    fun currentTimeMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTrusted = prefs.getLong(KEY_LAST_TIME, 0L)
        val calculatedTime = if (isInitialized) android.os.SystemClock.elapsedRealtime() + networkTimeOffset else System.currentTimeMillis() 
        val finalTime = max(calculatedTime, lastTrusted)
        if (finalTime > lastTrusted + 60000) prefs.edit().putLong(KEY_LAST_TIME, finalTime).apply()
        return finalTime
    }
}

object CloudflareAPI {
    private const val BASE_URL = "https://vpn-license.rauter505.workers.dev"

    suspend fun checkLiveConfig(licenseId: String): Triple<Long, String?, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_URL/check?guid=$licenseId").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(response)
                    // التعديل هنا لعدم حذف الوقت إذا لم يكن موجوداً، نتركه كما هو
                    val expiry = if (obj.has("expiryTime")) obj.getLong("expiryTime") else -1L
                    val configData = if (obj.has("configData") && !obj.isNull("configData")) obj.getString("configData") else null
                    val activeCount = if (obj.has("activeCount")) obj.getInt("activeCount") else 0
                    Triple(expiry, configData, activeCount)
                } else Triple(-1L, null, 0) 
            } catch (e: Exception) { Triple(-1L, null, 0) }
        }
    }

    suspend fun sendActiveState(id: String, isConnecting: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val action = if (isConnecting) "up" else "down"
                val conn = URL("$BASE_URL/active?id=$id&action=$action").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.responseCode 
            } catch (e: Exception) {}
        }
    }

    // هنا دمجنا المسارات لتتوافق مع التحديث الذي أجريناه للتو على Cloudflare Worker
    suspend fun updateExpiry(licenseId: String, newExpiryMs: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/admin/update_expiry")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("licenseId", licenseId)
                    put("expiryTime", newExpiryMs)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                return@withContext conn.responseCode == 200
            } catch (e: Exception) { false }
        }
    }

    suspend fun createOrUpdateSubscriber(licenseId: String, newExpiryMs: Long, configData: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/admin/upload_config")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("licenseId", licenseId)
                    put("expiryTime", newExpiryMs)
                    if (configData != null) {
                        put("configData", Base64.encodeToString(configData.toByteArray(), Base64.NO_WRAP))
                    }
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                return@withContext conn.responseCode == 200
            } catch (e: Exception) { false }
        }
    }
}
