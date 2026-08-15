package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
import java.io.File
import java.io.FileOutputStream
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

    // 🌟 مصفوفات التنقل الذكي (Smart Feed) 🌟
    private var usersWithStoriesList = mutableListOf<String>()
    private var previousUsersStack = mutableListOf<String>()

    // 🌟 نظام التخزين المؤقت الذكي (Cache) لآخر 10 استوريات (صور أو ملفات فيديو) 🌟
    private val storyMediaCache = LruCache<String, Any>(10) // يخزن Bitmap للصور أو String لمسار الفيديو

    private lateinit var ivStoryImage: ImageView
    private lateinit var vvStoryVideo: VideoView
    private lateinit var tvStoryText: TextView
    private lateinit var ivPfp: ImageView
    private lateinit var tvName: TextView
    private lateinit var btnFollow: MaterialButton
    private lateinit var tvCommentsCount: TextView
    private lateinit var progressLoading: ProgressBar
    
    private lateinit var tvTime: TextView
    private lateinit var tvViews: TextView
    private lateinit var layoutViewsContainer: LinearLayout
    private lateinit var layoutProgressBars: LinearLayout
    private lateinit var viewTouchOverlay: View
    private lateinit var btnOptions: ImageView
    private lateinit var storyContentContainer: FrameLayout
    private lateinit var reactionAnimationLayer: FrameLayout

    // 🌟 المحرك الجديد للأنيميشن السلس (بدل الـ Coroutines Delay) 🌟
    private var progressAnimator: ValueAnimator? = null
    private val STORY_DURATION = 5000L 
    private var progressBarsList = mutableListOf<ProgressBar>()
    private var isPaused = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_viewer)

        targetUserId = intent.getStringExtra("targetUserId") ?: intent.getStringExtra("userId") ?: ""
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

        // نجلب المستخدمين للتقليب العشوائي، ثم نجلب قصة الشخص الحالي
        buildSmartUsersFeed {
            fetchPublisherInfo(targetUserId)
            fetchStories(targetUserId, 0)
        }
    }

    private fun initViews() {
        ivStoryImage = findViewById(R.id.iv_story_image)
        vvStoryVideo = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER }
            visibility = View.GONE
        }
        
        storyContentContainer = findViewById(R.id.story_content_container)
        storyContentContainer.addView(vvStoryVideo, 0) 
        
        tvStoryText = findViewById(R.id.tv_story_text)
        ivPfp = findViewById(R.id.iv_story_avatar)
        tvName = findViewById(R.id.tv_story_username)
        btnFollow = findViewById(R.id.btn_follow)
        tvCommentsCount = findViewById(R.id.tv_comments_count)
        progressLoading = findViewById(R.id.pb_loading)
        
        tvTime = findViewById(R.id.tv_story_time)
        tvViews = findViewById(R.id.tv_story_views)
        layoutViewsContainer = findViewById(R.id.layout_story_views_container)
        layoutProgressBars = findViewById(R.id.layout_progress_bars)
        
        // 🌟 فرض اتجاه شريط التقدم ليكون من اليمين لليسار (RTL) 🌟
        layoutProgressBars.layoutDirection = View.LAYOUT_DIRECTION_RTL
        
        viewTouchOverlay = findViewById(R.id.view_touch_overlay)
        btnOptions = findViewById(R.id.btn_story_options)
        reactionAnimationLayer = findViewById(R.id.reaction_animation_layer)
    }

    // 🌟 جلب جميع المستخدمين النشطين وبناء طابور العرض 🌟
    private fun buildSmartUsersFeed(onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/admin/get_all_users").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val usersArray = JSONArray(resp)
                    val tempList = mutableListOf<String>()
                    
                    for (i in 0 until usersArray.length()) {
                        val uObj = usersArray.getJSONObject(i)
                        val uId = uObj.getString("id")
                        val hasStory = uObj.optBoolean("hasActiveStory", false)
                        
                        if (hasStory && uId != targetUserId && uId != myUserId) {
                            tempList.add(uId)
                        }
                    }
                    tempList.shuffle() 
                    usersWithStoriesList.addAll(tempList)
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btn_close_story).setOnClickListener { finish() }

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
                            if (event.x < screenWidth / 2) { 
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

    private fun fetchPublisherInfo(uId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$uId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val name = obj.getString("name")
                        val pfpBase64 = obj.optString("pfp", "")
                        val bitmap = getSafeBitmap(pfpBase64) ?: AvatarGenerator.generateAvatar(name, uId)

                        withContext(Dispatchers.Main) {
                            tvName.text = name
                            ivPfp.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun fetchStories(uId: String, startIndex: Int = 0) {
        progressLoading.visibility = View.VISIBLE
        // 🌟 نطلب القصص بمحاولات متكررة: إذا ماكو نت نستنى ونعيد المحاولة، ما نسكر الشاشة 🌟
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && uId == targetUserId) {
                try {
                    val conn = URL("$BASE_API_URL/story/get_user_stories?targetId=$uId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            storiesArray = obj.getJSONArray("stories")
                            withContext(Dispatchers.Main) {
                                progressLoading.visibility = View.GONE
                                if (storiesArray.length() > 0) {
                                    setupProgressBars()
                                    val startAt = if (startIndex == -1) storiesArray.length() - 1 else 0
                                    displayStory(startAt)
                                } else {
                                    jumpToNextUserStory()
                                }
                            }
                            return@launch
                        }
                    }
                    delay(2500)
                } catch (e: Exception) {
                    // ماكو نت أو فشل الاتصال: نستنى شوي ونعاود المحاولة تلقائياً بدون إغلاق الشاشة
                    delay(2500)
                }
            }
        }
    }

    private fun setupProgressBars() {
        layoutProgressBars.removeAllViews()
        progressBarsList.clear()
        val weight = 1.0f / storiesArray.length()
        
        for (i in 0 until storiesArray.length()) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    setMargins(4, 0, 4, 0)
                }
                max = 10000 // دقة عالية جداً للأنيميشن السلس (60fps)
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            layoutProgressBars.addView(pb)
            progressBarsList.add(pb)
        }
    }

    // 🌟 قلب المحرك الخرافي: التخزين المؤقت، التحميل المسبق، وعرض 0 شاشة سودة 🌟
    private fun displayStory(index: Int) {
        if (index < 0 || index >= storiesArray.length()) return
        progressAnimator?.cancel()
        currentIndex = index
        
        vvStoryVideo.stopPlayback()
        vvStoryVideo.visibility = View.GONE

        // تصفير وتعبئة الأشرطة
        for (i in 0 until index) progressBarsList[i].progress = 10000
        for (i in index until storiesArray.length()) progressBarsList[i].progress = 0

        val story = storiesArray.getJSONObject(index)
        val type = story.optString("type", "image") 
        val mediaBase64 = story.optString("image", "") 
        val textOrOverlay = story.optString("text", "") 
        val storyId = story.getString("id")
        val timestamp = story.optLong("timestamp", System.currentTimeMillis())
        
        currentViewsArray = story.optJSONArray("views")
        currentReactionsObj = story.optJSONObject("reactions")

        tvCommentsCount.text = story.optInt("commentsCount", 0).toString()
        tvViews.text = (currentViewsArray?.length() ?: 0).toString()
        tvTime.text = getTimeAgo(timestamp)

        // 🌟 عدد المشاهدات يبان بس لصاحب القصة أو الأدمن، وباقي المستخدمين ما يشوفونه ولا الزر 🌟
        val isOwnerOrAdmin = (targetUserId == myUserId || myRole == "admin")
        layoutViewsContainer.visibility = if (isOwnerOrAdmin) View.VISIBLE else View.GONE

        if (isOwnerOrAdmin) {
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

        // 🌟 فحص الكاش (Cache) قبل الاتصال بالسيرفر 🌟
        val cachedContent = storyMediaCache.get(storyId)

        if (type == "video") {
            tvStoryText.visibility = View.GONE
            vvStoryVideo.visibility = View.VISIBLE
            
            if (textOrOverlay.isNotEmpty()) {
                val overlayBitmap = getSafeBitmap(textOrOverlay)
                ivStoryImage.setImageBitmap(overlayBitmap)
                ivStoryImage.visibility = View.VISIBLE
            } else {
                ivStoryImage.visibility = View.GONE
            }

            if (cachedContent is String && File(cachedContent).exists()) {
                // الفيديو موجود بالكاش، تشغيل فوري (0 ثانية)
                progressLoading.visibility = View.GONE
                playVideo(cachedContent, index)
            } else {
                // 🌟 الفيديو غير موجود بالكاش: نحمله من السيرفر، وإذا ماكو نت نستنى ونعيد المحاولة
                // بدون ما نتخطى القصة ولا نحرك شريط التقدم لين يوصل الفيديو فعلياً 🌟
                progressLoading.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    val cachedFile = File(cacheDir, "vid_${storyId}.mp4")
                    while (isActive && currentIndex == index) {
                        try {
                            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                                val url = URL("$BASE_API_URL/story/stream_video?storyId=$storyId")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "GET"
                                conn.connectTimeout = 15000
                                conn.readTimeout = 20000
                                conn.connect()

                                if (conn.responseCode in 200..299) {
                                    val inputStream = conn.inputStream
                                    val fos = FileOutputStream(cachedFile)
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        fos.write(buffer, 0, bytesRead)
                                    }
                                    fos.flush()
                                    fos.close()
                                    inputStream.close()

                                    // حفظ المسار بالكاش
                                    storyMediaCache.put(storyId, cachedFile.absolutePath)
                                } else {
                                    throw Exception("HTTP ${conn.responseCode}")
                                }
                            } else {
                                storyMediaCache.put(storyId, cachedFile.absolutePath)
                            }

                            withContext(Dispatchers.Main) {
                                if (currentIndex == index) {
                                    progressLoading.visibility = View.GONE
                                    playVideo(cachedFile.absolutePath, index)
                                }
                            }
                            return@launch
                        } catch (e: Exception) {
                            cachedFile.delete()
                            // ماكو نت: نستنى شوي (الشريط بالأعلى يضل واقف عالصفر) وبعدين نعيد المحاولة تلقائياً
                            delay(2500)
                        }
                    }
                }
            }
        } else {
            // معالجة الصور والنصوص مع الكاش
            vvStoryVideo.visibility = View.GONE
            progressLoading.visibility = View.GONE
            
            if (mediaBase64.isNotEmpty()) {
                val bitmap = if (cachedContent is Bitmap) cachedContent else getSafeBitmap(mediaBase64)
                if (bitmap != null) {
                    if (cachedContent == null) storyMediaCache.put(storyId, bitmap) // حفظ الصورة بالكاش
                    ivStoryImage.setImageBitmap(bitmap)
                    ivStoryImage.visibility = View.VISIBLE
                    tvStoryText.visibility = View.GONE
                }
            } else {
                ivStoryImage.visibility = View.GONE
                tvStoryText.text = textOrOverlay
                tvStoryText.visibility = View.VISIBLE
                storyContentContainer.setBackgroundColor(Color.parseColor("#${storyId.takeLast(6).padEnd(6, '0')}"))
            }
            startStoryTimer(index, STORY_DURATION)
        }

        recordStoryView(storyId)
        
        // 🌟 التحضير المسبق (Pre-buffering) للمحتوى القادم 🌟
        preloadNextContent(index)
    }

    private fun playVideo(videoPath: String, index: Int) {
        vvStoryVideo.setVideoPath(videoPath)
        vvStoryVideo.setOnPreparedListener { mp ->
            mp.isLooping = true 
            val duration = mp.duration.toLong()

            // 🌟 تكبير الفيديو (Crop) ليملأ الشاشة بالكامل ويوصل حافة الأعلى بدون أي فراغ،
            // مثبّت من فوق (Gravity.TOP) والزيادة تنكرز تلقائياً من الأسفل 🌟
            val videoW = mp.videoWidth
            val videoH = mp.videoHeight
            if (videoW > 0 && videoH > 0) {
                storyContentContainer.post {
                    val containerW = storyContentContainer.width
                    val containerH = storyContentContainer.height
                    if (containerW > 0 && containerH > 0) {
                        val scale = maxOf(containerW.toFloat() / videoW, containerH.toFloat() / videoH)
                        val newW = (videoW * scale).toInt()
                        val newH = (videoH * scale).toInt()
                        vvStoryVideo.layoutParams = FrameLayout.LayoutParams(newW, newH).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        }
                    }
                }
            }

            mp.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    ivStoryImage.visibility = View.GONE
                    startStoryTimer(index, if (duration > 0) duration else STORY_DURATION)
                    true
                } else false
            }
            mp.start()
        }
        vvStoryVideo.setOnErrorListener { _, _, _ ->
            File(cacheDir, "vid_${storiesArray.getJSONObject(index).getString("id")}.mp4").delete()
            Toast.makeText(this@StoryViewerActivity, "خطأ في تشغيل الفيديو", Toast.LENGTH_SHORT).show()
            showNextStory()
            true
        }
    }

    // 🌟 دالة التحميل المسبق العميق 🌟
    private fun preloadNextContent(currentIndex: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (currentIndex < storiesArray.length() - 1) {
                    val nextStory = storiesArray.getJSONObject(currentIndex + 1)
                    val nextStoryId = nextStory.getString("id")
                    
                    if (storyMediaCache.get(nextStoryId) == null) {
                        if (nextStory.optString("type") == "video") {
                            val cachedFile = File(cacheDir, "vid_${nextStoryId}.mp4")
                            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                                val url = URL("$BASE_API_URL/story/stream_video?storyId=$nextStoryId")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "GET"
                                conn.connectTimeout = 15000
                                conn.readTimeout = 20000
                                
                                if (conn.responseCode in 200..299) {
                                    val inputStream = conn.inputStream
                                    val fos = FileOutputStream(cachedFile)
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        fos.write(buffer, 0, bytesRead)
                                    }
                                    fos.flush()
                                    fos.close()
                                    inputStream.close()
                                    storyMediaCache.put(nextStoryId, cachedFile.absolutePath)
                                }
                            } else {
                                storyMediaCache.put(nextStoryId, cachedFile.absolutePath)
                            }
                        } else {
                            val imgBase64 = nextStory.optString("image", "")
                            if (imgBase64.isNotEmpty()) {
                                val bmp = getSafeBitmap(imgBase64)
                                if (bmp != null) storyMediaCache.put(nextStoryId, bmp)
                            }
                        }
                    }
                } 
            } catch (e: Exception) {}
        }
    }

    // 🌟 محرك الأنيميشن السلس جداً (Smooth Animation 60fps) 🌟
    private fun startStoryTimer(index: Int, duration: Long) {
        progressAnimator?.cancel()
        val pb = progressBarsList[index]
        pb.progress = 0
        isPaused = false

        progressAnimator = ValueAnimator.ofInt(0, 10000).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (!isPaused) {
                    pb.progress = animation.animatedValue as Int
                } else {
                    animation.cancel() // الإيقاف عند اللمس
                }
            }
            // عند انتهاء الأنيميشن بنجاح ننتقل للقصة التالية
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isPaused && pb.progress >= 9900) {
                        showNextStory()
                    }
                }
            })
        }
        progressAnimator?.start()
    }

    private fun pauseStory() { 
        isPaused = true
        progressAnimator?.pause()
        if (vvStoryVideo.visibility == View.VISIBLE && vvStoryVideo.isPlaying) vvStoryVideo.pause()
    }
    
    private fun resumeStory() { 
        isPaused = false
        progressAnimator?.resume()
        if (vvStoryVideo.visibility == View.VISIBLE) vvStoryVideo.start()
    }

    private fun showNextStory() {
        if (currentIndex < storiesArray.length() - 1) {
            displayStory(currentIndex + 1)
        } else {
            jumpToNextUserStory()
        }
    }

    private fun showPreviousStory() {
        if (currentIndex > 0) {
            progressBarsList[currentIndex].progress = 0
            displayStory(currentIndex - 1)
        } else {
            jumpToPreviousUserStory()
        }
    }

    private fun jumpToNextUserStory() {
        if (usersWithStoriesList.isNotEmpty()) {
            previousUsersStack.add(targetUserId)
            targetUserId = usersWithStoriesList.removeAt(0)
            
            fetchPublisherInfo(targetUserId)
            fetchStories(targetUserId, 0)
        } else {
            finish()
        }
    }

    private fun jumpToPreviousUserStory() {
        if (previousUsersStack.isNotEmpty()) {
            usersWithStoriesList.add(0, targetUserId) 
            targetUserId = previousUsersStack.removeAt(previousUsersStack.size - 1)
            
            fetchPublisherInfo(targetUserId)
            fetchStories(targetUserId, -1)
        } else {
            progressBarsList[0].progress = 0
            displayStory(0)
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
                        
                        if (storiesArray.length() == 0) jumpToNextUserStory()
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

    // 🌟 التنظيف التام عند الخروج لمنع امتلاء مساحة الهاتف 🌟
    override fun onDestroy() {
        super.onDestroy()
        progressAnimator?.cancel()
        vvStoryVideo.stopPlayback()
        storyMediaCache.evictAll() // مسح الكاش من الرام
        clearVideoCache() // مسح الفيديوهات المؤقتة من الذاكرة
    }
    
    private fun clearVideoCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("vid_") && file.name.endsWith(".mp4")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {}
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