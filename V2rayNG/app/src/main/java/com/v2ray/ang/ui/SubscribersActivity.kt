package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.CloudflareAPI
import com.v2ray.ang.handler.NetworkTime
import com.v2ray.ang.handler.V2rayCrypt
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class SubscribersActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvEmptyState: TextView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    private lateinit var parentGuid: String
    private var allSubscribers = listOf<V2rayCrypt.SubscriberData>()
    private lateinit var adapter: SubscribersAdapter

    private var pendingEncryptedConfigToSave: String? = null

    private val saveEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            try {
                val content = pendingEncryptedConfigToSave
                if (!content.isNullOrEmpty()) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(this, "تم حفظ الملف بنجاح!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "حدث خطأ أثناء الحفظ", Toast.LENGTH_SHORT).show()
            }
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

        toolbar.setNavigationOnClickListener { finish() }

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SubscribersAdapter(
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
        syncSubscribersFromCloud()
    }

    private fun loadSubscribers() {
        allSubscribers = V2rayCrypt.getSubscribers(this, parentGuid)
        filterList(etSearch.text.toString())
    }

    private fun syncSubscribersFromCloud() {
        lifecycleScope.launch(Dispatchers.IO) {
            var isChanged = false
            allSubscribers.forEach { sub ->
                val cloudData = CloudflareAPI.checkLiveConfig(sub.licenseId)
                if (cloudData.first >= 0L && cloudData.first != sub.expiryTimeMs) {
                    V2rayCrypt.updateSubscriberExpiryLocally(this@SubscribersActivity, parentGuid, sub.licenseId, cloudData.first)
                    isChanged = true
                }
            }
            if (isChanged) {
                withContext(Dispatchers.Main) { loadSubscribers() }
            }
        }
    }

    private fun filterList(query: String) {
        val filtered = if (query.isEmpty()) {
            allSubscribers
        } else {
            allSubscribers.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
        tvEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(sub: V2rayCrypt.SubscriberData) {
        val options = arrayOf("تغيير اسم المشترك", "استبدال السيرفر للمشترك (من الحافظة)")
        AlertDialog.Builder(this)
            .setTitle("تعديل: ${sub.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(sub)
                    1 -> replaceSubscriberConfig(sub)
                }
            }.show()
    }

    private fun showRenameDialog(sub: V2rayCrypt.SubscriberData) {
        val input = EditText(this).apply {
            setText(sub.name)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("تغيير الاسم")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val prefs = getSharedPreferences("V2rayProtectedConfigs", Context.MODE_PRIVATE)
                    val key = "Subscribers_$parentGuid"
                    val currentListJson = prefs.getString(key, "[]") ?: "[]"
                    try {
                        val jsonArray = JSONArray(currentListJson)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            if (obj.getString("licenseId") == sub.licenseId) {
                                obj.put("name", newName)
                                break
                            }
                        }
                        prefs.edit().putString(key, jsonArray.toString()).apply()
                        loadSubscribers()
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun replaceSubscriberConfig(sub: V2rayCrypt.SubscriberData) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val newConf = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""

        if (newConf.isEmpty() || !newConf.contains("://")) {
            Toast.makeText(this, "الحافظة لا تحتوي على كود سيرفر صالح!", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "جاري رفع الكود الجديد للمشترك...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = CloudflareAPI.createOrUpdateSubscriber(sub.licenseId, sub.expiryTimeMs, newConf)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@SubscribersActivity, "تم استبدال سيرفر المشترك بنجاح!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SubscribersActivity, "فشل الاتصال بكلاود فلير.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showExtendDialog(sub: V2rayCrypt.SubscriberData) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val titleView = TextView(this).apply {
            text = "إدارة وقت: ${sub.name}"
            textSize = 18f
            setTextColor(Color.parseColor("#2196F3"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        val monthsInput = EditText(this).apply { hint = "عدد الأشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(monthsInput)

        val daysInput = EditText(this).apply { hint = "عدد الأيام"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(daysInput)

        val hoursInput = EditText(this).apply { hint = "عدد الساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(hoursInput)

        val builder = AlertDialog.Builder(this)
        builder.setView(layout)
        builder.setPositiveButton("تمديد") { dialog, _ ->
            val m = monthsInput.text.toString().toLongOrNull() ?: 0L
            val d = daysInput.text.toString().toLongOrNull() ?: 0L
            val h = hoursInput.text.toString().toLongOrNull() ?: 0L

            val totalMs = (m * 30L * 24L * 60L * 60L * 1000L) + (d * 24L * 60L * 60L * 1000L) + (h * 60L * 60L * 1000L)

            if (totalMs > 0L) {
                val newExpiry = NetworkTime.currentTimeMillis(this) + totalMs
                Toast.makeText(this, "جاري التحديث السحابي...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = CloudflareAPI.updateExpiry(sub.licenseId, newExpiry)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            V2rayCrypt.updateSubscriberExpiryLocally(this@SubscribersActivity, parentGuid, sub.licenseId, newExpiry)
                            loadSubscribers()
                            Toast.makeText(this@SubscribersActivity, "تم التمديد بنجاح!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SubscribersActivity, "فشل الاتصال", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "الرجاء إدخال وقت صحيح", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        
        // --- زر إيقاف الكود الفوري موجود هنا! ---
        builder.setNeutralButton("إيقاف الكود") { dialog, _ ->
            Toast.makeText(this, "جاري الإيقاف...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val expiredTime = NetworkTime.currentTimeMillis(this@SubscribersActivity) - 10000L
                val success = CloudflareAPI.updateExpiry(sub.licenseId, expiredTime)
                withContext(Dispatchers.Main) {
                    if (success) {
                        V2rayCrypt.updateSubscriberExpiryLocally(this@SubscribersActivity, parentGuid, sub.licenseId, expiredTime)
                        loadSubscribers()
                        Toast.makeText(this@SubscribersActivity, "تم إيقاف المشترك!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("إلغاء", null)
        builder.show()
    }

    private fun shareSubscriber(sub: V2rayCrypt.SubscriberData) {
        if (AngConfigManager.share2Clipboard(this, parentGuid) == 0) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (conf.isNotEmpty()) {
                val encryptedConf = V2rayCrypt.encrypt(conf, sub.expiryTimeMs, sub.licenseId)
                
                val options = arrayOf("نسخ إلى الحافظة", "تصدير كملف")
                AlertDialog.Builder(this)
                    .setTitle("مشاركة المشترك: ${sub.name}")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val clip = ClipData.newPlainText("Config", encryptedConf)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this, "تم نسخ كود المشترك!", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                pendingEncryptedConfigToSave = encryptedConf
                                saveEncryptedFileLauncher.launch("${sub.name.replace(" ", "_")}.ashor")
                            }
                        }
                    }.show()
            }
        }
    }

    private fun deleteSubscriber(sub: V2rayCrypt.SubscriberData) {
        AlertDialog.Builder(this)
            .setTitle("حذف المشترك")
            .setMessage("هل أنت متأكد؟ سيتم قطع الاتصال عن المشترك فوراً وحذفه نهائياً.")
            .setPositiveButton("نعم، احذف") { _, _ ->
                Toast.makeText(this, "جاري الحذف...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val expiredTime = NetworkTime.currentTimeMillis(this@SubscribersActivity) - 10000L
                    CloudflareAPI.updateExpiry(sub.licenseId, expiredTime)
                    
                    withContext(Dispatchers.Main) {
                        V2rayCrypt.removeSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId)
                        loadSubscribers()
                        Toast.makeText(this@SubscribersActivity, "تم حذف المشترك!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

class SubscribersAdapter(
    private val onExtend: (V2rayCrypt.SubscriberData) -> Unit,
    private val onShare: (V2rayCrypt.SubscriberData) -> Unit,
    private val onDelete: (V2rayCrypt.SubscriberData) -> Unit,
    private val onEdit: (V2rayCrypt.SubscriberData) -> Unit
) : RecyclerView.Adapter<SubscribersAdapter.SubViewHolder>() {

    private var list = listOf<V2rayCrypt.SubscriberData>()

    fun submitList(newList: List<V2rayCrypt.SubscriberData>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subscriber, parent, false)
        return SubViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubViewHolder, position: Int) {
        val item = list[position]
        holder.bind(item, onExtend, onShare, onDelete, onEdit)
    }

    override fun getItemCount() = list.size

    override fun onViewRecycled(holder: SubViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelTimer() 
    }

    class SubViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_sub_name)
        val tvExpiry: TextView = view.findViewById(R.id.tv_sub_expiry)
        val btnExtend: View = view.findViewById(R.id.btn_extend)
        val btnShare: View = view.findViewById(R.id.btn_share)
        val btnDelete: View = view.findViewById(R.id.btn_delete)
        val btnEdit: View? = view.findViewById(R.id.btn_edit) 
        
        private var timerJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun bind(
            item: V2rayCrypt.SubscriberData,
            onExtend: (V2rayCrypt.SubscriberData) -> Unit,
            onShare: (V2rayCrypt.SubscriberData) -> Unit,
            onDelete: (V2rayCrypt.SubscriberData) -> Unit,
            onEdit: (V2rayCrypt.SubscriberData) -> Unit
        ) {
            tvName.text = item.name
            
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
                        val s = (diffMs % 60000L) / 1000L
                        
                        val timeStr = buildString {
                            if (d > 0) append("$d يوم و ")
                            if (h > 0 || d > 0) append("$h ساعة و ")
                            append("$m دقيقة و $s ثانية")
                        }
                        
                        tvExpiry.text = timeStr
                        tvExpiry.setTextColor(Color.parseColor("#4CAF50"))
                    } else {
                        tvExpiry.text = "منتهي الصلاحية 🛑"
                        tvExpiry.setTextColor(Color.parseColor("#E53935"))
                    }
                    delay(1000L)
                }
            }
        }

        fun cancelTimer() {
            timerJob?.cancel()
        }
    }
}
