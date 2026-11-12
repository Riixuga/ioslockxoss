package com.miko.ioslock

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "IOSLock aktif di LSPosed untuk com.android.systemui.\n" +
                "Hook dipasang otomatis setiap System UI dimulai."
            gravity = Gravity.CENTER
        })

        root.addView(Button(this).apply {
            text = "Reset UI"
            setOnClickListener {
                Thread {
                    val ok = RestartUiReceiver.restartSystemUi()
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) "System UI direstart" else "Root ditolak, System UI tidak direstart",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
        })

        setContentView(root)
    }
}
