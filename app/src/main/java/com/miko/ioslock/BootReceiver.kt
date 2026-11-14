package com.miko.ioslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Setelah reboot, property debug.* hilang: terapkan lagi pengaturan yang tersimpan di aplikasi. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        RootUtil.applyAll(context) { pending.finish() }
    }
}
