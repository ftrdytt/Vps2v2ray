package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.AuthManager
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

        val etId = findViewById<EditText>(R.id.et_login_id)
        val etPass = findViewById<EditText>(R.id.et_login_pass)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        btnLogin.setOnClickListener {
            val id = etId.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (id.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال الايدي والباسورد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "جاري التحقق..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = URL("https://vpn-license.rauter505.workers.dev/auth/login")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    
                    val payload = JSONObject().apply {
                        put("id", id)
                        put("password", pass)
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val obj = JSONObject(resp)
                        if (obj.getBoolean("success")) {
                            val name = obj.getString("name")
                            val role = obj.getString("role")
                            val pfp = obj.optString("pfp", "")
                            
                            AuthManager.saveUser(this@LoginActivity, id, name, pass, role, pfp)
                            
                            withContext(Dispatchers.Main) {
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@LoginActivity, obj.optString("message", "خطأ في تسجيل الدخول"), Toast.LENGTH_SHORT).show()
                                btnLogin.isEnabled = true
                                btnLogin.text = "تسجيل الدخول"
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LoginActivity, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show()
                            btnLogin.isEnabled = true
                            btnLogin.text = "تسجيل الدخول"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "تأكد من اتصالك بالإنترنت", Toast.LENGTH_SHORT).show()
                        btnLogin.isEnabled = true
                        btnLogin.text = "تسجيل الدخول"
                    }
                }
            }
        }
    }
}
