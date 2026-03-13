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

object UpdateManagerHelper {
    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null
    private var updateDialog: AlertDialog? = null

    private fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    fun startBackgroundUpdateCheck(activity: Activity) {
        if (AuthManager.getRole(activity) == "admin") return

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                delay(2000)
                val arch = getDeviceArchitecture()
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/check?arch=$arch")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    val serverVersion = obj.getInt("version")
                    val totalChunks = obj.optInt("totalChunks", 0)

                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        isUpdatePending = true
                        val updateFile = File(activity.cacheDir, "Ashor_Update_v$serverVersion.apk")

                        if (updateFile.exists() && updateFile.length() > 0) {
                            isUpdateReady = true
                            readyApkFile = updateFile
                            showMandatoryUpdateDialog(activity, updateFile)
                        } else {
                            downloadUpdateWithNotification(activity, serverVersion, arch, totalChunks, updateFile)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun downloadUpdateWithNotification(activity: Activity, serverVersion: Int, arch: String, totalChunks: Int, updateFile: File) {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "update_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تحديثات التطبيق", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(activity, channelId)
            .setContentTitle("تحديث جديد إجباري 🚀")
            .setContentText("جاري تنزيل التحديث... 0%")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)

        notificationManager.notify(999, builder.build())

        try {
            val fos = FileOutputStream(updateFile)
            for (i in 0 until totalChunks) {
                val chunkUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/download_chunk?v=$serverVersion&arch=$arch&i=$i")
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
                    builder.setContentText("جاري تنزيل التحديث... $progress%")
                    notificationManager.notify(999, builder.build())
                } else {
                    fos.close()
                    throw Exception("Download failed")
                }
            }
            fos.flush(); fos.close()
            notificationManager.cancel(999)
            isUpdateReady = true
            readyApkFile = updateFile
            showMandatoryUpdateDialog(activity, updateFile)

        } catch (e: Exception) {
            builder.setContentText("فشل تنزيل التحديث، يرجى المحاولة لاحقاً.")
            builder.setProgress(0, 0, false)
            builder.setOngoing(false)
            notificationManager.notify(999, builder.build())
        }
    }

    fun showMandatoryUpdateDialog(activity: Activity, apkFile: File) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            updateDialog?.dismiss()

            updateDialog = AlertDialog.Builder(activity)
                .setTitle("تحديث إجباري 🚀")
                .setMessage("تم تنزيل الإصدار الجديد بنجاح.\nلا يمكنك الاستمرار في استخدام التطبيق حتى تقوم بتثبيت هذا التحديث.")
                .setCancelable(false)
                .setPositiveButton("تثبيت التحديث الآن") { _, _ ->
                    forceInstallApk(activity, apkFile)
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                        delay(1000)
                        showMandatoryUpdateDialog(activity, apkFile)
                    }
                }
                .setNegativeButton("إعادة التنزيل (في حال الخطأ)") { _, _ ->
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
        } catch (e: Exception) { Toast.makeText(activity, "خطأ التثبيت: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
