package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CommentsActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var guid: String
    private var isOwnerOrAdmin: Boolean = false
    private lateinit var myUserId: String

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CommentsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var etComment: EditText
    private lateinit var btnSend: ImageView
    private lateinit var tvEmptyState: TextView
    
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
            setBackgroundColor(Color.parseColor("#08080A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

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

        val bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F13"))
            setPadding(30, 20, 30, 30)
            elevation = 20f
        }
        
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

        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#0088FF"))
            setOnRefreshListener { fetchCommentsFromServer() }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CommentsActivity).apply {
                stackFromEnd = false 
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
            context = this,
            comments = commentsList, 
            myUserId = myUserId, 
            isAdmin = isOwnerOrAdmin,
            apiUrl = BASE_API_URL, // تمرير رابط السيرفر لجلب التحديثات
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
            val allComments = mutableListOf<CommentData>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val likesArr = obj.optJSONArray("likes") ?: JSONArray()
                val likesList = mutableListOf<String>()
                for(j in 0 until likesArr.length()) likesList.add(likesArr.getString(j))

                allComments.add(CommentData(
                    id = obj.optString("commentId", ""),
                    userId = obj.optString("userId", ""),
                    userName = obj.optString("userName", "مجهول"),
                    userPfp = obj.optString("userPfp", ""),
                    text = obj.optString("text", ""),
                    timestamp = obj.optString("timestamp", "الآن"),
                    isEdited = obj.optBoolean("isEdited", false),
                    parentId = obj.optString("parentId", ""), 
                    replyToName = obj.optString("replyToName", ""),
                    likes = likesList
                ))
            }
            
            // ترتيب التعليقات (الرئيسية أولاً ثم الردود)
            val parentComments = allComments.filter { it.parentId.isEmpty() }
            commentsList.clear()
            
            parentComments.forEach { parent ->
                commentsList.add(parent)
                val replies = allComments.filter { it.parentId == parent.id }
                if (replies.isNotEmpty()) {
                    parent.repliesCount = replies.size
                    parent.repliesList.addAll(replies)
                }
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
                // targetId هنا يعمل كـ ParentId للردود
                CloudflareAPI.addComment(guid, myUserId, myUserName, myUserPfp, text, replyName, targetId)
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
            .setMessage("هل أنت متأكد من الحذف؟ سيتم حذف جميع الردود التابعة له أيضاً.")
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

// 🌟 بيانات التعليق 🌟
data class CommentData(
    val id: String, 
    val userId: String, 
    var userName: String, 
    var userPfp: String, 
    val text: String, 
    val timestamp: String,
    val isEdited: Boolean,
    val parentId: String = "", 
    val replyToName: String,
    var likes: MutableList<String>,
    var repliesCount: Int = 0,
    var areRepliesVisible: Boolean = false,
    var repliesList: MutableList<CommentData> = mutableListOf(),
    var hasActiveStory: Boolean = false // لدائرة الاستوري
)

// 🌟 المحول الاحترافي للردود المتداخلة مع تحديث البيانات الحية 🌟
class CommentsAdapter(
    private val context: Context,
    private val comments: MutableList<CommentData>,
    private val myUserId: String,
    private val isAdmin: Boolean,
    private val apiUrl: String, // جلب البيانات من السيرفر
    private val onLikeClick: (CommentData) -> Unit,
    private val onReplyClick: (CommentData) -> Unit,
    private val onEditClick: (CommentData) -> Unit,
    private val onDeleteClick: (CommentData) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val ctx = parent.context
        
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // --- جسم التعليق الأساسي ---
        val mainCommentLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(30, 20, 30, 10)
        }

        // حاوية الصورة للاستوري
        val avatarContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 25, 0) }
        }

        val ivAvatar = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        avatarContainer.addView(ivAvatar)

        val contentLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val bubbleLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23"))
                cornerRadius = 35f
            }
            setPadding(35, 25, 35, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvName = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        val tvReplyContext = TextView(ctx).apply {
            setTextColor(Color.parseColor("#0088FF"))
            textSize = 12f
            setPadding(0, 5, 0, 5)
            visibility = View.GONE
        }

        val tvText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 8, 0, 8)
            setLineSpacing(0f, 1.2f)
        }

        bubbleLayout.addView(tvName)
        bubbleLayout.addView(tvReplyContext)
        bubbleLayout.addView(tvText)

        val actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 15, 0, 10)
        }

        val tvTime = TextView(ctx).apply {
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            setPadding(0, 0, 35, 0)
        }

        val tvLike = TextView(ctx).apply {
            text = "إعجاب"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 35, 0)
        }
        
        val tvLikesCount = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E91E63")) 
            textSize = 12f
            setPadding(10, 0, 35, 0)
            visibility = View.GONE
        }

        val tvReply = TextView(ctx).apply {
            text = "رد"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 35, 0)
        }

        val tvEdit = TextView(ctx).apply {
            text = "تعديل"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(0, 0, 35, 0)
            visibility = View.GONE
        }

        val tvDelete = TextView(ctx).apply {
            text = "حذف"
            setTextColor(Color.parseColor("#FF3B30"))
            textSize = 13f
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
        
        mainCommentLayout.addView(avatarContainer)
        mainCommentLayout.addView(contentLayout)

        // --- قسم إظهار الردود المتداخلة (Tree) ---
        val repliesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(140, 0, 0, 0) 
            }
            visibility = View.GONE
        }

        val btnShowReplies = TextView(ctx).apply {
            setTextColor(Color.parseColor("#0088FF"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(160, 5, 30, 20)
            visibility = View.GONE
        }

        layout.addView(mainCommentLayout)
        layout.addView(btnShowReplies)
        layout.addView(repliesContainer)

        return CommentViewHolder(layout, avatarContainer, ivAvatar, tvName, tvReplyContext, tvText, tvTime, tvLike, tvLikesCount, tvReply, tvEdit, tvDelete, btnShowReplies, repliesContainer, mainCommentLayout)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val comment = comments[position]
        
        // 🌟 جلب التحديث المباشر للصورة، الاسم، والاستوري من السيرفر 🌟
        if (comment.userId.isNotEmpty()) {
            (context as AppCompatActivity).lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = URL("$apiUrl/auth/get_user?id=${comment.userId}")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 3000
                    
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(resp)
                        if (json.optBoolean("success", false)) {
                            comment.userName = json.optString("name", comment.userName)
                            comment.userPfp = json.optString("pfp", "")
                            comment.hasActiveStory = json.optBoolean("hasActiveStory", false)
                            
                            withContext(Dispatchers.Main) {
                                // تحديث الواجهة فوراً
                                holder.tvName.text = comment.userName
                                updateAvatarAndStory(holder, comment)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        
        holder.tvName.text = comment.userName
        updateAvatarAndStory(holder, comment)

        holder.tvText.text = comment.text
        if (comment.isEdited) holder.tvText.append(android.text.Html.fromHtml(" <font color='#666666'><i>(معدل)</i></font>"))

        if (comment.replyToName.isNotEmpty()) {
            holder.tvReplyContext.visibility = View.VISIBLE
            holder.tvReplyContext.text = "↩ رد على ${comment.replyToName}"
        } else holder.tvReplyContext.visibility = View.GONE

        val timeParts = comment.timestamp.split(" ")
        holder.tvTime.text = if(timeParts.isNotEmpty()) timeParts.last() else comment.timestamp

        val isLikedByMe = comment.likes.contains(myUserId)
        holder.tvLike.text = if (isLikedByMe) "أعجبني" else "إعجاب"
        holder.tvLike.setTextColor(if (isLikedByMe) Color.parseColor("#E91E63") else Color.parseColor("#AAAAAA"))
        
        if (comment.likes.isNotEmpty()) {
            holder.tvLikesCount.visibility = View.VISIBLE
            holder.tvLikesCount.text = "♥ ${comment.likes.size}"
        } else holder.tvLikesCount.visibility = View.GONE

        val isMyComment = comment.userId == myUserId
        holder.tvEdit.visibility = if (isMyComment) View.VISIBLE else View.GONE
        holder.tvDelete.visibility = if (isMyComment || isAdmin) View.VISIBLE else View.GONE

        // 🌟 فتح شاشة المعجبين الخاصة بالتعليق عند الضغط على عداد اللايكات 🌟
        holder.tvLikesCount.setOnClickListener {
            try {
                val intent = Intent(context, Class.forName("com.v2ray.ang.ui.ConnectionsActivity"))
                intent.putExtra("targetUserId", comment.id) 
                intent.putExtra("type", "comment_likers") // نفتح الشاشة كإعجابات تعليق
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "حدث خطأ في فتح القائمة", Toast.LENGTH_SHORT).show()
            }
        }

        holder.tvLike.setOnClickListener {
            if (isLikedByMe) comment.likes.remove(myUserId) else comment.likes.add(myUserId)
            notifyItemChanged(position)
            onLikeClick(comment)
        }
        
        holder.tvReply.setOnClickListener { 
            val parentIdToPass = if (comment.parentId.isEmpty()) comment.id else comment.parentId
            onReplyClick(comment.copy(id = parentIdToPass)) 
        }
        holder.tvEdit.setOnClickListener { onEditClick(comment) }
        holder.tvDelete.setOnClickListener { onDeleteClick(comment) }

        // 🌟 إظهار الردود المتداخلة (Tree) 🌟
        if (comment.parentId.isEmpty() && comment.repliesCount > 0) {
            holder.btnShowReplies.visibility = View.VISIBLE
            holder.btnShowReplies.text = if (comment.areRepliesVisible) "إخفاء الردود ⌃" else "↪ عرض ${comment.repliesCount} من الردود ⌄"
            
            holder.btnShowReplies.setOnClickListener {
                comment.areRepliesVisible = !comment.areRepliesVisible
                
                if (comment.areRepliesVisible) {
                    val insertPos = position + 1
                    comments.addAll(insertPos, comment.repliesList)
                    notifyItemRangeInserted(insertPos, comment.repliesList.size)
                } else {
                    val removePos = position + 1
                    comments.removeAll(comment.repliesList)
                    notifyItemRangeRemoved(removePos, comment.repliesList.size)
                }
                notifyItemChanged(position)
            }
        } else {
            holder.btnShowReplies.visibility = View.GONE
        }

        // تغيير التصميم إذا كان هذا رداً وليس تعليقاً أساسياً
        if (comment.parentId.isNotEmpty()) {
            val params = holder.mainCommentLayout.layoutParams as LinearLayout.LayoutParams
            params.setMargins(100, 0, 0, 0) // دفع للداخل
            holder.mainCommentLayout.layoutParams = params
            holder.avatarContainer.layoutParams = LinearLayout.LayoutParams(90, 90).apply { setMargins(0, 0, 20, 0) } // تصغير الصورة للردود
        } else {
            val params = holder.mainCommentLayout.layoutParams as LinearLayout.LayoutParams
            params.setMargins(0, 0, 0, 0)
            holder.mainCommentLayout.layoutParams = params
            holder.avatarContainer.layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 25, 0) }
        }
    }
    
    // دالة لتحديث الصورة والاستوري للتعليقات والردود
    private fun updateAvatarAndStory(holder: CommentViewHolder, comment: CommentData) {
        if (comment.userPfp.isNotEmpty()) {
            try {
                val b = Base64.decode(if (comment.userPfp.contains(",")) comment.userPfp.substringAfter(",") else comment.userPfp, Base64.DEFAULT)
                holder.ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
            } catch (e: Exception) {
                holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 120))
            }
        } else holder.ivAvatar.setImageBitmap(AvatarGenerator.generateAvatar(comment.userName, comment.userId, 120))
        
        if (comment.hasActiveStory && comment.userId.isNotEmpty()) {
            holder.avatarContainer.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(5, Color.parseColor("#2196F3")) // لون الاستوري الأزرق
                setColor(Color.TRANSPARENT)
            }
            holder.avatarContainer.setPadding(6, 6, 6, 6)
            holder.avatarContainer.setOnClickListener {
                try {
                    val intent = Intent(context, Class.forName("com.v2ray.ang.ui.StoryViewerActivity"))
                    intent.putExtra("targetUserId", comment.userId)
                    context.startActivity(intent)
                } catch (e: Exception) {}
            }
        } else {
            holder.avatarContainer.background = null
            holder.avatarContainer.setPadding(0, 0, 0, 0)
            holder.avatarContainer.setOnClickListener {
                try {
                    val intent = Intent(context, Class.forName("com.v2ray.ang.ui.UserProfileActivity"))
                    intent.putExtra("targetUserId", comment.userId)
                    context.startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    override fun getItemCount(): Int = comments.size

    class CommentViewHolder(
        view: View,
        val avatarContainer: FrameLayout,
        val ivAvatar: ImageView,
        val tvName: TextView,
        val tvReplyContext: TextView,
        val tvText: TextView,
        val tvTime: TextView,
        val tvLike: TextView,
        val tvLikesCount: TextView,
        val tvReply: TextView,
        val tvEdit: TextView,
        val tvDelete: TextView,
        val btnShowReplies: TextView,
        val repliesContainer: LinearLayout,
        val mainCommentLayout: LinearLayout
    ) : RecyclerView.ViewHolder(view)
}
