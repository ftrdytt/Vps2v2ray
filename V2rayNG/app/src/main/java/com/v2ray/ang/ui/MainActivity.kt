package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.*
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener, MainAdapterListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null 
    private var screenWidth = 0
    
    private var pingJob: Job? = null
    private var vpnStartTime: Long = 0L

    private var accountWatchdogJob: Job? = null 
    private var fileStatsJob: Job? = null

    companion object { 
        var lastReportedState: Boolean? = null 
        var globalLastRxBytes: Long = 0L
        var globalLastTxBytes: Long = 0L
        var globalActivePingJob: Job? = null 
    }

    private val BASE_API_URL = "https://education.ashor.shop"

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { 
        if (it.resultCode == RESULT_OK) startV2RayCore() 
    }
    
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { 
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) restartV2Ray() 
    }
    
    private val openEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> 
        if (uri != null) {
            ImportHelper.importEncryptedContentFromUri(this, mainViewModel, uri)
            lifecycleScope.launch(Dispatchers.Main) { delay(1000); forceManualSync() }
        }
    }
    
    private val openLocalFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> 
        if (uri != null) {
            ImportHelper.readContentFromUri(this, mainViewModel, uri)
            lifecycleScope.launch(Dispatchers.Main) { delay(1000); forceManualSync() }
        }
    }

    fun showLoadingDialog() { showLoading() }
    fun hideLoadingDialog() { hideLoading() }

    private fun getUniqueHardwareId(): String {
        try {
            val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.DISPLAY +
                    Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT +
                    Build.USER + Build.ID + Build.BOOTLOADER
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(devInfo.toByteArray())
            val hexString = StringBuilder()
            for (byte in hash) {
                val hex = Integer.toHexString(0xFF and byte.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            return hexString.toString().take(15).uppercase()
        } catch (e: Exception) {
            return "UNKNOWN_HW_ID"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        
        if (AuthManager.hasLoggedOut(this)) { 
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return 
        }

        setContentView(binding.root)
        lifecycleScope.launch(Dispatchers.IO) { NetworkTime.syncTime(this@MainActivity) }
        
        if (globalLastRxBytes == 0L && globalLastTxBytes == 0L) {
            globalLastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
            globalLastTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            while (!AuthManager.isLoggedIn(this@MainActivity)) {
                val success = attemptInitialAuth()
                if (success) break
                delay(5000) 
            }
            withContext(Dispatchers.Main) {
                startAccountWatchdog()
                startFileStatsSync() 
            }
        }

        ActiveStatsHelper.reportUpdateSuccess(this)
        UpdateManager.startBackgroundUpdateCheck(this) 

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        setupScreenLayoutsSafe()
        setupUIInteractionsSafe()
        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private suspend fun fetchBatchStats(ids: List<String>): Map<String, JSONObject> {
        return withContext(Dispatchers.IO) {
            val resultMap = mutableMapOf<String, JSONObject>()
            val jobs = ids.distinct().map { id ->
                async {
                    try {
                        val encodedId = java.net.URLEncoder.encode(id, "UTF-8")
                        val checkConn = URL("$BASE_API_URL/check?guid=$encodedId").openConnection() as HttpURLConnection
                        checkConn.connectTimeout = 5000
                        checkConn.readTimeout = 5000
                        if (checkConn.responseCode == 200) {
                            val checkResp = BufferedReader(InputStreamReader(checkConn.inputStream)).readText()
                            return@async Pair(id, JSONObject(checkResp))
                        }
                    } catch (e: Exception) {}
                    null
                }
            }
            jobs.mapNotNull { it.await() }.forEach { resultMap[it.first] = it.second }
            resultMap
        }
    }

    private fun calculateRealDeltaBytes(rxDelta: Long, txDelta: Long): Long {
        val totalDeltaBytes = rxDelta + txDelta
        if (totalDeltaBytes <= 0) return 0L
        val realDelta = (totalDeltaBytes / 3.2).toLong()
        return if (realDelta > 0) realDelta else totalDeltaBytes 
    }

    private fun startFileStatsSync() {
        fileStatsJob?.cancel()
        fileStatsJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
                    val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    
                    val currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
                    val currentTx = TrafficStats.getUidTxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
                    
                    val rxDelta = if (currentRx > globalLastRxBytes) currentRx - globalLastRxBytes else 0L
                    val txDelta = if (currentTx > globalLastTxBytes) currentTx - globalLastTxBytes else 0L
                    
                    val realDeltaBytes = calculateRealDeltaBytes(rxDelta, txDelta)
                    
                    globalLastRxBytes = currentRx
                    globalLastTxBytes = currentTx
                    
                    val activeGuid = MmkvManager.getSelectServer().orEmpty()
                    val myDeviceId = getUniqueHardwareId()
                    val myUserId = AuthManager.getId(this@MainActivity)
                    val myUserRole = AuthManager.getRole(this@MainActivity)
                    val isSuperAdmin = (myUserRole == "admin")

                    if (activeGuid.isNotEmpty() && mainViewModel.isRunning.value == true && realDeltaBytes > 0) {
                        val activeLicenseId = V2rayCrypt.getLicenseId(this@MainActivity, activeGuid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: activeGuid
                        try {
                            val payload = JSONObject()
                                .put("guid", activeLicenseId)
                                .put("deviceId", myDeviceId)
                                .put("userId", myUserId)
                                .put("usageBytes", realDeltaBytes) 
                            
                            val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                            conn.responseCode
                        } catch (e: Exception) {}
                    }

                    val allIdsToFetch = mutableSetOf<String>()
                    val guidToIds = mutableMapOf<String, List<String>>()
                    val guidToLicenseId = mutableMapOf<String, String>()

                    for (guid in guids) {
                        var licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid)
                        if (licenseId.isEmpty() || licenseId == "LEGACY") {
                            if (myUserId.isNotEmpty()) {
                                V2rayCrypt.saveLicenseId(this@MainActivity, guid, myUserId)
                                licenseId = myUserId
                            } else {
                                licenseId = guid
                            }
                        }
                        guidToLicenseId[guid] = licenseId
                        val ids = mutableListOf(licenseId)
                        
                        val isOwnerOrAdmin = V2rayCrypt.isAdmin(this@MainActivity, guid) || isSuperAdmin || (licenseId == myUserId && myUserId.isNotEmpty())
                        
                        if (isOwnerOrAdmin) {
                            val subs = V2rayCrypt.getSubscribers(this@MainActivity, guid)
                            ids.addAll(subs.map { it.licenseId })
                        }
                        
                        guidToIds[guid] = ids
                        allIdsToFetch.addAll(ids)
                    }

                    val batchStats = fetchBatchStats(allIdsToFetch.toList())

                    for (guid in guids) {
                        val licenseId = guidToLicenseId[guid]!!
                        val ids = guidToIds[guid]!!

                        var totalUsageBytes = 0L
                        var totalActiveCount = 0
                        var parentExpiry = -1L
                        var isLocked = false

                        for (id in ids) {
                            val statObj = batchStats[id]
                            if (statObj != null) {
                                totalUsageBytes += statObj.optLong("totalUsageBytes", 0L)
                                totalActiveCount += statObj.optInt("activeCount", 0)
                                if (id == licenseId) {
                                    isLocked = statObj.optBoolean("isLocked", false)
                                    parentExpiry = statObj.optLong("expiryTime", -1L)
                                }
                            }
                        }
                        
                        editor.putBoolean("locked_$guid", isLocked)
                        editor.putString("usage_$guid", formatBytes(totalUsageBytes))
                        V2rayCrypt.saveActiveCount(this@MainActivity, guid, totalActiveCount)
                        if (parentExpiry >= 0L) {
                            V2rayCrypt.saveExpiryTime(this@MainActivity, guid, parentExpiry)
                        }
                    }

                    // 🌟 السحر الجديد: سحب بيانات "الناشر الأصلي" باستخدام pubId المدمج مع الملف 🌟
                    val userInfos = guids.map { guid ->
                        async(Dispatchers.IO) {
                            // نجيب آيدي الناشر الأصلي إذا كان موجود بالتشفير، وإذا ماكو نرجع لـ licenseId
                            var targetFetchId = prefs.getString("pubId_$guid", "") ?: ""
                            if (targetFetchId.isEmpty()) {
                                targetFetchId = guidToLicenseId[guid]!!
                            }

                            try {
                                val encodedLicenseId = java.net.URLEncoder.encode(targetFetchId, "UTF-8")
                                val conn = URL("$BASE_API_URL/auth/get_user?id=$encodedLicenseId").openConnection() as HttpURLConnection
                                conn.connectTimeout = 4000
                                conn.readTimeout = 4000
                                if (conn.responseCode == 200) {
                                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                                    return@async Pair(guid, JSONObject(resp))
                                }
                            } catch (e: Exception) {}
                            null
                        }
                    }
                    
                    userInfos.mapNotNull { it.await() }.forEach { (guid, obj) ->
                        if (obj.optBoolean("success", false)) {
                            val pName = obj.optString("name", "")
                            if (pName.isNotBlank()) editor.putString("name_$guid", pName)
                            editor.putString("pfp_$guid", obj.optString("pfp", ""))
                            editor.putBoolean("story_$guid", obj.optBoolean("hasActiveStory", false))
                            editor.putBoolean("verified_$guid", obj.optBoolean("isVerified", false))
                            
                            // نحتفظ بآيدي الناشر حتى استورياته تنفتح مضبوط
                            val currentPubId = prefs.getString("pubId_$guid", "") ?: ""
                            if (currentPubId.isEmpty()) {
                                editor.putString("pubId_$guid", obj.optString("id", guidToLicenseId[guid]!!)) 
                            }
                        }
                    }

                    editor.apply()
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList() 
                    }
                } catch (e: Exception) {}
                
                delay(5 * 60 * 1000L) 
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0.0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1024) {
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }
        return String.format("%.1f MB", mb)
    }

    private fun startAccountWatchdog() {
        val userId = AuthManager.getId(this)
        if (userId.isEmpty() || AuthManager.getRole(this) == "admin") return

        val deviceId = getUniqueHardwareId()

        accountWatchdogJob?.cancel()
        accountWatchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val conn = URL("$BASE_API_URL/auth/get_user?id=$userId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            val serverDevices = obj.optJSONArray("devices") ?: JSONArray()
                            var isDeviceAuthorized = false
                            for (i in 0 until serverDevices.length()) {
                                if (serverDevices.getString(i) == deviceId) {
                                    isDeviceAuthorized = true
                                    break
                                }
                            }
                            if (!isDeviceAuthorized) {
                                forceLogoutAndClean("تم إنهاء جلستك من جهاز آخر أو من الإدارة! 🚫")
                                break
                            }
                        } else {
                            forceLogoutAndClean("تم حذف حسابك من قبل الإدارة! 🚫")
                            break
                        }
                    }
                } catch (e: Exception) {}
                delay(15000)
            }
        }
    }

    private fun forceLogoutAndClean(reason: String) {
        val deviceId = getUniqueHardwareId() 
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this@MainActivity, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid

        lifecycleScope.launch(Dispatchers.IO) {
            if (mainViewModel.isRunning.value == true) {
                try {
                    val payload = JSONObject()
                        .put("guid", idToTrack)
                        .put("deviceId", deviceId)
                        .put("userId", "")
                        .put("disconnect", true)
                    val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { V2RayServiceManager.stopVService(this@MainActivity) }
            }

            try {
                val conn = URL("$BASE_API_URL/auth/terminate_device").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { 
                    it.write(JSONObject().put("id", AuthManager.getId(this@MainActivity)).put("targetDeviceId", deviceId).toString().toByteArray(Charsets.UTF_8)) 
                }
                conn.responseCode
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                AuthManager.logout(this@MainActivity)
                val intent = Intent(this@MainActivity, LoginActivity::class.java).apply { 
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private suspend fun attemptInitialAuth(): Boolean {
        var isSuccess = false
        val deviceId = getUniqueHardwareId() 
        try {
            val conn = URL("$BASE_API_URL/auth/init").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            
            val payload = JSONObject().apply { put("deviceId", deviceId) }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                if (obj.getBoolean("success")) {
                    AuthManager.saveUser(this@MainActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "تم إنشاء الحساب التلقائي بنجاح!", Toast.LENGTH_SHORT).show() }
                    isSuccess = true
                }
            }
        } catch (e: Exception) {}
        return isSuccess
    }

    private fun setupScreenLayoutsSafe() {
        try {
            screenWidth = resources.displayMetrics.widthPixels
            val settingsWrapper = binding.root.findViewById<View>(R.id.settings_wrapper)
            settingsWrapper?.layoutParams?.width = screenWidth
            
            val updatesWrapper = FrameLayout(this).apply { 
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
            }
            
            val profileWrapper = FrameLayout(this).apply { 
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
            }
            
            val scrollContainer = settingsWrapper?.parent as? LinearLayout
            if (scrollContainer != null) {
                scrollContainer.orientation = LinearLayout.HORIZONTAL
                scrollContainer.addView(updatesWrapper, 1)
                scrollContainer.addView(profileWrapper, 2)
                
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_fragment_container, SettingsActivity.SettingsFragment())
                    .replace(updatesWrapper.id, UpdatesFragment())
                    .replace(profileWrapper.id, ProfileFragment())
                    .commitAllowingStateLoss()
            }
            
            binding.homeContentContainer.layoutParams.width = screenWidth
            
            val greenScreen = binding.root.findViewById<View>(R.id.green_screen_container)
            greenScreen?.layoutParams?.width = screenWidth
            
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setupUIInteractionsSafe() {
        try {
            binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)?.setOnClickListener { handleFabAction() }
            
            binding.root.findViewById<MaterialButton>(R.id.btn_speed_test)?.let { 
                it.setOnClickListener { SpeedTestHelper.runSpeedTest(this, mainViewModel.isRunning.value == true) } 
            }
            
            binding.root.findViewById<CardView>(R.id.card_traffic_meter)?.setOnClickListener { 
                TrafficMonitorHelper.showTrafficDetailsDialog(this, mainViewModel.isRunning.value == true) 
            }

            binding.root.findViewById<ImageView>(R.id.btn_full_log)?.setOnClickListener {
                val fullLogs = mainViewModel.fullLog.value ?: "لا توجد سجلات حالياً..."
                
                val scrollView = ScrollView(this).apply {
                    setPadding(40, 30, 40, 30)
                }
                
                val tvLogs = TextView(this).apply {
                    text = fullLogs
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                }
                scrollView.addView(tvLogs)

                AlertDialog.Builder(this)
                    .setTitle("سجل المحرك الكامل")
                    .setView(scrollView)
                    .setPositiveButton("إغلاق", null)
                    .show()
            }

            val bottomNav = binding.root.findViewById<BottomNavigationView>(R.id.bottom_nav_view)
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

            bottomNav?.setOnItemSelectedListener { item -> 
                when (item.itemId) { 
                    R.id.nav_settings -> binding.mainScrollView.smoothScrollTo(0, 0)
                    R.id.nav_updates -> binding.mainScrollView.smoothScrollTo(screenWidth, 0)
                    R.id.nav_profile -> binding.mainScrollView.smoothScrollTo(screenWidth * 2, 0)
                    R.id.nav_servers -> binding.mainScrollView.smoothScrollTo(screenWidth * 3, 0)
                    R.id.nav_home -> binding.mainScrollView.smoothScrollTo(screenWidth * 4, 0) 
                }
                true 
            }
            
            binding.mainScrollView.post { binding.mainScrollView.scrollTo(screenWidth * 4, 0) }
            setupToolbar(binding.toolbar, false, "اشور لود")
            
            val toggle = ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
            toggle.isDrawerIndicatorEnabled = false 
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED) 
            binding.drawerLayout.addDrawerListener(toggle)
            toggle.syncState()
            binding.navView.setNavigationItemSelectedListener(this)
            
            binding.layoutTest.setOnClickListener { 
                if (mainViewModel.isRunning.value == true) { 
                    setTestState(getString(R.string.connection_test_testing))
                    mainViewModel.testCurrentServerRealPing() 
                } else { 
                    toast(R.string.connection_not_connected) 
                }
            }
            
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        if (binding.mainScrollView.scrollX != screenWidth * 4) { 
                            binding.mainScrollView.smoothScrollTo(screenWidth * 4, 0)
                            bottomNav?.selectedItemId = R.id.nav_home 
                        } else { 
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true 
                        }
                    }
                }
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleFabAction() {
        if (UpdateManager.isUpdateReady && UpdateManager.readyApkFile != null) {
            if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this)
            UpdateManager.showMandatoryUpdateDialog(this, UpdateManager.readyApkFile!!)
            return
        }
        
        if (mainViewModel.isRunning.value == true) {
            val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
            val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
            
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            btnGreenConnect?.text = "جاري قطع الاتصال..."
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00"))
            lottieEngine?.playAnimation()

            lifecycleScope.launch(Dispatchers.IO) {
                val guid = MmkvManager.getSelectServer().orEmpty()
                val idToTrack = V2rayCrypt.getLicenseId(this@MainActivity, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
                val deviceId = getUniqueHardwareId() 
                
                if (idToTrack.isNotEmpty()) {
                    val userId = AuthManager.getId(this@MainActivity)
                    val payload = JSONObject()
                        .put("guid", idToTrack)
                        .put("deviceId", deviceId)
                        .put("userId", userId)
                        .put("disconnect", true)
                    
                    try {
                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } catch (e: Exception) {}

                    lastReportedState = false
                    val prevCount = V2rayCrypt.getActiveCount(this@MainActivity, guid)
                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, max(0, prevCount - 1))
                }
                
                delay(1200) 
                
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    V2RayServiceManager.stopVService(this@MainActivity) 
                }
            }
        } else {
            startV2Ray()
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        val tvLiveLog = binding.root.findViewById<TextView>(R.id.tv_live_log)
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        val deviceId = getUniqueHardwareId() 
        val appContext = applicationContext 
        
        val isNowRunning = isRunning && !isLoading

        if (lastReportedState != isNowRunning && guid.isNotEmpty()) {
            if (isNowRunning && !isLoading) {
                lastReportedState = true
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val userId = AuthManager.getId(this@MainActivity)
                        val payload = JSONObject()
                            .put("guid", idToTrack)
                            .put("deviceId", deviceId)
                            .put("userId", userId)
                            .put("name", if (userId.isNotEmpty()) AuthManager.getName(this@MainActivity) else "مجهول")
                            .put("pfp", if (userId.isNotEmpty()) AuthManager.getPfp(this@MainActivity) else "")
                        
                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } catch (e: Exception) {}

                    delay(1000) 
                    val encodedId = java.net.URLEncoder.encode(idToTrack, "UTF-8")
                    try {
                        val checkConn = URL("$BASE_API_URL/check?guid=$encodedId").openConnection() as HttpURLConnection
                        checkConn.connectTimeout = 4000
                        checkConn.readTimeout = 4000
                        if (checkConn.responseCode == 200) {
                            val checkResp = BufferedReader(InputStreamReader(checkConn.inputStream)).readText()
                            val checkObj = JSONObject(checkResp)
                            V2rayCrypt.saveActiveCount(this@MainActivity, guid, checkObj.optInt("activeCount", 0))
                        }
                    } catch (e: Exception) {}
                    
                    withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                }
            }
        }

        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            btnGreenConnect?.text = "جاري تشغيل المحرك..."
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00"))
            binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f)
            binding.root.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f)
            tvLiveLog?.text = "⏳ جاري تهيئة المحرك للاتصال..."
            lottieEngine?.playAnimation()
            return
        }

        if (isRunning) {
            if (vpnStartTime == 0L) vpnStartTime = System.currentTimeMillis()
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            btnGreenConnect?.text = "إيقاف المحرك"
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            lottieEngine?.playAnimation()
            TrafficMonitorHelper.startTrafficMonitor(this)

            globalActivePingJob?.cancel()
            globalActivePingJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    try {
                        val currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
                        val currentTx = TrafficStats.getUidTxBytes(android.os.Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
                        
                        val rxDelta = if (currentRx > globalLastRxBytes) currentRx - globalLastRxBytes else 0L
                        val txDelta = if (currentTx > globalLastTxBytes) currentTx - globalLastTxBytes else 0L
                        
                        val realDeltaBytes = calculateRealDeltaBytes(rxDelta, txDelta)
                        
                        globalLastRxBytes = currentRx
                        globalLastTxBytes = currentTx

                        val userId = AuthManager.getId(appContext)
                        val payload = JSONObject()
                            .put("guid", idToTrack)
                            .put("deviceId", deviceId)
                            .put("userId", userId)
                            .put("name", if (userId.isNotEmpty()) AuthManager.getName(appContext) else "مجهول الهوية")
                            .put("pfp", if (userId.isNotEmpty()) AuthManager.getPfp(appContext) else "")
                            .put("usageBytes", realDeltaBytes) 
                            .put("disconnect", false)

                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } catch (e: Exception) {}
                    
                    delay(30000L) 
                }
            }

            pingJob?.cancel()
            pingJob = lifecycleScope.launch {
                delay(1000)
                while (isActive) {
                    try {
                        mainViewModel.testCurrentServerRealPing()

                        val currentExpiry = V2rayCrypt.getExpiryTime(this@MainActivity, guid)
                        if (currentExpiry > 0L && NetworkTime.currentTimeMillis(this@MainActivity) > currentExpiry) {
                            withContext(Dispatchers.IO) {
                                if (idToTrack.isNotEmpty()) {
                                    val userId = AuthManager.getId(this@MainActivity)
                                    val payload = JSONObject()
                                        .put("guid", idToTrack)
                                        .put("deviceId", deviceId)
                                        .put("userId", userId)
                                        .put("disconnect", true)
                                    try {
                                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        conn.doOutput = true
                                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                                        conn.responseCode
                                    } catch (e: Exception) {}
                                    
                                    val prevCount = V2rayCrypt.getActiveCount(this@MainActivity, guid)
                                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, max(0, prevCount - 1))
                                    lastReportedState = false
                                }
                                delay(1000) 
                            }
                            
                            withContext(Dispatchers.Main) {
                                V2RayServiceManager.stopVService(this@MainActivity)
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("انتهى الاشتراك")
                                    .setMessage("تم إيقاف المحرك لانتهاء مدة الصلاحية أو إيقافه من قبل الإدارة.")
                                    .setPositiveButton("حسناً", null)
                                    .setCancelable(false)
                                    .show()
                                mainViewModel.reloadServerList()
                            }
                            break 
                        }
                    } catch (e: Exception) {}
                    delay(5000) 
                }
            }
        } else {
            vpnStartTime = 0L 
            pingJob?.cancel()
            globalActivePingJob?.cancel() 
            TrafficMonitorHelper.stopTrafficMonitor()
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            btnGreenConnect?.text = "تشغيل المحرك"
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
            tvLiveLog?.text = "بانتظار تشغيل المحرك..."
            lottieEngine?.cancelAnimation()
            lottieEngine?.progress = 0f
            binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f)
            binding.root.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f)
            binding.root.findViewById<TextView>(R.id.tv_green_ping)?.text = "--- ms"
            val btnTest = binding.root.findViewById<MaterialButton>(R.id.btn_speed_test)
            btnTest?.isEnabled = true
            btnTest?.text = "قياس سرعة الإنترنت"
            btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
        }
    }

    private fun startV2Ray() { 
        val guid = MmkvManager.getSelectServer().orEmpty()
        if (guid.isNullOrEmpty()) { toast(R.string.title_file_chooser); return }
        
        val deviceId = getUniqueHardwareId() 
        val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        
        binding.fab.setImageResource(R.drawable.ic_fab_check)
        btnGreenConnect?.text = "جاري الفحص والتشغيل..."
        btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00"))
        lottieEngine?.playAnimation()

        lifecycleScope.launch(Dispatchers.IO) {
            var isBanned = false
            try {
                val conn = URL("$BASE_API_URL/file/check_ban?guid=$guid&deviceId=$deviceId").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    if (resp.startsWith("{")) {
                        val jsonResponse = JSONObject(resp)
                        if (jsonResponse.optBoolean("banned", false)) {
                            isBanned = true
                            val banMsg = jsonResponse.optString("message", "تم حظرك من هذا الملف من قبل الإدارة 🚫")
                            withContext(Dispatchers.Main) {
                                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.color_fab_inactive))
                                btnGreenConnect?.text = "تشغيل المحرك"
                                btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
                                lottieEngine?.cancelAnimation()
                                lottieEngine?.progress = 0f
                                Toast.makeText(this@MainActivity, banMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {} 
            
            if (!isBanned) {
                withContext(Dispatchers.Main) { startV2RayCore() }
            }
        }
    }

    private fun startV2RayCore() {
        UpdateManager.startSilentWatchdog(this)
        if (SettingsManager.isVpnMode()) { 
            val intent = VpnService.prepare(this)
            if (intent == null) V2RayServiceManager.startVService(this) else requestVpnPermission.launch(intent) 
        } else {
            V2RayServiceManager.startVService(this)
        }
    }
    
    fun restartV2Ray() { 
        if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this)
        lifecycleScope.launch { delay(500); startV2RayCore() } 
    }

    private fun performCloudBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = AuthManager.getId(this@MainActivity)
                if (userId.isEmpty()) return@launch
                
                val configsJsonStr = MmkvManager.exportAllConfigsForCloud()
                val configsArray = if (configsJsonStr.isNotBlank() && configsJsonStr != "null") JSONArray(configsJsonStr) else JSONArray()
                
                val payload = JSONObject().put("userId", userId).put("configs", configsArray)
                val conn = URL("$BASE_API_URL/user/backup").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                
                if (conn.responseCode == 200) {
                    val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
                    val editor = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE).edit()
                    guids.forEach { editor.putBoolean("cloud_$it", true) } 
                    editor.apply()
                    
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun forceManualSync() {
        showLoadingDialog()
        performCloudBackup()

        lifecycleScope.launch(Dispatchers.IO) {
            val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
            val myUserId = AuthManager.getId(this@MainActivity)
            val myUserRole = AuthManager.getRole(this@MainActivity)
            val isSuperAdmin = (myUserRole == "admin")
            val prefs = getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            val allIdsToFetch = mutableSetOf<String>()
            val guidToIds = mutableMapOf<String, List<String>>()
            val guidToLicenseId = mutableMapOf<String, String>()

            for (guid in guids) {
                var licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid)
                if (licenseId.isEmpty() || licenseId == "LEGACY") {
                    if (myUserId.isNotEmpty()) {
                        V2rayCrypt.saveLicenseId(this@MainActivity, guid, myUserId)
                        licenseId = myUserId
                    } else {
                        licenseId = guid
                    }
                }
                guidToLicenseId[guid] = licenseId
                val ids = mutableListOf(licenseId)
                
                val isOwnerOrAdmin = V2rayCrypt.isAdmin(this@MainActivity, guid) || isSuperAdmin || (licenseId == myUserId && myUserId.isNotEmpty())
                
                if (isOwnerOrAdmin) {
                    val subs = V2rayCrypt.getSubscribers(this@MainActivity, guid)
                    ids.addAll(subs.map { it.licenseId })
                }
                
                guidToIds[guid] = ids
                allIdsToFetch.addAll(ids)
            }

            val batchStats = fetchBatchStats(allIdsToFetch.toList())

            for (guid in guids) {
                val licenseId = guidToLicenseId[guid]!!
                val ids = guidToIds[guid]!!

                var totalUsageBytes = 0L
                var totalActiveCount = 0
                var parentExpiry = -1L
                var isLocked = false

                for (id in ids) {
                    val statObj = batchStats[id]
                    if (statObj != null) {
                        totalUsageBytes += statObj.optLong("totalUsageBytes", 0L)
                        totalActiveCount += statObj.optInt("activeCount", 0)
                        if (id == licenseId) {
                            isLocked = statObj.optBoolean("isLocked", false)
                            parentExpiry = statObj.optLong("expiryTime", -1L)
                        }
                    }
                }
                
                editor.putBoolean("locked_$guid", isLocked)
                editor.putString("usage_$guid", formatBytes(totalUsageBytes))
                V2rayCrypt.saveActiveCount(this@MainActivity, guid, totalActiveCount)
                if (parentExpiry >= 0L) {
                    V2rayCrypt.saveExpiryTime(this@MainActivity, guid, parentExpiry)
                }
            }

            // 🌟 السحر الجديد: سحب بيانات "الناشر الأصلي" باستخدام pubId المدمج مع الملف 🌟
            val userInfos = guids.map { guid ->
                async(Dispatchers.IO) {
                    // نجيب آيدي الناشر الأصلي إذا كان موجود بالتشفير، وإذا ماكو نرجع لـ licenseId
                    var targetFetchId = prefs.getString("pubId_$guid", "") ?: ""
                    if (targetFetchId.isEmpty()) {
                        targetFetchId = guidToLicenseId[guid]!!
                    }

                    try {
                        val encodedLicenseId = java.net.URLEncoder.encode(targetFetchId, "UTF-8")
                        val conn = URL("$BASE_API_URL/auth/get_user?id=$encodedLicenseId").openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        if (conn.responseCode == 200) {
                            val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                            return@async Pair(guid, JSONObject(resp))
                        }
                    } catch (e: Exception) {}
                    null
                }
            }
            
            userInfos.mapNotNull { it.await() }.forEach { (guid, obj) ->
                if (obj.optBoolean("success", false)) {
                    val pName = obj.optString("name", "")
                    if (pName.isNotBlank()) editor.putString("name_$guid", pName)
                    editor.putString("pfp_$guid", obj.optString("pfp", ""))
                    editor.putBoolean("story_$guid", obj.optBoolean("hasActiveStory", false))
                    editor.putBoolean("verified_$guid", obj.optBoolean("isVerified", false))
                    
                    // نحتفظ بآيدي الناشر حتى استورياته تنفتح مضبوط
                    val currentPubId = prefs.getString("pubId_$guid", "") ?: ""
                    if (currentPubId.isEmpty()) {
                        editor.putString("pubId_$guid", obj.optString("id", guidToLicenseId[guid]!!)) 
                    }
                }
            }
            
            editor.apply()
            withContext(Dispatchers.Main) { 
                mainViewModel.reloadServerList()
                hideLoadingDialog()
                Toast.makeText(this@MainActivity, "تم تحديث ومزامنة البيانات بنجاح! ☁️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun translateLog(log: String): String {
        if (log.isBlank()) return "بانتظار تشغيل المحرك..."
        val l = log.lowercase()
        return when {
            l.contains("started") -> "🚀 المحرك يعمل بنجاح! يتم تأمين الاتصال..."
            l.contains("timeout") -> "⚠️ انتهى وقت الاتصال (Timeout) - تحقق من السيرفر أو البايلود"
            l.contains("connection reset") -> "⚠️ تم قطع الاتصال من قبل السيرفر (Reset)"
            l.contains("no route to host") -> "🚫 لا يوجد مسار للسيرفر (تأكد من عنوان IP/SNI)"
            l.contains("connection refused") -> "🚫 السيرفر يرفض الاتصال (Refused - البورت مغلق؟)"
            l.contains("tls") || l.contains("certificate") -> "🔒 مشكلة في الحماية (TLS) - تحقق من الـ SNI"
            l.contains("dns") -> "🌐 مشكلة في تحليل الـ DNS للسيرفر"
            l.contains("io: read/write on closed pipe") -> "⚡ انقطع الاتصال فجأة (غالباً بسبب التوجيه الخاطئ)"
            l.contains("dial tcp") -> "⏳ جاري محاولة الاتصال والربط بالسيرفر..."
            l.contains("vless") && l.contains("encoding") -> "❌ خطأ في إعدادات VLESS"
            l.contains("proxy/vless") -> "🔄 جاري توجيه بيانات VLESS..."
            l.contains("proxy/trojan") -> "🔄 جاري توجيه بيانات التروجان..."
            l.contains("app/dispatcher") -> "🔀 جاري توجيه الاتصال داخلياً..."
            l.contains("failed to start") -> "❌ فشل بدء المحرك - تأكد من البايلود (JSON)"
            l.contains("invalid") -> "⚠️ خطأ في صياغة البايلود أو السيرفر غير صالح"
            else -> log 
        }
    }

    private fun setupViewModel() { 
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning -> applyRunningState(false, isRunning) }
        
        mainViewModel.liveLog.observe(this) { log ->
            val tvLiveLog = binding.root.findViewById<TextView>(R.id.tv_live_log)
            tvLiveLog?.text = translateLog(log)
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets) 
    }
    
    private fun setupGroupTab() { 
        try {
            val groups = mainViewModel.getSubscriptions(this)
            groupPagerAdapter.update(groups)
            
            tabMediator?.detach()
            tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position -> 
                val item = groupPagerAdapter.groups.getOrNull(position)
                tab.text = item?.remarks
                tab.tag = item?.id
            }.also { it.attach() }
            
            val index = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }
            binding.viewPager.setCurrentItem(if (index >= 0) index else max(0, groups.size - 1), false)
            binding.tabGroup.isVisible = groups.size > 1 
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setTestState(content: String?) {
        val tvTestState = binding.root.findViewById<TextView>(R.id.tv_test_state)
        val gaugePing = binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)
        val tvGreenPing = binding.root.findViewById<TextView>(R.id.tv_green_ping)
        
        tvTestState?.text = content ?: ""
        
        if (content.isNullOrEmpty()) {
            gaugePing?.setPing(0f)
            tvGreenPing?.text = "--- ms"
            return
        }
        
        try {
            val normalizedContent = content.replace("٠", "0").replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4").replace("٥", "5").replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9")
            
            if (normalizedContent.contains("ms", ignoreCase = true) || normalizedContent.contains("م.ث")) {
                val match = Regex("(\\d+)\\s*(ms|م\\.ث)", RegexOption.IGNORE_CASE).find(normalizedContent)
                if (match != null) { 
                    val pingValue = match.groupValues[1].toFloat()
                    gaugePing?.setPing(pingValue) 
                    tvGreenPing?.text = "${pingValue.toInt()} ms" 
                } 
                else Regex("(\\d+)").find(normalizedContent)?.let { 
                    gaugePing?.setPing(it.value.toFloat()) 
                    tvGreenPing?.text = "${it.value} ms" 
                }
            } else if (normalizedContent.contains("Timeout", ignoreCase = true) || normalizedContent.contains("Failed", ignoreCase = true) || normalizedContent.contains("فشل", ignoreCase = true)) { 
                gaugePing?.setPing(500f) 
                tvGreenPing?.text = "Timeout" 
            } 
            else if (normalizedContent == getString(R.string.connection_connected)) { 
                gaugePing?.setPing(0f)
                tvGreenPing?.text = "متصل..." 
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() { 
        super.onResume()
        if (mainViewModel.isRunning.value == true) TrafficMonitorHelper.startTrafficMonitor(this) else TrafficMonitorHelper.updateTrafficDisplay(this)
        VpnEngineHelper.startLiveUpdates(this, mainViewModel)
        if (UpdateManager.isUpdateReady && UpdateManager.readyApkFile != null) UpdateManager.showMandatoryUpdateDialog(this, UpdateManager.readyApkFile!!) 
        
        forceManualSync()
    }

    override fun onPause() { 
        super.onPause()
        TrafficMonitorHelper.stopTrafficMonitor()
        SpeedTestHelper.cancelJobs() 
    }
    
    override fun onDestroy() { 
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        if (lastReportedState == true && idToTrack.isNotEmpty()) {
            lastReportedState = false
            val deviceId = getUniqueHardwareId() 
            @Suppress("OPT_IN_USAGE")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userId = AuthManager.getId(this@MainActivity)
                    val payload = JSONObject()
                        .put("guid", idToTrack)
                        .put("deviceId", deviceId)
                        .put("userId", userId)
                        .put("disconnect", true)
                    val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } catch (e: Exception) {}
            }
        }
        tabMediator?.detach()
        VpnEngineHelper.cancelAllJobs()
        TrafficMonitorHelper.stopTrafficMonitor()
        SpeedTestHelper.cancelJobs()
        pingJob?.cancel()
        accountWatchdogJob?.cancel()
        fileStatsJob?.cancel() 
        super.onDestroy() 
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { 
        menuInflater.inflate(R.menu.menu_main, menu)
        (menu.findItem(R.id.search_view)?.actionView as? SearchView)?.apply { 
            setOnQueryTextListener(object : SearchView.OnQueryTextListener { 
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(t: String?) = false.also { mainViewModel.filterConfig(t.orEmpty()) } 
            }) 
        }
        return super.onCreateOptionsMenu(menu) 
    }
    
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) { 
        R.id.import_qrcode -> { 
            ImportHelper.showAddBottomSheet(this, mainViewModel, { openLocalFileLauncher.launch(arrayOf("*/*")) }, { openEncryptedFileLauncher.launch(arrayOf("*/*")) })
            true 
        }
        else -> super.onOptionsItemSelected(item) 
    }
    
    override fun onNavigationItemSelected(item: MenuItem): Boolean { 
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true 
    }
    
    private fun handleIntent(intent: Intent?) { 
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { 
                ImportHelper.importEncryptedContentFromUri(this, mainViewModel, it) 
                lifecycleScope.launch(Dispatchers.Main) { delay(1000); forceManualSync() }
            } 
        }
    }

    override fun onNewIntent(intent: Intent) { 
        super.onNewIntent(intent)
        handleIntent(intent) 
    }

    fun openSubscribersPanel(parentGuid: String) { 
        startActivity(Intent(this, SubscribersActivity::class.java).putExtra("parentGuid", parentGuid)) 
    }
    
    fun showExtendLicenseDialog(guid: String) { 
        AdminHelper.showExtendLicenseDialog(this, guid, { mainViewModel.reloadServerList() }, { showLoadingDialog() }, { hideLoadingDialog() }) 
    }
    
    fun replaceAndSyncConfigFromClipboard(guid: String) { 
        AdminHelper.replaceAndSyncConfigFromClipboard(this, guid, mainViewModel.subscriptionId, { 
            mainViewModel.reloadServerList() 
            performCloudBackup() 
        }, { showLoadingDialog() }, { hideLoadingDialog() }) 
    }

    override fun onSelectServer(guid: String) { 
        val oldGuid = MmkvManager.getSelectServer().orEmpty()
        if (oldGuid == guid) return

        if (mainViewModel.isRunning.value == true) {
            val idToTrack = V2rayCrypt.getLicenseId(this, oldGuid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: oldGuid
            val deviceId = getUniqueHardwareId() 
            
            toast("جاري التبديل للملف الجديد...")
            lifecycleScope.launch(Dispatchers.IO) {
                
                if (idToTrack.isNotEmpty()) {
                    try {
                        val userId = AuthManager.getId(this@MainActivity)
                        val payload = JSONObject()
                            .put("guid", idToTrack)
                            .put("deviceId", deviceId)
                            .put("userId", userId)
                            .put("disconnect", true)
                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } catch (e: Exception) {}

                    val prevCount = V2rayCrypt.getActiveCount(this@MainActivity, oldGuid)
                    V2rayCrypt.saveActiveCount(this@MainActivity, oldGuid, max(0, prevCount - 1))
                    lastReportedState = false
                }
                
                delay(1000) 
                
                withContext(Dispatchers.Main) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    MmkvManager.setSelectServer(guid)
                    groupPagerAdapter.notifyDataSetChanged()
                }
                
                delay(800) 
                
                withContext(Dispatchers.Main) {
                    startV2RayCore()
                }
            }
        } else {
            MmkvManager.setSelectServer(guid)
            toast(R.string.toast_success)
            groupPagerAdapter.notifyDataSetChanged()
        }
    }
    
    override fun onEdit(guid: String, pos: Int, p: ProfileItem) { 
        if (!V2rayCrypt.isProtected(this, guid) || V2rayCrypt.isAdmin(this, guid)) {
            startActivity(Intent(this, ServerActivity::class.java).putExtra("guid", guid)) 
        } else {
            toast("هذا السيرفر محمي") 
        }
    }
    
    override fun onRemove(guid: String, pos: Int) { 
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> mainViewModel.removeServer(guid) }
            .setNegativeButton(android.R.string.cancel, null)
            .show() 
    }
    
    override fun onShare(guid: String, p: ProfileItem, pos: Int, isMore: Boolean) {} 
    override fun onEdit(guid: String, pos: Int) {} 
    override fun onShare(url: String) {} 
    override fun onRefreshData() {}

    fun openAshorConfig() {
        startActivity(Intent(this, ServerAshorActivity::class.java))
    }
}
