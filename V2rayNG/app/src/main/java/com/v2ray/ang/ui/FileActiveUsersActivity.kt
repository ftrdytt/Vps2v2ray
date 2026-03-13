package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var etSearch: EditText
    private var currentGuid: String = ""
    
    // لتخزين البيانات محلياً وإجراء البحث عليها بدون الحاجة للاتصال بالسيرفر في كل مرة
    private var allLoadedUsers = JSONArray() 
    private var currentTabType = "ACTIVE"

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

        // حقل البحث الذكي 🔍
        etSearch = EditText(this).apply {
            hint = "🔍 ابحث بالاسم، ID، أو Device ID..."
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#141417"))
            setPadding(30, 30, 30, 30)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(20, 20, 20, 20)
            }
            
            // إضافة مستمع للبحث عند كتابة أي حرف
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterUsers(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // تبويبات (النشطين - المحظورين)
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10) 
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
        root.addView(etSearch) // إضافة حقل البحث تحت العنوان
        root.addView(tabsLayout)
        root.addView(tvLoading)
        root.addView(scrollView)

        setContentView(root)

        // برمجة الأزرار
        btnActiveTab.setOnClickListener {
            currentTabType = "ACTIVE"
            etSearch.text.clear() // مسح البحث عند التبديل
            btnActiveTab.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnActiveTab.setTextColor(Color.WHITE)
            btnBannedTab.setBackgroundColor(Color.parseColor("#252529"))
            btnBannedTab.setTextColor(Color.GRAY)
            loadUsers("ACTIVE")
        }

        btnBannedTab.setOnClickListener {
            currentTabType = "BANNED"
            etSearch.text.clear() // مسح البحث عند التبديل
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
        allLoadedUsers = JSONArray() // تصفير المصفوفة القديمة

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val endpoint = if (type == "ACTIVE") "get_active" else "get_banned"
                val url = URL("https://vpn-license.rauter505.workers.dev/file/$endpoint?guid=$currentGuid")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    allLoadedUsers = JSONArray(resp) // حفظ البيانات للبحث المستقبلي

                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        renderUsersList(allLoadedUsers, type)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "خطأ في الاتصال بالإنترنت" }
            }
        }
    }

    // دالة جديدة لتصفية النتائج بناءً على البحث
    private fun filterUsers(query: String) {
        val filteredArray = JSONArray()
        val lowerQuery = query.lowercase()

        for (i in 0 until allLoadedUsers.length()) {
            val obj = allLoadedUsers.getJSONObject(i)
            val name = obj.optString("name", "مجهول الهوية").lowercase()
            val userId = obj.optString("userId", "").lowercase()
            val deviceId = obj.optString("deviceId", "").lowercase()

            if (name.contains(lowerQuery) || userId.contains(lowerQuery) || deviceId.contains(lowerQuery)) {
                filteredArray.put(obj)
            }
        }
        
        mainContainer.removeAllViews()
        renderUsersList(filteredArray, currentTabType)
    }

    // دالة جديدة مسؤولة عن رسم بطاقات المستخدمين
    private fun renderUsersList(array: JSONArray, type: String) {
        if (array.length() == 0) {
            mainContainer.addView(TextView(this@FileActiveUsersActivity).apply { 
                text = if (etSearch.text.isNotEmpty()) "لم يتم العثور على نتائج تطابق بحثك" 
                       else if (type == "ACTIVE") "لا يوجد متصلين حالياً" 
                       else "لا يوجد محظورين في هذا الملف"
                setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, 50, 0, 0)
            })
            return
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
        
        // عرض جزء من Device ID للتأكد
        infoLayout.addView(TextView(this).apply { text = "Device: ${deviceId.takeLast(6)}"; setTextColor(Color.parseColor("#4CAF50")); textSize = 10f })

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
