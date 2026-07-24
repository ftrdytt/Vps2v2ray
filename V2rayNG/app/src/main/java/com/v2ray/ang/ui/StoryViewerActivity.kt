package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class StoryViewerActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private var myUserId: String = ""
    private var myRole: String = ""

    private var storiesArray = JSONArray()
    private var currentIndex = 0
    private var currentViewsArray: JSONArray? = null 
    private var currentReactionsObj: JSONObject? = null

    private lateinit var ivStoryImage: ImageView
    private lateinit var tvStoryText: TextView
    private lateinit var ivPfp: ImageView
    private lateinit var tvName: TextView
    private lateinit var btnFollow: MaterialButton
    private lateinit var tvCommentsCount: TextView
    private lateinit var progressLoading: ProgressBar
    
    private lateinit var tvTime: TextView
    private lateinit var tvViews: TextView
    private lateinit var layoutProgressBars: LinearLayout
    private lateinit var viewTouchOverlay: View
    private lateinit var btnOptions: ImageView
    private lateinit var storyContentContainer: FrameLayout
    private lateinit var reactionAnimationLayer: FrameLayout

    private var storyJob: Job? = null
    private val STORY_DURATION = 5000L 
    private var progressAnimators = mutableListOf<ProgressBar>()
    private var isPaused = false
    private var timeLeft = STORY_DURATION
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_viewer)

        // 🌟 نستلم targetUserId، سواء جايين من قائمة الملفات أو الأصدقاء 🌟
        targetUserId = intent.getStringExtra("targetUserId") ?: intent.getStringExtra("userId") ?: ""
        myUserId = AuthManager.getId(this)
        myRole = AuthManager.getRole(this)

        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات القصة (لا يوجد ID)", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupTouchListener()
        setupButtons()

        fetchPublisherInfo()
        fetchStories()
    }

    private fun initViews() {
        ivStoryImage = findViewById(R.id.iv_story_image)
        tvStoryText = findViewById(R.id.tv_story_text)
        ivPfp = findViewById(R.id.iv_story_avatar)
        tvName = findViewById(R.id.tv_story_username)
        btnFollow = findViewById(R.id.btn_follow)
        tvCommentsCount = findViewById(R.id.tv_comments_count)
        progressLoading = findViewById(R.id.pb_loading)
        
        tvTime = findViewById(R.id.tv_story_time)
        tvViews = findViewById(R.id.tv_story_views)
        layoutProgressBars = findViewById(R.id.layout_progress_bars)
        viewTouchOverlay = findViewById(R.id.view_touch_overlay)
        btnOptions = findViewById(R.id.btn_story_options)
        storyContentContainer = findViewById(R.id.story_content_container)
        reactionAnimationLayer = findViewById(R.id.reaction_animation_layer)
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btn_close_story).setOnClickListener { finish() }

        // التفاعلات
        findViewById<TextView>(R.id.btn_react_heart).setOnClickListener { reactToStory("❤️") }
        findViewById<TextView>(R.id.btn_react_fire).setOnClickListener { reactToStory("🔥") }
        findViewById<TextView>(R.id.btn_react_laugh).setOnClickListener { reactToStory("😂") }

        findViewById<LinearLayout>(R.id.btn_open_comments).setOnClickListener {
            if (storiesArray.length() > 0) {
                pauseStory()
                val currentStoryId = storiesArray.getJSONObject(currentIndex).getString("id")
                val bottomSheet = CommentsBottomSheet.newInstance(currentStoryId, myUserId)
                bottomSheet.show(supportFragmentManager, "CommentsBottomSheet")
            }
        }

        val openViewsListener = View.OnClickListener { openViewersSheet() }
        tvViews.setOnClickListener(openViewsListener)
        (tvViews.parent as? View)?.setOnClickListener(openViewsListener)

        val profileClickListener = View.OnClickListener {
            pauseStory()
            val intent = Intent(this, UserProfileActivity::class.java)
            intent.putExtra("targetUserId", targetUserId)
            startActivity(intent)
            finish()
        }
        ivPfp.setOnClickListener(profileClickListener)
        tvName.setOnClickListener(profileClickListener)

        btnFollow.setOnClickListener { toggleFollow() }
    }

    private fun openViewersSheet() {
        if (currentViewsArray == null || currentViewsArray!!.length() == 0) return
        pauseStory()
        val userIds = ArrayList<String>()
        for (i in 0 until currentViewsArray!!.length()) {
            userIds.add(currentViewsArray!!.getString(i))
        }
        
        val bottomSheet = StoryViewersBottomSheet()
        bottomSheet.userIds = userIds
        bottomSheet.myUserId = myUserId
        bottomSheet.reactionsJson = currentReactionsObj?.toString() ?: "{}"
        bottomSheet.onDismissAction = { resumeStory() }
        bottomSheet.show(supportFragmentManager, "StoryViewersBottomSheet")
    }

    private fun setupTouchListener() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 4.0f)
                storyContentContainer.scaleX = scaleFactor
                storyContentContainer.scaleY = scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                storyContentContainer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                scaleFactor = 1.0f
            }
        })

        var touchDownTime = 0L
        val screenWidth = resources.displayMetrics.widthPixels

        viewTouchOverlay.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    pauseStory()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resumeStory()
                    if (!scaleGestureDetector.isInProgress) {
                        val touchDuration = System.currentTimeMillis() - touchDownTime
                        if (touchDuration < 200) {
                            if (event.x > screenWidth / 2) {
                                showNextStory()
                            } else {
                                showPreviousStory()
                            }
                        }
                    }
                }
            }
            true
        }
    }

    private fun fetchPublisherInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$targetUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val name = obj.getString("name")
                        val pfpBase64 = obj.optString("pfp", "")
                        val bitmap = getSafeBitmap(pfpBase64) ?: AvatarGenerator.generateAvatar(name, targetUserId)

                        withContext(Dispatchers.Main) {
                            tvName.text = name
                            ivPfp.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun fetchStories() {
        progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/get_user_stories?targetId=$targetUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        storiesArray = obj.getJSONArray("stories")
                        withContext(Dispatchers.Main) {
                            progressLoading.visibility = View.GONE
                            if (storiesArray.length() > 0) {
                                setupProgressBars()
                                displayStory(0)
                            } else {
                                Toast.makeText(this@StoryViewerActivity, "لا توجد قصص نشطة!", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressLoading.visibility = View.GONE
                    finish()
                }
            }
        }
    }

    private fun setupProgressBars() {
        layoutProgressBars.removeAllViews()
        progressAnimators.clear()
        val weight = 1.0f / storiesArray.length()
        
        for (i in 0 until storiesArray.length()) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    setMargins(4, 0, 4, 0)
                }
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            layoutProgressBars.addView(pb)
            progressAnimators.add(pb)
        }
    }

    private fun displayStory(index: Int) {
        if (index < 0 || index >= storiesArray.length()) return
        storyJob?.cancel()
        currentIndex = index
        
        for (i in 0 until index) progressAnimators[i].progress = 100
        for (i in index until storiesArray.length()) progressAnimators[i].progress = 0

        val story = storiesArray.getJSONObject(index)
        val imageBase64 = story.optString("image", "")
        val text = story.optString("text", "")
        val storyId = story.getString("id")
        val timestamp = story.optLong("timestamp", System.currentTimeMillis())
        
        currentViewsArray = story.optJSONArray("views")
        currentReactionsObj = story.optJSONObject("reactions")

        tvCommentsCount.text = story.optInt("commentsCount", 0).toString()
        tvViews.text = (currentViewsArray?.length() ?: 0).toString()
        tvTime.text = getTimeAgo(timestamp)

        if (targetUserId == myUserId || myRole == "admin") {
            btnFollow.visibility = View.GONE
            btnOptions.visibility = View.VISIBLE
            btnOptions.setOnClickListener { 
                pauseStory()
                showStoryOptions(storyId)
            }
        } else {
            btnOptions.visibility = View.GONE
            btnFollow.visibility = View.VISIBLE
            checkFollowStatus()
        }

        if (imageBase64.isNotEmpty()) {
            val bitmap = getSafeBitmap(imageBase64)
            if (bitmap != null) {
                ivStoryImage.setImageBitmap(bitmap)
                ivStoryImage.visibility = View.VISIBLE
                tvStoryText.visibility = View.GONE
            }
        } else {
            ivStoryImage.visibility = View.GONE
            tvStoryText.text = text
            tvStoryText.visibility = View.VISIBLE
            storyContentContainer.setBackgroundColor(Color.parseColor("#${storyId.takeLast(6).padEnd(6, '0')}"))
        }

        recordStoryView(storyId)
        startStoryTimer(index)
    }

    private fun startStoryTimer(index: Int) {
        timeLeft = STORY_DURATION
        isPaused = false
        val pb = progressAnimators[index]
        pb.progress = 0
        
        storyJob = lifecycleScope.launch(Dispatchers.Main) {
            val interval = 50L
            while (timeLeft > 0) {
                if (!isPaused) {
                    timeLeft -= interval
                    pb.progress = ((STORY_DURATION - timeLeft) * 100 / STORY_DURATION).toInt()
                }
                delay(interval)
            }
            showNextStory()
        }
    }

    private fun pauseStory() { isPaused = true }
    private fun resumeStory() { isPaused = false }

    private fun showNextStory() {
        if (currentIndex < storiesArray.length() - 1) {
            displayStory(currentIndex + 1)
        } else {
            finish()
        }
    }

    private fun showPreviousStory() {
        if (currentIndex > 0) {
            progressAnimators[currentIndex].progress = 0
            displayStory(currentIndex - 1)
        } else {
            progressAnimators[currentIndex].progress = 0
            displayStory(currentIndex)
        }
    }

    private fun recordStoryView(storyId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/view").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("storyId", storyId).put("userId", myUserId).toString().toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    private fun showStoryOptions(storyId: String) {
        val popup = PopupMenu(this, btnOptions)
        popup.menu.add("حذف القصة 🗑️")
        popup.setOnMenuItemClickListener {
            deleteStory(storyId)
            true
        }
        popup.setOnDismissListener { resumeStory() }
        popup.show()
    }

    private fun deleteStory(storyId: String) {
        progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/delete").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("storyId", storyId).put("userId", myUserId).toString().toByteArray()) }
                
                val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                
                withContext(Dispatchers.Main) {
                    progressLoading.visibility = View.GONE
                    if (obj.optBoolean("success", false)) {
                        Toast.makeText(this@StoryViewerActivity, "تم حذف القصة", Toast.LENGTH_SHORT).show()
                        val newArray = JSONArray()
                        for (i in 0 until storiesArray.length()) {
                            if (i != currentIndex) newArray.put(storiesArray.getJSONObject(i))
                        }
                        storiesArray = newArray
                        
                        if (storiesArray.length() == 0) finish()
                        else {
                            setupProgressBars()
                            if (currentIndex >= storiesArray.length()) currentIndex = storiesArray.length() - 1
                            displayStory(currentIndex)
                        }
                    } else {
                        resumeStory()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressLoading.visibility = View.GONE
                    resumeStory()
                }
            }
        }
    }

    private fun animateFloatingEmoji(emoji: String) {
        val tvEmoji = TextView(this).apply {
            text = emoji
            textSize = 50f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 150
                marginEnd = Random.nextInt(50, 300)
            }
        }
        reactionAnimationLayer.addView(tvEmoji)
        tvEmoji.animate()
            .translationYBy(-800f)
            .alpha(0f)
            .setDuration(1500)
            .withEndAction { reactionAnimationLayer.removeView(tvEmoji) }
            .start()
    }

    private fun reactToStory(emoji: String) {
        if (storiesArray.length() == 0) return
        val currentStoryId = storiesArray.getJSONObject(currentIndex).getString("id")
        
        animateFloatingEmoji(emoji)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/react").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("storyId", currentStoryId)
                    put("userId", myUserId)
                    put("reaction", emoji)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    private fun checkFollowStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/check_follow?followerId=$myUserId&targetId=$targetUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        val isFollowing = obj.getBoolean("isFollowing")
                        withContext(Dispatchers.Main) { updateFollowButtonUI(isFollowing) }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun toggleFollow() {
        btnFollow.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/follow").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("followerId", myUserId).put("targetId", targetUserId).toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        val isFollowing = obj.getBoolean("isFollowing")
                        withContext(Dispatchers.Main) {
                            updateFollowButtonUI(isFollowing)
                            btnFollow.isEnabled = true
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { btnFollow.isEnabled = true }
            }
        }
    }

    private fun updateFollowButtonUI(isFollowing: Boolean) {
        if (isFollowing) {
            btnFollow.text = "متابَع ✔"
            btnFollow.setBackgroundColor(Color.parseColor("#33FFFFFF"))
            btnFollow.setTextColor(Color.WHITE)
        } else {
            btnFollow.text = "متابعة"
            btnFollow.setBackgroundColor(Color.parseColor("#2196F3"))
            btnFollow.setTextColor(Color.WHITE)
        }
    }

    private fun getTimeAgo(timeInMillis: Long): String {
        val diff = System.currentTimeMillis() - timeInMillis
        val minutes = diff / (60 * 1000)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "الآن"
            minutes == 1L -> "منذ دقيقة"
            minutes == 2L -> "منذ دقيقتين"
            minutes in 3..10 -> "منذ $minutes دقائق"
            minutes < 60 -> "منذ $minutes دقيقة"
            hours == 1L -> "منذ ساعة"
            hours == 2L -> "منذ ساعتين"
            hours in 3..10 -> "منذ $hours ساعات"
            hours < 24 -> "منذ $hours ساعة"
            days == 1L -> "منذ يوم"
            days == 2L -> "منذ يومين"
            else -> "منذ $days أيام"
        }
    }

    private fun getSafeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrEmpty()) return null
        return try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        storyJob?.cancel()
    }
    
    override fun onResume() {
        super.onResume()
        if (isPaused) resumeStory()
    }
}

// =======================================================
// 🌟 قائمة المشاهدات والتفاعلات الخاصة بكل مشاهد 🌟
// =======================================================
class StoryViewersBottomSheet : BottomSheetDialogFragment() {
    var userIds: List<String> = listOf()
    var myUserId: String = ""
    var reactionsJson: String = "{}"
    var onDismissAction: (() -> Unit)? = null

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissAction?.invoke()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            setPadding(0, 40, 0, 0)
        }

        val title = TextView(context).apply {
            text = "المشاهدات (${userIds.size})"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        layout.addView(title)

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ViewersAdapter(userIds, reactionsJson)
        }
        layout.addView(recyclerView)
        return layout
    }

    inner class ViewersAdapter(private val ids: List<String>, reactionsStr: String) : RecyclerView.Adapter<ViewersAdapter.ViewHolder>() {
        
        private val reactionsMap = JSONObject(reactionsStr)

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutAvatarContainer: FrameLayout = view.findViewById(R.id.layout_item_avatar_container)
            val ivPfp: ImageView = view.findViewById(R.id.iv_item_pfp)
            val tvName: TextView = view.findViewById(R.id.tv_item_name)
            val tvUsername: TextView = view.findViewById(R.id.tv_item_username)
            val btnFollow: MaterialButton = view.findViewById(R.id.btn_item_follow)
            val tvReaction: TextView = view.findViewById(R.id.tv_item_reaction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_connection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val id = ids[position]
            holder.tvName.text = "جاري التحميل..."
            holder.tvUsername.text = "ID: $id"
            holder.btnFollow.visibility = View.GONE
            
            val userReaction = reactionsMap.optString(id, "")
            if (userReaction.isNotEmpty()) {
                holder.tvReaction.text = userReaction
                holder.tvReaction.visibility = View.VISIBLE
            } else {
                holder.tvReaction.visibility = View.GONE
            }

            var isFollowingLocal = false

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://education.ashor.shop/auth/get_user?id=$id").openConnection() as HttpURLConnection
                    if (conn.responseCode == 200) {
                        val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                        if (obj.getBoolean("success")) {
                            val name = obj.getString("name")
                            val pfp = obj.optString("pfp", "")
                            val hasActiveStory = obj.getBoolean("hasActiveStory")
                            
                            val followConn = URL("https://education.ashor.shop/social/check_follow?followerId=$myUserId&targetId=$id").openConnection() as HttpURLConnection
                            if (followConn.responseCode == 200) {
                                isFollowingLocal = JSONObject(BufferedReader(InputStreamReader(followConn.inputStream)).readText()).optBoolean("isFollowing", false)
                            }

                            withContext(Dispatchers.Main) {
                                holder.tvName.text = name
                                val b = Base64.decode(if (pfp.contains(",")) pfp.substringAfter(",") else pfp, Base64.DEFAULT)
                                val bitmap = try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch(e:Exception){null} ?: AvatarGenerator.generateAvatar(name, id)
                                holder.ivPfp.setImageDrawable(RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true })

                                if (hasActiveStory) {
                                    holder.layoutAvatarContainer.background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(6, Color.parseColor("#2196F3"))
                                        setColor(Color.TRANSPARENT)
                                    }
                                    holder.layoutAvatarContainer.setPadding(6, 6, 6, 6)
                                } else {
                                    holder.layoutAvatarContainer.background = null
                                    holder.layoutAvatarContainer.setPadding(0, 0, 0, 0)
                                }

                                if (id != myUserId) {
                                    holder.btnFollow.visibility = View.VISIBLE
                                    updateFollowBtn(holder.btnFollow, isFollowingLocal)
                                    holder.btnFollow.setOnClickListener { 
                                        toggleFollow(id, holder.btnFollow, isFollowingLocal) { newState ->
                                            isFollowingLocal = newState
                                        } 
                                    }
                                }

                                holder.itemView.setOnClickListener {
                                    dismiss()
                                    val intent = Intent(context, if (hasActiveStory) StoryViewerActivity::class.java else UserProfileActivity::class.java)
                                    intent.putExtra("targetUserId", id)
                                    context?.startActivity(intent)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        override fun getItemCount(): Int = ids.size

        private fun updateFollowBtn(btn: MaterialButton, isFollowing: Boolean) {
            if (isFollowing) {
                btn.text = "متابَع ✔"
                btn.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.text = "متابعة"
                btn.setBackgroundColor(Color.parseColor("#2196F3"))
                btn.setTextColor(Color.WHITE)
            }
        }
        
        private fun toggleFollow(id: String, btn: MaterialButton, currentStatus: Boolean, callback: (Boolean) -> Unit) {
            btn.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://education.ashor.shop/social/follow").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(JSONObject().put("followerId", myUserId).put("targetId", id).toString().toByteArray()) }
                    if (conn.responseCode == 200) {
                        val isNowFollowing = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getBoolean("isFollowing")
                        withContext(Dispatchers.Main) { 
                            updateFollowBtn(btn, isNowFollowing)
                            callback(isNowFollowing) 
                            btn.isEnabled = true 
                        }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { btn.isEnabled = true } }
            }
        }
    }
}
