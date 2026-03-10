package com.v2ray.ang.ui

import android.app.Activity
import android.content.Context
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
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// نظام العقاب العالمي (Kill Switch)
object UpdateManager {
    var isUpdatePending = false
}

class UpdatesFragment : Fragment() {

    private lateinit var layoutAdmin: LinearLayout
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var pb: ProgressBar
    private lateinit var etVersion: EditText

    private var pendingVersion = ""

    private val pickApk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) uploadApkToServer(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_updates, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        layoutAdmin = view.findViewById(R.id.layout_admin_upload)
        layoutProgress = view.findViewById(R.id.layout_progress)
        tvStatus = view.findViewById(R.id.tv_progress_status)
        tvPercent = view.findViewById(R.id.tv_progress_percent)
        pb = view.findViewById(R.id.pb_download_upload)
        etVersion = view.findViewById(R.id.et_version_code)
        val btnUpload = view.findViewById<MaterialButton>(R.id.btn_upload_apk)

        // إظهار لوحة الرفع للأدمن فقط
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

        // فحص التحديثات للمستخدم العادي بصمت
        checkAndDownloadUpdateSilently()
    }

    // ================== نظام الأدمن: رفع التحديث ==================
    private fun uploadApkToServer(uri: Uri) {
        layoutProgress.visibility = View.VISIBLE
        tvStatus.text = "جاري رفع التحديث للسيرفر..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireActivity().contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@launch
                inputStream.close()
                
                // محاكاة شريط الرفع
                for (i in 1..100 step 5) {
                    withContext(Dispatchers.Main) { pb.progress = i; tvPercent.text = "$i%" }
                    kotlinx.coroutines.delay(100)
                }

                val base64Apk = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/upload")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val payload = JSONObject().apply { put("version", pendingVersion.toInt()); put("apkData", base64Apk) }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                withContext(Dispatchers.Main) {
                    if (conn.responseCode == 200) {
                        tvStatus.text = "تم الحفظ في السيرفر بنجاح!"
                        pb.progress = 100; tvPercent.text = "100%"
                        Toast.makeText(requireContext(), "تم رفع التحديث للمستخدمين!", Toast.LENGTH_LONG).show()
                    } else { tvStatus.text = "فشل الرفع." }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvStatus.text = "خطأ أثناء الرفع." }
            }
        }
    }

    // ================== نظام المستخدم: التنزيل والتثبيت والعقاب ==================
    private fun checkAndDownloadUpdateSilently() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/check")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    val serverVersion = obj.getInt("version")
                    
                    // إذا كان إصدار السيرفر أكبر من إصدار التطبيق الحالي
                    if (serverVersion > BuildConfig.VERSION_CODE) {
                        UpdateManager.isUpdatePending = true // تفعيل نظام العقاب 1-ساعة
                        val apkBase64 = obj.getString("apkData")
                        
                        withContext(Dispatchers.Main) {
                            layoutProgress.visibility = View.VISIBLE
                            tvStatus.text = "يوجد تحديث إجباري، جاري التنزيل..."
                        }

                        // تحويل Base64 إلى ملف APK وتنزيله
                        val apkBytes = Base64.decode(apkBase64, Base64.NO_WRAP)
                        val updateFile = File(requireContext().getExternalFilesDir(null), "update.apk")
                        val fos = FileOutputStream(updateFile)
                        
                        // محاكاة تقدم التنزيل
                        val totalChunks = 20
                        val chunkSize = apkBytes.size / totalChunks
                        for (i in 0 until totalChunks) {
                            val start = i * chunkSize
                            val end = if (i == totalChunks - 1) apkBytes.size else (i + 1) * chunkSize
                            fos.write(apkBytes, start, end - start)
                            withContext(Dispatchers.Main) {
                                val progress = ((i + 1f) / totalChunks * 100).toInt()
                                pb.progress = progress
                                tvPercent.text = "$progress%"
                            }
                            kotlinx.coroutines.delay(50)
                        }
                        fos.flush(); fos.close()

                        withContext(Dispatchers.Main) {
                            tvStatus.text = "تم التنزيل، جاري التثبيت إجبارياً!"
                            forceInstallApk(updateFile)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun forceInstallApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.cache", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(requireContext(), "فشل بدء التثبيت", Toast.LENGTH_SHORT).show() }
    }
}
