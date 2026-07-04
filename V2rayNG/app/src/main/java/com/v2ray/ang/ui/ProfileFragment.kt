package com.v2ray.ang.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator 
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

class ProfileFragment : Fragment() {

    private val BASE_API_URL = "https://education.ashor.shop"

    // 🌟 واجهات الواجهة برمجياً 🌟
    private lateinit var mainContainer: LinearLayout
    private lateinit var flAvatarContainer: FrameLayout
    private lateinit var tvLetterAvatar: TextView
    private lateinit var ivAvatar: ImageView
    
    private lateinit var btnAdminDashboard: ImageView
    private lateinit var btnUpdateLogs: ImageView 
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etUsername: EditText // 🌟 حقل المعرف 🌟
    private lateinit var etPass: EditText
    private lateinit var tvDeviceId: TextView // 🌟 عرض آيدي الجهاز 🌟
    
    private var currentBase64Pfp: String = ""
    private var myDeviceId: String = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
                    val maxImageSize = 800f
                    val ratio = min(maxImageSize / bitmap.width, maxImageSize / bitmap.height)
                    val width = (ratio * bitmap.width).roundToInt()
                    val height = (ratio * bitmap.height).roundToInt()
                    
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    val b = baos.toByteArray()
                    currentBase64Pfp = Base64.encodeToString(b, Base64.NO_WRAP)
                    
                    updateProfilePicture(currentBase64Pfp, AuthManager.getName(requireContext()), AuthManager.getId(requireContext()))
                } catch (e: Exception) {}
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // نستخدم نفس ملف الـ XML الخاص بك
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🌟 استخراج آيدي الجهاز 🌟
        myDeviceId = Settings.Secure.getString(requireActivity().contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        // جلب العناصر من ملف الـ XML
        btnAdminDashboard = view.findViewById(R.id.btn_admin_dashboard)
        btnUpdateLogs = view.findViewById(R.id.btn_update_logs) 
        etId = view.findViewById(R.id.et_profile_id)
        etName = view.findViewById(R.id.et_profile_name)
        etPass = view.findViewById(R.id.et_profile_pass)
        val btnSave = view.findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        // 🌟 بناء وتخصيص حاوية الصورة لتكون دائرية (نفس نظام التليجرام) 🌟
        val originalImageContainer = view.findViewById<View>(R.id.iv_profile_pic).parent as ViewGroup
        val imageIndex = originalImageContainer.indexOfChild(view.findViewById(R.id.iv_profile_pic))
        originalImageContainer.removeView(view.findViewById(R.id.iv_profile_pic)) // مسح الصورة القديمة المربعة

        val avatarCard = CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(250, 250).apply { gravity = Gravity.CENTER; setMargins(0, 40, 0, 40) }
            radius = 125f // دائري
            setCardBackgroundColor(Color.TRANSPARENT)
            cardElevation = 0f
            clipChildren = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
            setOnClickListener { val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); pickImage.launch(intent) }
        }
        
        flAvatarContainer = FrameLayout(requireContext()).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        tvLetterAvatar = TextView(requireContext()).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 40f; setTypeface(null, android.graphics.Typeface.BOLD); val bg = GradientDrawable(); bg.shape = GradientDrawable.OVAL; bg.setColor(Color.parseColor("#E91E63")); background = bg }
        ivAvatar = ImageView(requireContext()).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }

        flAvatarContainer.addView(tvLetterAvatar)
        flAvatarContainer.addView(ivAvatar)
        avatarCard.addView(flAvatarContainer)
        originalImageContainer.addView(avatarCard, imageIndex)

        // 🌟 إضافة حقل المعرف (Username) برمجياً تحت حقل الاسم 🌟
        etUsername = EditText(requireContext()).apply {
            hint = "المعرف (@) اختياري"
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 0) }
        }
        val nameContainer = etName.parent as ViewGroup
        val nameIndex = nameContainer.indexOfChild(etName)
        nameContainer.addView(etUsername, nameIndex + 1)

        // 🌟 إضافة معلومات الجهاز (ثابتة للمشاهدة فقط) في الأسفل 🌟
        tvDeviceId = TextView(requireContext()).apply {
            text = "📱 حسابك محمي ومقفل على هذا الجهاز فقط:\nID: $myDeviceId"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val logoutContainer = btnLogout.parent as ViewGroup
        logoutContainer.addView(tvDeviceId, 0)

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

        view.findViewById<View>(R.id.btn_change_avatar)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(intent)
        }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newUsername = etUsername.text.toString().trim().replace("@", "")
            val newPass = etPass.text.toString().trim()
            
            if (newName.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(requireContext(), "يرجى ملء الاسم وكلمة المرور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 🌟 فحص شروط المعرف (طريقة التليجرام) 🌟
            if (newUsername.isNotEmpty()) {
                if (!newUsername.matches(Regex("^[a-zA-Z0-9_.]{2,}\$"))) {
                    Toast.makeText(requireContext(), "المعرف غير صالح! مسموح فقط بالحروف، الأرقام، ( _ )، و ( . ) ويجب أن يكون حرفين فأكثر.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            btnSave.isEnabled = false
            btnSave.text = "جاري الحفظ السحابي..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = URL("$BASE_API_URL/auth/update")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    
                    val payload = JSONObject().apply {
                        put("id", AuthManager.getId(requireContext()))
                        put("currentPassword", AuthManager.getPass(requireContext()))
                        put("newName", newName)
                        put("password", newPass)
                        put("newPfp", currentBase64Pfp)
                        put("username", newUsername) // 🌟 إرسال المعرف للسيرفر 🌟
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(requireContext(), AuthManager.getId(requireContext()), newName, newPass, AuthManager.getRole(requireContext()), currentBase64Pfp)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "تم حفظ التعديلات بنجاح!", Toast.LENGTH_SHORT).show()
                                updateProfilePicture(currentBase64Pfp, newName, AuthManager.getId(requireContext()))
                                btnSave.isEnabled = true
                                btnSave.text = "حفظ التعديلات السحابية"
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), obj.optString("message", "لا يمكن تعديل هذا الحساب"), Toast.LENGTH_SHORT).show()
                                btnSave.isEnabled = true
                                btnSave.text = "حفظ التعديلات السحابية"
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                            btnSave.isEnabled = true
                            btnSave.text = "حفظ التعديلات السحابية"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "تأكد من الإنترنت لحفظ التعديلات", Toast.LENGTH_SHORT).show()
                        btnSave.isEnabled = true
                        btnSave.text = "حفظ التعديلات السحابية"
                    }
                }
            }
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("تسجيل خروج")
                .setMessage("هل أنت متأكد من تسجيل الخروج؟")
                .setPositiveButton("نعم") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL("$BASE_API_URL/admin/log_logout").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray(Charsets.UTF_8)) }
                            conn.responseCode 
                        } catch (e: Exception) {}
                    }
                    
                    AuthManager.logout(requireContext())
                    val intent = Intent(requireActivity(), LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun fetchUserDataFromServer(userId: String) {
        if (userId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ملاحظة: بما أنك لم ترسل لي كود (auth/get_user) في السيرفر، سيفشل هذا الطلب إذا لم يكن مبرمجاً في السيرفر
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    
                    if (obj.getBoolean("success")) {
                        val serverName = obj.optString("name", AuthManager.getName(requireContext()))
                        val serverPass = obj.optString("password", AuthManager.getPass(requireContext()))
                        val serverPfp = obj.optString("pfp", currentBase64Pfp)
                        val serverUsername = obj.optString("username", "") // جلب المعرف
                        
                        AuthManager.saveUser(requireContext(), userId, serverName, serverPass, AuthManager.getRole(requireContext()), serverPfp)
                        currentBase64Pfp = serverPfp

                        withContext(Dispatchers.Main) {
                            etName.setText(serverName)
                            if (serverUsername.isNotEmpty()) etUsername.setText(serverUsername)
                            etPass.setText(serverPass)
                            updateProfilePicture(currentBase64Pfp, serverName, userId)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    // تنظيف Base64
    private fun getSafeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrEmpty()) return null
        return try {
            val cleanStr = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val b = Base64.decode(cleanStr.replace("\\s+".toRegex(), ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    // تحديث الصورة وعملها دائرية
    private fun updateProfilePicture(base64Str: String, name: String, userId: String) {
        val bitmap = getSafeBitmap(base64Str)
        if (bitmap != null) {
            val circularDrawable = RoundedBitmapDrawableFactory.create(resources, bitmap)
            circularDrawable.isCircular = true
            ivAvatar.setImageDrawable(circularDrawable)
            ivAvatar.visibility = View.VISIBLE
            tvLetterAvatar.visibility = View.GONE
        } else {
            tvLetterAvatar.text = name.trim().firstOrNull()?.toString()?.uppercase() ?: "م"
            tvLetterAvatar.visibility = View.VISIBLE
            ivAvatar.visibility = View.GONE
        }
    }
}
