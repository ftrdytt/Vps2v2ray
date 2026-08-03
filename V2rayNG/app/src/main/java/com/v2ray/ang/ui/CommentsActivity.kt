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
    private lateinit var myUserId: String

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CommentsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var etComment: EditText
    private lateinit var btnSend: ImageView
    private lateinit var tvEmptyState: TextView
    
    // شريط الرد والتعديل
    private lateinit var layoutActionState: LinearLayout
    private lateinit var tvActionStateText: TextView
    private lateinit var btnCloseAction: ImageView

    private val commentsList = mutableListOf<CommentData>()
    
    private var editingCommentId: String? = null
    private var replyingToCommentId: String? = null
    private var replyingToName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        guid = intent.getStringExtra("guid") ?: return finish()
        isOwnerOrAdmin = intent.getBooleanExtra("isOwnerOrAdmin", false)
        myUserId = AuthManager.getId(this)

        setupPremiumUI()
        loadCachedComments()
        fetchCommentsFromServer()
    }

    private fun setupPremiumUI() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#08080A")) // لون أسود ليلي فخم جداً
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. الشريط العلوي (Toolbar)
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#08080A"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(50, 50, 50, 40)
            elevation = 8f
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

        // 2. حقل الإدخال السفلي والردود
        val bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F13")) // لون مختلف قليلاً لإبراز حقل النص
            setPadding(30, 20, 30, 30)
            elevation = 20f
        }
        
        // شريط حالة (جاري الرد / جاري التعديل)
        layoutActionState = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23"))
                cornerRadius = 20f
            }
            setPadding(30, 15, 30, 15)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 15)
            }
            visibility = View.GONE
        }
        tvActionStateText = TextView(this).apply {
            setTextColor(Color.parseColor("#0088FF"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnCloseAction = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(60, 60)
            setOnClickListener { cancelActionState() }
        }
        layoutActionState.addView(tvActionStateText)
        layoutActionState.addView(btnCloseAction)
        
        val inputWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181E"))
                cornerRadius = 50f
                setStroke(1, Color.parseColor("#2A2A35"))
            }
            setPadding(20, 5, 20, 5)
        }
        etComment = EditText(this).apply {
            hint = "اكتب تعليقاً..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            background = null
            setPadding(35, 25, 20, 25)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.parseColor("#0088FF"))
            setPadding(25, 20, 25, 20)
            layoutParams = LinearLayout.LayoutParams(110, 110)
            setOnClickListener { handleSend() }
        }
        inputWrapper.addView(etComment)
        inputWrapper.addView(btnSend)
        
        bottomContainer.addView(layoutActionState)
        bottomContainer.addView(inputWrapper)

        // 3. منطقة التعليقات
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#0088FF"))
            setOnRefreshListener { fetchCommentsFromServer() }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CommentsActivity).apply {
                stackFromEnd = false // ترتيب طبيعي
            }
            setPadding(0, 20, 0, 20)
            clipToPadding = false
        }
        swipeRefresh.addView(recyclerView)

        tvEmptyState = TextView(this).apply {
            text = "لا توجد تعليقات حتى الآن.\nكن أول من يبدأ النقاش! ✨"
            setTextColor(Color.parseColor("#555555"))
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val lpTopBar = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        val lpInputArea = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        val lpSwipe = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
            addRule(RelativeLayout.ABOVE, bottomContainer.id)
        }
        val lpEmptyState = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }

        rootLayout.addView(topBar, lpTopBar)
        rootLayout.addView(bottomContainer, lpInputArea)
        rootLayout.addView(swipeRefresh, lpSwipe)
        rootLayout.addView(tvEmptyState, lpEmptyState)

        setContentView(rootLayout)

        adapter = CommentsAdapter(
            commentsList, 
            myUserId, 
            isOwnerOrAdmin,
            onLikeClick = { comment -> toggleLikeComment(comment) },
            onReplyClick = { comment -> setReplyState(comment) },
            onEditClick = { comment -> setEditState(comment) },
            onDeleteClick = { comment -> deleteComment(comment) }
        )
        recyclerView.adapter = adapter
    }

    private fun cancelActionState() {
        editingCommentId = null
        replyingToCommentId = null
        replyingToName = null
        etComment.text.clear()
        layoutActionState.visibility = View.GONE
    }

    private fun setReplyState(comment: CommentData) {
        editingCommentId = null
        replyingToCommentId = comment.id
        replyingToName = comment.userName
        tvActionStateText.text = "الرد على ${comment.userName}..."
        layoutActionState.visibility = View.VISIBLE
        etComment.requestFocus()
    }

    private fun setEditState(comment: CommentData) {
        replyingToCommentId = null
        replyingToName = null
        editingCommentId = comment.id
        tvActionStateText.text = "تعديل التعليق..."
        layoutActionState.visibility = View.VISIBLE
        etComment.setText(comment.text)
        etComment.setSelection(comment.text.length)
        etComment.requestFocus()
    }

    private fun loadCachedComments() {
        val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
        val cachedStr = prefs.getString("cached_comments_$guid", "[]") ?: "[]"
        parseAndSetComments(cachedStr)
    }

    private fun fetchCommentsFromServer() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val jsonArray = CloudflareAPI.getComments(guid)
            withContext(Dispatchers.Main) {
                swipeRefresh.isRefreshing = false
                if (jsonArray != null) {
                    getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
                        .edit().putString("cached_comments_$guid", jsonArray.toString()).apply()
                    parseAndSetComments(jsonArray.toString())
                }
            }
        }
    }

    private fun parseAndSetComments(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            commentsList.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                
                // جلب قائمة اللايكات كـ List
                val likesArr = obj.optJSONArray("likes") ?: JSONArray()
                val likesList = mutableListOf<String>()
                for(j in 0 until likesArr.length()) likesList.add(likesArr.getString(j))

                commentsList.add(CommentData(
                    id = obj.optString("commentId", ""),
                    userId = obj.optString("userId", ""),
                    userName = obj.optString("userName", "مجهول"),
                    userPfp = obj.optString("userPfp", ""),
                    text = obj.optString("text", ""),
                    timestamp = obj.optString("timestamp", "الآن"),
                    isEdited = obj.optBoolean("isEdited", false),
                    replyToName = obj.optString("replyToName", ""),
                    likes = likesList
                ))
            }
            tvEmptyState.visibility = if (commentsList.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            if (commentsList.isNotEmpty()) recyclerView.scrollToPosition(commentsList.size - 1)
        } catch (e: Exception) {}
    }

    private fun handleSend() {
        val text = etComment.text.toString().trim()
        if (text.isEmpty()) return

        val myUserName = AuthManager.getName(this).takeIf { it.isNotEmpty() } ?: "صاحب الملف"
        val myUserPfp = AuthManager.getPfp(this)

        btnSend.isEnabled = false
        etComment.isEnabled = false

        val isEdit = editingCommentId != null
        val targetId = editingCommentId ?: replyingToCommentId ?: ""
        val replyName = replyingToName ?: ""

        lifecycleScope.launch(Dispatchers.IO) {
            val success = if (isEdit) {
                CloudflareAPI.editComment(guid, targetId, myUserId, text)
            } else {
                CloudflareAPI.addComment(guid, myUserId, myUserName, myUserPfp, text, replyName)
            }

            withContext(Dispatchers.Main) {
                btnSend.isEnabled = true
                etComment.isEnabled = true
                if (success) {
                    cancelActionState()
                    fetchCommentsFromServer()
                } else {
                    Toast.makeText(this@CommentsActivity, "فشل الاتصال بالخادم", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleLikeComment(comment: CommentData) {
        lifecycleScope.launch(Dispatchers.IO) {
            CloudflareAPI.likeComment(guid, comment.id, myUserId)
        }
    }

    private fun deleteComment(comment: CommentData) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("حذف التعليق")
            .setMessage("هل أنت متأكد من الحذف؟")
            .setPositiveButton("حذف") { _, _ ->
                swipeRefresh.isRefreshing = true
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = CloudflareAPI.deleteComment(guid, comment.id, myUserId)
                    withContext(Dispatchers.Main) {
                        swipeRefresh.isRefreshing = false
                        if (success) fetchCommentsFromServer()
                        else Toast.makeText(this@CommentsActivity, "فشل الحذف", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

// 🌟 بيانات التعليق (مع دعم اللايكات والردود والتعديل) 🌟
data class CommentData(
    val id: String, 
    val userId: String, 
    val userName: String, 
    val userPfp: String, 
    val text: String, 
    val timestamp: String,
    val isEdited: Boolean,
    val replyToName: String,
    var likes: MutableList<String>
)

// 🌟 المحول الاحترافي للتعليقات (تصميم الفيسبوك والانستا) 🌟
class CommentsAdapter(
    private val comments: List<CommentData>,
    private val myUserId: String,
    private val isAdmin: Boolean,
    private val onLikeClick: (CommentData) -> Unit,
    private val onReplyClick: (CommentData) -> Unit,
    private val onEditClick: (CommentData) -> Unit,
    private val onDeleteClick: (CommentData) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val context = parent.context
        
        // الحاوية الأساسية للتعليق
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(30, 20, 30, 20)
        }

        // صورة المستخدم
        val ivAvatar = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(0, 0, 25, 0) }
        }

        // الجزء الأيمن (الفقاعة + أزرار التفاعل)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 💬 فقاعة التعليق
        val bubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23"))
                cornerRadius = 30f
            }
            setPadding(35, 25, 35, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvName = TextView(context).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        val tvReplyContext = TextView(context).apply {
            setTextColor(Color.parseColor("#0088FF"))
            textSize = 12f
            setPadding(0, 5, 0, 5)
            visibility = View.GONE
        }

        val tvText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 5, 0, 5)
            setLineSpacing(0f, 1.2f)
        }

        bubbleLayout.addView(tvName)
        bubbleLayout.addView(tvReplyContext)
        bubbleLayout.addView(tvText)

        // 🔄 شريط التفاعل (الوقت، لايك، رد، تعديل، حذف) تحت الفقاعة
        val actionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 0, 0)
        }

        val tvTime = TextView(context).apply {
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setPadding(0, 0, 30, 0)
        }

        val tvLike = TextView(context).apply {
            text = "إعجاب"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 30, 0)
        }
        
        val tvLikesCount = TextView(context).apply {
            setTextColor(Color.parseColor("#E91E63")) // لون وردي للعداد
            textSize = 11f
            setPadding(10, 0, 30, 0)
            visibility = View.GONE
        }

        val tvReply = TextView(context).apply {
            text = "رد"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 30, 0)
        }

        val tvEdit = TextView(context).apply {
            text = "تعديل"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 0, 30, 0)
            visibility = View.GONE
        }

        val tvDelete = TextView(context).apply {
            text = "حذف"
            setTextColor(Color.parseColor("#FF3B30"))
            textSize = 12f
            visibility = View.GONE
        }

        actionsLayout.addView(tvTime)
        actionsLayout.addView(tvLike)
        actionsLayout.addView(tvLikesCount)
        actionsLayout.addView(tvReply)
        actionsLayout.addView(tvEdit)
        actionsLayout.addView(tvDelete)

        contentLayout.addView(bubbleLayout)
        contentLayout.addView(actionsLayout)
        
        layout.addView(ivAvatar)
        layout.addView(contentLayout)

        return CommentViewHolder(layout, ivAvatar, tvName, tvReplyContext, tvText, tvTime, tvLike, tvLikesCount, tvReply, tvEdit, tvDelete)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val comment = comments[position]
        
        holder.tvName.text = comment.userName
        holder.tvText.text = comment.text
        
        // إذا كان التعليق معدلاً
        if (comment.isEdited) {
            holder.tvText.append(android.text.Html.fromHtml(" <font color='#666666'><i>(معدل)</i></font>"))
        }

        // إذا كان رداً على شخص
        if (comment.replyToName.isNotEmpty()) {
            holder.tvReplyContext.visibility = View.VISIBLE
            holder.tvReplyContext.text = "↩ رد على ${comment.replyToName}"
        } else {
            holder.tvReplyContext.visibility = View.GONE
        }

        // تنسيق الوقت
        val timeParts = comment.timestamp.split(" ")
        holder.tvTime.text = if(timeParts.isNotEmpty()) timeParts.last() else comment.timestamp

        // إعداد اللايكات
        val isLikedByMe = comment.likes.contains(myUserId)
        holder.tvLike.text = if (isLikedByMe) "أعجبني" else "إعجاب"
        holder.tvLike.setTextColor(if (isLikedByMe) Color.parseColor("#E91E63") else Color.parseColor("#AAAAAA"))
        
        if (comment.likes.isNotEmpty()) {
            holder.tvLikesCount.visibility = View.VISIBLE
            holder.tvLikesCount.text = "♥ ${comment.likes.size}"
        } else {
            holder.tvLikesCount.visibility = View.GONE
        }

        // أزرار الصلاحيات (التعديل والحذف)
        val isMyComment = comment.userId == myUserId
        
        holder.tvEdit.visibility = if (isMyComment) View.VISIBLE else View.GONE
        holder.tvDelete.visibility = if (isMyComment || isAdmin) View.VISIBLE else View.GONE

        // أحداث الضغط (Click Listeners)
        holder.tvLike.setOnClickListener {
            // تحديث محلي سريع
            if (isLikedByMe) comment.likes.remove(myUserId) else comment.likes.add(myUserId)
            notifyItemChanged(position)
            onLikeClick(comment)
        }
        
        holder.tvReply.setOnClickListener { onReplyClick(comment) }
        holder.tvEdit.setOnClickListener { onEditClick(comment) }
        holder.tvDelete.setOnClickListener { onDeleteClick(comment) }

        // الصورة
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
    }

    override fun getItemCount(): Int = comments.size

    class CommentViewHolder(
        view: View,
        val ivAvatar: ImageView,
        val tvName: TextView,
        val tvReplyContext: TextView,
        val tvText: TextView,
        val tvTime: TextView,
        val tvLike: TextView,
        val tvLikesCount: TextView,
        val tvReply: TextView,
        val tvEdit: TextView,
        val tvDelete: TextView
    ) : RecyclerView.ViewHolder(view)
}
