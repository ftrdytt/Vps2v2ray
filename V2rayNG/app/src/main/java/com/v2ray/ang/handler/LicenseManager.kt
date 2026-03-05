package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// =======================================================
// خوارزمية التشفير والقائمة السرية (Global)
// =======================================================
object V2rayCrypt {
    private const val SECRET_KEY = "DarkTunlKey12345" 
    private const val PREFS_NAME = "V2rayProtectedConfigs"
    private const val KEY_GUIDS = "ProtectedGuids"
    private const val KEY_EXPIRY_PREFIX = "Expiry_"

    fun encrypt(data: String, expiryTimeMs: Long): String {
        return try {
            val payload = "$expiryTimeMs||$data"
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(payload.toByteArray())
            "ENC://" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decryptAndCheckExpiry(context: Context, data: String): Pair<String, Long>? {
        return try {
            if (!data.startsWith("ENC://")) return null
            val actualData = data.replace("ENC://", "")
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decryptedBytes = cipher.doFinal(Base64.decode(actualData, Base64.NO_WRAP))
            val decryptedString = String(decryptedBytes)

            val parts = decryptedString.split("||", limit = 2)
            if (parts.size == 2) {
                val expiryTimeMs = parts[0].toLongOrNull() ?: 0L
                val configData = parts[1]
                return Pair(configData, expiryTimeMs)
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

    fun addProtectedGuids(context: Context, newGuids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GUIDS, mutableSetOf()) ?: mutableSetOf()
        val updated = current.toMutableSet().apply { addAll(newGuids) }
        prefs.edit().putStringSet(KEY_GUIDS, updated).apply()
    }

    fun isProtected(context: Context, guid: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GUIDS, emptySet()) ?: emptySet()
        return current.contains(guid)
    }
}

// =======================================================
// خوارزمية جلب التوقيت الحقيقي من الإنترنت (Global)
// =======================================================
object NetworkTime {
    var isInitialized = false
    private var networkTimeOffset: Long = 0L

    suspend fun syncTime() {
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://www.google.com")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.connect()
                
                val serverTime = connection.date 
                if (serverTime > 0) {
                    networkTimeOffset = serverTime - android.os.SystemClock.elapsedRealtime()
                    isInitialized = true
                }
            } catch (e: Exception) {
                Log.e("NetworkTime", "Failed to sync internet time")
            }
        }
    }

    fun currentTimeMillis(): Long {
        return if (isInitialized) {
            android.os.SystemClock.elapsedRealtime() + networkTimeOffset
        } else {
            System.currentTimeMillis() 
        }
    }
}
