package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.handler.V2rayCrypt
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FileActiveUsersActivity : AppCompatActivity() {

    private var baseUrl: String = "https://education.ashor.shop"
    private lateinit var mainContainer: LinearLayout
    private lateinit var tvLoading: TextView
    private lateinit var etSearch: EditText
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var currentGuid: String = ""
    private var allLoadedUsers = JSONArray() 
    private var currentTabType = "ACTIVE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentGuid = intent.getStringExtra("guid") ?: ""
        baseUrl = intent.getStringExtra("apiUrl") ?: "https://education.ashor.shop"

        if (currentGuid.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات الملف", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }

        val header = TextView(this).apply {
            text = "إدارة المتصلين بالملف"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 40)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }

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
            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterUsers(s.toString()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

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
            text = "جاري تجميع بيانات المتصلين..."
            setTextColor(Color.parseColor("#FF9800"))
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 20)
            visibility = View.GONE
        }

        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setColorSchemeColors(Color.parseColor("#4CAF50"))
            setOnRefreshListener {
                loadUsers(currentTabType, isSilent = false)
            }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        scrollView.addView(mainContainer)
        swipeRefreshLayout.addView(scrollView)

        root.addView(header)
        root.addView(etSearch)
        root.addView(tabsLayout)
        root.addView(tvLoading)
        root.addView(swipeRefreshLayout)

        setContentView(root)

        btnActiveTab.setOnClickListener {
            currentTabType = "ACTIVE"
            etSearch.text.clear()
            btnActiveTab.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnActiveTab.setTextColor(Color.WHITE)
            btnBannedTab.setBackgroundColor(Color.parseColor("#252529"))
            btnBannedTab.setTextColor(Color.GRAY)
            loadUsers("ACTIVE", isSilent = false)
        }

        btnBannedTab.setOnClickListener {
            currentTabType = "BANNED"
            etSearch.text.clear()
            btnBannedTab.setBackgroundColor(Color.parseColor("#F44336"))
            btnBannedTab.setTextColor(Color.WHITE)
            btnActiveTab.setBackgroundColor(Color.parseColor("#252529"))
            btnActiveTab.setTextColor(Color.GRAY)
            loadUsers("BANNED", isSilent = false)
        }
    }

    override fun onResume() {
        super.onResume()
        loadUsers(currentTabType, isSilent = false)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0.0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1024) {
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }
        return String.format("%.2f MB", mb)
    }

    private fun loadUsers(type: String, isSilent: Boolean) {
        if (!isSilent && allLoadedUsers.length() == 0) {
            tvLoading.visibility = View.VISIBLE
            tvLoading.text = "جاري تجميع بيانات المتصلين..."
            mainContainer.removeAllViews()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allGuidsToFetch = mutableSetOf<String>()
                allGuidsToFetch.add(currentGuid) 

                val baseLicenseId = V2rayCrypt.getLicenseId(this@FileActiveUsersActivity, currentGuid)
                if (baseLicenseId.isNotEmpty() && baseLicenseId != "LEGACY") {
                    allGuidsToFetch.add(baseLicenseId) 
                }

                val myUserId = com.v2ray.ang.handler.AuthManager.getId(this@FileActiveUsersActivity)
                val myUserRole = com.v2ray.ang.handler.AuthManager.getRole(this@FileActiveUsersActivity)
                val isSuperAdmin = (myUserRole == "admin")
                val isAdmin = V2rayCrypt.isAdmin(this@FileActiveUsersActivity, currentGuid)
                val isOwner = (baseLicenseId == myUserId && myUserId.isNotEmpty()) || (currentGuid == myUserId)

                if (isOwner && myUserId.isNotEmpty()) {
                    allGuidsToFetch.add(myUserId) 
                }

                if (isAdmin || isSuperAdmin || isOwner) {
                    val subs = V2rayCrypt.getSubscribers(this@FileActiveUsersActivity, currentGuid)
                    subs.forEach { sub ->
                        if (sub.licenseId.isNotEmpty()) allGuidsToFetch.add(sub.licenseId)
                    }
                }

                val endpoint = if (type == "ACTIVE") "get_active" else "get_banned"
                val finalCombinedArray = JSONArray()

                val fetchJobs = allGuidsToFetch.toList().map { targetGuid ->
                    async {
                        try {
                            val encodedGuid = URLEncoder.encode(targetGuid, "UTF-8")
                            
                            var actualUsageBytes = 0L
                            try {
                                val checkConn = URL("$baseUrl/check?guid=$encodedGuid").openConnection() as HttpURLConnection
                                checkConn.connectTimeout = 4000
                                checkConn.readTimeout = 4000
                                if (checkConn.responseCode == 200) {
                                    val checkResp = BufferedReader(InputStreamReader(checkConn.inputStream)).readText()
                                    actualUsageBytes = JSONObject(checkResp).optLong("totalUsageBytes", 0L)
                                }
                            } catch(e:Exception){}

                            val url = URL("$baseUrl/file/$endpoint?guid=$encodedGuid")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 7000
                            conn.readTimeout = 7000
                            
                            if (conn.responseCode == 200) {
                                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText().trim()
                                if (resp.isNotBlank()) {
                                    var parsedArray: JSONArray? = null
                                    if (resp.startsWith("{")) {
                                        val jsonObj = JSONObject(resp)
                                        parsedArray = jsonObj.optJSONArray("data") ?: jsonObj.optJSONArray("users")
                                    } else if (resp.startsWith("[")) {
                                        parsedArray = JSONArray(resp)
                                    }

                                    if (parsedArray != null) {
                                        for (i in 0 until parsedArray.length()) {
                                            val obj = parsedArray.getJSONObject(i)
                                            obj.put("realUsageBytes", actualUsageBytes) 
                                        }
                                        return@async parsedArray
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                        return@async JSONArray()
                    }
                }

                fetchJobs.forEach { job ->
                    val resultArr = job.await()
                    for (i in 0 until resultArr.length()) {
                        finalCombinedArray.put(resultArr.getJSONObject(i))
                    }
                }

                val uniqueUsersMap = mutableMapOf<String, JSONObject>()
                for (i in 0 until finalCombinedArray.length()) {
                    val obj = finalCombinedArray.getJSONObject(i)
                    val devId = obj.optString("deviceId", "")
                    val currentUserId = obj.optString("userId", "")
                    
                    val uniqueKey = if (devId.isNotEmpty()) devId else "unknown_${System.currentTimeMillis()}_$i"
                    
                    if (uniqueUsersMap.containsKey(uniqueKey)) {
                        val existingUserId = uniqueUsersMap[uniqueKey]?.optString("userId", "") ?: ""
                        if (existingUserId.isEmpty() && currentUserId.isNotEmpty()) {
                            uniqueUsersMap[uniqueKey] = obj 
                        }
                    } else {
                        uniqueUsersMap[uniqueKey] = obj
                    }
                }
                
                val cleanUniqueArray = JSONArray()
                uniqueUsersMap.values.forEach { cleanUniqueArray.put(it) }

                withContext(Dispatchers.Main) {
                    allLoadedUsers = cleanUniqueArray
                    tvLoading.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                    
                    val currentSearch = etSearch.text.toString()
                    if (currentSearch.isEmpty()) {
                        mainContainer.removeAllViews()
                        renderUsersList(allLoadedUsers, type)
                    } else {
                        filterUsers(currentSearch)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    if (!isSilent) tvLoading.text = "تأكد من اتصالك بالإنترنت، ثم اسحب للتحديث" 
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

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

        val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
        val fallbackMainUsage = prefs.getString("usage_$currentGuid", "0.0 MB") ?: "0.0 MB"

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val isBanned = obj.optBoolean("isBanned", type == "BANNED")
            val hasActiveStory = obj.optBoolean("hasActiveStory", false)
            val userId = obj.optString("userId", "")
            val deviceId = obj.optString("deviceId", "")
            val name = obj.optString("name", "مجهول الهوية")
            val pfp = obj.optString("pfp", "")

            val serverUsageBytes = obj.optLong("realUsageBytes", obj.optLong("totalUsageBytes", 0L))
            var finalUsageStr = formatBytes(serverUsageBytes)

            if (serverUsageBytes == 0L && userId.isEmpty()) {
                finalUsageStr = fallbackMainUsage 
            }

            addUserCardOriginal(deviceId, name, userId, pfp, isBanned, type, hasActiveStory, finalUsageStr)
        }
    }

    private fun addUserCardOriginal(deviceId: String, name: String, userId: String, pfp: String, isBanned: Boolean, currentTab: String, hasActiveStory: Boolean, usageStr: String) {
        
        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
                setMargins(0, 0, 0, 20) 
            }
            radius = 25f
            cardElevation = 10f
            setCardBackgroundColor(Color.parseColor("#1C1C22"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val avatarContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { setMargins(20, 0, 0, 0) }
            
            if (hasActiveStory && userId.isNotEmpty()) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(5, Color.parseColor("#2196F3"))
                    setColor(Color.TRANSPARENT)
                }
                setPadding(6, 6, 6, 6)
                setOnClickListener {
                    try {
                        val intent = Intent(this@FileActiveUsersActivity, StoryViewerActivity::class.java)
                        intent.putExtra("userId", userId)
                        intent.putExtra("targetId", userId)
                        startActivity(intent)
                    } catch (e: Exception) {}
                }
            } else {
                background = null
                setPadding(0, 0, 0, 0)
            }
        }

        val cvAvatar = CardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            radius = 70f
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
        }

        val ivAvatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        if (pfp.isNotEmpty()) {
            try {
                val bytes = Base64.decode(pfp, Base64.DEFAULT)
                ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (e: Exception) { 
                ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(name, deviceId)) 
            }
        } else {
            ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(name, deviceId))
        }

        cvAvatar.addView(ivAvatar)
        avatarContainer.addView(cvAvatar)

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.RIGHT or Gravity.END
        }

        val tvName = TextView(this).apply {
            text = if (userId.isNotEmpty()) "👑 $name" else "👤 $name"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvUsage = TextView(this).apply {
            text = "📊 الاستهلاك: $usageStr"
            setTextColor(Color.parseColor("#00BCD4"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 5, 0, 0)
        }

        val tvId = TextView(this).apply {
            text = if (userId.isNotEmpty()) "ID: $userId" else "ID: (غير مسجل)"
            setTextColor(Color.parseColor("#FFC107")) 
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 5, 0, 0)
        }

        // 🌟 تعديل قسم الـ Device ID وإضافة زر الأجهزة 🌟
        val deviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.END
            setPadding(0, 5, 0, 0)
        }

        val tvDevice = TextView(this).apply {
            text = "📋 الجهاز: $deviceId"
            setTextColor(Color.parseColor("#9E9E9E")) 
            textSize = 11f
            
            isClickable = true
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                Toast.makeText(this@FileActiveUsersActivity, "تم نسخ Device ID", Toast.LENGTH_SHORT).show()
            }
        }
        
        deviceRow.addView(tvDevice)

        // إضافة أيقونة "عرض الأجهزة" في حال كان المتصل لديه حساب (User ID)
        if (userId.isNotEmpty()) {
            val btnAllDevices = TextView(this).apply {
                text = " 🔗 عرض الأجهزة"
                setTextColor(Color.parseColor("#2196F3"))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(20, 0, 0, 0)
                setOnClickListener {
                    showDevicesDialog(userId, name, deviceId)
                }
            }
            deviceRow.addView(btnAllDevices)
        }

        infoLayout.addView(tvName)
        infoLayout.addView(tvUsage)
        infoLayout.addView(tvId)
        infoLayout.addView(deviceRow) // تم استبدال tvDevice بـ deviceRow بالكامل

        val btnAction = MaterialButton(this).apply {
            text = if (isBanned) "إلغاء الحظر" else "حظر فوراً"
            setBackgroundColor(if (isBanned) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")) 
            setTextColor(Color.WHITE)
            textSize = 12f
            cornerRadius = 15
            setPadding(20, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 20, 0)
            }
            setOnClickListener {
                toggleBanStatus(deviceId, name, userId, pfp, !isBanned, currentTab)
            }
        }

        mainLayout.addView(avatarContainer)
        mainLayout.addView(infoLayout)
        mainLayout.addView(btnAction)

        cardView.addView(mainLayout)
        mainContainer.addView(cardView)
    }

    // 🌟 دالة عرض الأجهزة المنبثقة 🌟
    private fun showDevicesDialog(userId: String, userName: String, currentDeviceId: String) {
        val bottomSheet = BottomSheetDialog(this)
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }

        container.addView(TextView(this).apply {
            text = "الأجهزة المربوطة بحساب: $userName"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        val loadingText = TextView(this).apply {
            text = "جاري جلب بيانات الأجهزة..."
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        container.addView(loadingText)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val devicesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(devicesList)
        container.addView(scrollView)

        bottomSheet.setContentView(container)
        bottomSheet.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/auth/get_user?id=$userId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(resp)
                    
                    withContext(Dispatchers.Main) {
                        loadingText.visibility = View.GONE
                        val devices = json.optJSONArray("devices") ?: JSONArray()
                        
                        if (devices.length() == 0) devices.put(currentDeviceId) 
                        
                        for (i in 0 until devices.length()) {
                            val devId = devices.getString(i)
                            devicesList.addView(createDeviceRow(devId, devId == currentDeviceId))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingText.text = "فشل الاتصال بالسيرفر!"
                    loadingText.setTextColor(Color.parseColor("#F44336"))
                }
            }
        }
    }

    private fun createDeviceRow(deviceId: String, isCurrent: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#252529"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
            setPadding(30, 30, 30, 30)
        }

        val tvDevice = TextView(this).apply {
            text = if (isCurrent) "✅ $deviceId (النشط الآن)" else "📱 $deviceId"
            setTextColor(if (isCurrent) Color.parseColor("#4CAF50") else Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnCopy = TextView(this).apply {
            text = "نسخ"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 20, 20, 20)
            
            isClickable = true
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                Toast.makeText(context, "تم نسخ أيدي الجهاز!", Toast.LENGTH_SHORT).show()
            }
        }

        row.addView(tvDevice)
        row.addView(btnCopy)
        return row
    }

    private fun toggleBanStatus(deviceId: String, name: String, userId: String, pfp: String, banStatus: Boolean, currentTab: String) {
        val actionName = if (banStatus) "حظر وطرد" else "إلغاء حظر"
        AlertDialog.Builder(this)
            .setTitle("تأكيد العملية")
            .setMessage("هل أنت متأكد أنك تريد $actionName هذا المستخدم؟\n(سيتم فصل اتصاله فوراً ومنعه من الدخول)")
            .setPositiveButton("نعم") { _, _ ->
                tvLoading.visibility = View.VISIBLE
                tvLoading.text = "جاري تنفيذ الأمر..."
                
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL("$baseUrl/file/toggle_ban").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true

                        var baseLicenseId = V2rayCrypt.getLicenseId(this@FileActiveUsersActivity, currentGuid)
                        if (baseLicenseId.isEmpty() || baseLicenseId == "LEGACY") {
                            baseLicenseId = currentGuid
                        }
                        
                        val targetGuid = if (userId.isNotEmpty()) userId else baseLicenseId 

                        val payload = JSONObject()
                            .put("guid", targetGuid) 
                            .put("deviceId", deviceId)
                            .put("banStatus", banStatus)
                            .put("name", name)
                            .put("userId", userId)
                            .put("pfp", pfp)

                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        
                        val responseOk = conn.responseCode == 200

                        if (responseOk && banStatus) {
                            try {
                                val pingPayload = JSONObject()
                                    .put("guid", targetGuid)
                                    .put("deviceId", deviceId)
                                    .put("userId", userId)
                                    .put("disconnect", true) 
                                
                                val pingConn = URL("$baseUrl/file/ping").openConnection() as HttpURLConnection
                                pingConn.requestMethod = "POST"
                                pingConn.setRequestProperty("Content-Type", "application/json")
                                pingConn.doOutput = true
                                pingConn.outputStream.use { it.write(pingPayload.toString().toByteArray(Charsets.UTF_8)) }
                                pingConn.responseCode
                            } catch (e: Exception) {}
                        }

                        if (responseOk) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@FileActiveUsersActivity, "تم التنفيذ والطرد بنجاح!", Toast.LENGTH_SHORT).show()
                                loadUsers(currentTab, isSilent = false) 
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@FileActiveUsersActivity, "فشل التنفيذ، حاول مرة أخرى", Toast.LENGTH_SHORT).show()
                                tvLoading.visibility = View.GONE
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FileActiveUsersActivity, "خطأ في الاتصال", Toast.LENGTH_SHORT).show()
                            tvLoading.visibility = View.GONE
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
