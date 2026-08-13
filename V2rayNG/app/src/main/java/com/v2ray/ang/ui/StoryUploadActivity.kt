package com.v2ray.ang.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.roundToInt

class StoryUploadActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    
    private var hasSelectedImage = false
    private var hasSelectedVideo = false
    private var selectedVideoUri: Uri? = null

    // 🌟 عناصر الواجهة 🌟
    private lateinit var storyCaptureFrame: FrameLayout
    private lateinit var ivPreview: ImageView
    private lateinit var tvPreviewText: TextView
    private lateinit var viewOverlay: View
    private lateinit var btnSelectImage: MaterialButton
    private lateinit var etText: TextInputEditText
    private lateinit var btnPublish: MaterialButton
    private lateinit var layoutLoading: FrameLayout

    // 🌟 متغيرات أدوات التعديل (ستايل انستغرام) 🌟
    private var currentTextColor = Color.WHITE
    private var currentTextStyle = "CLASSIC" // CLASSIC, NEON, TYPEWRITER

    // لوحة ألوان للقصص
    private val textStoryColors = arrayOf(
        "#FFFFFF", "#000000", "#FF5722", "#9C27B0", "#E91E63", "#009688", "#3F51B5", "#4CAF50", "#FF9800", "#FFEB3B"
    )

    private val pickMedia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val mimeType = contentResolver.getType(uri) ?: ""

            if (mimeType.startsWith("video/")) {
                handleVideoSelection(uri)
            } else {
                handleImageSelection(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_upload)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        storyCaptureFrame = findViewById(R.id.story_capture_frame)
        ivPreview = findViewById(R.id.iv_story_preview)
        tvPreviewText = findViewById(R.id.tv_preview_text)
        viewOverlay = findViewById(R.id.view_overlay)
        btnSelectImage = findViewById(R.id.btn_select_image)
        etText = findViewById(R.id.et_story_text)
        btnPublish = findViewById(R.id.btn_publish_story)
        layoutLoading = findViewById(R.id.layout_loading)

        // 🌟 بناء شريط أدوات التصميم (الألوان والخطوط) برمجياً وإضافته للشاشة 🌟
        buildStoryEditorTools()

        // تفعيل نظام السحب والإفلات والتكبير للنص
        setupTouchListeners(tvPreviewText)

        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                tvPreviewText.text = text
                
                if (!hasSelectedImage && !hasSelectedVideo) {
                    if (text.isNotEmpty()) {
                        ivPreview.setImageDrawable(null)
                        val colorIndex = text.hashCode().absoluteValue % textStoryColors.size
                        ivPreview.setBackgroundColor(Color.parseColor(textStoryColors[colorIndex]))
                    } else {
                        ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
                        ivPreview.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#555555"))
                        ivPreview.setBackgroundColor(Color.TRANSPARENT)
                    }
                }
            }
        })

        btnSelectImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            pickMedia.launch(intent)
        }

        btnPublish.setOnClickListener {
            val textContent = etText.text.toString().trim()
            if (!hasSelectedImage && !hasSelectedVideo && textContent.isEmpty()) {
                showCustomSnackbar("يجب إضافة صورة أو فيديو أو كتابة نص على الأقل", "#FF9800")
                return@setOnClickListener
            }

            // 🌟 التحقق من قيود الهاردوير (Limits Check) قبل النشر 🌟
            if (!checkDailyQuota(isVideo = hasSelectedVideo)) {
                return@setOnClickListener
            }

            uploadStory()
        }
    }

    // =========================================================================
    // 🌟 أدوات تعديل الاستوري (انستغرام ستايل) المضافة برمجياً 🌟
    // =========================================================================
    private fun buildStoryEditorTools() {
        val rootLayout = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        
        val toolsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                setMargins(0, 0, 0, 350) // لرفعه فوق زر النشر
            }
        }

        // 1. شريط الستايلات (كلاسيكي، نيون، آلة كاتبة)
        val stylesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }

        val styles = mapOf("كلاسيكي" to "CLASSIC", "نيون" to "NEON", "آلة كاتبة" to "TYPEWRITER")
        for ((label, styleKey) in styles) {
            val btnStyle = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(30, 15, 30, 15)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#66000000"))
                    cornerRadius = 30f
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(10, 0, 10, 0)
                }
                setOnClickListener {
                    applyTextStyle(styleKey)
                }
            }
            stylesLayout.addView(btnStyle)
        }

        // 2. شريط الألوان الدائري
        val colorsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(20, 0, 20, 0)
        }
        val colorsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        for (colorHex in textStoryColors) {
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(15, 0, 15, 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(colorHex))
                    setStroke(3, Color.WHITE)
                }
                setOnClickListener {
                    currentTextColor = Color.parseColor(colorHex)
                    applyTextStyle(currentTextStyle) 
                }
            }
            colorsLayout.addView(colorView)
        }

        colorsScroll.addView(colorsLayout)
        toolsContainer.addView(stylesLayout)
        toolsContainer.addView(colorsScroll)
        rootLayout.addView(toolsContainer)
    }

    private fun applyTextStyle(styleKey: String) {
        currentTextStyle = styleKey
        tvPreviewText.setTextColor(currentTextColor)

        when (styleKey) {
            "CLASSIC" -> {
                tvPreviewText.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                tvPreviewText.setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            "NEON" -> {
                tvPreviewText.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL)
                // توهج بلون النص
                tvPreviewText.setShadowLayer(25f, 0f, 0f, currentTextColor) 
                tvPreviewText.setTextColor(Color.WHITE) 
            }
            "TYPEWRITER" -> {
                tvPreviewText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                tvPreviewText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tvPreviewText.setBackgroundColor(Color.parseColor("#88000000")) // خلفية سوداء شفافة للنص
            }
        }
        
        if (styleKey != "TYPEWRITER") {
            tvPreviewText.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    // =========================================================================
    // 🌟 معالجة واختيار الميديا (الفيديو والصور) 🌟
    // =========================================================================
    private fun handleImageSelection(uri: Uri) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            val maxImageSize = 1080f
            val ratio = min(1f, min(maxImageSize / bitmap.width, maxImageSize / bitmap.height))
            val width = (ratio * bitmap.width).roundToInt()
            val height = (ratio * bitmap.height).roundToInt()
            
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            ivPreview.setImageBitmap(scaled)
            ivPreview.imageTintList = null
            ivPreview.setBackgroundColor(Color.TRANSPARENT)
            
            viewOverlay.visibility = View.VISIBLE
            hasSelectedImage = true
            hasSelectedVideo = false
            selectedVideoUri = null
            
        } catch (e: Exception) {
            showCustomSnackbar("فشل في قراءة الصورة", "#F44336")
        }
    }

    private fun handleVideoSelection(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = timeString?.toLongOrNull() ?: 0L

            // 🌟 شرط الفيديو 60 ثانية 🌟
            if (durationMs > 60500L) { 
                showCustomSnackbar("عذراً، يجب أن لا تتجاوز مدة الفيديو دقيقة واحدة (60 ثانية)", "#F44336")
                return
            }

            // وضع صورة مصغرة للفيديو
            val thumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (thumbnail != null) {
                ivPreview.setImageBitmap(thumbnail)
            } else {
                ivPreview.setImageResource(android.R.drawable.ic_media_play)
            }
            
            ivPreview.imageTintList = null
            ivPreview.setBackgroundColor(Color.TRANSPARENT)
            viewOverlay.visibility = View.VISIBLE
            
            hasSelectedVideo = true
            hasSelectedImage = false
            selectedVideoUri = uri
            
            showCustomSnackbar("تم اختيار الفيديو بنجاح", "#4CAF50")

        } catch (e: Exception) {
            showCustomSnackbar("الفيديو غير مدعوم أو تالف", "#F44336")
        } finally {
            retriever.release()
        }
    }

    // =========================================================================
    // 🌟 نظام اللمس المتعدد (Pinch-to-zoom & Drag) الاحترافي 🌟
    // =========================================================================
    private fun setupTouchListeners(view: View) {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = view.scaleX * detector.scaleFactor
                view.scaleX = Math.max(0.5f, Math.min(scale, 3.0f))
                view.scaleY = Math.max(0.5f, Math.min(scale, 3.0f))
                return true
            }
        })

        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                        v.x = event.rawX + dX
                        v.y = event.rawY + dY
                    }
                }
            }
            true 
        }
    }

    // =========================================================================
    // 🌟 نظام البصمة (Hardware ID) وقيود النشر (24 ساعة) 🌟
    // =========================================================================
    private fun getHardwareId(): String {
        val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_id"
        val combined = devInfo + androidId
        
        val md = java.security.MessageDigest.getInstance("MD5")
        val hash = md.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16).uppercase()
    }

    private fun checkDailyQuota(isVideo: Boolean): Boolean {
        val prefs = getSharedPreferences("StoryQuotaPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        val videoCount = prefs.getInt("vid_$todayDate", 0)
        val imageTextCount = prefs.getInt("img_$todayDate", 0)

        if (isVideo && videoCount >= 2) {
            showCustomSnackbar("استهلكت الحد اليومي للفيديوهات (2/2) لهذا الجهاز 🚫", "#F44336")
            return false
        } else if (!isVideo && imageTextCount >= 25) {
            showCustomSnackbar("استهلكت الحد اليومي للصور (25/25) لهذا الجهاز 🚫", "#F44336")
            return false
        }
        return true
    }

    private fun incrementDailyQuota(isVideo: Boolean) {
        val prefs = getSharedPreferences("StoryQuotaPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val key = if (isVideo) "vid_$todayDate" else "img_$todayDate"
        
        val currentCount = prefs.getInt(key, 0)
        prefs.edit().putInt(key, currentCount + 1).apply()
    }

    // =========================================================================
    // 🌟 عملية الرفع والتشفير 🌟
    // =========================================================================
    private fun captureStoryFrame(view: View): String {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos) 
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun convertVideoToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun uploadStory() {
        layoutLoading.visibility = View.VISIBLE
        btnPublish.isEnabled = false
        btnSelectImage.isEnabled = false
        etText.isEnabled = false

        val userId = AuthManager.getId(this)
        val hardwareId = getHardwareId() // بصمة الجهاز الصارمة
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaBase64: String
                val storyType: String
                val textContent = etText.text.toString().trim()

                // إذا فيديو: نرسل الفيديو كـ Base64، والنص نرسله كبيانات مفصولة حتى ينعرض فوكاه
                // إذا صورة/نص: ندمجهم كصورة وحدة Bitmap وندزها
                if (hasSelectedVideo && selectedVideoUri != null) {
                    val encodedVideo = convertVideoToBase64(selectedVideoUri!!)
                    if (encodedVideo == null) {
                        withContext(Dispatchers.Main) {
                            showCustomSnackbar("حجم الفيديو كبير جداً للتحويل", "#F44336")
                            layoutLoading.visibility = View.GONE
                            enableControls()
                        }
                        return@launch
                    }
                    mediaBase64 = encodedVideo
                    storyType = "video"
                } else {
                    mediaBase64 = captureStoryFrame(storyCaptureFrame)
                    storyType = "image"
                }

                val conn = URL("$BASE_API_URL/story/add").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("userId", userId)
                    put("hardwareId", hardwareId) 
                    put("type", storyType)
                    put("mediaBase64", mediaBase64)
                    put("textContent", if (storyType == "video") textContent else "") 
                    put("textStyle", currentTextStyle) // لمعرفة الستايل من قبل الـ Viewer
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    incrementDailyQuota(isVideo = hasSelectedVideo) // تسجيل العملية بالجهاز محلياً
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StoryUploadActivity, "تم نشر القصة بنجاح ✅", Toast.LENGTH_SHORT).show()
                        finish() 
                    }
                } else {
                    // إذا السيرفر رفض (مثلاً تجاوز الحد)
                    val errorMsg = try {
                        val errorStr = BufferedReader(InputStreamReader(conn.errorStream)).readText()
                        JSONObject(errorStr).optString("message", "فشل النشر، حاول مجدداً")
                    } catch (e: Exception) { "فشل النشر، حدث خطأ بالسيرفر" }

                    withContext(Dispatchers.Main) {
                        layoutLoading.visibility = View.GONE
                        showCustomSnackbar(errorMsg, "#F44336")
                        enableControls()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    layoutLoading.visibility = View.GONE
                    showCustomSnackbar("خطأ في الاتصال بالسيرفر أو حجم الملف كبير", "#F44336")
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
