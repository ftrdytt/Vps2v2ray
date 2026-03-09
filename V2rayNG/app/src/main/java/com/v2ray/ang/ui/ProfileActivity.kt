package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

class ProfileActivity : AppCompatActivity() {
    
    private lateinit var ivPfp: ImageView
    private var currentBase64Pfp: String = ""

    // حل مشكلة الدقة المليحة (التغويش): زيادة الأبعاد والجودة
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    
                    // تحديد حجم كبير يحافظ على النقاء (800 بكسل كحد أقصى)
                    val maxImageSize = 800f
                    val ratio = min(maxImageSize / bitmap.width, maxImageSize / bitmap.height)
                    val width = (ratio * bitmap.width).roundToInt()
                    val height = (ratio * bitmap.height).roundToInt()
                    
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    val baos = ByteArrayOutputStream()
                    // ضغط بجودة ممتازة 95%
                    scaled.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    val b = baos.toByteArray()
                    currentBase64Pfp = Base64.encodeToString(b, Base64.NO_WRAP)
                    
                    // عرض الصورة الجديدة
                    ivPfp.setImageBitmap(scaled)
                    ivPfp.imageTintList = null // إزالة الفلتر الرمادي
                } catch (e: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        ivPfp = findViewById(R.id.iv_profile_pic)
        val etId = findViewById<EditText>(R.id.et_profile_id)
        val etName = findViewById<EditText>(R.id.et_profile_name)
        val etPass = findViewById<EditText>(R.id.et_profile_pass)
        val btnSave = findViewById<Button>(R.id.btn_save_profile)
        val btnLogout = findViewById<Button>(R.id.btn_logout)

        // جلب البيانات المحفوظة وعرضها
        etId.setText(AuthManager.getId(this))
        etName.setText(AuthManager.getName(this))
        etPass.setText(AuthManager.getPass(this))
        currentBase64Pfp = AuthManager.getPfp(this)
        
        if (currentBase64Pfp.isNotEmpty()) {
            try {
                val decodedBytes = Base64.decode(currentBase64Pfp, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                ivPfp.setImageBitmap(bitmap)
                ivPfp.imageTintList = null // إزالة الفلتر إذا وجدت صورة
            } catch (e: Exception) {}
        }

        ivPfp.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(intent)
        }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newPass = etPass.text.toString().trim()
            
            if (newName.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(this, "يرجى ملء جميع الحقول", Toast.LENGTH_SHORT).show()
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
                        put("id", AuthManager.getId(this@ProfileActivity))
                        put("currentPassword", AuthManager.getPass(this@ProfileActivity))
                        put("newName", newName)
                        put("password", newPass)
                        put("newPfp", currentBase64Pfp)
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(this@ProfileActivity, AuthManager.getId(this@ProfileActivity), newName, newPass, AuthManager.getRole(this@ProfileActivity), currentBase64Pfp)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ProfileActivity, "تم حفظ التعديلات بنجاح!", Toast.LENGTH_SHORT).show()
                                btnSave.isEnabled = true
                                btnSave.text = "حفظ التعديلات السحابية"
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ProfileActivity, obj.optString("message", "لا يمكن تعديل هذا الحساب"), Toast.LENGTH_SHORT).show()
                                btnSave.isEnabled = true
                                btnSave.text = "حفظ التعديلات السحابية"
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ProfileActivity, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                            btnSave.isEnabled = true
                            btnSave.text = "حفظ التعديلات السحابية"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ProfileActivity, "تأكد من الإنترنت لحفظ التعديلات", Toast.LENGTH_SHORT).show()
                        btnSave.isEnabled = true
                        btnSave.text = "حفظ التعديلات السحابية"
                    }
                }
            }
        }

        btnLogout.setOnClickListener {
            AuthManager.logout(this)
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
