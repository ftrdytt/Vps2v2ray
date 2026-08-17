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
import android.text.InputType
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
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

    companion object {
        val globalStoryCache = LruCache<String, Any>(30)
        private const val PRELOAD_API_URL = "https://education.ashor.shop"
        private val preloadedUsers = mutableSetOf<String>()
        private const val MAX_CACHED_VIDEO_FILES = 20

        val storiesCache = mutableMapOf<String, String>() 

        fun preloadUserStories(context: Context, targetUserId: String, viewerId: String, maxCount: Int = 5) {
            if (targetUserId.isEmpty() || !preloadedUsers.add(targetUserId)) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val conn = URL("$PRELOAD_API_URL/story/get_user_stories?targetId=$targetUserId&viewerId=$viewerId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.optBoolean("success", false)) {
                            val arr = obj.getJSONArray("stories")
                            storiesCache[targetUserId] = arr.toString()
                            
                            val count = minOf(arr.length(), maxCount)
                            for (i in 0 until count) {
                                preloadSingleStoryStatic(context, arr.getJSONObject(i))
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        private fun preloadSingleStoryStatic(context: Context, story: JSONObject) {
            val storyId = story.optString("id")
            if (storyId.isEmpty() || globalStoryCache.get(storyId) != null) return
            try {
                if (story.optString("type") == "video") {
                    val cachedFile = File(context.cacheDir, "vid_${storyId}.mp4")
                    if (!cachedFile.exists() || cachedFile.length() == 0L) {
                        val vConn = URL("$PRELOAD_API_URL/story/stream_video?storyId=$storyId").openConnection() as HttpURLConnection
                        vConn.connectTimeout = 15000; vConn.readTimeout = 20000
                        if (vConn.responseCode in 200..299) {
                            val ins = vConn.inputStream
                            val fos = FileOutputStream(cachedFile)
                            val buf = ByteArray(8192); var n: Int
                            while (ins.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                            fos.flush(); fos.close(); ins.close()
                            globalStoryCache.put(storyId, cachedFile.absolutePath)
                            pruneVideoDiskCache(context)
                        }
                    } else {
                        globalStoryCache.put(storyId, cachedFile.absolutePath)
                    }
                } else {
                    val b64 = story.optString("image", "")
                    if (b64.isNotEmpty()) {
                        val clean = if (b64.contains(",")) b64.substringAfter(",") else b64
                        val bytes = Base64.decode(clean.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) globalStoryCache.put(storyId, bmp)
                    }
                }
            } catch (e: Exception) {}
        }

        fun pruneVideoDiskCache(context: Context) {
            try {
                val files = context.cacheDir.listFiles { f -> f.name.startsWith("vid_") && f.name.endsWith(".mp4") } ?: return
                if (files.size <= MAX_CACHED_VIDEO_FILES) return
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_CACHED_VIDEO_FILES)
                    .forEach { it.delete() }
            } catch (e: Exception) {}
        }
    }

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private var myUserId: String = ""
    private var myRole: String = ""

    private var storiesArray = JSONArray()
    private var currentIndex = 0
    private var currentViewsArray: JSONArray? = null 
    private var currentReactionsObj: JSONObject? = null

    private var usersWithStoriesList = mutableListOf<String>()
    private var previousUsersStack = mutableListOf<String>()

    private val storyMediaCache get() = globalStoryCache
    private var preloadJob: Job? = null

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
        
        layoutProgressBars.layoutDirection = View.LAYOUT_DIRECTION_RTL
        
        viewTouchOverlay = findViewById(R.id.view_touch_overlay)
        btnOptions = findViewById(R.id.btn_story_options)
        reactionAnimationLayer = findViewById(R.id.reaction_animation_layer)
    }

    private fun buildSmartUsersFeed(onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/get_smart_feed?myId=$myUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.optBoolean("success", false)) {
                        val usersArray = obj.optJSONArray("feed") ?: JSONArray()
                        val tempList = mutableListOf<String>()
                        
                        for (i in 0 until usersArray.length()) {
                            val uId = usersArray.getString(i)
                            if (uId != targetUserId && uId != myUserId) {
                                tempList.add(uId)
                            }
                        }
                        usersWithStoriesList.addAll(tempList)
                    }
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    private fun animateReactionClick(view: View) {
        view.animate()
            .scaleX(1.4f)
            .scaleY(1.4f)
            .setDuration(150)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btn_close_story).setOnClickListener { finish() }

        findViewById<TextView>(R.id.btn_react_heart).setOnClickListener { 
            animateReactionClick(it)
            reactToStory("❤️") 
        }
        findViewById<TextView>(R.id.btn_react_fire).setOnClickListener { 
            animateReactionClick(it)
            reactToStory("🔥") 
        }
        findViewById<TextView>(R.id.btn_react_laugh).setOnClickListener { 
            animateReactionClick(it)
            reactToStory("😂") 
        }

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

    private var panX = 0f
    private var panY = 0f
    private var rotationAngle = 0f
    private var lastRotationTouchAngle = 0f
    private var lastPanRawX = 0f
    private var lastPanRawY = 0f

    private fun resetZoomPanRotation() {
        scaleFactor = 1.0f
        panX = 0f; panY = 0f
        rotationAngle = 0f
        storyContentContainer.scaleX = 1f
        storyContentContainer.scaleY = 1f
        storyContentContainer.translationX = 0f
        storyContentContainer.translationY = 0f
        storyContentContainer.rotation = 0f
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
        })

        var touchDownTime = 0L
        var downX = 0f
        var downY = 0f
        var isSwiping = false
        val screenWidth = resources.displayMetrics.widthPixels
        val swipeThreshold = screenWidth * 0.18f

        viewTouchOverlay.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.pointerCount == 2) {
                val dxA = (event.getX(0) - event.getX(1)).toDouble()
                val dyA = (event.getY(0) - event.getY(1)).toDouble()
                val angle = Math.toDegrees(Math.atan2(dyA, dxA)).toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> lastRotationTouchAngle = angle
                    MotionEvent.ACTION_MOVE -> {
                        rotationAngle += (angle - lastRotationTouchAngle)
                        storyContentContainer.rotation = rotationAngle
                        lastRotationTouchAngle = angle
                    }
                }
            }

            if (scaleFactor > 1.01f && event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { lastPanRawX = event.rawX; lastPanRawY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        panX += (event.rawX - lastPanRawX)
                        panY += (event.rawY - lastPanRawY)
                        storyContentContainer.translationX = panX
                        storyContentContainer.translationY = panY
                        lastPanRawX = event.rawX; lastPanRawY = event.rawY
                    }
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    downX = event.x; downY = event.y
                    isSwiping = false
                    pauseStory()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor <= 1.01f) {
                        val dx = event.x - downX
                        if (kotlin.math.abs(dx) > 25 && kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY)) {
                            isSwiping = true
                            storyContentContainer.translationX = dx * 0.5f
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    var navigated = false
                    if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                        val dx = event.x - downX
                        val touchDuration = System.currentTimeMillis() - touchDownTime

                        if (isSwiping && kotlin.math.abs(dx) > swipeThreshold) {
                            navigated = true
                            if (dx > 0) showNextStory() else showPreviousStory()
                        } else if (isSwiping) {
                            storyContentContainer.animate().translationX(0f).setDuration(200).start()
                        } else if (touchDuration < 200) {
                            navigated = true
                            if (event.x < screenWidth / 2) {
                                showNextStory()
                            } else {
                                showPreviousStory()
                            }
                        }
                    }
                    isSwiping = false
                    if (!navigated) resumeStory()
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
        val preloadedStr = storiesCache[uId]
        if (preloadedStr != null) {
            try {
                val preloaded = JSONArray(preloadedStr)
                if (preloaded.length() > 0) {
                    storiesArray = preloaded
                    progressLoading.visibility = View.GONE
                    setupProgressBars()
                    val startAt = if (startIndex == -1) storiesArray.length() - 1 else 0
                    displayStory(startAt)
                    return
                }
            } catch (e: Exception) {}
        }

        progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && uId == targetUserId) {
                try {
                    val conn = URL("$BASE_API_URL/story/get_user_stories?targetId=$uId&viewerId=$myUserId").openConnection() as HttpURLConnection
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
                max = 10000 
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            layoutProgressBars.addView(pb)
            progressBarsList.add(pb)
        }
    }

    private fun displayStory(index: Int) {
        if (index < 0 || index >= storiesArray.length()) return
        progressAnimator?.cancel()
        currentIndex = index

        resetZoomPanRotation()

        vvStoryVideo.stopPlayback()
        vvStoryVideo.visibility = View.GONE

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

        val isOwnerOrAdmin = (targetUserId == myUserId || myRole == "admin")
        layoutViewsContainer.visibility = if (isOwnerOrAdmin) View.VISIBLE else View.GONE

        btnOptions.visibility = View.VISIBLE
        btnFollow.visibility = if (isOwnerOrAdmin) View.GONE else View.VISIBLE
        if (!isOwnerOrAdmin) checkFollowStatus()

        btnOptions.setOnClickListener { 
            pauseStory()
            showStoryOptions(storyId, targetUserId)
        }

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
                progressLoading.visibility = View.GONE
                playVideo(cachedContent, index)
            } else {
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
                            delay(2500)
                        }
                    }
                }
            }
        } else {
            vvStoryVideo.visibility = View.GONE
            progressLoading.visibility = View.GONE
            
            if (mediaBase64.isNotEmpty()) {
                val bitmap = if (cachedContent is Bitmap) cachedContent else getSafeBitmap(mediaBase64)
                if (bitmap != null) {
                    if (cachedContent == null) storyMediaCache.put(storyId, bitmap) 
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
        startDeepPreload(index)
    }

    private fun playVideo(videoPath: String, index: Int) {
        vvStoryVideo.setVideoPath(videoPath)
        var timerStarted = false
        vvStoryVideo.setOnPreparedListener { mp ->
            mp.isLooping = true 
            val duration = mp.duration.toLong()

            val videoW = mp.videoWidth
            val videoH = mp.videoHeight
            if (videoW > 0 && videoH > 0) {
                storyContentContainer.post {
                    val containerW = storyContentContainer.width
                    if (containerW > 0) {
                        val scale = containerW.toFloat() / videoW
                        val newW = containerW
                        val newH = (videoH * scale).toInt()
                        vvStoryVideo.layoutParams = FrameLayout.LayoutParams(newW, newH).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        }
                    }
                }
            }

            fun startTimerOnce() {
                if (!timerStarted && currentIndex == index) {
                    timerStarted = true
                    ivStoryImage.visibility = View.GONE
                    startStoryTimer(index, if (duration > 0) duration else STORY_DURATION)
                }
            }

            mp.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    startTimerOnce()
                    true
                } else false
            }
            mp.start()
            vvStoryVideo.postDelayed({ startTimerOnce() }, 400)
        }
        vvStoryVideo.setOnErrorListener { _, _, _ ->
            File(cacheDir, "vid_${storiesArray.getJSONObject(index).getString("id")}.mp4").delete()
            Toast.makeText(this@StoryViewerActivity, "خطأ في تشغيل الفيديو", Toast.LENGTH_SHORT).show()
            showNextStory()
            true
        }
    }

    private fun startDeepPreload(fromIndex: Int) {
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val maxAhead = 8
                var loadedCount = 0
                var i = fromIndex + 1
                while (isActive && i < storiesArray.length() && loadedCount < maxAhead) {
                    val story = storiesArray.optJSONObject(i)
                    if (story != null) {
                        val sid = story.optString("id")
                        if (sid.isNotEmpty() && storyMediaCache.get(sid) == null) {
                            preloadStoryContent(story, sid)
                            loadedCount++
                        }
                    }
                    i++
                }

                var usersDone = 0
                for (uid in usersWithStoriesList) {
                    if (!isActive || usersDone >= 2) break
                    preloadFirstStoryOfUser(uid)
                    usersDone++
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun preloadStoryContent(story: JSONObject, storyId: String) {
        try {
            if (story.optString("type") == "video") {
                val cachedFile = File(cacheDir, "vid_${storyId}.mp4")
                if (!cachedFile.exists() || cachedFile.length() == 0L) {
                    val url = URL("$BASE_API_URL/story/stream_video?storyId=$storyId")
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
                        storyMediaCache.put(storyId, cachedFile.absolutePath)
                        pruneVideoDiskCache(this@StoryViewerActivity)
                    }
                } else {
                    storyMediaCache.put(storyId, cachedFile.absolutePath)
                }
            } else {
                val imgBase64 = story.optString("image", "")
                if (imgBase64.isNotEmpty()) {
                    val bmp = getSafeBitmap(imgBase64)
                    if (bmp != null) storyMediaCache.put(storyId, bmp)
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun preloadFirstStoryOfUser(uid: String) {
        try {
            val conn = URL("$BASE_API_URL/story/get_user_stories?targetId=$uid&viewerId=$myUserId").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val obj = JSONObject(resp)
                if (obj.optBoolean("success", false)) {
                    val arr = obj.getJSONArray("stories")
                    if (arr.length() > 0) {
                        val first = arr.getJSONObject(0)
                        val sid = first.optString("id")
                        if (sid.isNotEmpty() && storyMediaCache.get(sid) == null) {
                            preloadStoryContent(first, sid)
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

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
                    animation.cancel() 
                }
            }
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

    private fun animateStoryTransition(goNext: Boolean) {
        storyContentContainer.animate()
            .alpha(0.3f)
            .setDuration(120)
            .withEndAction {
                goToAdjacentStoryOrUser(goNext)
                if (isFinishing || isDestroyed) return@withEndAction
                storyContentContainer.alpha = 1f
            }.start()
    }

    private fun showNextStory() { animateStoryTransition(goNext = true) }

    private fun showPreviousStory() { animateStoryTransition(goNext = false) }

    private fun goToAdjacentStoryOrUser(goNext: Boolean) {
        if (goNext) {
            if (currentIndex < storiesArray.length() - 1) {
                displayStory(currentIndex + 1)
            } else {
                jumpToNextUserStory()
            }
        } else {
            if (currentIndex > 0) {
                progressBarsList[currentIndex].progress = 0
                displayStory(currentIndex - 1)
            } else {
                jumpToPreviousUserStory()
            }
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

    private fun showStoryOptions(storyId: String, storyOwnerId: String) {
        val popup = PopupMenu(this, btnOptions)
        
        if (storyOwnerId == myUserId) {
            popup.menu.add("حذف القصة 🗑️")
            popup.setOnMenuItemClickListener {
                deleteStory(storyId)
                true
            }
        } else {
            popup.menu.add("إبلاغ عن القصة 🚩")
            if (myRole == "admin") {
                popup.menu.add("حذف القصة (أدمن) 🗑️")
                popup.menu.add("حظر المستخدم (أدمن) 🚫")
            }
            
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "إبلاغ عن القصة 🚩" -> reportStory(storyId)
                    "حذف القصة (أدمن) 🗑️" -> deleteStory(storyId)
                    "حظر المستخدم (أدمن) 🚫" -> showBanDialog(storyOwnerId)
                }
                true
            }
        }
        
        popup.setOnDismissListener { resumeStory() }
        popup.show()
    }

    private fun reportStory(storyId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/report").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("storyId", storyId).put("reporterId", myUserId).toString().toByteArray()) }
                
                val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoryViewerActivity, obj.optString("message", "تم الإرسال"), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@StoryViewerActivity, "فشل الاتصال", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showBanDialog(targetId: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "عدد الساعات (مثال: 24)"
            setPadding(40, 40, 40, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("حظر من الاستوريات 🚫")
            .setView(input)
            .setPositiveButton("حظر") { _, _ ->
                val hours = input.text.toString().trim()
                if (hours.isNotEmpty()) banUserFromStories(targetId, hours)
            }
            .setNegativeButton("إلغاء") { _, _ -> resumeStory() }
            .setOnCancelListener { resumeStory() }
            .show()
    }

    private fun banUserFromStories(targetId: String, hours: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/admin/ban_story_user").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("adminId", myUserId).put("targetUserId", targetId).put("hours", hours).toString().toByteArray()) }
                
                val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoryViewerActivity, obj.optString("message", "تم الحظر"), Toast.LENGTH_LONG).show()
                    resumeStory()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(this@StoryViewerActivity, "فشل الاتصال", Toast.LENGTH_SHORT).show()
                    resumeStory()
                }
            }
        }
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
                bottomMargin = 180
                marginEnd = 100
            }
        }
        reactionAnimationLayer.addView(tvEmoji)
        
        val randomX = Random.nextInt(-300, 300).toFloat()
        tvEmoji.animate()
            .translationYBy(-(800f + Random.nextInt(300)))
            .translationXBy(randomX)
            .scaleX(1.8f)
            .scaleY(1.8f)
            .alpha(0f)
            .setDuration(2200)
            .setInterpolator(DecelerateInterpolator())
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
        preloadJob?.cancel()
        progressAnimator?.cancel()
        vvStoryVideo.stopPlayback()
    }
    
    override fun onResume() {
        super.onResume()
        if (isPaused) resumeStory()
    }
}

// 🌟 قائمة المشاهدات بتصميم VIP (Glassmorphism) 🌟
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
            // تصميم زجاجي عصري
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#E60A0A0C"), Color.parseColor("#CC1A1A1D"))
                cornerRadii = floatArrayOf(80f, 80f, 80f, 80f, 0f, 0f, 0f, 0f)
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
            setPadding(0, 30, 0, 0)
        }

        // خط السحب (Handle) بالأعلى
        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 12).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 40
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#55FFFFFF"))
                cornerRadius = 10f
            }
        }
        layout.addView(handle)

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
            
            // إضافة لمسة زجاجية لكل مستخدم بالقائمة
            holder.itemView.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFFFFF")) 
                cornerRadius = 40f
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                setMargins(20, 10, 20, 10)
            }

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
