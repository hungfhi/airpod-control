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

class PodService : Service() {
    companion object {
        const val MIN_RSSI = -60        // tăng (vd -50) nếu hay bắt nhầm AirPods người khác
        @Volatile var running = false
        const val QUIET_MS = 10_000L    // im lặng ≥10s rồi thấy lại => coi là lần mở nắp mới
        const val COOLDOWN_MS = 4_000L  // không popup 2 lần trong 4s
    }
    private var lastSeen = 0L
    private var lastPopup = 0L
    private var lastLid = -1

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (r.rssi < MIN_RSSI) return
            val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            val pods = AirPodsParser.parse(d) ?: return
            Log.d("PodPopup", "rssi=${r.rssi} inCase=${pods.inCase} lid=${pods.lid} raw=${pods.raw}")
            val now = SystemClock.elapsedRealtime()
            val fresh = now - lastSeen > QUIET_MS
            val lidChanged = lastLid >= 0 && pods.lid != lastLid
            lastSeen = now; lastLid = pods.lid
            // Đang đeo (không tai nào trong hộp) => không bao giờ popup, tránh bật nhầm khi tín hiệu chập chờn trong túi
            if (!pods.inCase) return
            if ((fresh || lidChanged) && now - lastPopup > COOLDOWN_MS) {
                lastPopup = now
                startActivity(pods.toIntent(Intent(this@PodService, PopupActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "Theo dõi AirPods", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "svc").setContentTitle("PodPopup đang chạy")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startScan(); running = true
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (scanner != null) return
        scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setManufacturerData(
            0x004C, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte())).build()
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), s, cb)
    }

    @Suppress("MissingPermission")
    override fun onDestroy() { running = false; scanner?.stopScan(cb); scanner = null; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
