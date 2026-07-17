package com.v2ray.ang.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.io.File

class ServerAshorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_ashor)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val etRemarks = findViewById<EditText>(R.id.et_remarks)
        val etAddress = findViewById<EditText>(R.id.et_address)
        val etPort = findViewById<EditText>(R.id.et_port)
        val rbVless = findViewById<RadioButton>(R.id.rb_vless)
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

            if (serverAddress.isEmpty() || serverPort.isEmpty() || uuid.isEmpty() || proxyIp.isEmpty() || sni.isEmpty()) {
                Toast.makeText(this, "يرجى ملء الحقول الأساسية", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val jsonConfig = generateAshorPayload(rbVless.isChecked, serverAddress, serverPort, uuid, proxyIp, proxyPort, sni, bsid, payload, cbTls.isChecked)
            
            try {
                val guid = Utils.getUuid()
                val file = File(filesDir, "ashor_$guid.json")
                file.writeText(jsonConfig)

                // 🌟 الحل الجذري: إعطاء قيم وهمية للنواة لكي لا توقف المحرك 🌟
                val profile = ProfileItem.create(EConfigType.CUSTOM)
                profile.remarks = if (remarks.isNotEmpty()) remarks else "Ashor: $sni"
                profile.server = "127.0.0.1"      // قيمة وهمية للواجهة
                profile.serverPort = "1080"       // قيمة وهمية للواجهة
                profile.description = "Ashor Payload ⚡"
                
                MmkvManager.encodeServerConfig(guid, profile)
                MmkvManager.encodeServerRaw(guid, jsonConfig) // المحرك يقرأ هذا فقط
                MmkvManager.setSelectServer(guid)
                
                Toast.makeText(this, "تم الحفظ! الآن اضغط تشغيل في الشاشة الرئيسية", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطأ في الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateAshorPayload(
        isVless: Boolean, vlessAddr: String, vlessPort: String, uuid: String, 
        proxyIp: String, proxyPort: String, sni: String, bsid: String, payload: String, useTls: Boolean
    ): String {
        val tlsBlock = if (useTls) "\"security\": \"tls\", \"tlsSettings\": { \"allowInsecure\": true, \"serverName\": \"$sni\" }" else "\"security\": \"none\""
        
        val targetProtocolBlock = if (isVless) """
            {
              "protocol": "vless",
              "settings": { "vnext": [ { "address": "$vlessAddr", "port": ${vlessPort.toIntOrNull() ?: 443}, "users": [ { "encryption": "none", "id": "$uuid", "level": 8 } ] } ] },
              "streamSettings": { "network": "tcp", $tlsBlock, "sockopt": { "dialerProxy": "alrufaaey" } },
              "tag": "VLESS"
            }
        """ else """
            {
              "protocol": "trojan",
              "settings": { "servers": [ { "address": "$vlessAddr", "level": 8, "password": "$uuid", "port": ${vlessPort.toIntOrNull() ?: 443} } ] },
              "streamSettings": { "network": "tcp", $tlsBlock, "sockopt": { "dialerProxy": "alrufaaey" } },
              "tag": "TROJAN"
            }
        """

        val outboundTag = if (isVless) "VLESS" else "TROJAN"
        val userAgent = if (payload.isNotEmpty()) payload else "Mozilla/5.0 (Linux; Android 14; ...)"

        return """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [ { "listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": { "auth": "noauth", "udp": true }, "tag": "socks-inbound" } ],
          "outbounds": [
            $targetProtocolBlock,
            {
              "protocol": "http",
              "settings": { "servers": [ { "address": "$proxyIp", "port": ${proxyPort.toIntOrNull() ?: 8080} } ] },
              "streamSettings": { "sockopt": { "tcpFastOpen": true } },
              "tag": "alrufaaey",
              "headers": { "Host": "$sni:443", "Proxy-Connection": "keep-alive", "User-Agent": "$userAgent", "X-iorg-bsid": "$bsid" }
            },
            { "protocol": "freedom", "tag": "direct" }
          ],
          "routing": { "rules": [ { "type": "field", "inboundTag": ["socks-inbound"], "outboundTag": "$outboundTag" } ] }
        }
        """.trimIndent()
    }
}
