package com.v2ray.ang.handler

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import kotlinx.coroutines.*

object AdminHelper {

    // دالة ذكية لحساب الوقت وإخفاء الثواني (أيام، ساعات، دقائق فقط)
    fun formatTimeNoSeconds(expiryMs: Long, context: Context): String {
        val now = NetworkTime.currentTimeMillis(context)
        val diff = expiryMs - now
        if (diff <= 0) return "منتهي الصلاحية 🛑"

        val days = diff / (1000 * 60 * 60 * 24)
        val hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
        val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)

        val parts = mutableListOf<String>()
        if (days > 0) parts.add("$days يوم")
        if (hours > 0) parts.add("$hours ساعة")
        if (minutes > 0) parts.add("$minutes دقيقة")

        if (parts.isEmpty()) return "أقل من دقيقة ⏳"
        return parts.joinToString(" و ")
    }

    fun showExtendLicenseDialog(activity: Activity, guid: String, onReloadRequired: () -> Unit, showLoading: () -> Unit, hideLoading: () -> Unit) {
        val mainLicenseId = V2rayCrypt.getLicenseId(activity, guid)
        if (mainLicenseId.isEmpty() || mainLicenseId == "LEGACY") { activity.toastError("هذا الكود قديم ولا يدعم الإدارة السحابية."); return }

        // جلب قائمة الملفات المستخرجة
        val exportedConfigs = V2rayCrypt.getSubscribers(activity, guid)
        
        val optionsList = mutableListOf<String>()
        val licenseIdsList = mutableListOf<String>()
        val isMainConfigList = mutableListOf<Boolean>()

        // 1. إضافة الملف الرئيسي للخيارات
        val mainExpiry = V2rayCrypt.getExpiryTime(activity, guid)
        optionsList.add("👑 الملف الأساسي - (${formatTimeNoSeconds(mainExpiry, activity)})")
        licenseIdsList.add(mainLicenseId)
        isMainConfigList.add(true)

        // 2. إضافة الملفات المستخرجة
        for (config in exportedConfigs) {
            optionsList.add("📄 ${config.name} - (${formatTimeNoSeconds(config.expiryTimeMs, activity)})")
            licenseIdsList.add(config.licenseId)
            isMainConfigList.add(false)
        }

        AlertDialog.Builder(activity)
            .setTitle("اختر الملف لتمديد وقته ⏱️")
            .setItems(optionsList.toTypedArray()) { _, which ->
                val selectedLicense = licenseIdsList[which]
                val isMain = isMainConfigList[which]
                showTimeInputDialog(activity, guid, selectedLicense, isMain, onReloadRequired, showLoading, hideLoading)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTimeInputDialog(
        activity: Activity, 
        mainGuid: String, 
        targetLicenseId: String, 
        isMainConfig: Boolean, 
        onReloadRequired: () -> Unit, 
        showLoading: () -> Unit, 
        hideLoading: () -> Unit
    ) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        layout.addView(TextView(activity).apply {
            text = if (isMainConfig) "تمديد الملف الأساسي" else "تمديد الملف المستخرج"
            textSize = 17f; setTextColor(Color.parseColor("#FF9800")); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 30); gravity = Gravity.CENTER
        })

        val mInput = EditText(activity).apply { hint = "أشهر"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.BLACK) }; layout.addView(mInput)
        val dInput = EditText(activity).apply { hint = "أيام"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.BLACK) }; layout.addView(dInput)
        val hInput = EditText(activity).apply { hint = "ساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.BLACK) }; layout.addView(hInput)

        AlertDialog.Builder(activity).setView(layout).setPositiveButton("تمديد") { _, _ ->
            val ms = ((mInput.text.toString().toLongOrNull() ?: 0L) * 30L * 86400000L) + 
                     ((dInput.text.toString().toLongOrNull() ?: 0L) * 86400000L) + 
                     ((hInput.text.toString().toLongOrNull() ?: 0L) * 3600000L)
            
            if (ms > 0L) {
                val newTime = NetworkTime.currentTimeMillis(activity) + ms
                showLoading()
                GlobalScope.launch(Dispatchers.IO) {
                    val success = CloudflareAPI.updateExpiry(targetLicenseId, newTime)
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (success) { 
                            if (isMainConfig) {
                                V2rayCrypt.getAllProtectedGuids(activity).forEach { if (V2rayCrypt.getLicenseId(activity, it) == targetLicenseId) V2rayCrypt.saveExpiryTime(activity, it, newTime) }
                            } else {
                                val sub = V2rayCrypt.getSubscribers(activity, mainGuid).find { it.licenseId == targetLicenseId }
                                V2rayCrypt.updateSubscriberLocally(activity, mainGuid, targetLicenseId, newTime, sub?.activeCount ?: 0)
                            }
                            activity.toastSuccess("تم التمديد بنجاح!"); onReloadRequired() 
                        } else activity.toastError("فشل الاتصال بالسيرفر")
                    }
                }
            } else activity.toastError("أدخل وقت صحيح")
        }.setNeutralButton("إيقاف (باند)") { _, _ ->
            showLoading()
            GlobalScope.launch(Dispatchers.IO) {
                val expired = NetworkTime.currentTimeMillis(activity) - 1000L
                val success = CloudflareAPI.updateExpiry(targetLicenseId, expired)
                withContext(Dispatchers.Main) { 
                    hideLoading()
                    if (success) { 
                        if (isMainConfig) {
                            V2rayCrypt.getAllProtectedGuids(activity).forEach { if (V2rayCrypt.getLicenseId(activity, it) == targetLicenseId) V2rayCrypt.saveExpiryTime(activity, it, expired) }
                        } else {
                            val sub = V2rayCrypt.getSubscribers(activity, mainGuid).find { it.licenseId == targetLicenseId }
                            V2rayCrypt.updateSubscriberLocally(activity, mainGuid, targetLicenseId, expired, sub?.activeCount ?: 0)
                        }
                        activity.toastSuccess("تم إيقاف الملف!"); onReloadRequired() 
                    } else activity.toastError("فشل الاتصال")
                }
            }
        }.show()
    }

    // الدالة الأصلية لتحديث الكود من الحافظة (تم الحفاظ عليها كما أرسلتها)
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
