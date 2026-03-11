package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.TimeUnit

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var listAllUsers: LinearLayout
    private lateinit var listActiveUsers: LinearLayout
    private lateinit var tvTotalUsers: TextView
    private lateinit var tvTotalActive: TextView

    // ذاكرة التخزين المؤقتة لتسريع جلب الأسماء والصور في الإحصائيات
    private val allUsersCache = mutableMapOf<String, JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)

        listAllUsers = findViewById(R.id.list_all_users)
        tvTotalUsers = findViewById(R.id.tv_total_users)

        // إعداد وتفعيل قسم النشطين
        val layoutActive = findViewById<LinearLayout>(R.id.layout_active_container)
        layoutActive.removeAllViews()
        tvTotalActive = TextView(this).apply { 
            text = "النشطين الآن: جاري التحميل..."
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD) // تم الإصلاح هنا
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10) 
        }
        listActiveUsers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10) }
        layoutActive.addView(tvTotalActive)
        layoutActive.addView(ScrollView(this).apply { addView(listActiveUsers) })

        // إعداد وتفعيل قسم الإحصائيات
        val layoutStatsContainer = findViewById<LinearLayout>(R.id.layout_stats_container)
        layoutStatsContainer.removeAllViews()
        setupStatsTab(layoutStatsContainer)

        // الأزرار للتبديل بين الصفحات
        val tabAllUsers = findViewById<Button>(R.id.tab_all_users)
        val tabActive = findViewById<Button>(R.id.tab_active_now)
        val tabStats = findViewById<Button>(R.id.tab_stats)
        val layoutAll = findViewById<View>(R.id.layout_all_users_container)

        tabAllUsers.setOnClickListener {
            layoutAll.visibility = View.VISIBLE; layoutActive.visibility = View.GONE; layoutStatsContainer.visibility = View.GONE
            tabAllUsers.setBackgroundColor(Color.parseColor("#FF9800")); tabAllUsers.setTextColor(Color.WHITE)
            tabActive.setBackgroundColor(Color.parseColor("#252529")); tabActive.setTextColor(Color.parseColor("#80FFFFFF"))
            tabStats.setBackgroundColor(Color.parseColor("#252529")); tabStats.setTextColor(Color.parseColor("#80FFFFFF"))
            fetchAllUsers()
        }
        
        tabActive.setOnClickListener {
            layoutAll.visibility = View.GONE; layoutActive.visibility = View.VISIBLE; layoutStatsContainer.visibility = View.GONE
            tabActive.setBackgroundColor(Color.parseColor("#FF9800")); tabActive.setTextColor(Color.WHITE)
            tabAllUsers.setBackgroundColor(Color.parseColor("#252529")); tabAllUsers.setTextColor(Color.parseColor("#80FFFFFF"))
            tabStats.setBackgroundColor(Color.parseColor("#252529")); tabStats.setTextColor(Color.parseColor("#80FFFFFF"))
            fetchActiveUsers()
        }

        tabStats.setOnClickListener {
            layoutAll.visibility = View.GONE; layoutActive.visibility = View.GONE; layoutStatsContainer.visibility = View.VISIBLE
            tabStats.setBackgroundColor(Color.parseColor("#FF9800")); tabStats.setTextColor(Color.WHITE)
            tabAllUsers.setBackgroundColor(Color.parseColor("#252529")); tabAllUsers.setTextColor(Color.parseColor("#80FFFFFF"))
            tabActive.setBackgroundColor(Color.parseColor("#252529")); tabActive.setTextColor(Color.parseColor("#80FFFFFF"))
            if (allUsersCache.isEmpty()) fetchAllUsers() // جلب صامت لتعبئة الكاش لاستخدامه في الإحصائيات
        }

        // جلب المستخدمين عند فتح الشاشة كبداية
        fetchAllUsers()
    }

    // =================== 1. جميع المستخدمين ===================
    private fun fetchAllUsers() {
        tvTotalUsers.text = "جاري تحميل المستخدمين من السحابة..."
        listAllUsers.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/admin/get_all_users")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val usersArray = JSONArray(resp)
                    withContext(Dispatchers.Main) {
                        tvTotalUsers.text = "إجمالي المستخدمين: ${usersArray.length()}"
                        allUsersCache.clear()
                        for (i in 0 until usersArray.length()) {
                            val u = usersArray.getJSONObject(i)
                            allUsersCache[u.getString("id")] = u // حفظ للكاش
                            addUserCard(
                                listAllUsers,
                                u.getString("id"),
                                u.getString("name"),
                                u.getString("password"),
                                u.optString("pfp", ""),
                                u.optBoolean("banned", false)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvTotalUsers.text = "خطأ في الاتصال بالإنترنت" }
            }
        }
    }

    // =================== 2. النشطين الآن ===================
    private fun fetchActiveUsers() {
        tvTotalActive.text = "جاري البحث عن النشطين..."
        listActiveUsers.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/admin/get_active_users")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val activeArray = JSONArray(resp)
                    withContext(Dispatchers.Main) {
                        tvTotalActive.text = "عدد النشطين الآن: ${activeArray.length()}"
                        val now = System.currentTimeMillis()
                        for (i in 0 until activeArray.length()) {
                            val u = activeArray.getJSONObject(i)
                            val startTime = u.getLong("startTime")
                            val durationMs = now - startTime
                            
                            val days = TimeUnit.MILLISECONDS.toDays(durationMs)
                            val hours = TimeUnit.MILLISECONDS.toHours(durationMs) % 24
                            val mins = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
                            
                            val timeStr = buildString {
                                if (days > 0) append("$days يوم و ")
                                if (hours > 0) append("$hours ساعة و ")
                                append("$mins دقيقة")
                            }

                            addActiveUserCard(u.getString("id"), u.getString("name"), u.optString("pfp", ""), timeStr)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun addActiveUserCard(id: String, name: String, pfp: String, timeStr: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val ivAvatar = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(0, 0, 30, 0) }
            if (pfp.isNotEmpty()) try { val b = Base64.decode(pfp, Base64.DEFAULT); setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { setImageResource(R.mipmap.ic_launcher) } // تم الإصلاح هنا (استخدام mipmap بدلا من drawable)
            else setImageResource(R.mipmap.ic_launcher) // تم الإصلاح هنا
        }
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }) // تم الإصلاح هنا
        infoLayout.addView(TextView(this).apply { text = "ID: $id"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f })
        infoLayout.addView(TextView(this).apply { text = "مدة النشاط: $timeStr"; setTextColor(Color.parseColor("#4CAF50")); textSize = 12f })
        card.addView(ivAvatar); card.addView(infoLayout); listActiveUsers.addView(card)
    }

    // =================== 3. الإحصائيات (التقويم) ===================
    private fun setupStatsTab(container: LinearLayout) {
        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        
        fun createStatButton(text: String, color: String, type: String) {
            val btn = MaterialButton(this).apply { this.text = text; setBackgroundColor(Color.parseColor(color)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 20) }
                setOnClickListener { loadCalendarStats(text, type) }
            }
            btnLayout.addView(btn)
        }

        createStatButton("📈 المستخدمين الجدد", "#2196F3", "NEW")
        createStatButton("🟢 النشطين (حسب الأيام)", "#4CAF50", "ACTIVE")
        createStatButton("🔑 عمليات الدخول", "#9C27B0", "LOGIN")
        createStatButton("🚪 عمليات الخروج", "#FF5722", "LOGOUT")
        createStatButton("🚫 الحسابات المحظورة", "#F44336", "BANNED") // لها مسار خاص
        
        container.addView(ScrollView(this).apply { addView(btnLayout) })
    }

    private fun loadCalendarStats(title: String, type: String) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(this).apply { text = title; setTextColor(Color.parseColor("#FF9800")); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } } // تم الإصلاح هنا
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })

        val dialog = AlertDialog.Builder(this).setView(dialogView).setPositiveButton("إغلاق", null).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (type == "BANNED") {
                    val bannedList = allUsersCache.values.filter { it.optBoolean("banned", false) }
                    withContext(Dispatchers.Main) {
                        if (bannedList.isEmpty()) scrollContent.addView(TextView(this@AdminDashboardActivity).apply { text = "لا يوجد محظورين"; setTextColor(Color.WHITE) })
                        bannedList.forEach { u -> addUserCard(scrollContent, u.getString("id"), u.getString("name"), u.getString("password"), u.optString("pfp", ""), true) }
                    }
                } else {
                    val url = URL("https://vpn-license.rauter505.workers.dev/admin/get_stats?type=$type")
                    val conn = url.openConnection() as HttpURLConnection
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val statsObj = JSONObject(resp)
                        val dates = statsObj.keys().asSequence().toList().sortedDescending() // الأحدث أولاً
                        
                        withContext(Dispatchers.Main) {
                            if (dates.isEmpty()) scrollContent.addView(TextView(this@AdminDashboardActivity).apply { text = "لا توجد بيانات مسجلة"; setTextColor(Color.WHITE) })
                            for (date in dates) {
                                val idsArray = statsObj.getJSONArray(date)
                                val dateBtn = MaterialButton(this@AdminDashboardActivity).apply {
                                    text = "📅 يوم $date (العدد: ${idsArray.length()})"
                                    setBackgroundColor(Color.parseColor("#252529"))
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                                    setOnClickListener { showUsersForDate(date, idsArray) }
                                }
                                scrollContent.addView(dateBtn)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun showUsersForDate(date: String, idsArray: JSONArray) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(this).apply { text = "المستخدمين في $date"; setTextColor(Color.parseColor("#4CAF50")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } } // تم الإصلاح هنا
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })

        for (i in 0 until idsArray.length()) {
            val id = idsArray.getString(i)
            val u = allUsersCache[id] // جلب من الكاش فوراً
            if (u != null) addUserCard(scrollContent, id, u.getString("name"), u.getString("password"), u.optString("pfp", ""), u.optBoolean("banned", false))
            else scrollContent.addView(TextView(this).apply { text = "ID: $id (محذوف من النظام)"; setTextColor(Color.GRAY) })
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("رجوع", null).show()
    }

    // =================== بطاقة المستخدم العامة ===================
    private fun addUserCard(container: LinearLayout, id: String, name: String, pass: String, pfp: String, isBanned: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        // الجزء العلوي: الصورة + الاسم + الايدي
        val topLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        val ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            if (pfp.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(pfp, Base64.DEFAULT)
                    setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } catch (e: Exception) { setImageResource(R.mipmap.ic_launcher) } // تم الإصلاح هنا
            } else { setImageResource(R.mipmap.ic_launcher) } // تم الإصلاح هنا
        }
        
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoLayout.addView(TextView(this).apply { text = "الاسم: $name"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }) // تم الإصلاح هنا
        infoLayout.addView(TextView(this).apply { text = "ID: $id"; setTextColor(Color.parseColor("#FF9800")); textSize = 14f })
        infoLayout.addView(TextView(this).apply { text = "الرمز: $pass"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 14f })
        
        if (isBanned) {
            infoLayout.addView(TextView(this).apply { text = "🚫 محظور"; setTextColor(Color.RED); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }) // تم الإصلاح هنا
        }

        topLayout.addView(ivAvatar)
        topLayout.addView(infoLayout)
        card.addView(topLayout)

        // أزرار التحكم
        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 30 } }

        val btnEdit = MaterialButton(this).apply { text = "تعديل"; setBackgroundColor(Color.parseColor("#2196F3")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) }
            setOnClickListener { showEditDialog(id, name, pass) }
        }
        
        val btnBan = MaterialButton(this).apply { text = if(isBanned) "فك الحظر" else "حظر"; setBackgroundColor(Color.parseColor("#FF9800")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) }
            setOnClickListener { toggleBanUser(id, !isBanned) }
        }

        val btnDelete = MaterialButton(this).apply { text = "حذف"; setBackgroundColor(Color.RED); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showDeleteConfirmDialog(id) }
        }

        btnLayout.addView(btnEdit); btnLayout.addView(btnBan); btnLayout.addView(btnDelete)
        card.addView(btnLayout)
        
        container.addView(card)
    }

    private fun showEditDialog(id: String, oldName: String, oldPass: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val etName = EditText(this).apply { hint = "الاسم الجديد"; setText(oldName); setTextColor(Color.BLACK) }
        val etPass = EditText(this).apply { hint = "الرمز الجديد"; setText(oldPass); setTextColor(Color.BLACK) }
        layout.addView(etName); layout.addView(etPass)

        AlertDialog.Builder(this).setTitle("تعديل بيانات $id").setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL("https://vpn-license.rauter505.workers.dev/admin/force_update").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                        conn.outputStream.use { it.write(JSONObject().put("id", id).put("name", etName.text.toString()).put("password", etPass.text.toString()).toString().toByteArray()) }
                        if (conn.responseCode == 200) fetchAllUsers()
                    } catch (e: Exception) {}
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun toggleBanUser(id: String, banStatus: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("https://vpn-license.rauter505.workers.dev/admin/toggle_ban").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("id", id).put("banned", banStatus).toString().toByteArray()) }
                if (conn.responseCode == 200) fetchAllUsers()
            } catch (e: Exception) {}
        }
    }

    private fun showDeleteConfirmDialog(id: String) {
        val input = EditText(this).apply { hint = "أدخل رمز الأدمن للتاكيد"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle("تحذير: حذف نهائي!").setMessage("لإثبات أنك أدمن، اكتب الرمز السري الأساسي للحذف:")
            .setView(input).setPositiveButton("حذف") { _, _ ->
                if (input.text.toString() == "mdMD@#$2002") { // التحقق من رمز الأدمن
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL("https://vpn-license.rauter505.workers.dev/admin/delete_user").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                            conn.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray()) }
                            if (conn.responseCode == 200) fetchAllUsers()
                        } catch (e: Exception) {}
                    }
                } else Toast.makeText(this, "رمز الأدمن خاطئ!", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("إلغاء", null).show()
    }
}
