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

    fun applyRunningState(activity: MainActivity, mainViewModel: MainViewModel, isLoading: Boolean, isRunning: Boolean) {
        val lottie = activity.findViewById<LottieAnimationView>(R.id.lottie_engine)
        val btnConnect = activity.findViewById<MaterialButton>(R.id.btn_green_connect)
        val fab = activity.findViewById<FloatingActionButton>(R.id.fab)
        
        val guid = MmkvManager.getSelectServer().orEmpty()
        val deviceId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        if (isLoading) {
            fab?.setImageResource(R.drawable.ic_fab_check); btnConnect?.text = "جاري التشغيل..."; btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F57C00")); activity.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f); activity.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f); lottie?.playAnimation(); return
        }

        if (isRunning) {
            vpnStartTime = System.currentTimeMillis()
            fab?.setImageResource(R.drawable.ic_stop_24dp); btnConnect?.text = "إيقاف المحرك"; btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F")); lottie?.playAnimation()
            TrafficMonitorHelper.startTrafficMonitor(activity)

            // فحص الحظر
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://vpn-license.rauter505.workers.dev/file/check_ban?guid=$guid&deviceId=$deviceId").openConnection() as HttpURLConnection
                    if (conn.responseCode == 200 && JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).optBoolean("banned", false)) {
                        delay(1000); withContext(Dispatchers.Main) { V2RayServiceManager.stopVService(activity); Toast.makeText(activity, "تم حظرك من هذا الملف", Toast.LENGTH_LONG).show() }; return@launch
                    }
                } catch (e: Exception) {}
            }

            activePingJob?.cancel()
            activePingJob = GlobalScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val userId = AuthManager.getId(activity)
                        val payload = JSONObject().put("guid", guid).put("deviceId", deviceId).put("userId", userId).put("name", if (userId.isNotEmpty()) AuthManager.getName(activity) else "مجهول").put("pfp", if (userId.isNotEmpty()) AuthManager.getPfp(activity) else "")
                        val conn = URL("https://vpn-license.rauter505.workers.dev/file/ping").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"; conn.doOutput = true; conn.outputStream.use { it.write(payload.toString().toByteArray()) }; conn.responseCode
                        
                        if (userId.isNotEmpty()) {
                            val conn2 = URL("https://vpn-license.rauter505.workers.dev/admin/ping_active").openConnection() as HttpURLConnection
                            conn2.requestMethod = "POST"; conn2.doOutput = true; conn2.outputStream.use { it.write(JSONObject().put("id", userId).toString().toByteArray()) }; conn2.responseCode
                        }
                    } catch (e: Exception) {}
                    delay(30000)
                }
            }

        } else {
            vpnStartTime = 0L; activePingJob?.cancel(); TrafficMonitorHelper.stopTrafficMonitor(); SpeedTestHelper.cancelJobs()
            fab?.setImageResource(R.drawable.ic_play_24dp); btnConnect?.text = "تشغيل المحرك"; btnConnect?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")); lottie?.cancelAnimation(); lottie?.progress = 0f; activity.findViewById<PingGaugeView>(R.id.gauge_ping)?.setPing(0f); activity.findViewById<SpeedGaugeView>(R.id.gauge_speed)?.setSpeed(0f)
        }
    }

    fun startLiveUpdates(activity: MainActivity, mainViewModel: MainViewModel) {
        liveUpdateJob?.cancel()
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
                delay(4000)
            }
        }
    }
    
    fun cancelAllJobs() { pingJob?.cancel(); activePingJob?.cancel(); liveUpdateJob?.cancel() }
}
