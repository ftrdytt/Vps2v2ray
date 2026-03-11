package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
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
import android.util.Base64
import android.os.Build

class LoginActivity : AppCompatActivity() {
    
    private var updateDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // بدء فحص التحديثات التلقائي حتى وهو مسجل خروج
        startBackgroundUpdateCheck()

        val btnQuickLogin = findViewById<MaterialButton>(R.id.btn_quick_login)
        val btnCreateRandom = findViewById<MaterialButton>(R.id.btn_create_random)
        val etId = findViewById<EditText>(R.id.et_login_id)
        val etPass = findViewById<EditText>(R.id.et_login_pass)
        val btnManualLogin = findViewById<MaterialButton>(R.id.btn_manual_login)

        // فحص وجود حساب قديم مسجل خروج
        val savedId = AuthManager.getSavedId(this)
        val savedName = AuthManager.getSavedName(this)
        val savedPass = AuthManager.getSavedPass(this)

        if (!savedId.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
            btnQuickLogin.visibility = View.VISIBLE
            btnQuickLogin.text = "متابعة كـ ($savedName)"
            btnQuickLogin.setOnClickListener {
                loginProcess(savedId, savedPass, btnQuickLogin)
            }
        }

        btnCreateRandom.setOnClickListener {
            btnCreateRandom.isEnabled = false
            btnCreateRandom.text = "جاري إنشاء الحساب..."
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = URL("https://vpn-license.rauter505.workers.dev/auth/init")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(this@LoginActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@LoginActivity, "تم إنشاء الحساب بنجاح!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "فشل الاتصال بالإنترنت", Toast.LENGTH_SHORT).show()
                        btnCreateRandom.isEnabled = true
                        btnCreateRandom.text = "إنشاء حساب عشوائي جديد بضغطة"
                    }
                }
            }
        }

        btnManualLogin.setOnClickListener {
            val id = etId.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (id.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال الايدي والباسورد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loginProcess(id, pass, btnManualLogin)
        }
    }

    private fun loginProcess(id: String, pass: String, button: MaterialButton) {
        val originalText = button.text.toString()
        button.isEnabled = false
        button.text = "جاري التحقق..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply { put("id", id); put("password", pass) }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        AuthManager.saveUser(this@LoginActivity, id, obj.getString("name"), pass, obj.getString("role"), obj.optString("pfp", ""))
                        withContext(Dispatchers.Main) {
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LoginActivity, obj.optString("message", "خطأ في تسجيل الدخول"), Toast.LENGTH_SHORT).show()
                            button.isEnabled = true; button.text = originalText
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                        button.isEnabled = true; button.text = originalText
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "تأكد من اتصالك بالإنترنت", Toast.LENGTH_SHORT).show()
                    button.isEnabled = true; button.text = originalText
                }
            }
        }
    }

    // ====================================================================
    // ================== نظام التحديث الإجباري الخفي =====================
    // ====================================================================

    private fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    private fun startBackgroundUpdateCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
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
                        UpdateManager.isUpdatePending = true 
                        val updateFile = File(cacheDir, "Ashor_Update_v$serverVersion.apk")
                        
                        if (updateFile.exists() && updateFile.length() > 0) {
                            UpdateManager.isUpdateReady = true
                            UpdateManager.readyApkFile = updateFile
                            showMandatoryUpdateDialog(updateFile)
                        } else {
                            downloadUpdateWithNotification(serverVersion, arch, totalChunks, updateFile)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun downloadUpdateWithNotification(serverVersion: Int, arch: String, totalChunks: Int, updateFile: File) {
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
                } else { fos.close(); throw Exception("Download failed") }
            }
            fos.flush(); fos.close()
            UpdateManager.isUpdateReady = true
            UpdateManager.readyApkFile = updateFile
            showMandatoryUpdateDialog(updateFile)
        } catch (e: Exception) { }
    }

    private fun showDownloadingDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateDialog?.dismiss()
            updateDialog = AlertDialog.Builder(this@LoginActivity)
                .setTitle("تحديث إجباري 🚀")
                .setMessage("جاري إعادة تنزيل التحديث...\nيرجى الانتظار لمعرفة نسبة التحميل في شريط الإشعارات.")
                .setCancelable(false)
                .create()
            updateDialog?.show()
        }
    }

    private fun showMandatoryUpdateDialog(apkFile: File) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateDialog?.dismiss() 
            updateDialog = AlertDialog.Builder(this@LoginActivity)
                .setTitle("تحديث إجباري 🚀")
                .setMessage("تم تنزيل الإصدار الجديد بنجاح.\nلا يمكنك تسجيل الدخول حتى تقوم بتثبيت هذا التحديث.")
                .setCancelable(false)
                .setPositiveButton("تثبيت التحديث الآن") { _, _ ->
                    forceInstallApk(apkFile)
                    lifecycleScope.launch {
                        delay(1000)
                        showMandatoryUpdateDialog(apkFile)
                    }
                }
                .setNegativeButton("إعادة التنزيل (في حال الخطأ)") { _, _ ->
                    if (apkFile.exists()) apkFile.delete()
                    UpdateManager.isUpdateReady = false
                    UpdateManager.readyApkFile = null
                    showDownloadingDialog()
                    startBackgroundUpdateCheck()
                }
                .create()
            updateDialog?.show()
        }
    }

    private fun forceInstallApk(apkFile: File) {
        runOnUiThread {
            try {
                apkFile.setReadable(true, false) 
                val uri = FileProvider.getUriForFile(this@LoginActivity, "${packageName}.cache", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) { Toast.makeText(this@LoginActivity, "خطأ التثبيت: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
