package com.v2ray.ang.handler

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
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
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// 🌟 المحرك الخارق للتحديثات الإجبارية 🌟
object UpdateManager { 
    
    // 🌟 الرابط الجديد الأساسي للـ VPS 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null
    private var updateDialog: AlertDialog? = null
    private var isChecking = false

    fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    // يتم استدعاء هذا الفحص في الخلفية عند تشغيل التطبيق أو فتح صفحة التحديثات
    fun startBackgroundUpdateCheck(activity: Activity) {
        if (AuthManager.getRole(activity) == "admin" || isChecking || isUpdateReady) return
        isChecking = true

        @Suppress("OPT_IN_USAGE")
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
                    val serverVersion = obj.optInt("version", 0)
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
                            downloadUpdateWithNotification(activity, serverVersion, arch, totalChunks, updateFile)
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

    private suspend fun downloadUpdateWithNotification(activity: Activity, serverVersion: Int, arch: String, totalChunks: Int, updateFile: File) {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ashor_update_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تحديثات النظام", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(activity, channelId)
            .setContentTitle("تحديث أمني إجباري 🚀")
            .setContentText("جاري تنزيل التحديث... 0%")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)

        notificationManager.notify(888, builder.build())

        try {
            val fos = FileOutputStream(updateFile)
            for (i in 0 until totalChunks) {
                // 🌟 استخدام الرابط الجديد لتنزيل أجزاء التحديث 🌟
                val chunkUrl = URL("$BASE_API_URL/app/update/download_chunk?v=$serverVersion&arch=$arch&i=$i")
                val chunkConn = chunkUrl.openConnection() as HttpURLConnection
                chunkConn.connectTimeout = 30000
                chunkConn.readTimeout = 60000

                if (chunkConn.responseCode == 200) {
                    val chunkResp = BufferedReader(InputStreamReader(chunkConn.inputStream)).readText()
                    val chunkObj = JSONObject(chunkResp)
                    val base64Data = chunkObj.getString("chunkData")
                    val chunkBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                    fos.write(chunkBytes)

                    val progress = ((i + 1f) / totalChunks * 100).toInt()
                    builder.setProgress(100, progress, false)
                    builder.setContentText("جاري التنزيل... $progress%")
                    notificationManager.notify(888, builder.build())
                } else {
                    fos.close()
                    throw Exception("فشل تنزيل الجزء $i")
                }
            }
            fos.flush(); fos.close()
            notificationManager.cancel(888)
            isUpdateReady = true
            readyApkFile = updateFile
            
            withContext(Dispatchers.Main) { showMandatoryUpdateDialog(activity, updateFile) }

        } catch (e: Exception) {
            builder.setContentText("فشل تنزيل التحديث، تأكد من الإنترنت.")
            builder.setProgress(0, 0, false)
            builder.setOngoing(false)
            notificationManager.notify(888, builder.build())
        }
    }

    fun showMandatoryUpdateDialog(activity: Activity, apkFile: File) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            updateDialog?.dismiss()

            updateDialog = AlertDialog.Builder(activity)
                .setTitle("تحديث إجباري جاهز 🚀")
                .setMessage("تم تنزيل الإصدار الجديد بنجاح.\nلحماية حسابك وضمان عمل السيرفرات، يجب تثبيت هذا التحديث الآن لكي يفتح التطبيق.")
                .setCancelable(false) // 🌟 يمنع الإغلاق نهائياً 🌟
                .setPositiveButton("تثبيت التحديث الآن") { _, _ ->
                    forceInstallApk(activity, apkFile)
                    @Suppress("OPT_IN_USAGE")
                    GlobalScope.launch(Dispatchers.Main) {
                        delay(1500)
                        showMandatoryUpdateDialog(activity, apkFile) // إذا رجع للتطبيق تظهر النافذة فوراً
                    }
                }
                .setNegativeButton("حذف التنزيل والمحاولة مجدداً") { _, _ ->
                    if (apkFile.exists()) apkFile.delete()
                    isUpdateReady = false
                    readyApkFile = null
                    startBackgroundUpdateCheck(activity)
                }
                .create()

            updateDialog?.show()
        }
    }

    private fun forceInstallApk(activity: Activity, apkFile: File) {
        try {
            apkFile.setReadable(true, false)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cache", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) { 
            Toast.makeText(activity, "خطأ التثبيت: ${e.message}", Toast.LENGTH_LONG).show() 
        }
    }
}
