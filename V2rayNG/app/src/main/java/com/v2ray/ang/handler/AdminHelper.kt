package com.v2ray.ang.handler

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import kotlinx.coroutines.*

object AdminHelper {

    fun showExtendLicenseDialog(activity: Activity, guid: String, onReloadRequired: () -> Unit, showLoading: () -> Unit, hideLoading: () -> Unit) {
        val licenseId = V2rayCrypt.getLicenseId(activity, guid)
        if (licenseId.isEmpty() || licenseId == "LEGACY") { activity.toastError("هذا الكود قديم ولا يدعم التمديد المركزي."); return }

        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val mInput = EditText(activity).apply { hint = "أشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.BLACK) }
        val dInput = EditText(activity).apply { hint = "أيام"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.BLACK) }
        layout.addView(mInput); layout.addView(dInput)

        AlertDialog.Builder(activity).setTitle("تمديد الكود للجميع").setView(layout).setPositiveButton("تمديد") { _, _ ->
            val ms = ((mInput.text.toString().toLongOrNull() ?: 0L) * 30L * 86400000L) + ((dInput.text.toString().toLongOrNull() ?: 0L) * 86400000L)
            if (ms > 0L) {
                val newTime = NetworkTime.currentTimeMillis(activity) + ms
                showLoading()
                GlobalScope.launch(Dispatchers.IO) {
                    val success = CloudflareAPI.updateExpiry(licenseId, newTime)
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (success) { V2rayCrypt.getAllProtectedGuids(activity).forEach { if (V2rayCrypt.getLicenseId(activity, it) == licenseId) V2rayCrypt.saveExpiryTime(activity, it, newTime) }; activity.toastSuccess("تم التمديد!"); onReloadRequired() } else activity.toastError("فشل الاتصال")
                    }
                }
            }
        }.setNeutralButton("إيقاف الكود") { _, _ ->
            showLoading()
            GlobalScope.launch(Dispatchers.IO) {
                val expired = NetworkTime.currentTimeMillis(activity) - 1000L
                val success = CloudflareAPI.updateExpiry(licenseId, expired)
                withContext(Dispatchers.Main) { hideLoading(); if (success) { activity.toastSuccess("تم إيقاف الكود!"); onReloadRequired() } }
            }
        }.show()
    }

    fun replaceAndSyncConfigFromClipboard(activity: Activity, guid: String, subId: String, onReloadRequired: () -> Unit, showLoading: () -> Unit, hideLoading: () -> Unit) {
        val licenseId = V2rayCrypt.getLicenseId(activity, guid)
        val newConf = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: return
        
        val expiry = V2rayCrypt.getExpiryTime(activity, guid)
        showLoading()
        GlobalScope.launch(Dispatchers.IO) {
            if (CloudflareAPI.createOrUpdateSubscriber(licenseId, expiry, newConf)) {
                withContext(Dispatchers.Main) {
                    val before = MmkvManager.decodeServerList()?.toSet() ?: emptySet()
                    val (count, _) = AngConfigManager.importBatchConfig(newConf, subId, true)
                    if (count > 0) {
                        val newGuid = ((MmkvManager.decodeServerList()?.toSet() ?: emptySet()) - before).firstOrNull()
                        if (newGuid != null) {
                            V2rayCrypt.addAdminGuid(activity, newGuid); V2rayCrypt.addProtectedGuids(activity, setOf(newGuid)); V2rayCrypt.saveLicenseId(activity, newGuid, licenseId); V2rayCrypt.saveExpiryTime(activity, newGuid, expiry)
                            MmkvManager.removeServer(guid); MmkvManager.setSelectServer(newGuid); activity.toastSuccess("تم التحديث السحابي!"); onReloadRequired()
                        }
                    }
                    hideLoading()
                }
            }
        }
    }
}
