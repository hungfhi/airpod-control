package dev.me.podpopup

import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PodService : Service() {
    companion object {
        const val MIN_RSSI = -60        // tăng (vd -50) nếu hay bắt nhầm AirPods người khác
        @Volatile var running = false
        const val QUIET_MS = 3_000L     // im lặng ≥3s rồi thấy lại => coi là lần mở nắp mới
        const val RESTART_MS = 20 * 60_000L  // Android hạ cấp lệnh quét chạy >30 phút => khởi động lại mỗi 20 phút
        const val COOLDOWN_MS = 6_000L  // không popup 2 lần trong 6s (BLE và sự kiện kết nối có thể cùng báo)
        // Broadcast ẩn của Android: pin tai nghe gửi qua kết nối (AirPods báo bằng lệnh AT+IPHONEACCEV)
        private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        // ---- số liệu chẩn đoán, hiển thị ở màn hình chính ----
        @Volatile var packets = 0          // số gói AirPods nhận được
        @Volatile var weak = 0             // số gói bị bỏ qua vì RSSI < MIN_RSSI
        @Volatile var lastPods: Pods? = null
        @Volatile var lastRssi = 0
        @Volatile var lastAt = 0L          // elapsedRealtime của gói gần nhất
        @Volatile var scanError: String? = null
        /** Số gói Apple nhận được theo loại (byte đầu): 0x07 = báo pin AirPods, các loại khác của iPhone/Mac/AirTag… */
        val appleTypes = sortedMapOf<Int, Int>()
        @Volatile var sysBatt = "chưa thử"     // kết quả đọc pin hệ thống qua getBatteryLevel
        // Pin "đã biết" của từng phần, nhớ tối đa KNOWN_MS để popup không hiện "--" khi gói mới thiếu dữ liệu
        const val KNOWN_MS = 15 * 60_000L
        @Volatile private var kL: Int? = null; @Volatile private var kLAt = 0L
        @Volatile private var kR: Int? = null; @Volatile private var kRAt = 0L
        @Volatile private var kC: Int? = null; @Volatile private var kCAt = 0L
        fun remember(p: Pods) {
            val now = SystemClock.elapsedRealtime()
            p.left?.let { kL = it; kLAt = now }; p.right?.let { kR = it; kRAt = now }; p.case?.let { kC = it; kCAt = now }
        }
        fun fillKnown(p: Pods): Pods {
            val now = SystemClock.elapsedRealtime()
            fun <T> ok(v: T?, at: Long) = v?.takeIf { now - at < KNOWN_MS }
            return p.copy(left = p.left ?: ok(kL, kLAt), right = p.right ?: ok(kR, kRAt), case = p.case ?: ok(kC, kCAt))
        }
        /** Nhật ký sự kiện (mới nhất ở đầu), hiển thị ở khung Chẩn đoán. */
        val events = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun log(msg: String) {
            Log.d("PodPopup", msg)
            synchronized(events) { events.addFirst("${fmt.format(Date())}  $msg"); while (events.size > 12) events.removeLast() }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val overlay by lazy { PopupOverlay(this) }
    private var lastInCase = false
    private var btBattery = -1
    private var lastDev: BluetoothDevice? = null      // AirPods đang kết nối qua Bluetooth thường
    private var connected = false
    private var receiverOn = false

    /** Mở nắp hộp => AirPods đã ghép đôi tự kết nối lại => Android phát ACL_CONNECTED. Dùng làm tín hiệu chính. */
    private val btReceiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) { restartScan("bật màn hình"); return }
            val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val name = runCatching { dev.name }.getOrNull() ?: "?"
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val recentBle = SystemClock.elapsedRealtime() - lastAt < 30_000
                    val apple = name.contains("AirPods", true) || name.contains("Beats", true) || recentBle
                    log("Kết nối Bluetooth: $name${if (apple) "" else " (không phải AirPods, bỏ qua)"}")
                    if (apple) {
                        lastDev = dev; connected = true
                        restartScan("AirPods kết nối")      // quét mới ngay lúc mở nắp: One UI hay giảm nhịp quét của app nền
                        main.postDelayed({ onAirPodsConnected(name) }, 1_200)   // chờ chút để có gói BLE/pin mới
                        // pin qua kết nối (HFP) thường về sau vài giây => cập nhật popup đang hiện
                        main.postDelayed({ refreshPopup(name) }, 3_500)
                        main.postDelayed({ refreshPopup(name) }, 7_000)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    log("Ngắt kết nối: $name")
                    if (dev.address == lastDev?.address) { connected = false; btBattery = -1 }
                }
                ACTION_BATTERY -> {
                    val lvl = i.getIntExtra(EXTRA_BATTERY, -1)
                    if (lvl in 0..100) { btBattery = lvl; log("Pin qua kết nối ($name): $lvl%") }
                }
            }
        }
    }

    /** Pin Android đang lưu cho thiết bị (API ẩn getBatteryLevel, đọc qua reflection). -1 nếu không có. */
    private fun sysBattery(d: BluetoothDevice?): Int =
        runCatching { d?.javaClass?.getMethod("getBatteryLevel")?.invoke(d) as? Int }.getOrNull() ?: -1

    /** Dữ liệu tốt nhất hiện có: gói BLE gần nhất + pin qua kết nối điền vào những tai bị thiếu. */
    private fun bestPods(name: String): Pods {
        val now = SystemClock.elapsedRealtime()
        val ble = lastPods?.takeIf { now - lastAt < 60_000 }
        return withBt(fillKnown(ble ?: Pods(lastPods?.model ?: name, null, null, null, false, false, false, "")))
    }
    private fun withBt(p: Pods): Pods {
        if (!connected) return p
        val sys = sysBattery(lastDev)
        sysBatt = if (sys in 0..100) "$sys%" else "không đọc được"
        val bt = maxOf(btBattery, sys).takeIf { it in 0..100 } ?: return p
        return p.copy(left = p.left ?: bt, right = p.right ?: bt)
    }

    private fun onAirPodsConnected(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPopup > COOLDOWN_MS) { lastPopup = now; log("Mở nắp (theo sự kiện kết nối)"); showPopup(bestPods(name)) }
        else log("Bỏ qua (kết nối): vừa hiện popup")
    }
    private fun refreshPopup(name: String) {
        val p = bestPods(name)
        runCatching { overlay.update(p) }
        log("Cập nhật pin: trái ${p.left ?: "--"} · phải ${p.right ?: "--"} · hộp ${p.case ?: "--"} (pin hệ thống: $sysBatt)")
    }
    private val restart = object : Runnable {
        @Suppress("MissingPermission")
        override fun run() { scanner?.stopScan(cb); scanner = null; startScan(); log("Khởi động lại quét (định kỳ 20 phút)") }
    }
    private var lastSeen = 0L
    private var lastPopup = 0L
    private var lastLid = -1
    private var scanner: BluetoothLeScanner? = null

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            if (d.isNotEmpty()) synchronized(appleTypes) { val t = d[0].toInt() and 0xFF; appleTypes[t] = (appleTypes[t] ?: 0) + 1 }
            val pods = AirPodsParser.parse(d) ?: return
            packets++; lastRssi = r.rssi; lastAt = SystemClock.elapsedRealtime(); lastPods = pods; scanError = null
            if (r.rssi >= MIN_RSSI) remember(pods)
            if (r.rssi < MIN_RSSI) { weak++; return }
            Log.d("PodPopup", "rssi=${r.rssi} inCase=${pods.inCase} lid=${pods.lid} raw=${pods.raw}")
            val now = SystemClock.elapsedRealtime()
            val gap = now - lastSeen
            val fresh = lastSeen == 0L || gap > QUIET_MS
            val lidChanged = lastLid >= 0 && pods.lid != lastLid
            val backInCase = lastSeen != 0L && !lastInCase && pods.inCase
            if (fresh) log("Thấy AirPods (sau ${if (lastSeen == 0L) "lúc bắt đầu" else "${gap / 1000}s im lặng"}) · rssi ${r.rssi} · ${if (pods.inCase) "trong hộp" else "ngoài hộp"}")
            if (lidChanged) log("Bộ đếm nắp đổi ${lastLid} → ${pods.lid}")
            if (pods.inCase != lastInCase && !fresh) log(if (pods.inCase) "Tai nghe vào hộp" else "Tai nghe ra khỏi hộp")
            lastSeen = now; lastLid = pods.lid; lastInCase = pods.inCase
            // Đang đeo (không tai nào trong hộp) => không bao giờ popup, tránh bật nhầm khi tín hiệu chập chờn trong túi
            if (!pods.inCase) return
            if (fresh || lidChanged || backInCase) {
                if (now - lastPopup > COOLDOWN_MS) { lastPopup = now; main.post { showPopup(withBt(fillKnown(pods))) } }
                else log("Bỏ qua: vừa hiện popup < ${COOLDOWN_MS / 1000}s")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanError = "Lỗi quét Bluetooth (mã $errorCode)"; log(scanError!!); scanner = null
            main.postDelayed({ startScan() }, 5_000)   // thử quét lại sau 5s
        }
    }

    /** Hiện popup: overlay khi máy đang mở khoá (không bị chặn khi chạy nền), Activity khi đang ở màn hình khoá. */
    private fun showPopup(p: Pods) {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!locked && Settings.canDrawOverlays(this)) {
            try { overlay.show(p); log("Hiện popup (cửa sổ nổi)"); return }
            catch (e: Exception) { log("Cửa sổ nổi lỗi: ${e.message}") }
        }
        try {
            startActivity(p.toIntent(Intent(this, PopupActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            log(if (locked) "Hiện popup trên màn hình khoá" else "Hiện popup (activity) – chưa có quyền cửa sổ nổi")
        } catch (e: Exception) { log("Không mở được popup: ${e.message}") }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "test") {   // nút "Thử popup sau 5 giây" ở màn hình chính
            log("Thử popup: sẽ hiện sau 5 giây, hãy ra màn hình chính")
            main.postDelayed({ log("Thử popup ngoài app"); showPopup(bestPods("AirPods")) }, 5_000)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "Theo dõi AirPods", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "svc").setContentTitle("PodPopup đang chạy")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        // Thêm loại "location" để vẫn nhận kết quả quét BLE khi app ở nền (khi đã có quyền vị trí)
        val loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (loc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
            if (!loc) log("Chưa có quyền vị trí: khi ở nền sẽ không nhận được gói BLE")
        } catch (e: Exception) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            log("Không bật được chế độ vị trí nền: ${e.message}")
        }
        if (!receiverOn) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED); addAction(ACTION_BATTERY)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(this, btReceiver, f, ContextCompat.RECEIVER_EXPORTED)
            receiverOn = true
        }
        startScan(); running = true; log("Bắt đầu theo dõi")
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun restartScan(why: String) {
        scanner?.let { runCatching { it.stopScan(cb) } }; scanner = null; startScan(); log("Quét lại ($why)")
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (scanner != null) return
        scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setManufacturerData(
            0x004C, ByteArray(0)).build()   // mọi gói Apple (đếm theo loại để chẩn đoán); chỉ loại 0x07 được dùng cho popup
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), s, cb)
        main.removeCallbacks(restart); main.postDelayed(restart, RESTART_MS)
    }

    @Suppress("MissingPermission")
    override fun onDestroy() {
        running = false; main.removeCallbacksAndMessages(null)
        if (receiverOn) { runCatching { unregisterReceiver(btReceiver) }; receiverOn = false }
        scanner?.stopScan(cb); scanner = null
        runCatching { overlay.hide() }; log("Dừng theo dõi"); super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
