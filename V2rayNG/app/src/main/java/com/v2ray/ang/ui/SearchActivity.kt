package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private lateinit var myUserId: String

    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvSearchStatus: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var btnBack: ImageView

    private lateinit var adapter: SearchAdapter
    private var usersList = mutableListOf<SearchUserItem>()
    
    // متغير لتأخير البحث وتخفيف الضغط على السيرفر (Debounce)
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        myUserId = AuthManager.getId(this)

        initViews()
        setupRecyclerView()
        setupSearchListener()
    }

    private fun initViews() {
        etSearchQuery = findViewById(R.id.et_search_query)
        rvSearchResults = findViewById(R.id.rv_search_results)
        tvSearchStatus = findViewById(R.id.tv_search_status)
        pbLoading = findViewById(R.id.pb_search_loading)
        btnBack = findViewById(R.id.btn_back_search)

        btnBack.setOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        adapter = SearchAdapter(usersList)
        rvSearchResults.adapter = adapter
    }

    private fun setupSearchListener() {
        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                searchJob?.cancel() // إلغاء البحث السابق إذا كتب المستخدم حرفاً جديداً بسرعة

                if (query.isEmpty()) {
                    usersList.clear()
                    adapter.notifyDataSetChanged()
                    tvSearchStatus.text = "اكتب شيئاً للبحث 🔍"
                    tvSearchStatus.visibility = View.VISIBLE
                    pbLoading.visibility = View.GONE
                    return
                }

                // بدء مؤقت نصف ثانية قبل إرسال الطلب (Debounce)
                searchJob = lifecycleScope.launch(Dispatchers.Main) {
                    delay(500)
                    performSearch(query)
                }
            }
        })
    }

    private fun performSearch(query: String) {
        pbLoading.visibility = View.VISIBLE
        tvSearchStatus.visibility = View.GONE
        usersList.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val conn = URL("$BASE_API_URL/social/search?query=$encodedQuery&myId=$myUserId").openConnection() as HttpURLConnection
                
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    
                    if (obj.getBoolean("success")) {
                        val usersArray = obj.getJSONArray("users")
                        val newList = mutableListOf<SearchUserItem>()
                        
                        for (i in 0 until usersArray.length()) {
                            val u = usersArray.getJSONObject(i)
                            // لا نعرض حسابي الشخصي في نتائج البحث الخاصة بي
                            if (u.getString("id") != myUserId) {
                                newList.add(
                                    SearchUserItem(
                                        id = u.getString("id"),
                                        name = u.getString("name"),
                                        username = u.optString("username", ""),
                                        pfp = u.optString("pfp", ""),
                                        hasActiveStory = u.getBoolean("hasActiveStory"),
                                        isFollowing = u.getBoolean("isFollowing")
                                    )
                                )
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            usersList.addAll(newList)
                            adapter.notifyDataSetChanged()
                            pbLoading.visibility = View.GONE
                            
                            if (usersList.isEmpty()) {
                                tvSearchStatus.text = "لا توجد نتائج مطابقة 💔"
                                tvSearchStatus.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    tvSearchStatus.text = "خطأ في الاتصال بالإنترنت ⚠️"
                    tvSearchStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- Data Class ---
    data class SearchUserItem(
        val id: String, val name: String, val username: String, val pfp: String,
        val hasActiveStory: Boolean, var isFollowing: Boolean
    )

    // --- Adapter ---
    inner class SearchAdapter(private val items: List<SearchUserItem>) : RecyclerView.Adapter<SearchAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutAvatarContainer: FrameLayout = view.findViewById(R.id.layout_item_avatar_container)
            val ivPfp: ImageView = view.findViewById(R.id.iv_item_pfp)
            val tvName: TextView = view.findViewById(R.id.tv_item_name)
            val tvUsername: TextView = view.findViewById(R.id.tv_item_username)
            val btnFollow: MaterialButton = view.findViewById(R.id.btn_item_follow)

            init {
                // فتح الملف الشخصي
                view.setOnClickListener {
                    val user = items[adapterPosition]
                    val intent = Intent(this@SearchActivity, UserProfileActivity::class.java)
                    intent.putExtra("targetUserId", user.id)
                    startActivity(intent)
                }

                // فتح القصة
                layoutAvatarContainer.setOnClickListener {
                    val user = items[adapterPosition]
                    if (user.hasActiveStory) {
                        val intent = Intent(this@SearchActivity, StoryViewerActivity::class.java)
                        intent.putExtra("targetUserId", user.id)
                        startActivity(intent)
                    } else {
                        view.performClick() // فتح البروفايل إذا لم تكن هناك قصة
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

            val bitmap = getSafeBitmap(user.pfp) ?: AvatarGenerator.generateAvatar(user.name, user.id)
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            holder.ivPfp.setImageDrawable(circularDrawable)

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

            updateFollowButton(holder.btnFollow, user.isFollowing)

            holder.btnFollow.setOnClickListener {
                holder.btnFollow.isEnabled = false
                toggleFollow(user, holder.btnFollow)
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

        private fun toggleFollow(user: SearchUserItem, button: MaterialButton) {
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
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SearchActivity, "فشل في تحديث المتابعة", Toast.LENGTH_SHORT).show()
                        button.isEnabled = true
                    }
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
