package dev.me.podpopup

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { c.startForegroundService(Intent(c, PodService::class.java)) }
    }
}
