package com.miftah.tunnelpro

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.*
import android.graphics.Color
import android.view.Gravity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(11, 20, 34)
    private val panel = Color.rgb(22, 38, 58)
    private val mint = Color.rgb(36, 214, 164)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var profile: TextView
    private lateinit var connectButton: Button
    private var selectedConfig: Config? = null
    private var tunnelName = "MiftahTunnel"
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                status.text = if (newState == Tunnel.State.UP) "النفق متصل" else "النفق غير متصل"
                status.setTextColor(if (newState == Tunnel.State.UP) mint else Color.WHITE)
                connectButton.text = if (newState == Tunnel.State.UP) "إيقاف النفق" else "تشغيل النفق"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        buildUi()
        refreshNetwork()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 30)
            setBackgroundColor(bg)
        }
        scroll.addView(col)
        col.addView(TextView(this).apply {
            text = "مفتاح النفق الذكي"
            textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 8, 0, 4))
        col.addView(TextView(this).apply {
            text = "MIFTAH TUNNEL PRO  •  الإصدار 0.2.0"
            textSize = 12f; setTextColor(0xFF9EB2C8.toInt()); gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 0, 0, 22))

        status = TextView(this).apply {
            text = "النفق غير متصل"
            textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(12, 24, 12, 24); setBackgroundColor(panel)
        }
        col.addView(status, lp(-1, -2, 0, 0, 0, 12))

        col.addView(button("استيراد ملف WireGuard (.conf)") { pickConfig() }, lp(-1, -2, 0, 0, 0, 8))
        profile = TextView(this).apply {
            text = "لم يتم اختيار ملف إعداد بعد"
            textSize = 13f; setTextColor(0xFFB8C9DA.toInt()); setPadding(12, 12, 12, 12); setBackgroundColor(panel)
        }
        col.addView(profile, lp(-1, -2, 0, 0, 0, 8))

        connectButton = button("تشغيل النفق") { toggleTunnel() }
        connectButton.isEnabled = false
        col.addView(connectButton, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("فحص اتصال الإنترنت") { refreshNetwork() }, lp(-1, -2, 0, 0, 0, 8))

        details = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setPadding(16, 16, 16, 16); setBackgroundColor(panel)
        }
        col.addView(details, lp(-1, -2, 0, 12, 0, 12))

        col.addView(TextView(this).apply {
            text = "تنبيه: يحتاج النفق إلى ملف WireGuard صالح وخادم تملك حق استخدامه. الـVPN لا يوقف عداد الوقت أو البيانات في Hotspot ولا يتجاوز انتهاء البطاقة. لا تستورد ملفات من مصدر مجهول؛ ملف WireGuard يحتوي مفتاحاً خاصاً."
            textSize = 13f; setTextColor(0xFFFFD88A.toInt()); setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(55, 43, 24))
        }, lp(-1, -2, 0, 8, 0, 0))
        setContentView(scroll)
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(20, 105, 88))
            setOnClickListener { action() }
        }

    private fun pickConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 702)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 702 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            loadConfig(uri)
        }
        if (requestCode == 701 && resultCode == RESULT_OK) {
            startTunnel()
        } else if (requestCode == 701 && resultCode != RESULT_OK) {
            Toast.makeText(this, "لم يتم منح إذن VPN", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadConfig(uri: Uri) {
        try {
            val config = contentResolver.openInputStream(uri)?.use { Config.parse(it) }
                ?: throw IllegalArgumentException("تعذر قراءة الملف")
            selectedConfig = config
            tunnelName = "MiftahTunnel"
            profile.text = "تم تحليل ملف WireGuard بنجاح. جاهز للاتصال."
            connectButton.isEnabled = true
            status.text = "الإعداد جاهز — النفق غير متصل"
            details.text = "ملف الإعداد صالح من ناحية الصياغة.\n\nملاحظة: نجاح التحليل لا يضمن أن الخادم متاح أو أن المفاتيح صحيحة. سيتم اختبار الاتصال عند التشغيل."
        } catch (e: Exception) {
            selectedConfig = null
            connectButton.isEnabled = false
            profile.text = "تعذر قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}"
        }
    }

    private fun toggleTunnel() {
        val cfg = selectedConfig ?: return
        val b = backend ?: return
        executor.execute {
            try {
                val state = b.getState(tunnel)
                if (state == Tunnel.State.UP) {
                    b.setState(tunnel, Tunnel.State.DOWN, null)
                    return@execute
                }
                runOnUiThread {
                    val prepare = VpnService.prepare(this)
                    if (prepare != null) {
                        startActivityForResult(prepare, 701)
                    } else {
                        startTunnel()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "تعذر تغيير حالة النفق: ${e.message}", Toast.LENGTH_LONG).show()
                    status.text = "تعذر تشغيل النفق"
                }
            }
        }
    }

    private fun startTunnel() {
        val cfg = selectedConfig ?: return
        executor.execute {
            try {
                backend?.setState(tunnel, Tunnel.State.UP, cfg)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "تعذر تشغيل النفق: ${e.message}", Toast.LENGTH_LONG).show()
                    status.text = "فشل الاتصال — تحقق من الخادم"
                    connectButton.text = "تشغيل النفق"
                }
            }
        }
    }

    private fun refreshNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        details.text = "فحص الشبكة\n\nWi-Fi: ${if (wifi) "متصل" else "غير متصل"}\nإمكانية الإنترنت: ${if (internet) "معلنة" else "غير معلنة"}\nتم التحقق من الإنترنت: ${if (validated) "نعم" else "لا"}\n\nيمكن استيراد ملف WireGuard صالح ثم طلب إذن VPN وتشغيل النفق."
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun lp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(l, t, r, b) }
}
