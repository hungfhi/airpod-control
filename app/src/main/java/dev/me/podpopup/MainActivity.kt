package dev.me.podpopup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.location.LocationManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); tick++ }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent {
            val ctx = LocalContext.current
            val cs = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = cs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS)
                    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }   // làm mới khung chẩn đoán mỗi giây
                    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
                    val t = tick
                    val permOk = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                    val running = PodService.running
                    val svc = Intent(this@MainActivity, PodService::class.java)
                    Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("PodPopup", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Text("Popup pin AirPods kiểu iPhone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Step(1, "Quyền Bluetooth, vị trí & thông báo", permOk) { ask.launch(perms) }
                        val locOn = getSystemService(LocationManager::class.java).isLocationEnabled
                        Step(2, "Bật Vị trí (cần để quét Bluetooth)", locOn) {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        Step(3, "Hiển thị trên ứng dụng khác", Settings.canDrawOverlays(ctx)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                        Step(4, "Pin → Không hạn chế", batteryOk) {
                            // Chưa miễn tối ưu pin: hỏi trực tiếp. Đã miễn: mở trang ứng dụng để chọn "Không hạn chế" của One UI
                            if (!batteryOk) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        Text("Máy Samsung: vào Chăm sóc thiết bị → Pin → Giới hạn sử dụng nền → Ứng dụng không bao giờ ngủ, thêm PodPopup để One UI không tắt dịch vụ.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.height(4.dp))
                        Diagnostics(running, t)
                        val real = PodService.lastPods
                        OutlinedButton({
                            startActivity((real ?: Pods("AirPods Pro 2", 80, 90, 60, false, false, true, ""))
                                .toIntent(Intent(this@MainActivity, PopupActivity::class.java)))
                        }, Modifier.fillMaxWidth().height(52.dp)) { Text(if (real != null) "Xem popup (dữ liệu thật)" else "Xem thử popup (dữ liệu mẫu)") }
                        Button({
                            if (running) stopService(svc) else startForegroundService(svc)
                            window.decorView.postDelayed({ tick++ }, 600)
                        }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (running) "Dừng theo dõi" else "Bắt đầu theo dõi") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(if (done) Color(0xFF30D158) else MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else "$n", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Khung chẩn đoán: cho biết app có thực sự nhận được gói pin từ AirPods hay không. */
@Composable
private fun Diagnostics(running: Boolean, @Suppress("UNUSED_PARAMETER") tick: Int) {   // tick: ép vẽ lại mỗi giây
    val p = PodService.lastPods
    val ago = if (PodService.lastAt == 0L) null else (SystemClock.elapsedRealtime() - PodService.lastAt) / 1000
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Chẩn đoán", fontWeight = FontWeight.SemiBold)
            val status = when {
                !running -> "Chưa bật theo dõi. Bấm \"Bắt đầu theo dõi\" bên dưới."
                PodService.scanError != null -> PodService.scanError!!
                PodService.packets == 0 -> "Đang quét… chưa nhận được gói nào. Mở nắp hộp AirPods sát điện thoại."
                else -> "Đã nhận ${PodService.packets} gói (${PodService.weak} gói tín hiệu yếu < ${PodService.MIN_RSSI} dBm bị bỏ qua)"
            }
            Text(status, color = fg, fontSize = 14.sp)
            if (p != null) {
                fun f(v: Int?, c: Boolean) = (v?.let { "$it%" } ?: "--") + if (c) " ⚡" else ""
                Text("${p.model} · RSSI ${PodService.lastRssi} dBm · ${ago}s trước", fontSize = 14.sp)
                Text("Trái ${f(p.left, p.chargeL)}   Phải ${f(p.right, p.chargeR)}   Case ${f(p.case, p.chargeC)}", fontSize = 14.sp)
                Text("Trong hộp: ${if (p.inCase) "có" else "không"} · bộ đếm nắp: ${p.lid}", color = fg, fontSize = 13.sp)
                Text(p.raw, color = fg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            val ev = synchronized(PodService.events) { PodService.events.toList() }
            if (ev.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Nhật ký", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                ev.forEach { Text(it, color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
