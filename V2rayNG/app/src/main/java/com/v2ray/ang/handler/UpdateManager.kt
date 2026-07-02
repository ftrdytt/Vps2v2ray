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

// 🌟 المحرك الدكتاتوري للتحديثات الإجبارية 🌟
object UpdateManager { 
    
    // 🌟 الرابط الجديد الأساسي للـ VPS 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null
    
    private var updateDialog: AlertDialog? = null
    private var downloadLockDialog: AlertDialog? = null // نافذة سجن المستخدم أثناء التحميل
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
        if (AuthManager.getRole(activity) == "admin") return // الأدمن مستثنى من الإغلاق الإجباري لتسهيل الرفع
        
        // إذا التحديث جاهز مسبقاً، اقفله فوراً
        if (isUpdateReady && readyApkFile != null) {
            showMandatoryUpdateDialog(activity, readyApkFile!!)
            return
        }

        if (isChecking) return
        isChecking = true

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                delay(2000) // إعطاء مهلة صغيرة لكي لا يثقل التطبيق عند الفتح مباشرة
                val arch = getDeviceArchitecture()
                val url = URL("$BASE_API_URL/app/update/check?arch=$arch")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    val serverVersion = obj.optInt("version", 0)
                    val totalChunks = obj.optInt("totalChunks", 0)

                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        isUpdatePending = true
                        
                        // 🌟 إعدام الـ VPN فوراً بدون نقاش 🌟
                        withContext(Dispatchers.Main) {
                            try {
                                V2RayServiceManager.stopVService(activity)
                            } catch (e: Exception) {}
                        }

                        val updateFile = File(activity.cacheDir, "Ashor_Update_v$serverVersion.apk")

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

    // 🌟 نافذة السجن أثناء التحميل (لا يمكن إغلاقها نهائياً) 🌟
    private fun showDownloadingLockDialog(activity: Activity) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            downloadLockDialog?.dismiss()
            
            downloadLockDialog = AlertDialog.Builder(activity)
                .setTitle("تحديث أمني إجباري 🛑")
                .setMessage("تم إيقاف المحرك.\nجاري تنزيل التحديث الإجباري الآن، يرجى الانتظار وعدم إغلاق التطبيق...")
                .setCancelable(false) // 🌟 يمنع الهروب 🌟
                .create()
                
            downloadLockDialog?.show()
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

        // قفل الشاشة على المستخدم فور بدء التحميل
        withContext(Dispatchers.Main) { showDownloadingLockDialog(activity) }

        try {
            val fos = FileOutputStream(updateFile)
            for (i in 0 until totalChunks) {
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
            
            withContext(Dispatchers.Main) { 
                downloadLockDialog?.dismiss() // إخفاء نافذة السجن
                showMandatoryUpdateDialog(activity, updateFile) // إظهار نافذة التثبيت
            }

        } catch (e: Exception) {
            notificationManager.cancel(888)
            withContext(Dispatchers.Main) {
                downloadLockDialog?.dismiss()
                // إذا فشل التحميل، نرجع نظهر نافذة الخطأ اللي تمنعه من الدخول
                showMandatoryUpdateDialog(activity, updateFile, hasError = true)
            }
        }
    }

    fun showMandatoryUpdateDialog(activity: Activity, apkFile: File, hasError: Boolean = false) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            updateDialog?.dismiss()

            val msg = if (hasError) {
                "حدث خطأ أثناء تنزيل التحديث الإجباري بسبب ضعف الإنترنت.\nيجب إعادة التنزيل لكي تتمكن من استخدام التطبيق."
            } else {
                "تم تنزيل الإصدار الجديد بنجاح.\nلحماية حسابك وضمان عمل السيرفرات، يجب تثبيت هذا التحديث الآن لكي يفتح التطبيق."
            }

            val dialogBuilder = AlertDialog.Builder(activity)
                .setTitle("تحديث إجباري جاهز 🚀")
                .setMessage(msg)
                .setCancelable(false) // 🌟 يمنع الإغلاق نهائياً 🌟

            if (!hasError) {
                dialogBuilder.setPositiveButton("تثبيت التحديث الآن") { _, _ ->
                    forceInstallApk(activity, apkFile)
                    @Suppress("OPT_IN_USAGE")
                    GlobalScope.launch(Dispatchers.Main) {
                        delay(1500)
                        showMandatoryUpdateDialog(activity, apkFile) // إذا رجع للتطبيق تظهر النافذة فوراً
                    }
                }
            }

            dialogBuilder.setNegativeButton("إعادة التنزيل (حذف القديم)") { _, _ ->
                if (apkFile.exists()) apkFile.delete()
                isUpdateReady = false
                readyApkFile = null
                // 🌟 من يحذف، نرجع نقفل الشاشة بوجهه ونبدأ التحميل الإجباري من جديد 🌟
                startBackgroundUpdateCheck(activity)
            }

            updateDialog = dialogBuilder.create()
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
