package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentGroupServerBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NetworkTime
import com.v2ray.ang.handler.V2rayCrypt
import com.v2ray.ang.handler.CloudflareAPI
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>() {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

    private var pendingEncryptedConfigToSave: String? = null

    private val saveEncryptedFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = pendingEncryptedConfigToSave
                if (!content.isNullOrEmpty()) {
                    ownerActivity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    ownerActivity.toast("تم حفظ الملف بنجاح!")
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                ownerActivity.toastError(R.string.toast_failure)
            }
        }
        pendingEncryptedConfigToSave = null
    }

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        adapter = MainRecyclerAdapter(mainViewModel, ActivityAdapterListener())
        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false))
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        // تفعيل ميزة السحب للتحديث وإيقاف الأنيميشن عند الانتهاء
        binding.swipeRefresh.setColorSchemeColors(Color.parseColor("#4CAF50"))
        binding.swipeRefresh.setOnRefreshListener {
            ownerActivity.forceManualSync()
            binding.swipeRefresh.postDelayed({
                binding.swipeRefresh.isRefreshing = false
            }, 1500) // نوقف دائرة التحميل بعد ثانية ونصف
        }

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { index ->
            if (mainViewModel.subscriptionId != subId) {
                return@observe
            }
            adapter.setData(mainViewModel.serversCache, index)
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.subscriptionIdChanged(subId)
    }

    private fun shareServer(guid: String, profile: ProfileItem, position: Int) {
        val isProtected = V2rayCrypt.isProtected(requireContext(), guid)
        val isAdmin = V2rayCrypt.isAdmin(requireContext(), guid)

        val bottomSheetDialog = BottomSheetDialog(ownerActivity)
        
        val scrollView = ScrollView(ownerActivity)
        val container = LinearLayout(ownerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141417")) 
            setPadding(0, 0, 0, 40)
        }
        scrollView.addView(container)

        val title = TextView(ownerActivity).apply {
            text = "خيارات الإدارة والمشاركة"
            textSize = 18f
            setTextColor(Color.parseColor("#FF9800")) 
            setPadding(40, 40, 40, 20)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(title)
        
        val divider = View(ownerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(40, 0, 40, 20)
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        container.addView(divider)

        fun createOptionButton(textStr: String, iconRes: Int, onClick: () -> Unit) {
            val layout = LinearLayout(ownerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(50, 30, 50, 30)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                val outValue = TypedValue()
                ownerActivity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                
                setOnClickListener {
                    onClick()
                    bottomSheetDialog.dismiss()
                }
            }

            val icon = ImageView(ownerActivity).apply {
                setImageResource(iconRes)
                setColorFilter(Color.parseColor("#FF9800"))
                layoutParams = LinearLayout.LayoutParams(56, 56)
            }

            val textView = TextView(ownerActivity).apply {
                text = textStr
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 40
                }
            }

            layout.addView(icon)
            layout.addView(textView)
            container.addView(layout)
        }

        if (!isProtected || isAdmin) {
            createOptionButton("إضافة مشتركين", android.R.drawable.ic_menu_add) {
                showAddSubscriberDialog(guid, profile)
            }
            
            createOptionButton("تصدير إلى ملف مشفر (.ashor)", android.R.drawable.ic_menu_save) {
                exportEncryptedFile(guid)
            }
            
            createOptionButton("تصدير إلى الحافظة مشفر", android.R.drawable.ic_lock_idle_lock) {
                shareEncryptedClipboard(guid)
            }

            val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP
            
            createOptionButton("نسخ التكوين العادي للحافظة", android.R.drawable.ic_menu_edit) {
                share2Clipboard(guid)
            }
            
            if (!isCustom) {
                createOptionButton("نسخ التكوين الكامل للحافظة", android.R.drawable.ic_menu_share) {
                    shareFullContent(guid)
                }
            }
            
            createOptionButton("حذف التكوين", android.R.drawable.ic_menu_delete) {
                removeServer(guid, position)
            }
        } else {
            createOptionButton("حذف التكوين", android.R.drawable.ic_menu_delete) {
                removeServer(guid, position)
            }
        }

        bottomSheetDialog.setContentView(scrollView)
        bottomSheetDialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        bottomSheetDialog.show()
    }

    private fun showAddSubscriberDialog(parentGuid: String, profile: ProfileItem) {
        val layout = LinearLayout(ownerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val titleView = TextView(ownerActivity).apply {
            text = "إضافة مشترك جديد"
            textSize = 18f
            setTextColor(Color.parseColor("#FF9800"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        val nameInput = EditText(ownerActivity).apply {
            hint = "اسم المشترك (مثال: علي محمد)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setText("مشترك_" + (1000..9999).random())
        }
        layout.addView(nameInput)

        val monthsInput = EditText(ownerActivity).apply { hint = "عدد الأشهر (مثال: 1)"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(monthsInput)

        val daysInput = EditText(ownerActivity).apply { hint = "عدد الأيام (مثال: 15)"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(daysInput)

        val hoursInput = EditText(ownerActivity).apply { hint = "عدد الساعات (مثال: 12)"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(hoursInput)

        val builder = AlertDialog.Builder(ownerActivity)
        builder.setView(layout)
        builder.setPositiveButton("حفظ المشترك") { dialog, _ ->
            val subName = nameInput.text.toString().trim()
            val m = monthsInput.text.toString().toLongOrNull() ?: 0L
            val d = daysInput.text.toString().toLongOrNull() ?: 0L
            val h = hoursInput.text.toString().toLongOrNull() ?: 0L

            val totalDurationMs = (m * 30L * 24L * 60L * 60L * 1000L) + (d * 24L * 60L * 60L * 1000L) + (h * 60L * 60L * 1000L)

            if (subName.isEmpty()) {
                ownerActivity.toastError("يجب إدخال اسم المشترك")
            } else if (totalDurationMs <= 0L) {
                ownerActivity.toastError("الرجاء إدخال مدة صحيحة")
            } else {
                val expiryTimeMs = NetworkTime.currentTimeMillis(ownerActivity) + totalDurationMs
                val licenseId = "LIC_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                
                if (AngConfigManager.share2Clipboard(ownerActivity, parentGuid) == 0) {
                    val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    
                    if (conf.isNotEmpty()) {
                        ownerActivity.showLoadingDialog()
                        ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
                            val uploaded = CloudflareAPI.createOrUpdateSubscriber(licenseId, expiryTimeMs, conf)
                            withContext(Dispatchers.Main) {
                                ownerActivity.hideLoadingDialog()
                                if (uploaded) {
                                    V2rayCrypt.saveSubscriberLocally(ownerActivity, parentGuid, licenseId, subName, expiryTimeMs)
                                    ownerActivity.toastSuccess("تم إضافة المشترك بنجاح!")
                                    askToShareSubscriberCode(conf, expiryTimeMs, licenseId, subName)
                                } else {
                                    ownerActivity.toastError("فشل الاتصال بكلاود فلير.")
                                }
                            }
                        }
                    }
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("إلغاء") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun askToShareSubscriberCode(conf: String, expiryTimeMs: Long, licenseId: String, subName: String) {
        val encryptedConf = V2rayCrypt.encrypt(conf, expiryTimeMs, licenseId)
        if (encryptedConf.isNotEmpty()) {
            val options = arrayOf("نسخ الكود إلى الحافظة", "حفظ كملف (.ashor)")
            AlertDialog.Builder(ownerActivity)
                .setTitle("مشاركة المشترك ($subName)")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Encrypted V2ray Config", encryptedConf)
                            clipboard.setPrimaryClip(clip)
                            ownerActivity.toastSuccess("تم نسخ الكود وجاهز للإرسال!")
                        }
                        1 -> {
                            pendingEncryptedConfigToSave = encryptedConf
                            val fileName = "${subName.replace(" ", "_")}.ashor"
                            saveEncryptedFileLauncher.launch(fileName)
                        }
                    }
                }
                .show()
        }
    }

    private fun showCustomExpiryDialog(onExpirySelected: (Long) -> Unit) {
        val layout = LinearLayout(ownerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val titleView = TextView(ownerActivity).apply { text = "أدخل مدة الصلاحية"; textSize = 18f; setTextColor(Color.parseColor("#FF9800")); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 30); gravity = Gravity.CENTER }
        layout.addView(titleView)

        val monthsInput = EditText(ownerActivity).apply { hint = "عدد الأشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        val daysInput = EditText(ownerActivity).apply { hint = "عدد الأيام"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        val hoursInput = EditText(ownerActivity).apply { hint = "عدد الساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setHintTextColor(Color.GRAY); setTextColor(Color.BLACK) }
        layout.addView(monthsInput); layout.addView(daysInput); layout.addView(hoursInput)

        val builder = AlertDialog.Builder(ownerActivity)
        builder.setView(layout)
        builder.setPositiveButton("تأكيد") { dialog, _ ->
            val totalDurationMs = ((monthsInput.text.toString().toLongOrNull() ?: 0L) * 30L * 24L * 60L * 60L * 1000L) + ((daysInput.text.toString().toLongOrNull() ?: 0L) * 24L * 60L * 60L * 1000L) + ((hoursInput.text.toString().toLongOrNull() ?: 0L) * 60L * 60L * 1000L)
            if (totalDurationMs > 0L) {
                val expiryTimeMs = NetworkTime.currentTimeMillis(ownerActivity) + totalDurationMs
                onExpirySelected(expiryTimeMs)
            } else ownerActivity.toastError("الرجاء إدخال مدة صحيحة")
            dialog.dismiss()
        }
        builder.setNegativeButton("إلغاء") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun exportEncryptedFile(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) != 0) { ownerActivity.toastError(R.string.toast_failure); return }
        try {
            val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (conf.isNullOrEmpty()) { ownerActivity.toastError(R.string.toast_failure); return }

            showCustomExpiryDialog { expiryTime ->
                val licenseId = "LIC_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                ownerActivity.showLoadingDialog()
                ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
                    val uploaded = CloudflareAPI.createOrUpdateSubscriber(licenseId, expiryTime, conf)
                    withContext(Dispatchers.Main) {
                        ownerActivity.hideLoadingDialog()
                        if (uploaded) {
                            V2rayCrypt.addAdminGuid(ownerActivity, guid)
                            V2rayCrypt.saveLicenseId(ownerActivity, guid, licenseId)
                            V2rayCrypt.saveExpiryTime(ownerActivity, guid, expiryTime)
                            
                            val encryptedConf = V2rayCrypt.encrypt(conf, expiryTime, licenseId)
                            if (encryptedConf.isNotEmpty()) {
                                pendingEncryptedConfigToSave = encryptedConf
                                val fileName = "Config_${NetworkTime.currentTimeMillis(ownerActivity)}.ashor"
                                saveEncryptedFileLauncher.launch(fileName)
                                mainViewModel.reloadServerList() 
                            } else ownerActivity.toastError(R.string.toast_failure)
                        } else ownerActivity.toastError("فشل الاتصال بكلاود فلير.")
                    }
                }
            }
        } catch (e: Exception) { ownerActivity.toastError(R.string.toast_failure) }
    }

    private fun shareEncryptedClipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) != 0) { ownerActivity.toastError(R.string.toast_failure); return }
        try {
            val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (conf.isNullOrEmpty()) { ownerActivity.toastError(R.string.toast_failure); return }

            showCustomExpiryDialog { expiryTime ->
                val licenseId = "LIC_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                ownerActivity.showLoadingDialog()
                ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
                    val uploaded = CloudflareAPI.createOrUpdateSubscriber(licenseId, expiryTime, conf)
                    withContext(Dispatchers.Main) {
                        ownerActivity.hideLoadingDialog()
                        if (uploaded) {
                            V2rayCrypt.addAdminGuid(ownerActivity, guid)
                            V2rayCrypt.saveLicenseId(ownerActivity, guid, licenseId)
                            V2rayCrypt.saveExpiryTime(ownerActivity, guid, expiryTime)

                            val encryptedConf = V2rayCrypt.encrypt(conf, expiryTime, licenseId)
                            if (encryptedConf.isNotEmpty()) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Encrypted V2ray Config", encryptedConf))
                                ownerActivity.toast("تم نسخ التكوين المشفر!")
                                mainViewModel.reloadServerList()
                            } else ownerActivity.toastError(R.string.toast_failure)
                        } else ownerActivity.toastError("فشل الاتصال بكلاود فلير.")
                    }
                }
            }
        } catch (e: Exception) { ownerActivity.toastError(R.string.toast_failure) }
    }

    private fun share2Clipboard(guid: String) { if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) ownerActivity.toastSuccess(R.string.toast_success) else ownerActivity.toastError(R.string.toast_failure) }

    private fun shareFullContent(guid: String) {
        ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            launch(Dispatchers.Main) { if (result == 0) ownerActivity.toastSuccess(R.string.toast_success) else ownerActivity.toastError(R.string.toast_failure) }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent().putExtra("guid", guid).putExtra("isRunning", mainViewModel.isRunning.value).putExtra("createConfigType", profile.configType.value)
        when (profile.configType) {
            EConfigType.CUSTOM -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerCustomConfigActivity::class.java))
            EConfigType.POLICYGROUP -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerGroupActivity::class.java))
            else -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerActivity::class.java))
        }
    }

    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) { ownerActivity.toast(R.string.toast_action_not_allowed); return }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ -> removeServerSub(guid, position) }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
        } else removeServerSub(guid, position)
    }

    private fun removeServerSub(guid: String, position: Int) { ownerActivity.mainViewModel.removeServer(guid); adapter.removeServerSub(guid, position) }

    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            val fromPosition = mainViewModel.getPosition(selected.orEmpty()); val toPosition = mainViewModel.getPosition(guid)
            adapter.setSelectServer(fromPosition, toPosition)
            if (mainViewModel.isRunning.value == true) ownerActivity.restartV2Ray()
        }
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {}
        override fun onShare(url: String) {}
        override fun onRefreshData() {}
        override fun onRemove(guid: String, position: Int) { removeServer(guid, position) }
        override fun onEdit(guid: String, position: Int, profile: ProfileItem) { editServer(guid, profile) }
        override fun onSelectServer(guid: String) { setSelectServer(guid) }
        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) { shareServer(guid, profile, position) }
    }
}
