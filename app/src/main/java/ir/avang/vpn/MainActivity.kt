package ir.avang.vpn

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class ServerNode(
    val name: String,
    val host: String,
    val port: Int,
    val rawUri: String,
    var ping: Int = -1
)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var btnMagic: Button
    private lateinit var btnClipboard: Button
    private lateinit var btnQrScan: Button
    private lateinit var btnSubManager: Button
    private lateinit var btnPower: Button

    private var isConnected = false
    private var pulseAnim: ObjectAnimator? = null
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val subLinks = mutableListOf<String>()
    private val serverList = mutableListOf<ServerNode>()
    private var activeServer: ServerNode? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            activateVpnConnection()
        } else {
            Toast.makeText(this, "مجوز VPN صادر نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleImportedText(result.contents.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        btnMagic = findViewById(R.id.btnMagic)
        btnClipboard = findViewById(R.id.btnClipboard)
        btnQrScan = findViewById(R.id.btnQrScan)
        btnSubManager = findViewById(R.id.btnSubManager)
        btnPower = findViewById(R.id.btnPower)

        loadData()
        updateSubCountUI()

        btnClipboard.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clipText.isNullOrEmpty()) {
                Toast.makeText(this, "کلیپ‌بورد خالی است!", Toast.LENGTH_SHORT).show()
            } else {
                handleImportedText(clipText)
            }
        }

        btnQrScan.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("کد QR کانفیگ یا ساب‌لینک را اسکن کنید")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }
            qrLauncher.launch(options)
        }

        btnSubManager.setOnClickListener {
            showSubscriptionManagerDialog()
        }

        btnMagic.setOnClickListener {
            runMagicUpdateAndPing()
        }

        btnPower.setOnClickListener {
            if (!isConnected) {
                if (activeServer == null && serverList.isEmpty()) {
                    Toast.makeText(this, "ابتدا ساب‌لینک وارد کنید و دکمه آپدیت را بزنید", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    activateVpnConnection()
                }
            } else {
                disconnectVpn()
            }
        }
    }

    private fun handleImportedText(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var addedSubs = 0
        var addedConfigs = 0

        for (line in lines) {
            if (line.startsWith("http://") || line.startsWith("https://")) {
                if (!subLinks.contains(line)) {
                    subLinks.add(line)
                    addedSubs++
                }
            } else if (isV2RayUri(line)) {
                parseConfigUri(line)?.let {
                    serverList.add(it)
                    addedConfigs++
                }
            }
        }

        saveData()
        updateSubCountUI()

        if (addedSubs > 0) {
            Toast.makeText(this, "$addedSubs ساب‌لینک اضافه شد. در حال دریافت سرورها...", Toast.LENGTH_SHORT).show()
            runMagicUpdateAndPing()
        } else if (addedConfigs > 0) {
            Toast.makeText(this, "$addedConfigs کانفیگ اضافه شد. در حال تست پینگ...", Toast.LENGTH_SHORT).show()
            pingAndSelectBest()
        } else {
            Toast.makeText(this, "لینک یا کانفیگ معتبر یافت نشد!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runMagicUpdateAndPing() {
        if (subLinks.isEmpty() && serverList.isEmpty()) {
            Toast.makeText(this, "هیچ ساب‌لینکی ثبت نشده است!", Toast.LENGTH_SHORT).show()
            return
        }

        btnMagic.isEnabled = false
        tvServerInfo.text = "در حال دریافت لیست سرورها از ساب‌لینک..."
        tvServerInfo.setTextColor(Color.parseColor("#C77DFF"))

        Executors.newSingleThreadExecutor().execute {
            if (subLinks.isNotEmpty()) {
                val fetchedServers = mutableListOf<ServerNode>()
                for (sub in subLinks) {
                    try {
                        val raw = URL(sub).readText().trim()
                        val decoded = try {
                            String(Base64.decode(raw, Base64.DEFAULT))
                        } catch (e: Exception) {
                            raw
                        }
                        decoded.lines().map { it.trim() }.filter { isV2RayUri(it) }.forEach { uri ->
                            parseConfigUri(uri)?.let { fetchedServers.add(it) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (fetchedServers.isNotEmpty()) {
                    serverList.clear()
                    serverList.addAll(fetchedServers)
                    saveData()
                }
            }
            mainHandler.post {
                pingAndSelectBest()
            }
        }
    }

    private fun pingAndSelectBest() {
        if (serverList.isEmpty()) {
            btnMagic.isEnabled = true
            tvServerInfo.text = "سروری یافت نشد"
            return
        }

        tvServerInfo.text = "در حال تست پینگ موازی ${serverList.size} سرور..."

        Executors.newSingleThreadExecutor().execute {
            val pool = Executors.newFixedThreadPool(12)
            val tasks = serverList.map { node ->
                Callable {
                    node.ping = measureTcpPing(node.host, node.port)
                    node
                }
            }
            try {
                pool.invokeAll(tasks)
            } finally {
                pool.shutdown()
            }

            val validServers = serverList.filter { it.ping in 1..4999 }.sortedBy { it.ping }
            mainHandler.post {
                btnMagic.isEnabled = true
                if (validServers.isNotEmpty()) {
                    activeServer = validServers.first()
                    saveData()
                    tvServerInfo.text = "⚡ ${activeServer!!.name} (${activeServer!!.ping} ms)"
                    tvServerInfo.setTextColor(Color.parseColor("#FFFFFF"))
                    Toast.makeText(this, "بهترین سرور انتخاب شد: ${activeServer!!.ping}ms", Toast.LENGTH_SHORT).show()
                } else {
                    tvServerInfo.text = "هیچ سروری پاسخ نداد (Timeout)"
                    tvServerInfo.setTextColor(Color.parseColor("#FF5252"))
                }
            }
        }
    }

    private fun measureTcpPing(host: String, port: Int): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun isV2RayUri(uri: String): Boolean {
        return uri.startsWith("vless://") || uri.startsWith("vmess://") ||
               uri.startsWith("trojan://") || uri.startsWith("ss://")
    }

    private fun parseConfigUri(uri: String): ServerNode? {
        return try {
            if (uri.startsWith("vmess://")) {
                val b64 = uri.removePrefix("vmess://")
                val jsonStr = String(Base64.decode(b64, Base64.DEFAULT))
                val obj = gson.fromJson(jsonStr, JsonObject::class.java)
                val host = obj.get("add")?.asString ?: return null
                val port = obj.get("port")?.asInt ?: 443
                val ps = obj.get("ps")?.asString?.takeIf { it.isNotBlank() } ?: host
                ServerNode(ps, host, port, uri)
            } else {
                val parsed = Uri.parse(uri)
                val host = parsed.host ?: return null
                val port = if (parsed.port > 0) parsed.port else 443
                val tag = parsed.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() } ?: host
                ServerNode(tag, host, port, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showSubscriptionManagerDialog() {
        if (subLinks.isEmpty()) {
            Toast.makeText(this, "لیست ساب‌لینک‌ها خالی است", Toast.LENGTH_SHORT).show()
            return
        }
        val displayItems = subLinks.mapIndexed { index, url ->
            val shortUrl = if (url.length > 42) url.take(39) + "..." else url
            "${index + 1}. $shortUrl"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("حذف ساب‌لینک (روی مورد بزنید)")
            .setItems(displayItems) { _, which ->
                subLinks.removeAt(which)
                if (subLinks.isEmpty()) {
                    serverList.clear()
                    activeServer = null
                    tvServerInfo.text = "سروری انتخاب نشده است"
                }
                saveData()
                updateSubCountUI()
                Toast.makeText(this, "ساب‌لینک با موفقیت حذف شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun activateVpnConnection() {
        val intent = Intent(this, AvangVpnService::class.java)
        startService(intent)
        isConnected = true

        tvStatus.text = "CONNECTED • کانتکت شد"
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        btnPower.text = "CONNECTED"
        btnPower.setBackgroundResource(R.drawable.bg_power_on)
        startPulseAnimation()
    }

    private fun disconnectVpn() {
        val intent = Intent(this, AvangVpnService::class.java).apply { action = "STOP" }
        startService(intent)
        isConnected = false

        tvStatus.text = "DISCONNECTED"
        tvStatus.setTextColor(Color.parseColor("#FF5252"))
        btnPower.text = "CONNECT"
        btnPower.setBackgroundResource(R.drawable.bg_power_off)
        stopPulseAnimation()
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.07f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.07f)
        pulseAnim = ObjectAnimator.ofPropertyValuesHolder(btnPower, scaleX, scaleY).apply {
            duration = 950
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnim?.cancel()
        pulseAnim = null
        btnPower.scaleX = 1.0f
        btnPower.scaleY = 1.0f
    }

    private fun updateSubCountUI() {
        btnSubManager.text = "🗑 مدیریت و حذف ساب‌لینک‌ها (${subLinks.size})"
        activeServer?.let {
            val pingTxt = if (it.ping > 0) " (${it.ping} ms)" else ""
            tvServerInfo.text = "⚡ ${it.name}$pingTxt"
            tvServerInfo.setTextColor(Color.parseColor("#FFFFFF"))
        }
    }

    private fun saveData() {
        val prefs = getSharedPreferences("avang_vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("subs", gson.toJson(subLinks))
            .putString("servers", gson.toJson(serverList))
            .putString("active", gson.toJson(activeServer))
            .apply()
    }

    private fun loadData() {
        val prefs = getSharedPreferences("avang_vpn_prefs", Context.MODE_PRIVATE)
        prefs.getString("subs", null)?.let {
            val type = object : TypeToken<MutableList<String>>() {}.type
            subLinks.clear()
            subLinks.addAll(gson.fromJson(it, type))
        }
        prefs.getString("servers", null)?.let {
            val type = object : TypeToken<MutableList<ServerNode>>() {}.type
            serverList.clear()
            serverList.addAll(gson.fromJson(it, type))
        }
        prefs.getString("active", null)?.let {
            activeServer = gson.fromJson(it, ServerNode::class.java)
        }
    }
}