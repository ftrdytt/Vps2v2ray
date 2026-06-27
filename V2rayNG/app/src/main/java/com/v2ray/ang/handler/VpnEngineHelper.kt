package com.v2ray.ang.handler

import android.content.res.ColorStateList
import android.graphics.Color
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.v2ray.ang.R
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.ui.PingGaugeView
import com.v2ray.ang.ui.SpeedGaugeView
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object VpnEngineHelper {
    var vpnStartTime: Long = 0L
    var pingJob: Job? = null
    var activePingJob: Job? = null
    var liveUpdateJob: Job? = null

    // 🌟 الرابط الجديد الأساسي الآمن والمخفي 🌟
    private const val BASE_API_URL = "https://education.ashor.shop"

    fun applyRunningState(activity: MainActivity, mainViewModel: MainViewModel, isLoading: Boolean, isRunning: Boolean) {
        val lottie = activity.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnConnect = activity.findViewById<MaterialButton>(R.id.btn_green_connect)
        val fab = activity.findViewById<FloatingActionButton>(R.id.fab)
        
        val guid = MmkvManager.getSelectServer().orEmpty()
        val deviceId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        if (isLoading) {
            fab?.setImageResource(R.drawable.ic_fab_check)
            btnConnect?.text = "جاري التشغيل..."
            btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00"))
            activity.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f)
            activity.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f)
            lottie?.playAnimation()
            return
        }

        if (isRunning) {
            vpnStartTime = System.currentTimeMillis()
            fab?.setImageResource(R.drawable.ic_stop_24dp)
            btnConnect?.text = "إيقاف المحرك"
            btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            lottie?.playAnimation()
            TrafficMonitorHelper.startTrafficMonitor(activity)

            activePingJob?.cancel()
            @Suppress("OPT_IN_USAGE")
            activePingJob = GlobalScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        // 🌟 1. فحص الحظر بشكل مستمر لإيقاف الملف فوراً إذا تم حظره من الإدارة 🌟
                        val banConn = URL("$BASE_API_URL/file/check_ban?guid=$guid&deviceId=$deviceId").openConnection() as HttpURLConnection
                        if (banConn.responseCode == 200) {
                            val resp = BufferedReader(InputStreamReader(banConn.inputStream)).readText()
                            val jsonResponse = JSONObject(resp)
                            if (jsonResponse.optBoolean("banned", false)) {
                                val banMsg = jsonResponse.optString("message", "تم حظرك من هذا الملف من قبل الإدارة 🚫")
                                withContext(Dispatchers.Main) { 
                                    V2RayServiceManager.stopVService(activity)
                                    Toast.makeText(activity, banMsg, Toast.LENGTH_LONG).show() 
                                }
                                return@launch // إيقاف اللوب وقطع الاتصال فوراً
                            }
                        }

                        // 🌟 2. إرسال نبضة الاتصال للملف (لكي يظهر في المتصلين داخل الملف) 🌟
                        val userId = AuthManager.getId(activity)
                        val payload = JSONObject()
                            .put("guid", guid)
                            .put("deviceId", deviceId)
                            .put("userId", userId)
                            .put("name", if (userId.isNotEmpty()) AuthManager.getName(activity) else "مجهول")
                            .put("pfp", if (userId.isNotEmpty()) AuthManager.getPfp(activity) else "")
                        
                        val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                        
                        // 🌟 3. إرسال نبضة للوحة تحكم الإدارة (النشطين الكلي) 🌟
                        if (userId.isNotEmpty()) {
                            val conn2 = URL("$BASE_API_URL/admin/ping_active").openConnection() as HttpURLConnection
                            conn2.requestMethod = "POST"
                            conn2.setRequestProperty("Content-Type", "application/json")
                            conn2.doOutput = true
                            conn2.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray(Charsets.UTF_8)) }
                            conn2.responseCode
                        }
                    } catch (e: Exception) {}
                    
                    // 🌟 الانتظار 30 ثانية لتخفيف الضغط على السيرفر ومطابقة إعدادات دقيقتين 🌟
                    delay(30000) 
                }
            }

        } else {
            // 🌟 4. أمر الخروج الفوري: بمجرد الإيقاف يتم مسحه من السيرفر بنفس اللحظة 🌟
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val userId = AuthManager.getId(activity)
                    val payload = JSONObject()
                        .put("guid", guid)
                        .put("deviceId", deviceId)
                        .put("userId", userId)
                        .put("disconnect", true) // تنبيه السيرفر ليمسح المستخدم فوراً
                    
                    val conn = URL("$BASE_API_URL/file/ping").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } catch (e: Exception) {}
            }

            vpnStartTime = 0L; activePingJob?.cancel(); TrafficMonitorHelper.stopTrafficMonitor(); SpeedTestHelper.cancelJobs()
            fab?.setImageResource(R.drawable.ic_play_24dp); btnConnect?.text = "تشغيل المحرك"; btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")); lottie?.cancelAnimation(); lottie?.progress = 0f; activity.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f); activity.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f)
        }
    }

    fun startLiveUpdates(activity: MainActivity, mainViewModel: MainViewModel) {
        liveUpdateJob?.cancel()
        @Suppress("OPT_IN_USAGE")
        liveUpdateJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                val guids = MmkvManager.decodeServerList()?.toList() ?: emptyList()
                var needsRefresh = false
                for (guid in guids) {
                    val lId = V2rayCrypt.getLicenseId(activity, guid)
                    if (lId.isNotEmpty() && lId != "LEGACY") {
                        val cloudData = CloudflareAPI.checkLiveConfig(lId)
                        if (cloudData.first >= 0L && V2rayCrypt.getActiveCount(activity, guid) != cloudData.third) {
                            V2rayCrypt.saveActiveCount(activity, guid, cloudData.third); needsRefresh = true
                        }
                    }
                }
                if (needsRefresh) withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                
                // تم تخفيف الضغط ليفحص كل 30 ثانية
                delay(30000)
            }
        }
    }
    
    fun cancelAllJobs() { pingJob?.cancel(); activePingJob?.cancel(); liveUpdateJob?.cancel() }
}
