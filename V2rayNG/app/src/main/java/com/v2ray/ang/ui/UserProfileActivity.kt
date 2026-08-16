package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UserProfileActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private lateinit var myUserId: String

    private lateinit var layoutAvatarContainer: FrameLayout
    private lateinit var ivUserPfp: ImageView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserUsername: TextView
    private lateinit var tvFollowersCount: TextView
    private lateinit var tvFollowingCount: TextView
    private lateinit var btnFollow: MaterialButton

    private var hasActiveStory = false
    private var isFollowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        targetUserId = intent.getStringExtra("targetUserId") ?: ""
        myUserId = AuthManager.getId(this)

        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "خطأ في جلب بيانات المستخدم", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        layoutAvatarContainer = findViewById(R.id.layout_avatar_container)
        ivUserPfp = findViewById(R.id.iv_user_pfp)
        tvUserName = findViewById(R.id.tv_user_name)
        tvUserUsername = findViewById(R.id.tv_user_username)
        tvFollowersCount = findViewById(R.id.tv_followers_count)
        tvFollowingCount = findViewById(R.id.tv_following_count)
        btnFollow = findViewById(R.id.btn_follow_user)

        // إعداد زر المتابعة مع الأنيميشن
        if (targetUserId == myUserId) {
            btnFollow.visibility = View.GONE
        } else {
            btnFollow.setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // تأثير ضغطة احترافي
                view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    toggleFollow()
                }.start()
            }
            checkFollowStatus()
        }
        
        tvFollowersCount.setOnClickListener {
            Toast.makeText(this, "قائمة المتابعين (قريباً)...", Toast.LENGTH_SHORT).show()
            /*
            val intent = Intent(this, ConnectionsActivity::class.java)
            intent.putExtra("targetUserId", targetUserId)
            intent.putExtra("type", "followers")
            startActivity(intent)
            */
        }

        tvFollowingCount.setOnClickListener {
            Toast.makeText(this, "قائمة المتابَعين (قريباً)...", Toast.LENGTH_SHORT).show()
            /*
            val intent = Intent(this, ConnectionsActivity::class.java)
            intent.putExtra("targetUserId", targetUserId)
            intent.putExtra("type", "following")
            startActivity(intent)
            */
        }

        fetchUserData()
    }

    // لضمان تحديث الصفحة إذا رجع المستخدم من شاشة النشر أو القصص
    override fun onResume() {
        super.onResume()
        fetchUserData()
    }

    private fun fetchUserData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$targetUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val name = obj.getString("name")
                        val username = obj.optString("username", "")
                        val pfpBase64 = obj.optString("pfp", "")
                        val followers = obj.optInt("followersCount", 0)
                        val following = obj.optInt("followingCount", 0)
                        hasActiveStory = obj.optBoolean("hasActiveStory", false)
                        if (hasActiveStory) {
                            // 🌟 نبدأ تحميل الاستوري بالخلفية من صفحة البروفايل نفسها 🌟
                            StoryViewerActivity.preloadUserStories(this@UserProfileActivity, targetUserId, AuthManager.getId(this@UserProfileActivity))
                        }

                        withContext(Dispatchers.Main) {
                            tvUserName.text = name
                            tvUserUsername.text = if (username.isNotEmpty()) "@$username" else ""
                            tvFollowersCount.text = followers.toString()
                            tvFollowingCount.text = following.toString()
                            updateProfilePicture(pfpBase64, name, targetUserId, hasActiveStory)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UserProfileActivity, "المستخدم غير موجود", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserProfileActivity, "خطأ في الاتصال بالإنترنت", Toast.LENGTH_SHORT).show()
                }
            }
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
                        isFollowing = obj.getBoolean("isFollowing")
                        withContext(Dispatchers.Main) {
                            updateFollowButtonUI()
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun toggleFollow() {
        btnFollow.isEnabled = false
        btnFollow.text = "جاري..."
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
                        isFollowing = obj.getBoolean("isFollowing")
                        val newFollowersCount = obj.getInt("followersCount")
                        withContext(Dispatchers.Main) {
                            tvFollowersCount.text = newFollowersCount.toString()
                            updateFollowButtonUI()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserProfileActivity, "فشل في تحديث المتابعة", Toast.LENGTH_SHORT).show()
                    btnFollow.isEnabled = true
                }
            }
        }
    }

    private fun updateFollowButtonUI() {
        btnFollow.isEnabled = true
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

    private fun updateProfilePicture(base64Str: String, name: String, userId: String, hasStory: Boolean) {
        val bitmap = getSafeBitmap(base64Str) ?: AvatarGenerator.generateAvatar(name, userId)

        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            ivUserPfp.setImageDrawable(circularDrawable)

            // إعداد إطار القصة وإجراءات الضغط على الصورة
            if (hasStory) {
                layoutAvatarContainer.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(8, Color.parseColor("#2196F3")) // اللون الأزرق للقصة النشطة
                    setColor(Color.TRANSPARENT)
                }
                layoutAvatarContainer.setPadding(8, 8, 8, 8)
                
                layoutAvatarContainer.setOnClickListener {
                    try {
                        val intent = Intent(this, Class.forName("com.v2ray.ang.ui.StoryViewerActivity"))
                        intent.putExtra("targetUserId", targetUserId)
                        startActivity(intent)
                    } catch (e: Exception) {}
                }
            } else {
                layoutAvatarContainer.background = null
                layoutAvatarContainer.setPadding(0, 0, 0, 0)
                
                // إذا كان حسابي وليس لدي قصة، بضغطي على صورتي تفتح شاشة نشر قصة!
                if (targetUserId == myUserId) {
                    layoutAvatarContainer.setOnClickListener {
                        try {
                            val intent = Intent(this, Class.forName("com.v2ray.ang.ui.StoryUploadActivity"))
                            startActivity(intent)
                        } catch (e: Exception) {}
                    }
                } else {
                    layoutAvatarContainer.setOnClickListener(null)
                }
            }

            // 🌟 تصميم زر إضافة القصة (+) الاحترافي مثل فيسبوك 🌟
            if (targetUserId == myUserId) {
                var btnAddStory = layoutAvatarContainer.findViewWithTag<ImageView>("btn_add_story_overlay")
                if (btnAddStory == null) {
                    btnAddStory = ImageView(this).apply {
                        tag = "btn_add_story_overlay"
                        setImageResource(android.R.drawable.ic_input_add) 
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#2196F3")) // لون الزر الأزرق
                            setStroke(6, Color.parseColor("#1A1A1D")) // إطار عازل بلون خلفية التطبيق
                        }
                        setColorFilter(Color.WHITE)
                        setPadding(10, 10, 10, 10)
                        
                        // تحديد الحجم والتموضع بأسفل الزاوية
                        layoutParams = FrameLayout.LayoutParams(85, 85).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            setMargins(0, 0, 5, 5)
                        }
                        
                        setOnClickListener {
                            try {
                                val intent = Intent(this@UserProfileActivity, Class.forName("com.v2ray.ang.ui.StoryUploadActivity"))
                                startActivity(intent)
                            } catch (e: Exception) {}
                        }
                    }
                    layoutAvatarContainer.addView(btnAddStory)
                }
            } else {
                // إزالة الزر لو فتحنا حساب شخص ثاني
                layoutAvatarContainer.findViewWithTag<ImageView>("btn_add_story_overlay")?.let {
                    layoutAvatarContainer.removeView(it)
                }
            }
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