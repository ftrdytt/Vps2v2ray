package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

class UpdatesFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutAdmin: LinearLayout
    private lateinit var layoutAdminHistory: LinearLayout
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
        layoutAdminHistory = view.findViewById(R.id.layout_admin_history)
        layoutProgress = view.findViewById(R.id.layout_progress)
        tvStatus = view.findViewById(R.id.tv_progress_status)
        tvPercent = view.findViewById(R.id.tv_progress_percent)
        tvCurrentVersion = view.findViewById(R.id.tv_current_version)
        pb = view.findViewById(R.id.pb_download_upload)
        etVersion = view.findViewById(R.id.et_version_code)
        btnUpload = view.findViewById(R.id.btn_upload_apk)

        tvCurrentVersion.text = "إصدار التطبيق الحالي: ${BuildConfig.VERSION_CODE}"

        val isAdmin = AuthManager.getRole(requireContext()) == "admin"
        if (isAdmin) {
            layoutAdmin.visibility = View.VISIBLE
            loadAdminUpdateHistory() // جلب السجل للأدمن
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

        swipeRefresh.setOnRefreshListener {
            if (isAdmin) loadAdminUpdateHistory()
            manualCheckForUpdates()
        }
        
        manualCheckForUpdates(true)
    }

    // ================== نظام الفحص للمستخدم ==================
    private fun manualCheckForUpdates(isSilent: Boolean = false) {
        if (!isSilent) swipeRefresh.isRefreshing = true

        // منع التحديث الإجباري للأدمن
        if (AuthManager.getRole(requireContext()) == "admin" && isSilent) {
            swipeRefresh.isRefreshing = false
            return
        }

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

                    if (serverVersion > BuildConfig.VERSION_CODE && totalChunks > 0) {
                        UpdateManager.isUpdatePending = true 
                        
                        val updateFile = File(requireContext().cacheDir, "Ashor_Update_v$serverVersion.apk")
                        if (updateFile.exists() && updateFile.length() > 0) {
                            UpdateManager.isUpdateReady = true
                            UpdateManager.readyApkFile = updateFile
                            forceInstallApk(updateFile)
                        } else {
                            downloadUpdateInForeground(serverVersion, totalChunks, updateFile)
                        }
                    } else {
                        if (!isSilent) {
                            withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "أنت تمتلك أحدث إصدار أو لا توجد تحديثات نشطة!", Toast.LENGTH_SHORT).show() }
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

    private suspend fun downloadUpdateInForeground(serverVersion: Int, totalChunks: Int, updateFile: File) {
        withContext(Dispatchers.Main) {
            layoutProgress.visibility = View.VISIBLE
            tvStatus.text = "تحديث متاح! جاري التنزيل..."
            pb.progress = 0
            tvPercent.text = "0%"
        }

        try {
            val fos = FileOutputStream(updateFile)
            for (i in 0 until totalChunks) {
                val chunkUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/download_chunk?v=$serverVersion&i=$i")
                val chunkConn = chunkUrl.openConnection() as HttpURLConnection
                chunkConn.connectTimeout = 30000; chunkConn.readTimeout = 60000
                
                if (chunkConn.responseCode == 200) {
                    val chunkResp = BufferedReader(InputStreamReader(chunkConn.inputStream)).readText()
                    val base64Data = JSONObject(chunkResp).getString("chunkData")
                    fos.write(Base64.decode(base64Data, Base64.NO_WRAP))
                    
                    withContext(Dispatchers.Main) {
                        val progress = ((i + 1f) / totalChunks * 100).toInt()
                        pb.progress = progress; tvPercent.text = "$progress%"
                    }
                } else { fos.close(); throw Exception("Download chunk failed") }
            }
            fos.flush(); fos.close()

            UpdateManager.isUpdateReady = true
            UpdateManager.readyApkFile = updateFile

            withContext(Dispatchers.Main) {
                tvStatus.text = "تم التنزيل بنجاح! جاري التثبيت..."
                forceInstallApk(updateFile)
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "حدث خطأ أثناء التنزيل." } }
    }

    // ================== نظام الأدمن: جلب السجل وإدارته ==================
    private fun loadAdminUpdateHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/list")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val array = JSONArray(resp)
                    withContext(Dispatchers.Main) {
                        layoutAdminHistory.removeAllViews()
                        for (i in array.length() - 1 downTo 0) { // الأحدث أولاً
                            val item = array.getJSONObject(i)
                            val v = item.getInt("version")
                            val isActive = item.getBoolean("active")
                            val date = item.optString("date", "غير محدد")
                            addHistoryCard(v, date, isActive)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun addHistoryCard(version: Int, date: String, isActive: Boolean) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#252529"))
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        val tvInfo = TextView(requireContext()).apply {
            text = "إصدار: $version\nالتاريخ: $date\nالحالة: ${if(isActive) "🟢 نشط (يظهر للمستخدمين)" else "🔴 متوقف"}"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }
        card.addView(tvInfo)

        val btnLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        
        val btnToggle = MaterialButton(requireContext()).apply {
            text = if(isActive) "إيقاف" else "تشغيل"
            setBackgroundColor(if(isActive) Color.parseColor("#FF9800") else Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 20, 0) }
            setOnClickListener { toggleUpdateStatus(version) }
        }
        val btnDelete = MaterialButton(requireContext()).apply {
            text = "حذف نهائي"
            setBackgroundColor(Color.parseColor("#F44336"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { deleteUpdate(version) }
        }

        btnLayout.addView(btnToggle)
        btnLayout.addView(btnDelete)
        card.addView(btnLayout)
        
        layoutAdminHistory.addView(card)
    }

    private fun toggleUpdateStatus(version: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("https://vpn-license.rauter505.workers.dev/app/update/toggle").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("version", version).toString().toByteArray()) }
                if (conn.responseCode == 200) loadAdminUpdateHistory()
            } catch (e: Exception) {}
        }
    }

    private fun deleteUpdate(version: Int) {
        AlertDialog.Builder(requireContext()).setTitle("تأكيد الحذف").setMessage("هل أنت متأكد من حذف تحديث $version نهائياً؟").setPositiveButton("حذف") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://vpn-license.rauter505.workers.dev/app/update/delete").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                    conn.outputStream.use { it.write(JSONObject().put("version", version).toString().toByteArray()) }
                    if (conn.responseCode == 200) loadAdminUpdateHistory()
                } catch (e: Exception) {}
            }
        }.setNegativeButton("إلغاء", null).show()
    }

    // ================== نظام الأدمن: الرفع المجزأ ==================
    private fun uploadApkToServerChunked(uri: Uri) {
        layoutProgress.visibility = View.VISIBLE; btnUpload.isEnabled = false
        tvStatus.text = "جاري تهيئة الملف وتقطيعه..."; pb.progress = 0; tvPercent.text = "0%"
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireActivity().contentResolver.openInputStream(uri) ?: throw Exception("Cannot open")
                val fileBytes = inputStream.readBytes(); inputStream.close()
                val chunkSize = 3 * 1024 * 1024; val totalChunks = ceil(fileBytes.size.toDouble() / chunkSize).toInt()

                val initConn = URL("https://vpn-license.rauter505.workers.dev/app/update/upload_init").openConnection() as HttpURLConnection
                initConn.requestMethod = "POST"; initConn.setRequestProperty("Content-Type", "application/json"); initConn.doOutput = true
                initConn.outputStream.use { it.write(JSONObject().put("version", pendingVersion.toInt()).put("totalChunks", totalChunks).toString().toByteArray()) }
                if (initConn.responseCode != 200) throw Exception("Failed init")

                for (i in 0 until totalChunks) {
                    withContext(Dispatchers.Main) { tvStatus.text = "جاري رفع الجزء ${i + 1} من $totalChunks..." }
                    val start = i * chunkSize; val end = if (i == totalChunks - 1) fileBytes.size else (i + 1) * chunkSize
                    val base64Chunk = Base64.encodeToString(fileBytes.copyOfRange(start, end), Base64.NO_WRAP)
                    var success = false; var retries = 3
                    
                    while (!success && retries > 0) {
                        try {
                            val chunkConn = URL("https://vpn-license.rauter505.workers.dev/app/update/upload_chunk").openConnection() as HttpURLConnection
                            chunkConn.requestMethod = "POST"; chunkConn.setRequestProperty("Content-Type", "application/json"); chunkConn.doOutput = true
                            chunkConn.outputStream.use { it.write(JSONObject().put("version", pendingVersion.toInt()).put("chunkIndex", i).put("chunkData", base64Chunk).toString().toByteArray()) }
                            if (chunkConn.responseCode == 200) success = true
                        } catch (e: Exception) { retries--; delay(2000) }
                    }
                    if (!success) throw Exception("Failed chunk $i")
                    withContext(Dispatchers.Main) { pb.progress = ((i + 1f) / totalChunks * 100).toInt(); tvPercent.text = "${pb.progress}%" }
                }
                withContext(Dispatchers.Main) { tvStatus.text = "✅ تم حفظ التحديث بنجاح!"; btnUpload.isEnabled = true; loadAdminUpdateHistory() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "❌ خطأ: ${e.message}"; btnUpload.isEnabled = true } }
        }
    }

    private fun forceInstallApk(apkFile: File) {
        requireActivity().runOnUiThread {
            try {
                apkFile.setReadable(true, false)
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.cache", apkFile)
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            } catch (e: Exception) {}
        }
    }
}
