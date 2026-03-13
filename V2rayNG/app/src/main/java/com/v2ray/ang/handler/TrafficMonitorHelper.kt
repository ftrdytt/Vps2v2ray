package com.v2ray.ang.handler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.TrafficStats
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import kotlinx.coroutines.*
import java.util.Locale

object TrafficMonitorHelper {
    private var trafficJob: Job? = null
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var isFirstTrafficRead: Boolean = true

    fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0.00 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0; if (kb < 1024) return String.format(Locale.ENGLISH, "%.2f KB", kb)
        val mb = kb / 1024.0; if (mb < 1024) return String.format(Locale.ENGLISH, "%.2f MB", mb)
        val gb = mb / 1024.0; return String.format(Locale.ENGLISH, "%.2f GB", gb)
    }

    fun updateTrafficDisplay(activity: Activity) {
        val prefs = activity.getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
        activity.findViewById<TextView>(R.id.tv_total_traffic)?.text = formatTraffic(prefs.getLong("rx", 0L) + prefs.getLong("tx", 0L))
    }

    fun startTrafficMonitor(activity: Activity) {
        isFirstTrafficRead = true
        trafficJob?.cancel()
        trafficJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val rx = TrafficStats.getTotalRxBytes()
                    val tx = TrafficStats.getTotalTxBytes()
                    if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                        if (isFirstTrafficRead) { lastRxBytes = rx; lastTxBytes = tx; isFirstTrafficRead = false }
                        else {
                            val diffRx = rx - lastRxBytes; val diffTx = tx - lastTxBytes
                            if (diffRx > 0 || diffTx > 0) {
                                val prefs = activity.getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
                                prefs.edit().putLong("rx", prefs.getLong("rx", 0L) + diffRx).putLong("tx", prefs.getLong("tx", 0L) + diffTx).apply()
                                lastRxBytes = rx; lastTxBytes = tx
                                withContext(Dispatchers.Main) { updateTrafficDisplay(activity) }
                            }
                        }
                    }
                } catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    fun stopTrafficMonitor() {
        trafficJob?.cancel()
        isFirstTrafficRead = true
    }

    fun showTrafficDetailsDialog(activity: Activity, isVpnRunning: Boolean) {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_traffic_stats)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val prefs = activity.getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
        
        fun refreshDialogData() {
            dialog.findViewById<TextView>(R.id.tv_download_stat).text = formatTraffic(prefs.getLong("rx", 0L))
            dialog.findViewById<TextView>(R.id.tv_upload_stat).text = formatTraffic(prefs.getLong("tx", 0L))
        }
        refreshDialogData()
        
        dialog.findViewById<ImageView>(R.id.btn_close_dialog).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<MaterialButton>(R.id.btn_reset_stats).setOnClickListener {
            prefs.edit().putLong("rx", 0L).putLong("tx", 0L).apply()
            if (isVpnRunning) { lastRxBytes = TrafficStats.getTotalRxBytes(); lastTxBytes = TrafficStats.getTotalTxBytes() }
            refreshDialogData()
            updateTrafficDisplay(activity)
            activity.toast("تم التصفير بنجاح!")
        }
        dialog.show()
    }
}
