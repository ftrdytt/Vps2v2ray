package com.v2ray.ang.ui

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private var currentBase64Pfp: String = ""
    private var myDeviceId: String = ""
    private var activeDevicesList = JSONArray()
    private var checkUserJob: Job? = null 

    private lateinit var ivAvatar: ImageView

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
                // 🌟 ضغط الصورة وتقليل حجمها لتسريع الحفظ السحابي ومنع التعليق 🌟
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        myDeviceId = Settings.Secure.getString(requireActivity().contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        ivAvatar = view.findViewById(R.id.iv_profile_pic)
        val etId = view.findViewById<EditText>(R.id.et_profile_id)
        val etName = view.findViewById<EditText>(R.id.et_profile_name)
        val etPass = view.findViewById<EditText>(R.id.et_profile_pass)
        val btnSave = view.findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        val btnAdminDashboard = view.findViewById<ImageView>(R.id.btn_admin_dashboard)
        val btnUpdateLogs = view.findViewById<ImageView>(R.id.btn_update_logs)

        ivAvatar.setOnClickListener {
            pickImage.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }

        // 🌟 بناء الواجهة المحمية (Safe UI Injection) 🌟
        val rootLayout = etId.parent as? ViewGroup ?: return

        // 1. حقل المعرف وحالته
        val tvUsernameStatus = TextView(requireContext()).apply { textSize = 12f; setPadding(20, 5, 20, 0); visibility = View.GONE }
        val etUsername = EditText(requireContext()).apply {
            hint = "المعرف (@) اختياري"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 10) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    checkUsernameLive(s.toString().trim().replace("@", ""), tvUsernameStatus)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val wrapperUsername = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(etUsername)
            addView(tvUsernameStatus)
        }
        rootLayout.addView(wrapperUsername, rootLayout.indexOfChild(etName) + 1)

        // 2. زر نسخ الايدي
        val btnCopyId = MaterialButton(requireContext()).apply {
            text = "نسخ آيدي الحساب 📋"
            setBackgroundColor(Color.parseColor("#252529"))
            setTextColor(Color.parseColor("#FF9800"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply { setMargins(0, 10, 0, 20) }
            setOnClickListener { copyToClipboard("آيدي الحساب", etId.text.toString()) }
        }
        rootLayout.addView(btnCopyId, rootLayout.indexOfChild(etId) + 1)

        // 3. حقل جهاز المستخدم وزر النسخ
        val wrapperDevice = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 20) }
            
            val tvLabel = TextView(requireContext()).apply { text = "آيدي جهازك الحالي 📱:"; setTextColor(Color.GRAY); setPadding(10, 10, 10, 5) }
            val etDevice = EditText(requireContext()).apply {
                setText(myDeviceId)
                isEnabled = false
                setTextColor(Color.parseColor("#4CAF50"))
                textSize = 14f
            }
            val btnCopyDev = MaterialButton(requireContext()).apply {
                text = "نسخ آيدي الجهاز 📋"
                setBackgroundColor(Color.parseColor("#252529"))
                setTextColor(Color.parseColor("#FF9800"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply { setMargins(0, 10, 0, 0) }
                setOnClickListener { copyToClipboard("آيدي الجهاز", myDeviceId) }
            }
            
            addView(tvLabel)
            addView(etDevice)
            addView(btnCopyDev)
        }
        rootLayout.addView(wrapperDevice, rootLayout.indexOfChild(etPass) + 1)

        // 4. إدارة الأجهزة
        val btnManageDevices = MaterialButton(requireContext()).apply {
            text = "📱 إدارة الأجهزة النشطة ومراقبة الجلسات"
            setBackgroundColor(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 30) }
            setOnClickListener { showDevicesDialog() }
        }
        rootLayout.addView(btnManageDevices, rootLayout.indexOfChild(btnSave))

        // ==========================================
        // تهيئة البيانات الأولية
        // ==========================================
        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())
        
        etId.setText(userId)
        etName.setText(AuthManager.getName(requireContext()))
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        fetchUserDataFromServer(userId, etName, etPass, etUsername, ivAvatar)

        if (userRole == "admin") {
            btnAdminDashboard.visibility = View.VISIBLE
            btnAdminDashboard.setOnClickListener { startActivity(Intent(requireContext(), AdminDashboardActivity::class.java)) }
            btnUpdateLogs.visibility = View.VISIBLE
            btnUpdateLogs.setOnClickListener { startActivity(Intent(requireContext(), UpdateLogsActivity::class.java)) }
        }

        updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), userId)

        // ==========================================
        // أزرار الحفظ والخروج
        // ==========================================
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val user = etUsername.text.toString().trim().replace("@", "")
            val pass = etPass.text.toString().trim()
            
            if (name.isEmpty() || pass.isEmpty()) {
                Toast.makeText(requireContext(), "الاسم وكلمة المرور مطلوبة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (user.isNotEmpty() && !user.matches(Regex("^[a-zA-Z0-9_.]{2,}\$"))) {
                Toast.makeText(requireContext(), "المعرف غير صالح!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveProfile(name, user, pass, btnSave)
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "تم نسخ $label 📋", Toast.LENGTH_SHORT).show()
    }

    private fun saveProfile(name: String, username: String, pass: String, btn: Button) {
        btn.isEnabled = false
        btn.text = "جاري الحفظ..."
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
                        AuthManager.saveUser(requireContext(), AuthManager.getId(requireContext()), name, pass, AuthManager.getRole(requireContext()), currentBase64Pfp)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
                            btn.isEnabled = true
                            btn.text = "حفظ التعديلات السحابية"
                        }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), obj.optString("message", "فشل الحفظ"), Toast.LENGTH_SHORT).show(); btn.isEnabled = true; btn.text = "حفظ التعديلات السحابية" }
                    }
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { btn.isEnabled = true; btn.text = "حفظ التعديلات السحابية" } 
            }
        }
    }

    private fun checkUsernameLive(username: String, tvStatus: TextView) {
        checkUserJob?.cancel()
        if (username.isEmpty()) { tvStatus.visibility = View.GONE; return }
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "⏳ فحص المعرف..."
        tvStatus.setTextColor(Color.parseColor("#FF9800"))
        
        checkUserJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            try {
                val conn = URL("$BASE_API_URL/auth/check_username?username=$username&id=${AuthManager.getId(requireContext())}").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val available = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getBoolean("available")
                    withContext(Dispatchers.Main) {
                        tvStatus.text = if (available) "✅ المعرف متاح" else "❌ المعرف محجوز"
                        tvStatus.setTextColor(if (available) Color.GREEN else Color.RED)
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
            // نمسح كلشي ما عدا العنوان
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
                        text = "طرد ❌"; setBackgroundColor(Color.RED); textSize = 10f; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(10, 0, 0, 0) }
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
                                            withContext(Dispatchers.Main) { renderDevices(); Toast.makeText(requireContext(), "تم طرد الجهاز بنجاح!", Toast.LENGTH_SHORT).show() }
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
        
        val circular = RoundedBitmapDrawableFactory.create(resources, bitmap)
        circular.isCircular = true
        ivAvatar.setImageDrawable(circular)
    }
    
    private fun fetchUserDataFromServer(userId: String, etName: EditText, etPass: EditText, etUser: EditText, iv: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    if (obj.getBoolean("success")) {
                        withContext(Dispatchers.Main) {
                            etName.setText(obj.getString("name"))
                            etPass.setText(obj.getString("password"))
                            etUser.setText(obj.optString("username", ""))
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
