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

    // 🌟 الرابط الجديد الأساسي للـ VPS 🌟
    private val BASE_API_URL = "https://education.ashor.shop"

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
                try {
                    val usersUrl = URL("$BASE_API_URL/admin/get_all_users")
                    val usersConn = usersUrl.openConnection() as HttpURLConnection
                    usersConn.connectTimeout = 5000
                    if (usersConn.responseCode == 200) {
                        val usersResp = BufferedReader(InputStreamReader(usersConn.inputStream)).readText()
                        val usersArray = JSONArray(usersResp)
                        for (i in 0 until usersArray.length()) {
                            val u = usersArray.getJSONObject(i)
                            allUsersCache[u.getString("id")] = u
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل الخطأ في حالة فشل جلب المستخدمين، سيتم عرض الأسماء الافتراضية
                }

                // 2. جلب سجل التحديثات (الآن السيرفر يرسل JSONArray قائمة مسطحة)
                val logsUrl = URL("$BASE_API_URL/admin/get_update_logs")
                val logsConn = logsUrl.openConnection() as HttpURLConnection
                logsConn.connectTimeout = 8000
                logsConn.readTimeout = 8000
                
                if (logsConn.responseCode == 200) {
                    val logsResp = BufferedReader(InputStreamReader(logsConn.inputStream)).readText()
                    val flatLogsArray = JSONArray(logsResp)
                    
                    // 🌟 فرز البيانات المجمعة من السيرفر وتصنيفها حسب الأيام 🌟
                    val groupedLogs = mutableMapOf<String, JSONArray>()
                    
                    for (i in 0 until flatLogsArray.length()) {
                        val logItem = flatLogsArray.getJSONObject(i)
                        val fullDate = logItem.optString("date", "")
                        // استخراج اليوم فقط من التاريخ (مثال: 2026-07-02)
                        val dayDate = if (fullDate.contains(" ")) fullDate.split(" ")[0] else "غير محدد"
                        
                        if (!groupedLogs.containsKey(dayDate)) {
                            groupedLogs[dayDate] = JSONArray()
                        }
                        groupedLogs[dayDate]?.put(logItem)
                    }
                    
                    // ترتيب التواريخ من الأحدث للأقدم
                    val dates = groupedLogs.keys.sortedDescending()
                    
                    withContext(Dispatchers.Main) {
                        tvLoading.visibility = View.GONE
                        if (dates.isEmpty()) {
                            mainContainer.addView(TextView(this@UpdateLogsActivity).apply { 
                                text = "لا توجد سجلات تحديث حتى الآن"
                                setTextColor(Color.WHITE)
                                gravity = Gravity.CENTER 
                            })
                        } else {
                            for (date in dates) {
                                val logsForThisDay = groupedLogs[date]!!
                                val dateBtn = MaterialButton(this@UpdateLogsActivity).apply {
                                    text = "📅 يوم $date (عدد المحدثين: ${logsForThisDay.length()})"
                                    setBackgroundColor(Color.parseColor("#252529"))
                                    setTextColor(Color.parseColor("#FF9800"))
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply { setMargins(0, 0, 0, 20) }
                                    setOnClickListener { showLogsForDate(date, logsForThisDay) }
                                }
                                mainContainer.addView(dateBtn)
                            }
                        }
                    }
                } else {
                    throw Exception("Server Error: ${logsConn.responseCode}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLoading.text = "فشل الاتصال بالسيرفر. يرجى المحاولة لاحقاً." }
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
            
            // قراءة المتغيرات حسب نظام السيرفر الجديد
            val userId = logItem.optString("userId", "")
            val deviceId = logItem.optString("deviceId", "غير معروف")
            val version = logItem.optInt("version", 0)
            val fullDate = logItem.optString("date", "")
            val time = if (fullDate.contains(" ")) fullDate.split(" ")[1] else fullDate
            
            var name = logItem.optString("name", "مجهول الهوية")
            var pfp = ""
            
            // إذا كان يملك حساب (ID)، نجلب أحدث معلوماته من الكاش
            if (userId.isNotEmpty()) {
                val u = allUsersCache[userId]
                if (u != null) {
                    name = u.optString("name", name)
                    pfp = u.optString("pfp", "")
                }
            }
            
            // تحديد الـ ID الظاهر للمسؤول (إما ID الحساب أو ID الجهاز إذا كان مجهول)
            val displayId = if (userId.isNotEmpty()) "حساب ID: $userId" else "جهاز ID: $deviceId"
            
            addLogCard(scrollContent, displayId, name, pfp, version, time)
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("رجوع", null).show()
    }

    private fun addLogCard(container: LinearLayout, idString: String, name: String, pfp: String, version: Int, time: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1A1A1D")); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val ivAvatar = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            if (pfp.isNotEmpty()) try { val b = Base64.decode(pfp, Base64.DEFAULT); setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { setImageResource(R.mipmap.ic_launcher) }
            else setImageResource(R.mipmap.ic_launcher)
        }
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoLayout.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
        infoLayout.addView(TextView(this).apply { text = idString; setTextColor(Color.parseColor("#FF9800")); textSize = 12f })
        infoLayout.addView(TextView(this).apply { text = "رقم الإصدار: $version"; setTextColor(Color.parseColor("#2196F3")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD) })
        infoLayout.addView(TextView(this).apply { text = "تم التحديث الساعة: $time"; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 12f })
        
        card.addView(ivAvatar); card.addView(infoLayout); container.addView(card)
    }
}
