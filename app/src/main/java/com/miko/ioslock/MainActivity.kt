package com.miko.ioslock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var pal: Pal
    private var lang = Strings.EN
    private var builtLang = ""

    private val main = Handler(Looper.getMainLooper())
    private var pendingApply: Runnable? = null

    private var rootText: TextView? = null
    private var rootDot: View? = null
    private var preview: PhonePreviewView? = null
    private var valueText: TextView? = null
    private var slider: GradientSlider? = null

    private fun s(key: String) = Strings.get(lang, key)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = palette()
        lang = AppPrefs.lang(this)
        builtLang = lang
        setContentView(buildUi())
        setupWindow(this, pal)
    }

    override fun onResume() {
        super.onResume()
        if (AppPrefs.lang(this) != builtLang) {
            recreate()
            return
        }
        checkRoot()
    }

    override fun onDestroy() {
        pendingApply?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    // ---- root ----

    private fun setRootState(state: Int) {
        // 0 = memeriksa, 1 = ada, 2 = tidak ada
        val dot = rootDot?.background as? GradientDrawable
        when (state) {
            1 -> {
                rootText?.text = s("root_ok")
                dot?.setColor(0xFF2EE6A6.toInt())
            }
            2 -> {
                rootText?.text = s("root_no")
                dot?.setColor(0xFFFF6B81.toInt())
            }
            else -> {
                rootText?.text = s("root_checking")
                dot?.setColor(0xFFFFC857.toInt())
            }
        }
    }

    private var rootOk = false

    private fun checkRoot() {
        if (rootOk) {
            setRootState(1)
            return
        }
        setRootState(0)
        RootUtil.check { ok ->
            if (isFinishing || isDestroyed) return@check
            rootOk = ok
            setRootState(if (ok) 1 else 2)
            // sinkronkan pengaturan ke System UI (mis. setelah reboot)
            if (ok) RootUtil.applyAll(this)
        }
    }

    /** Terapkan pengaturan ke System UI. immediate = true kalau tidak perlu menunggu (slider dilepas, toggle). */
    private fun scheduleApply(immediate: Boolean) {
        pendingApply?.let { main.removeCallbacks(it) }
        val r = Runnable {
            RootUtil.applyAll(this) { ok ->
                if (!ok && !isFinishing && !isDestroyed) {
                    setRootState(2)
                    Toast.makeText(this, s("root_needed"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        pendingApply = r
        main.postDelayed(r, if (immediate) 0L else 140L)
    }

    // ---- tampilan ----

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.layoutDirection = layoutDirOf(lang)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = buildHeader()
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPaddingRelative(dp(16), dp(4), dp(16), dp(28))
        body.addView(sectionLabel(this, pal, s("sec_power")))
        body.addView(buildPowerCard())
        body.addView(sectionLabel(this, pal, s("sec_pill")))
        body.addView(buildPillCard())
        body.addView(sectionLabel(this, pal, s("sec_more")))
        body.addView(buildMoreCard())
        body.addView(buildFooter())

        col.addView(header, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(body, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headerPad = header.paddingTop
        onInsets(root) { top, bottom ->
            header.setPaddingRelative(dp(20), headerPad + top, dp(20), dp(24))
            body.setPaddingRelative(dp(16), dp(4), dp(16), dp(28) + bottom)
        }
        return root
    }

    private fun buildHeader(): LinearLayout {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.VERTICAL
        h.background = HeaderDrawable(dpf(30f))
        h.setPaddingRelative(dp(20), dp(18), dp(20), dp(24))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val logo = FrameLayout(this)
        val logoBg = GradientDrawable()
        logoBg.cornerRadius = dpf(15f)
        logoBg.setColor(0x33FFFFFF)
        logoBg.setStroke(dp(1), 0x55FFFFFF)
        logo.background = logoBg
        logo.addView(IconView(this, IconView.PILL, Color.WHITE), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        top.addView(logo, lpv(dp(46), dp(46)).m(0, 0, dp(12), 0))

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(tv(this, "IOSLock", 23f, Color.WHITE, true))
        titles.addView(tv(this, s("subtitle"), 12.5f, 0xDDFFFFFF.toInt()).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(2), 0, 0)
        })
        top.addView(titles, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // tombol Reset UI sekarang berupa ikon, di samping tombol pengaturan bahasa
        top.addView(
            headerIconButton(this, IconView.RESET, s("restart_cd")) { showRestartConfirm(this, pal, lang) },
            lpv(dp(42), dp(42)).m(dp(8), 0, 0, 0)
        )
        top.addView(
            headerIconButton(this, IconView.GEAR, s("settings")) {
                showLanguageSheet(this, pal, lang) { recreate() }
            },
            lpv(dp(42), dp(42)).m(dp(8), 0, 0, 0)
        )
        h.addView(top, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // status root + versi
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL

        val status = LinearLayout(this)
        status.orientation = LinearLayout.HORIZONTAL
        status.gravity = Gravity.CENTER_VERTICAL
        val sbg = GradientDrawable()
        sbg.cornerRadius = dpf(20f)
        sbg.setColor(0x2EFFFFFF)
        status.background = sbg
        status.setPaddingRelative(dp(12), dp(7), dp(14), dp(7))
        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(0xFFFFC857.toInt())
        dot.background = dotBg
        rootDot = dot
        status.addView(dot, lpv(dp(9), dp(9)).m(0, 0, dp(8), 0))
        val st = tv(this, s("root_checking"), 12.5f, Color.WHITE, true)
        rootText = st
        status.addView(st)
        chips.addView(status, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chips.addView(View(this), lpv(0, 1, 1f))

        val ver = tv(this, "v" + AppInfo.VERSION, 12.5f, Color.WHITE, true)
        val vbg = GradientDrawable()
        vbg.cornerRadius = dpf(20f)
        vbg.setColor(0x2EFFFFFF)
        ver.background = vbg
        ver.setPaddingRelative(dp(12), dp(7), dp(12), dp(7))
        chips.addView(ver, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(dp(8), 0, 0, 0))

        h.addView(chips, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(20), 0, 0))
        return h
    }

    private fun buildPowerCard(): View {
        val card = cardView(this, pal)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
        row.background = rippleOf(pal, null)
        row.isClickable = true

        row.addView(badgeView(this, IconView.POWER, 44), lpv(dp(44), dp(44)).m(0, 0, dp(14), 0))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(tv(this, s("powerbtn_title"), 16f, pal.text, true))
        texts.addView(tv(this, s("powerbtn_desc"), 13f, pal.sub).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(3), 0, 0)
        })
        row.addView(texts, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).m(0, 0, dp(12), 0))

        val toggle = ToggleView(this, pal)
        toggle.contentDescription = s("powerbtn_title")
        toggle.setChecked(AppPrefs.powerBtn(this), user = false, animate = false)
        toggle.onChange = { on ->
            AppPrefs.setPowerBtn(this, on)
            scheduleApply(true)
        }
        row.addView(toggle, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { toggle.toggle() }

        card.addView(row, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun fracText(f: Float): String = String.format(Locale.US, "%.1f%%", f * 100f)

    private fun buildPillCard(): View {
        val card = cardView(this, pal)
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setPaddingRelative(dp(16), dp(16), dp(16), dp(10))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL

        val pv = PhonePreviewView(this)
        preview = pv
        top.addView(pv, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, 0, dp(16), 0))

        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        side.addView(tv(this, s("pill_title"), 16f, pal.text, true))
        side.addView(tv(this, s("pill_desc"), 13f, pal.sub).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(4), 0, 0)
        })
        side.addView(View(this), lpv(1, 0, 1f))
        side.addView(tv(this, s("pill_position"), 12.5f, pal.sub, true))
        val vt = tv(this, "", 30f, pal.accent, true)
        valueText = vt
        side.addView(vt)
        val reset = textButton(this, pal, s("pill_default"))
        val rbg = GradientDrawable()
        rbg.cornerRadius = dpf(18f)
        rbg.setColor(pal.chip)
        reset.background = rippleOf(pal, rbg)
        side.addView(reset, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(8), 0, 0))
        top.addView(side, lpv(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pad.addView(top, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sl = GradientSlider(this, pal)
        slider = sl
        val range = AppPrefs.PILL_MAX - AppPrefs.PILL_MIN
        val startFrac = AppPrefs.pillFrac(this)
        sl.value = (startFrac - AppPrefs.PILL_MIN) / range
        pv.frac = startFrac
        vt.text = fracText(startFrac)
        sl.onChange = { v, done ->
            val f = (Math.round((AppPrefs.PILL_MIN + v * range) * 200f) / 200f)
                .coerceIn(AppPrefs.PILL_MIN, AppPrefs.PILL_MAX)
            pv.frac = f
            vt.text = fracText(f)
            AppPrefs.setPillFrac(this, f)
            scheduleApply(done)
        }
        pad.addView(sl, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(10), 0, 0))

        val ends = LinearLayout(this)
        ends.orientation = LinearLayout.HORIZONTAL
        ends.addView(tv(this, fracText(AppPrefs.PILL_MIN), 11.5f, pal.sub), lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(tv(this, fracText(AppPrefs.PILL_MAX), 11.5f, pal.sub))
        ends.setPaddingRelative(dp(4), 0, dp(4), 0)
        pad.addView(ends, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        reset.setOnClickListener {
            val d = AppPrefs.PILL_DEFAULT
            sl.value = (d - AppPrefs.PILL_MIN) / range
            pv.frac = d
            vt.text = fracText(d)
            AppPrefs.setPillFrac(this, d)
            scheduleApply(true)
        }

        card.addView(pad, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun linkRow(kind: Int, title: String, desc: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dp(16), dp(14), dp(14), dp(14))
        row.background = rippleOf(pal, null)
        row.isClickable = true
        row.isFocusable = true
        row.addView(badgeView(this, kind, 40), lpv(dp(40), dp(40)).m(0, 0, dp(14), 0))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.addView(tv(this, title, 16f, pal.text, true))
        t.addView(tv(this, desc, 13f, pal.sub).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(2), 0, 0)
        })
        row.addView(t, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IconView(this, IconView.CHEVRON, pal.sub), lpv(dp(20), dp(20)))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun buildMoreCard(): View {
        val card = cardView(this, pal)
        card.addView(
            linkRow(IconView.BUG, s("bug_title"), s("bug_desc")) { showBugReport(this, pal, lang) },
            lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        card.addView(dividerView(this, pal, 70))
        card.addView(
            linkRow(IconView.INFO, s("about_title"), s("about_desc")) {
                startActivity(Intent(this, AboutActivity::class.java))
            },
            lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return card
    }

    private fun buildFooter(): View {
        val f = LinearLayout(this)
        f.orientation = LinearLayout.VERTICAL
        f.gravity = Gravity.CENTER_HORIZONTAL
        val link = tv(this, AppInfo.CHANNEL_NAME, 14f, pal.accent, true)
        link.gravity = Gravity.CENTER
        link.setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
        link.background = rippleOf(pal, null)
        link.isClickable = true
        link.setOnClickListener { openUrl(this, lang, AppInfo.CHANNEL_URL) }
        f.addView(link, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ver = tv(this, "IOSLock " + AppInfo.VERSION + "  \u2022  " + AppInfo.AUTHOR, 12f, pal.sub)
        ver.gravity = Gravity.CENTER
        f.addView(ver, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        f.layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(22), 0, 0)
        return f
    }
}
