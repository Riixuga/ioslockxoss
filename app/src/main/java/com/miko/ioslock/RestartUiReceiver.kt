package com.miko.ioslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RestartUiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Thread { restartSystemUi() }.start()
    }

    companion object {
        const val ACTION = "com.miko.ioslock.RESTART_SYSTEMUI"

        // Dipakai juga oleh MainActivity. Mengembalikan true kalau perintah root berhasil dijalankan.
        fun restartSystemUi(): Boolean = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui"))
            p.waitFor() == 0
        } catch (t: Throwable) {
            false
        }
    }
}
