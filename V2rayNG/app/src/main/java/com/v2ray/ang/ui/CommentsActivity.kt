package com.v2ray.ang.ui

import android.content.Context
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.handler.CloudflareAPI
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CommentsActivity : AppCompatActivity() {

    private lateinit var guid: String
    private var isOwnerOrAdmin: Boolean = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CommentsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var etComment: EditText
    private lateinit var btnSend: ImageView
    private lateinit var tvEmptyState: TextView

    private val commentsList = mutableListOf<CommentData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        guid = intent.getStringExtra("guid") ?: return finish()
        isOwnerOrAdmin = intent.getBooleanExtra("isOwnerOrAdmin", false)

        setupPremiumUI()
        
        // 🌟 استدعاء التعليقات من الذاكرة (بدون نت) لتسريع الفتح 🌟
        loadCachedComments()
        
        // 🌟 مزامنة التعليقات الجديدة من السيرفر 🌟
        fetchCommentsFromServer()
    }

    // 🌟 بناء واجهة فخمة جداً برمجياً (VIP Dark Theme) 🌟
    private fun setupPremiumUI() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D11")) // لون أسود عميق جداً وراقي
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. الشريط العلوي
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D0D11"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(50, 50, 50, 40)
        }
        val btnBack = ImageView(this).apply {
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
            setPadding(0, 0, 40, 0)
        }
        val tvTitle = TextView(this).apply {
            text = "التعليقات"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)

        // 2. حقل الإدخال السفلي (تصميم عائم مثل تيليجرام)
        val inputAreaContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D11"))
            setPadding(40, 20, 40, 40)
        }
        val inputWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23")) // لون رمادي أنيق لحقل النص
                cornerRadius = 60f
                setStroke(2, Color.parseColor("#2A2A35")) // حواف شفافة راقية
            }
            setPadding(20, 10, 20, 10)
        }
        etComment = EditText(this).apply {
            hint = "اكتب تعليقاً..."
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            background = null // إخفاء الخط السفلي الافتراضي
            setPadding(40, 25, 20, 25)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.parseColor("#0088FF")) // أزرق راقي لزر الإرسال
            setPadding(20, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(110, 110)
            setOnClickListener { postComment() }
        }
        inputWrapper.addView(etComment)
        inputWrapper.addView(btnSend)
        inputAreaContainer.addView(inputWrapper)

        // 3. منطقة عرض التعليقات
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#0088FF"))
            setOnRefreshListener { fetchCommentsFromServer() }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CommentsActivity).apply {
                stackFromEnd = true // عرض أحدث التعليقات بالأسفل
            }
            setPadding(0, 0, 0, 20)
            clipToPadding = false
        }
        swipeRefresh.addView(recyclerView)

        // 4. حالة لا توجد تعليقات
        tvEmptyState = TextView(this).apply {
            text = "لا توجد تعليقات حتى الآن.\nكن أول من يعلق!"
            setTextColor(Color.parseColor("#666666"))
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // ترتيب العناصر
        val lpTopBar = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        val lpInputArea = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        val lpSwipe = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
            addRule(RelativeLayout.ABOVE, inputAreaContainer.id)
        }
        val lpEmptyState = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }

        rootLayout.addView(topBar, lpTopBar)
        rootLayout.addView(inputAreaContainer, lpInputArea)
        rootLayout.addView(swipeRefresh, lpSwipe)
        rootLayout.addView(tvEmptyState, lpEmptyState)

        setContentView(rootLayout)

        adapter = CommentsAdapter(commentsList, isOwnerOrAdmin) { comment -> deleteComment(comment) }
        recyclerView.adapter = adapter
    }

    // 🌟 ميزة الخزن المؤقت (العمل بدون نت) 🌟
    private fun loadCachedComments() {
        val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
        val cachedStr = prefs.getString("cached_comments_$guid", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(cachedStr)
            commentsList.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                commentsList.add(CommentData(
                    id = obj.optString("commentId", ""),
                    userId = obj.optString("userId", ""),
                    userName = obj.optString("userName", "مجهول"),
                    userPfp = obj.optString("userPfp", ""),
                    text = obj.optString("text", ""),
                    timestamp = obj.optString("timestamp", "الآن")
                ))
            }
            if (commentsList.isNotEmpty()) {
                tvEmptyState.visibility = View.GONE
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(commentsList.size - 1)
            }
        } catch (e: Exception) {}
    }

    // 🌟 المزامنة مع السيرفر 🌟
    private fun fetchCommentsFromServer() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val jsonArray = CloudflareAPI.getComments(guid)
            withContext(Dispatchers.Main) {
                swipeRefresh.isRefreshing = false
                if (jsonArray != null) {
                    // حفظ التعليقات لتفتح بدون نت بالمستقبل
                    getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("cached_comments_$guid", jsonArray.toString())
                        .apply()
                        
                    commentsList.clear()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        commentsList.add(CommentData(
                            id = obj.optString("commentId", ""),
                            userId = obj.optString("userId", ""),
                            userName = obj.optString("userName", "مجهول"),
                            userPfp = obj.optString("userPfp", ""),
                            text = obj.optString("text", ""),
                            timestamp = obj.optString("timestamp", "الآن")
                        ))
                    }
                    tvEmptyState.visibility = if (commentsList.isEmpty()) View.VISIBLE else View.GONE
                    adapter.notifyDataSetChanged()
                    if (commentsList.isNotEmpty()) recyclerView.scrollToPosition(commentsList.size - 1)
                }
            }
        }
    }

    private fun postComment() {
        val text = etComment.text.toString().trim()
        if (text.isEmpty()) return

        val myUserId = AuthManager.getId(this)
        val myUserName = AuthManager.getName(this).takeIf { it.isNotEmpty() } ?: "صاحب الملف"
        val myUserPfp = AuthManager.getPfp(this)

        btnSend.isEnabled = false
        etComment.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val success = CloudflareAPI.addComment(guid, myUserId, myUserName, myUserPfp, text)
            withContext(Dispatchers.Main) {
                btnSend.isEnabled = true
                etComment.isEnabled = true
                if (success) {
                    etComment.text.clear()
                    fetchCommentsFromServer() 
                } else {
                    Toast.makeText(this@CommentsActivity, "تحقق من اتصال الإنترنت", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteComment(comment: CommentData) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("حذف التعليق")
            .setMessage("هل أنت متأكد من حذف هذا التعليق نهائياً؟")
            .setPositiveButton("حذف") { _, _ ->
                swipeRefresh.isRefreshing = true
                lifecycleScope.launch(Dispatchers.IO) {
                    val myAdminId = AuthManager.getId(this@CommentsActivity)
                    val success = CloudflareAPI.deleteComment(guid, comment.id, myAdminId)
                    withContext(Dispatchers.Main) {
                        swipeRefresh.isRefreshing = false
                        if (success) {
                            Toast.makeText(this@CommentsActivity, "تم حذف التعليق!", Toast.LENGTH_SHORT).show()
                            fetchCommentsFromServer()
                        } else {
                            Toast.makeText(this@CommentsActivity, "حدث خطأ أثناء الحذف.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

data class CommentData(val id: String, val userId: String, val userName: String, val userPfp: String, val text: String, val timestamp: String)

// 🌟 المحول الخاص بتصميم فقاعات التعليقات (Premium Bubbles) 🌟
class CommentsAdapter(
    private val comments: List<CommentData>,
    private val isAdmin: Boolean,
    private val onDeleteClick: (CommentData) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val context = parent.context
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(40, 20, 40, 20)
        }

        val ivAvatar = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(0, 0, 30, 0) }
        }

        // تصميم الفقاعة
        val bubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23")) // لون راقي للفقاعة
                cornerRadius = 35f // حواف ناعمة دائرية
            }
            setPadding(40, 25, 40, 25)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // الحاوية لاسم المستخدم والوقت
        val nameTimeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvName = TextView(context).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTime = TextView(context).apply {
            setTextColor(Color.parseColor("#666666"))
            textSize = 10f
        }
        
        nameTimeLayout.addView(tvName)
        nameTimeLayout.addView(tvTime)

        val tvText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 10, 0, 5)
            setLineSpacing(0f, 1.2f)
        }

        bubbleLayout.addView(nameTimeLayout)
        bubbleLayout.addView(tvText)

        val btnDelete = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#FF3B30")) // لون أحمر طوخ خاص للآدمن
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(20, 0, 0, 0) }
            visibility = if (isAdmin) View.VISIBLE else View.GONE
        }

        layout.addView(ivAvatar)
        layout.addView(bubbleLayout)
        layout.addView(btnDelete)

        return CommentViewHolder(layout, ivAvatar, tvName, tvText, tvTime, btnDelete)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val comment = comments[position]
        holder.tvName.text = comment.userName
        holder.tvText.text = comment.text
        
        // تنسيق الوقت
        val timeParts = comment.timestamp.split(" ")
        holder.tvTime.text = if(timeParts.isNotEmpty()) timeParts.last() else comment.timestamp

        // تحميل صورة الحساب أو توليد صورة افتراضية
        if (comment.userPfp.isNotEmpty()) {
            try {
                val b = Base64.decode(if (comment.userPfp.contains(",")) comment.userPfp.substringAfter(",") else comment.userPfp, Base64.DEFAULT)
                holder.ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
            } catch (e: Exception) {
                holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 100))
            }
        } else {
            holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 100))
        }

        if (isAdmin) {
            holder.btnDelete.setOnClickListener { onDeleteClick(comment) }
        }
    }

    override fun getItemCount(): Int = comments.size

    class CommentViewHolder(
        view: View,
        val ivAvatar: ImageView,
        val tvName: TextView,
        val tvText: TextView,
        val tvTime: TextView,
        val btnDelete: ImageView
    ) : RecyclerView.ViewHolder(view)
}
