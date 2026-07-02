package com.v2ray.ang.handler

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings // 🌟 ضروري لجلب آيدي الجهاز 🌟
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.service.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// 🌟 المحرك الدكتاتوري للتحديثات الإجبارية (النسخة الذكية) 🌟
object UpdateManager { 
    
    // 🌟 الرابط الجديد الأساسي للـ VPS 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null
    
    private var updateDialog: AlertDialog? = null
    private var downloadLockDialog: AlertDialog? = null // نافذة سجن المستخدم أثناء التحميل
    private var isChecking = false
    private var watchdogJob: Job? = null // وظيفة الرادار المستمر

    fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    // 🌟 الدالة الجديدة: إرسال بيانات المستخدم للسيرفر بعد إكمال التحميل 🌟
    fun logUpdateToServer(context: Context, version: Int, arch: String) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(context)
                val name = AuthManager.getName(context)
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                
                val payload = JSONObject().apply {
                    put("userId", userId)
                    put("deviceId", deviceId)
                    put("name", if (userId.isNotEmpty()) name else "مجهول")
                    put("version", version)
                    put("arch", arch)
                }
                
                val conn = URL("$BASE_API_URL/app/log_update").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    // 🌟 الرادار المستمر (الذكي - مع المهلة) 🌟
    fun startSilentWatchdog(context: Context) {
        if (watchdogJob?.isActive == true) return
        
        @Suppress("OPT_IN_USAGE")
        watchdogJob = GlobalScope.launch(Dispatchers.IO) {
            var isFirstCheckPassed = false

            while (isActive) {
                try {
                    val arch = getDeviceArchitecture()
                    val url = URL("$BASE_API_URL/app/update/check?arch=$arch")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        val serverVersion = obj.optInt("version", 0)
                        val totalChunks = obj.optInt("totalChunks", 0)
                        
                        // 🌟 قراءة الإعدادات من الأدمن 🌟
                        val watchdogEnabled = obj.optBoolean("watchdogEnabled", true)
                        val gracePeriodMins = obj.optInt("gracePeriodMins", 5)
                        
                        // إذا الأدمن طفى الرادار، نام لمدة ساعة كاملة لتخفيف الضغط على السيرفر
                        if (!watchdogEnabled) {
                            delay(60 * 60 * 1000L) 
                            continue
                        }

                        // 🛑 إذا اكو تحديث 🛑
                        if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                            isUpdatePending = true
                            
                            // ⏱️ تطبيق مهلة السماح قبل الإعدام (يستفاد من الـ VPN للتحميل) ⏱️
                            if (gracePeriodMins > 0) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "⚠️ تحديث إجباري متاح!\nالـ VPN سيفصل تلقائياً بعد $gracePeriodMins دقائق. يرجى التحديث الآن!", Toast.LENGTH_LONG).show()
                                }
                                // انتظر المهلة اللي حددها الأدمن
                                delay(gracePeriodMins * 60 * 1000L)
                            }

                            // 💀 انتهت المهلة! إعدام المحرك فوراً 💀
                            withContext(Dispatchers.Main) {
                                try { V2RayServiceManager.stopVService(context) } catch (e: Exception) {}
                                Toast.makeText(context, "انتهت المهلة! تم إيقاف التطبيق لإجبارك على التحديث.", Toast.LENGTH_LONG).show()
                            }

                            val updateFile = File(context.cacheDir, "Ashor_Update_v$serverVersion.apk")

                            // إذا فاتح التطبيق نطلعله شاشة التحميل، إذا بالخلفية ندزله إشعار مرعب
                            if (context is Activity) {
                                if (updateFile.exists() && updateFile.length() > 0) {
                                    isUpdateReady = true; readyApkFile = updateFile
                                    withContext(Dispatchers.Main) { showMandatoryUpdateDialog(context, updateFile) }
                                } else {
                                    downloadUpdateWithNotification(context, serverVersion, arch, totalChunks, updateFile)
                                }
                            } else {
                                sendForceUpdateNotification(context)
                            }
                            break // نقتل الرادار لأن لزمنا التحديث وانتهى الموضوع
                        }
                        
                        isFirstCheckPassed = true
                        delay(5 * 60 * 1000L) // في حالة عدم وجود تحديث، افحص كل 5 دقائق
                        
                    } else { delay(10000) }
                } catch (e: Exception) {
                    if (!isFirstCheckPassed) delay(5000) else delay(10000)
                }
            }
        }
    }

    // إشعار لا يمكن إغلاقه لفتح التطبيق من البردة
    private fun sendForceUpdateNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "strict_update_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تحديثات إجبارية", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        try {
            val intent = Intent(context, Class.forName("com.v2ray.ang.ui.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(context, channelId)
                .setContentTitle("⚠️ تحديث أمني إجباري")
                .setContentText("انتهت مهلة السماح وتم إيقاف المحرك. اضغط هنا للتحديث فوراً.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true) // لا يمكن مسحه
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)

            notificationManager.notify(777, builder.build())
        } catch (e: Exception) {}
    }

    // يتم استدعاء هذا الفحص في الخلفية عند تشغيل التطبيق أو فتح صفحة التحديثات
    fun startBackgroundUpdateCheck(activity: Activity) {
        if (AuthManager.getRole(activity) == "admin") return 
        
        // نشغل كلب الحراسة بالخلفية
        startSilentWatchdog(activity)

        if (isUpdateReady && readyApkFile != null) {
            showMandatoryUpdateDialog(activity, readyApkFile!!)
            return
        }

        if (isChecking) return
        isChecking = true

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                delay(2000)
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
                    val watchdogEnabled = obj.optBoolean("watchdogEnabled", true)

                    if (watchdogEnabled && serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        isUpdatePending = true
                        val updateFile = File(activity.cacheDir, "Ashor_Update_v$serverVersion.apk")

                        if (updateFile.exists() && updateFile.length() > 0) {
                            isUpdateReady = true
                            readyApkFile = updateFile
                            withContext(Dispatchers.Main) { showMandatoryUpdateDialog(activity, updateFile) }
                        } else {
                            // إذا فتح واجهة التطبيق يبدأ التحميل حتى يستفاد من الـ VPN المفتوح قبل الإعدام
                            downloadUpdateWithNotification(activity, serverVersion, arch, totalChunks, updateFile)
                        }
                    } else {
                        isUpdatePending = false
                    }
                }
            } catch (e: Exception) {
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
                .setMessage("جاري تنزيل التحديث الإجباري الآن.\nيرجى الانتظار وعدم إغلاق التطبيق حتى يتم التثبيت بنجاح.")
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
            
            // 🌟 إرسال السجل للسيرفر بعد نجاح التحميل مباشرة 🌟
            logUpdateToServer(activity, serverVersion, arch)
            
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
                
                // 🌟 من يحذف، نلغي الرادار القديم ونشغل واحد جديد يحاصره فوراً 🌟
                watchdogJob?.cancel()
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
