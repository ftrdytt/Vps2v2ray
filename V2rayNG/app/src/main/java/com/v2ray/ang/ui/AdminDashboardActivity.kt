package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.util.AvatarGenerator
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

    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var listAllUsers: ViewGroup
    private lateinit var listActiveUsers: LinearLayout
    private lateinit var tvTotalUsers: TextView
    private lateinit var tvTotalActive: TextView

    private lateinit var usersContainer: LinearLayout
    private lateinit var activeUsersContainer: LinearLayout

    private val allUsersCache = mutableMapOf<String, JSONObject>()
    private var allUsersArray = JSONArray()
    private var activeUsersArray = JSONArray()
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)

        // 🌟 حماية من الكراش باستخدام ViewGroup بدلاً من LinearLayout 🌟
        listAllUsers = findViewById<ViewGroup>(R.id.list_all_users)
        tvTotalUsers = findViewById(R.id.tv_total_users)

        // إعداد شريط البحث
        val searchInput = EditText(this).apply {
            hint = "🔍 بحث (الاسم، المعرف، الآيدي، الجهاز)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(40, 40, 40, 40)
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s.toString().trim()
                    renderAllUsers()
                    renderActiveUsers()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        listAllUsers.addView(searchInput)
        usersContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listAllUsers.addView(usersContainer)

        val layoutActive = findViewById<ViewGroup>(R.id.layout_active_container)
        layoutActive.removeAllViews()
        tvTotalActive = TextView(this).apply { 
            text = "النشطين الآن: جاري التحميل..."
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD) 
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
        }
        listActiveUsers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10) }
        activeUsersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listActiveUsers.addView(activeUsersContainer)
        
        layoutActive.addView(tvTotalActive)
        layoutActive.addView(ScrollView(this).apply { addView(listActiveUsers) })

        val layoutStatsContainer = findViewById<ViewGroup>(R.id.layout_stats_container)
        layoutStatsContainer.removeAllViews()
        setupStatsTab(layoutStatsContainer)

        val tabAllUsers = findViewById<Button>(R.id.tab_all_users)
        val tabActive = findViewById<Button>(R.id.tab_active_now)
        val tabStats = findViewById<Button>(R.id.tab_stats)
        val layoutAll = findViewById<View>(R.id.layout_all_users_container)

        tabAllUsers.setOnClickListener {
            layoutAll.visibility = View.VISIBLE; layoutActive.visibility = View.GONE; layoutStatsContainer.visibility = View.GONE
            tabAllUsers.setBackgroundColor(Color.parseColor("#FF9800")); tabAllUsers.setTextColor(Color.WHITE)
            tabActive.setBackgroundColor(Color.parseColor("#252529")); tabActive.setTextColor(Color.parseColor("#80FFFFFF"))
            tabStats.setBackgroundColor(Color.parseColor("#252529")); tabStats.setTextColor(Color.parseColor("#80FFFFFF"))
            searchInput.visibility = View.VISIBLE
            fetchAllUsers()
        }
        
        tabActive.setOnClickListener {
            layoutAll.visibility = View.GONE; layoutActive.visibility = View.VISIBLE; layoutStatsContainer.visibility = View.GONE
            tabActive.setBackgroundColor(Color.parseColor("#FF9800")); tabActive.setTextColor(Color.WHITE)
            tabAllUsers.setBackgroundColor(Color.parseColor("#252529")); tabAllUsers.setTextColor(Color.parseColor("#80FFFFFF"))
            tabStats.setBackgroundColor(Color.parseColor("#252529")); tabStats.setTextColor(Color.parseColor("#80FFFFFF"))
            searchInput.visibility = View.VISIBLE 
            fetchActiveUsers()
        }

        tabStats.setOnClickListener {
            layoutAll.visibility = View.GONE; layoutActive.visibility = View.GONE; layoutStatsContainer.visibility = View.VISIBLE
            tabStats.setBackgroundColor(Color.parseColor("#FF9800")); tabStats.setTextColor(Color.WHITE)
            tabAllUsers.setBackgroundColor(Color.parseColor("#252529")); tabAllUsers.setTextColor(Color.parseColor("#80FFFFFF"))
            tabActive.setBackgroundColor(Color.parseColor("#252529")); tabActive.setTextColor(Color.parseColor("#80FFFFFF"))
            searchInput.visibility = View.GONE
            if (allUsersCache.isEmpty()) fetchAllUsers() 
        }

        fetchAllUsers()
    }

    private fun getSafeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrEmpty()) return null
        return try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    // 🌟 دالة النسخ 🌟
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "تم نسخ $label 📋", Toast.LENGTH_SHORT).show()
    }

    private fun fetchAllUsers() {
        tvTotalUsers.text = "جاري تحميل المستخدمين من السحابة..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$BASE_API_URL/admin/get_all_users")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    allUsersArray = JSONArray(resp)
                    withContext(Dispatchers.Main) {
                        tvTotalUsers.text = "إجمالي المستخدمين: ${allUsersArray.length()}"
                        allUsersCache.clear()
                        for (i in 0 until allUsersArray.length()) {
                            val u = allUsersArray.getJSONObject(i)
                            allUsersCache[u.getString("id")] = u
                        }
                        renderAllUsers()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvTotalUsers.text = "خطأ في الاتصال بالإنترنت" }
            }
        }
    }

    private fun renderAllUsers() {
        usersContainer.removeAllViews()
        for (i in 0 until allUsersArray.length()) {
            val u = allUsersArray.getJSONObject(i)
            val id = u.getString("id")
            val name = u.getString("name")
            val username = u.optString("username", "")
            val devicesArray = u.optJSONArray("devices")
            
            // فلترة البحث
            var match = name.contains(searchQuery, true) || id.contains(searchQuery, true) || username.contains(searchQuery, true)
            if (!match && devicesArray != null) {
                for (j in 0 until devicesArray.length()) {
                    if (devicesArray.getString(j).contains(searchQuery, true)) { match = true; break }
                }
            }
            if (searchQuery.isNotEmpty() && !match) continue

            addUserCard(
                usersContainer, id, name, u.getString("password"), u.optString("pfp", ""),
                u.optBoolean("banned", false), username, devicesArray
            )
        }
    }

    private fun fetchActiveUsers() {
        tvTotalActive.text = "جاري البحث عن النشطين..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$BASE_API_URL/admin/get_active_users")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    activeUsersArray = JSONArray(resp)
                    withContext(Dispatchers.Main) {
                        tvTotalActive.text = "عدد النشطين الآن: ${activeUsersArray.length()}"
                        renderActiveUsers()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun renderActiveUsers() {
        activeUsersContainer.removeAllViews()
        val now = System.currentTimeMillis()
        for (i in 0 until activeUsersArray.length()) {
            val u = activeUsersArray.getJSONObject(i)
            val id = u.getString("id")
            val name = u.getString("name")
            val deviceId = u.optString("deviceId", "")
            
            if (searchQuery.isNotEmpty() && !name.contains(searchQuery, true) && !id.contains(searchQuery, true) && !deviceId.contains(searchQuery, true)) {
                continue
            }

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
            addActiveUserCard(id, name, u.optString("pfp", ""), timeStr, deviceId)
        }
    }

    // =================== كارت النشطين ===================
    private fun addActiveUserCard(id: String, name: String, pfp: String, timeStr: String, deviceId: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        
        val avatarCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            radius = 60f
            setCardBackgroundColor(Color.TRANSPARENT)
            cardElevation = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }
        val ivAvatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val bitmap = getSafeBitmap(pfp) ?: AvatarGenerator.generateAvatar(name, id)
        ivAvatar.setImageBitmap(bitmap)
        avatarCard.addView(ivAvatar)
        
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }) 
        
        val idLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        idLayout.addView(TextView(this).apply { text = "ID: $id"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val btnCopyId = TextView(this).apply { text = "📋 نسخ"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f; setPadding(15,10,15,10); background = GradientDrawable().apply { setColor(Color.parseColor("#252529")); cornerRadius = 10f }; setOnClickListener { copyToClipboard("آيدي الحساب", id) } }
        idLayout.addView(btnCopyId)
        infoLayout.addView(idLayout)

        val devLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,10,0,0) }
        devLayout.addView(TextView(this).apply { text = "الجهاز: ${if(deviceId.length>10) deviceId.substring(0,10)+".." else deviceId}"; setTextColor(Color.parseColor("#9C27B0")); textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val btnCopyDev = TextView(this).apply { text = "📋 نسخ"; setTextColor(Color.parseColor("#9C27B0")); textSize = 12f; setPadding(15,10,15,10); background = GradientDrawable().apply { setColor(Color.parseColor("#252529")); cornerRadius = 10f }; setOnClickListener { copyToClipboard("آيدي الجهاز", deviceId) } }
        devLayout.addView(btnCopyDev)
        infoLayout.addView(devLayout)

        infoLayout.addView(TextView(this).apply { text = "مدة النشاط: $timeStr"; setTextColor(Color.parseColor("#4CAF50")); textSize = 12f; setPadding(0,10,0,0) })
        card.addView(avatarCard); card.addView(infoLayout); activeUsersContainer.addView(card)
    }

    private fun setupStatsTab(container: ViewGroup) {
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
        createStatButton("🚫 الحسابات المحظورة", "#F44336", "BANNED") 
        container.addView(ScrollView(this).apply { addView(btnLayout) })
    }

    private fun loadCalendarStats(title: String, type: String) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(this).apply { text = title; setTextColor(Color.parseColor("#FF9800")); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } } 
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("إغلاق", null).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (type == "BANNED") {
                    val bannedList = allUsersCache.values.filter { it.optBoolean("banned", false) }
                    withContext(Dispatchers.Main) {
                        if (bannedList.isEmpty()) scrollContent.addView(TextView(this@AdminDashboardActivity).apply { text = "لا يوجد محظورين"; setTextColor(Color.WHITE) })
                        bannedList.forEach { u -> addUserCard(scrollContent, u.getString("id"), u.getString("name"), u.getString("password"), u.optString("pfp", ""), true, u.optString("username", ""), u.optJSONArray("devices")) }
                    }
                } else {
                    val url = URL("$BASE_API_URL/admin/get_stats?type=$type")
                    val conn = url.openConnection() as HttpURLConnection
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val statsObj = JSONObject(resp)
                        val dates = statsObj.keys().asSequence().toList().sortedDescending()
                        withContext(Dispatchers.Main) {
                            if (dates.isEmpty()) scrollContent.addView(TextView(this@AdminDashboardActivity).apply { text = "لا توجد بيانات مسجلة"; setTextColor(Color.WHITE) })
                            for (date in dates) {
                                val idsArray = statsObj.getJSONArray(date)
                                val dateBtn = MaterialButton(this@AdminDashboardActivity).apply { text = "📅 يوم $date (العدد: ${idsArray.length()})"; setBackgroundColor(Color.parseColor("#252529")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
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
        val tvTitle = TextView(this).apply { text = "المستخدمين في $date"; setTextColor(Color.parseColor("#4CAF50")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } } 
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })

        for (i in 0 until idsArray.length()) {
            val id = idsArray.getString(i)
            val u = allUsersCache[id]
            if (u != null) addUserCard(scrollContent, id, u.getString("name"), u.getString("password"), u.optString("pfp", ""), u.optBoolean("banned", false), u.optString("username", ""), u.optJSONArray("devices"))
            else scrollContent.addView(TextView(this).apply { text = "ID: $id (محذوف من النظام)"; setTextColor(Color.GRAY) })
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("رجوع", null).show()
    }

    // =================== بطاقة المستخدم الشاملة VIP ===================
    private fun addUserCard(container: LinearLayout, id: String, name: String, pass: String, pfp: String, isBanned: Boolean, username: String, devicesArray: JSONArray?) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val topLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        val avatarCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(130, 130).apply { setMargins(0, 0, 30, 0) }
            radius = 65f
            setCardBackgroundColor(Color.TRANSPARENT)
            cardElevation = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }
        val ivAvatar = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.CENTER_CROP }
        val bitmap = getSafeBitmap(pfp) ?: AvatarGenerator.generateAvatar(name, id)
        ivAvatar.setImageBitmap(bitmap)
        avatarCard.addView(ivAvatar)
        
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        infoLayout.addView(TextView(this).apply { text = "الاسم: $name"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }) 
        
        if (username.isNotEmpty()) {
            infoLayout.addView(TextView(this).apply { text = "المعرف: @$username"; setTextColor(Color.parseColor("#2196F3")); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) })
        }
        
        val idLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,5,0,5) }
        idLayout.addView(TextView(this).apply { text = "ID: $id"; setTextColor(Color.parseColor("#FF9800")); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val btnCopyId = TextView(this).apply { text = "📋 نسخ"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f; setPadding(20,10,20,10); background = GradientDrawable().apply { setColor(Color.parseColor("#252529")); cornerRadius = 15f }; setOnClickListener { copyToClipboard("آيدي الحساب", id) } }
        idLayout.addView(btnCopyId)
        infoLayout.addView(idLayout)

        infoLayout.addView(TextView(this).apply { text = "الرمز: $pass"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 14f })
        
        if (devicesArray != null && devicesArray.length() > 0) {
            infoLayout.addView(TextView(this).apply { text = "الأجهزة المسجلة (${devicesArray.length()}):"; setTextColor(Color.parseColor("#9C27B0")); textSize = 12f; setPadding(0, 15, 0, 10) })
            for (j in 0 until devicesArray.length()) {
                val devId = devicesArray.getString(j)
                val devLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }
                devLayout.addView(TextView(this).apply { text = "📱 $devId"; setTextColor(Color.LTGRAY); textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                val btnCopyDev = TextView(this).apply { text = "📋 نسخ"; setTextColor(Color.LTGRAY); textSize = 10f; setPadding(20,10,20,10); background = GradientDrawable().apply { setColor(Color.parseColor("#252529")); cornerRadius = 15f }; setOnClickListener { copyToClipboard("آيدي الجهاز", devId) } }
                devLayout.addView(btnCopyDev)
                infoLayout.addView(devLayout)
            }
        } else {
            infoLayout.addView(TextView(this).apply { text = "لا توجد أجهزة مرتبطة"; setTextColor(Color.GRAY); textSize = 12f; setPadding(0, 10, 0, 5) })
        }
        
        if (isBanned) { infoLayout.addView(TextView(this).apply { text = "🚫 محظور"; setTextColor(Color.RED); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,10,0,0) }) }

        topLayout.addView(avatarCard); topLayout.addView(infoLayout); card.addView(topLayout)

        val btnLayout1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 } }
        val btnEdit = MaterialButton(this).apply { text = "تعديل"; setBackgroundColor(Color.parseColor("#2196F3")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) }; setOnClickListener { showEditDialog(id, name, pass, username) } }
        val btnUnbind = MaterialButton(this).apply { text = "مسح الأجهزة"; setBackgroundColor(Color.parseColor("#9C27B0")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { unbindDevice(id) } }
        btnLayout1.addView(btnEdit); btnLayout1.addView(btnUnbind)

        val btnLayout2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 } }
        val btnBan = MaterialButton(this).apply { text = if(isBanned) "فك الحظر" else "حظر"; setBackgroundColor(Color.parseColor("#FF9800")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) }; setOnClickListener { toggleBanUser(id, !isBanned) } }
        val btnDelete = MaterialButton(this).apply { text = "حذف"; setBackgroundColor(Color.RED); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { showDeleteConfirmDialog(id) } }
        btnLayout2.addView(btnBan); btnLayout2.addView(btnDelete)

        card.addView(btnLayout1); card.addView(btnLayout2)
        container.addView(card)
    }

    private fun showEditDialog(id: String, oldName: String, oldPass: String, oldUsername: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val etName = EditText(this).apply { hint = "الاسم الجديد"; setText(oldName); setTextColor(Color.BLACK) }
        val etUsername = EditText(this).apply { hint = "المعرف (@) اختياري"; setText(oldUsername); setTextColor(Color.BLACK) }
        val etPass = EditText(this).apply { hint = "الرمز الجديد"; setText(oldPass); setTextColor(Color.BLACK) }
        layout.addView(etName); layout.addView(etUsername); layout.addView(etPass)

        AlertDialog.Builder(this).setTitle("تعديل بيانات $id").setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                val newUsername = etUsername.text.toString().trim().replace("@", "")
                if (newUsername.isNotEmpty() && !newUsername.matches(Regex("^[a-zA-Z0-9_.]{2,}\$"))) {
                    Toast.makeText(this, "المعرف غير صالح!", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL("$BASE_API_URL/admin/force_update").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                        val payload = JSONObject().apply { put("id", id); put("name", etName.text.toString()); put("password", etPass.text.toString()); put("username", newUsername) }
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode == 200) fetchAllUsers()
                    } catch (e: Exception) {}
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun unbindDevice(id: String) {
        AlertDialog.Builder(this).setTitle("مسح الأجهزة").setMessage("هل تريد السماح لهذا الحساب بتسجيل الدخول من جهاز آخر؟")
            .setPositiveButton("نعم") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL("$BASE_API_URL/admin/reset_device").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                        conn.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode == 200) {
                            withContext(Dispatchers.Main) { Toast.makeText(this@AdminDashboardActivity, "تم فك الجهاز بنجاح!", Toast.LENGTH_SHORT).show() }
                            fetchAllUsers()
                        }
                    } catch (e: Exception) {}
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun toggleBanUser(id: String, banStatus: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/admin/toggle_ban").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("id", id).put("banned", banStatus).toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) fetchAllUsers()
            } catch (e: Exception) {}
        }
    }

    private fun showDeleteConfirmDialog(id: String) {
        val input = EditText(this).apply { hint = "أدخل رمز الأدمن للتاكيد"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle("تحذير: حذف نهائي!").setMessage("لإثبات أنك أدمن، اكتب الرمز السري الأساسي للحذف:")
            .setView(input).setPositiveButton("حذف") { _, _ ->
                if (input.text.toString() == "mdMD@#$2002") { 
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL("$BASE_API_URL/admin/delete_user").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                            conn.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray(Charsets.UTF_8)) }
                            if (conn.responseCode == 200) fetchAllUsers()
                        } catch (e: Exception) {}
                    }
                } else Toast.makeText(this, "رمز الأدمن خاطئ!", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("إلغاء", null).show()
    }
}
