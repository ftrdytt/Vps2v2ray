package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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

class ConnectionsActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private lateinit var type: String // "followers" or "following"
    private lateinit var myUserId: String

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConnectionsAdapter
    private lateinit var tvEmptyState: TextView

    private val usersList = mutableListOf<ConnectionUser>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        targetUserId = intent.getStringExtra("targetUserId") ?: return finish()
        type = intent.getStringExtra("type") ?: "followers"
        myUserId = AuthManager.getId(this)

        setupPremiumUI()
        fetchConnections()
    }

    private fun setupPremiumUI() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#08080A")) // خلفية سوداء ليلية فخمة
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // الشريط العلوي المبرمج
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#08080A"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(50, 50, 50, 40)
            elevation = 10f
        }
        val btnBack = ImageView(this).apply {
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
            setPadding(0, 0, 40, 0)
        }
        val tvTitle = TextView(this).apply {
            text = if (type == "followers") "المتابعون" else "أُتابع"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)

        // القائمة (RecyclerView) والشريط المحدث
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#0088FF"))
            setOnRefreshListener { fetchConnections() }
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ConnectionsActivity)
            setPadding(0, 20, 0, 20)
            clipToPadding = false
        }
        swipeRefresh.addView(recyclerView)

        // حالة القائمة الفارغة
        tvEmptyState = TextView(this).apply {
            text = "لا يوجد مستخدمين لعرضهم."
            setTextColor(Color.parseColor("#666666"))
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // ترتيب العناصر برمجياً
        val lpTopBar = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        val lpSwipe = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { addRule(RelativeLayout.BELOW, topBar.id) }
        val lpEmptyState = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }

        rootLayout.addView(topBar, lpTopBar)
        rootLayout.addView(swipeRefresh, lpSwipe)
        rootLayout.addView(tvEmptyState, lpEmptyState)

        setContentView(rootLayout)

        adapter = ConnectionsAdapter(usersList, myUserId, 
            onUserClick = { user ->
                val intent = Intent(this, UserProfileActivity::class.java)
                intent.putExtra("targetUserId", user.id)
                startActivity(intent)
            },
            onStoryClick = { user ->
                val intent = Intent(this, StoryViewerActivity::class.java)
                intent.putExtra("targetUserId", user.id)
                startActivity(intent)
            },
            onFollowClick = { user, position -> toggleFollow(user, position) }
        )
        recyclerView.adapter = adapter
    }

    private fun fetchConnections() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "$BASE_API_URL/social/get_connections?targetId=$targetUserId&type=$type&myId=$myUserId"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val usersArray = obj.getJSONArray("users")
                        val newList = mutableListOf<ConnectionUser>()
                        for (i in 0 until usersArray.length()) {
                            val uObj = usersArray.getJSONObject(i)
                            newList.add(ConnectionUser(
                                id = uObj.getString("id"),
                                name = uObj.getString("name"),
                                username = uObj.optString("username", ""),
                                pfp = uObj.optString("pfp", ""),
                                isFollowing = uObj.optBoolean("isFollowing", false),
                                isVerified = uObj.optBoolean("isVerified", false),
                                hasActiveStory = uObj.optBoolean("hasActiveStory", false)
                            ))
                        }
                        withContext(Dispatchers.Main) {
                            usersList.clear()
                            usersList.addAll(newList)
                            adapter.notifyDataSetChanged()
                            tvEmptyState.visibility = if (usersList.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { swipeRefresh.isRefreshing = false }
        }
    }

    private fun toggleFollow(user: ConnectionUser, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/follow").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("followerId", myUserId)
                    put("targetId", user.id)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val newFollowStatus = obj.getBoolean("isFollowing")
                        withContext(Dispatchers.Main) {
                            user.isFollowing = newFollowStatus
                            adapter.notifyItemChanged(position)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
}

// 🌟 هيكل بيانات المستخدم (مع التوثيق والقصص) 🌟
data class ConnectionUser(
    val id: String, val name: String, val username: String, val pfp: String,
    var isFollowing: Boolean, val isVerified: Boolean, val hasActiveStory: Boolean
)

// 🌟 المحول الاحترافي برمجياً بالكامل (بدون ملفات XML) 🌟
class ConnectionsAdapter(
    private val users: List<ConnectionUser>,
    private val myUserId: String,
    private val onUserClick: (ConnectionUser) -> Unit,
    private val onStoryClick: (ConnectionUser) -> Unit,
    private val onFollowClick: (ConnectionUser, Int) -> Unit
) : RecyclerView.Adapter<ConnectionsAdapter.UserViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val context = parent.context
        
        // الحاوية الأساسية للعنصر
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(40, 30, 40, 30)
            gravity = Gravity.CENTER_VERTICAL
        }

        // حاوية الصورة الشخصية والقصة
        val avatarContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { setMargins(0, 0, 40, 0) }
        }
        val ivAvatar = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        avatarContainer.addView(ivAvatar)

        // قسم الاسم ومعرف المستخدم
        val infoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val tvUsername = TextView(context).apply {
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, 5, 0, 0)
        }
        infoLayout.addView(tvName)
        infoLayout.addView(tvUsername)

        // زر المتابعة
        val btnFollow = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 20, 40, 20)
        }

        layout.addView(avatarContainer)
        layout.addView(infoLayout)
        layout.addView(btnFollow)

        return UserViewHolder(layout, avatarContainer, ivAvatar, tvName, tvUsername, btnFollow)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        
        // ضبط الاسم وعلامة التوثيق
        holder.tvName.text = if (user.isVerified) "${user.name} ☑️" else user.name
        
        // ضبط اليوزر نيم
        holder.tvUsername.text = if (user.username.isNotEmpty()) "@${user.username}" else "ID: ${user.id}"
        holder.tvUsername.visibility = if (user.username.isEmpty() && user.id == "1") View.GONE else View.VISIBLE

        // الصورة الشخصية
        val bitmap = getSafeBitmap(user.pfp) ?: AvatarGenerator.generateAvatar(user.name, user.id, 140)
        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(holder.itemView.context.resources, bitmap).apply { isCircular = true }
            holder.ivAvatar.setImageDrawable(circularDrawable)
        }

        // إطار القصة النشطة
        if (user.hasActiveStory) {
            holder.avatarContainer.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(6, Color.parseColor("#2196F3")) // حواف زرقاء للقصة
                setColor(Color.TRANSPARENT)
            }
            holder.avatarContainer.setPadding(6, 6, 6, 6)
            holder.avatarContainer.setOnClickListener { onStoryClick(user) }
        } else {
            holder.avatarContainer.background = null
            holder.avatarContainer.setPadding(0, 0, 0, 0)
            holder.avatarContainer.setOnClickListener { onUserClick(user) }
        }

        // أزرار المتابعة
        if (user.id == myUserId) {
            holder.btnFollow.visibility = View.GONE
        } else {
            holder.btnFollow.visibility = View.VISIBLE
            if (user.isFollowing) {
                holder.btnFollow.text = "متابَع"
                holder.btnFollow.setTextColor(Color.WHITE)
                holder.btnFollow.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FFFFFF"))
                    cornerRadius = 20f
                }
            } else {
                holder.btnFollow.text = "متابعة"
                holder.btnFollow.setTextColor(Color.WHITE)
                holder.btnFollow.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0088FF")) // أزرق مثل الانستكرام
                    cornerRadius = 20f
                }
            }
        }

        // النقر على الزر والنقر على العنصر
        holder.btnFollow.setOnClickListener { onFollowClick(user, position) }
        holder.itemView.setOnClickListener { onUserClick(user) }
    }

    private fun getSafeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrEmpty()) return null
        return try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    override fun getItemCount(): Int = users.size

    class UserViewHolder(
        view: View,
        val avatarContainer: FrameLayout,
        val ivAvatar: ImageView,
        val tvName: TextView,
        val tvUsername: TextView,
        val btnFollow: TextView
    ) : RecyclerView.ViewHolder(view)
}
