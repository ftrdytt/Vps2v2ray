package com.v2ray.ang.handler

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.SpeedGaugeView
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

object SpeedTestHelper {
    private var speedTestJob: Job? = null
    private var resetSpeedButtonJob: Job? = null

    fun runSpeedTest(activity: Activity, isVpnRunning: Boolean) {
        val speedGauge = activity.findViewById<SpeedGaugeView>(R.id.gauge_speed)
        val btnTest = activity.findViewById<MaterialButton>(R.id.btn_speed_test)
        
        if (speedTestJob?.isActive == true) return
        resetSpeedButtonJob?.cancel()
        btnTest?.isEnabled = false
        btnTest?.text = "جاري القياس..."
        btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))

        speedTestJob = GlobalScope.launch(Dispatchers.IO) {
            var finalSpeed = -1f
            val url = URL("https://speed.cloudflare.com/__down?bytes=20000000")
            
            if (isVpnRunning && finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10809))) } catch (e: Exception) {}
            if (isVpnRunning && finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))) } catch (e: Exception) {}
            if (finalSpeed < 0f) try { finalSpeed = attemptDownload(url, speedGauge, Proxy.NO_PROXY) } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                speedGauge?.setSpeed(0f)
                btnTest?.isEnabled = true
                if (finalSpeed >= 0f) {
                    btnTest?.text = "السرعة: ${String.format(Locale.US, "%.1f", finalSpeed)} Mbps"
                    btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    resetSpeedButtonJob = launch { delay(10000); btnTest?.text = "قياس سرعة الإنترنت"; btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3")) }
                } else {
                    btnTest?.text = "قياس سرعة الإنترنت"
                    btnTest?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
                    activity.toast("تعذر الفحص.")
                }
            }
        }
    }

    private suspend fun attemptDownload(url: URL, speedGauge: SpeedGaugeView?, proxy: Proxy): Float {
        val connection = url.openConnection(proxy) as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 2000
        connection.readTimeout = 5000
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return -1f

        val inputStream = connection.inputStream
        val buffer = ByteArray(16384)
        var totalBytesRead = 0L
        var bytesRead: Int
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastBytesRead = 0L
        var maxSpeed = 0f

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            totalBytesRead += bytesRead
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - lastUpdateTime
            if (timeDiff >= 300) {
                val speedMbps = ((totalBytesRead - lastBytesRead) / (timeDiff / 1000f) * 8) / 1_000_000f
                if (speedMbps > maxSpeed) maxSpeed = speedMbps
                withContext(Dispatchers.Main) { speedGauge?.setSpeed(speedMbps) }
                lastUpdateTime = currentTime; lastBytesRead = totalBytesRead
            }
            if (currentTime - startTime > 8000) break
        }
        inputStream.close()
        return if (maxSpeed > 0f) maxSpeed else -1f
    }

    fun cancelJobs() {
        speedTestJob?.cancel()
        resetSpeedButtonJob?.cancel()
    }
}
