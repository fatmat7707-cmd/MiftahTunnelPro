package com.miftah.tunnelpro

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.VpnService
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerEndElapsed: Long = 0L
    private var timerRunnable: Runnable? = null

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
            text = "MIFTAH TUNNEL PRO  •  الإصدار 0.9.0"
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
        col.addView(button("عرض سجل الفحوصات السابقة") { showDiagnosticHistory() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("مسح سجل الفحوصات") { clearDiagnosticHistory() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("نسخ التقرير الحالي") { copyCurrentReport() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("تصدير سجل الفحوصات CSV") { exportHistoryCsv() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("تنقيب وتحليل بيانات الشبكة") { analyzeNetworkData() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("⏱️ مؤقت مدة الاشتراك") { configureSessionTimer() }, lp(-1, -2, 0, 0, 0, 8))
        col.addView(button("📶 قياس استقرار الاتصال") { measureConnectionStability() }, lp(-1, -2, 0, 0, 0, 8))

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




    private fun measureConnectionStability() {
        details.text = "جارٍ قياس استقرار الاتصال بثلاث محاولات خفيفة..."
        executor.execute {
            val samples = mutableListOf<Long>()
            val outcomes = mutableListOf<String>()
            val network = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork
            repeat(3) { index ->
                var connection: HttpURLConnection? = null
                val started = SystemClock.elapsedRealtime()
                try {
                    if (network == null) throw IllegalStateException("لا توجد شبكة نشطة")
                    connection = network.openConnection(URL("https://connectivitycheck.gstatic.com/generate_204")) as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    val code = connection.responseCode
                    val elapsed = SystemClock.elapsedRealtime() - started
                    samples.add(elapsed)
                    outcomes.add("المحاولة ${index + 1}: HTTP ${code} — ${elapsed} ms")
                } catch (e: Exception) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    outcomes.add("المحاولة ${index + 1}: فشلت بعد ${elapsed} ms (${e.javaClass.simpleName})")
                } finally {
                    connection?.disconnect()
                }
                if (index < 2) Thread.sleep(700)
            }
            val average = if (samples.isEmpty()) null else samples.average().toLong()
            val minimum = samples.minOrNull()
            val maximum = samples.maxOrNull()
            val report = buildString {
                append("تقرير استقرار الاتصال\\n\\n")
                append(outcomes.joinToString("\\n"))
                append("\\n\\nنجاح الاستجابة: ${samples.size}/3")
                if (average != null) {
                    append("\\nمتوسط زمن الاستجابة: ${average} ms")
                    append("\\nأسرع استجابة: ${minimum} ms")
                    append("\\nأبطأ استجابة: ${maximum} ms")
                }
                append("\\n\\nالتقييم: ")
                append(when {
                    samples.size < 2 -> "الاتصال غير مستقر أو خادم الاختبار غير متاح"
                    average!! < 300 -> "استجابة جيدة في هذا الاختبار القصير"
                    average < 800 -> "استجابة متوسطة"
                    else -> "استجابة بطيئة"
                })
                append("\\n\\nهذا اختبار HTTPS محدود بثلاث محاولات إلى خادم اختبار عام، وليس قياس سرعة تنزيل/رفع أو اختبار ضغط أو مسحاً للشبكة.")
            }
            runOnUiThread { details.text = report }
        }
    }

    private fun configureSessionTimer() {
        val input = EditText(this).apply {
            hint = "مثلاً: 120"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("مؤقت مدة الاشتراك")
            .setMessage("أدخل عدد الدقائق المسموح بها في اشتراكك حسب بيانات البطاقة أو صفحة الحساب. هذا مؤقت محلي للتذكير ولا يغيّر وقت الشبكة أو صلاحياتها.")
            .setView(input)
            .setPositiveButton("بدء المؤقت") { _, _ ->
                val minutes = input.text.toString().toLongOrNull()
                if (minutes == null || minutes !in 1..10080) {
                    Toast.makeText(this, "أدخل مدة بين دقيقة و7 أيام", Toast.LENGTH_LONG).show()
                } else startSessionTimer(minutes * 60_000L)
            }
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("إيقاف المؤقت") { _, _ -> stopSessionTimer() }
            .show()
    }

    private fun startSessionTimer(durationMs: Long) {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerEndElapsed = SystemClock.elapsedRealtime() + durationMs
        val tick = object : Runnable {
            override fun run() {
                val remaining = (timerEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val totalSeconds = remaining / 1000L
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                details.text = "⏱️ مؤقت مدة الاشتراك\\n\\nالوقت المتبقي حسب المدة التي أدخلتها: %02d:%02d:%02d".format(hours, minutes, seconds) +
                    "\\n\\nهذا عدّاد محلي فقط. لا يقرأ رصيد الشبكة ولا يمدد الجلسة ولا يغيّر إعدادات الراوتر."
                if (remaining == 0L) {
                    Toast.makeText(this@MainActivity, "انتهى المؤقت الذي ضبطته", Toast.LENGTH_LONG).show()
                    timerRunnable = null
                } else timerHandler.postDelayed(this, 1000L)
            }
        }
        timerRunnable = tick
        timerHandler.post(tick)
        Toast.makeText(this, "بدأ مؤقت التذكير", Toast.LENGTH_SHORT).show()
    }

    private fun stopSessionTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        timerEndElapsed = 0L
        details.text = "تم إيقاف مؤقت مدة الاشتراك."
    }

    private fun analyzeNetworkData() {
        val raw = getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val history = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
        if (history.length() == 0) {
            details.text = "لا توجد بيانات كافية للتحليل. نفّذ فحص الشبكة أو احفظ حالة مرجعية أولاً."
            return
        }
        val total = history.length()
        var wifiCount = 0
        var validatedCount = 0
        var captiveCount = 0
        var httpsOk = 0
        val gateways = linkedMapOf<String, Int>()
        val dnsServers = linkedMapOf<String, Int>()
        val failures = mutableListOf<String>()
        for (i in 0 until total) {
            val item = history.getJSONObject(i)
            if (item.optBoolean("wifi")) wifiCount++
            if (item.optBoolean("validated")) validatedCount++
            if (item.optBoolean("captive")) captiveCount++
            val https = item.optString("https")
            if (https.contains("204")) httpsOk++
            else if (https.isNotBlank()) failures.add(item.optString("time") + ": " + https)
            val gateway = item.optString("gateway", "غير متاح")
            gateways[gateway] = (gateways[gateway] ?: 0) + 1
            item.optString("dns", "").split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                dnsServers[it] = (dnsServers[it] ?: 0) + 1
            }
        }
        val first = history.getJSONObject(0)
        val last = history.getJSONObject(total - 1)
        val changes = listOf("gateway", "dns", "addresses").count { first.optString(it) != last.optString(it) }
        val pct: (Int) -> Int = { count -> (count * 100) / total }
        val out = StringBuilder()
        out.append("تحليل بيانات الشبكة المحفوظة\\n")
        out.append("عدد العينات: ").append(total).append(" (آخر 50 عينة كحد أقصى)\\n")
        out.append("الفترة: ").append(first.optString("time")).append(" — ").append(last.optString("time")).append("\\n\\n")
        out.append("مؤشرات الاتصال\\n")
        out.append("عينات Wi-Fi: ").append(wifiCount).append("/").append(total).append(" (").append(pct(wifiCount)).append("%)\\n")
        out.append("عينات الإنترنت المتحقق: ").append(validatedCount).append("/").append(total).append(" (").append(pct(validatedCount)).append("%)\\n")
        out.append("عينات رصد بوابة دخول: ").append(captiveCount).append("\\n")
        out.append("استجابات HTTPS الطبيعية 204: ").append(httpsOk).append("/").append(total).append("\\n")
        out.append("تغيرت بيانات البوابة/DNS/العنوان بين أول وآخر عينة في ").append(changes).append(" من 3 حقول\\n\\n")
        out.append("البوابات الأكثر ظهوراً\\n")
        gateways.entries.sortedByDescending { it.value }.take(5).forEach { out.append(it.key).append(" — ").append(it.value).append(" مرة\\n") }
        out.append("\\nخوادم DNS المسجلة\\n")
        if (dnsServers.isEmpty()) out.append("لا توجد بيانات DNS\\n")
        else dnsServers.entries.sortedByDescending { it.value }.take(8).forEach { out.append(it.key).append(" — ").append(it.value).append(" مرة\\n") }
        out.append("\\nآخر ملاحظات فشل HTTPS\\n")
        if (failures.isEmpty()) out.append("لا توجد حالات فشل مسجلة\\n")
        else failures.takeLast(5).forEach { out.append(it).append("\\n") }
        out.append("\\nالتحليل يعتمد فقط على سجلات هذا الهاتف التي جمعها التطبيق. لا يمسح أجهزة الآخرين ولا يجمع كلمات مرور أو حركة مرور خاصة، ولا يثبت وجود ثغرة.")
        details.text = out.toString()
    }

    private fun copyCurrentReport() {
        val report = details.text?.toString()?.trim().orEmpty()
        if (report.isBlank()) { Toast.makeText(this, "لا يوجد تقرير لنسخه", Toast.LENGTH_SHORT).show(); return }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("تقرير تشخيص الشبكة", report))
        Toast.makeText(this, "تم نسخ التقرير", Toast.LENGTH_SHORT).show()
    }

    private fun exportHistoryCsv() {
        val raw = getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val history = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
        if (history.length() == 0) { Toast.makeText(this, "لا يوجد سجل لتصديره", Toast.LENGTH_SHORT).show(); return }
        val csv = StringBuilder("time,wifi,validated,captive,gateway,dns,addresses,https\\n")
        for (i in 0 until history.length()) {
            val item = history.getJSONObject(i)
            val values = listOf("time","wifi","validated","captive","gateway","dns","addresses","https").map { item.optString(it) }
            csv.append(values.joinToString(",") { "\\"" + it.replace("\\"", "\\"\\"") + "\\"" }).append("\\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "سجل فحوصات Miftah Tunnel Pro")
            putExtra(Intent.EXTRA_TEXT, csv.toString())
        }
        startActivity(Intent.createChooser(send, "تصدير سجل الفحوصات"))
    }
    private fun appendDiagnosticHistory(snapshot: JSONObject) {
        val prefs = getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE)
        val history = try { org.json.JSONArray(prefs.getString("history", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        history.put(snapshot)
        val trimmed = org.json.JSONArray()
        val start = (history.length() - 50).coerceAtLeast(0)
        for (i in start until history.length()) trimmed.put(history.getJSONObject(i))
        prefs.edit().putString("history", trimmed.toString()).apply()
    }

    private fun showDiagnosticHistory() {
        val raw = getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val history = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
        if (history.length() == 0) { details.text = "لا توجد فحوصات محفوظة بعد."; return }
        val out = StringBuilder("سجل الفحوصات المحفوظة — الأحدث أولاً\\n\\n")
        for (i in history.length() - 1 downTo 0) {
            val item = history.getJSONObject(i)
            out.append("#").append(i + 1).append("  ").append(item.optString("time")).append("\\n")
            out.append("Wi-Fi: ").append(if (item.optBoolean("wifi")) "متصل" else "غير متصل")
            out.append(" | الإنترنت متحقق: ").append(if (item.optBoolean("validated")) "نعم" else "لا").append("\\n")
            out.append("بوابة: ").append(item.optString("gateway")).append("\\n")
            out.append("HTTPS: ").append(item.optString("https")).append("\\n\\n")
        }
        details.text = out.toString()
    }

    private fun clearDiagnosticHistory() {
        getSharedPreferences("network_diagnostics", Context.MODE_PRIVATE).edit().remove("history").apply()
        details.text = "تم مسح سجل الفحوصات المحفوظ على هذا الجهاز."
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
        }.also { appendDiagnosticHistory(it) }
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
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        executor.shutdown()
    }

    private fun lp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(l, t, r, b) }
}
