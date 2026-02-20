package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentGroupServerBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>() {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

    // متغير لحفظ النص المشفر مؤقتاً حتى يختار المستخدم مكان الحفظ
    private var pendingEncryptedConfigToSave: String? = null

    // نافذة حفظ الملف (تفتح مدير الملفات ليختار المستخدم المكان)
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
                Log.e(AppConfig.TAG, "Failed to write file", e)
            }
        }
        pendingEncryptedConfigToSave = null // تفريغ المتغير بعد الانتهاء
    }

    private val share_method: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method)
    }
    private val share_method_more: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method_more)
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

    /**
     * Shares server configuration
     * Displays a dialog with sharing options and executes the selected action
     */
    private fun shareServer(guid: String, profile: ProfileItem, position: Int, baseOptions: List<String>, skip: Int) {
        val encryptedFileOptionName = "تصدير إلى ملف مشفر (.ashor)"
        val encryptedOptionName = "تصدير إلى الحافظة مشفر"
        
        // ========================================================
        // فحص هل السيرفر محمي أم لا
        // ========================================================
        val isProtected = V2rayCrypt.isProtected(requireContext(), guid)

        val shareOptions: MutableList<String>
        
        if (isProtected) {
            // إذا كان محمياً: إظهار خيارات التشفير فقط
            shareOptions = mutableListOf(encryptedFileOptionName, encryptedOptionName)
        } else {
            // إذا كان عادياً: إظهار كل الخيارات
            shareOptions = baseOptions.toMutableList()
            if (profile.configType != EConfigType.CUSTOM && profile.configType != EConfigType.POLICYGROUP) {
                shareOptions.add(1, encryptedFileOptionName)
                shareOptions.add(2, encryptedOptionName)
            }
        }
        // ========================================================

        AlertDialog.Builder(ownerActivity).setItems(shareOptions.toTypedArray()) { _, index ->
            try {
                val selectedItem = shareOptions[index]
                
                when (selectedItem) {
                    encryptedFileOptionName -> exportEncryptedFile(guid)
                    encryptedOptionName -> shareEncryptedClipboard(guid)
                    else -> {
                        // يتم تنفيذ هذا الجزء فقط إذا كان السيرفر غير محمي (عادي)
                        val originalIndex = baseOptions.indexOf(selectedItem)
                        when (originalIndex + skip) {
                            0 -> showQRCode(guid)
                            1 -> share2Clipboard(guid)
                            2 -> shareFullContent(guid)
                            3 -> editServer(guid, profile)
                            4 -> removeServer(guid, position)
                            else -> ownerActivity.toast("else")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error when sharing server", e)
            }
        }.show()
    }

    // --- دالة تصدير الكونفج إلى ملف (.ashor) مع فتح مدير الملفات ---
    private fun exportEncryptedFile(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) != 0) {
            ownerActivity.toastError(R.string.toast_failure)
            return
        }
        
        try {
            val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            
            if (conf.isNullOrEmpty()) {
                ownerActivity.toastError(R.string.toast_failure)
                return
            }
            
            // تشفير الكونفج
            val encryptedConf = V2rayCrypt.encrypt(conf)
            
            if (encryptedConf.isNotEmpty()) {
                // حفظ النص المشفر في المتغير
                pendingEncryptedConfigToSave = encryptedConf
                
                // تحديد اسم الملف الافتراضي
                val fileName = "Config_${System.currentTimeMillis()}.ashor"
                
                // فتح نافذة اختيار مكان الحفظ
                saveEncryptedFileLauncher.launch(fileName)
            } else {
                ownerActivity.toastError(R.string.toast_failure)
            }
        } catch (e: Exception) {
            ownerActivity.toastError(R.string.toast_failure)
            Log.e(AppConfig.TAG, "Error exporting encrypted file", e)
        }
    }

    // --- دالة نسخ الكونفج المشفر للحافظة ---
    private fun shareEncryptedClipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) != 0) {
            ownerActivity.toastError(R.string.toast_failure)
            return
        }
        
        try {
            val clipboard = ownerActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val conf = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            
            if (conf.isNullOrEmpty()) {
                ownerActivity.toastError(R.string.toast_failure)
                return
            }
            
            val encryptedConf = V2rayCrypt.encrypt(conf)
            
            if (encryptedConf.isNotEmpty()) {
                val clip = ClipData.newPlainText("Encrypted V2ray Config", encryptedConf)
                clipboard.setPrimaryClip(clip)
                ownerActivity.toast("تم نسخ التكوين المشفر بنجاح!")
            } else {
                ownerActivity.toastError(R.string.toast_failure)
            }
        } catch (e: Exception) {
            ownerActivity.toastError(R.string.toast_failure)
            Log.e(AppConfig.TAG, "Error encrypting server", e)
        }
    }

    /**
     * Displays QR code for the server configuration
     */
    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        if (share_method.isNotEmpty()) {
            ivBinding.ivQcode.contentDescription = share_method[0]
        } else {
            ivBinding.ivQcode.contentDescription = "QR Code"
        }
        AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
    }

    /**
     * Shares server configuration to clipboard
     */
    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) {
            ownerActivity.toastSuccess(R.string.toast_success)
        } else {
            ownerActivity.toastError(R.string.toast_failure)
        }
    }

    /**
     * Shares full server configuration content to clipboard
     */
    private fun shareFullContent(guid: String) {
        ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    ownerActivity.toastSuccess(R.string.toast_success)
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Edits server configuration
     */
    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent().putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value)
            .putExtra("createConfigType", profile.configType.value)
        when (profile.configType) {
            EConfigType.CUSTOM -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerCustomConfigActivity::class.java))
            }

            EConfigType.POLICYGROUP -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerGroupActivity::class.java))
            }

            else -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerActivity::class.java))
            }
        }
    }

    /**
     * Removes server configuration
     */
    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) {
            ownerActivity.toast(R.string.toast_action_not_allowed)
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    removeServerSub(guid, position)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    //do noting
                }
                .show()
        } else {
            removeServerSub(guid, position)
        }
    }

    private fun removeServerSub(guid: String, position: Int) {
        ownerActivity.mainViewModel.removeServer(guid)
        adapter.removeServerSub(guid, position)
    }

    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            val fromPosition = mainViewModel.getPosition(selected.orEmpty())
            val toPosition = mainViewModel.getPosition(guid)
            adapter.setSelectServer(fromPosition, toPosition)

            if (mainViewModel.isRunning.value == true) {
                ownerActivity.restartV2Ray()
            }
        }
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
        }

        override fun onRemove(guid: String, position: Int) {
            removeServer(guid, position)
        }

        override fun onEdit(guid: String, position: Int, profile: ProfileItem) {
            editServer(guid, profile)
        }

        override fun onSelectServer(guid: String) {
            setSelectServer(guid)
        }

        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) {
            val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP

            val (shareOptions, skip) = if (more) {
                val options = if (isCustom) share_method_more.asList().takeLast(3) else share_method_more.asList()
                options to if (isCustom) 2 else 0
            } else {
                val options = if (isCustom) share_method.asList().takeLast(1) else share_method.asList()
                options to if (isCustom) 2 else 0
            }

            shareServer(guid, profile, position, shareOptions, skip)
        }
    }
}
