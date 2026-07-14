package com.v2ray.ang.ui

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator 
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

class ProfileFragment : Fragment() {

    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var ivAvatar: ImageView
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvUsernameStatus: TextView 
    private lateinit var etPass: EditText
    private lateinit var btnSave: Button
    
    // 🌟 عناصر التواصل الاجتماعي الجديدة 🌟
    private var tvFollowersCount: TextView? = null
    private var tvFollowingCount: TextView? = null
    private var btnAddStory: ImageView? = null
    private var layoutAvatarContainer: FrameLayout? = null
    
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var currentBase64Pfp: String = ""
    private var myDeviceId: String = ""
    private var activeDevicesList = JSONArray()
    private var checkUserJob: Job? = null 

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
                val maxImageSize = 400f
                val ratio = min(1f, min(maxImageSize / bitmap.width, maxImageSize / bitmap.height))
                val width = (ratio * bitmap.width).roundToInt()
                val height = (ratio * bitmap.height).roundToInt()
                
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                
                currentBase64Pfp = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), AuthManager.getId(requireContext()), false)
            } catch (e: Exception) {}
        }
    }

    private fun getUniqueHardwareId(): String {
        try {
            val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.DISPLAY +
                    Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT +
                    Build.USER + Build.ID + Build.BOOTLOADER
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(devInfo.toByteArray())
            val hexString = StringBuilder()
            for (byte in hash) {
                val hex = Integer.toHexString(0xFF and byte.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            return hexString.toString().take(15).uppercase()
        } catch (e: Exception) {
            return "UNKNOWN_HW_ID"
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    private fun showCustomSnackbar(message: String, colorHex: String, type: String = "info") {
        view?.let { root ->
            val snackbar = Snackbar.make(root, "", Snackbar.LENGTH_SHORT)
            val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
            snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
            snackbarLayout.setPadding(0, 0, 0, 0)

            val customLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(40, 30, 40, 30)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
                    cornerRadius = 60f 
                }
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(50, 0, 50, 50) 
                }
            }

            val iconText = TextView(requireContext()).apply {
                text = when (type) {
                    "success" -> "✔"
                    "error" -> "✖"
                    "copy" -> "📄"
                    else -> "ℹ"
                }
                setTextColor(Color.parseColor(colorHex))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    shape = GradientDrawable.OVAL
                }
                layoutParams = LinearLayout.LayoutParams(50, 50).apply { setMargins(0, 0, 20, 0) }
            }
            customLayout.addView(iconText)

            val textView = TextView(requireContext()).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            customLayout.addView(textView)

            snackbarLayout.addView(customLayout, 0)
            snackbar.show()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Data", text)
        clipboard.setPrimaryClip(clip)
        showCustomSnackbar("تم نسخ $label", "#FF9800", "copy") 
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        myDeviceId = getUniqueHardwareId()
        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())

        val rootLayout = view as? ViewGroup
        var scrollView: ScrollView? = null
        rootLayout?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child is ScrollView) {
                    scrollView = child
                    break
                }
            }
        }

        scrollView?.let { sv ->
            val parent = sv.parent as ViewGroup
            val index = parent.indexOfChild(sv)
            parent.removeView(sv)

            swipeRefreshLayout = SwipeRefreshLayout(requireContext()).apply {
                layoutParams = sv.layoutParams
                setProgressBackgroundColorSchemeColor(Color.parseColor("#1A1A1D"))
                setColorSchemeColors(Color.parseColor("#FF9800"), Color.parseColor("#4CAF50"), Color.parseColor("#2196F3"))
                addView(sv)
                setOnRefreshListener {
                    fetchUserDataFromServer(userId, isSwipeRefresh = true)
                }
            }
            parent.addView(swipeRefreshLayout, index)
        }

        ivAvatar = view.findViewById(R.id.iv_profile_pic)
        etId = view.findViewById(R.id.et_profile_id)
        etName = view.findViewById(R.id.et_profile_name)
        etUsername = view.findViewById(R.id.et_profile_username)
        tvUsernameStatus = view.findViewById(R.id.tv_username_status)
        etPass = view.findViewById(R.id.et_profile_pass)
        val etDevice = view.findViewById<EditText>(R.id.et_profile_device)
        btnSave = view.findViewById(R.id.btn_save_profile)
        
        // 🌟 استدعاء العناصر الاجتماعية (سنقوم بإنشائها في ملف الـ XML لاحقاً) 🌟
        tvFollowersCount = view.findViewById(R.id.tv_followers_count)
        tvFollowingCount = view.findViewById(R.id.tv_following_count)
        btnAddStory = view.findViewById(R.id.btn_add_story)
        layoutAvatarContainer = view.findViewById(R.id.layout_avatar_container)

        if (userRole == "admin") {
            etId.isEnabled = true
            etId.setTextColor(Color.parseColor("#FF9800"))
        } else {
            etId.isEnabled = false
        }
        
        view.findViewById<ImageView>(R.id.btn_change_avatar).setOnClickListener {
            pickImage.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }
        
        // 🌟 زر إضافة قصة 🌟
        btnAddStory?.setOnClickListener {
            // سيتم تفعيل هذا بعد إنشاء StoryUploadActivity
            // startActivity(Intent(requireContext(), StoryUploadActivity::class.java))
            Toast.makeText(requireContext(), "سيتم تفعيل رفع القصة قريباً", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btn_copy_id).setOnClickListener { copyToClipboard("آيدي الحساب", etId.text.toString()) }
        view.findViewById<Button>(R.id.btn_copy_device).setOnClickListener { copyToClipboard("آيدي الجهاز", myDeviceId) }
        view.findViewById<Button>(R.id.btn_manage_devices).setOnClickListener { showDevicesDialog() }
        
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        etUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                checkUsernameLive(s.toString().trim().replace("@", ""))
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        etId.setText(userId)
        etDevice.setText(myDeviceId)
        etName.setText(AuthManager.getName(requireContext()))
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        fetchUserDataFromServer(userId)

        if (userRole == "admin") {
            view.findViewById<ImageView>(R.id.btn_admin_dashboard)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(requireContext(), AdminDashboardActivity::class.java)) }
            }
            view.findViewById<ImageView>(R.id.btn_update_logs)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(requireContext(), UpdateLogsActivity::class.java)) }
            }
        }

        updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), userId, false)

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newUsername = etUsername.text.toString().trim().replace("@", "")
            val newPass = etPass.text.toString().trim()
            val newId = etId.text.toString().trim() 
            
            if (newName.isEmpty() || newPass.isEmpty() || newId.isEmpty()) {
                showCustomSnackbar("يرجى ملء كافة الحقول الأساسية", "#F44336", "error")
                return@setOnClickListener
            }
            
            if (newUsername.isNotEmpty()) {
                if (newUsername.length !in 2..40) {
                    showCustomSnackbar("المعرف يجب أن يكون بين 2 و 40 حرفاً/رقماً", "#F44336", "error")
                    return@setOnClickListener
                }
                if (!newUsername.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
                    showCustomSnackbar("مسموح بالحروف الإنجليزية والأرقام فقط", "#F44336", "error")
                    return@setOnClickListener
                }
            }
            
            saveProfile(newName, newUsername, newPass, userRole, newId)
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("تسجيل خروج").setMessage("هل أنت متأكد من الخروج التام؟")
                .setPositiveButton("نعم") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL("$BASE_API_URL/auth/terminate_device").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                            conn.outputStream.use { it.write(JSONObject().put("id", userId).put("targetDeviceId", myDeviceId).toString().toByteArray(Charsets.UTF_8)) }
                            conn.responseCode
                        } catch (e: Exception) {}
                    }
                    performLogout()
                }.setNegativeButton("إلغاء", null).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        checkUserJob?.cancel()
    }

    private fun performLogout(kickedMessage: String? = null) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (kickedMessage != null) {
                Toast.makeText(requireContext(), kickedMessage, Toast.LENGTH_LONG).show()
            }
            AuthManager.logout(requireContext())
            val intent = Intent(requireActivity(), LoginActivity::class.java).apply { 
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
            }
            startActivity(intent)
            requireActivity().finish()
        }
    }

    private fun saveProfile(name: String, username: String, pass: String, role: String, newId: String) {
        btnSave.isEnabled = false
        btnSave.text = "جاري الحفظ..."
        val oldId = AuthManager.getId(requireContext())
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/update").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("id", oldId)
                    put("currentPassword", AuthManager.getPass(requireContext()))
                    put("newName", name)
                    put("password", pass)
                    put("newPfp", currentBase64Pfp)
                    put("username", username)
                    put("newId", newId) 
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                
                val stream = if (conn.responseCode == 200) conn.inputStream else conn.errorStream
                val responseText = BufferedReader(InputStreamReader(stream)).readText()
                val obj = JSONObject(responseText)
                
                if (obj.optBoolean("success", false)) {
                    val savedId = obj.optString("newId", oldId)
                    AuthManager.saveUser(requireContext(), savedId, name, pass, role, currentBase64Pfp)
                    withContext(Dispatchers.Main) {
                        etId.setText(savedId)
                        showCustomSnackbar("تم حفظ التعديلات بنجاح!", "#4CAF50", "success") 
                        updateProfilePicture(currentBase64Pfp, name, savedId, false)
                        btnSave.isEnabled = true
                        btnSave.text = "حفظ التعديلات السحابية"
                    }
                } else {
                    val errorMsg = obj.optString("message", "فشل الحفظ")
                    withContext(Dispatchers.Main) { 
                        showCustomSnackbar(errorMsg, "#F44336", "error")
                        btnSave.isEnabled = true
                        btnSave.text = "حفظ التعديلات السحابية" 
                    }
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { 
                    showCustomSnackbar("خطأ في الاتصال بالإنترنت", "#F44336", "error")
                    btnSave.isEnabled = true
                    btnSave.text = "حفظ التعديلات السحابية" 
                } 
            }
        }
    }

    private fun checkUsernameLive(username: String) {
        checkUserJob?.cancel()
        if (!::tvUsernameStatus.isInitialized) return
        if (username.isEmpty()) { tvUsernameStatus.visibility = View.GONE; return }
        
        if (username.length !in 2..40) {
            tvUsernameStatus.visibility = View.VISIBLE
            tvUsernameStatus.text = "❌ المعرف يجب أن يكون بين 2 و 40 حرفاً/رقماً"
            tvUsernameStatus.setTextColor(Color.RED)
            return
        }
        if (!username.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            tvUsernameStatus.visibility = View.VISIBLE
            tvUsernameStatus.text = "❌ مسموح بالحروف الإنجليزية والأرقام فقط"
            tvUsernameStatus.setTextColor(Color.RED)
            return
        }

        tvUsernameStatus.visibility = View.VISIBLE
        tvUsernameStatus.text = "⏳ فحص المعرف..."
        tvUsernameStatus.setTextColor(Color.parseColor("#FF9800"))

        checkUserJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            try {
                val conn = URL("$BASE_API_URL/auth/check_username?username=$username&id=${AuthManager.getId(requireContext())}").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val available = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getBoolean("available")
                    withContext(Dispatchers.Main) {
                        tvUsernameStatus.text = if (available) "✅ المعرف متاح" else "❌ المعرف محجوز"
                        tvUsernameStatus.setTextColor(if (available) Color.GREEN else Color.RED)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun showDevicesDialog() {
        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(40,40,40,40); setBackgroundColor(Color.parseColor("#141417")) }
        scroll.addView(layout)

        val tvTitle = TextView(requireContext()).apply { text = "📱 الأجهزة النشطة لحسابك"; setTextColor(Color.parseColor("#9C27B0")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,0,0,30) }
        layout.addView(tvTitle)

        val dialog = AlertDialog.Builder(requireContext()).setView(scroll).setPositiveButton("إغلاق", null).show()

        fun renderDevices() {
            val childCount = layout.childCount
            if (childCount > 1) layout.removeViews(1, childCount - 1)

            if (activeDevicesList.length() == 0) {
                layout.addView(TextView(requireContext()).apply { text = "لا توجد أجهزة مسجلة"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                return
            }

            for (i in 0 until activeDevicesList.length()) {
                val devId = activeDevicesList.getString(i)
                val isCurrent = (devId == myDeviceId)

                val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,15) } }
                val info = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                
                info.addView(TextView(requireContext()).apply { text = if (isCurrent) "💻 جهازك الحالي" else "📱 جهاز مرتبط"; setTextColor(if(isCurrent) Color.parseColor("#4CAF50") else Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
                info.addView(TextView(requireContext()).apply { text = "ID: $devId"; setTextColor(Color.GRAY); textSize = 12f })
                row.addView(info)

                if (!isCurrent) {
                    val btnTerminate = MaterialButton(requireContext()).apply {
                        text = "طرد ❌"
                        setBackgroundColor(Color.RED)
                        textSize = 10f
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(10, 0, 0, 0) }
                        setOnClickListener {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val conn = URL("$BASE_API_URL/auth/terminate_device").openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"
                                    conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                                    conn.outputStream.use { it.write(JSONObject().put("id", AuthManager.getId(requireContext())).put("targetDeviceId", devId).toString().toByteArray()) }
                                    if (conn.responseCode == 200) {
                                        val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                                        if (obj.getBoolean("success")) {
                                            activeDevicesList = obj.getJSONArray("devices")
                                            withContext(Dispatchers.Main) { renderDevices(); showCustomSnackbar("تم طرد الجهاز بنجاح!", "#4CAF50", "success") }
                                        }
                                    }
                                } catch(e: Exception){} 
                            }
                        }
                    }
                    row.addView(btnTerminate)
                }
                layout.addView(row)
            }
        }
        renderDevices()
    }

    // 🌟 إضافة الدائرة الزرقاء (Blue Ring) للقصة النشطة 🌟
    private fun updateProfilePicture(base64Str: String, name: String, userId: String, hasActiveStory: Boolean) {
        val bitmap = try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null } ?: AvatarGenerator.generateAvatar(name, userId)
        
        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            ivAvatar.setImageDrawable(circularDrawable)
            
            layoutAvatarContainer?.let {
                if (hasActiveStory) {
                    it.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(8, Color.parseColor("#2196F3")) // لون القصة النشطة الأزرق
                        setColor(Color.TRANSPARENT)
                    }
                    it.setPadding(8, 8, 8, 8)
                    it.setOnClickListener {
                        // سيتم تفعيله لاحقاً عند إنشاء StoryViewerActivity
                        Toast.makeText(requireContext(), "فتح القصة الخاصة بك...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    it.background = null
                    it.setPadding(0, 0, 0, 0)
                    it.setOnClickListener(null)
                }
            }
        }
    }

    private fun fetchUserDataFromServer(userId: String, isSwipeRefresh: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        val serverDevices = obj.optJSONArray("devices") ?: JSONArray()
                        
                        var isDeviceAuthorized = false
                        if (AuthManager.getRole(requireContext()) == "admin") {
                            isDeviceAuthorized = true
                        } else {
                            for (i in 0 until serverDevices.length()) {
                                if (serverDevices.getString(i) == myDeviceId) {
                                    isDeviceAuthorized = true
                                    break
                                }
                            }
                        }
                        
                        if (!isDeviceAuthorized) {
                            performLogout("تم إنهاء جلستك من جهاز آخر أو من الإدارة! 🚫")
                            return@launch
                        }

                        val followers = obj.optInt("followersCount", 0)
                        val following = obj.optInt("followingCount", 0)
                        val hasActiveStory = obj.optBoolean("hasActiveStory", false)

                        withContext(Dispatchers.Main) {
                            etName.setText(obj.getString("name"))
                            etPass.setText(obj.getString("password"))
                            etUsername.setText(obj.optString("username", ""))
                            
                            // 🌟 عرض المتابعين 🌟
                            tvFollowersCount?.text = "المتابعون\n$followers"
                            tvFollowingCount?.text = "أتابع\n$following"
                            
                            activeDevicesList = serverDevices
                            currentBase64Pfp = obj.optString("pfp", currentBase64Pfp)
                            updateProfilePicture(currentBase64Pfp, obj.getString("name"), userId, hasActiveStory)
                            if (isSwipeRefresh) showCustomSnackbar("تم تحديث البيانات بنجاح ✔", "#4CAF50", "success")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isSwipeRefresh) withContext(Dispatchers.Main) { showCustomSnackbar("فشل التحديث، تأكد من الإنترنت!", "#F44336", "error") }
            } finally {
                if (isSwipeRefresh) {
                    withContext(Dispatchers.Main) { swipeRefreshLayout?.isRefreshing = false }
                }
            }
        }
    }
}
