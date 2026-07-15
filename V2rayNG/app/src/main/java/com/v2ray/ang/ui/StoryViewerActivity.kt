package com.v2ray.ang.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

class StoryViewerActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private var myUserId: String = ""
    private var myRole: String = ""

    private var storiesArray = JSONArray()
    private var currentIndex = 0

    // 🌟 عناصر الواجهة 🌟
    private lateinit var ivStoryImage: ImageView
    private lateinit var tvStoryText: TextView
    private lateinit var ivPfp: ImageView
    private lateinit var tvName: TextView
    private lateinit var btnFollow: MaterialButton
    private lateinit var tvCommentsCount: TextView
    private lateinit var progressLoading: ProgressBar
    
    // 🌟 العناصر الجديدة للقصص 🌟
    private lateinit var tvTime: TextView
    private lateinit var tvViews: TextView
    private lateinit var layoutProgressBars: LinearLayout
    private lateinit var viewTouchOverlay: View
    private lateinit var btnOptions: ImageView
    private lateinit var storyContentContainer: FrameLayout

    // 🌟 متغيرات المؤقت والتكبير 🌟
    private var storyJob: Job? = null
    private val STORY_DURATION = 5000L // 5 ثواني
    private var progressAnimators = mutableListOf<ProgressBar>()
    private var isPaused = false
    private var timeLeft = STORY_DURATION
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_viewer)

        targetUserId = intent.getStringExtra("targetUserId") ?: ""
        myUserId = AuthManager.getId(this)
        myRole = AuthManager.getRole(this)

        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات القصة", Toast.LENGTH_SHORT).show()
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
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btn_close_story).setOnClickListener { finish() }

        // التفاعلات
        findViewById<TextView>(R.id.btn_react_heart).setOnClickListener { reactToStory("❤️") }
        findViewById<TextView>(R.id.btn_react_fire).setOnClickListener { reactToStory("🔥") }
        findViewById<TextView>(R.id.btn_react_laugh).setOnClickListener { reactToStory("😂") }

        // التعليقات
        findViewById<LinearLayout>(R.id.btn_open_comments).setOnClickListener {
            if (storiesArray.length() > 0) {
                pauseStory() // إيقاف القصة عند فتح التعليقات
                val currentStoryId = storiesArray.getJSONObject(currentIndex).getString("id")
                val bottomSheet = CommentsBottomSheet.newInstance(currentStoryId, myUserId)
                bottomSheet.show(supportFragmentManager, "CommentsBottomSheet")
            }
        }

        // المتابعة
        btnFollow.setOnClickListener { toggleFollow() }
    }

    private fun setupTouchListener() {
        // إعداد حساس التكبير والتصغير
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 4.0f)
                storyContentContainer.scaleX = scaleFactor
                storyContentContainer.scaleY = scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // العودة للحجم الطبيعي عند الإفلات
                storyContentContainer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                scaleFactor = 1.0f
            }
        })

        var touchDownTime = 0L

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
                        if (touchDuration < 200) { // نقرة سريعة للتقليب
                            if (event.x < resources.displayMetrics.widthPixels / 3) {
                                showPreviousStory()
                            } else {
                                showNextStory()
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
                    Toast.makeText(this@StoryViewerActivity, "خطأ في الاتصال بالإنترنت", Toast.LENGTH_SHORT).show()
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
                // يمكنك تخصيص شكل التقدم هنا أو استخدام الافتراضي
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
        
        // ضبط أشرطة التقدم
        for (i in 0 until index) progressAnimators[i].progress = 100
        for (i in index until storiesArray.length()) progressAnimators[i].progress = 0

        val story = storiesArray.getJSONObject(index)
        val imageBase64 = story.optString("image", "")
        val text = story.optString("text", "")
        val storyId = story.getString("id")
        val timestamp = story.optLong("timestamp", System.currentTimeMillis())
        val viewsArray = story.optJSONArray("views")

        // التحديثات النصية
        tvCommentsCount.text = story.optInt("commentsCount", 0).toString()
        tvViews.text = (viewsArray?.length() ?: 0).toString()
        tvTime.text = getTimeAgo(timestamp)

        // التحكم بظهور أزرار المتابعة والحذف
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

        // عرض المحتوى
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

    // 🌟 الدوال الخدمية (مشاهدات، حذف، تفاعل، ومتابعة) 🌟

    private fun recordStoryView(storyId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/view").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().put("storyId", storyId).put("userId", myUserId)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
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
                val payload = JSONObject().put("storyId", storyId).put("userId", myUserId)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                
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
                        Toast.makeText(this@StoryViewerActivity, "فشل الحذف", Toast.LENGTH_SHORT).show()
                        resumeStory()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressLoading.visibility = View.GONE
                    Toast.makeText(this@StoryViewerActivity, "خطأ في الاتصال", Toast.LENGTH_SHORT).show()
                    resumeStory()
                }
            }
        }
    }

    private fun reactToStory(emoji: String) {
        if (storiesArray.length() == 0) return
        val currentStoryId = storiesArray.getJSONObject(currentIndex).getString("id")
        Toast.makeText(this, "تم التفاعل $emoji", Toast.LENGTH_SHORT).show()

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
                        withContext(Dispatchers.Main) {
                            updateFollowButtonUI(isFollowing)
                        }
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
                val payload = JSONObject().apply {
                    put("followerId", myUserId)
                    put("targetId", targetUserId)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

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
        storyJob?.cancel() // إيقاف المؤقت عند الخروج
    }
    
    // استئناف القصة إذا تم إغلاق التعليقات والعودة للنشاط
    override fun onResume() {
        super.onResume()
        if (isPaused) resumeStory()
    }
}
