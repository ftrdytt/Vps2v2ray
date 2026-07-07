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
import android.provider.Settings
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
import androidx.cardview.widget.CardView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import kotlin.math.min
import kotlin.math.roundToInt

class ProfileFragment : Fragment() {

    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var flAvatarContainer: FrameLayout
    private lateinit var tvLetterAvatar: TextView
    private lateinit var ivAvatar: ImageView
    
    private lateinit var btnAdminDashboard: ImageView
    private lateinit var btnUpdateLogs: ImageView 
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvUsernameStatus: TextView 
    private lateinit var etPass: EditText
    
    private var currentBase64Pfp: String = ""
    private var myDeviceId: String = ""
    private var activeDevicesList = JSONArray()
    private var checkUserJob: Job? = null 

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
                    // 🌟 تقليل الحجم والضغط لتسريع الرفع ومنع التعليق 🌟
                    val maxImageSize = 400f
                    // نمنع تكبير الصور الصغيرة (نأخذ النسبة الأصغر، بحد أقصى 1.0)
                    val ratio = min(1f, min(maxImageSize / bitmap.width, maxImageSize / bitmap.height))
                    val width = (ratio * bitmap.width).roundToInt()
                    val height = (ratio * bitmap.height).roundToInt()
                    
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos) // ضغط ذكي 75%
                    val b = baos.toByteArray()
                    currentBase64Pfp = Base64.encodeToString(b, Base64.NO_WRAP)
                    
                    updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), AuthManager.getId(requireContext()))
                } catch (e: Exception) {}
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    // 🌟 الدالة السحرية للحقن الآمن للعناصر 🌟
    private fun wrapAndAddViews(target: View, addAbove: Boolean, vararg newViews: View) {
        val parent = target.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(target)
        val params = target.layoutParams
        parent.removeView(target)

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = params
        }
        
        target.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        
        if (addAbove) {
            newViews.forEach { wrapper.addView(it) }
            wrapper.addView(target)
        } else {
            wrapper.addView(target)
            newViews.forEach { wrapper.addView(it) }
        }
        parent.addView(wrapper, index)
    }

    private fun createCopyButton(textStr: String, targetText: () -> String): MaterialButton {
        return MaterialButton(requireContext()).apply {
            text = textStr
            setBackgroundColor(Color.parseColor("#252529"))
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply { setMargins(0, 5, 0, 20) }
            setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Data", targetText())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "تم النسخ بنجاح! 📋", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        myDeviceId = Settings.Secure.getString(requireActivity().contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        btnAdminDashboard = view.findViewById(R.id.btn_admin_dashboard)
        btnUpdateLogs = view.findViewById(R.id.btn_update_logs) 
        etId = view.findViewById(R.id.et_profile_id)
        etName = view.findViewById(R.id.et_profile_name)
        etPass = view.findViewById(R.id.et_profile_pass)
        val btnSave = view.findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        
        // 🌟 1. توسيط الصورة بشكل آمن جداً 🌟
        val ivProfilePic = view.findViewById<ImageView>(R.id.iv_profile_pic)
        val avatarParent = ivProfilePic.parent as? ViewGroup
        if (avatarParent != null) {
            val idx = avatarParent.indexOfChild(ivProfilePic)
            val params = ivProfilePic.layoutParams
            avatarParent.removeView(ivProfilePic)

            val centerWrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = params
            }

            val avatarCard = CardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(250, 250).apply { setMargins(0, 20, 0, 20) }
                radius = 125f
                setCardBackgroundColor(Color.TRANSPARENT)
                cardElevation = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
                setOnClickListener { val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); pickImage.launch(intent) }
            }
            
            flAvatarContainer = FrameLayout(requireContext()).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            tvLetterAvatar = TextView(requireContext()).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 40f; setTypeface(null, android.graphics.Typeface.BOLD); val bg = GradientDrawable(); bg.shape = GradientDrawable.OVAL; bg.setColor(Color.parseColor("#E91E63")); background = bg }
            ivAvatar = ImageView(requireContext()).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }

            flAvatarContainer.addView(tvLetterAvatar)
            flAvatarContainer.addView(ivAvatar)
            avatarCard.addView(flAvatarContainer)
            centerWrapper.addView(avatarCard)
            avatarParent.addView(centerWrapper, idx)
        }

        // 🌟 2. إضافة زر النسخ للآيدي 🌟
        val btnCopyId = createCopyButton("نسخ الآيدي الخاص بك 📋") { etId.text.toString() }
        wrapAndAddViews(etId, false, btnCopyId)

        // 🌟 3. حقل المعرف والفحص المباشر 🌟
        tvUsernameStatus = TextView(requireContext()).apply { textSize = 12f; setPadding(20, 5, 20, 10); visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        etUsername = EditText(requireContext()).apply {
            hint = "المعرف (@) اختياري"
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 16f
            background = etName.background
            setPadding(etName.paddingLeft, etName.paddingTop, etName.paddingRight, etName.paddingBottom)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { checkUsernameLive(s.toString().trim().replace("@", "")) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val wrapperUsername = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setPadding(0, 20, 0, 0) }
        wrapperUsername.addView(etUsername)
        wrapperUsername.addView(tvUsernameStatus)
        wrapAndAddViews(etName, false, wrapperUsername)

        // 🌟 4. حقل آيدي الجهاز وزر النسخ 🌟
        val tvDeviceLabel = TextView(requireContext()).apply { text = "آيدي جهازك الحالي 📱:"; setTextColor(Color.GRAY); setPadding(10,30,10,5); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val etDeviceDisplay = EditText(requireContext()).apply {
            setText(myDeviceId)
            isEnabled = false
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 14f
            background = etId.background
            setPadding(etId.paddingLeft, etId.paddingTop, etId.paddingRight, etId.paddingBottom)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnCopyDevice = createCopyButton("نسخ آيدي الجهاز 📋") { etDeviceDisplay.text.toString() }
        val wrapperDevice = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setPadding(0, 30, 0, 0) }
        wrapperDevice.addView(tvDeviceLabel)
        wrapperDevice.addView(etDeviceDisplay)
        wrapperDevice.addView(btnCopyDevice)
        wrapAndAddViews(etPass, false, wrapperDevice)

        // 🌟 5. زر إدارة الأجهزة النشطة 🌟
        val btnManageDevices = MaterialButton(requireContext()).apply {
            text = "📱 إدارة الأجهزة النشطة ومراقبة الجلسات"
            setBackgroundColor(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { showDevicesDialog() }
        }
        val manageWrapper = LinearLayout(requireContext()).apply { setPadding(0, 30, 0, 30); addView(btnManageDevices) }
        wrapAndAddViews(btnSave, true, manageWrapper)

        // ==========================================

        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())
        val userName = AuthManager.getName(requireContext())
        
        etId.setText(userId)
        etName.setText(userName)
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        fetchUserDataFromServer(userId)

        if (userRole == "admin") {
            btnAdminDashboard.visibility = View.VISIBLE
            btnAdminDashboard.setOnClickListener { startActivity(Intent(requireContext(), AdminDashboardActivity::class.java)) }
            btnUpdateLogs.visibility = View.VISIBLE
            btnUpdateLogs.setOnClickListener { startActivity(Intent(requireContext(), UpdateLogsActivity::class.java)) }
        }

        updateProfilePicture(currentBase64Pfp, userName, userId)

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newUsername = etUsername.text.toString().trim().replace("@", "")
            val newPass = etPass.text.toString().trim()
            
            if (newName.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(requireContext(), "يرجى ملء الاسم وكلمة المرور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newUsername.isNotEmpty() && !newUsername.matches(Regex("^[a-zA-Z0-9_.]{2,}\$"))) {
                Toast.makeText(requireContext(), "المعرف غير صالح! مسموح فقط بالحروف، الأرقام، ( _ )، و ( . )", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnSave.text = "جاري حفظ بياناتك..."

            // 🌟 نظام حماية الأخطاء (Finally) لمنع التعليق 🌟
            lifecycleScope.launch(Dispatchers.IO) {
                var isSuccess = false
                var message = "فشل الاتصال بالسيرفر"
                
                try {
                    val url = URL("$BASE_API_URL/auth/update")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    
                    val payload = JSONObject().apply {
                        put("id", userId)
                        put("currentPassword", AuthManager.getPass(requireContext()))
                        put("newName", newName)
                        put("password", newPass)
                        put("newPfp", currentBase64Pfp)
                        put("username", newUsername)
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode == 200) {
                        val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                        if (obj.getBoolean("success")) {
                            isSuccess = true
                            message = "تم حفظ التعديلات بنجاح!"
                        } else {
                            message = obj.optString("message", "فشل الحفظ")
                        }
                    } else {
                        message = "خطأ في السيرفر: ${conn.responseCode}"
                    }
                } catch (e: Throwable) {
                    message = "تأكد من اتصالك بالإنترنت"
                } finally {
                    withContext(Dispatchers.Main) {
                        if (isSuccess) {
                            AuthManager.saveUser(requireContext(), userId, newName, newPass, userRole, currentBase64Pfp)
                            updateProfilePicture(currentBase64Pfp, newName, AuthManager.getId(requireContext()))
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        btnSave.isEnabled = true
                        btnSave.text = "حفظ التعديلات السحابية" // الزر يرجع لوضعه الطبيعي دائماً
                    }
                }
            }
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
                    AuthManager.logout(requireContext())
                    val intent = Intent(requireActivity(), LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
                    startActivity(intent); requireActivity().finish()
                }.setNegativeButton("إلغاء", null).show()
        }
    }

    private fun checkUsernameLive(username: String) {
        checkUserJob?.cancel()
        if (username.isEmpty()) { tvUsernameStatus.visibility = View.GONE; return }
        if (username.length < 2) {
            tvUsernameStatus.visibility = View.VISIBLE
            tvUsernameStatus.text = "❌ المعرف قصير جداً (حرفين فما فوق)"
            tvUsernameStatus.setTextColor(Color.RED)
            return
        }

        tvUsernameStatus.visibility = View.VISIBLE
        tvUsernameStatus.text = "⏳ جاري فحص توفر المعرف..."
        tvUsernameStatus.setTextColor(Color.parseColor("#FF9800"))

        checkUserJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            try {
                val conn = URL("$BASE_API_URL/auth/check_username?username=$username&id=${AuthManager.getId(requireContext())}").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val available = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getBoolean("available")
                    withContext(Dispatchers.Main) {
                        if (available) {
                            tvUsernameStatus.text = "✅ المعرف متاح للاستخدام من قبلك"
                            tvUsernameStatus.setTextColor(Color.parseColor("#4CAF50"))
                        } else {
                            tvUsernameStatus.text = "❌ المعرف محجوز ومستخدم مسبقاً"
                            tvUsernameStatus.setTextColor(Color.RED)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun showDevicesDialog() {
        val dialogView = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(requireContext()).apply { text = "📱 الأجهزة المرتبطة بحسابك"; setTextColor(Color.parseColor("#9C27B0")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,0,0,30) }
        val scrollContent = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(requireContext()).apply { addView(scrollContent) })

        AlertDialog.Builder(requireContext()).setView(dialogView).setPositiveButton("إغلاق", null).show()

        fun renderDevices() {
            scrollContent.removeAllViews()
            if (activeDevicesList.length() == 0) {
                scrollContent.addView(TextView(requireContext()).apply { text = "لا توجد أجهزة مسجلة حالياً"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                return
            }

            for (i in 0 until activeDevicesList.length()) {
                val devId = activeDevicesList.getString(i)
                val isCurrent = (devId == myDeviceId)

                val item = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,15) } }
                val info = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                
                info.addView(TextView(requireContext()).apply { text = if (isCurrent) "💻 جهازك الحالي" else "📱 جهاز مرتبط"; setTextColor(if(isCurrent) Color.parseColor("#4CAF50") else Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
                info.addView(TextView(requireContext()).apply { text = "ID: $devId"; setTextColor(Color.GRAY); textSize = 12f })
                item.addView(info)

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
                                            withContext(Dispatchers.Main) { renderDevices(); Toast.makeText(requireContext(), "تم طرد الجهاز بنجاح! 🔒", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                } catch(e: Exception){} 
                            }
                        }
                    }
                    item.addView(btnTerminate)
                }
                scrollContent.addView(item)
            }
        }
        renderDevices()
    }

    private fun fetchUserDataFromServer(userId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        val serverName = obj.getString("name")
                        val serverPass = obj.getString("password")
                        val serverPfp = obj.optString("pfp", currentBase64Pfp)
                        val serverUsername = obj.optString("username", "")
                        activeDevicesList = obj.optJSONArray("devices") ?: JSONArray()
                        
                        currentBase64Pfp = serverPfp
                        withContext(Dispatchers.Main) {
                            etName.setText(serverName)
                            etPass.setText(serverPass)
                            if (serverUsername.isNotEmpty()) etUsername.setText(serverUsername)
                            updateProfilePicture(currentBase64Pfp, serverName, userId)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateProfilePicture(base64Str: String, name: String, userId: String) {
        val bitmap = try {
            val b = Base64.decode(base64Str.substringAfter(",").replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null } ?: AvatarGenerator.generateAvatar(name, userId)
        
        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            ivAvatar.setImageDrawable(circularDrawable)
            ivAvatar.visibility = View.VISIBLE
            tvLetterAvatar.visibility = View.GONE
        }
    }
}
