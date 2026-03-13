package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class FileActiveUsersActivity : AppCompatActivity() {

    private lateinit var mainContainer: LinearLayout
    private lateinit var tvLoading: TextView
    private var currentGuid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentGuid = intent.getStringExtra("guid") ?: ""
        if (currentGuid.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات الملف", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // بناء الواجهة برمجياً
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }

        // شريط العنوان
        val header = TextView(this).apply {
            text = "إدارة المتصلين بالملف"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 40)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }

        // تبويبات (النشطين - المحظورين)
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            padding = 10
            setBackgroundColor(Color.parseColor("#141417"))
        }

        val btnActiveTab = MaterialButton(this).apply {
            text = "النشطين الآن 🟢"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
        }

        val btnBannedTab = MaterialButton(this).apply {
            text = "المحظورين 🚫"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
            setBackgroundColor(Color.parseColor("#252529"))
            setTextColor(Color.GRAY)
        }

        tabsLayout.addView(btnActiveTab)
        tabsLayout.addView(btnBannedTab)

        tvLoading = TextView(this).apply {
            text = "جاري تحميل البيانات..."
            setTextColor(Color.parseColor("#FF9800"))
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 20)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        scrollView.addView(mainContainer)
        root.addView(header)
        root.addView(tabsLayout)
        root.addView(tvLoading)
        root.addView(scrollView)

        setContentView(root)

        // برمجة الأزرار
        btnActiveTab.setOnClickListener {
            btnActiveTab.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnActiveTab.setTextColor(Color.WHITE)
            btnBannedTab.setBackgroundColor(Color.parseColor("#252529"))
            btnBannedTab.setTextColor(Color.GRAY)
            loadUsers("ACTIVE")
        }

        btnBannedTab.setOnClickListener {
            btnBannedTab.setBackgroundColor(Color.parseColor("#F44336"))
            btnBannedTab.setTextColor(Color.WHITE)
            btnActiveTab.setBackgroundColor(Color.parseColor("#252529"))
            btnActiveTab.setTextColor(Color.GRAY)
            loadUsers("BANNED")
        }

        // تحميل النشطين افتراضياً
        loadUsers("ACTIVE")
    }

    private fun loadUsers(type: String) {
        tvLoading.visibility = View.VISIBLE
        mainContainer.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val endpoint = if (type == "ACTIVE") "get_active" else "get_banned"
                val url = URL("https://vpn-license.rauter505.workers.dev/file/$endpoint?guid=$currentGuid")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val array = JSONArray(resp)

                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        if (array.length() == 0) {
                            mainContainer.addView(TextView(this@FileActiveUsersActivity).apply { 
                                text = if (type == "ACTIVE") "لا يوجد متصلين حالياً" else "لا يوجد محظورين في هذا الملف"
                                setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, 50, 0, 0)
                            })
                            return@withContext
                        }

                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            val isBanned = obj.optBoolean("isBanned", type == "BANNED")
                            addUserCard(
                                obj.optString("deviceId"),
                                obj.optString("name", "مجهول الهوية"),
                                obj.optString("userId", ""),
                                obj.optString("pfp", ""),
                                isBanned,
                                type
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "خطأ في الاتصال بالإنترنت" }
            }
        }
    }

    private fun addUserCard(deviceId: String, name: String, userId: String, pfp: String, isBanned: Boolean, currentTab: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        val ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            background = resources.getDrawable(android.R.drawable.dialog_holo_dark_frame, null)
            clipToOutline = true
        }

        // تحميل الصورة (إذا كان مجهول نعطيه صورة أنمي عشوائية بناءً على جهازة)
        if (pfp.isNotEmpty()) {
            try {
                val bytes = Base64.decode(pfp, Base64.DEFAULT)
                ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (e: Exception) { loadAnimeAvatar(ivAvatar, deviceId) }
        } else {
            loadAnimeAvatar(ivAvatar, deviceId)
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
        if (userId.isNotEmpty()) {
            infoLayout.addView(TextView(this).apply { text = "ID: $userId"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f })
        } else {
            infoLayout.addView(TextView(this).apply { text = "غير مسجل (حساب جهاز)"; setTextColor(Color.GRAY); textSize = 12f })
        }

        // زر الحظر / إلغاء الحظر
        val btnAction = MaterialButton(this).apply {
            if (isBanned) {
                text = "إلغاء الحظر"
                setBackgroundColor(Color.parseColor("#2196F3"))
            } else {
                text = "حظر فوراً"
                setBackgroundColor(Color.parseColor("#F44336"))
            }
            setOnClickListener {
                toggleBanStatus(deviceId, name, userId, pfp, !isBanned, currentTab)
            }
        }

        card.addView(ivAvatar)
        card.addView(infoLayout)
        card.addView(btnAction)

        mainContainer.addView(card)
    }

    private fun loadAnimeAvatar(imageView: ImageView, seed: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.dicebear.com/7.x/adventurer/png?seed=$seed&backgroundColor=b6e3f4,c0aede,d1d4f9")
                val conn = url.openConnection() as HttpURLConnection
                val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                withContext(Dispatchers.Main) { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) {}
        }
    }

    private fun toggleBanStatus(deviceId: String, name: String, userId: String, pfp: String, banStatus: Boolean, currentTab: String) {
        val actionName = if (banStatus) "حظر" else "إلغاء حظر"
        AlertDialog.Builder(this)
            .setTitle("تأكيد العملية")
            .setMessage("هل أنت متأكد أنك تريد $actionName هذا المستخدم من الملف؟\n(سيتم تطبيق ذلك حتى لو قام بمسح بيانات التطبيق)")
            .setPositiveButton("نعم") { _, _ ->
                tvLoading.visibility = View.VISIBLE
                tvLoading.text = "جاري تنفيذ الأمر..."
                
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL("https://vpn-license.rauter505.workers.dev/file/toggle_ban").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true

                        val payload = JSONObject()
                            .put("guid", currentGuid)
                            .put("deviceId", deviceId)
                            .put("banStatus", banStatus)
                            .put("name", name)
                            .put("userId", userId)
                            .put("pfp", pfp)

                        conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                        if (conn.responseCode == 200) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@FileActiveUsersActivity, "تم التنفيذ بنجاح!", Toast.LENGTH_SHORT).show()
                                loadUsers(currentTab) // إعادة تحميل القائمة
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
