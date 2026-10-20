package dev.me.podpopup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
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
                        Manifest.permission.POST_NOTIFICATIONS)
                    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
                    val t = tick
                    val permOk = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                    val running = PodService.running
                    val svc = Intent(this@MainActivity, PodService::class.java)
                    Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("PodPopup", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Text("Popup pin AirPods kiểu iPhone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Step(1, "Quyền Bluetooth & thông báo", permOk) { ask.launch(perms) }
                        Step(2, "Hiển thị trên ứng dụng khác", Settings.canDrawOverlays(ctx)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                        Step(3, "Pin → Không hạn chế", batteryOk) {
                            // Chưa miễn tối ưu pin: hỏi trực tiếp. Đã miễn: mở trang ứng dụng để chọn "Không hạn chế" của One UI
                            if (!batteryOk) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        Text("Máy Samsung: vào Chăm sóc thiết bị → Pin → Giới hạn sử dụng nền → Ứng dụng không bao giờ ngủ, thêm PodPopup để One UI không tắt dịch vụ.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.weight(1f))
                        OutlinedButton({
                            startActivity(Pods("AirPods Pro 2", 80, 90, 60, false, false, true, "")
                                .toIntent(Intent(this@MainActivity, PopupActivity::class.java)))
                        }, Modifier.fillMaxWidth().height(52.dp)) { Text("Xem thử popup") }
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
