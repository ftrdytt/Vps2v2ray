package com.v2ray.ang.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.util.AvatarGenerator // 🌟 استدعاء نظام الصور الذكي 🌟
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

class ProfileFragment : Fragment() {

    // 🌟 الرابط الجديد الأساسي الآمن والمخفي 🌟
    private val BASE_API_URL = "https://education.ashor.shop"

    private lateinit var ivPfp: ImageView
    private lateinit var btnAdminDashboard: ImageView
    private lateinit var btnUpdateLogs: ImageView 
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etPass: EditText
    private var currentBase64Pfp: String = ""

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
                    
                    ivPfp.setImageBitmap(scaled)
                    ivPfp.imageTintList = null 
                } catch (e: Exception) {}
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivPfp = view.findViewById(R.id.iv_profile_pic)
        btnAdminDashboard = view.findViewById(R.id.btn_admin_dashboard)
        btnUpdateLogs = view.findViewById(R.id.btn_update_logs) 
        
        etId = view.findViewById(R.id.et_profile_id)
        etName = view.findViewById(R.id.et_profile_name)
        etPass = view.findViewById(R.id.et_profile_pass)
        val btnSave = view.findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())
        val userName = AuthManager.getName(requireContext())
        
        etId.setText(userId)
        etName.setText(userName)
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        // 🌟 استدعاء دالة جلب البيانات الجديدة من السيرفر 🌟
        fetchUserDataFromServer(userId)

        if (userRole == "admin") {
            btnAdminDashboard.visibility = View.VISIBLE
            btnAdminDashboard.setOnClickListener {
                startActivity(Intent(requireContext(), AdminDashboardActivity::class.java))
            }
            
            btnUpdateLogs.visibility = View.VISIBLE
            btnUpdateLogs.setOnClickListener {
                startActivity(Intent(requireContext(), UpdateLogsActivity::class.java))
            }
        }

        updateProfilePicture(currentBase64Pfp, userName, userId)

        view.findViewById<View>(R.id.btn_change_avatar)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(intent)
        }
        
        ivPfp.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(intent)
        }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newPass = etPass.text.toString().trim()
            
            if (newName.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(requireContext(), "يرجى ملء جميع الحقول", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnSave.text = "جاري الحفظ السحابي..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 🌟 استخدام الرابط الجديد 🌟
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
                    }
                    // 🌟 إضافة ترميز UTF-8 🌟
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
                            // 🌟 استخدام الرابط الجديد 🌟
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

    // 🌟 دالة جلب البيانات من السيرفر 🌟
    private fun fetchUserDataFromServer(userId: String) {
        if (userId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 🌟 استخدام الرابط الجديد 🌟
                val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    
                    if (obj.getBoolean("success")) {
                        val serverName = obj.optString("name", AuthManager.getName(requireContext()))
                        val serverPass = obj.optString("password", AuthManager.getPass(requireContext()))
                        val serverPfp = obj.optString("pfp", currentBase64Pfp)
                        
                        // تحديث البيانات محلياً
                        AuthManager.saveUser(requireContext(), userId, serverName, serverPass, AuthManager.getRole(requireContext()), serverPfp)
                        currentBase64Pfp = serverPfp

                        // تحديث الواجهة (UI)
                        withContext(Dispatchers.Main) {
                            etName.setText(serverName)
                            etPass.setText(serverPass)
                            updateProfilePicture(currentBase64Pfp, serverName, userId)
                        }
                    }
                }
            } catch (e: Exception) {
                // إذا لم يتصل، تبقى البيانات القديمة كما هي
            }
        }
    }

    // 🌟 التعديل السحري: الاعتماد على الـ AvatarGenerator 🌟
    private fun updateProfilePicture(base64Str: String, name: String, userId: String) {
        if (base64Str.isNotEmpty()) {
            try {
                val decodedBytes = Base64.decode(base64Str, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                ivPfp.setImageBitmap(bitmap)
                ivPfp.imageTintList = null 
            } catch (e: Exception) {
                ivPfp.setImageBitmap(AvatarGenerator.generateAvatar(name, userId))
                ivPfp.imageTintList = null 
            }
        } else {
            ivPfp.setImageBitmap(AvatarGenerator.generateAvatar(name, userId))
            ivPfp.imageTintList = null 
        }
    }
}
