package com.v2ray.ang.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    
    private var pingJob: Job? = null
    private var trafficJob: Job? = null
    
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var isFirstTrafficRead: Boolean = true

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }

    private val openEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readEncryptedContentFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        
        handleIntent(intent)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        binding.homeContentContainer.layoutParams.width = screenWidth
        binding.greenScreenContainer.layoutParams.width = screenWidth

        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        btnGreenConnect?.setOnClickListener {
            handleFabAction()
        }

        val cardTrafficMeter = binding.root.findViewById<CardView>(R.id.card_traffic_meter)
        cardTrafficMeter?.setOnClickListener {
            showTrafficDetailsDialog()
        }
        
        updateTrafficDisplay()

        binding.mainScrollView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val scrollX = binding.mainScrollView.scrollX
                val halfScreen = screenWidth / 2
                if (scrollX > halfScreen) {
                    binding.mainScrollView.post { binding.mainScrollView.smoothScrollTo(screenWidth, 0) }
                } else {
                    binding.mainScrollView.post { binding.mainScrollView.smoothScrollTo(0, 0) }
                }
                return@setOnTouchListener true
            }
            false
        }

        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    if (binding.mainScrollView.scrollX > 0) {
                         binding.mainScrollView.smoothScrollTo(0, 0)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }
    
    private fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0.00 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ENGLISH, "%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ENGLISH, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.ENGLISH, "%.2f GB", gb)
    }

    private fun updateTrafficDisplay() {
        val prefs = getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
        val savedRx = prefs.getLong("rx", 0L)
        val savedTx = prefs.getLong("tx", 0L)
        val total = savedRx + savedTx
        
        val tvTotalTraffic = binding.root.findViewById<TextView>(R.id.tv_total_traffic)
        tvTotalTraffic?.text = formatTraffic(total)
    }

    private fun startTrafficMonitor() {
        isFirstTrafficRead = true
        trafficJob?.cancel()
        
        trafficJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val currentRxBytes = TrafficStats.getTotalRxBytes()
                    val currentTxBytes = TrafficStats.getTotalTxBytes()

                    if (currentRxBytes == TrafficStats.UNSUPPORTED.toLong() || currentTxBytes == TrafficStats.UNSUPPORTED.toLong()) {
                        delay(1000)
                        continue
                    }

                    if (isFirstTrafficRead) {
                        lastRxBytes = currentRxBytes
                        lastTxBytes = currentTxBytes
                        isFirstTrafficRead = false
                    } else {
                        val diffRx = currentRxBytes - lastRxBytes
                        val diffTx = currentTxBytes - lastTxBytes
                        
                        if (diffRx > 0 || diffTx > 0) {
                            val prefs = getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
                            val oldRx = prefs.getLong("rx", 0L)
                            val oldTx = prefs.getLong("tx", 0L)
                            
                            prefs.edit()
                                .putLong("rx", oldRx + diffRx)
                                .putLong("tx", oldTx + diffTx)
                                .apply()
                                
                            lastRxBytes = currentRxBytes
                            lastTxBytes = currentTxBytes

                            withContext(Dispatchers.Main) {
                                updateTrafficDisplay()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Traffic monitor error", e)
                }
                delay(1000)
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        isFirstTrafficRead = true
    }

    private fun showTrafficDetailsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_traffic_stats)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvDownload = dialog.findViewById<TextView>(R.id.tv_download_stat)
        val tvUpload = dialog.findViewById<TextView>(R.id.tv_upload_stat)
        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_dialog)
        val btnReset = dialog.findViewById<MaterialButton>(R.id.btn_reset_stats)

        val prefs = getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
        
        fun refreshDialogData() {
            val rx = prefs.getLong("rx", 0L)
            val tx = prefs.getLong("tx", 0L)
            tvDownload.text = formatTraffic(rx)
            tvUpload.text = formatTraffic(tx)
        }
        
        refreshDialogData()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            prefs.edit().putLong("rx", 0L).putLong("tx", 0L).apply()
            
            if (mainViewModel.isRunning.value == true) {
                lastRxBytes = TrafficStats.getTotalRxBytes()
                lastTxBytes = TrafficStats.getTotalTxBytes()
            }
            
            refreshDialogData()
            updateTrafficDisplay()
            toast("تم تصفير الاستهلاك بنجاح!")
        }

        dialog.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                readEncryptedContentFromUri(uri)
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    // =========================================================================
    // التعديل الخرافي: الفلتر الذكي لقراءة البنق (Ping) من أي نص معقد!
    // =========================================================================
    private fun setTestState(content: String?) {
        // 1. تحديث النص أسفل الشاشة (مثل: نجاح: استغرق اتصال 141ms HTTP)
        binding.tvTestState.text = content
        
        val gaugePing = binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)
        
        if (content.isNullOrEmpty()) return

        try {
            // تحويل الأرقام العربية (١٢٣) إلى إنجليزية (123) في حال كان هاتف المستخدم باللغة العربية
            val normalizedContent = content
                .replace("٠", "0").replace("١", "1").replace("٢", "2")
                .replace("٣", "3").replace("٤", "4").replace("٥", "5")
                .replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9")

            // 2. إذا احتوى النص على كلمة ms أو م.ث، نقوم باصطياد الرقم الذي قبلها مباشرة
            if (normalizedContent.contains("ms", ignoreCase = true) || normalizedContent.contains("م.ث")) {
                
                // هذا الكود (Regex) يبحث عن أي رقم متصل بكلمة ms 
                val match = Regex("(\\d+)\\s*(ms|م\\.ث)", RegexOption.IGNORE_CASE).find(normalizedContent)
                
                if (match != null) {
                    val pingValue = match.groupValues[1].toFloat()
                    gaugePing?.setPing(pingValue) // تحريك الإبرة للرقم!
                } else {
                    // إذا لم تلتصق الكلمة بالرقم، نأخذ أول رقم نجده في الجملة
                    val fallbackMatch = Regex("(\\d+)").find(normalizedContent)
                    if (fallbackMatch != null) {
                        gaugePing?.setPing(fallbackMatch.value.toFloat())
                    }
                }
            } 
            // 3. في حالة فشل البنق (Timeout)
            else if (normalizedContent.contains("Timeout", ignoreCase = true) || 
                     normalizedContent.contains("Failed", ignoreCase = true) ||
                     normalizedContent.contains("فشل", ignoreCase = true)) {
                gaugePing?.setPing(500f) // صعود الإبرة للون الأحمر
            } 
            // 4. تصفير العداد فقط إذا كان النص "متصل" ولم يبدأ الفحص بعد
            else if (normalizedContent == getString(R.string.connection_connected)) {
                gaugePing?.setPing(0f)
            }
            // ملاحظة: تم تجاهل الكلمات الأخرى مثل "Testing..." لكي لا تنزل الإبرة للصفر فجأة.
            
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error parsing ping", e)
        }
    }
    // =========================================================================

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val lottieEngine = binding.root.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnGreenConnect = binding.root.findViewById<MaterialButton>(R.id.btn_green_connect)
        val gaugePing = binding.root.findViewById<PingGaugeView>(R.id.gauge_ping)

        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            btnGreenConnect?.text = "جاري تشغيل المحرك..."
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00"))
            gaugePing?.setPing(0f)
            lottieEngine?.playAnimation()
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            
            btnGreenConnect?.text = "إيقاف المحرك"
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            lottieEngine?.playAnimation()
            
            startTrafficMonitor()
            
            // ============================================
            // حلقة الفحص المستمر للبنق (بدون كراش)
            // ============================================
            pingJob?.cancel()
            pingJob = lifecycleScope.launch {
                delay(2000) // انتظار ثانيتين لكي يكتمل الاتصال ولا ينهار التطبيق
                
                while (true) {
                    try {
                        mainViewModel.testCurrentServerRealPing()
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Ping Error", e)
                    }
                    delay(1500) // يتم الفحص كل ثانية ونصف، سرعة مثالية للعداد ولا تخنق السيرفر
                }
            }
            
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false

            btnGreenConnect?.text = "تشغيل المحرك"
            btnGreenConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
            
            lottieEngine?.cancelAnimation()
            lottieEngine?.progress = 0f
            
            stopTrafficMonitor()
            
            pingJob?.cancel()
            gaugePing?.setPing(0f) 
        }
    }

    override fun onResume() {
        super.onResume()
        if (mainViewModel.isRunning.value == true) {
            startTrafficMonitor()
        } else {
            updateTrafficDisplay()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTrafficMonitor()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> { importQRcode(); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_clipboard_encrypted -> { importClipboardEncrypted(); true }
        R.id.import_encrypted_file -> { importEncryptedFile(); true }
        R.id.import_local -> { importConfigLocal(); true }
        R.id.import_manually_policy_group -> { importManually(EConfigType.POLICYGROUP.value); true }
        R.id.import_manually_vmess -> { importManually(EConfigType.VMESS.value); true }
        R.id.import_manually_vless -> { importManually(EConfigType.VLESS.value); true }
        R.id.import_manually_ss -> { importManually(EConfigType.SHADOWSOCKS.value); true }
        R.id.import_manually_socks -> { importManually(EConfigType.SOCKS.value); true }
        R.id.import_manually_http -> { importManually(EConfigType.HTTP.value); true }
        R.id.import_manually_trojan -> { importManually(EConfigType.TROJAN.value); true }
        R.id.import_manually_wireguard -> { importManually(EConfigType.WIREGUARD.value); true }
        R.id.import_manually_hysteria2 -> { importManually(EConfigType.HYSTERIA2.value); true }
        R.id.export_all -> { exportAll(); true }
        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }
        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }
        R.id.service_restart -> { restartV2Ray(); true }
        R.id.del_all_config -> { delAllConfig(); true }
        R.id.del_duplicate_config -> { delDuplicateConfig(); true }
        R.id.del_invalid_config -> { delInvalidConfig(); true }
        R.id.sort_by_test_results -> { sortByTestResults(); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(Intent().putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerGroupActivity::class.java))
        } else {
            startActivity(Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java))
        }
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult -> if (scanResult != null) importBatchConfig(scanResult) }
        return true
    }

    private fun importClipboard(): Boolean {
        try { importBatchConfig(Utils.getClipboard(this)) } 
        catch (e: Exception) { Log.e(AppConfig.TAG, "Failed to import config from clipboard", e); return false }
        return true
    }

    private fun importClipboardEncrypted(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            if (clipboard.isNullOrEmpty()) {
                toast("الحافظة فارغة!")
                return false
            }
            val decrypted = V2rayCrypt.decrypt(clipboard)
            if (decrypted.isEmpty()) {
                toast("الكود المشفر غير صالح أو غير مدعوم!")
                return false
            }
            importEncryptedBatchConfig(decrypted)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import encrypted config from clipboard", e)
            return false
        }
        return true
    }
    
    private fun importEncryptedFile() {
        try {
            openEncryptedFileLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to open file picker", e)
        }
    }
    
    private fun readEncryptedContentFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val fileContent = reader.readText()
            reader.close()
            
            if (fileContent.isEmpty()) {
                toast("الملف فارغ!")
                return
            }
            
            val decrypted = V2rayCrypt.decrypt(fileContent)
            if (decrypted.isEmpty()) {
                toast("الملف غير صالح أو ليس ملف .ashor صحيح!")
                return
            }
            
            importEncryptedBatchConfig(decrypted)
            
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read encrypted content from URI", e)
            toast("حدث خطأ أثناء قراءة الملف.")
        }
    }

    private fun importEncryptedBatchConfig(server: String?) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val beforeGuids = MmkvManager.decodeServerList()?.toSet() ?: emptySet()
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)

                if (count > 0) { 
                    val afterGuids = MmkvManager.decodeServerList()?.toSet() ?: emptySet()
                    val newGuids = afterGuids - beforeGuids
                    
                    if (newGuids.isNotEmpty()) {
                        V2rayCrypt.addProtectedGuids(this@MainActivity, newGuids)
                    }

                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast("تم استيراد الملف بنجاح!")
                        hideLoading()
                    }
                } else if (countSub > 0) {
                    withContext(Dispatchers.Main) {
                        setupGroupTab()
                        hideLoading()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_failure)
                        hideLoading()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    toastError(R.string.toast_failure)
                    hideLoading() 
                }
                Log.e(AppConfig.TAG, "Failed to import encrypted batch config", e)
            }
        }
    }

    private fun importBatchConfig(server: String?) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> { toast(getString(R.string.title_import_config_count, count)); mainViewModel.reloadServerList() }
                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure); hideLoading() }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    private fun importConfigLocal(): Boolean {
        try { showFileChooser() } catch (e: Exception) { Log.e(AppConfig.TAG, "Failed to import config from local file", e); return false }
        return true
    }

    private fun importConfigViaSub(): Boolean {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) { toast(getString(R.string.title_update_config_count, count)); mainViewModel.reloadServerList() } 
                else { toastError(R.string.toast_failure) }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0) toast(getString(R.string.title_export_config_count, ret)) else toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); hideLoading() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_duplicate_config_count, ret)); hideLoading() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); hideLoading() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) { mainViewModel.reloadServerList(); hideLoading() }
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri -> if (uri != null) readContentFromUri(uri) }
    }

    private fun readContentFromUri(uri: Uri) {
        try { contentResolver.openInputStream(uri).use { input -> importBatchConfig(input?.bufferedReader()?.readText()) } } 
        catch (e: Exception) { Log.e(AppConfig.TAG, "Failed to read content from URI", e) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) { moveTaskToBack(false); return true }
        return super.onKeyDown(keyCode, event)
    }

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

    override fun onDestroy() {
        tabMediator?.detach()
        pingJob?.cancel() 
        trafficJob?.cancel()
        super.onDestroy()
    }
}

// =======================================================
// خوارزمية التشفير والقائمة السرية لحماية السيرفرات 
// =======================================================
object V2rayCrypt {
    private const val SECRET_KEY = "DarkTunlKey12345" 
    private const val PREFS_NAME = "V2rayProtectedConfigs"
    private const val KEY_GUIDS = "ProtectedGuids"

    fun encrypt(data: String): String {
        return try {
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(data.toByteArray())
            "ENC://" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decrypt(data: String): String {
        return try {
            if (!data.startsWith("ENC://")) return ""
            val actualData = data.replace("ENC://", "")
            val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decryptedBytes = cipher.doFinal(Base64.decode(actualData, Base64.NO_WRAP))
            String(decryptedBytes)
        } catch (e: Exception) {
            ""
        }
    }

    fun addProtectedGuids(context: Context, newGuids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GUIDS, mutableSetOf()) ?: mutableSetOf()
        val updated = current.toMutableSet().apply { addAll(newGuids) }
        prefs.edit().putStringSet(KEY_GUIDS, updated).apply()
    }

    fun isProtected(context: Context, guid: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_GUIDS, emptySet()) ?: emptySet()
        return current.contains(guid)
    }
}
