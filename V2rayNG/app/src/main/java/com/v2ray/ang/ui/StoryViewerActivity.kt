package com.v2ray.ang.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
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

class StoryViewerActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private var myUserId: String = ""

    private var storiesArray = JSONArray()
    private var currentIndex = 0

    private lateinit var ivStoryImage: ImageView
    private lateinit var tvStoryText: TextView
    private lateinit var ivPfp: ImageView
    private lateinit var tvName: TextView
    private lateinit var btnFollow: MaterialButton
    private lateinit var tvCommentsCount: TextView
    private lateinit var progressLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_viewer)

        targetUserId = intent.getStringExtra("targetUserId") ?: ""
        myUserId = AuthManager.getId(this)

        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات القصة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ivStoryImage = findViewById(R.id.iv_story_image)
        tvStoryText = findViewById(R.id.tv_story_text)
        ivPfp = findViewById(R.id.iv_publisher_pfp)
        tvName = findViewById(R.id.tv_publisher_name)
        btnFollow = findViewById(R.id.btn_follow)
        tvCommentsCount = findViewById(R.id.tv_comments_count)
        progressLoading = findViewById(R.id.progress_loading)

        findViewById<ImageView>(R.id.btn_close_story).setOnClickListener { finish() }

        // أزرار التقليب
        findViewById<View>(R.id.view_previous).setOnClickListener { showPreviousStory() }
        findViewById<View>(R.id.view_next).setOnClickListener { showNextStory() }

        // التفاعلات
        findViewById<TextView>(R.id.btn_react_heart).setOnClickListener { reactToStory("❤️") }
        findViewById<TextView>(R.id.btn_react_fire).setOnClickListener { reactToStory("🔥") }
        findViewById<TextView>(R.id.btn_react_laugh).setOnClickListener { reactToStory("😂") }

        // 🌟 فتح نافذة التعليقات (Bottom Sheet) 🌟
        findViewById<LinearLayout>(R.id.btn_open_comments).setOnClickListener {
            if (storiesArray.length() > 0) {
                val currentStoryId = storiesArray.getJSONObject(currentIndex).getString("id")
                val bottomSheet = CommentsBottomSheet.newInstance(currentStoryId, myUserId)
                bottomSheet.show(supportFragmentManager, "CommentsBottomSheet")
            }
        }

        btnFollow.setOnClickListener { toggleFollow() }

        // إخفاء زر المتابعة إذا كانت القصة خاصة بي
        if (targetUserId == myUserId) {
            btnFollow.visibility = View.GONE
        } else {
            checkFollowStatus()
        }

        fetchPublisherInfo()
        fetchStories()
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

    private fun displayStory(index: Int) {
        if (index < 0 || index >= storiesArray.length()) return
        currentIndex = index
        val story = storiesArray.getJSONObject(index)

        val imageBase64 = story.optString("image", "")
        val text = story.optString("text", "")
        tvCommentsCount.text = story.optInt("commentsCount", 0).toString()

        if (imageBase64.isNotEmpty()) {
            val bitmap = getSafeBitmap(imageBase64)
            if (bitmap != null) {
                ivStoryImage.setImageBitmap(bitmap)
                ivStoryImage.visibility = View.VISIBLE
            }
        } else {
            ivStoryImage.visibility = View.GONE
        }

        if (text.isNotEmpty()) {
            tvStoryText.text = text
            tvStoryText.visibility = View.VISIBLE
        } else {
            tvStoryText.visibility = View.GONE
        }
    }

    private fun showNextStory() {
        if (currentIndex < storiesArray.length() - 1) {
            displayStory(currentIndex + 1)
        } else {
            finish() // إغلاق إذا انتهت القصص
        }
    }

    private fun showPreviousStory() {
        if (currentIndex > 0) {
            displayStory(currentIndex - 1)
        }
    }

    private fun checkFollowStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/check_follow?followerId=$myUserId&targetId=$targetUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val isFollowing = obj.getBoolean("isFollowing")
                        withContext(Dispatchers.Main) {
                            btnFollow.visibility = View.VISIBLE
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
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
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
                conn.responseCode // إرسال بدون الحاجة لانتظار رد لعدم تأخير واجهة المستخدم
            } catch (e: Exception) {}
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
}
