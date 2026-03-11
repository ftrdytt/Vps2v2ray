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

    private lateinit var ivPfp: ImageView
    private lateinit var btnAdminDashboard: ImageView
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
        
        val etId = view.findViewById<EditText>(R.id.et_profile_id)
        val etName = view.findViewById<EditText>(R.id.et_profile_name)
        val etPass = view.findViewById<EditText>(R.id.et_profile_pass)
        val btnSave = view.findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        val userId = AuthManager.getId(requireContext())
        val userRole = AuthManager.getRole(requireContext())
        
        etId.setText(userId)
        etName.setText(AuthManager.getName(requireContext()))
        etPass.setText(AuthManager.getPass(requireContext()))
        currentBase64Pfp = AuthManager.getPfp(requireContext())
        
        // إظهار زر لوحة التحكم للأدمن فقط
        if (userRole == "admin") {
            btnAdminDashboard.visibility = View.VISIBLE
            btnAdminDashboard.setOnClickListener {
                startActivity(Intent(requireContext(), AdminDashboardActivity::class.java))
            }
        }

        // التحقق مما إذا كان هناك صورة محفوظة
        if (currentBase64Pfp.isNotEmpty()) {
            try {
                val decodedBytes = Base64.decode(currentBase64Pfp, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                ivPfp.setImageBitmap(bitmap)
                ivPfp.imageTintList = null 
            } catch (e: Exception) {}
        } else {
            // إذا لم يكن هناك صورة، نقوم بتحميل صورة افتراضية (أنمي/أفاتار) بناءً على ID المستخدم
            loadDefaultAvatar(userId)
        }

        // زر تغيير الصورة مدمج مع زر الكاميرا الصغير
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
                    val url = URL("https://vpn-license.rauter505.workers.dev/auth/update")
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
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(requireContext(), AuthManager.getId(requireContext()), newName, newPass, AuthManager.getRole(requireContext()), currentBase64Pfp)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "تم حفظ التعديلات بنجاح!", Toast.LENGTH_SHORT).show()
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
                    // تسجيل الخروج السحابي للإحصائيات
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL("https://vpn-license.rauter505.workers.dev/admin/log_logout").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray()) }
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

    // دالة مساعدة لتحميل الأفاتار الافتراضي من الإنترنت
    private fun loadDefaultAvatar(seed: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // نستخدم خدمة DiceBear للحصول على أفاتار شخصية أنمي (adventurer)
                val avatarUrl = URL("https://api.dicebear.com/7.x/adventurer/png?seed=$seed&backgroundColor=b6e3f4,c0aede,d1d4f9")
                val connection = avatarUrl.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        ivPfp.setImageBitmap(bitmap)
                        ivPfp.imageTintList = null // إزالة الفلتر الرمادي
                    }
                }
            } catch (e: Exception) {
                // في حال فشل التحميل، نترك الصورة الافتراضية
            }
        }
    }
}
