package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.handler.UpdateManager 
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class LoginActivity : AppCompatActivity() {

    private val BASE_API_URL = "https://education.ashor.shop"
    private var myDeviceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 🌟 استخراج آيدي الهاردوير الثابت (مستحيل يتغير) 🌟
        myDeviceId = getUniqueHardwareId(this)

        UpdateManager.startBackgroundUpdateCheck(this)

        val btnQuickLogin = findViewById<MaterialButton>(R.id.btn_quick_login)
        val btnCreateRandom = findViewById<MaterialButton>(R.id.btn_create_random)
        val etId = findViewById<EditText>(R.id.et_login_id)
        val etPass = findViewById<EditText>(R.id.et_login_pass)
        val btnManualLogin = findViewById<MaterialButton>(R.id.btn_manual_login)

        val savedId = AuthManager.getSavedId(this)
        val savedName = AuthManager.getSavedName(this)
        val savedPass = AuthManager.getSavedPass(this)

        if (!savedId.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
            btnQuickLogin.visibility = View.VISIBLE
            btnQuickLogin.text = "متابعة كـ ($savedName)"
            btnQuickLogin.setOnClickListener {
                loginProcess(savedId, savedPass, btnQuickLogin)
            }
        }

        btnCreateRandom.setOnClickListener {
            btnCreateRandom.isEnabled = false
            btnCreateRandom.text = "جاري إنشاء الحساب..."
            
            lifecycleScope.launch(Dispatchers.IO) {
                var isSuccess = false
                var message = "فشل الاتصال بالإنترنت"
                try {
                    val url = URL("$BASE_API_URL/auth/init")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    
                    // إرسال آيدي الهاردوير الثابت ليتم تسجيله
                    val payload = JSONObject().apply { put("deviceId", myDeviceId) }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(this@LoginActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                            isSuccess = true
                            message = "تم إنشاء الحساب بنجاح! ✅"
                        } else {
                            message = "حدث خطأ أثناء الإنشاء ❌"
                        }
                    } else {
                        message = "خطأ في السيرفر: ${conn.responseCode} ❌"
                    }
                } catch (e: Exception) {
                    message = "تأكد من اتصالك بالإنترنت 🌐"
                } finally {
                    withContext(Dispatchers.Main) {
                        if (isSuccess) {
                            showCustomSnackbar(message, "#4CAF50")
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        } else {
                            showCustomSnackbar(message, "#F44336")
                            btnCreateRandom.isEnabled = true
                            btnCreateRandom.text = "إنشاء حساب عشوائي جديد بضغطة"
                        }
                    }
                }
            }
        }

        btnManualLogin.setOnClickListener {
            val id = etId.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (id.isEmpty() || pass.isEmpty()) {
                showCustomSnackbar("يرجى إدخال الايدي والباسورد ⚠️", "#FF9800")
                return@setOnClickListener
            }
            loginProcess(id, pass, btnManualLogin)
        }
    }

    private fun loginProcess(id: String, pass: String, button: MaterialButton) {
        val originalText = button.text.toString()
        button.isEnabled = false
        button.text = "جاري التحقق..."

        lifecycleScope.launch(Dispatchers.IO) {
            var isSuccess = false
            var message = "تأكد من اتصالك بالإنترنت 🌐"
            
            try {
                val url = URL("$BASE_API_URL/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                // إرسال آيدي الهاردوير الثابت للتحقق
                val payload = JSONObject().apply { 
                    put("id", id)
                    put("password", pass)
                    put("deviceId", myDeviceId) 
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        AuthManager.saveUser(this@LoginActivity, id, obj.getString("name"), pass, obj.getString("role"), obj.optString("pfp", ""))
                        isSuccess = true
                    } else {
                        message = obj.optString("message", "خطأ في تسجيل الدخول ❌")
                    }
                } else {
                    message = "فشل الاتصال بالسيرفر ❌"
                }
            } catch (e: Exception) {
                 message = "تأكد من اتصالك بالإنترنت 🌐"
            } finally {
                withContext(Dispatchers.Main) {
                    if (isSuccess) {
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        showCustomSnackbar(message, "#F44336")
                        button.isEnabled = true
                        button.text = originalText
                    }
                }
            }
        }
    }

    // 🌟 دالة الإشعارات الأنيقة والحديثة 🌟
    private fun showCustomSnackbar(message: String, colorHex: String) {
        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, "", Snackbar.LENGTH_SHORT)
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.setPadding(0, 0, 0, 0)

        val customView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = 40f
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(40, 0, 40, 40)
            }
        }

        snackbarLayout.addView(customView, 0)
        snackbar.show()
    }

    // 🌟 دالة توليد Hardware ID ثابت كالصخر ومستحيل يتغير 🌟
    private fun getUniqueHardwareId(context: Context): String {
        try {
            val devInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.DISPLAY +
                    Build.HARDWARE + Build.MANUFACTURER + Build.MODEL + Build.PRODUCT +
                    Build.USER + Build.ID + Build.BOOTLOADER
            
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(devInfo.toByteArray())
            val hexString = StringBuilder()
            for (byte in hash) {
                val hex = Integer.toHexString(0xFF and byte.toInt())
                if (hex.length == 1) {
                    hexString.append('0')
                }
                hexString.append(hex)
            }
            return hexString.toString().take(15).toUpperCase() // يولد آيدي مثل: A9C8B7F6D5E4A3
        } catch (e: Exception) {
            return "UNKNOWN_HW_ID"
        }
    }
}
