package com.v2ray.ang.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UpdateLogsActivity : AppCompatActivity() {
    private lateinit var mainContainer: LinearLayout
    private lateinit var tvLoading: TextView
    private val allUsersCache = mutableMapOf<String, JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // بناء الواجهة برمجياً لضمان عدم وجود أخطاء في الـ XML
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }
        
        val header = TextView(this).apply {
            text = "سجل التحديثات (التقويم)"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 40, 20, 40)
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }
        
        tvLoading = TextView(this).apply {
            text = "جاري تحميل البيانات من السيرفر..."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }
        
        scrollView.addView(mainContainer)
        root.addView(header)
        root.addView(tvLoading)
        root.addView(scrollView)
        
        setContentView(root)
        
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. جلب جميع المستخدمين وحفظهم في الكاش لكي نعرض صورهم وأسماءهم لاحقاً
                val usersUrl = URL("https://vpn-license.rauter505.workers.dev/admin/get_all_users")
                val usersConn = usersUrl.openConnection() as HttpURLConnection
                if (usersConn.responseCode == 200) {
                    val usersResp = BufferedReader(InputStreamReader(usersConn.inputStream)).readText()
                    val usersArray = JSONArray(usersResp)
                    for (i in 0 until usersArray.length()) {
                        val u = usersArray.getJSONObject(i)
                        allUsersCache[u.getString("id")] = u
                    }
                }

                // 2. جلب سجل التحديثات حسب الأيام
                val logsUrl = URL("https://vpn-license.rauter505.workers.dev/admin/get_update_logs")
                val logsConn = logsUrl.openConnection() as HttpURLConnection
                if (logsConn.responseCode == 200) {
                    val logsResp = BufferedReader(InputStreamReader(logsConn.inputStream)).readText()
                    val statsObj = JSONObject(logsResp)
                    val dates = statsObj.keys().asSequence().toList().sortedDescending()
                    
                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        if (dates.isEmpty()) {
                            mainContainer.addView(TextView(this@UpdateLogsActivity).apply { text = "لا توجد سجلات تحديث حتى الآن"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                        } else {
                            for (date in dates) {
                                val logsArray = statsObj.getJSONArray(date)
                                val dateBtn = MaterialButton(this@UpdateLogsActivity).apply {
                                    text = "📅 يوم $date (عدد المحدثين: ${logsArray.length()})"
                                    setBackgroundColor(Color.parseColor("#252529"))
                                    setTextColor(Color.parseColor("#FF9800"))
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 20) }
                                    setOnClickListener { showLogsForDate(date, logsArray) }
                                }
                                mainContainer.addView(dateBtn)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "فشل الاتصال بالسيرفر" }
            }
        }
    }

    private fun showLogsForDate(date: String, logsArray: JSONArray) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#141417")) }
        val tvTitle = TextView(this).apply { text = "قائمة من قاموا بالتحديث يوم $date"; setTextColor(Color.parseColor("#4CAF50")); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,30) } }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(tvTitle); dialogView.addView(ScrollView(this).apply { addView(scrollContent) })

        for (i in 0 until logsArray.length()) {
            val logItem = logsArray.getJSONObject(i)
            val id = logItem.getString("id")
            val version = logItem.getInt("version")
            val time = logItem.getString("time")
            
            val u = allUsersCache[id]
            val name = u?.optString("name") ?: "حساب مجهول/محذوف"
            val pfp = u?.optString("pfp", "") ?: ""
            
            addLogCard(scrollContent, id, name, pfp, version, time)
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("رجوع", null).show()
    }

    private fun addLogCard(container: LinearLayout, id: String, name: String, pfp: String, version: Int, time: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val ivAvatar = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            if (pfp.isNotEmpty()) try { val b = Base64.decode(pfp, Base64.DEFAULT); setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { setImageResource(R.mipmap.ic_launcher) }
            else setImageResource(R.mipmap.ic_launcher)
        }
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
        infoLayout.addView(TextView(this).apply { text = "ID: $id"; setTextColor(Color.parseColor("#FF9800")); textSize = 12f })
        infoLayout.addView(TextView(this).apply { text = "رقم الإصدار: $version"; setTextColor(Color.parseColor("#2196F3")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD) })
        infoLayout.addView(TextView(this).apply { text = "تم التحديث الساعة: $time"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 12f })
        
        card.addView(ivAvatar); card.addView(infoLayout); container.addView(card)
    }
}
