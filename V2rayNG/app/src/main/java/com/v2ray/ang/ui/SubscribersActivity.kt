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
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.NetworkTime
import com.v2ray.ang.handler.V2rayCrypt
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class SubscribersActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var recycler: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvEmptyState: TextView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var parentGuid: String
    private var allSubscribers = listOf<V2rayCrypt.SubscriberData>()
    private lateinit var adapter: SubscribersAdapter

    private var pendingEncryptedConfigToSave: String? = null

    private val saveEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            try {
                val content = pendingEncryptedConfigToSave
                if (!content.isNullOrEmpty()) {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Toast.makeText(this, "تم حفظ الملف بنجاح!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { Toast.makeText(this, "حدث خطأ أثناء الحفظ", Toast.LENGTH_SHORT).show() }
        }
        pendingEncryptedConfigToSave = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscribers)

        parentGuid = intent.getStringExtra("parentGuid") ?: return finish()

        toolbar = findViewById(R.id.toolbar)
        etSearch = findViewById(R.id.et_search)
        recycler = findViewById(R.id.recycler_subscribers)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        
        swipeRefresh = findViewById(R.id.swipe_refresh)

        toolbar.setNavigationOnClickListener { finish() }

        swipeRefresh.setColorSchemeColors(Color.parseColor("#4CAF50"))
        swipeRefresh.setOnRefreshListener {
            syncSubscribersFromCloud(isManualRefresh = true)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        
        adapter = SubscribersAdapter(
            apiUrl = BASE_API_URL,
            onExtend = { sub -> showExtendDialog(sub) },
            onShare = { sub -> shareSubscriber(sub) },
            onDelete = { sub -> deleteSubscriber(sub) },
            onEdit = { sub -> showEditDialog(sub) } 
        )
        recycler.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        loadSubscribers()
        syncSubscribersFromCloud(isManualRefresh = false)
    }

    private fun loadSubscribers() {
        allSubscribers = V2rayCrypt.getSubscribers(this, parentGuid)
        filterList(etSearch.text.toString())
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0.0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1024) {
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }
        return String.format("%.1f MB", mb)
    }

    private fun syncSubscribersFromCloud(isManualRefresh: Boolean) {
        if (isManualRefresh) swipeRefresh.isRefreshing = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            if (allSubscribers.isEmpty()) {
                withContext(Dispatchers.Main) { swipeRefresh.isRefreshing = false }
                return@launch
            }

            var isChanged = false
            val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)

            val deferreds = allSubscribers.map { sub ->
                async {
                    try {
                        val connCheck = URL("$BASE_API_URL/check?guid=${sub.licenseId}").openConnection() as HttpURLConnection
                        connCheck.requestMethod = "GET"
                        connCheck.connectTimeout = 4000
                        connCheck.readTimeout = 4000
                        
                        if (connCheck.responseCode == 200) {
                            val data = JSONObject(BufferedReader(InputStreamReader(connCheck.inputStream)).readText())
                            val exp = data.optLong("expiryTime", -1L)
                            val actCount = data.optInt("activeCount", 0) 
                            val totalUsageBytes = data.optLong("totalUsageBytes", 0L) 
                            
                            // 🌟 سحب أرقام التفاعل من السيرفر 🌟
                            val views = data.optInt("views", 0)
                            val likes = data.optInt("likes", 0)
                            val comments = data.optInt("comments", 0)
                            
                            if (exp >= 0L) {
                                V2rayCrypt.updateSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId, exp, actCount)
                                
                                val baseline = prefs.getLong("baseline_${sub.licenseId}", 0L)
                                val actualUsage = max(0L, totalUsageBytes - baseline)
                                
                                prefs.edit()
                                    .putLong("raw_usage_${sub.licenseId}", totalUsageBytes)
                                    .putString("usage_${sub.licenseId}", formatBytes(actualUsage))
                                    .putInt("views_${sub.licenseId}", views)
                                    .putInt("likes_${sub.licenseId}", likes)
                                    .putInt("comments_${sub.licenseId}", comments)
                                    .apply()
                                    
                                isChanged = true
                            }
                        }

                        val connAuth = URL("$BASE_API_URL/auth/get_user?id=${sub.licenseId}").openConnection() as HttpURLConnection
                        connAuth.requestMethod = "GET"
                        connAuth.connectTimeout = 4000
                        connAuth.readTimeout = 4000

                        if (connAuth.responseCode == 200) {
                            val authData = JSONObject(BufferedReader(InputStreamReader(connAuth.inputStream)).readText())
                            if (authData.getBoolean("success")) {
                                prefs.edit()
                                    .putString("name_${sub.licenseId}", authData.getString("name"))
                                    .putString("pfp_${sub.licenseId}", authData.optString("pfp", ""))
                                    .putBoolean("story_${sub.licenseId}", authData.optBoolean("hasActiveStory", false))
                                    .putBoolean("verified_${sub.licenseId}", authData.optBoolean("isVerified", false))
                                    .apply()
                                isChanged = true
                            }
                        }

                    } catch (e: Exception) {}
                }
            }
            
            deferreds.awaitAll()

            withContext(Dispatchers.Main) { 
                if (isChanged) loadSubscribers() 
                swipeRefresh.isRefreshing = false
                if (isManualRefresh) Toast.makeText(this@SubscribersActivity, "تم تحديث بيانات المشتركين! ☁️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterList(query: String) {
        val filtered = if (query.isEmpty()) allSubscribers else allSubscribers.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submitList(filtered)
        tvEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    fun showDevicesDialog(userId: String, userName: String) {
        val bottomSheet = BottomSheetDialog(this)
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }

        container.addView(TextView(this).apply {
            text = "الأجهزة المربوطة بـ: $userName"
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

        bottomSheet.setContentView(container)
        bottomSheet.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$BASE_API_URL/auth/get_user?id=$userId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(resp)
                    
                    withContext(Dispatchers.Main) {
                        container.removeView(loadingText)
                        val devices = json.optJSONArray("devices") ?: JSONArray()
                        
                        if (devices.length() == 0) {
                            container.addView(TextView(this@SubscribersActivity).apply {
                                text = "لا توجد أجهزة مرتبطة حالياً"
                                setTextColor(Color.GRAY)
                                gravity = Gravity.CENTER
                                setPadding(0, 20, 0, 20)
                            })
                        } else {
                            for (i in 0 until devices.length()) {
                                val devId = devices.getString(i)
                                container.addView(createDeviceRow(devId))
                            }
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

    private fun createDeviceRow(deviceId: String): View {
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
            text = "📱 $deviceId"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnCopy = TextView(this).apply {
            text = "نسخ"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true

            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                Toast.makeText(this@SubscribersActivity, "تم نسخ أيدي الجهاز!", Toast.LENGTH_SHORT).show()
            }
        }

        row.addView(tvDevice)
        row.addView(btnCopy)
        return row
    }

    private fun showEditDialog(sub: V2rayCrypt.SubscriberData) {
        AlertDialog.Builder(this).setTitle("إدارة المشترك")
            .setItems(arrayOf("تغيير اسم المشترك محلياً", "استبدال السيرفر للمشترك (من الحافظة)", "تصفير عداد الاستهلاك 🔄")) { _, which ->
                when (which) { 
                    0 -> showRenameDialog(sub) 
                    1 -> replaceSubscriberConfig(sub) 
                    2 -> resetSubscriberUsage(sub) 
                }
            }.show()
    }

    private fun resetSubscriberUsage(sub: V2rayCrypt.SubscriberData) {
        val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
        val rawUsage = prefs.getLong("raw_usage_${sub.licenseId}", 0L) 
        
        prefs.edit()
            .putLong("baseline_${sub.licenseId}", rawUsage) 
            .putString("usage_${sub.licenseId}", "0.0 MB") 
            .apply()
            
        loadSubscribers()
        Toast.makeText(this, "تم تصفير عداد الاستهلاك للمشترك بنجاح!", Toast.LENGTH_SHORT).show()
    }

    private fun showRenameDialog(sub: V2rayCrypt.SubscriberData) {
        val input = EditText(this).apply { setText(sub.name); setTextColor(Color.BLACK); setHintTextColor(Color.GRAY) }
        AlertDialog.Builder(this).setTitle("تغيير الاسم محلياً").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val prefs = getSharedPreferences("V2rayProtectedConfigs", Context.MODE_PRIVATE)
                    val key = "Subscribers_$parentGuid"
                    try {
                        val jsonArray = JSONArray(prefs.getString(key, "[]") ?: "[]")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            if (obj.getString("licenseId") == sub.licenseId) { obj.put("name", newName); break }
                        }
                        prefs.edit().putString(key, jsonArray.toString()).apply()
                        loadSubscribers()
                    } catch (e: Exception) {}
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun replaceSubscriberConfig(sub: V2rayCrypt.SubscriberData) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val newConf = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (newConf.isEmpty() || !newConf.contains("://")) { Toast.makeText(this, "الحافظة لا تحتوي على كود سيرفر صالح!", Toast.LENGTH_LONG).show(); return }

        Toast.makeText(this, "جاري رفع الكود...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val conn = URL("$BASE_API_URL/admin/upload_config").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject()
                    .put("licenseId", sub.licenseId)
                    .put("expiryTime", sub.expiryTimeMs)
                    .put("configData", newConf)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) success = true
            } catch (e: Exception) {}
            
            withContext(Dispatchers.Main) { Toast.makeText(this@SubscribersActivity, if (success) "تم استبدال السيرفر بنجاح!" else "فشل الاتصال.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showExtendDialog(sub: V2rayCrypt.SubscriberData) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val titleView = TextView(this).apply { text = "إدارة وقت المشترك"; textSize = 18f; setTextColor(Color.parseColor("#2196F3")); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 30); gravity = Gravity.CENTER }
        layout.addView(titleView)
        val monthsInput = EditText(this).apply { hint = "عدد الأشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        val daysInput = EditText(this).apply { hint = "عدد الأيام"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        val hoursInput = EditText(this).apply { hint = "عدد الساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(monthsInput); layout.addView(daysInput); layout.addView(hoursInput)

        val builder = AlertDialog.Builder(this)
        builder.setView(layout)
        
        builder.setPositiveButton("تمديد") { dialog, _ ->
            val totalMs = ((monthsInput.text.toString().toLongOrNull() ?: 0L) * 30L * 24L * 60L * 60L * 1000L) + ((daysInput.text.toString().toLongOrNull() ?: 0L) * 24L * 60L * 60L * 1000L) + ((hoursInput.text.toString().toLongOrNull() ?: 0L) * 60L * 60L * 1000L)
            if (totalMs > 0L) {
                val newExpiry = NetworkTime.currentTimeMillis(this) + totalMs
                Toast.makeText(this, "جاري التحديث...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    var success = false
                    try {
                        val conn = URL("$BASE_API_URL/admin/update_expiry").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        val payload = JSONObject().put("licenseId", sub.licenseId).put("expiryTime", newExpiry)
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode == 200) success = true
                    } catch (e: Exception) {}

                    withContext(Dispatchers.Main) {
                        if (success) {
                            V2rayCrypt.updateSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId, newExpiry, sub.activeCount)
                            loadSubscribers()
                            Toast.makeText(this@SubscribersActivity, "تم التمديد بنجاح!", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(this@SubscribersActivity, "فشل الاتصال", Toast.LENGTH_SHORT).show()
                    }
                }
            } else Toast.makeText(this, "الرجاء إدخال وقت صحيح", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        builder.setNeutralButton("إيقاف الكود") { dialog, _ ->
            Toast.makeText(this, "جاري الإيقاف...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val expiredTime = NetworkTime.currentTimeMillis(this@SubscribersActivity) - 10000L
                var success = false
                try {
                    val conn = URL("$BASE_API_URL/admin/update_expiry").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = JSONObject().put("licenseId", sub.licenseId).put("expiryTime", expiredTime)
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode == 200) success = true
                } catch (e: Exception) {}

                withContext(Dispatchers.Main) {
                    if (success) {
                        V2rayCrypt.updateSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId, expiredTime, 0)
                        loadSubscribers()
                        Toast.makeText(this@SubscribersActivity, "تم إيقاف المشترك!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("إلغاء", null).show()
    }

    private fun shareSubscriber(sub: V2rayCrypt.SubscriberData) {
        if (AngConfigManager.share2Clipboard(this, parentGuid) == 0) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (conf.isNotEmpty()) {
                val encryptedConf = V2rayCrypt.encrypt(conf, sub.expiryTimeMs, sub.licenseId)
                AlertDialog.Builder(this).setTitle("مشاركة المشترك")
                    .setItems(arrayOf("نسخ إلى الحافظة", "تصدير كملف")) { _, which ->
                        when (which) {
                            0 -> { clipboard.setPrimaryClip(ClipData.newPlainText("Config", encryptedConf)); Toast.makeText(this, "تم نسخ الكود!", Toast.LENGTH_SHORT).show() }
                            1 -> { pendingEncryptedConfigToSave = encryptedConf; saveEncryptedFileLauncher.launch("${sub.name.replace(" ", "_")}.ashor") }
                        }
                    }.show()
            }
        }
    }

    private fun deleteSubscriber(sub: V2rayCrypt.SubscriberData) {
        AlertDialog.Builder(this).setTitle("حذف المشترك").setMessage("هل أنت متأكد؟ سيتم قطع الاتصال فوراً.")
            .setPositiveButton("نعم، احذف") { _, _ ->
                Toast.makeText(this, "جاري الحذف...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val expiredTime = NetworkTime.currentTimeMillis(this@SubscribersActivity) - 10000L
                    try {
                        val conn = URL("$BASE_API_URL/admin/update_expiry").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        val payload = JSONObject().put("licenseId", sub.licenseId).put("expiryTime", expiredTime)
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } catch (e: Exception) {}
                    
                    withContext(Dispatchers.Main) {
                        V2rayCrypt.removeSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId)
                        loadSubscribers(); Toast.makeText(this@SubscribersActivity, "تم حذف المشترك!", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("إلغاء", null).show()
    }
}

class SubscribersAdapter(
    private val apiUrl: String, 
    private val onExtend: (V2rayCrypt.SubscriberData) -> Unit,
    private val onShare: (V2rayCrypt.SubscriberData) -> Unit,
    private val onDelete: (V2rayCrypt.SubscriberData) -> Unit,
    private val onEdit: (V2rayCrypt.SubscriberData) -> Unit
) : RecyclerView.Adapter<SubscribersAdapter.SubViewHolder>() {

    private var list = listOf<V2rayCrypt.SubscriberData>()

    fun submitList(newList: List<V2rayCrypt.SubscriberData>) { list = newList; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubViewHolder {
        return SubViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_subscriber, parent, false))
    }

    override fun onBindViewHolder(holder: SubViewHolder, position: Int) { holder.bind(list[position], apiUrl, onExtend, onShare, onDelete, onEdit) }

    override fun getItemCount() = list.size

    override fun onViewRecycled(holder: SubViewHolder) { super.onViewRecycled(holder); holder.cancelTimer() }

    class SubViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_sub_name)
        val tvExpiry: TextView = view.findViewById(R.id.tv_sub_expiry)
        val tvActiveCount: TextView = view.findViewById(R.id.tv_active_count)
        val btnExtend: View = view.findViewById(R.id.btn_extend)
        val btnShare: View = view.findViewById(R.id.btn_share)
        val btnDelete: View = view.findViewById(R.id.btn_delete)
        val btnEdit: View? = view.findViewById(R.id.btn_edit) 
        
        val ivAvatar: ImageView = view.findViewById(R.id.iv_sub_avatar)
        val cvStoryRing: CardView = view.findViewById(R.id.cv_story_ring)
        val flAvatarContainer: FrameLayout = view.findViewById(R.id.fl_avatar_container)
        val tvDataUsage: TextView? = view.findViewById(R.id.tv_data_usage)

        private var timerJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun bind(
            item: V2rayCrypt.SubscriberData,
            apiUrl: String,
            onExtend: (V2rayCrypt.SubscriberData) -> Unit,
            onShare: (V2rayCrypt.SubscriberData) -> Unit,
            onDelete: (V2rayCrypt.SubscriberData) -> Unit,
            onEdit: (V2rayCrypt.SubscriberData) -> Unit
        ) {
            val prefs = itemView.context.getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
            
            val realName = prefs.getString("name_${item.licenseId}", item.name) ?: item.name
            val pfp = prefs.getString("pfp_${item.licenseId}", "") ?: ""
            val isVerified = prefs.getBoolean("verified_${item.licenseId}", false)
            val hasStory = prefs.getBoolean("story_${item.licenseId}", false)
            val usage = prefs.getString("usage_${item.licenseId}", "0.0 MB") ?: "0.0 MB"

            // 🌟 استدعاء أرقام التفاعل من الذاكرة 🌟
            val viewsCount = prefs.getInt("views_${item.licenseId}", 0)
            val likesCount = prefs.getInt("likes_${item.licenseId}", 0)
            val commentsCount = prefs.getInt("comments_${item.licenseId}", 0)

            tvName.text = if (isVerified) "$realName ☑️" else realName
            
            tvName.setOnClickListener {
                if (itemView.context is SubscribersActivity) {
                    (itemView.context as SubscribersActivity).showDevicesDialog(item.licenseId, realName)
                }
            }
            
            if (pfp.isNotEmpty()) {
                try {
                    val b = Base64.decode(if (pfp.contains(",")) pfp.substringAfter(",") else pfp, Base64.DEFAULT)
                    ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                } catch (e: Exception) {
                    ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(realName, item.licenseId, 120))
                }
            } else {
                ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(realName, item.licenseId, 120))
            }
            
            cvStoryRing.setCardBackgroundColor(Color.TRANSPARENT)
            
            if (hasStory) {
                flAvatarContainer.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(5, Color.parseColor("#2196F3"))
                    setColor(Color.TRANSPARENT)
                }
                flAvatarContainer.setPadding(8, 8, 8, 8)
                flAvatarContainer.setOnClickListener {
                    try {
                        val intent = Intent(itemView.context, Class.forName("com.v2ray.ang.ui.StoryViewerActivity"))
                        intent.putExtra("targetUserId", item.licenseId)
                        intent.putExtra("userId", item.licenseId)
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) { Toast.makeText(itemView.context, "الاستوري غير متوفر", Toast.LENGTH_SHORT).show() }
                }
            } else {
                flAvatarContainer.background = null
                flAvatarContainer.setPadding(0, 0, 0, 0)
                flAvatarContainer.setOnClickListener {
                    try {
                        val intent = Intent(itemView.context, Class.forName("com.v2ray.ang.ui.UserProfileActivity"))
                        intent.putExtra("targetUserId", item.licenseId)
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {}
                }
            }

            tvDataUsage?.text = "استهلاك: $usage"
            tvDataUsage?.visibility = View.VISIBLE

            tvActiveCount.text = "نشط الآن: 🟢 ${item.activeCount}"
            tvActiveCount.setOnClickListener {
                try {
                    val intent = Intent(itemView.context, Class.forName("com.v2ray.ang.ui.FileActiveUsersActivity"))
                    intent.putExtra("guid", item.licenseId)
                    intent.putExtra("apiUrl", apiUrl) 
                    itemView.context.startActivity(intent)
                } catch (e: Exception) {}
            }

            // 🌟 تصميم شريط التفاعل برمجياً مع تكبير الخطوط 🌟
            val parentLayout = tvActiveCount.parent as? ViewGroup
            if (parentLayout != null) {
                var socialBar = parentLayout.findViewWithTag<LinearLayout>("social_bar_${item.licenseId}")
                if (socialBar == null) {
                    socialBar = LinearLayout(itemView.context).apply {
                        tag = "social_bar_${item.licenseId}"
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 15, 0, 15) // هوامش أكبر
                        }
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    // 👁️ المشاهدات
                    val tvViews = TextView(itemView.context).apply {
                        text = "👁️ $viewsCount"
                        setTextColor(Color.LTGRAY)
                        textSize = 15f // خط كبير
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 50, 0)
                    }

                    // ❤️ اللايكات
                    val tvLikes = TextView(itemView.context).apply {
                        text = "❤️ $likesCount"
                        setTextColor(Color.LTGRAY)
                        textSize = 15f // خط كبير
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 50, 0)
                        
                        // 🌟 النقر يفتح المعجبين 🌟
                        setOnClickListener {
                            try {
                                val intent = Intent(itemView.context, Class.forName("com.v2ray.ang.ui.ConnectionsActivity"))
                                intent.putExtra("targetUserId", item.licenseId)
                                intent.putExtra("type", "likers") // تفعيل قائمة المعجبين
                                itemView.context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(itemView.context, "حدث خطأ أثناء الفتح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    // 💬 التعليقات
                    val tvComments = TextView(itemView.context).apply {
                        text = "💬 $commentsCount"
                        setTextColor(Color.LTGRAY)
                        textSize = 15f // خط كبير
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        
                        setOnClickListener {
                            try {
                                val intent = Intent(itemView.context, Class.forName("com.v2ray.ang.ui.CommentsActivity"))
                                intent.putExtra("guid", item.licenseId)
                                intent.putExtra("isOwnerOrAdmin", true) 
                                itemView.context.startActivity(intent)
                            } catch (e: Exception) {}
                        }
                    }

                    socialBar.addView(tvViews)
                    socialBar.addView(tvLikes)
                    socialBar.addView(tvComments)

                    val index = parentLayout.indexOfChild(tvActiveCount)
                    parentLayout.addView(socialBar, index + 1)
                } else {
                    // تحديث الأرقام بوضوح أكبر
                    (socialBar.getChildAt(0) as? TextView)?.text = "👁️ $viewsCount"
                    (socialBar.getChildAt(1) as? TextView)?.text = "❤️ $likesCount"
                    (socialBar.getChildAt(2) as? TextView)?.text = "💬 $commentsCount"
                }
            }
            
            btnExtend.setOnClickListener { onExtend(item) }
            btnShare.setOnClickListener { onShare(item) }
            btnDelete.setOnClickListener { onDelete(item) }
            btnEdit?.setOnClickListener { onEdit(item) }

            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    val currentTime = NetworkTime.currentTimeMillis(itemView.context)
                    val diffMs = item.expiryTimeMs - currentTime
                    
                    if (diffMs > 0) {
                        val d = diffMs / 86400000L
                        val h = (diffMs % 86400000L) / 3600000L
                        val m = (diffMs % 3600000L) / 60000L
                        
                        val timeText = when {
                            d > 0 -> "$d يوم"
                            h > 0 -> "$h ساعة"
                            m > 0 -> "$m دقيقة"
                            else -> "أقل من دقيقة"
                        }
                        
                        tvExpiry.text = timeText
                        tvExpiry.setTextColor(Color.parseColor("#4CAF50"))
                    } else {
                        tvExpiry.text = "منتهي الصلاحية 🛑"
                        tvExpiry.setTextColor(Color.parseColor("#E53935"))
                    }
                    delay(60000L) 
                }
            }
        }
        fun cancelTimer() { timerJob?.cancel() }
    }
}
