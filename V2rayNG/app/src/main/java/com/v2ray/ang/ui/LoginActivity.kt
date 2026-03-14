package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
import com.v2ray.ang.handler.UpdateManager // الاستدعاء السحري لحل المشكلة 🚀
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // بدء فحص التحديثات التلقائي بالاعتماد على الملف المساعد (Clean Architecture)
        UpdateManager.startBackgroundUpdateCheck(this)

        val btnQuickLogin = findViewById<MaterialButton>(R.id.btn_quick_login)
        val btnCreateRandom = findViewById<MaterialButton>(R.id.btn_create_random)
        val etId = findViewById<EditText>(R.id.et_login_id)
        val etPass = findViewById<EditText>(R.id.et_login_pass)
        val btnManualLogin = findViewById<MaterialButton>(R.id.btn_manual_login)

        // فحص وجود حساب قديم مسجل خروج
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
                try {
                    val url = URL("https://vpn-license.rauter505.workers.dev/auth/init")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            AuthManager.saveUser(this@LoginActivity, obj.getString("id"), obj.getString("name"), obj.getString("password"), "user", "")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@LoginActivity, "تم إنشاء الحساب بنجاح!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "فشل الاتصال بالإنترنت", Toast.LENGTH_SHORT).show()
                        btnCreateRandom.isEnabled = true
                        btnCreateRandom.text = "إنشاء حساب عشوائي جديد بضغطة"
                    }
                }
            }
        }

        btnManualLogin.setOnClickListener {
            val id = etId.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (id.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال الايدي والباسورد", Toast.LENGTH_SHORT).show()
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
            try {
                val url = URL("https://vpn-license.rauter505.workers.dev/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject().apply { put("id", id); put("password", pass) }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val obj = JSONObject(resp)
                    if (obj.getBoolean("success")) {
                        AuthManager.saveUser(this@LoginActivity, id, obj.getString("name"), pass, obj.getString("role"), obj.optString("pfp", ""))
                        withContext(Dispatchers.Main) {
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LoginActivity, obj.optString("message", "خطأ في تسجيل الدخول"), Toast.LENGTH_SHORT).show()
                            button.isEnabled = true; button.text = originalText
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                        button.isEnabled = true; button.text = originalText
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "تأكد من اتصالك بالإنترنت", Toast.LENGTH_SHORT).show()
                    button.isEnabled = true; button.text = originalText
                }
            }
        }
    }
}
