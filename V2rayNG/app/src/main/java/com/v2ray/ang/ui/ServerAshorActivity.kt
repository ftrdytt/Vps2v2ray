package com.v2ray.ang.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

class ServerAshorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_ashor)

        // زر الرجوع في الهيدر
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // ربط الحقول المشتركة
        val etRemarks = findViewById<EditText>(R.id.et_remarks)
        val etAddress = findViewById<EditText>(R.id.et_address)
        val etPort = findViewById<EditText>(R.id.et_port)
        
        // ربط الحقول الخاصة بنا
        val etId = findViewById<EditText>(R.id.et_id)
        val etProxyIp = findViewById<EditText>(R.id.et_proxy_ip)
        val etProxyPort = findViewById<EditText>(R.id.et_proxy_port)
        val etSni = findViewById<EditText>(R.id.et_sni)
        val etBsid = findViewById<EditText>(R.id.et_bsid)
        
        val btnSaveAndConnect = findViewById<MaterialButton>(R.id.btn_save_and_connect)

        btnSaveAndConnect.setOnClickListener {
            val remarks = etRemarks.text.toString().trim()
            val vlessAddress = etAddress.text.toString().trim()
            val vlessPort = etPort.text.toString().trim()
            val uuid = etId.text.toString().trim()
            val proxyIp = etProxyIp.text.toString().trim()
            val proxyPort = etProxyPort.text.toString().trim()
            val sni = etSni.text.toString().trim()
            val bsid = etBsid.text.toString().trim()

            // التحقق من أن الحقول المهمة غير فارغة
            if (vlessAddress.isEmpty() || vlessPort.isEmpty() || uuid.isEmpty() || proxyIp.isEmpty() || sni.isEmpty()) {
                Toast.makeText(this, "يرجى ملء جميع الحقول المطلوبة الأساسية", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // توليد الـ JSON الاحترافي مع الحاقن
            val jsonConfig = generateAshorPayload(vlessAddress, vlessPort, uuid, proxyIp, proxyPort, sni, bsid)
            val finalRemarks = if (remarks.isNotEmpty()) remarks else "Ashor: $sni"

            try {
                // 🌟 الحل السحري لتجاوز جميع أخطاء المترجم (Build Errors) 🌟
                // نستخدم Gson لبناء البروفايل وحقن البيانات مباشرة دون المرور بشروط النواة
                val gson = Gson()
                var profile: ProfileItem? = null
                
                try {
                    val mapStr = mapOf("configType" to "CUSTOM", "remarks" to finalRemarks, "server" to jsonConfig)
                    profile = gson.fromJson(gson.toJson(mapStr), ProfileItem::class.java)
                } catch (e: Exception) {
                    try {
                        val mapInt = mapOf("configType" to 1, "remarks" to finalRemarks, "server" to jsonConfig)
                        profile = gson.fromJson(gson.toJson(mapInt), ProfileItem::class.java)
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }

                if (profile != null) {
                    val guid = Utils.getUuid()
                    
                    // حفظ السيرفر وتحديده كالسيرفر الافتراضي فوراً لتشغيله
                    MmkvManager.encodeServerConfig(guid, profile)
                    MmkvManager.setSelectServer(guid)
                    
                    Toast.makeText(this, "تم الحفظ والتحديد بنجاح! جاهز للتشغيل 🚀", Toast.LENGTH_SHORT).show()
                    finish() // العودة للصفحة الرئيسية
                } else {
                    Toast.makeText(this, "فشل في بناء البروفايل، يرجى المحاولة مجدداً", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطأ غير متوقع أثناء الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // دالة توليد JSON المخصص (Chained Proxies: VLESS -> HTTP Proxy)
    private fun generateAshorPayload(vlessAddr: String, vlessPort: String, uuid: String, proxyIp: String, proxyPort: String, sni: String, bsid: String): String {
        return """
        {
          "log": {
            "loglevel": "warning"
          },
          "dns": {
            "servers": [
              "8.8.8.8"
            ]
          },
          "inbounds": [
            {
              "listen": "0.0.0.0",
              "port": "1080",
              "protocol": "dokodemo-door",
              "settings": {
                "network": "tcp,udp",
                "followRedirect": true
              },
              "tag": "tun-inbound"
            },
            {
              "listen": "127.0.0.1",
              "port": "10808",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              },
              "tag": "socks-inbound"
            }
          ],
          "outbounds": [
            {
              "mux": {
                "enabled": false
              },
              "protocol": "vless",
              "proxySettings": {
                "tag": "alrufaaey",
                "transportLayer": true
              },
              "settings": {
                "vnext": [
                  {
                    "address": "$vlessAddr",
                    "port": ${vlessPort.toIntOrNull() ?: 443},
                    "users": [
                      {
                        "encryption": "none",
                        "id": "$uuid",
                        "level": 8
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "tls",
                "tlsSettings": {
                  "allowInsecure": true,
                  "serverName": "$sni"
                }
              },
              "tag": "VLESS"
            },
            {
              "domainStrategy": "AsIs",
              "protocol": "http",
              "settings": {
                "servers": [
                  {
                    "address": "$proxyIp",
                    "port": ${proxyPort.toIntOrNull() ?: 8080}
                  }
                ],
                "headers": {
                  "Host": "$sni:443",
                  "Proxy-Connection": "keep-alive",
                  "User-Agent": "Mozilla/5.0 (Linux; Android 14; SM-A245F Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.122 Mobile Safari/537.36 [FBAN/InternetOrgApp;FBAV/166.0.0.0.169;]",
                  "X-iorg-bsid": "$bsid"
                }
              },
              "tag": "alrufaaey"
            },
            {
              "protocol": "freedom",
              "tag": "direct"
            },
            {
              "protocol": "blackhole",
              "tag": "block"
            }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {
                "type": "field",
                "protocol": ["dns"],
                "outboundTag": "direct"
              },
              {
                "type": "field",
                "inboundTag": [
                  "tun-inbound",
                  "socks-inbound"
                ],
                "outboundTag": "VLESS"
              }
            ]
          },
          "policy": {
            "levels": {
              "8": {
                "connIdle": 300,
                "downlinkOnly": 1,
                "handshake": 4,
                "uplinkOnly": 1
              }
            }
          }
        }
        """.trimIndent()
    }
}
