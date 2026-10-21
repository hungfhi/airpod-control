package dev.me.podpopup

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Giữ sẵn 24 frame hộp (HARDWARE bitmap, nằm trên GPU) để mỗi lần mở popup không phải giải mã lại. */
object FrameCache {
    @Volatile var frames: List<ImageBitmap>? = null
        private set
    fun load(ctx: Context): List<ImageBitmap> = frames ?: synchronized(this) {
        frames ?: run {
            val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE; inScaled = false }
            HeroMeta.FRAMES.map { BitmapFactory.decodeResource(ctx.resources, it, opt).asImageBitmap() }.also { frames = it }
        }
    }
}

/** Hệ số làm chậm animation: 1 = gốc, 2 = chậm gấp đôi. Lò xo: độ cứng chia SLOW² để thời gian dài gấp SLOW. */
private const val SLOW = 1.6f
private fun ms(v: Int) = (v * SLOW).toInt()
private fun dl(v: Int) = (v * SLOW).toLong()          // cho delay() (nhận Long)
private fun stiff(k: Float) = k / (SLOW * SLOW)

class PopupActivity : ComponentActivity() {
    private var pods by mutableStateOf<Pods?>(null)
    private var token by mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pods = Pods.from(intent)
        setContent {
            LaunchedEffect(token) { delay(8000); finish() }   // đủ lâu để xem hết animation đã làm chậm
            Box(
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() },
                contentAlignment = Alignment.BottomCenter,
            ) { pods?.let { p -> key(token) { PopupCard(p) } } }   // key: chạy lại animation khi mở nắp lần nữa
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); pods = Pods.from(i); token++ }
}

/**
 * Popup dạng cửa sổ nổi (overlay), dùng khi app đang chạy nền.
 * Android/One UI hay chặn mở Activity từ nền, còn overlay thì được phép bất cứ lúc nào khi có quyền
 * "Hiển thị trên ứng dụng khác".
 * Mỗi lần hiện dùng một vòng đời (lifecycle) MỚI và huỷ hẳn khi ẩn: dùng chung một lifecycle giữa các lần
 * khiến Compose tạm dừng vẽ từ lần thứ hai (nội dung được dựng nhưng không bao giờ vẽ ra màn hình).
 */
class PopupOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var host: Host? = null

    private inner class Host : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        var pods by mutableStateOf<Pods?>(null)
        var token by mutableIntStateOf(0)
        val view: ComposeView
        private var drawn = false

        init {
            ssr.performAttach(); ssr.performRestore(null)
            reg.currentState = Lifecycle.State.CREATED
            view = ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(this@Host)
                setViewTreeSavedStateRegistryOwner(this@Host)
                setContent {
                    val t = token
                    LaunchedEffect(t) { PodService.log("Popup đã dựng xong trong cửa sổ nổi"); delay(8000); hide() }
                    Box(
                        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { hide() },
                        contentAlignment = Alignment.BottomCenter,
                    ) { pods?.let { p -> key(t) { PopupCard(p) } } }
                }
            }
            // xác nhận popup thực sự được VẼ lên màn hình (khác với chỉ dựng xong)
            view.viewTreeObserver.addOnDrawListener {
                if (!drawn && view.width > 0 && view.height > 0) {
                    drawn = true
                    PodService.log("Popup đã vẽ lên màn hình (${view.width}×${view.height}, GPU: ${view.isHardwareAccelerated})")
                }
            }
        }
        fun start() { reg.currentState = Lifecycle.State.RESUMED }
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED }
    }

    fun show(p: Pods) {
        host?.let { it.pods = p; it.token++; return }      // đang hiện sẵn: chỉ chạy lại animation
        val h = Host().apply { pods = p }
        // Chiều cao cố định bằng thẻ popup: WRAP_CONTENT cho ra cửa sổ cao 0px vì lúc tạo nội dung còn ẩn (chờ animation)
        val height = (600 * ctx.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // cửa sổ thêm từ Service mặc định vẽ bằng CPU => ảnh HARDWARE bitmap không hiện; bật tăng tốc GPU
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        h.start()
        wm.addView(h.view, lp)
        host = h
    }

    /** Cập nhật số liệu khi popup đang hiện (pin qua kết nối về chậm hơn vài giây), không chạy lại animation. */
    fun update(p: Pods) { host?.pods = p }
    val showing: Boolean get() = host != null

    fun hide() {
        val h = host ?: return
        host = null
        runCatching { wm.removeView(h.view) }
        h.destroy()
    }
}

@Composable
private fun PopupCard(p: Pods) {
    val dark = isSystemInDarkTheme()
    val fg = if (dark) Color.White else Color(0xFF111111)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        shown,
        enter = slideInVertically(spring(0.78f, stiff(Spring.StiffnessMediumLow))) { it } + fadeIn(tween(ms(220))),
    ) {
        Column(
            Modifier.padding(12.dp).navigationBarsPadding().fillMaxWidth().widthIn(max = 420.dp)
                .background(if (dark) Color(0xF01C1C1E) else Color(0xF2F2F2F7), RoundedCornerShape(36.dp))
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PodsHero(p)
            Spacer(Modifier.height(6.dp))
            Text(p.model, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Ring("Trái", p.left, p.chargeL, fg, 0)
                Ring("Phải", p.right, p.chargeR, fg, 120)
                Ring("Case", p.case, p.chargeC, fg, 240)
            }
        }
    }
}

@Composable
private fun Ring(label: String, v: Int?, charging: Boolean, fg: Color, delayMs: Int) {
    if (v == null && label != "Case") return
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(v) { delay(dl(700)); target = (v ?: 0) / 100f }   // chạy lại khi số liệu được cập nhật
    val prog by animateFloatAsState(target, tween(ms(900), ms(delayMs), FastOutSlowInEasing), label = "ring")
    val col = if (!charging && (v ?: 100) <= 20) Color(0xFFFF453A) else Color(0xFF30D158)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 7.dp.toPx(); val s = Size(size.width - sw, size.height - sw); val o = Offset(sw / 2, sw / 2)
                drawArc(fg.copy(alpha = .14f), 0f, 360f, false, o, s, style = Stroke(sw))
                drawArc(col, -90f, 360f * prog, false, o, s, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text(v?.let { "$it%" } ?: "--", color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (charging) "$label ⚡" else label, color = fg.copy(alpha = .6f), fontSize = 13.sp)
    }
}

/** Mở nắp case (24 frame + crossfade, nắp xoay quanh bản lề) → đèn LED sáng → 2 tai nghe trượt lên khỏi khe và xoay mặt ra trước. */
@Composable
private fun PodsHero(p: Pods) {
    val hasCase = p.case != null
    val ctx = LocalContext.current
    // Decode trước toàn bộ frame ở background (HARDWARE bitmap: nằm trên GPU, không tốn heap) để animation không giật
    // Ảnh hộp đã được dịch vụ giải mã sẵn (FrameCache) => có ngay, không phải chờ
    val frames by produceState(if (hasCase) FrameCache.frames else emptyList(), hasCase) {
        if (value == null) value = withContext(Dispatchers.IO) { FrameCache.load(ctx.applicationContext) }
    }
    val ready = frames != null
    val open = remember { Animatable(if (hasCase) 0f else 1f) }
    val led = remember { Animatable(0f) }
    val riseL = remember { Animatable(0f) }; val riseR = remember { Animatable(0f) }
    val spinL = remember { Animatable(-90f) }; val spinR = remember { Animatable(90f) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        launch {
            if (hasCase) {
                delay(dl(200))
                // nắp bật lên rồi chậm dần như bản lề lò xo thật
                open.animateTo(1f, tween(ms(650), easing = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)))
            }
        }
        launch { if (hasCase) { delay(dl(420)); led.animateTo(1f, tween(ms(300))) } }
        launch {
            delay(dl(if (hasCase) 620 else 100))
            launch { spinL.animateTo(0f, spring(0.5f, stiff(45f))) }
            riseL.animateTo(1f, tween(ms(800), easing = FastOutSlowInEasing))
        }
        launch {
            delay(dl(if (hasCase) 740 else 220))
            launch { spinR.animateTo(0f, spring(0.5f, stiff(45f))) }
            riseR.animateTo(1f, tween(ms(800), easing = FastOutSlowInEasing))
        }
    }
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        -4f, 4f, infiniteRepeatable(tween(ms(2000), easing = EaseInOutSine), RepeatMode.Reverse), label = "b")
    // đèn LED: xanh khi đủ pin / đang sạc, cam khi case yếu
    val ledColor = if (p.chargeC || (p.case ?: 100) > 20) Color(0xFF30D158) else Color(0xFFFF9F0A)
    val h = if (hasCase) HeroMeta.H else HeroMeta.UPL + HeroMeta.LH + 16f
    BoxWithConstraints(Modifier.width(240.dp).aspectRatio(HeroMeta.W / h)) {
        val k = maxWidth / HeroMeta.W                      // dp trên mỗi pixel ảnh gốc
        val fr = frames
        if (hasCase && fr != null && fr.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val f = open.value * (fr.size - 1)
                val i = floor(f).toInt().coerceIn(0, fr.size - 1)
                val a = f - i
                drawImage(fr[i], dstSize = dst, filterQuality = FilterQuality.High)
                // crossfade sang frame kế tiếp để nắp chuyển động liên tục
                if (a > 0.01f && i + 1 < fr.size) drawImage(fr[i + 1], dstSize = dst, alpha = a, filterQuality = FilterQuality.High)
                val s = size.width / HeroMeta.W
                val c = Offset(HeroMeta.LEDX * s, HeroMeta.LEDY * s)
                val r = HeroMeta.LEDR * 0.7f * s
                drawCircle(Brush.radialGradient(listOf(ledColor.copy(alpha = .45f * led.value), Color.Transparent), c, r * 4f), r * 4f, c)
                drawCircle(ledColor.copy(alpha = led.value), r, c)
            }
        }
        Box(Modifier.fillMaxWidth().height((if (hasCase) HeroMeta.RIM else h) * k).clipToBounds()) {
            @Composable
            fun Pod(res: Int, x: Float, w: Float, ph: Float, up: Float, r: Float, spin: Float, tilt: Float) {
                Image(painterResource(res), null,
                    Modifier.offset(x * k, (lerp(if (hasCase) HeroMeta.REST else h, up, r) + bob * r) * k).size(w * k, ph * k)
                        .graphicsLayer { rotationY = spin; rotationZ = tilt * (1f - r); cameraDistance = 14f * density })
            }
            if (p.left != null) Pod(R.drawable.pod_l, HeroMeta.LX, HeroMeta.LW, HeroMeta.LH, HeroMeta.UPL, riseL.value, spinL.value, -12f)
            if (p.right != null) Pod(R.drawable.pod_r, HeroMeta.RX, HeroMeta.RW, HeroMeta.RH, HeroMeta.UPR, riseR.value, spinR.value, 12f)
        }
    }
}
