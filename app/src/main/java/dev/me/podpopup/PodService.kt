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
        const val COOLDOWN_MS = 4_000L  // không popup 2 lần trong 4s
        // ---- số liệu chẩn đoán, hiển thị ở màn hình chính ----
        @Volatile var packets = 0          // số gói AirPods nhận được
        @Volatile var weak = 0             // số gói bị bỏ qua vì RSSI < MIN_RSSI
        @Volatile var lastPods: Pods? = null
        @Volatile var lastRssi = 0
        @Volatile var lastAt = 0L          // elapsedRealtime của gói gần nhất
        @Volatile var scanError: String? = null
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
            val pods = AirPodsParser.parse(d) ?: return
            packets++; lastRssi = r.rssi; lastAt = SystemClock.elapsedRealtime(); lastPods = pods; scanError = null
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
                if (now - lastPopup > COOLDOWN_MS) { lastPopup = now; main.post { showPopup(pods) } }
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "Theo dõi AirPods", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "svc").setContentTitle("PodPopup đang chạy")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startScan(); running = true; log("Bắt đầu theo dõi")
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (scanner != null) return
        scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setManufacturerData(
            0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build()   // mọi gói "proximity pairing" (0x07), mọi độ dài
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), s, cb)
        main.removeCallbacks(restart); main.postDelayed(restart, RESTART_MS)
    }

    @Suppress("MissingPermission")
    override fun onDestroy() {
        running = false; main.removeCallbacksAndMessages(null); scanner?.stopScan(cb); scanner = null
        runCatching { overlay.hide() }; log("Dừng theo dõi"); super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
