package com.v2ray.ang.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.io.File

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
        
        // ربط الحقول الخاصة بالبروتوكول والتشفير
        val rbVless = findViewById<RadioButton>(R.id.rb_vless)
        val rbTrojan = findViewById<RadioButton>(R.id.rb_trojan)
        val cbTls = findViewById<CheckBox>(R.id.cb_tls)
        
        // ربط حقول الاتصال والحاقن
        val etId = findViewById<EditText>(R.id.et_id)
        val etProxyIp = findViewById<EditText>(R.id.et_proxy_ip)
        val etProxyPort = findViewById<EditText>(R.id.et_proxy_port)
        val etSni = findViewById<EditText>(R.id.et_sni)
        val etPayload = findViewById<EditText>(R.id.et_payload)
        val etBsid = findViewById<EditText>(R.id.et_bsid)
        
        // زر الحفظ الجديد (بدون اتصال تلقائي لمنع الخروج المفاجئ)
        val btnSaveConfig = findViewById<MaterialButton>(R.id.btn_save_config)

        btnSaveConfig.setOnClickListener {
            val remarks = etRemarks.text.toString().trim()
            val serverAddress = etAddress.text.toString().trim()
            val serverPort = etPort.text.toString().trim()
            val uuid = etId.text.toString().trim()
            val proxyIp = etProxyIp.text.toString().trim()
            val proxyPort = etProxyPort.text.toString().trim()
            val sni = etSni.text.toString().trim()
            val payload = etPayload.text.toString().trim()
            val bsid = etBsid.text.toString().trim()

            val isVless = rbVless?.isChecked ?: true
            val useTls = cbTls?.isChecked ?: true

            // التحقق من أن الحقول المهمة غير فارغة
            if (serverAddress.isEmpty() || serverPort.isEmpty() || uuid.isEmpty() || proxyIp.isEmpty() || sni.isEmpty()) {
                Toast.makeText(this, "يرجى ملء جميع الحقول المطلوبة الأساسية", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // توليد الـ JSON الاحترافي والثغرة
            val jsonConfig = generateAshorPayload(isVless, serverAddress, serverPort, uuid, proxyIp, proxyPort, sni, bsid, payload, useTls)
            val finalRemarks = if (remarks.isNotEmpty()) remarks else "Ashor: $sni"

            try {
                // 🌟 الحل الجذري لمنع الخروج المفاجئ (Crash): حفظ الـ JSON كملف وتمرير مساره 🌟
                val guid = Utils.getUuid()
                val fileName = "ashor_config_${guid}.json"
                val file = File(filesDir, fileName)
                file.writeText(jsonConfig)
                
                val fileAbsolutePath = file.absolutePath

                // الحل السحري لتجاوز أخطاء المترجم (Build Errors) باستخدام Gson
                val gson = Gson()
                var profile: ProfileItem? = null
                
                try {
                    val mapStr = mapOf("configType" to "CUSTOM", "remarks" to finalRemarks, "server" to fileAbsolutePath)
                    profile = gson.fromJson(gson.toJson(mapStr), ProfileItem::class.java)
                } catch (e: Exception) {
                    try {
                        val mapInt = mapOf("configType" to 2, "remarks" to finalRemarks, "server" to fileAbsolutePath)
                        profile = gson.fromJson(gson.toJson(mapInt), ProfileItem::class.java)
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }

                if (profile != null) {
                    // حفظ السيرفر وتحديده كالسيرفر الافتراضي
                    MmkvManager.encodeServerConfig(guid, profile)
                    MmkvManager.setSelectServer(guid)
                    
                    Toast.makeText(this, "تم حفظ التكوين بنجاح! يمكنك تشغيله الآن 🚀", Toast.LENGTH_SHORT).show()
                    finish() // العودة للصفحة الرئيسية للتشغيل اليدوي بأمان
                } else {
                    Toast.makeText(this, "فشل في بناء البروفايل، يرجى المحاولة مجدداً", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطأ غير متوقع أثناء الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // دالة توليد JSON المخصص بدمج VLESS و Trojan ودعم البايلود الجديد والـ TLS
    private fun generateAshorPayload(
        isVless: Boolean, vlessAddr: String, vlessPort: String, uuid: String, 
        proxyIp: String, proxyPort: String, sni: String, bsid: String, payload: String, useTls: Boolean
    ): String {
        
        val tlsBlock = if (useTls) {
            """
            "security": "tls",
            "tlsSettings": {
              "allowInsecure": true,
              "serverName": "$sni"
            }
            """
        } else {
            """
            "security": "none"
            """
        }

        val targetProtocolBlock = if (isVless) {
            """
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
                $tlsBlock
              },
              "tag": "VLESS"
            }
            """
        } else {
            """
            {
              "mux": {
                "enabled": false
              },
              "protocol": "trojan",
              "proxySettings": {
                "tag": "alrufaaey",
                "transportLayer": true
              },
              "settings": {
                "servers": [
                  {
                    "address": "$vlessAddr",
                    "level": 8,
                    "password": "$uuid",
                    "port": ${vlessPort.toIntOrNull() ?: 443}
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                $tlsBlock
              },
              "tag": "TROJAN"
            }
            """
        }

        val outboundTag = if (isVless) "VLESS" else "TROJAN"
        
        // إذا ترك المستخدم حقل البايلود فارغاً نستخدم الافتراضي
        val userAgent = if (payload.isNotEmpty()) payload else "Mozilla/5.0 (Linux; Android 14; SM-A245F Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.122 Mobile Safari/537.36 [FBAN/InternetOrgApp;FBAV/166.0.0.0.169;]"

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
            $targetProtocolBlock,
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
                  "User-Agent": "$userAgent",
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
                "outboundTag": "$outboundTag"
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
