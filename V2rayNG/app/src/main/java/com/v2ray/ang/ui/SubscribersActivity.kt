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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            onDelete = { sub -> deleteSubscriber(sub) }
        )
        recycler.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadSubscribers()
    }

    private fun loadSubscribers() {
        allSubscribers = V2rayCrypt.getSubscribers(this, parentGuid)
        filterList(etSearch.text.toString())
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
        builder.setPositiveButton("تمديد للمشترك") { dialog, _ ->
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
        builder.setNegativeButton("إلغاء", null)
        builder.show()
    }

    private fun shareSubscriber(sub: V2rayCrypt.SubscriberData) {
        // نسخ الكود الأصلي وتشفيره للمشترك من جديد
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
                    // جعل الوقت في الماضي لقطع الاتصال
                    val expiredTime = NetworkTime.currentTimeMillis(this@SubscribersActivity) - 10000L
                    CloudflareAPI.updateExpiry(sub.licenseId, expiredTime)
                    
                    withContext(Dispatchers.Main) {
                        V2rayCrypt.removeSubscriberLocally(this@SubscribersActivity, parentGuid, sub.licenseId)
                        loadSubscribers()
                        Toast.makeText(this@SubscribersActivity, "تم حذف المشترك وقطع الاتصال عنه!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

// محول القائمة (Adapter) الخاص ببطاقات المشتركين
class SubscribersAdapter(
    private val onExtend: (V2rayCrypt.SubscriberData) -> Unit,
    private val onShare: (V2rayCrypt.SubscriberData) -> Unit,
    private val onDelete: (V2rayCrypt.SubscriberData) -> Unit
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
        holder.tvName.text = item.name

        val currentTime = NetworkTime.currentTimeMillis(holder.itemView.context)
        val diffMs = item.expiryTimeMs - currentTime
        if (diffMs > 0) {
            val days = diffMs / (1000L * 60L * 60L * 24L)
            val hours = (diffMs / (1000L * 60L * 60L)) % 24L
            holder.tvExpiry.text = "متبقي: $days يوم و $hours ساعة"
            holder.tvExpiry.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvExpiry.text = "منتهي الصلاحية"
            holder.tvExpiry.setTextColor(Color.parseColor("#E53935"))
        }

        holder.btnExtend.setOnClickListener { onExtend(item) }
        holder.btnShare.setOnClickListener { onShare(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = list.size

    class SubViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_sub_name)
        val tvExpiry: TextView = view.findViewById(R.id.tv_sub_expiry)
        val btnExtend: View = view.findViewById(R.id.btn_extend)
        val btnShare: View = view.findViewById(R.id.btn_share)
        val btnDelete: View = view.findViewById(R.id.btn_delete)
    }
}
