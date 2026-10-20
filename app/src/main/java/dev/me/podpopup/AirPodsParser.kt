package dev.me.podpopup

import android.content.Intent

data class Pods(
    val model: String,
    val left: Int?, val right: Int?, val case: Int?,      // phần trăm, null = không có dữ liệu
    val chargeL: Boolean, val chargeR: Boolean, val chargeC: Boolean,
    val raw: String,
    val inCase: Boolean = true,      // ít nhất 1 tai đang nằm trong hộp (status bit 2/4/6)
    val lid: Int = -1,               // bộ đếm mở nắp (byte 8), đổi giá trị mỗi lần mở nắp
) {
    fun toIntent(i: Intent) = i.apply {
        putExtra("model", model); putExtra("l", left ?: -1); putExtra("r", right ?: -1); putExtra("c", case ?: -1)
        putExtra("cl", chargeL); putExtra("cr", chargeR); putExtra("cc", chargeC)
    }
    companion object {
        fun from(i: Intent) = Pods(
            i.getStringExtra("model") ?: "AirPods",
            i.getIntExtra("l", -1).takeIf { it >= 0 }, i.getIntExtra("r", -1).takeIf { it >= 0 },
            i.getIntExtra("c", -1).takeIf { it >= 0 },
            i.getBooleanExtra("cl", false), i.getBooleanExtra("cr", false), i.getBooleanExtra("cc", false), "")
    }
}

object AirPodsParser {
    private val models = mapOf(
        0x0220 to "AirPods (1st gen)", 0x0F20 to "AirPods (2nd gen)", 0x1320 to "AirPods (3rd gen)",
        0x1920 to "AirPods 4", 0x1B20 to "AirPods 4 (ANC)",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x2420 to "AirPods Pro 2 (USB-C)",
        0x0A20 to "AirPods Max",
    )

    private fun pct(n: Int) = if (n > 10) null else n * 10   // 0xF = không có dữ liệu

    /** d = manufacturer data của Apple (đã bỏ company ID): d[0]=0x07, d[1]=len, d[2]=0x01... */
    fun parse(d: ByteArray): Pods? {
        if (d.size < 9 || d[0].toInt() != 0x07) return null
        val u = { i: Int -> d[i].toInt() and 0xFF }
        val modelId = (u(3) shl 8) or u(4)
        val flipped = (u(5) and 0x20) == 0           // logic theo OpenPods/CAPod: bit 0x20 của byte status
        val hi = pct(u(6) shr 4); val lo = pct(u(6) and 0x0F)
        val flags = u(7) shr 4
        val case = pct(u(7) and 0x0F)
        val status = u(5)
        // status: bit1/bit3 = tai chính/phụ trong tai, bit2/bit4/bit6 = có tai trong hộp (0x55 = cả 2 trong hộp)
        val inCase = status and (0x04 or 0x10 or 0x40) != 0
        return Pods(
            model = models[modelId] ?: "AirPods (0x%04X)".format(modelId),
            left = if (flipped) hi else lo, right = if (flipped) lo else hi, case = case,
            chargeL = flags and (if (flipped) 0x02 else 0x01) != 0,
            chargeR = flags and (if (flipped) 0x01 else 0x02) != 0,
            chargeC = flags and 0x04 != 0,
            raw = d.joinToString("") { "%02X".format(it) },
            inCase = inCase,
            lid = u(8),
        )
    }
}
