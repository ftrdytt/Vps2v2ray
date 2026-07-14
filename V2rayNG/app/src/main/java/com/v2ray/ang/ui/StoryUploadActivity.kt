package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

class StoryUploadActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private var currentBase64Image: String = ""

    private lateinit var ivPreview: ImageView
    private lateinit var btnSelectImage: MaterialButton
    private lateinit var etText: TextInputEditText
    private lateinit var btnPublish: MaterialButton

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                // تقليل الدقة لمنع ثقل السيرفر وتسريع الرفع
                val maxImageSize = 800f
                val ratio = min(1f, min(maxImageSize / bitmap.width, maxImageSize / bitmap.height))
                val width = (ratio * bitmap.width).roundToInt()
                val height = (ratio * bitmap.height).roundToInt()
                
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                ivPreview.setImageBitmap(scaled)
                ivPreview.imageTintList = null // إزالة اللون الافتراضي للأيقونة

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                currentBase64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                showCustomSnackbar("فشل في قراءة الصورة", "#F44336")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_upload)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        ivPreview = findViewById(R.id.iv_story_preview)
        btnSelectImage = findViewById(R.id.btn_select_image)
        etText = findViewById(R.id.et_story_text)
        btnPublish = findViewById(R.id.btn_publish_story)

        btnSelectImage.setOnClickListener {
            pickImage.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }

        btnPublish.setOnClickListener {
            val textContent = etText.text.toString().trim()
            if (currentBase64Image.isEmpty() && textContent.isEmpty()) {
                showCustomSnackbar("يجب إضافة صورة أو كتابة نص على الأقل", "#F44336")
                return@setOnClickListener
            }
            uploadStory(textContent)
        }
    }

    private fun uploadStory(text: String) {
        btnPublish.isEnabled = false
        btnPublish.text = "جاري النشر..."
        val userId = AuthManager.getId(this)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$BASE_API_URL/story/add").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("userId", userId)
                    put("imageBase64", currentBase64Image)
                    put("textContent", text)
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StoryUploadActivity, "تم نشر القصة بنجاح ✅", Toast.LENGTH_SHORT).show()
                        finish() // إغلاق الواجهة والعودة للملف الشخصي
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showCustomSnackbar("فشل النشر، حاول مجدداً", "#F44336")
                        btnPublish.isEnabled = true
                        btnPublish.text = "نشر القصة 🚀"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showCustomSnackbar("خطأ في الاتصال بالسيرفر", "#F44336")
                    btnPublish.isEnabled = true
                    btnPublish.text = "نشر القصة 🚀"
                }
            }
        }
    }

    private fun showCustomSnackbar(message: String, colorHex: String) {
        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, "", Snackbar.LENGTH_SHORT)
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.setPadding(0, 0, 0, 0)

        val customView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = 40f
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(40, 0, 40, 40)
            }
        }
        snackbarLayout.addView(customView, 0)
        snackbar.show()
    }
}
