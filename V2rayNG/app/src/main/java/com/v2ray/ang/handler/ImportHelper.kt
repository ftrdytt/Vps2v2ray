package com.v2ray.ang.handler

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.ui.ScannerActivity
import com.v2ray.ang.ui.ServerActivity
import com.v2ray.ang.ui.ServerGroupActivity
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

object ImportHelper {

    fun showAddBottomSheet(activity: MainActivity, mainViewModel: MainViewModel, onLocalFileClick: () -> Unit, onEncryptedFileClick: () -> Unit) {
        val bottomSheetDialog = BottomSheetDialog(activity)
        val scrollView = ScrollView(activity)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141417")); setPadding(0, 0, 0, 40) }
        scrollView.addView(container)

        container.addView(TextView(activity).apply { text = "إضافة تكوين جديد"; textSize = 20f; setTextColor(Color.parseColor("#FF9800")); setPadding(40, 40, 40, 20); textAlignment = View.TEXT_ALIGNMENT_CENTER; setTypeface(null, android.graphics.Typeface.BOLD) })
        container.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { setMargins(40, 0, 40, 20) }; setBackgroundColor(Color.parseColor("#33FFFFFF")) })

        fun createOption(textStr: String, iconRes: Int, onClick: () -> Unit) {
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setPadding(50, 30, 50, 30); gravity = Gravity.CENTER_VERTICAL; isClickable = true; val outValue = android.util.TypedValue(); activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true); setBackgroundResource(outValue.resourceId); setOnClickListener { onClick(); bottomSheetDialog.dismiss() } }
            layout.addView(ImageView(activity).apply { setImageResource(iconRes); setColorFilter(Color.parseColor("#FF9800")); layoutParams = LinearLayout.LayoutParams(56, 56) })
            layout.addView(TextView(activity).apply { text = textStr; textSize = 16f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 40 } })
            container.addView(layout)
        }

        createOption("استيراد من رمز الاستجابة (QR)", android.R.drawable.ic_menu_camera) { activity.startActivity(Intent(activity, ScannerActivity::class.java)) }
        createOption("استيراد من الحافظة (عادي)", android.R.drawable.ic_menu_add) { importClipboard(activity, mainViewModel) }
        createOption("استيراد من حافظة مشفرة", android.R.drawable.ic_lock_idle_lock) { importClipboardEncrypted(activity, mainViewModel) }
        createOption("استيراد ملف من الجهاز", android.R.drawable.ic_menu_upload) { onLocalFileClick() }
        createOption("إضافة ملف مشفر (.ashor)", android.R.drawable.ic_menu_save) { onEncryptedFileClick() }
        
        createOption("إضافة VLESS", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.VLESS.value) }
        createOption("إضافة VMess", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.VMESS.value) }
        createOption("إضافة Trojan", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.TROJAN.value) }
        // الأسطر المضافة للبروتوكولات الجديدة
        createOption("إضافة Shadowsocks", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.SHADOWSOCKS.value) }
        createOption("إضافة Socks", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.SOCKS.value) }
        createOption("إضافة WireGuard", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.WIREGUARD.value) }
        createOption("إضافة تكوين مخصص (Custom)", android.R.drawable.ic_menu_edit) { importManually(activity, mainViewModel, EConfigType.CUSTOM.value) }

        bottomSheetDialog.setContentView(scrollView)
        bottomSheetDialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        bottomSheetDialog.show()
    }

    private fun importManually(activity: Activity, mainViewModel: MainViewModel, type: Int) {
        activity.startActivity(Intent().putExtra("createConfigType", type).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(activity, ServerActivity::class.java))
    }

    fun importClipboard(activity: MainActivity, vm: MainViewModel) { try { importBatchConfig(activity, vm, Utils.getClipboard(activity)) } catch (e: Exception) {} }
    
    fun importClipboardEncrypted(activity: MainActivity, vm: MainViewModel) {
        try {
            val clip = Utils.getClipboard(activity); if (clip.isNullOrEmpty()) { activity.toast("الحافظة فارغة"); return }
            val res = V2rayCrypt.decryptAndCheckExpiry(clip); if (res == null) { activity.toast("الكود غير صالح"); return }
            importEncryptedBatchConfig(activity, vm, res.first, res.second, res.third)
        } catch (e: Exception) {}
    }

    fun importEncryptedContentFromUri(activity: MainActivity, vm: MainViewModel, uri: Uri) {
        try {
            val content = activity.contentResolver.openInputStream(uri)?.use { BufferedReader(InputStreamReader(it)).readText() } ?: return
            val res = V2rayCrypt.decryptAndCheckExpiry(content); if (res != null) importEncryptedBatchConfig(activity, vm, res.first, res.second, res.third)
        } catch (e: Exception) { activity.toast("خطأ في الملف") }
    }

    fun readContentFromUri(activity: MainActivity, vm: MainViewModel, uri: Uri) {
        try { activity.contentResolver.openInputStream(uri).use { importBatchConfig(activity, vm, it?.bufferedReader()?.readText()) } } catch (e: Exception) {}
    }

    private fun importEncryptedBatchConfig(activity: MainActivity, vm: MainViewModel, server: String?, expiry: Long, licenseId: String) {
        activity.showLoadingDialog()
        GlobalScope.launch(Dispatchers.IO) {
            val before = MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()
            val (count, _) = AngConfigManager.importBatchConfig(server, vm.subscriptionId, true)
            withContext(Dispatchers.Main) {
                if (count > 0) {
                    val newGuids = (MmkvManager.decodeServerList()?.toSet() ?: emptySet<String>()) - before
                    V2rayCrypt.addProtectedGuids(activity, newGuids)
                    newGuids.forEach { guid ->
                        if (expiry > 0L) V2rayCrypt.saveExpiryTime(activity, guid, expiry)
                        if (licenseId.isNotEmpty()) V2rayCrypt.saveLicenseId(activity, guid, licenseId)
                        if (server != null) V2rayCrypt.saveLastConfigHash(activity, guid, Base64.encodeToString(server.toByteArray(), Base64.NO_WRAP).hashCode())
                    }
                    vm.reloadServerList(); activity.toast("تم استيراد التكوين!")
                } else activity.toastError(R.string.toast_failure)
                activity.hideLoadingDialog()
            }
        }
    }

    private fun importBatchConfig(activity: MainActivity, vm: MainViewModel, server: String?) {
        activity.showLoadingDialog()
        GlobalScope.launch(Dispatchers.IO) {
            val (count, _) = AngConfigManager.importBatchConfig(server, vm.subscriptionId, true); delay(500L)
            withContext(Dispatchers.Main) {
                if (count > 0) { activity.toast("تم إضافة $count سيرفر"); vm.reloadServerList() } else activity.toastError(R.string.toast_failure)
                activity.hideLoadingDialog()
            }
        }
    }
}
