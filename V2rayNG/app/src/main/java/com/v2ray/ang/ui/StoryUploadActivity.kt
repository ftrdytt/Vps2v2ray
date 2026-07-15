package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.roundToInt

class StoryUploadActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private var currentBase64Image: String = ""

    private lateinit var ivPreview: ImageView
    private lateinit var tvPreviewText: TextView
    private lateinit var viewOverlay: View
    private lateinit var btnSelectImage: MaterialButton
    private lateinit var etText: TextInputEditText
    private lateinit var btnPublish: MaterialButton
    private lateinit var layoutLoading: FrameLayout

    // لوحة ألوان للقصص النصية فقط (مثل إنستغرام)
    private val textStoryColors = arrayOf(
        "#FF5722", "#9C27B0", "#E91E63", "#009688", "#3F51B5", "#4CAF50", "#FF9800", "#607D8B"
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                // تم رفع الجودة قليلاً لـ 1080 لتبدو الصور أوضح على الشاشات الحديثة مع الحفاظ على سرعة الرفع
                val maxImageSize = 1080f
                val ratio = min(1f, min(maxImageSize / bitmap.width, maxImageSize / bitmap.height))
                val width = (ratio * bitmap.width).roundToInt()
                val height = (ratio * bitmap.height).roundToInt()
                
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                ivPreview.setImageBitmap(scaled)
                ivPreview.imageTintList = null // إزالة التلوين الرمادي الافتراضي
                ivPreview.setBackgroundColor(Color.TRANSPARENT)
                
                // إظهار التظليل ليكون النص مقروءاً فوق الصورة
                viewOverlay.visibility = View.VISIBLE

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
        tvPreviewText = findViewById(R.id.tv_preview_text)
        viewOverlay = findViewById(R.id.view_overlay)
        btnSelectImage = findViewById(R.id.btn_select_image)
        etText = findViewById(R.id.et_story_text)
        btnPublish = findViewById(R.id.btn_publish_story)
        layoutLoading = findViewById(R.id.layout_loading)

        // 🌟 إعداد المعاينة الحية للنص 🌟
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                tvPreviewText.text = text
                
                // إذا لم يكن هناك صورة، قم بتغيير لون الخلفية بناءً على النص
                if (currentBase64Image.isEmpty()) {
                    if (text.isNotEmpty()) {
                        ivPreview.setImageDrawable(null) // إزالة الأيقونة الافتراضية
                        val colorIndex = text.hashCode().absoluteValue % textStoryColors.size
                        ivPreview.setBackgroundColor(Color.parseColor(textStoryColors[colorIndex]))
                    } else {
                        // العودة للحالة الافتراضية
                        ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
                        ivPreview.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#555555"))
                        ivPreview.setBackgroundColor(Color.TRANSPARENT)
                    }
                }
            }
        })

        btnSelectImage.setOnClickListener {
            pickImage.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }

        btnPublish.setOnClickListener {
            val textContent = etText.text.toString().trim()
            if (currentBase64Image.isEmpty() && textContent.isEmpty()) {
                showCustomSnackbar("يجب إضافة صورة أو كتابة نص على الأقل", "#FF9800")
                return@setOnClickListener
            }
            uploadStory(textContent)
        }
    }

    private fun uploadStory(text: String) {
        // إظهار شاشة التحميل وتعطيل الأزرار
        layoutLoading.visibility = View.VISIBLE
        btnPublish.isEnabled = false
        btnSelectImage.isEnabled = false
        etText.isEnabled = false

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
                        layoutLoading.visibility = View.GONE
                        showCustomSnackbar("فشل النشر، حاول مجدداً", "#F44336")
                        enableControls()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    layoutLoading.visibility = View.GONE
                    showCustomSnackbar("خطأ في الاتصال بالسيرفر", "#F44336")
                    enableControls()
                }
            }
        }
    }

    private fun enableControls() {
        btnPublish.isEnabled = true
        btnSelectImage.isEnabled = true
        etText.isEnabled = true
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
