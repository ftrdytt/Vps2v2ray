package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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

class ConnectionsActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var targetUserId: String
    private lateinit var type: String // "followers" or "following"
    private lateinit var myUserId: String

    private lateinit var rvConnections: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvEmptyState: TextView
    private lateinit var adapter: ConnectionsAdapter

    private var usersList = mutableListOf<UserItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connections)

        targetUserId = intent.getStringExtra("targetUserId") ?: ""
        type = intent.getStringExtra("type") ?: "followers"
        myUserId = AuthManager.getId(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_connections)
        setSupportActionBar(toolbar)
        supportActionBar?.title = if (type == "followers") "المتابعون" else "أتابع"
        toolbar.setNavigationOnClickListener { onBackPressed() }

        rvConnections = findViewById(R.id.rv_connections)
        swipeRefresh = findViewById(R.id.swipe_refresh_connections)
        tvEmptyState = findViewById(R.id.tv_empty_state)

        rvConnections.layoutManager = LinearLayoutManager(this)
        adapter = ConnectionsAdapter(usersList)
        rvConnections.adapter = adapter

        swipeRefresh.setColorSchemeColors(Color.parseColor("#2196F3"))
        swipeRefresh.setOnRefreshListener { fetchConnections() }

        fetchConnections()
    }

    private fun fetchConnections() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/social/get_connections?targetId=$targetUserId&type=$type&myId=$myUserId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        val usersArray = obj.getJSONArray("users")
                        val newList = mutableListOf<UserItem>()
                        for (i in 0 until usersArray.length()) {
                            val u = usersArray.getJSONObject(i)
                            newList.add(
                                UserItem(
                                    id = u.getString("id"),
                                    name = u.getString("name"),
                                    username = u.optString("username", ""),
                                    pfp = u.optString("pfp", ""),
                                    hasActiveStory = u.getBoolean("hasActiveStory"),
                                    isFollowing = u.getBoolean("isFollowing")
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            usersList.clear()
                            usersList.addAll(newList)
                            adapter.notifyDataSetChanged()
                            tvEmptyState.visibility = if (usersList.isEmpty()) View.VISIBLE else View.GONE
                            swipeRefresh.isRefreshing = false
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this@ConnectionsActivity, "خطأ في الاتصال", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Data Class for List Item ---
    data class UserItem(
        val id: String, val name: String, val username: String, val pfp: String,
        val hasActiveStory: Boolean, var isFollowing: Boolean
    )

    // --- Adapter for RecyclerView ---
    inner class ConnectionsAdapter(private val items: List<UserItem>) : RecyclerView.Adapter<ConnectionsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutAvatarContainer: FrameLayout = view.findViewById(R.id.layout_item_avatar_container)
            val ivPfp: ImageView = view.findViewById(R.id.iv_item_pfp)
            val tvName: TextView = view.findViewById(R.id.tv_item_name)
            val tvUsername: TextView = view.findViewById(R.id.tv_item_username)
            val btnFollow: MaterialButton = view.findViewById(R.id.btn_item_follow)

            init {
                // النقر على الحساب يفتح الملف الشخصي
                view.setOnClickListener {
                    val user = items[adapterPosition]
                    val intent = Intent(this@ConnectionsActivity, UserProfileActivity::class.java)
                    intent.putExtra("targetUserId", user.id)
                    startActivity(intent)
                }

                // النقر على الصورة يفتح القصة إذا كانت موجودة
                layoutAvatarContainer.setOnClickListener {
                    val user = items[adapterPosition]
                    if (user.hasActiveStory) {
                        val intent = Intent(this@ConnectionsActivity, StoryViewerActivity::class.java)
                        intent.putExtra("targetUserId", user.id)
                        startActivity(intent)
                    } else {
                        view.performClick() // إذا لم تكن هناك قصة، افتح البروفايل
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_connection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = items[position]
            holder.tvName.text = user.name
            holder.tvUsername.text = if (user.username.isNotEmpty()) "@${user.username}" else ""

            // تحميل الصورة
            val bitmap = getSafeBitmap(user.pfp) ?: AvatarGenerator.generateAvatar(user.name, user.id)
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            holder.ivPfp.setImageDrawable(circularDrawable)

            // إعداد إطار القصة
            if (user.hasActiveStory) {
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

            // إعداد زر المتابعة
            if (user.id == myUserId) {
                holder.btnFollow.visibility = View.GONE
            } else {
                holder.btnFollow.visibility = View.VISIBLE
                updateFollowButton(holder.btnFollow, user.isFollowing)

                holder.btnFollow.setOnClickListener {
                    holder.btnFollow.isEnabled = false
                    toggleFollow(user, holder.btnFollow)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        private fun updateFollowButton(button: MaterialButton, isFollowing: Boolean) {
            button.isEnabled = true
            if (isFollowing) {
                button.text = "متابَع ✔"
                button.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                button.setTextColor(Color.WHITE)
            } else {
                button.text = "متابعة"
                button.setBackgroundColor(Color.parseColor("#2196F3"))
                button.setTextColor(Color.WHITE)
            }
        }

        private fun toggleFollow(user: UserItem, button: MaterialButton) {
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
                        val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                        if (obj.getBoolean("success")) {
                            user.isFollowing = obj.getBoolean("isFollowing")
                            withContext(Dispatchers.Main) { updateFollowButton(button, user.isFollowing) }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { button.isEnabled = true }
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
