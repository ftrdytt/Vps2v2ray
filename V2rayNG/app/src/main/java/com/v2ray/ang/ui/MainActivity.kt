package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.delay
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
            
            // 1. الإعدادات
            val settingsWrapper = binding.root.findViewById<View>(R.id.settings_wrapper)
            settingsWrapper?.layoutParams?.width = screenWidth
            
            // 2. إنشاء حاويات التحديثات والملف الشخصي
            val updatesWrapper = FrameLayout(this).apply { 
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
            }
            
            val profileWrapper = FrameLayout(this).apply { 
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT) 
            }
            
            // إضافتهم بالترتيب الصحيح ليتجاوروا أفقياً
            val scrollContainer = settingsWrapper?.parent as? LinearLayout
            if (scrollContainer != null) {
                // التأكد من أن الترتيب أفقي (وهذا هو الحل السحري لمشكلة التداخل!)
                scrollContainer.orientation = LinearLayout.HORIZONTAL
                
                scrollContainer.addView(updatesWrapper, 1)
                scrollContainer.addView(profileWrapper, 2)
                
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_fragment_container, SettingsActivity.SettingsFragment())
                    .replace(updatesWrapper.id, UpdatesFragment())
                    .replace(profileWrapper.id, ProfileFragment())
                    .commitAllowingStateLoss()
            }
            
            // 3. قائمة السيرفرات
            binding.homeContentContainer.layoutParams.width = screenWidth
            
            // 4. الشاشة الرئيسية (عدادات السرعة)
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
            
            binding.layoutTest.setOnClickListener { if (mainViewModel.isRunning.value == true) { setTestState(getString(R.string.connection_test_testing)); mainViewModel.testCurrentServerRealPing() } }
            
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
        if (UpdateManager.isUpdateReady && UpdateManager.readyApkFile != null) return UpdateManager.showMandatoryUpdateDialog(this, UpdateManager.readyApkFile!!)
        VpnEngineHelper.applyRunningState(this, mainViewModel, true, false)
        if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this) else if (SettingsManager.isVpnMode()) VpnService.prepare(this)?.let { requestVpnPermission.launch(it) } ?: startV2Ray() else startV2Ray()
    }

    private fun startV2Ray() { if (MmkvManager.getSelectServer().isNullOrEmpty()) toast(R.string.title_file_chooser) else V2RayServiceManager.startVService(this) }
    
    fun restartV2Ray() { if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this); lifecycleScope.launch { delay(500); startV2Ray() } }

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

    private fun setupViewModel() { mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }; mainViewModel.isRunning.observe(this) { isRunning -> VpnEngineHelper.applyRunningState(this, mainViewModel, false, isRunning) }; mainViewModel.startListenBroadcast(); mainViewModel.initAssets(assets) }
    
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

    private fun setTestState(content: String?) { binding.tvTestState.text = content; val tvPing = binding.root.findViewById<TextView>(R.id.tv_green_ping); if (content?.contains("ms", true) == true) tvPing?.text = content else if (content?.contains("Timeout", true) == true) tvPing?.text = "Timeout" else if (content == getString(R.string.connection_connected)) tvPing?.text = "متصل" }

    override fun onResume() { super.onResume(); if (mainViewModel.isRunning.value == true) TrafficMonitorHelper.startTrafficMonitor(this) else TrafficMonitorHelper.updateTrafficDisplay(this); VpnEngineHelper.startLiveUpdates(this, mainViewModel); if (UpdateManager.isUpdateReady && UpdateManager.readyApkFile != null) UpdateManager.showMandatoryUpdateDialog(this, UpdateManager.readyApkFile!!) }
    override fun onPause() { super.onPause(); TrafficMonitorHelper.stopTrafficMonitor(); SpeedTestHelper.cancelJobs() }
    override fun onDestroy() { tabMediator?.detach(); VpnEngineHelper.cancelAllJobs(); TrafficMonitorHelper.stopTrafficMonitor(); SpeedTestHelper.cancelJobs(); super.onDestroy() }

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
