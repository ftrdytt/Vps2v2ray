package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
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
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.*
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener, MainAdapterListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null 
    private var screenWidth = 0
    
    private var pingJob: Job? = null
    private var activePingJob: Job? = null
    private var vpnStartTime: Long = 0L
    companion object { var lastReportedState: Boolean? = null }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) startV2Ray() }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) restartV2Ray() }
    
    private val openEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) ImportHelper.importEncryptedContentFromUri(this, mainViewModel, uri) }
    private val openLocalFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) ImportHelper.readContentFromUri(this, mainViewModel, uri) }

    fun showLoadingDialog() { showLoading() }
    fun hideLoadingDialog() { hideLoading() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        
        if (AuthManager.hasLoggedOut(this)) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }

        setContentView(binding.root)
        lifecycleScope.launch(Dispatchers.IO) { NetworkTime.syncTime(this@MainActivity) }
        
        checkInitialAuth()
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

    private fun checkInitialAuth() {
        if (!AuthManager.isLoggedIn(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://vpn-license.rauter505.workers.dev/auth/init").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    if (conn.responseCode == 200) {
                        val obj = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                        if (obj.getBoolean("success")) AuthManager.saveUser(this@MainActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                    }
                } catch (e: Exception) {}
            }
        }
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
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupUIInteractionsSafe() {
        try {
            binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)?.setOnClickListener { handleFabAction() }
            binding.root.findViewById<MaterialButton>(R.id.btn_speed_test)?.let { it.setOnClickListener { SpeedTestHelper.runSpeedTest(this, mainViewModel.isRunning.value == true) } }
            binding.root.findViewById<CardView>(R.id.card_traffic_meter)?.setOnClickListener { TrafficMonitorHelper.showTrafficDetailsDialog(this, mainViewModel.isRunning.value == true) }
            
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
            binding.drawerLayout.addDrawerListener(toggle); toggle.syncState(); binding.navView.setNavigationItemSelectedListener(this)
            
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
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) binding.drawerLayout.closeDrawer(GravityCompat.START)
                    else {
                        if (binding.mainScrollView.scrollX != screenWidth * 4) { binding.mainScrollView.smoothScrollTo(screenWidth * 4, 0); bottomNav?.selectedItemId = R.id.nav_home } 
                        else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
                
                if (idToTrack.isNotEmpty()) {
                    CloudflareAPI.sendActiveState(idToTrack, deviceId, true)
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
            applyRunningState(isLoading = true, isRunning = false)
            if (SettingsManager.isVpnMode()) { 
                val intent = VpnService.prepare(this)
                if (intent == null) startV2Ray() else requestVpnPermission.launch(intent) 
            } else {
                startV2Ray()
            }
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        
        val isNowRunning = isRunning && !isLoading

        if (lastReportedState != isNowRunning && guid.isNotEmpty()) {
            if (isNowRunning && !isLoading) {
                lastReportedState = true
                lifecycleScope.launch(Dispatchers.IO) {
                    CloudflareAPI.sendActiveState(idToTrack, deviceId, false)
                    delay(1000) 
                    val updatedData = CloudflareAPI.checkLiveConfig(idToTrack)
                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, updatedData.third)
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

            activePingJob?.cancel()
            activePingJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val userId = AuthManager.getId(this@MainActivity)
                        val name = if (userId.isNotEmpty()) AuthManager.getName(this@MainActivity) else "مجهول الهوية"
                        val pfp = if (userId.isNotEmpty()) AuthManager.getPfp(this@MainActivity) else ""
                        val conn = URL("https://vpn-license.rauter505.workers.dev/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        val payload = JSONObject()
                            .put("guid", idToTrack)
                            .put("deviceId", deviceId)
                            .put("userId", userId)
                            .put("name", name)
                            .put("pfp", pfp)
                            .put("disconnect", false)
                        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                        conn.responseCode
                    } catch (e: Exception) {}
                    
                    delay(10800000L) 
                }
            }

            pingJob?.cancel()
            pingJob = lifecycleScope.launch {
                delay(1000)
                var updateCheckedAfterConnect = false // 🌟 السويتش اللي ضفناه لفحص التحديث بعد 30 ثانية
                
                while (isActive) {
                    try {
                        if (UpdateManager.isUpdatePending && (System.currentTimeMillis() - vpnStartTime) > 3600000L) {
                            withContext(Dispatchers.Main) {
                                V2RayServiceManager.stopVService(this@MainActivity)
                                vpnStartTime = 0L
                                AlertDialog.Builder(this@MainActivity).setTitle("تحديث إجباري 🛑").setMessage("انتهت مهلة السماح (ساعة واحدة). تم إيقاف التطبيق لوجود تحديث أمني هام.").setPositiveButton("موافق", null).setCancelable(false).show()
                            }
                            break 
                        }
                        
                        // 🌟 الكود الجديد: فحص التحديث لمرة واحدة فقط بعد 30 ثانية من الاتصال 🌟
                        if (!updateCheckedAfterConnect && (System.currentTimeMillis() - vpnStartTime) > 30000L) {
                            updateCheckedAfterConnect = true // قفل السويتش حتى لا يفحص مرة ثانية
                            UpdateManager.startBackgroundUpdateCheck(this@MainActivity)
                        }

                        mainViewModel.testCurrentServerRealPing()

                        val currentExpiry = V2rayCrypt.getExpiryTime(this@MainActivity, guid)
                        if (currentExpiry > 0L && NetworkTime.currentTimeMillis(this@MainActivity) > currentExpiry) {
                            withContext(Dispatchers.IO) {
                                if (idToTrack.isNotEmpty()) {
                                    CloudflareAPI.sendActiveState(idToTrack, deviceId, true)
                                    val prevCount = V2rayCrypt.getActiveCount(this@MainActivity, guid)
                                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, max(0, prevCount - 1))
                                    lastReportedState = false
                                }
                                delay(1000) 
                            }
                            
                            withContext(Dispatchers.Main) {
                                V2RayServiceManager.stopVService(this@MainActivity)
                                AlertDialog.Builder(this@MainActivity).setTitle("انتهى الاشتراك").setMessage("تم إيقاف المحرك لانتهاء مدة الصلاحية أو إيقافه من قبل الإدارة.").setPositiveButton("حسناً", null).setCancelable(false).show()
                                mainViewModel.reloadServerList()
                            }
                            break 
                        }
                    } catch (e: Exception) {}
                    delay(20000) 
                }
            }
        } else {
            vpnStartTime = 0L 
            pingJob?.cancel()
            activePingJob?.cancel() 
            TrafficMonitorHelper.stopTrafficMonitor()
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            btnGreenConnect?.text = "تشغيل المحرك"
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
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

    private fun startV2Ray() { if (MmkvManager.getSelectServer().isNullOrEmpty()) toast(R.string.title_file_chooser) else V2RayServiceManager.startVService(this) }
    
    fun restartV2Ray() { if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this); lifecycleScope.launch { delay(500); startV2Ray() } }

    fun forceManualSync() {
        showLoadingDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
            val licenseIds = guids.map { V2rayCrypt.getLicenseId(this@MainActivity, it).takeIf { l -> l.isNotEmpty() && l != "LEGACY" } ?: it }
            
            val batchResults = CloudflareAPI.checkAllLiveConfigs(licenseIds)
            
            for (guid in guids) {
                val licenseId = V2rayCrypt.getLicenseId(this@MainActivity, guid).takeIf { l -> l.isNotEmpty() && l != "LEGACY" } ?: guid
                val data = batchResults[licenseId]
                if (data != null) {
                    if (data.first >= 0L) {
                        V2rayCrypt.saveExpiryTime(this@MainActivity, guid, data.first)
                    }
                    V2rayCrypt.saveActiveCount(this@MainActivity, guid, data.second)
                }
            }
            withContext(Dispatchers.Main) { 
                mainViewModel.reloadServerList()
                hideLoadingDialog()
                toastSuccess("تم التحديث بنجاح!")
            }
        }
    }

    private fun setupViewModel() { 
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning -> applyRunningState(false, isRunning) }
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
        
        // تم الإيقاف لمنع استهلاك الطلبات المتكرر
        // forceManualSync()
    }

    override fun onPause() { 
        super.onPause()
        TrafficMonitorHelper.stopTrafficMonitor()
        SpeedTestHelper.cancelJobs()
        VpnEngineHelper.cancelAllJobs() 
    }
    
    override fun onDestroy() { 
        val guid = MmkvManager.getSelectServer().orEmpty()
        val idToTrack = V2rayCrypt.getLicenseId(this, guid).takeIf { it.isNotEmpty() && it != "LEGACY" } ?: guid
        if (lastReportedState == true && idToTrack.isNotEmpty()) {
            lastReportedState = false
            val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) { CloudflareAPI.sendActiveState(idToTrack, deviceId, true) }
        }
        tabMediator?.detach(); VpnEngineHelper.cancelAllJobs(); TrafficMonitorHelper.stopTrafficMonitor(); SpeedTestHelper.cancelJobs(); pingJob?.cancel(); activePingJob?.cancel(); super.onDestroy() 
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_main, menu); (menu.findItem(R.id.search_view)?.actionView as? SearchView)?.apply { setOnQueryTextListener(object : SearchView.OnQueryTextListener { override fun onQueryTextSubmit(q: String?) = false; override fun onQueryTextChange(t: String?) = false.also { mainViewModel.filterConfig(t.orEmpty()) } }) }; return super.onCreateOptionsMenu(menu) }
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) { R.id.import_qrcode -> { ImportHelper.showAddBottomSheet(this, mainViewModel, { openLocalFileLauncher.launch(arrayOf("*/*")) }, { openEncryptedFileLauncher.launch(arrayOf("*/*")) }); true } else -> super.onOptionsItemSelected(item) }
    override fun onNavigationItemSelected(item: MenuItem): Boolean { binding.drawerLayout.closeDrawer(GravityCompat.START); return true }
    
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) { if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { ImportHelper.importEncryptedContentFromUri(this, mainViewModel, it) } }

    fun openSubscribersPanel(parentGuid: String) { startActivity(Intent(this, SubscribersActivity::class.java).putExtra("parentGuid", parentGuid)) }
    fun showExtendLicenseDialog(guid: String) { AdminHelper.showExtendLicenseDialog(this, guid, { mainViewModel.reloadServerList() }, { showLoadingDialog() }, { hideLoadingDialog() }) }
    fun replaceAndSyncConfigFromClipboard(guid: String) { AdminHelper.replaceAndSyncConfigFromClipboard(this, guid, mainViewModel.subscriptionId, { mainViewModel.reloadServerList() }, { showLoadingDialog() }, { hideLoadingDialog() }) }

    override fun onSelectServer(guid: String) { MmkvManager.setSelectServer(guid); toast(R.string.toast_success); groupPagerAdapter.notifyDataSetChanged() }
    override fun onEdit(guid: String, pos: Int, p: ProfileItem) { if (!V2rayCrypt.isProtected(this, guid) || V2rayCrypt.isAdmin(this, guid)) startActivity(Intent(this, ServerActivity::class.java).putExtra("guid", guid)) else toast("هذا السيرفر محمي") }
    override fun onRemove(guid: String, pos: Int) { AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ -> mainViewModel.removeServer(guid) }.setNegativeButton(android.R.string.cancel, null).show() }
    override fun onShare(guid: String, p: ProfileItem, pos: Int, isMore: Boolean) {} override fun onEdit(guid: String, pos: Int) {} override fun onShare(url: String) {} override fun onRefreshData() {}
}
