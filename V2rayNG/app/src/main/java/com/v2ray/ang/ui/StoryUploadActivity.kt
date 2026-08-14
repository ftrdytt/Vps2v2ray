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
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StoryUploadActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    
    private var hasSelectedImage = false
    private var hasSelectedVideo = false
    private var selectedVideoUri: Uri? = null
    private var selectedMusicId: String? = null

    // واجهات أساسية
    private lateinit var storyCaptureFrame: FrameLayout
    private lateinit var ivVideoPreview: ImageView
    private lateinit var drawingContainer: FrameLayout
    private lateinit var toolsOverlayLayout: View
    private lateinit var layoutLoading: FrameLayout

    // واجهة أدمن الموسيقى
    private lateinit var adminMusicUploadLayout: FrameLayout
    private lateinit var etAdminMusicTitle: EditText
    private var adminSelectedCoverBase64: String = ""
    private var adminSelectedAudioBase64: String = ""

    // محرر النصوص
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
    private var textBgMode = 0
    private var activeTextViewToEdit: TextView? = null

    // 🌟 رافعات الملفات (الاستوديو) 🌟
    private val pickMedia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val mimeType = contentResolver.getType(uri) ?: ""
            if (mimeType.startsWith("video/")) handleVideoSelection(uri) else handleImageSelection(uri)
        } else {
            // إذا المستخدم لم يختار شيئاً في البداية نخرجه
            if (!hasSelectedImage && !hasSelectedVideo) finish()
        }
    }

    private val pickAdminCover = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                adminSelectedCoverBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                showCustomSnackbar("تم اختيار غلاف الأغنية 🖼️", "#4CAF50")
            } catch (e: Exception) {
                showCustomSnackbar("فشل قراءة الصورة", "#F44336")
            }
        }
    }

    private val pickAdminAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration > 30500L) {
                    showCustomSnackbar("عذراً، يجب أن يكون المقطع 30 ثانية أو أقل 🚫", "#F44336")
                    return@registerForActivityResult
                }
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                adminSelectedAudioBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                showCustomSnackbar("تم اختيار المقطع الصوتي 🎵", "#4CAF50")
            } catch (e: Exception) {
                showCustomSnackbar("ملف غير مدعوم", "#F44336")
            } finally { retriever.release() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide() // شاشة كاملة
        setContentView(R.layout.activity_story_upload)

        storyCaptureFrame = findViewById(R.id.story_capture_frame)
        ivVideoPreview = findViewById(R.id.iv_story_video_preview)
        drawingContainer = findViewById(R.id.drawing_view_container)
        toolsOverlayLayout = findViewById(R.id.tools_overlay_layout)
        layoutLoading = findViewById(R.id.layout_loading)
        adminMusicUploadLayout = findViewById(R.id.admin_music_upload_layout)

        textEditorLayout = findViewById(R.id.text_editor_layout)
        etTextInput = findViewById(R.id.et_story_text_input)
        colorPickerContainer = findViewById(R.id.color_picker_container)
        btnToggleFont = findViewById(R.id.btn_toggle_font)
        btnToggleTextBg = findViewById(R.id.btn_toggle_text_bg)
        etAdminMusicTitle = findViewById(R.id.et_admin_music_title)

        // فتح الاستوديو فوراً عند الدخول
        if (savedInstanceState == null) openGallery()

        findViewById<ImageView>(R.id.btn_close).setOnClickListener { finish() }

        // أزرار الواجهة العائمة (انستغرام ستايل)
        findViewById<ImageView>(R.id.btn_add_text).setOnClickListener {
            activeTextViewToEdit = null
            etTextInput.setText("")
            openTextEditor()
        }

        findViewById<ImageView>(R.id.btn_add_music).setOnClickListener { showMusicBottomSheet() }
        
        findViewById<ImageView>(R.id.btn_add_sticker).setOnClickListener {
            showCustomSnackbar("ميزة الملصقات الإضافية قادمة قريباً!", "#2196F3")
        }

        findViewById<MaterialButton>(R.id.btn_publish_story).setOnClickListener {
            if (!hasSelectedImage && !hasSelectedVideo && drawingContainer.childCount == 0) {
                showCustomSnackbar("أضف صورة أو نص أو موسيقى أولاً", "#FF9800")
                return@setOnClickListener
            }
            if (!checkDailyQuota(isVideo = hasSelectedVideo)) return@setOnClickListener
            
            // إخفاء الأزرار قبل أخذ لقطة الشاشة
            toolsOverlayLayout.visibility = View.INVISIBLE
            uploadStory()
        }

        setupTextEditorTools()
        setupAdminMusicTools()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        pickMedia.launch(intent)
    }

    // =========================================================================
    // 🌟 نظام الصورة الحرة (Drag, Scale, Rotate) 🌟
    // =========================================================================
    private fun handleImageSelection(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return

            // إضافة الصورة كعنصر حر داخل الشاشة بدلاً من أن تكون خلفية ثابتة
            val ivSticker = ImageView(this).apply {
                setImageBitmap(originalBitmap)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.CENTER
                }
            }
            
            makeViewFreeTransformable(ivSticker)
            // نضع الصورة في الخلف (index 0) حتى تكون النصوص فوقها دائماً
            drawingContainer.addView(ivSticker, 0)
            
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
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            // حد الفيديو 30 ثانية
            if (durationMs > 30500L) { 
                showCustomSnackbar("عذراً، يجب أن لا تتجاوز مدة الفيديو 30 ثانية 🚫", "#F44336")
                return
            }

            val thumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (thumbnail != null) {
                ivVideoPreview.setImageBitmap(thumbnail)
                ivVideoPreview.visibility = View.VISIBLE
            }
            
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
    // 🌟 نظام الموسيقى والأدمن الخرافي (BottomSheet) 🌟
    // =========================================================================
    private fun showMusicBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141417"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1500)
            setPadding(30, 30, 30, 30)
        }

        val etSearchMusic = EditText(this).apply {
            hint = "🔍 بحث عن موسيقى..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply { setColor(Color.parseColor("#252529")); cornerRadius = 30f }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 30) }
        }
        container.addView(etSearchMusic)

        if (AuthManager.getRole(this) == "admin") {
            val btnAdminAdd = MaterialButton(this).apply {
                text = "➕ إضافة موسيقى للسيرفر (لأدمن فقط)"
                setBackgroundColor(Color.parseColor("#9C27B0"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 30) }
                setOnClickListener {
                    bottomSheet.dismiss()
                    adminMusicUploadLayout.visibility = View.VISIBLE
                }
            }
            container.addView(btnAdminAdd)
        }

        val scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val musicListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        scrollView.addView(musicListLayout)
        container.addView(scrollView)
        bottomSheet.setContentView(container)
        bottomSheet.show()

        // 🌟 جلب الأغاني من السيرفر (Trends) 🌟
        fun fetchAndPopulate(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("$BASE_API_URL/music/list?query=${Uri.encode(query)}").openConnection() as HttpURLConnection
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val resp = reader.readText()
                        reader.close()
                        val json = JSONObject(resp)
                        if (json.getBoolean("success")) {
                            val array = json.getJSONArray("music")
                            withContext(Dispatchers.Main) {
                                musicListLayout.removeAllViews()
                                for (i in 0 until array.length()) {
                                    val obj = array.getJSONObject(i)
                                    val id = obj.getString("id")
                                    val title = obj.getString("title")
                                    val artist = obj.optString("artist", "")
                                    val coverBase64 = obj.optString("coverBase64", "")
                                    
                                    val row = LinearLayout(this@StoryUploadActivity).apply {
                                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                        setPadding(20, 20, 20, 20)
                                        background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#1A1A1D")) }
                                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
                                        
                                        val ivCover = ImageView(this@StoryUploadActivity).apply {
                                            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
                                            if (coverBase64.isNotEmpty()) {
                                                val b = Base64.decode(coverBase64, Base64.DEFAULT)
                                                setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                                                scaleType = ImageView.ScaleType.CENTER_CROP
                                            } else {
                                                setImageResource(android.R.drawable.ic_media_play)
                                                setBackgroundColor(Color.parseColor("#333333"))
                                                setPadding(20, 20, 20, 20)
                                            }
                                        }

                                        val textLayout = LinearLayout(this@StoryUploadActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                                        textLayout.addView(TextView(this@StoryUploadActivity).apply { text = title; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD) })
                                        textLayout.addView(TextView(this@StoryUploadActivity).apply { text = artist; setTextColor(Color.GRAY); textSize = 12f })

                                        addView(ivCover)
                                        addView(textLayout)
                                        setOnClickListener {
                                            bottomSheet.dismiss()
                                            addMusicStickerToScreen(id, title, artist, coverBase64)
                                        }
                                    }
                                    musicListLayout.addView(row)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        
        fetchAndPopulate("")
        etSearchMusic.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fetchAndPopulate(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupAdminMusicTools() {
        findViewById<MaterialButton>(R.id.btn_admin_pick_cover).setOnClickListener {
            pickAdminCover.launch(Intent(Intent.ACTION_PICK).apply { type = "image/*" })
        }
        findViewById<MaterialButton>(R.id.btn_admin_pick_audio).setOnClickListener {
            pickAdminAudio.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" })
        }
        findViewById<MaterialButton>(R.id.btn_admin_close_music).setOnClickListener {
            adminMusicUploadLayout.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btn_admin_upload_music).setOnClickListener {
            val title = etAdminMusicTitle.text.toString().trim()
            if (title.isEmpty() || adminSelectedAudioBase64.isEmpty()) {
                showCustomSnackbar("يرجى كتابة الاسم واختيار الملف الصوتي", "#F44336")
                return@setOnClickListener
            }
            layoutLoading.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("$BASE_API_URL/music/add").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = JSONObject().apply {
                        put("adminId", AuthManager.getId(this@StoryUploadActivity))
                        put("title", title)
                        put("audioBase64", adminSelectedAudioBase64)
                        put("coverBase64", adminSelectedCoverBase64)
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode == 200) {
                        withContext(Dispatchers.Main) {
                            showCustomSnackbar("تم رفع الموسيقى بنجاح!", "#4CAF50")
                            adminMusicUploadLayout.visibility = View.GONE
                            layoutLoading.visibility = View.GONE
                            etAdminMusicTitle.setText("")
                            adminSelectedAudioBase64 = ""
                            adminSelectedCoverBase64 = ""
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun addMusicStickerToScreen(id: String, songName: String, artist: String, coverBase64: String) {
        val stickerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6FFFFFF")); cornerRadius = 50f }
            setPadding(30, 20, 40, 20)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
        }

        val ivArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0, 0, 20, 0) }
            if (coverBase64.isNotEmpty()) {
                val b = Base64.decode(coverBase64, Base64.DEFAULT)
                setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
            } else {
                setImageResource(android.R.drawable.ic_media_play)
                setColorFilter(Color.BLACK)
            }
        }

        val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textLayout.addView(TextView(this).apply { 
            text = songName; setTextColor(Color.BLACK); textSize = 14f; setTypeface(null, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.MARQUEE; isSingleLine = true; isSelected = true; marqueeRepeatLimit = -1
        })
        textLayout.addView(TextView(this).apply { text = artist; setTextColor(Color.DKGRAY); textSize = 11f })

        stickerLayout.addView(ivArt)
        stickerLayout.addView(textLayout)

        makeViewFreeTransformable(stickerLayout)
        drawingContainer.addView(stickerLayout)
        selectedMusicId = id
        showCustomSnackbar("تم إضافة الملصق. دبل كلك عليه لإخفائه!", "#4CAF50")
    }

    // =========================================================================
    // 🌟 أدوات محرر النصوص (Drag, Color, Font, Background) 🌟
    // =========================================================================
    private fun setupTextEditorTools() {
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

        btnToggleFont.setOnClickListener {
            currentTextStyle = when (currentTextStyle) { "CLASSIC" -> "NEON"; "NEON" -> "TYPEWRITER"; else -> "CLASSIC" }
            btnToggleFont.text = when (currentTextStyle) { "CLASSIC" -> "كلاسيكي"; "NEON" -> "نيون"; else -> "آلة كاتبة" }
            applyTextStyleToEditText()
        }

        btnToggleTextBg.setOnClickListener {
            textBgMode = (textBgMode + 1) % 3
            updateTextBackground()
        }

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
            0 -> etTextInput.background = null 
            1 -> etTextInput.background = GradientDrawable().apply { setColor(Color.parseColor("#88000000")); cornerRadius = 25f }
            2 -> etTextInput.background = GradientDrawable().apply { setColor(if (currentTextColor == Color.WHITE) Color.BLACK else Color.WHITE); cornerRadius = 25f }
        }
    }

    private fun applyTextStyleToEditText() {
        when (currentTextStyle) {
            "CLASSIC" -> { etTextInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD); etTextInput.setShadowLayer(5f, 2f, 2f, Color.BLACK) }
            "NEON" -> { etTextInput.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL); etTextInput.setShadowLayer(25f, 0f, 0f, currentTextColor) }
            "TYPEWRITER" -> { etTextInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); etTextInput.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
        }
    }

    private fun addDraggableTextSticker(text: String) {
        val tvSticker = TextView(this).apply {
            this.text = text; textSize = 38f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            var lastClickTime: Long = 0
            setOnClickListener {
                val clickTime = System.currentTimeMillis()
                if (clickTime - lastClickTime < 300) { activeTextViewToEdit = this; etTextInput.setText(this.text); openTextEditor() }
                lastClickTime = clickTime
            }
        }
        applyStyleToTextView(tvSticker)
        makeViewFreeTransformable(tvSticker)
        drawingContainer.addView(tvSticker)
    }

    private fun applyStyleToTextView(tv: TextView) {
        tv.setTextColor(currentTextColor)
        when (textBgMode) {
            0 -> tv.background = null
            1 -> tv.background = GradientDrawable().apply { setColor(Color.parseColor("#88000000")); cornerRadius = 25f }
            2 -> tv.background = GradientDrawable().apply { setColor(if (currentTextColor == Color.WHITE) Color.BLACK else Color.WHITE); cornerRadius = 25f }
        }
        tv.setPadding(30, 20, 30, 20)
        when (currentTextStyle) {
            "CLASSIC" -> { tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD); tv.setShadowLayer(5f, 2f, 2f, Color.BLACK) }
            "NEON" -> { tv.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL); tv.setShadowLayer(25f, 0f, 0f, currentTextColor); tv.setTextColor(Color.WHITE) }
            "TYPEWRITER" -> { tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
        }
    }

    // =========================================================================
    // 🌟 المحرك الجبار للتحكم الحر (Multi-Touch: Drag, Scale, Rotate) 🌟
    // =========================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun makeViewFreeTransformable(view: View) {
        var mActivePointerId = MotionEvent.INVALID_POINTER_ID
        var mLastTouchX = 0f; var mLastTouchY = 0f
        var mPosX = 0f; var mPosY = 0f

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                view.scaleX = max(0.2f, min(view.scaleX * scaleFactor, 10.0f))
                view.scaleY = max(0.2f, min(view.scaleY * scaleFactor, 10.0f))
                return true
            }
        })

        var initialRotation = 0f; var initialAngle = 0f

        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mLastTouchX = event.getX(event.actionIndex)
                    mLastTouchY = event.getY(event.actionIndex)
                    mActivePointerId = event.getPointerId(0)
                    mPosX = v.x; mPosY = v.y
                    v.bringToFront() // جلب للأمام
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        val dx = event.getX(1) - event.getX(0); val dy = event.getY(1) - event.getY(0)
                        initialAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        initialRotation = v.rotation
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(mActivePointerId)
                    if (pointerIndex != -1) {
                        if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                            mPosX += event.getX(pointerIndex) - mLastTouchX
                            mPosY += event.getY(pointerIndex) - mLastTouchY
                            v.x = mPosX; v.y = mPosY
                        }
                        if (event.pointerCount == 2) {
                            val dx = event.getX(1) - event.getX(0); val dy = event.getY(1) - event.getY(0)
                            v.rotation = initialRotation + (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() - initialAngle)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mActivePointerId = MotionEvent.INVALID_POINTER_ID; v.performClick() }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    if (event.getPointerId(pointerIndex) == mActivePointerId) {
                        val newIndex = if (pointerIndex == 0) 1 else 0
                        mLastTouchX = event.getX(newIndex); mLastTouchY = event.getY(newIndex)
                        mActivePointerId = event.getPointerId(newIndex)
                    }
                }
            }
            true
        }
    }

    // =========================================================================
    // 🌟 القيود وعملية الرفع الخرافية 🌟
    // =========================================================================
    private fun getHardwareId(): String {
        val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_id"
        return java.security.MessageDigest.getInstance("MD5").digest((devInfo + androidId).toByteArray()).joinToString("") { "%02x".format(it) }.take(16).uppercase()
    }

    private fun checkDailyQuota(isVideo: Boolean): Boolean {
        val prefs = getSharedPreferences("StoryQuotaPrefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (isVideo && prefs.getInt("vid_$today", 0) >= 2) { showCustomSnackbar("استهلكت الحد اليومي للفيديوهات 🚫", "#F44336"); return false }
        if (!isVideo && prefs.getInt("img_$today", 0) >= 25) { showCustomSnackbar("استهلكت الحد اليومي للصور 🚫", "#F44336"); return false }
        return true
    }

    private fun incrementDailyQuota(isVideo: Boolean) {
        val prefs = getSharedPreferences("StoryQuotaPrefs", Context.MODE_PRIVATE)
        val key = (if (isVideo) "vid_" else "img_") + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

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
                // التقاط الشاشة بالكامل بجميع ملصقاتها
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
                    put("musicId", selectedMusicId ?: "")
                    put("textContent", if (storyType == "video") finalCompositeBase64 else "") 
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    incrementDailyQuota(isVideo = hasSelectedVideo)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StoryUploadActivity, "تم النشر بنجاح 🚀", Toast.LENGTH_SHORT).show()
                        finish() 
                    }
                } else {
                    val errorMsg = try {
                        val reader = BufferedReader(InputStreamReader(conn.errorStream))
                        val errorStr = reader.readText()
                        reader.close()
                        JSONObject(errorStr).optString("message", "فشل النشر")
                    } catch (e: Exception) { "فشل النشر" }
                    withContext(Dispatchers.Main) { showCustomSnackbar(errorMsg, "#F44336"); restoreControls() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showCustomSnackbar("خطأ بالاتصال", "#F44336"); restoreControls() }
            }
        }
    }

    private fun restoreControls() {
        layoutLoading.visibility = View.GONE
        toolsOverlayLayout.visibility = View.VISIBLE
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
