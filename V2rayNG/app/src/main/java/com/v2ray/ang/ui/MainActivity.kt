package com.v2ray.ang.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.handler.NetworkTime
import com.v2ray.ang.handler.V2rayCrypt
import com.v2ray.ang.handler.CloudflareAPI
import com.v2ray.ang.handler.AuthManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener, MainAdapterListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    
    private var pingJob: Job? = null
    private var trafficJob: Job? = null
    private var speedTestJob: Job? = null
    private var resetSpeedButtonJob: Job? = null 
    private var liveUpdateJob: Job? = null 
    
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var isFirstTrafficRead: Boolean = true
    
    private var vpnStartTime: Long = 0L // وقت تشغيل الـ VPN للعقاب (Kill Switch)

    companion object { var lastReportedState: Boolean? = null }

    private var screenWidth = 0

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) startV2Ray() }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) restartV2Ray()
        if (SettingsChangeManager.consumeSetupGroupTab()) setupGroupTab()
    }
    private val openEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readEncryptedContentFromUri(uri) }

    fun showLoadingDialog() { showLoading() }
    fun hideLoadingDialog() { hideLoading() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        if (AuthManager.hasLoggedOut(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (!AuthManager.isLoggedIn(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = URL("https://vpn-license.rauter505.workers.dev/auth/init")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(this@MainActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        setContentView(binding.root)
        handleIntent(intent)

        lifecycleScope.launch(Dispatchers.IO) { NetworkTime.syncTime(this@MainActivity) }

        // بدء فحص التحديثات التلقائي في الخلفية عند فتح التطبيق
        startBackgroundUpdateCheck()

        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        
        // إعداد 5 شاشات (الإعدادات، التحديثات، الملف الشخصي، الملفات، الرئيسية)
        binding.root.findViewById<View>(R.id.settings_wrapper)?.layoutParams?.width = screenWidth
        
        val updatesWrapper = FrameLayout(this).apply { 
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
        }
        
        val profileWrapper = FrameLayout(this).apply { 
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
        }
        
        val scrollContainer = binding.root.findViewById<LinearLayout>(R.id.settings_wrapper).parent as LinearLayout
        scrollContainer.addView(updatesWrapper, 1) // إضافة التحديثات بعد الإعدادات
        scrollContainer.addView(profileWrapper, 2) // إضافة الملف الشخصي بعد التحديثات
        
        binding.homeContentContainer.layoutParams.width = screenWidth
        binding.greenScreenContainer.layoutParams.width = screenWidth

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_fragment_container, SettingsActivity.SettingsFragment())
            .replace(updatesWrapper.id, UpdatesFragment())
            .replace(profileWrapper.id, ProfileFragment())
            .commit()

        binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)?.setOnClickListener { handleFabAction() }
        binding.root.findViewById<MaterialButton>(R.id.btn_speed_test)?.let { it.text = "قياس سرعة الإنترنت"; it.setOnClickListener { runSpeedTest() } }
        binding.root.findViewById<CardView>(R.id.card_traffic_meter)?.setOnClickListener { showTrafficDetailsDialog() }
        updateTrafficDisplay()

        val bottomNav = binding.root.findViewById<BottomNavigationView>(R.id.bottom_nav_view)
        
        // نظام السحب ليغطي 5 صفحات (من 0 إلى 4)
        binding.mainScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val scrollX = binding.mainScrollView.scrollX
                val page = if (screenWidth > 0) ((scrollX + (screenWidth / 2)) / screenWidth).coerceIn(0, 4) else 0
                binding.mainScrollView.post { binding.mainScrollView.smoothScrollTo(page * screenWidth, 0) }
                when (page) { 
                    0 -> bottomNav?.selectedItemId = R.id.nav_settings
                    1 -> bottomNav?.selectedItemId = R.id.nav_updates
                    2 -> bottomNav?.selectedItemId = R.id.nav_profile
                    3 -> bottomNav?.selectedItemId = R.id.nav_servers
                    4 -> bottomNav?.selectedItemId = R.id.nav_home 
                }
                return@setOnTouchListener true
            }
            false
        }

        // ربط أزرار الأسفل بأماكن السحب
        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> { binding.mainScrollView.smoothScrollTo(0, 0); true }
                R.id.nav_updates -> { binding.mainScrollView.smoothScrollTo(screenWidth, 0); true }
                R.id.nav_profile -> { binding.mainScrollView.smoothScrollTo(screenWidth * 2, 0); true }
                R.id.nav_servers -> { binding.mainScrollView.smoothScrollTo(screenWidth * 3, 0); true }
                R.id.nav_home -> { binding.mainScrollView.smoothScrollTo(screenWidth * 4, 0); true }
                else -> false
            }
        }
        bottomNav?.selectedItemId = R.id.nav_home
        binding.mainScrollView.post { binding.mainScrollView.scrollTo(screenWidth * 4, 0) }

        setupToolbar(binding.toolbar, false, "اشور لود")

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter; binding.viewPager.isUserInputEnabled = true

        val toggle = ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        toggle.isDrawerIndicatorEnabled = false; binding.drawerLayout.addDrawerListener(toggle); toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) binding.drawerLayout.closeDrawer(GravityCompat.START)
                else {
                    if (binding.mainScrollView.scrollX != screenWidth * 4) { binding.mainScrollView.smoothScrollTo(screenWidth * 4, 0); bottomNav?.selectedItemId = R.id.nav_home } 
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }; binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        setupGroupTab(); setupViewModel(); mainViewModel.reloadServerList()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    // ====================================================================
    // ================== نظام التحديثات التلقائي (OTA) ===================
    // ====================================================================

    private fun startBackgroundUpdateCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // الفحص يحدث بعد ثانيتين من فتح التطبيق لضمان استقرار النت
                delay(2000)
                val url = URL("https://vpn-license.rauter505.workers.dev/app/update/check")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    val serverVersion = obj.getInt("version")
                    val totalChunks = obj.optInt("totalChunks", 0)
                    
                    // إذا كان السيرفر يحمل إصدار أعلى من إصدار التطبيق الحالي، نبدأ التنزيل
                    if (serverVersion > com.v2ray.ang.BuildConfig.VERSION_CODE && totalChunks > 0) {
                        UpdateManager.isUpdatePending = true // تفعيل العقاب في حال التأخير
                        downloadUpdateWithNotification(serverVersion, totalChunks)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun downloadUpdateWithNotification(serverVersion: Int, totalChunks: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "update_channel"
        
        // إنشاء قناة الإشعارات للأندرويد 8 وما فوق
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "تحديثات التطبيق", android.app.NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("تحديث جديد إجباري 🚀")
            .setContentText("جاري تنزيل التحديث... 0%")
            .setSmallIcon(R.drawable.ic_popup_sync) // أيقونة التحديث
            .setOngoing(true) // لا يمكن للمستخدم مسح الإشعار
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)

        notificationManager.notify(999, builder.build())

        try {
            // حفظ التحديث في مجلد الـ Cache لكي يسهل تثبيته
            val updateFile = java.io.File(externalCacheDir, "Ashor_Update_v$serverVersion.apk")
            val fos = java.io.FileOutputStream(updateFile)
            
            for (i in 0 until totalChunks) {
                val chunkUrl = URL("https://vpn-license.rauter505.workers.dev/app/update/download_chunk?v=$serverVersion&i=$i")
                val chunkConn = chunkUrl.openConnection() as HttpURLConnection
                chunkConn.connectTimeout = 30000
                chunkConn.readTimeout = 60000
                
                if (chunkConn.responseCode == 200) {
                    val chunkResp = BufferedReader(InputStreamReader(chunkConn.inputStream)).readText()
                    val chunkObj = JSONObject(chunkResp)
                    val base64Data = chunkObj.getString("chunkData")
                    val chunkBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                    fos.write(chunkBytes)
                    
                    // تحديث نسبة الإشعار (في البردة)
                    val progress = ((i + 1f) / totalChunks * 100).toInt()
                    builder.setProgress(100, progress, false)
                    builder.setContentText("جاري تنزيل التحديث... $progress%")
                    notificationManager.notify(999, builder.build())
                } else {
                    fos.close()
                    throw Exception("Download failed")
                }
            }
            fos.flush(); fos.close()

            // تغيير الإشعار للاكتمال
            builder.setContentText("تم التنزيل، جاري التثبيت...")
            builder.setProgress(0, 0, false)
            notificationManager.notify(999, builder.build())

            // تثبيت التطبيق إجبارياً
            forceInstallApk(updateFile)

        } catch (e: Exception) {
            builder.setContentText("فشل تنزيل التحديث، يرجى المحاولة لاحقاً.")
            builder.setProgress(0, 0, false)
            builder.setOngoing(false)
            notificationManager.notify(999, builder.build())
        }
    }

    private fun forceInstallApk(apkFile: java.io.File) {
        withContext(Dispatchers.Main) {
            try {
                // استخدام FileProvider لفتح الـ APK بشكل آمن
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.cache", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            } catch (e: Exception) { 
                Toast.makeText(this@MainActivity, "فشل بدء التثبيت", Toast.LENGTH_LONG).show() 
            }
        }
    }
    
    // ====================================================================

    private fun startLiveUpdates() {
        liveUpdateJob?.cancel()
        liveUpdateJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
                var needsRefresh = false
                for (guid in guids) {
                    val licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid)
                    if (licenseId.isNotEmpty() && licenseId != "LEGACY" && (V2rayCrypt.isProtected(this@MainActivity, guid) || V2rayCrypt.isAdmin(this@MainActivity, guid))) {
                        val cloudData = CloudflareAPI.checkLiveConfig(licenseId)
                        if (cloudData.first >= 0L) {
                            val currentCount = V2rayCrypt.getActiveCount(this@MainActivity, guid)
                            if (currentCount != cloudData.third) {
                                V2rayCrypt.saveActiveCount(this@MainActivity, guid, cloudData.third)
                                needsRefresh = true
                            }
                        }
                    }
                }
                if (needsRefresh) {
                    withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                }
                delay(4000) 
            }
        }
    }

    fun forceManualSync() {
        showLoadingDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
            for (guid in guids) {
                val licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid)
                if (licenseId.isNotEmpty() && licenseId != "LEGACY") {
                    val cloudData = CloudflareAPI.checkLiveConfig(licenseId)
                    if (cloudData.first >= 0L) {
                        V2rayCrypt.saveActiveCount(this@MainActivity, guid, cloudData.third)
                        V2rayCrypt.saveExpiryTime(this@MainActivity, guid, cloudData.first)
                    }
                }
            }
            withContext(Dispatchers.Main) { 
                mainViewModel.reloadServerList()
                hideLoadingDialog()
                toastSuccess("تم تحديث البيانات بنجاح!")
            }
        }
    }

    private fun showAddBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141417")); setPadding(0, 0, 0, 40) }
        scrollView.addView(container)

        container.addView(TextView(this).apply { text = "إضافة تكوين جديد"; textSize = 20f; setTextColor(Color.parseColor("#FF9800")); setPadding(40, 40, 40, 20); textAlignment = View.TEXT_ALIGNMENT_CENTER; setTypeface(null, android.graphics.Typeface.BOLD) })
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { setMargins(40, 0, 40, 20) }; setBackgroundColor(Color.parseColor("#33FFFFFF")) })

        fun addSectionTitle(textStr: String) { container.addView(TextView(this).apply { text = textStr; textSize = 14f; setTextColor(Color.parseColor("#80FFFFFF")); setPadding(50, 30, 50, 10); setTypeface(null, android.graphics.Typeface.BOLD) }) }
        fun createOptionButton(textStr: String, iconRes: Int, onClick: () -> Unit) {
            val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setPadding(50, 30, 50, 30); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true; val outValue = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true); setBackgroundResource(outValue.resourceId); setOnClickListener { onClick(); bottomSheetDialog.dismiss() } }
            layout.addView(ImageView(this).apply { setImageResource(iconRes); setColorFilter(Color.parseColor("#FF9800")); layoutParams = LinearLayout.LayoutParams(56, 56) })
            layout.addView(TextView(this).apply { text = textStr; textSize = 16f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 40 } })
            container.addView(layout)
        }

        addSectionTitle("الاستيراد السريع")
        createOptionButton("استيراد من الحافظة (عادي)", android.R.drawable.ic_menu_add) { if (importClipboard()) bottomSheetDialog.dismiss() }
        createOptionButton("استيراد من حافظة مشفرة", android.R.drawable.ic_lock_idle_lock) { if (importClipboardEncrypted()) bottomSheetDialog.dismiss() }
        createOptionButton("إضافة ملف مشفر (.ashor)", android.R.drawable.ic_menu_save) { importEncryptedFile() }

        addSectionTitle("الإضافة اليدوية")
        createOptionButton("VLESS", android.R.drawable.ic_menu_edit) { importManually(EConfigType.VLESS.value) }
        createOptionButton("VMess", android.R.drawable.ic_menu_edit) { importManually(EConfigType.VMESS.value) }
        createOptionButton("Trojan", android.R.drawable.ic_menu_edit) { importManually(EConfigType.TROJAN.value) }
        
        bottomSheetDialog.setContentView(scrollView); bottomSheetDialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); bottomSheetDialog.show()
    }

    fun showExtendLicenseDialog(guid: String) {
        val licenseId = V2rayCrypt.getLicenseId(this, guid)
        if (licenseId.isEmpty() || licenseId == "LEGACY") { toastError("هذا الكود قديم ولا يدعم التمديد المركزي."); return }

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        layout.addView(TextView(this).apply { text = "لوحة التحكم: تعديل للجميع"; textSize = 17f; setTextColor(Color.parseColor("#4CAF50")); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 30); gravity = Gravity.CENTER })
        val monthsInput = EditText(this).apply { hint = "عدد الأشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }; layout.addView(monthsInput)
        val daysInput = EditText(this).apply { hint = "عدد الأيام"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }; layout.addView(daysInput)
        val hoursInput = EditText(this).apply { hint = "عدد الساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }; layout.addView(hoursInput)

        AlertDialog.Builder(this).setView(layout).setPositiveButton("تمديد للجميع") { dialog, _ ->
            val totalDurationMs = ((monthsInput.text.toString().toLongOrNull() ?: 0L) * 30L * 24L * 60L * 60L * 1000L) + ((daysInput.text.toString().toLongOrNull() ?: 0L) * 24L * 60L * 60L * 1000L) + ((hoursInput.text.toString().toLongOrNull() ?: 0L) * 60L * 60L * 1000L)
            if (totalDurationMs > 0L) {
                val newExpiryTimeMs = NetworkTime.currentTimeMillis(this) + totalDurationMs
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = CloudflareAPI.updateExpiry(licenseId, newExpiryTimeMs)
                    withContext(Dispatchers.Main) {
                        hideLoadingDialog()
                        if (success) {
                            V2rayCrypt.getAllProtectedGuids(this@MainActivity).forEach { pGuid -> if (V2rayCrypt.getLicenseId(this@MainActivity, pGuid) == licenseId) V2rayCrypt.saveExpiryTime(this@MainActivity, pGuid, newExpiryTimeMs) }
                            toastSuccess("تم التمديد بنجاح!"); mainViewModel.reloadServerList() 
                        } else toastError("فشل الاتصال")
                    }
                }
            } else toastError("الرجاء إدخال وقت صحيح")
            dialog.dismiss()
        }.setNeutralButton("إيقاف الكود فوراً") { dialog, _ ->
            showLoadingDialog()
            lifecycleScope.launch(Dispatchers.IO) {
                val expiredTime = NetworkTime.currentTimeMillis(this@MainActivity) - 100000L
                val success = CloudflareAPI.updateExpiry(licenseId, expiredTime)
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    if (success) {
                        V2rayCrypt.getAllProtectedGuids(this@MainActivity).forEach { pGuid -> if (V2rayCrypt.getLicenseId(this@MainActivity, pGuid) == licenseId) V2rayCrypt.saveExpiryTime(this@MainActivity, pGuid, expiredTime) }
                        toastSuccess("تم إيقاف الكود عن الجميع!"); mainViewModel.reloadServerList()
                    } else toastError("فشل الاتصال.")
                }
            }
        }.setNegativeButton("إلغاء") { dialog, _ -> dialog.cancel() }.show()
    }

    fun replaceAndSyncConfigFromClipboard(guid: String) {
        val licenseId = V2rayCrypt.getLicenseId(this, guid)
        if (licenseId.isEmpty() || licenseId == "LEGACY") { toastError("هذا الكود لا يدعم التحديث السحابي."); return }

        val newConf = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (newConf.isEmpty() || !newConf.contains("://")) { toastError("الحافظة لا تحتوي على كود صالح."); return }

        val currentExpiry = V2rayCrypt.getExpiryTime(this, guid)
        showLoadingDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = CloudflareAPI.createOrUpdateSubscriber(licenseId, currentExpiry, newConf)
            withContext(Dispatchers.Main) {
                hideLoadingDialog()
                if (success) {
                    val beforeGuids = MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()
                    val (count, _) = AngConfigManager.importBatchConfig(newConf, mainViewModel.subscriptionId, true)
                    if (count > 0) {
                        val newGuid = ((MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()) - beforeGuids).firstOrNull()
                        if (newGuid != null) {
                            V2rayCrypt.addAdminGuid(this@MainActivity, newGuid); V2rayCrypt.addProtectedGuids(this@MainActivity, setOf(newGuid)); V2rayCrypt.saveLicenseId(this@MainActivity, newGuid, licenseId); V2rayCrypt.saveExpiryTime(this@MainActivity, newGuid, currentExpiry); V2rayCrypt.saveLastConfigHash(this@MainActivity, newGuid, Base64.encodeToString(newConf.toByteArray(), Base64.NO_WRAP).hashCode())
                            mainViewModel.removeServer(guid); MmkvManager.setSelectServer(newGuid); toastSuccess("تم تحديث السيرفر سحابياً!"); mainViewModel.reloadServerList()
                        }
                    } else toastError("الكود في الحافظة غير صالح.")
                } else toastError("فشل رفع الكود.")
            }
        }
    }

    fun openSubscribersPanel(parentGuid: String) { startActivity(Intent(this, SubscribersActivity::class.java).putExtra("parentGuid", parentGuid)) }

    private fun runSpeedTest() {
        val speedGauge = binding.root.findViewById<SpeedGaugeView>(R.id.gauge_speed); val btnTest = binding.root.findViewById<MaterialButton>(R.id.btn_speed_test)
        if (speedTestJob?.isActive == true) return
        resetSpeedButtonJob?.cancel(); btnTest?.isEnabled = false; btnTest?.text = "جاري القياس..."; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800")) 

        speedTestJob = lifecycleScope.launch(Dispatchers.IO) {
            var finalSpeed = -1f; val url = URL("https://speed.cloudflare.com/__down?bytes=20000000")
            if (mainViewModel.isRunning.value == true && finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10809))) } catch (e: Exception) {}
            if (mainViewModel.isRunning.value == true && finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))) } catch (e: Exception) {}
            if (finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy.NO_PROXY) } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                speedGauge?.setSpeed(0f); btnTest?.isEnabled = true
                if (finalSpeed >= 0f) {
                    btnTest?.text = "السرعة: ${String.format(Locale.US, "%.1f", finalSpeed)} Mbps"; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    resetSpeedButtonJob = lifecycleScope.launch { delay(10000); btnTest?.text = "قياس سرعة الإنترنت"; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3")) }
                } else { btnTest?.text = "قياس سرعة الإنترنت"; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3")); toast("تعذر الفحص.") }
            }
        }
    }

    private suspend fun attemptDownload(url: URL, speedGauge: SpeedGaugeView?, proxy: Proxy): Float {
        val connection = url.openConnection(proxy) as HttpURLConnection
        connection.requestMethod = "GET"; connection.setRequestProperty("User-Agent", "Mozilla/5.0"); connection.connectTimeout = 2000; connection.readTimeout = 5000; connection.connect() 
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return -1f
        
        val inputStream = connection.inputStream; val buffer = ByteArray(16384); var totalBytesRead = 0L; var bytesRead: Int; val startTime = System.currentTimeMillis(); var lastUpdateTime = startTime; var lastBytesRead = 0L; var maxSpeed = 0f
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            totalBytesRead += bytesRead; val currentTime = System.currentTimeMillis(); val timeDiff = currentTime - lastUpdateTime
            if (timeDiff >= 300) { 
                val speedMbps = ((totalBytesRead - lastBytesRead) / (timeDiff / 1000f) * 8) / 1_000_000f 
                if (speedMbps > maxSpeed) maxSpeed = speedMbps
                withContext(Dispatchers.Main) { speedGauge?.setSpeed(speedMbps) }
                lastUpdateTime = currentTime; lastBytesRead = totalBytesRead
            }
            if (currentTime - startTime > 8000) break 
        }
        inputStream.close()
        return if (maxSpeed > 0f) maxSpeed else -1f
    }

    private fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0.00 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0; if (kb < 1024) return String.format(Locale.ENGLISH, "%.2f KB", kb)
        val mb = kb / 1024.0; if (mb < 1024) return String.format(Locale.ENGLISH, "%.2f MB", mb)
        val gb = mb / 1024.0; return String.format(Locale.ENGLISH, "%.2f GB", gb)
    }

    private fun updateTrafficDisplay() { binding.root.findViewById<TextView>(R.id.tv_total_traffic)?.text = formatTraffic(getSharedPreferences("traffic_stats", Context.MODE_PRIVATE).let { it.getLong("rx", 0L) + it.getLong("tx", 0L) }) }

    private fun startTrafficMonitor() {
        isFirstTrafficRead = true; trafficJob?.cancel()
        trafficJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
                    if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                        if (isFirstTrafficRead) { lastRxBytes = rx; lastTxBytes = tx; isFirstTrafficRead = false } 
                        else {
                            val diffRx = rx - lastRxBytes; val diffTx = tx - lastTxBytes
                            if (diffRx > 0 || diffTx > 0) {
                                val prefs = getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
                                prefs.edit().putLong("rx", prefs.getLong("rx", 0L) + diffRx).putLong("tx", prefs.getLong("tx", 0L) + diffTx).apply()
                                lastRxBytes = rx; lastTxBytes = tx
                                withContext(Dispatchers.Main) { updateTrafficDisplay() }
                            }
                        }
                    }
                } catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stopTrafficMonitor() { trafficJob?.cancel(); isFirstTrafficRead = true }

    private fun showTrafficDetailsDialog() {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(R.layout.dialog_traffic_stats); window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val prefs = getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
        fun refreshDialogData() { dialog.findViewById<TextView>(R.id.tv_download_stat).text = formatTraffic(prefs.getLong("rx", 0L)); dialog.findViewById<TextView>(R.id.tv_upload_stat).text = formatTraffic(prefs.getLong("tx", 0L)) }
        refreshDialogData()
        dialog.findViewById<ImageView>(R.id.btn_close_dialog).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<MaterialButton>(R.id.btn_reset_stats).setOnClickListener {
            prefs.edit().putLong("rx", 0L).putLong("tx", 0L).apply()
            if (mainViewModel.isRunning.value == true) { lastRxBytes = TrafficStats.getTotalRxBytes(); lastTxBytes = TrafficStats.getTotalTxBytes() }
            refreshDialogData(); updateTrafficDisplay(); toast("تم التصفير بنجاح!")
        }
        dialog.show()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) { if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { readEncryptedContentFromUri(it) } }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning -> applyRunningState(false, isRunning) }
        mainViewModel.startListenBroadcast(); mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this); groupPagerAdapter.update(groups)
        tabMediator?.detach(); tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position -> groupPagerAdapter.groups.getOrNull(position)?.let { tab.text = it.remarks; tab.tag = it.id } }.also { it.attach() }
        binding.viewPager.setCurrentItem(groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1), false)
        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)
        if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this)
        else if (SettingsManager.isVpnMode()) { val intent = VpnService.prepare(this); if (intent == null) startV2Ray() else requestVpnPermission.launch(intent) } 
        else startV2Ray()
    }

    private fun handleLayoutTestClick() { if (mainViewModel.isRunning.value == true) { setTestState(getString(R.string.connection_test_testing)); mainViewModel.testCurrentServerRealPing() } }

    private fun startV2Ray() { if (MmkvManager.getSelectServer().isNullOrEmpty()) { toast(R.string.title_file_chooser); return }; V2RayServiceManager.startVService(this) }

    fun restartV2Ray() { if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this); lifecycleScope.launch { delay(500); startV2Ray() } }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content; val gaugePing = binding.root.findViewById<PingGaugeView>(R.id.gauge_ping); val tvGreenPing = binding.root.findViewById<TextView>(R.id.tv_green_ping)
        if (content.isNullOrEmpty()) return
        try {
            val normalizedContent = content.replace("٠", "0").replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4").replace("٥", "5").replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9")
            if (normalizedContent.contains("ms", ignoreCase = true) || normalizedContent.contains("م.ث")) {
                val match = Regex("(\\d+)\\s*(ms|م\\.ث)", RegexOption.IGNORE_CASE).find(normalizedContent)
                if (match != null) { val pingValue = match.groupValues[1].toFloat(); gaugePing?.setPing(pingValue); tvGreenPing?.text = "${pingValue.toInt()} ms" } 
                else Regex("(\\d+)").find(normalizedContent)?.let { gaugePing?.setPing(it.value.toFloat()); tvGreenPing?.text = "${it.value} ms" }
            } else if (normalizedContent.contains("Timeout", ignoreCase = true) || normalizedContent.contains("Failed", ignoreCase = true) || normalizedContent.contains("فشل", ignoreCase = true)) { gaugePing?.setPing(500f); tvGreenPing?.text = "Timeout" } 
            else if (normalizedContent == getString(R.string.connection_connected)) { gaugePing?.setPing(0f); tvGreenPing?.text = "متصل..." }
        } catch (e: Exception) {}
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        
        val isNowRunning = isRunning && !isLoading
        if (lastReportedState != isNowRunning && guid.isNotEmpty()) {
            lastReportedState = isNowRunning
            lifecycleScope.launch(Dispatchers.IO) { 
                CloudflareAPI.sendActiveState(idToTrack, isNowRunning) 
                val updatedData = CloudflareAPI.checkLiveConfig(idToTrack)
                V2rayCrypt.saveActiveCount(this@MainActivity, guid, updatedData.third)
                withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
            }
        }

        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check); btnGreenConnect?.text = "جاري تشغيل المحرك..."; btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00")); binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f); binding.root.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f); lottieEngine?.playAnimation(); return
        }

        if (isRunning) {
            if (vpnStartTime == 0L) vpnStartTime = System.currentTimeMillis() // تسجيل وقت التشغيل للعقاب

            binding.fab.setImageResource(R.drawable.ic_stop_24dp); binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active)); binding.fab.contentDescription = getString(R.string.action_stop_service); setTestState(getString(R.string.connection_connected)); binding.layoutTest.isFocusable = true; btnGreenConnect?.text = "إيقاف المحرك"; btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F")); lottieEngine?.playAnimation(); startTrafficMonitor()
            
            pingJob?.cancel(); var lastCloudflareCheck = 0L 
            pingJob = lifecycleScope.launch {
                delay(1000) 
                while (isActive) {
                    try {
                        // ==== نظام العقاب (Kill Switch) ====
                        if (UpdateManager.isUpdatePending && (System.currentTimeMillis() - vpnStartTime) > 3600000L) { // 1 ساعة
                            withContext(Dispatchers.Main) {
                                V2RayServiceManager.stopVService(this@MainActivity)
                                vpnStartTime = 0L
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("تحديث إجباري 🛑")
                                    .setMessage("انتهت مهلة السماح (ساعة واحدة). تم إيقاف التطبيق لوجود تحديث أمني هام. يرجى الذهاب لقسم التحديثات وتثبيته للاستمرار في الاستخدام.")
                                    .setPositiveButton("موافق", null)
                                    .setCancelable(false)
                                    .show()
                            }
                            cancel()
                        }
                        // ===================================

                        mainViewModel.testCurrentServerRealPing()
                        val licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid)
                        val isProtected = V2rayCrypt.isProtected(this@MainActivity, guid)
                        val isAdmin = V2rayCrypt.isAdmin(this@MainActivity, guid)
                        
                        if (licenseId.isNotEmpty() && licenseId != "LEGACY" && (isProtected || isAdmin)) {
                            if (System.currentTimeMillis() - lastCloudflareCheck > 60000L) {
                                lastCloudflareCheck = System.currentTimeMillis()
                                val cloudData = CloudflareAPI.checkLiveConfig(licenseId)
                                val liveExpiry = cloudData.first
                                val liveConfigBase64 = cloudData.second
                                val activeCount = cloudData.third

                                if (liveExpiry >= 0L) {
                                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, activeCount)
                                    withContext(Dispatchers.Main) { mainViewModel.reloadServerList() } 
                                    
                                    val allProtected = V2rayCrypt.getAllProtectedGuids(this@MainActivity)
                                    allProtected.forEach { pGuid -> if (V2rayCrypt.getLicenseId(this@MainActivity, pGuid) == licenseId) V2rayCrypt.saveExpiryTime(this@MainActivity, pGuid, liveExpiry) }
                                    if (isAdmin) V2rayCrypt.saveExpiryTime(this@MainActivity, guid, liveExpiry)
                                    
                                    if (!isAdmin && liveConfigBase64 != null) {
                                        val incomingHash = liveConfigBase64.hashCode(); val currentHash = V2rayCrypt.getLastConfigHash(this@MainActivity, guid)
                                        if (incomingHash != currentHash && liveExpiry > NetworkTime.currentTimeMillis(this@MainActivity)) {
                                            val newConfigRaw = String(Base64.decode(liveConfigBase64, Base64.NO_WRAP)).trim()
                                            withContext(Dispatchers.Main) {
                                                val beforeGuids = MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()
                                                val (count, _) = AngConfigManager.importBatchConfig(newConfigRaw, mainViewModel.subscriptionId, true)
                                                if (count > 0) {
                                                    val newGuid = ((MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()) - beforeGuids).firstOrNull() 
                                                    if (newGuid != null) {
                                                        V2rayCrypt.addProtectedGuids(this@MainActivity, setOf(newGuid)); V2rayCrypt.saveLicenseId(this@MainActivity, newGuid, licenseId); V2rayCrypt.saveExpiryTime(this@MainActivity, newGuid, liveExpiry); V2rayCrypt.saveLastConfigHash(this@MainActivity, newGuid, incomingHash); mainViewModel.removeServer(guid); MmkvManager.setSelectServer(newGuid); toastSuccess("تم تحديث إعدادات السيرفر بنجاح!"); restartV2Ray(); cancel() 
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val currentExpiry = V2rayCrypt.getExpiryTime(this@MainActivity, guid)
                            if (currentExpiry > 0L && NetworkTime.currentTimeMillis(this@MainActivity) > currentExpiry) {
                                withContext(Dispatchers.Main) {
                                    V2RayServiceManager.stopVService(this@MainActivity)
                                    AlertDialog.Builder(this@MainActivity).setTitle("انتهى الاشتراك").setMessage("تم إيقاف المحرك لانتهاء مدة الصلاحية.").setPositiveButton("حسناً", null).setCancelable(false).show()
                                    mainViewModel.reloadServerList()
                                }
                                cancel() 
                            }
                        }
                    } catch (e: Exception) {}
                    delay(3000) 
                }
            }
        } else {
            vpnStartTime = 0L // تصفير وقت التشغيل عند الإيقاف الطوعي
            pingJob?.cancel(); speedTestJob?.cancel(); resetSpeedButtonJob?.cancel(); stopTrafficMonitor()
            binding.fab.setImageResource(R.drawable.ic_play_24dp); binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive)); binding.fab.contentDescription = getString(R.string.tasker_start_service); setTestState(getString(R.string.connection_not_connected)); binding.layoutTest.isFocusable = false; btnGreenConnect?.text = "تشغيل المحرك"; btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")); lottieEngine?.cancelAnimation(); lottieEngine?.progress = 0f; binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f); binding.root.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f); binding.root.findViewById<TextView>(R.id.tv_green_ping)?.text = "--- ms"; val btnTest = binding.root.findViewById<MaterialButton>(R.id.btn_speed_test); btnTest?.isEnabled = true; btnTest?.text = "قياس سرعة الإنترنت"; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
        }
    }

    override fun onResume() { 
        super.onResume()
        if (mainViewModel.isRunning.value == true) startTrafficMonitor() else updateTrafficDisplay() 
        startLiveUpdates()
    }
    
    override fun onPause() { 
        super.onPause()
        stopTrafficMonitor(); speedTestJob?.cancel(); resetSpeedButtonJob?.cancel() 
        liveUpdateJob?.cancel()
    }
    
    override fun onDestroy() {
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        if (lastReportedState == true && idToTrack.isNotEmpty()) {
            lastReportedState = false
            GlobalScope.launch(Dispatchers.IO) { CloudflareAPI.sendActiveState(idToTrack, false) }
        }
        tabMediator?.detach(); pingJob?.cancel(); trafficJob?.cancel(); speedTestJob?.cancel(); resetSpeedButtonJob?.cancel(); liveUpdateJob?.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean { mainViewModel.filterConfig(newText.orEmpty()); return false }
            })
            searchView.setOnCloseListener { mainViewModel.filterConfig(""); false }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) { R.id.import_qrcode -> { showAddBottomSheet(); true } else -> super.onOptionsItemSelected(item) }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) startActivity(Intent().putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerGroupActivity::class.java))
        else startActivity(Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java))
    }

    private fun importClipboard(): Boolean { try { importBatchConfig(Utils.getClipboard(this)) } catch (e: Exception) { return false }; return true }
    private fun importClipboardEncrypted(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this); if (clipboard.isNullOrEmpty()) { toast("الحافظة فارغة!"); return false }
            val result = V2rayCrypt.decryptAndCheckExpiry(clipboard); if (result == null) { toast("الكود غير صالح!"); return false }
            importEncryptedBatchConfig(result.first, result.second, result.third)
        } catch (e: Exception) { return false }
        return true
    }
    private fun importEncryptedFile() { try { openEncryptedFileLauncher.launch(arrayOf("*/*")) } catch (e: Exception) {} }
    private fun readEncryptedContentFromUri(uri: Uri) {
        try {
            val fileContent = contentResolver.openInputStream(uri)?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            if (fileContent.isEmpty()) { toast("الملف فارغ!"); return }
            val result = V2rayCrypt.decryptAndCheckExpiry(fileContent); if (result == null) { toast("الملف غير صالح!"); return }
            importEncryptedBatchConfig(result.first, result.second, result.third)
        } catch (e: Exception) { toast("حدث خطأ") }
    }

    private fun importEncryptedBatchConfig(server: String?, expiryTimeMs: Long = 0L, licenseId: String = "") {
        showLoadingDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val beforeGuids = MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                if (count > 0) { 
                    val newGuids = (MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()) - beforeGuids
                    if (newGuids.isNotEmpty()) {
                        V2rayCrypt.addProtectedGuids(this@MainActivity, newGuids)
                        newGuids.forEach { guid ->
                            if (expiryTimeMs > 0L) V2rayCrypt.saveExpiryTime(this@MainActivity, guid, expiryTimeMs)
                            if (licenseId.isNotEmpty()) V2rayCrypt.saveLicenseId(this@MainActivity, guid, licenseId)
                            if (server != null) V2rayCrypt.saveLastConfigHash(this@MainActivity, guid, Base64.encodeToString(server.toByteArray(), Base64.NO_WRAP).hashCode())
                        }
                    }
                    withContext(Dispatchers.Main) { mainViewModel.reloadServerList(); toast("تم استيراد التكوين!"); hideLoadingDialog() }
                } else if (countSub > 0) { withContext(Dispatchers.Main) { setupGroupTab(); hideLoadingDialog() }
                } else { withContext(Dispatchers.Main) { toastError(R.string.toast_failure); hideLoadingDialog() } }
            } catch (e: Exception) { withContext(Dispatchers.Main) { toastError(R.string.toast_failure); hideLoadingDialog() } }
        }
    }

    private fun importBatchConfig(server: String?) {
        showLoadingDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true); delay(500L)
                withContext(Dispatchers.Main) {
                    when { count > 0 -> { toast(getString(R.string.title_import_config_count, count)); mainViewModel.reloadServerList() } countSub > 0 -> setupGroupTab() else -> toastError(R.string.toast_failure) }
                    hideLoadingDialog()
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { toastError(R.string.toast_failure); hideLoadingDialog() } }
        }
    }

    private fun importConfigLocal(): Boolean { try { launchFileChooser { uri -> if (uri != null) readContentFromUri(uri) } } catch (e: Exception) { return false }; return true }
    private fun readContentFromUri(uri: Uri) { try { contentResolver.openInputStream(uri).use { input -> importBatchConfig(input?.bufferedReader()?.readText()) } } catch (e: Exception) {} }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean { if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) { moveTaskToBack(false); return true }; return super.onKeyDown(keyCode, event) }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onSelectServer(guid: String) { MmkvManager.setSelectServer(guid); toast(R.string.toast_success); groupPagerAdapter.notifyDataSetChanged() }
    override fun onEdit(guid: String, position: Int, profile: ProfileItem) { if (!V2rayCrypt.isProtected(this, guid) || V2rayCrypt.isAdmin(this, guid)) { startActivity(Intent().putExtra("guid", guid).putExtra("subscriptionId", profile.subscriptionId).setClass(this, ServerActivity::class.java)) } else { toast("هذا السيرفر محمي ولا يمكن تعديله") } }
    override fun onRemove(guid: String, position: Int) { AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ -> mainViewModel.removeServer(guid) }.setNegativeButton(android.R.string.cancel, null).show() }
    
    override fun onShare(guid: String, profile: ProfileItem, position: Int, isMore: Boolean) {}
    override fun onEdit(guid: String, position: Int) {}
    override fun onShare(url: String) {}
    override fun onRefreshData() {}
}
