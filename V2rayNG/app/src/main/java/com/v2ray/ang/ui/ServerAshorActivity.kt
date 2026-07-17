package com.v2ray.ang.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

class ServerAshorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_ashor)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val etRemarks = findViewById<EditText>(R.id.et_remarks)
        val etAddress = findViewById<EditText>(R.id.et_address)
        val etPort = findViewById<EditText>(R.id.et_port)
        val rbVless = findViewById<RadioButton>(R.id.rb_vless)
        val rbTrojan = findViewById<RadioButton>(R.id.rb_trojan)
        val cbTls = findViewById<CheckBox>(R.id.cb_tls)
        val etId = findViewById<EditText>(R.id.et_id)
        val etProxyIp = findViewById<EditText>(R.id.et_proxy_ip)
        val etProxyPort = findViewById<EditText>(R.id.et_proxy_port)
        val etSni = findViewById<EditText>(R.id.et_sni)
        val etPayload = findViewById<EditText>(R.id.et_payload)
        val etBsid = findViewById<EditText>(R.id.et_bsid)
        
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

            if (serverAddress.isEmpty() || serverPort.isEmpty() || uuid.isEmpty() || proxyIp.isEmpty() || sni.isEmpty()) {
                Toast.makeText(this, "يرجى ملء جميع الحقول المطلوبة الأساسية", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val jsonConfig = generateAshorPayload(isVless, serverAddress, serverPort, uuid, proxyIp, proxyPort, sni, bsid, payload, useTls)
            val finalRemarks = if (remarks.isNotEmpty()) remarks else "Ashor: $sni"

            try {
                val guid = Utils.getUuid()

                // بناء البروفايل الآمن لتجنب الكراش
                val profile = ProfileItem.create(EConfigType.CUSTOM)
                profile.remarks = finalRemarks
                // ترك هذه الحقول فارغة لمنع انهيار MainRecyclerAdapter
                profile.server = null
                profile.serverPort = null
                profile.description = "Ashor Payload ⚡"

                // الحفظ المزدوج (الواجهة + النواة)
                MmkvManager.encodeServerConfig(guid, profile)
                MmkvManager.encodeServerRaw(guid, jsonConfig)

                MmkvManager.setSelectServer(guid)
                
                Toast.makeText(this, "تم الحفظ بنجاح! جاهز للتشغيل 🚀", Toast.LENGTH_SHORT).show()
                finish() 
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطأ غير متوقع أثناء الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
