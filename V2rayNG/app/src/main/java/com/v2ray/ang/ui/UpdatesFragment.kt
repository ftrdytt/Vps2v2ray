package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
import kotlin.math.ceil

object UpdateManager {
    var isUpdatePending = false
}

class UpdatesFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutAdmin: LinearLayout
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvCurrentVersion: TextView
    private lateinit var pb: ProgressBar
    private lateinit var etVersion: EditText
    private lateinit var btnUpload: MaterialButton

    private var pendingVersion = ""

    private val pickApk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) uploadApkToServerChunked(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_updates, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        layoutAdmin = view.findViewById(R.id.layout_admin_upload)
        layoutProgress = view.findViewById(R.id.layout_progress)
        tvStatus = view.findViewById(R.id.tv_progress_status)
        tvPercent = view.findViewById(R.id.tv_progress_percent)
        tvCurrentVersion = view.findViewById(R.id.tv_current_version)
        pb = view.findViewById(R.id.pb_download_upload)
        etVersion = view.findViewById(R.id.et_version_code)
        btnUpload = view.findViewById(R.id.btn_upload_apk)

        tvCurrentVersion.text = "إصدار التطبيق الحالي: ${BuildConfig.VERSION_CODE}"

        // إظهار واجهة الأدمن
        if (AuthManager.getRole(requireContext()) == "admin") {
            layoutAdmin.visibility = View.VISIBLE
        }

        btnUpload.setOnClickListener {
            pendingVersion = etVersion.text.toString().trim()
            if (pendingVersion.isEmpty()) {
                Toast.makeText(requireContext(), "أدخل رقم الإصدار أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/vnd.android.package-archive" }
            pickApk.launch(intent)
        }

        // تفعيل ميزة السحب للتحديث
        swipeRefresh.setOnRefreshListener {
            manualCheckForUpdates()
        }
        
        // فحص أولي صامت عند فتح الصفحة
        manualCheckForUpdates(true)
    }

    // ================== نظام الفحص اليدوي (للمستخدم) ==================
    private fun manualCheckForUpdates(isSilent: Boolean = false) {
        if (!isSilent) swipeRefresh.isRefreshing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/check")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    val serverVersion = obj.getInt("version")
                    val totalChunks = obj.optInt("totalChunks", 0)
                    
                    withContext(Dispatchers.Main) { swipeRefresh.isRefreshing = false }

                    // إذا كان السيرفر يحمل إصدار أعلى من إصدار التطبيق الحالي
                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        UpdateManager.isUpdatePending = true 
                        downloadUpdateInForeground(serverVersion, totalChunks)
                    } else {
                        if (!isSilent) {
                            withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "أنت تمتلك أحدث إصدار بالفعل!", Toast.LENGTH_SHORT).show() }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { swipeRefresh.isRefreshing = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    if (!isSilent) Toast.makeText(requireContext(), "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun downloadUpdateInForeground(serverVersion: Int, totalChunks: Int) {
        withContext(Dispatchers.Main) {
            layoutProgress.visibility = View.VISIBLE
            tvStatus.text = "تحديث متاح! جاري التنزيل الإجباري..."
            pb.progress = 0
            tvPercent.text = "0%"
        }

        try {
            // התعديل 1: التنزيل في مسار cacheDir الآمن
            val updateFile = File(requireContext().cacheDir, "Ashor_Update_v$serverVersion.apk")
            val fos = FileOutputStream(updateFile)
            
            for (i in 0 until totalChunks) {
                val chunkUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/download_chunk?v=$serverVersion&i=$i")
                val chunkConn = chunkUrl.openConnection() as HttpURLConnection
                chunkConn.connectTimeout = 30000
                chunkConn.readTimeout = 60000
                
                if (chunkConn.responseCode == 200) {
                    val chunkResp = BufferedReader(InputStreamReader(chunkConn.inputStream)).readText()
                    val chunkObj = JSONObject(chunkResp)
                    val base64Data = chunkObj.getString("chunkData")
                    val chunkBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                    fos.write(chunkBytes)
                    
                    withContext(Dispatchers.Main) {
                        val progress = ((i + 1f) / totalChunks * 100).toInt()
                        pb.progress = progress
                        tvPercent.text = "$progress%"
                    }
                } else {
                    fos.close()
                    throw Exception("Download chunk failed")
                }
            }
            fos.flush(); fos.close()

            withContext(Dispatchers.Main) {
                tvStatus.text = "تم التنزيل بنجاح! جاري التثبيت..."
                forceInstallApk(updateFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "حدث خطأ أثناء تنزيل التحديث."
            }
        }
    }

    // ================== نظام الأدمن: الرفع المجزأ ==================
    private fun uploadApkToServerChunked(uri: Uri) {
        layoutProgress.visibility = View.VISIBLE
        btnUpload.isEnabled = false
        tvStatus.text = "جاري تهيئة الملف وتقطيعه..."
        pb.progress = 0
        tvPercent.text = "0%"
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireActivity().contentResolver.openInputStream(uri)
                if (inputStream == null) throw Exception("Cannot open file")
                val fileBytes = inputStream.readBytes()
                inputStream.close()

                val chunkSize = 3 * 1024 * 1024
                val totalChunks = ceil(fileBytes.size.toDouble() / chunkSize).toInt()

                val initUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/upload_init")
                val initConn = initUrl.openConnection() as HttpURLConnection
                initConn.requestMethod = "POST"
                initConn.setRequestProperty("Content-Type", "application/json")
                initConn.connectTimeout = 15000
                initConn.doOutput = true
                val initPayload = JSONObject().apply { put("version", pendingVersion.toInt()); put("totalChunks", totalChunks) }
                initConn.outputStream.use { it.write(initPayload.toString().toByteArray()) }
                
                if (initConn.responseCode != 200) throw Exception("Failed to initialize upload")

                for (i in 0 until totalChunks) {
                    withContext(Dispatchers.Main) { tvStatus.text = "جاري رفع الجزء ${i + 1} من $totalChunks للسيرفر..." }
                    
                    val start = i * chunkSize
                    val end = if (i == totalChunks - 1) fileBytes.size else (i + 1) * chunkSize
                    val chunkBytes = fileBytes.copyOfRange(start, end)
                    val base64Chunk = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                    var success = false
                    var retries = 3
                    
                    while (!success && retries > 0) {
                        try {
                            val chunkUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/upload_chunk")
                            val chunkConn = chunkUrl.openConnection() as HttpURLConnection
                            chunkConn.requestMethod = "POST"
                            chunkConn.setRequestProperty("Content-Type", "application/json")
                            chunkConn.connectTimeout = 30000 
                            chunkConn.readTimeout = 60000 
                            chunkConn.doOutput = true
                            
                            val chunkPayload = JSONObject().apply { 
                                put("version", pendingVersion.toInt())
                                put("chunkIndex", i)
                                put("chunkData", base64Chunk) 
                            }
                            chunkConn.outputStream.use { it.write(chunkPayload.toString().toByteArray()) }
                            if (chunkConn.responseCode == 200) success = true
                        } catch (e: Exception) { retries--; delay(2000) }
                    }
                    if (!success) throw Exception("Failed to upload chunk $i")

                    withContext(Dispatchers.Main) {
                        val progress = ((i + 1f) / totalChunks * 100).toInt()
                        pb.progress = progress
                        tvPercent.text = "$progress%"
                    }
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "✅ تم حفظ التحديث في السيرفر بنجاح!"
                    pb.progress = 100; tvPercent.text = "100%"
                    btnUpload.isEnabled = true
                    Toast.makeText(requireContext(), "تم إصدار التحديث للمستخدمين!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    tvStatus.text = "❌ خطأ أثناء الرفع: ${e.message}"
                    btnUpload.isEnabled = true 
                }
            }
        }
    }

    // התعديل 2: تحديث دالة التثبيت لتطبع سبب الخطأ وتعطي صلاحيات كاملة
    private fun forceInstallApk(apkFile: File) {
        requireActivity().runOnUiThread {
            try {
                apkFile.setReadable(true, false) // السماح للنظام بقراءة الملف
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.cache", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) { 
                Toast.makeText(requireContext(), "خطأ التثبيت: ${e.message}", Toast.LENGTH_LONG).show() 
            }
        }
    }
}
