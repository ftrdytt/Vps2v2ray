package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UpdateLogsActivity : AppCompatActivity() {

    // 🌟 الرابط الأساسي للسيرفر 🌟
    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var mainContainer: LinearLayout
    private lateinit var tvLoading: TextView
    private val allUsersCache = mutableMapOf<String, JSONObject>()

    // أزرار التبويبات (الخانات)
    private lateinit var btnTabUsers: MaterialButton
    private lateinit var btnTabServer: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🌟 بناء الواجهة الرئيسية الاحترافية 🌟
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }
        
        val header = TextView(this).apply {
            text = "مركز مراقبة التحديثات"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 40)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }

        // 🌟 شريط التبويبات (Tabs) 🌟
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#141417"))
            setPadding(20, 20, 20, 20)
        }

        btnTabUsers = MaterialButton(this).apply {
            text = "سجل المُحَدِّثين 👥"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
            setOnClickListener { switchTab(true) }
        }

        btnTabServer = MaterialButton(this).apply {
            text = "النسخ المرفوعة 📦"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
            setOnClickListener { switchTab(false) }
        }

        tabsLayout.addView(btnTabUsers)
        tabsLayout.addView(btnTabServer)
        
        tvLoading = TextView(this).apply {
            text = "جاري تحميل البيانات من السيرفر..."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 50, 20, 20)
        }
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }
        
        scrollView.addView(mainContainer)
        root.addView(header)
        root.addView(tabsLayout)
        root.addView(tvLoading)
        root.addView(scrollView)
        
        setContentView(root)
        
        // البدء بتحميل سجل المستخدمين كافتراضي (الخانة الأولى)
        switchTab(true)
    }

    // 🌟 دالة التبديل بين التبويبات 🌟
    private fun switchTab(isUsersTab: Boolean) {
        mainContainer.removeAllViews()
        tvLoading.visibility = View.VISIBLE
        
        if (isUsersTab) {
            btnTabUsers.setBackgroundColor(Color.parseColor("#FF9800")) // لون مفعل
            btnTabServer.setBackgroundColor(Color.parseColor("#252529")) // لون غير مفعل
            loadUserLogs()
        } else {
            btnTabServer.setBackgroundColor(Color.parseColor("#FF9800"))
            btnTabUsers.setBackgroundColor(Color.parseColor("#252529"))
            loadServerUpdates()
        }
    }

    // ==========================================
    // 🟢 القسم الأول: سجل الأشخاص المُحَدِّثين 🟢
    // ==========================================
    private fun loadUserLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // جلب الكاش للمستخدمين لربط الـ ID بالصورة والاسم
                try {
                    val usersConn = URL("$BASE_API_URL/admin/get_all_users").openConnection() as HttpURLConnection
                    usersConn.connectTimeout = 5000
                    if (usersConn.responseCode == 200) {
                        val usersArray = JSONArray(BufferedReader(InputStreamReader(usersConn.inputStream)).readText())
                        for (i in 0 until usersArray.length()) {
                            val u = usersArray.getJSONObject(i)
                            allUsersCache[u.getString("id")] = u
                        }
                    }
                } catch (e: Exception) {}

                // جلب سجلات التحديث
                val logsConn = URL("$BASE_API_URL/admin/get_update_logs").openConnection() as HttpURLConnection
                logsConn.connectTimeout = 8000
                
                if (logsConn.responseCode == 200) {
                    val flatLogsArray = JSONArray(BufferedReader(InputStreamReader(logsConn.inputStream)).readText())
                    val groupedLogs = mutableMapOf<String, JSONArray>()
                    
                    for (i in 0 until flatLogsArray.length()) {
                        val logItem = flatLogsArray.getJSONObject(i)
                        val fullDate = logItem.optString("date", "")
                        val dayDate = if (fullDate.contains(" ")) fullDate.split(" ")[0] else "غير محدد"
                        
                        if (!groupedLogs.containsKey(dayDate)) groupedLogs[dayDate] = JSONArray()
                        groupedLogs[dayDate]?.put(logItem)
                    }
                    
                    val dates = groupedLogs.keys.sortedDescending()
                    
                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        if (dates.isEmpty()) {
                            mainContainer.addView(TextView(this@UpdateLogsActivity).apply { text = "لا توجد سجلات تحديث حتى الآن"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                        } else {
                            for (date in dates) {
                                val logsForThisDay = groupedLogs[date]!!
                                val dateBtn = MaterialButton(this@UpdateLogsActivity).apply {
                                    text = "📅 يوم $date (عدد المُحَدِّثين: ${logsForThisDay.length()})"
                                    setBackgroundColor(Color.parseColor("#1B2E1C"))
                                    setTextColor(Color.parseColor("#8BC34A"))
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 20) }
                                    setOnClickListener { showLogsForDateDialog(date, logsForThisDay) }
                                }
                                mainContainer.addView(dateBtn)
                            }
                        }
                    }
                } else throw Exception("Server Error")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "فشل الاتصال بالسيرفر. يرجى المحاولة لاحقاً." }
            }
        }
    }

    private fun showLogsForDateDialog(date: String, logsArray: JSONArray) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(this).apply { text = "الأشخاص الذين حدثوا يوم $date"; setTextColor(Color.parseColor("#4CAF50")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })

        for (i in 0 until logsArray.length()) {
            val logItem = logsArray.getJSONObject(i)
            val userId = logItem.optString("userId", "")
            val deviceId = logItem.optString("deviceId", "غير معروف")
            val version = logItem.optInt("version", 0)
            val fullDate = logItem.optString("date", "")
            val time = if (fullDate.contains(" ")) fullDate.split(" ")[1] else fullDate
            
            var name = logItem.optString("name", "مجهول الهوية")
            var pfp = ""
            var role = "مستخدم"
            
            if (userId.isNotEmpty()) {
                val u = allUsersCache[userId]
                if (u != null) {
                    name = u.optString("name", name)
                    pfp = u.optString("pfp", "")
                    role = u.optString("role", "مستخدم")
                }
            }
            
            if (name.trim().isEmpty() || name == "مجهول") name = "مجهول"
            val displayId = if (userId.isNotEmpty()) "حساب ID: $userId" else "جهاز ID: $deviceId"
            
            addUserCard(scrollContent, displayId, name, pfp, version, time, role)
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("رجوع", null).show()
    }

    // 🌟 تصميم كارت المستخدم الاحترافي (صورة دائرية أو أول حرف) 🌟
    private fun addUserCard(container: LinearLayout, idString: String, name: String, pfp: String, version: Int, time: String, role: String) {
        val card = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } 
        }
        
        // 🌟 دائرة الصورة (CardView) لضمان القص الدائري 🌟
        val avatarCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { setMargins(0, 0, 30, 0) }
            radius = 70f // نصف الحجم لجعله دائرة مثالية
            setCardBackgroundColor(Color.TRANSPARENT)
            cardElevation = 0f
        }
        
        val flContainer = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        
        val tvLetter = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#3F51B5")) // خلفية الحرف الأول
            background = bg
            text = name.trim().firstOrNull()?.toString()?.uppercase() ?: "م"
        }
        
        val ivAvatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        if (pfp.isNotEmpty()) {
            try {
                val b = Base64.decode(pfp, Base64.DEFAULT)
                ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                ivAvatar.visibility = View.VISIBLE
                tvLetter.visibility = View.GONE
            } catch (e: Exception) {}
        }
        
        flContainer.addView(tvLetter)
        flContainer.addView(ivAvatar)
        avatarCard.addView(flContainer)

        // 🌟 ميزة الضغط لعرض التفاصيل الكاملة والصورة المربعة 🌟
        avatarCard.setOnClickListener {
            showFullUserDetails(name, idString, version, time, role, pfp)
        }

        // 🌟 معلومات المستخدم الجانبية 🌟
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
        infoLayout.addView(TextView(this).apply { text = idString; setTextColor(Color.parseColor("#FF9800")); textSize = 12f })
        infoLayout.addView(TextView(this).apply { text = "التحديث: $version | الساعة: $time"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 12f })
        
        card.addView(avatarCard)
        card.addView(infoLayout)
        container.addView(card)
    }

    // 🌟 النافذة المنبثقة للتفاصيل الكاملة (تظهر عند الضغط على الصورة الدائرية) 🌟
    private fun showFullUserDetails(name: String, idString: String, version: Int, time: String, role: String, pfp: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141417"))
            setPadding(50, 50, 50, 50)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 🌟 الصورة بالحجم الكبير المربع 🌟
        val largeImageContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(400, 400).apply { setMargins(0, 0, 0, 40) }
        }

        val largeTvLetter = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 80f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE // مربعة
            bg.cornerRadius = 20f
            bg.setColor(Color.parseColor("#3F51B5"))
            background = bg
            text = name.trim().firstOrNull()?.toString()?.uppercase() ?: "م"
        }

        val largeIvAvatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        if (pfp.isNotEmpty()) {
            try {
                val b = Base64.decode(pfp, Base64.DEFAULT)
                largeIvAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                largeIvAvatar.visibility = View.VISIBLE
                largeTvLetter.visibility = View.GONE
            } catch (e: Exception) {}
        }

        largeImageContainer.addView(largeTvLetter)
        largeImageContainer.addView(largeIvAvatar)

        // 🌟 نصوص التفاصيل 🌟
        val tvName = TextView(this).apply { text = "الاسم: $name"; setTextColor(Color.WHITE); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) } }
        val tvId = TextView(this).apply { text = idString; setTextColor(Color.parseColor("#FF9800")); textSize = 16f; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) } }
        val tvRole = TextView(this).apply { text = "الصلاحية: ${if (role == "admin") "مدير 👑" else "مستخدم"}"; setTextColor(Color.parseColor("#E91E63")); textSize = 16f; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) } }
        val tvVersion = TextView(this).apply { text = "قام بتثبيت تحديث رقم: $version"; setTextColor(Color.parseColor("#2196F3")); textSize = 16f; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) } }
        val tvTime = TextView(this).apply { text = "تاريخ ووقت التحديث: $time"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 16f; }

        root.addView(largeImageContainer)
        root.addView(tvName)
        root.addView(tvId)
        root.addView(tvRole)
        root.addView(tvVersion)
        root.addView(tvTime)

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("إغلاق النافذة", null)
            .show()
    }

    // ==========================================
    // 🟢 القسم الثاني: تفاصيل التحديثات المرفوعة بالسيرفر 🟢
    // ==========================================
    private fun loadServerUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/app/update/list").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                if (conn.responseCode == 200) {
                    val array = JSONArray(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        if (array.length() == 0) {
                            mainContainer.addView(TextView(this@UpdateLogsActivity).apply { text = "لا توجد نسخ مرفوعة على السيرفر"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                        } else {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val v = item.getInt("version")
                                val isActive = item.getBoolean("active")
                                val date = item.optString("date", "غير محدد")
                                
                                val archsObj = item.optJSONObject("architectures")
                                val availableArchs = mutableListOf<String>()
                                if (archsObj != null) {
                                    val keys = archsObj.keys()
                                    while(keys.hasNext()) { 
                                        val archKey = keys.next() as String
                                        val niceName = if (archKey.contains("64")) "64-بت (arm64-v8a)" else if (archKey.contains("v7a")) "32-بت (armeabi-v7a)" else "محاكي (x86)"
                                        availableArchs.add(niceName) 
                                    }
                                }
                                val archsStr = if (availableArchs.isEmpty()) "لم يكتمل الرفع بعد" else availableArchs.joinToString("\n• ")
                                
                                addServerUpdateCard(v, date, isActive, archsStr)
                            }
                        }
                    }
                } else throw Exception("Error")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "فشل جلب النسخ المرفوعة." }
            }
        }
    }

    // 🌟 تصميم كارت تفاصيل التحديث المرفوع (مع المعماريات 32 و 64) 🌟
    private fun addServerUpdateCard(version: Int, date: String, isActive: Boolean, archsStr: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if(isActive) Color.parseColor("#1B2E1C") else Color.parseColor("#252529"))
            setPadding(30, 40, 30, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        val tvTitle = TextView(this).apply {
            text = "إصدار التطبيق: $version"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
        }
        
        val tvStatus = TextView(this).apply {
            text = "حالة النسخة: ${if(isActive) "🟢 نشطة (إجبارية للمستخدمين)" else "🔴 متوقفة (لا تظهر للمستخدمين)"}"
            setTextColor(if(isActive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
        }

        val tvArchs = TextView(this).apply {
            text = "الأنظمة المتوفرة بهذه النسخة:\n• $archsStr"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
        }
        
        val tvDate = TextView(this).apply {
            text = "تاريخ الرفع: $date"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
        }

        card.addView(tvTitle)
        card.addView(tvStatus)
        card.addView(tvArchs)
        card.addView(tvDate)
        
        mainContainer.addView(card)
    }
}
