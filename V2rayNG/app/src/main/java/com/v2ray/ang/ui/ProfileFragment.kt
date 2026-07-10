package com.v2ray.ang.ui

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    private lateinit var ivAvatar: ImageView
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvUsernameStatus: TextView 
    private lateinit var etPass: EditText
    private lateinit var btnSave: Button
    
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
                updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), AuthManager.getId(requireContext()))
            } catch (e: Exception) {}
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Data", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "تم نسخ $label 📋", Toast.LENGTH_SHORT).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        myDeviceId = Settings.Secure.getString(requireActivity().contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        etId = view.findViewById(R.id.et_profile_id)
        etName = view.findViewById(R.id.et_profile_name)
        etPass = view.findViewById(R.id.et_profile_pass)
        btnSave = view.findViewById(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        ivAvatar = view.findViewById(R.id.iv_profile_pic)

        // 🌟 إعادة بناء الواجهة بالكامل برمجياً لحمايتها من الـ Crash 🌟
        val rootLayout = etId.parent as? ViewGroup ?: return
        
        // 1. حاوية ذكية للصورة
        val avatarIdx = rootLayout.indexOfChild(ivAvatar)
        if (avatarIdx != -1) {
            rootLayout.removeView(ivAvatar)
            val centerWrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val card = CardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(250, 250).apply { setMargins(0, 30, 0, 30) }
                radius = 125f
                setCardBackgroundColor(Color.TRANSPARENT)
                cardElevation = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
                setOnClickListener { pickImage.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) }
            }
            ivAvatar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            ivAvatar.scaleType = ImageView.ScaleType.FIT_XY
            card.addView(ivAvatar)
            centerWrapper.addView(card)
            rootLayout.addView(centerWrapper, avatarIdx)
        }

        // 2. زر نسخ الايدي
        val btnCopyId = MaterialButton(requireContext()).apply {
            text = "نسخ آيدي الحساب 📋"
            setBackgroundColor(Color.parseColor("#252529"))
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 5, 0, 30) }
            setOnClickListener { copyToClipboard("آيدي الحساب", etId.text.toString()) }
        }
        rootLayout.addView(btnCopyId, rootLayout.indexOfChild(etId) + 1)

        // 3. المعرف (Username)
        val usernameWrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 20) }
        }
        etUsername = EditText(requireContext()).apply {
            hint = "المعرف (@) اختياري"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 16f
            background = etName.background
            setPadding(etName.paddingLeft, etName.paddingTop, etName.paddingRight, etName.paddingBottom)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    checkUsernameLive(s.toString().trim().replace("@", ""))
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        tvUsernameStatus = TextView(requireContext()).apply {
            textSize = 12f
            setPadding(20, 10, 20, 0)
            visibility = View.GONE
        }
        usernameWrapper.addView(etUsername)
        usernameWrapper.addView(tvUsernameStatus)
        rootLayout.addView(usernameWrapper, rootLayout.indexOfChild(etName) + 1)

        // 4. آيدي الجهاز وزر النسخ
        val deviceWrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 30) }
        }
        val tvDeviceLabel = TextView(requireContext()).apply { text = "آيدي جهازك الحالي 📱:"; setTextColor(Color.GRAY); setPadding(10,10,10,5) }
        val etDeviceDisplay = EditText(requireContext()).apply {
            setText(myDeviceId)
            isEnabled = false
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 14f
            background = etId.background
            setPadding(etId.paddingLeft, etId.paddingTop, etId.paddingRight, etId.paddingBottom)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnCopyDev = MaterialButton(requireContext()).apply {
            text = "نسخ آيدي الجهاز 📋"
            setBackgroundColor(Color.parseColor("#252529"))
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 5, 0, 0) }
            setOnClickListener { copyToClipboard("آيدي الجهاز", myDeviceId) }
        }
        deviceWrapper.addView(tvDeviceLabel)
        deviceWrapper.addView(etDeviceDisplay)
        deviceWrapper.addView(btnCopyDev)
        rootLayout.addView(deviceWrapper, rootLayout.indexOfChild(etPass) + 1)

        // 5. زر إدارة الأجهزة النشطة
        val btnManageDevices = MaterialButton(requireContext()).apply {
            text = "📱 إدارة الأجهزة النشطة ومراقبة الجلسات"
            setBackgroundColor(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 30) }
            setOnClickListener { showDevicesDialog() }
        }
        rootLayout.addView(btnManageDevices, rootLayout.indexOfChild(btnSave))

        // ==========================================

        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())
        
        etId.setText(userId)
        etName.setText(AuthManager.getName(requireContext()))
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        fetchUserDataFromServer(userId)

        if (userRole == "admin") {
            view.findViewById<View>(R.id.btn_admin_dashboard)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(requireContext(), AdminDashboardActivity::class.java)) }
            }
            view.findViewById<View>(R.id.btn_update_logs)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(requireContext(), UpdateLogsActivity::class.java)) }
            }
        }

        updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), userId)

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
            saveProfile(newName, newUsername, newPass, userRole)
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
                    startActivity(intent)
                    requireActivity().finish()
                }.setNegativeButton("إلغاء", null).show()
        }
    }

    private fun saveProfile(name: String, username: String, pass: String, role: String) {
        btnSave.isEnabled = false
        btnSave.text = "جاري الحفظ..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/update").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("id", AuthManager.getId(requireContext()))
                    put("currentPassword", AuthManager.getPass(requireContext()))
                    put("newName", name)
                    put("password", pass)
                    put("newPfp", currentBase64Pfp)
                    put("username", username)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        AuthManager.saveUser(requireContext(), AuthManager.getId(requireContext()), name, pass, role, currentBase64Pfp)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
                            updateProfilePicture(currentBase64Pfp, name, AuthManager.getId(requireContext()))
                            btnSave.isEnabled = true
                            btnSave.text = "حفظ التعديلات السحابية"
                        }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), obj.optString("message", "فشل الحفظ"), Toast.LENGTH_SHORT).show(); btnSave.isEnabled = true; btnSave.text = "حفظ التعديلات السحابية" }
                    }
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { btnSave.isEnabled = true; btnSave.text = "حفظ التعديلات السحابية" } 
            }
        }
    }

    private fun checkUsernameLive(username: String) {
        checkUserJob?.cancel()
        if (!::tvUsernameStatus.isInitialized) return
        if (username.isEmpty()) { tvUsernameStatus.visibility = View.GONE; return }
        if (username.length < 2) {
            tvUsernameStatus.visibility = View.VISIBLE
            tvUsernameStatus.text = "❌ المعرف قصير جداً"
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
                                            withContext(Dispatchers.Main) { renderDevices(); Toast.makeText(requireContext(), "تم طرد الجهاز بنجاح! 🔒", Toast.LENGTH_SHORT).show() }
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

    private fun updateProfilePicture(base64Str: String, name: String, userId: String) {
        val bitmap = try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null } ?: AvatarGenerator.generateAvatar(name, userId)
        
        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
            ivAvatar.setImageDrawable(circularDrawable)
        }
    }

    private fun fetchUserDataFromServer(userId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        withContext(Dispatchers.Main) {
                            etName.setText(obj.getString("name"))
                            etPass.setText(obj.getString("password"))
                            etUsername.setText(obj.optString("username", ""))
                            activeDevicesList = obj.optJSONArray("devices") ?: JSONArray()
                            currentBase64Pfp = obj.optString("pfp", currentBase64Pfp)
                            updateProfilePicture(currentBase64Pfp, obj.getString("name"), userId)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
