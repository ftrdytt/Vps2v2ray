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

        setupProgrammaticUI()
        loadComments()
    }

    // 🌟 السحر: بناء الواجهة بالكامل برمجياً بدون الحاجة لملف XML 🌟
    private fun setupProgrammaticUI() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141417"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. الشريط العلوي (Toolbar)
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val btnBack = ImageView(this).apply {
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
            setPadding(0, 0, 40, 0)
        }
        val tvTitle = TextView(this).apply {
            text = "التعليقات 💬"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)

        // 2. حقل الإدخال السفلي
        val inputArea = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            setPadding(30, 30, 30, 30)
            elevation = 10f
        }
        etComment = EditText(this).apply {
            hint = "أضف تعليقاً..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252529"))
                cornerRadius = 50f
            }
            setPadding(40, 30, 40, 30)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.parseColor("#2196F3"))
            setPadding(30, 0, 10, 0)
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setOnClickListener { postComment() }
        }
        inputArea.addView(etComment)
        inputArea.addView(btnSend)

        // 3. منطقة عرض التعليقات (RecyclerView)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#2196F3"))
            setOnRefreshListener { loadComments() }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CommentsActivity).apply {
                stackFromEnd = true // التعليقات تظهر من الأسفل
            }
        }
        swipeRefresh.addView(recyclerView)

        // 4. حالة لا توجد تعليقات
        tvEmptyState = TextView(this).apply {
            text = "لا توجد تعليقات حتى الآن.\nكن أول من يعلق! ✨"
            setTextColor(Color.GRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // ترتيب العناصر في الشاشة
        val lpTopBar = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        val lpInputArea = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        val lpSwipe = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
            addRule(RelativeLayout.ABOVE, inputArea.id)
        }
        val lpEmptyState = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }

        rootLayout.addView(topBar, lpTopBar)
        rootLayout.addView(inputArea, lpInputArea)
        rootLayout.addView(swipeRefresh, lpSwipe)
        rootLayout.addView(tvEmptyState, lpEmptyState)

        setContentView(rootLayout)

        adapter = CommentsAdapter(commentsList, isOwnerOrAdmin) { comment -> deleteComment(comment) }
        recyclerView.adapter = adapter
    }

    private fun loadComments() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val jsonArray = CloudflareAPI.getComments(guid)
            withContext(Dispatchers.Main) {
                swipeRefresh.isRefreshing = false
                commentsList.clear()
                if (jsonArray != null && jsonArray.length() > 0) {
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
                    tvEmptyState.visibility = View.GONE
                } else {
                    tvEmptyState.visibility = View.VISIBLE
                }
                adapter.notifyDataSetChanged()
                if (commentsList.isNotEmpty()) recyclerView.scrollToPosition(commentsList.size - 1)
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
                    loadComments() // إعادة التحميل لإظهار التعليق الجديد
                } else {
                    Toast.makeText(this@CommentsActivity, "فشل إرسال التعليق، حاول مجدداً.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteComment(comment: CommentData) {
        AlertDialog.Builder(this)
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
                            Toast.makeText(this@CommentsActivity, "تم حذف التعليق بنجاح!", Toast.LENGTH_SHORT).show()
                            loadComments()
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

// 🌟 المحول الخاص بعرض التعليقات 🌟
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
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(0, 0, 30, 0) }
        }

        val bubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252529"))
                cornerRadius = 30f
            }
            setPadding(35, 25, 35, 25)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvName = TextView(context).apply {
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }

        val tvTime = TextView(context).apply {
            setTextColor(Color.GRAY)
            textSize = 11f
        }

        bubbleLayout.addView(tvName)
        bubbleLayout.addView(tvText)
        bubbleLayout.addView(tvTime)

        val btnDelete = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#E53935"))
            setPadding(20, 20, 20, 20)
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
        holder.tvTime.text = comment.timestamp

        // تحميل صورة الحساب أو توليد صورة افتراضية
        if (comment.userPfp.isNotEmpty()) {
            try {
                val b = Base64.decode(if (comment.userPfp.contains(",")) comment.userPfp.substringAfter(",") else comment.userPfp, Base64.DEFAULT)
                holder.ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
            } catch (e: Exception) {
                holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 110))
            }
        } else {
            holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 110))
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
