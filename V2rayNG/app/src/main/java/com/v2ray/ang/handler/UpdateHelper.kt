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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    var isUpdatePending = false
    var isUpdateReady = false
    var readyApkFile: File? = null
    private var updateDialog: AlertDialog? = null

    fun startBackgroundUpdateCheck(activity: Activity) {
        if (AuthManager.getRole(activity) == "admin") return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                delay(2000)
                val arch = if (Build.SUPPORTED_ABIS[0].contains("arm64")) "arm64-v8a" else "armeabi-v7a"
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/check?arch=$arch")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    val serverVersion = obj.getInt("version")
                    val totalChunks = obj.optInt("totalChunks", 0)

                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        isUpdatePending = true
                        val updateFile = File(activity.cacheDir, "Ashor_Update_v$serverVersion.apk")
                        if (updateFile.exists() && updateFile.length() > 0) {
                            isUpdateReady = true; readyApkFile = updateFile; showMandatoryUpdateDialog(activity, updateFile)
                        } else { downloadUpdateWithNotification(activity, serverVersion, arch, totalChunks, updateFile) }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun downloadUpdateWithNotification(activity: Activity, serverVersion: Int, arch: String, totalChunks: Int, updateFile: File) {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "update_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(channelId, "تحديثات", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        }
        val builder = NotificationCompat.Builder(activity, channelId).setContentTitle("تحديث إجباري 🚀").setSmallIcon(android.R.drawable.ic_popup_sync).setOngoing(true)
        notificationManager.notify(999, builder.build())

        try {
            val fos = FileOutputStream(updateFile)
            for (i in 0 until totalChunks) {
                val conn = URL("https://vpn-license.rauter505.workers.dev/app/update/download_chunk?v=$serverVersion&arch=$arch&i=$i").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val base64Data = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getString("chunkData")
                    fos.write(Base64.decode(base64Data, Base64.NO_WRAP))
                    builder.setProgress(100, ((i + 1f) / totalChunks * 100).toInt(), false)
                    notificationManager.notify(999, builder.build())
                } else throw Exception("Download failed")
            }
            fos.close(); notificationManager.cancel(999)
            isUpdateReady = true; readyApkFile = updateFile; showMandatoryUpdateDialog(activity, updateFile)
        } catch (e: Exception) { notificationManager.cancel(999) }
    }

    fun showMandatoryUpdateDialog(activity: Activity, apkFile: File) {
        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            updateDialog?.dismiss()
            updateDialog = AlertDialog.Builder(activity).setTitle("تحديث إجباري 🚀")
                .setMessage("لا يمكنك الاستمرار حتى تقوم بتثبيت التحديث الجديد.").setCancelable(false)
                .setPositiveButton("تثبيت الآن") { _, _ -> forceInstallApk(activity, apkFile); GlobalScope.launch(Dispatchers.Main) { delay(1000); showMandatoryUpdateDialog(activity, apkFile) } }
                .setNegativeButton("إعادة التنزيل") { _, _ -> if (apkFile.exists()) apkFile.delete(); startBackgroundUpdateCheck(activity) }.create()
            updateDialog?.show()
        }
    }

    private fun forceInstallApk(activity: Activity, apkFile: File) {
        try {
            apkFile.setReadable(true, false)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cache", apkFile)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        } catch (e: Exception) { Toast.makeText(activity, "خطأ التثبيت", Toast.LENGTH_LONG).show() }
    }
}
