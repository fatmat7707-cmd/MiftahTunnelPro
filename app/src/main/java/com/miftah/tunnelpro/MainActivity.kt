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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(11, 20, 34)
    private val panel = Color.rgb(22, 38, 58)
    private val mint = Color.rgb(36, 214, 164)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var profile: TextView
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var shareButton: Button
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
            text = "MIFTAH TUNNEL PRO  •  الإصدار 0.5.0"
            textSize = 12f; setTextColor(0xFF9EB2C8.toInt()); gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 0, 0, 22))

        status = TextView(this).apply {
            text = "النفق غير متصل"
            textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(12, 24, 12, 24); setBackgroundColor(panel)
        }
        col.addView(status, lp(-1, -2, 0, 0, 0, 12))

        col.addView(button("فحص اتصال الإنترنت") { refreshNetwork() }, lp(-1, -2, 0, 0, 0, 8))
        scanButton = button("تحليل الشبكة والبوابة") { runNetworkAssessment() }
        col.addView(scanButton, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("حفظ الحالة الحالية للمقارنة") { saveBaseline() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("مقارنة الحالة الحالية بالحالة المحفوظة") { compareWithBaseline() }, lp(-1, -2, 0, 0, 0, 8))
        shareButton = button("مشاركة تقرير التشخيص") { shareDiagnosticReport() }
        col.addView(shareButton, lp(-1, -2, 0, 0, 0, 8))

        profile = TextView(this).apply {
            text = "لم يتم اختيار ملف إعداد بعد"
            textSize = 13f; setTextColor(0xFFB8C9DA.toInt()); setPadding(12, 12, 12, 12); setBackgroundColor(panel)
        }
        col.addView(button("استيراد ملف WireGuard (.conf)") { pickConfig() }, lp(-1, -2, 0, 12, 0, 8))
        col.addView(profile, lp(-1, -2, 0, 0, 0, 8))

        connectButton = button("تشغيل النفق") { toggleTunnel() }
        connectButton.isEnabled = false
        col.addView(connectButton, lp(-1, -2, 0, 0, 0, 8))

        details = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setPadding(16, 16, 16, 16); setBackgroundColor(panel)
        }
        col.addView(details, lp(-1, -2, 0, 12, 0, 12))

        col.addView(TextView(this).apply {
            text = "الفحص تشخيصي غير تخريبي: يقرأ معلومات الاتصال والبوابة وDNS ويتحقق من استجابة اختبار اتصال قياسي. لا يخترق كلمة مرور ولا يتجاوز بطاقة أو نظام دفع. ظهور مؤشر غير طبيعي ليس دليلاً وحده على وجود ثغرة.\n\nيحتاج النفق إلى ملف WireGuard صالح وخادم تملك حق استخدامه. لا تستورد ملفات من مصدر مجهول؛ ملف WireGuard يحتوي مفتاحاً خاصاً."
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
            details.text = "ملف الإعداد صالح من ناحية الصياغة.\n\nنجاح التحليل لا يضمن أن الخادم متاح أو أن المفاتيح صحيحة. سيتم اختبار الاتصال عند التشغيل."
        } catch (e: Exception) {
            selectedConfig = null
            connectButton.isEnabled = false
            profile.text = "تعذر قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}"
        }
    }

    private fun toggleTunnel() {
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
                    if (prepare != null) startActivityForResult(prepare, 701) else startTunnel()
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
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        details.text = "حالة الاتصال\n\nWi-Fi: ${if (wifi) "متصل" else "غير متصل"}\nإمكانية الإنترنت: ${if (internet) "معلنة" else "غير معلنة"}\nالإنترنت متحقق منه: ${if (validated) "نعم" else "لا"}\nبوابة دخول أسيرة مكتشفة من Android: ${if (captive) "نعم" else "لا"}\n\nاضغط «تحليل الشبكة والبوابة» للحصول على تفاصيل إضافية."
    }

    private fun runNetworkAssessment() {
        scanButton.isEnabled = false
        scanButton.text = "جارٍ تحليل الشبكة..."
        details.text = "جارٍ جمع معلومات الشبكة وإجراء اختبار اتصال قياسي..."
        executor.execute {
            val report = try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(network)
                val props = cm.getLinkProperties(network)
                val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
                val gateway = props?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "غير متاح"
                val dns = props?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" }?.ifBlank { "غير متاح" } ?: "غير متاح"
                val localAddress = props?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "" }?.ifBlank { "غير متاح" } ?: "غير متاح"
                val probe = checkStandardConnectivity(network)
                buildString {
                    append("تقرير تحليل الشبكة\n\n")
                    append("نوع الاتصال: ${if (wifi) "Wi-Fi" else "ليس Wi-Fi أو غير معروف"}\n")
                    append("عنوان الجهاز: $localAddress\n")
                    append("البوابة الافتراضية: $gateway\n")
                    append("خوادم DNS: $dns\n\n")
                    append("Android يعتبر الإنترنت متحققاً: ${if (validated) "نعم" else "لا"}\n")
                    append("Android رصد بوابة دخول: ${if (captive) "نعم" else "لا"}\n")
                    append("اختبار HTTPS القياسي: $probe\n\n")
                    append("التفسير: هذه بيانات تشخيصية تساعد على تحديد موضع مشكلة الاتصال أو صفحة الدخول. لا تثبت وجود ثغرة، ولا تختبر تجاوز المصادقة أو الدفع.")
                }
            } catch (e: Exception) {
                "تعذر إكمال التحليل: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                details.text = report
                scanButton.isEnabled = true
                scanButton.text = "إعادة تحليل الشبكة والبوابة"
            }
        }
    }

    private fun shareDiagnosticReport() {
        val report = details.text?.toString()?.trim().orEmpty()
        if (report.isBlank()) {
            Toast.makeText(this, "نفّذ الفحص أولاً لإنشاء تقرير", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "تقرير تشخيص الشبكة - " + stamp)
            putExtra(Intent.EXTRA_TEXT, "Miftah Tunnel Pro 0.5.0\\n" + report + "\\n\\nتقرير تشخيصي فقط؛ لا يثبت وجود ثغرة.")
        }
        startActivity(Intent.createChooser(send, "مشاركة تقرير التشخيص"))
    }
    private fun collectSnapshot(): JSONObject {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val props = cm.getLinkProperties(network)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return JSONObject().apply {
            put("time", stamp)
            put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("captive", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            put("gateway", props?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "غير متاح")
            put("dns", props?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" } ?: "غير متاح")
            put("addresses", props?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "" } ?: "غير متاح")
            put("https", checkStandardConnectivity(network))
        }
    }

    private fun saveBaseline() {
        details.text = "جارٍ حفظ الحالة المرجعية..."
        executor.execute {
            val snap = try { collectSnapshot() } catch (e: Exception) { null }
            runOnUiThread {
                if (snap == null) details.text = "تعذر حفظ الحالة الحالية." else {
                    getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).edit().putString("baseline", snap.toString()).apply()
                    details.text = "تم حفظ الحالة المرجعية.\\n\\n" + snap.toString(2) + "\\n\\nبعد انتهاء جلسة الاختبار المصرح بها، اضغط زر المقارنة."
                }
            }
        }
    }

    private fun compareWithBaseline() {
        val saved = getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).getString("baseline", null)
        if (saved == null) { details.text = "لا توجد حالة مرجعية. احفظ الحالة الحالية أولاً."; return }
        details.text = "جارٍ جمع الحالة الحالية ومقارنتها..."
        executor.execute {
            val current = try { collectSnapshot() } catch (e: Exception) { null }
            runOnUiThread {
                if (current == null) { details.text = "تعذر جمع الحالة الحالية."; return@runOnUiThread }
                val base = JSONObject(saved)
                val keys = listOf("wifi", "validated", "captive", "gateway", "dns", "addresses", "https")
                val labels = listOf("Wi-Fi", "تحقق Android من الإنترنت", "رصد بوابة الدخول", "البوابة", "DNS", "عناوين الجهاز", "اختبار HTTPS")
                val changes = keys.indices.filter { base.optString(keys[it]) != current.optString(keys[it]) }
                val result = if (changes.isEmpty()) "لم يتغير أي مؤشر مسجل." else changes.joinToString("\\n\\n") { i -> labels[i] + "\\nقبل: " + base.optString(keys[i]) + "\\nالآن: " + current.optString(keys[i]) }
                details.text = "نتيجة المقارنة\\nالحالة المرجعية: " + base.optString("time") + "\\nالحالة الحالية: " + current.optString("time") + "\\n\\n" + result + "\\n\\nالمقارنة تشخيصية فقط؛ لا تثبت وجود ثغرة ولا تحاول تجاوز تسجيل الدخول أو الدفع."
            }
        }
    }

    private fun checkStandardConnectivity(network: android.net.Network?): String {
        if (network == null) return "لا توجد شبكة نشطة"
        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(URL("https://connectivitycheck.gstatic.com/generate_204")) as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            val code = connection.responseCode
            when {
                code == 204 -> "استجابة 204 طبيعية"
                code in 300..399 -> "إعادة توجيه HTTP $code — قد تكون صفحة دخول أو سياسة شبكة"
                code == 200 -> "استجابة 200 — قد تكون الشبكة تعترض اختبار الاتصال"
                else -> "استجابة HTTP $code"
            }
        } catch (e: Exception) {
            "تعذر الوصول إلى خادم الاختبار (${e.javaClass.simpleName})"
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun lp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(l, t, r, b) }
}
