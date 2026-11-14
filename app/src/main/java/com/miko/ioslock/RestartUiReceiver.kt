package com.miko.ioslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dipanggil oleh tombol "Reset UI" di menu power (dikirim modul dari proses System UI). */
class RestartUiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        RootUtil.restartSystemUi { pending.finish() }
    }

    companion object {
        const val ACTION = "com.miko.ioslock.RESTART_SYSTEMUI"
    }
}
