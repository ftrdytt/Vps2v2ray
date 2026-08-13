package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StoryUploadActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    
    private var hasSelectedImage = false
    private var hasSelectedVideo = false
    private var selectedVideoUri: Uri? = null

    // 🌟 عناصر الواجهة (التصميم الجديد الشفاف) 🌟
    private lateinit var storyCaptureFrame: FrameLayout
    private lateinit var ivPreview: ImageView
    private lateinit var viewOverlay: View
    private lateinit var drawingContainer: FrameLayout
    private lateinit var layoutLoading: FrameLayout
    private lateinit var topToolsLayout: LinearLayout
    private lateinit var bottomToolsLayout: LinearLayout

    // 🌟 أدوات محرر النصوص (الشاشة الكاملة) 🌟
    private lateinit var textEditorLayout: FrameLayout
    private lateinit var etTextInput: EditText
    private lateinit var colorPickerContainer: LinearLayout
    private lateinit var btnToggleFont: TextView
    private lateinit var btnToggleTextBg: ImageView

    private val textStoryColors = arrayOf(
        "#FFFFFF", "#000000", "#FF5722", "#9C27B0", "#E91E63", "#009688", "#3F51B5", "#4CAF50", "#FF9800", "#FFEB3B"
    )

    private var currentTextColor = Color.WHITE
    private var currentTextStyle = "CLASSIC"
    private var textBgMode = 0 // 0: Transparent, 1: Semi-transparent, 2: Solid
    private var activeTextViewToEdit: TextView? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val mimeType = contentResolver.getType(uri) ?: ""
            if (mimeType.startsWith("video/")) handleVideoSelection(uri) else handleImageSelection(uri)
        } else {
            // إذا المستخدم فتح الاستوديو وتراجع بدون ما يختار، نطلعه من الشاشة
            if (!hasSelectedImage && !hasSelectedVideo) finish() 
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // إخفاء الـ Action Bar للشاشة الكاملة
        supportActionBar?.hide()
        setContentView(R.layout.activity_story_upload)

        storyCaptureFrame = findViewById(R.id.story_capture_frame)
        ivPreview = findViewById(R.id.iv_story_preview)
        viewOverlay = findViewById(R.id.view_overlay)
        drawingContainer = findViewById(R.id.drawing_view_container)
        layoutLoading = findViewById(R.id.layout_loading)
        topToolsLayout = findViewById(R.id.top_tools_layout)
        bottomToolsLayout = findViewById(R.id.bottom_tools_layout)

        textEditorLayout = findViewById(R.id.text_editor_layout)
        etTextInput = findViewById(R.id.et_story_text_input)
        colorPickerContainer = findViewById(R.id.color_picker_container)
        btnToggleFont = findViewById(R.id.btn_toggle_font)
        btnToggleTextBg = findViewById(R.id.btn_toggle_text_bg)

        // 🌟 فتح الاستوديو فوراً عند الدخول 🌟
        if (savedInstanceState == null) {
            openGallery()
        }

        findViewById<ImageView>(R.id.btn_close).setOnClickListener { finish() }

        // 🌟 زر إضافة نص حر 🌟
        findViewById<ImageView>(R.id.btn_add_text).setOnClickListener {
            activeTextViewToEdit = null
            etTextInput.setText("")
            openTextEditor()
        }

        // 🌟 إضافة ملصق الموسيقى 🌟
        findViewById<ImageView>(R.id.btn_add_music).setOnClickListener {
            addMusicSticker("موسيقى اشور", "صوت الإدارة")
        }

        // 🌟 زر المشاركة (النشر) 🌟
        findViewById<MaterialButton>(R.id.btn_publish_story).setOnClickListener {
            if (!hasSelectedImage && !hasSelectedVideo && drawingContainer.childCount == 0) {
                showCustomSnackbar("يجب إضافة صورة أو نص أو موسيقى على الأقل", "#FF9800")
                return@setOnClickListener
            }
            if (!checkDailyQuota(isVideo = hasSelectedVideo)) return@setOnClickListener
            
            // إخفاء الأزرار الشفافة حتى لا تظهر في الصورة الملتقطة
            topToolsLayout.visibility = View.INVISIBLE
            bottomToolsLayout.visibility = View.INVISIBLE
            
            uploadStory()
        }

        setupTextEditorTools()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        pickMedia.launch(intent)
    }

    // =========================================================================
    // 🌟 أدوات محرر النصوص (Drag, Color, Font, Background) 🌟
    // =========================================================================
    private fun setupTextEditorTools() {
        // شريط الألوان
        for (colorHex in textStoryColors) {
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { setMargins(15, 0, 15, 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(colorHex))
                    setStroke(4, Color.WHITE)
                }
                setOnClickListener {
                    currentTextColor = Color.parseColor(colorHex)
                    etTextInput.setTextColor(currentTextColor)
                    updateTextBackground()
                }
            }
            colorPickerContainer.addView(colorView)
        }

        // تغيير الخط
        btnToggleFont.setOnClickListener {
            currentTextStyle = when (currentTextStyle) {
                "CLASSIC" -> "NEON"
                "NEON" -> "TYPEWRITER"
                else -> "CLASSIC"
            }
            btnToggleFont.text = when (currentTextStyle) { "CLASSIC" -> "كلاسيكي"; "NEON" -> "نيون"; else -> "آلة كاتبة" }
            applyTextStyleToEditText()
        }

        // تغيير خلفية النص
        btnToggleTextBg.setOnClickListener {
            textBgMode = (textBgMode + 1) % 3
            updateTextBackground()
        }

        // زر (تم) عند الانتهاء من الكتابة
        findViewById<MaterialButton>(R.id.btn_done_text).setOnClickListener {
            val text = etTextInput.text.toString().trim()
            if (text.isNotEmpty()) {
                if (activeTextViewToEdit == null) {
                    addDraggableTextSticker(text)
                } else {
                    activeTextViewToEdit?.text = text
                    applyStyleToTextView(activeTextViewToEdit!!)
                }
            } else if (activeTextViewToEdit != null) {
                drawingContainer.removeView(activeTextViewToEdit)
            }
            closeTextEditor()
        }
    }

    private fun openTextEditor() {
        textEditorLayout.visibility = View.VISIBLE
        etTextInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etTextInput, InputMethodManager.SHOW_IMPLICIT)
        applyTextStyleToEditText()
        updateTextBackground()
    }

    private fun closeTextEditor() {
        textEditorLayout.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etTextInput.windowToken, 0)
    }

    private fun updateTextBackground() {
        when (textBgMode) {
            0 -> etTextInput.background = null // شفاف
            1 -> etTextInput.background = GradientDrawable().apply {
                setColor(Color.parseColor("#88000000"))
                cornerRadius = 20f
            } // أسود شفاف
            2 -> etTextInput.background = GradientDrawable().apply {
                setColor(if (currentTextColor == Color.WHITE) Color.BLACK else Color.WHITE)
                cornerRadius = 20f
            } // لون عكسي سادة
        }
        etTextInput.setPadding(30, 20, 30, 20)
    }

    private fun applyTextStyleToEditText() {
        when (currentTextStyle) {
            "CLASSIC" -> {
                etTextInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                etTextInput.setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            "NEON" -> {
                etTextInput.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL)
                etTextInput.setShadowLayer(25f, 0f, 0f, currentTextColor)
            }
            "TYPEWRITER" -> {
                etTextInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                etTextInput.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }

    // =========================================================================
    // 🌟 إضافة العناصر القابلة للسحب والتكبير (النصوص والموسيقى) 🌟
    // =========================================================================
    private fun addDraggableTextSticker(text: String) {
        val tvSticker = TextView(this).apply {
            this.text = text
            textSize = 32f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            setOnClickListener {
                activeTextViewToEdit = this
                etTextInput.setText(text)
                openTextEditor()
            }
        }
        applyStyleToTextView(tvSticker)
        makeViewDraggable(tvSticker)
        drawingContainer.addView(tvSticker)
    }

    private fun applyStyleToTextView(tv: TextView) {
        tv.setTextColor(currentTextColor)
        when (textBgMode) {
            0 -> tv.background = null
            1 -> tv.background = GradientDrawable().apply { setColor(Color.parseColor("#88000000")); cornerRadius = 20f }
            2 -> tv.background = GradientDrawable().apply { setColor(if (currentTextColor == Color.WHITE) Color.BLACK else Color.WHITE); cornerRadius = 20f }
        }
        tv.setPadding(30, 20, 30, 20)

        when (currentTextStyle) {
            "CLASSIC" -> { tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD); tv.setShadowLayer(5f, 2f, 2f, Color.BLACK) }
            "NEON" -> { tv.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL); tv.setShadowLayer(25f, 0f, 0f, currentTextColor); tv.setTextColor(Color.WHITE) }
            "TYPEWRITER" -> { tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
        }
    }

    private fun addMusicSticker(songName: String, artist: String) {
        val musicLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000")) 
                cornerRadius = 40f
            }
            setPadding(20, 15, 30, 15)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(0, 200, 0, 0)
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(0, 0, 15, 0) }
        }

        val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textLayout.addView(TextView(this).apply { text = songName; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD) })
        textLayout.addView(TextView(this).apply { text = artist; setTextColor(Color.LTGRAY); textSize = 11f })

        musicLayout.addView(icon)
        musicLayout.addView(textLayout)

        makeViewDraggable(musicLayout)
        drawingContainer.addView(musicLayout)
        showCustomSnackbar("تم إضافة الملصق الموسيقي. يمكنك سحبه أو تكبيره!", "#4CAF50")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeViewDraggable(view: View) {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = view.scaleX * detector.scaleFactor
                view.scaleX = Math.max(0.3f, min(scale, 5.0f))
                view.scaleY = Math.max(0.3f, min(scale, 5.0f))
                return true
            }
        })

        var dX = 0f; var dY = 0f
        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY }
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
    // 🌟 معالجة واختيار الميديا (الفيديو والصور) 🌟
    // =========================================================================
    private fun handleImageSelection(uri: Uri) {
        try {
            // حل مشكلة البناء بالاعتماد على BitmapFactory
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) {
                showCustomSnackbar("فشل في قراءة الصورة", "#F44336")
                return
            }

            val maxImageSize = 1080f
            val ratio = min(1f, min(maxImageSize / originalBitmap.width, maxImageSize / originalBitmap.height))
            val width = (ratio * originalBitmap.width).roundToInt()
            val height = (ratio * originalBitmap.height).roundToInt()
            
            val scaled = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
            ivPreview.setImageBitmap(scaled)
            ivPreview.imageTintList = null
            
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

            if (durationMs > 60500L) { 
                showCustomSnackbar("عذراً، يجب أن لا تتجاوز مدة الفيديو دقيقة واحدة", "#F44336")
                return
            }

            val thumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (thumbnail != null) ivPreview.setImageBitmap(thumbnail)
            
            ivPreview.imageTintList = null
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
    // 🌟 نظام البصمة (Hardware ID) وقيود النشر (24 ساعة) 🌟
    // =========================================================================
    private fun getHardwareId(): String {
        val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_id"
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest((devInfo + androidId).toByteArray()).joinToString("") { "%02x".format(it) }.take(16).uppercase()
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
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    // =========================================================================
    // 🌟 عملية الرفع والتشفير 🌟
    // =========================================================================
    private fun captureStoryFrame(view: View): String {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos) 
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun convertVideoToBase64(uri: Uri): String? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    private fun uploadStory() {
        layoutLoading.visibility = View.VISIBLE

        val userId = AuthManager.getId(this)
        val hardwareId = getHardwareId() 
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 🌟 التقاط الإطار بالكامل (صورة + ملصقات + نصوص) كصورة واحدة! 🌟
                val finalCompositeBase64 = captureStoryFrame(storyCaptureFrame)
                val mediaBase64: String
                val storyType: String

                if (hasSelectedVideo && selectedVideoUri != null) {
                    val encodedVideo = convertVideoToBase64(selectedVideoUri!!)
                    if (encodedVideo == null) {
                        withContext(Dispatchers.Main) { showCustomSnackbar("حجم الفيديو كبير جداً", "#F44336"); restoreControls() }
                        return@launch
                    }
                    mediaBase64 = encodedVideo
                    storyType = "video"
                } else {
                    mediaBase64 = finalCompositeBase64
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
                    // إذا كان فيديو، نرسل إطار الملصقات كصورة شفافة تتركب فوق الفيديو بالعارض (Viewer)
                    put("textContent", if (storyType == "video") finalCompositeBase64 else "") 
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    incrementDailyQuota(isVideo = hasSelectedVideo)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StoryUploadActivity, "تم نشر القصة بنجاح ✅", Toast.LENGTH_SHORT).show()
                        finish() 
                    }
                } else {
                    // 🌟 قراءة الخطأ بطريقة آمنة لتجنب أخطاء البناء 🌟
                    val errorMsg = try {
                        val reader = BufferedReader(InputStreamReader(conn.errorStream))
                        val errorStr = reader.readText()
                        reader.close()
                        JSONObject(errorStr).optString("message", "فشل النشر")
                    } catch (e: Exception) { "فشل النشر" }

                    withContext(Dispatchers.Main) { showCustomSnackbar(errorMsg, "#F44336"); restoreControls() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showCustomSnackbar("خطأ بالاتصال أو حجم الملف كبير", "#F44336"); restoreControls() }
            }
        }
    }

    private fun restoreControls() {
        layoutLoading.visibility = View.GONE
        topToolsLayout.visibility = View.VISIBLE
        bottomToolsLayout.visibility = View.VISIBLE
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
            setTypeface(null, Typeface.BOLD)
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
