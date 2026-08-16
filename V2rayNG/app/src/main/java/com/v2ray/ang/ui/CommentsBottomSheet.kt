package com.v2ray.ang.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class CommentsBottomSheet : BottomSheetDialogFragment() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var storyId: String
    private lateinit var myUserId: String

    private lateinit var rvComments: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageView
    private lateinit var tvReplyingTo: TextView

    private val commentsList = mutableListOf<CommentModel>()
    private lateinit var adapter: CommentsAdapter

    private var replyingToCommentId: String? = null
    private var replyingToName: String? = null

    companion object {
        fun newInstance(storyId: String, myUserId: String): CommentsBottomSheet {
            val fragment = CommentsBottomSheet()
            val args = Bundle()
            args.putString("storyId", storyId)
            args.putString("myUserId", myUserId)
            fragment.arguments = args
            return fragment
        }
    }

    // 🌟 فتح النافذة بكامل الشاشة مع دعم الكيبورد السلس 🌟
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT) // جعل الخلفية شفافة للتصميم الزجاجي
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_comments, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        storyId = arguments?.getString("storyId") ?: ""
        myUserId = arguments?.getString("myUserId") ?: ""

        rvComments = view.findViewById(R.id.rv_comments)
        etInput = view.findViewById(R.id.et_comment_input)
        btnSend = view.findViewById(R.id.btn_send_comment)
        tvReplyingTo = view.findViewById(R.id.tv_replying_to)

        // 🌟 تطبيق تصميم Glassmorphism (زجاجي) على النافذة بالكامل برمجياً 🌟
        view.background = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#F2050505"), Color.parseColor("#E6121212")) // لون أسود زجاجي عميق
            cornerRadii = floatArrayOf(80f, 80f, 80f, 80f, 0f, 0f, 0f, 0f) // حواف دائرية قوية من الأعلى
            setStroke(3, Color.parseColor("#1AFFFFFF")) // خط مضيء خفيف يعطي إحساس 3D
        }

        // 🌟 تفعيل التسريع العتادي لضمان نعومة الفريمات (120Hz/60Hz) 🌟
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        rvComments.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        rvComments.overScrollMode = View.OVER_SCROLL_NEVER // إزالة ظل التمرير المزعج للأناقة

        // 🌟 تصميم كبسولة زجاجية لحقل الكتابة 🌟
        val inputContainer = etInput.parent as? ViewGroup
        inputContainer?.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1AFFFFFF"))
            cornerRadius = 60f
            setStroke(2, Color.parseColor("#26FFFFFF"))
        }
        inputContainer?.setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 4.dpToPx())

        // 🌟 تصميم مؤشر الرد (Replying To) 🌟
        tvReplyingTo.background = GradientDrawable().apply {
            setColor(Color.parseColor("#332196F3"))
            cornerRadius = 40f
        }
        tvReplyingTo.setPadding(24, 12, 24, 12)

        adapter = CommentsAdapter()
        rvComments.layoutManager = LinearLayoutManager(requireContext())
        rvComments.adapter = adapter
        rvComments.itemAnimator = null // إزالة الوميض عند الإعجاب

        tvReplyingTo.setOnClickListener {
            cancelReply()
        }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                // أنيميشن النبض لزر الإرسال
                it.animate().scaleX(0.7f).scaleY(0.7f).setDuration(100).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator()).setDuration(200).start()
                }.start()
                sendComment(text)
            }
        }

        fetchComments()
    }

    private fun cancelReply() {
        replyingToCommentId = null
        replyingToName = null
        tvReplyingTo.visibility = View.GONE
        etInput.hint = "إضافة تعليق..."
    }

    private fun fetchComments() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/get_comments?storyId=$storyId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val arr = obj.getJSONArray("comments")
                        val rawList = mutableListOf<CommentModel>()
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            val likesArr = c.optJSONArray("likes")
                            val likesList = mutableListOf<String>()
                            if (likesArr != null) {
                                for (j in 0 until likesArr.length()) likesList.add(likesArr.getString(j))
                            }
                            rawList.add(
                                CommentModel(
                                    c.getString("id"), c.getString("userId"), c.optString("name", "مجهول"), c.optString("pfp", ""),
                                    c.optBoolean("isVerified", false), c.getString("text"), c.getLong("timestamp"),
                                    c.optString("parentId", "null"), likesList
                                )
                            )
                        }

                        // تنظيم القائمة
                        val organizedList = mutableListOf<CommentModel>()
                        val parents = rawList.filter { it.parentId == "null" || it.parentId.isEmpty() }.sortedBy { it.timestamp }
                        for (p in parents) {
                            organizedList.add(p)
                            val replies = rawList.filter { it.parentId == p.id }.sortedBy { it.timestamp }
                            organizedList.addAll(replies)
                        }

                        withContext(Dispatchers.Main) {
                            commentsList.clear()
                            commentsList.addAll(organizedList)
                            adapter.notifyDataSetChanged()
                            if(commentsList.isNotEmpty()) {
                                rvComments.scrollToPosition(commentsList.size - 1)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun sendComment(text: String) {
        etInput.setText("")
        val parentId = replyingToCommentId
        cancelReply()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/comment").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("storyId", storyId)
                    put("userId", myUserId)
                    put("commentText", text)
                    if (parentId != null) put("parentId", parentId)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                
                if (conn.responseCode == 200) {
                    fetchComments() 
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "فشل الإرسال", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun likeComment(commentId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/like_comment").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("storyId", storyId)
                    put("commentId", commentId)
                    put("userId", myUserId)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode 
            } catch (e: Exception) {}
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    inner class CommentsAdapter : RecyclerView.Adapter<CommentsAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val rootLayout: View = v.findViewById(R.id.layout_root)
            val ivPfp: ImageView = v.findViewById(R.id.iv_comment_pfp)
            val tvName: TextView = v.findViewById(R.id.tv_comment_name)
            val ivVerified: ImageView = v.findViewById(R.id.iv_verified)
            val tvText: TextView = v.findViewById(R.id.tv_comment_text)
            val tvTime: TextView = v.findViewById(R.id.tv_comment_time)
            val btnReply: TextView = v.findViewById(R.id.tv_comment_reply)
            val btnLike: View = v.findViewById(R.id.btn_like_comment)
            val ivLikeIcon: ImageView = v.findViewById(R.id.iv_like_icon)
            val tvLikeCount: TextView = v.findViewById(R.id.tv_like_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))
        override fun getItemCount() = commentsList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = commentsList[position]
            
            // 🌟 تصميم الردود بشكل متداخل وحديث 🌟
            val isReply = c.parentId != "null" && c.parentId.isNotEmpty()
            val paddingStartEnd = if (isReply) 64.dpToPx() else 16.dpToPx()
            
            holder.rootLayout.setPadding(16.dpToPx(), 12.dpToPx(), paddingStartEnd, 12.dpToPx())

            holder.tvName.text = c.name
            holder.tvText.text = c.text
            holder.ivVerified.visibility = if (c.isVerified) View.VISIBLE else View.GONE
            
            val durationMs = System.currentTimeMillis() - c.timestamp
            val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
            val mins = TimeUnit.MILLISECONDS.toMinutes(durationMs)
            holder.tvTime.text = if (hours > 0) "${hours}h" else if (mins > 0) "${mins}m" else "الآن"

            val isLikedByMe = c.likes.contains(myUserId)
            holder.ivLikeIcon.setColorFilter(if (isLikedByMe) Color.parseColor("#E0245E") else Color.parseColor("#80FFFFFF"))
            
            if (c.likes.isEmpty()) {
                holder.tvLikeCount.visibility = View.GONE
            } else {
                holder.tvLikeCount.visibility = View.VISIBLE
                holder.tvLikeCount.text = c.likes.size.toString()
                holder.tvLikeCount.setTextColor(if (isLikedByMe) Color.parseColor("#E0245E") else Color.parseColor("#80FFFFFF"))
            }

            val bitmap = try {
                val cleanStr = if (c.pfp.contains(",")) c.pfp.substringAfter(",") else c.pfp
                val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            } catch (e: Exception) { null } ?: AvatarGenerator.generateAvatar(c.name, c.userId)
            
            holder.ivPfp.setImageDrawable(RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true })

            // 🌟 أنيميشن واهتزاز متطور عند الإعجاب 🌟
            holder.btnLike.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                
                holder.ivLikeIcon.animate().scaleX(1.5f).scaleY(1.5f).setDuration(120).withEndAction {
                    holder.ivLikeIcon.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(OvershootInterpolator()).setDuration(150).start()
                }.start()

                if (isLikedByMe) c.likes.remove(myUserId) else c.likes.add(myUserId)
                notifyItemChanged(position)
                likeComment(c.id)
            }

            holder.btnReply.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                replyingToCommentId = if (isReply) c.parentId else c.id
                replyingToName = c.name
                tvReplyingTo.visibility = View.VISIBLE
                tvReplyingTo.text = "يتم الرد على ${c.name} ✖"
                etInput.hint = "اكتب ردك لـ ${c.name}..."
                etInput.requestFocus()
            }

            holder.rootLayout.setOnLongClickListener {
                holder.rootLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Comment", c.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "تم نسخ التعليق", Toast.LENGTH_SHORT).show()
                true
            }

            val clickToProfile = View.OnClickListener {
                val intent = Intent(requireContext(), UserProfileActivity::class.java)
                intent.putExtra("targetUserId", c.userId)
                startActivity(intent)
            }
            holder.ivPfp.setOnClickListener(clickToProfile)
            holder.tvName.setOnClickListener(clickToProfile)
        }
    }

    data class CommentModel(
        val id: String, val userId: String, val name: String, val pfp: String, 
        val isVerified: Boolean, val text: String, val timestamp: Long, 
        val parentId: String, val likes: MutableList<String>
    )
}
