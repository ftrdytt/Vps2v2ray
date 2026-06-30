package com.v2ray.ang.handler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    
    // 🌟 الرابط الجديد الأساسي للـ VPS 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null

    private var isChecking = false

    private fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    // يتم استدعاء هذا الفحص في الخلفية عند تشغيل التطبيق أو فتح صفحة التحديثات
    @Suppress("OPT_IN_USAGE")
    fun startBackgroundUpdateCheck(activity: Activity) {
        if (isChecking || isUpdateReady) return
        isChecking = true

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // إعطاء مهلة صغيرة لكي لا يثقل التطبيق عند الفتح مباشرة
                delay(3000)

                val arch = getDeviceArchitecture()
                // 🌟 الاتصال بسيرفر VPS للتحقق من وجود تحديث 🌟
                val url = URL("$BASE_API_URL/app/update/check?arch=$arch")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    
                    val serverVersion = obj.getInt("version")
                    val totalChunks = obj.optInt("totalChunks", 0)

                    // التحقق: هل النسخة في السيرفر أحدث من الموجودة في الهاتف؟
                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        isUpdatePending = true
                        
                        val updateFile = File(activity.cacheDir, "Ashor_Update_v$serverVersion.apk")
                        
                        // إذا الملف موجود مسبقاً، نطلب تثبيته فوراً
                        if (updateFile.exists() && updateFile.length() > 0) {
                            isUpdateReady = true
                            readyApkFile = updateFile
                            withContext(Dispatchers.Main) { showMandatoryUpdateDialog(activity, updateFile) }
                        } else {
                            // تنبيه المستخدم بوجود تحديث وإرساله لصفحة التحديثات
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, "تحديث جديد متوفر! يرجى التوجه لمركز التحديثات للتنزيل.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        isUpdatePending = false
                    }
                }
            } catch (e: Exception) {
                // فشل الاتصال، صمت تام لتجنب إزعاج المستخدم
            } finally {
                isChecking = false
            }
        }
    }

    fun showMandatoryUpdateDialog(activity: Activity, apkFile: File) {
        if (activity.isFinishing || activity.isDestroyed) return
        
        AlertDialog.Builder(activity)
            .setTitle("تحديث إجباري جاهز! 🚀")
            .setMessage("تم تنزيل التحديث الأمني الجديد بنجاح. يرجى تثبيته الآن للمتابعة.\n(لن تتمكن من تشغيل المحرك بدون التحديث)")
            .setCancelable(false)
            .setPositiveButton("تثبيت الآن") { _, _ ->
                forceInstallApk(activity, apkFile)
            }
            .show()
    }

    fun forceInstallApk(context: Context, apkFile: File) {
        try {
            apkFile.setReadable(true, false)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply { 
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) 
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "فشل بدء التثبيت: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
