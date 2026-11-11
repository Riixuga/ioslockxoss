package com.miko.ioslock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var content: LinearLayout

    private data class Spec(
        val key: String,
        val label: String,
        val min: Float,
        val max: Float,
        val def: Float,
        val unit: String,
        val isInt: Boolean = false
    )

    private val specs = listOf(
        Spec("debug.ioslock.pillshrink", "Pengecilan pil saat masuk state 2", 0.05f, 0.6f, 0.2f, "x"),
        Spec("debug.ioslock.pilldrop", "Jarak turun pil", 0f, 40f, 10f, "dp"),
        Spec("debug.ioslock.pillgrow", "Durasi grow-in pil", 100f, 800f, 360f, "ms", true),
        Spec("debug.ioslock.snapms", "Durasi buka/tutup", 150f, 800f, 420f, "ms", true),
        Spec("debug.ioslock.pillmediaw", "Lebar mini media player", 150f, 330f, 208f, "dp"),
        Spec("debug.ioslock.pillborder", "Tebal border kaca", 0f, 4f, 1f, "dp"),
        Spec("debug.ioslock.pillbordera", "Kekuatan border", 0f, 255f, 110f, ""),
        Spec("debug.ioslock.pilltint", "Tint kaca", 0f, 120f, 56f, ""),
        Spec("debug.ioslock.pillblur", "Radius blur pil", 0f, 120f, 60f, "px"),
        Spec("debug.ioslock.full2", "Kartu penuh di state 2", 1f, 8f, 3f, "", true),
        Spec("debug.ioslock.dock2max", "Batas peek stack state 2 (0 = penuh)", 0f, 80f, 0f, "dp"),
        Spec("debug.ioslock.openscale", "Skala awal kartu", 0.2f, 1f, 0.55f, ""),
        Spec("debug.ioslock.openstagger", "Stagger buka kartu", 0f, 0.8f, 0.35f, ""),
        Spec("debug.ioslock.fling", "Faktor fling state 3", 0f, 0.6f, 0.22f, "")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1016")
        window.navigationBarColor = Color.parseColor("#0B1016")

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(34))
            setBackgroundColor(Color.parseColor("#0B1016"))
        }
        scroll.addView(content)
        setContentView(scroll)

        addTitle("IOsLock — Pengaturan")
        addNote("Perubahan langsung dibaca SystemUI setelah ±0.4 detik. Tidak perlu reboot.")
        addSection("Pil & animasi")
        specs.forEach { addSlider(it) }
        addSection("Stack state 2")
        addSwitch("debug.ioslock.dockstack2", "Pakai mode dock (kartu di belakang menumpuk)", true)
        addNote("Matikan dock untuk tumpukan pita, atau atur jumlah kartu penuh dan tebal peek di atas.")
        addSection("Lainnya")
        addReset()
    }

    private fun addTitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun addNote(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#9FB0C0"))
            textSize = 13f
            setPadding(0, 0, 0, dp(14))
        })
    }

    private fun addSection(title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(Color.parseColor("#7FD4FF"))
            textSize = 12f
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun addSlider(spec: Spec) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val name = TextView(this).apply {
            text = spec.label
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val value = TextView(this).apply {
            setTextColor(Color.parseColor("#7FD4FF"))
            textSize = 15f
            gravity = Gravity.END
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(row)

        val bar = SeekBar(this).apply {
            max = 200
            setPadding(0, dp(2), 0, dp(7))
        }
        content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun current(): Float = try {
            if (spec.isInt) prefs.getInt(spec.key, spec.def.toInt()).toFloat()
            else prefs.getFloat(spec.key, spec.def)
        } catch (_: Throwable) {
            spec.def
        }

        fun display(v: Float) {
            value.text = if (spec.isInt) "${v.toInt()} ${spec.unit}".trim()
            else String.format("%.2f %s", v, spec.unit).trim()
        }

        fun save(v: Float) {
            val e = prefs.edit()
            if (spec.isInt) e.putInt(spec.key, v.toInt()) else e.putFloat(spec.key, v)
            e.apply()
        }

        fun valueAt(p: Int): Float {
            val v = spec.min + (spec.max - spec.min) * p / 200f
            return if (spec.isInt) Math.round(v).toFloat() else v
        }

        val initial = current().coerceIn(spec.min, spec.max)
        bar.progress = ((initial - spec.min) / (spec.max - spec.min) * 200f).toInt().coerceIn(0, 200)
        display(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueAt(progress)
                display(v)
                save(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun addSwitch(key: String, label: String, def: Boolean) {
        val sw = Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            isChecked = prefs.getBoolean(key, def)
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        content.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addReset() {
        val b = Button(this).apply {
            text = "Reset semua pengaturan"
            setOnClickListener {
                prefs.edit().clear().apply()
                recreate()
            }
        }
        content.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
