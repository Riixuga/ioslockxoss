package com.miko.ioslock

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AboutActivity : Activity() {

    private lateinit var pal: Pal
    private var lang = Strings.EN
    private var builtLang = ""

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
        if (AppPrefs.lang(this) != builtLang) recreate()
    }

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
        body.setPaddingRelative(dp(16), dp(18), dp(16), dp(28))
        body.addView(buildDescriptionCard())
        body.addView(sectionLabel(this, pal, s("info_title")))
        body.addView(buildInfoCard())
        body.addView(sectionLabel(this, pal, s("support_title")))
        body.addView(buildSupportCard())
        body.addView(buildFooter())

        col.addView(header, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(body, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headerPad = header.paddingTop
        onInsets(root) { top, bottom ->
            header.setPaddingRelative(dp(16), headerPad + top, dp(16), dp(28))
            body.setPaddingRelative(dp(16), dp(18), dp(16), dp(28) + bottom)
        }
        return root
    }

    private fun buildHeader(): LinearLayout {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.VERTICAL
        h.background = HeaderDrawable(dpf(30f))
        h.setPaddingRelative(dp(16), dp(14), dp(16), dp(28))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(
            headerIconButton(this, IconView.BACK, s("back")) { finish() },
            lpv(dp(42), dp(42))
        )
        top.addView(tv(this, s("about_title"), 19f, Color.WHITE, true).apply {
            layoutParams = lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).m(dp(12), 0, 0, 0)
        })
        h.addView(top, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER_HORIZONTAL

        val logo = FrameLayout(this)
        val logoBg = GradientDrawable()
        logoBg.cornerRadius = dpf(26f)
        logoBg.setColor(0x33FFFFFF)
        logoBg.setStroke(dp(1), 0x66FFFFFF)
        logo.background = logoBg
        logo.addView(IconView(this, IconView.PILL, Color.WHITE), FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        center.addView(logo, lpv(dp(84), dp(84)))

        val name = tv(this, "IOSLock", 27f, Color.WHITE, true)
        name.gravity = Gravity.CENTER
        center.addView(name, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(14), 0, 0))

        val chip = tv(this, s("version") + " " + AppInfo.VERSION, 13f, Color.WHITE, true)
        val cbg = GradientDrawable()
        cbg.cornerRadius = dpf(20f)
        cbg.setColor(0x2EFFFFFF)
        cbg.setStroke(dp(1), Brand.cyan and 0x00FFFFFF or 0x88000000.toInt())
        chip.background = cbg
        chip.setPaddingRelative(dp(14), dp(6), dp(14), dp(6))
        center.addView(chip, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(10), 0, 0))

        val sub = tv(this, s("subtitle"), 13f, 0xDDFFFFFF.toInt())
        sub.gravity = Gravity.CENTER
        center.addView(sub, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(10), 0, 0))

        h.addView(center, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(14), 0, 0))
        return h
    }

    private fun buildDescriptionCard(): View {
        val card = cardView(this, pal)
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setPaddingRelative(dp(18), dp(18), dp(18), dp(18))

        val text = tv(this, s("about_text"), 14.5f, pal.text)
        text.setLineSpacing(0f, 1.18f)
        pad.addView(text, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pad.addView(tv(this, s("feat_title"), 15f, pal.text, true).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(18), 0, dp(6))
        })
        for (k in listOf("feat1", "feat2", "feat3", "feat4")) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            val ic = FrameLayout(this)
            val ibg = GradientDrawable()
            ibg.shape = GradientDrawable.OVAL
            ibg.setColor(pal.chip)
            ic.background = ibg
            ic.addView(IconView(this, IconView.CHECK, Brand.cyan), FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
            r.addView(ic, lpv(dp(22), dp(22)).m(0, dp(1), dp(10), 0))
            val t = tv(this, s(k), 14f, pal.text)
            t.setLineSpacing(0f, 1.1f)
            r.addView(t, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pad.addView(r, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(5), 0, dp(5)))
        }

        val req = LinearLayout(this)
        req.orientation = LinearLayout.VERTICAL
        val rbg = GradientDrawable()
        rbg.cornerRadius = dpf(16f)
        rbg.setColor(pal.chip)
        req.background = rbg
        req.setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
        val rh = LinearLayout(this)
        rh.orientation = LinearLayout.HORIZONTAL
        rh.gravity = Gravity.CENTER_VERTICAL
        rh.addView(IconView(this, IconView.INFO, pal.accent), lpv(dp(18), dp(18)).m(0, 0, dp(8), 0))
        rh.addView(tv(this, s("req_title"), 14f, pal.accent, true))
        req.addView(rh)
        val rt = tv(this, s("req_text"), 13.5f, pal.sub)
        rt.setLineSpacing(0f, 1.12f)
        req.addView(rt, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(6), 0, 0))
        pad.addView(req, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(16), 0, 0))

        card.addView(pad, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    /** Baris informasi: ikon, label kecil, nilai. Kalau onClick != null, barisnya bisa diketuk. */
    private fun infoRow(kind: Int, label: String, value: String, accentValue: Boolean, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dp(16), dp(13), dp(14), dp(13))
        row.addView(badgeView(this, kind, 38), lpv(dp(38), dp(38)).m(0, 0, dp(14), 0))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.addView(tv(this, label, 12.5f, pal.sub))
        t.addView(tv(this, value, 16f, if (accentValue) pal.accent else pal.text, true).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(2), 0, 0)
        })
        row.addView(t, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.background = rippleOf(pal, null)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
            row.addView(IconView(this, IconView.CHEVRON, pal.sub), lpv(dp(20), dp(20)))
        }
        return row
    }

    private fun buildInfoCard(): View {
        val card = cardView(this, pal)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        card.addView(infoRow(IconView.TAG, s("version"), AppInfo.VERSION, false, null), lpv(match, wrap))
        card.addView(dividerView(this, pal, 68))
        card.addView(infoRow(IconView.USER, s("author"), AppInfo.AUTHOR, false, null), lpv(match, wrap))
        card.addView(dividerView(this, pal, 68))
        card.addView(
            infoRow(IconView.TELEGRAM, s("channel"), AppInfo.CHANNEL_NAME, true) {
                openUrl(this, lang, AppInfo.CHANNEL_URL)
            },
            lpv(match, wrap)
        )
        card.addView(dividerView(this, pal, 68))

        // donasi: label di kiri, tombol gradient "Buy me a coffee" di kanan
        val donate = LinearLayout(this)
        donate.orientation = LinearLayout.HORIZONTAL
        donate.gravity = Gravity.CENTER_VERTICAL
        donate.setPaddingRelative(dp(16), dp(13), dp(14), dp(13))
        donate.addView(badgeView(this, IconView.COFFEE, 38), lpv(dp(38), dp(38)).m(0, 0, dp(14), 0))
        donate.addView(tv(this, s("donate"), 16f, pal.text, true), lpv(0, wrap, 1f))
        val btn = gradientButton(this, AppInfo.DONATE_TEXT, IconView.COFFEE)
        btn.setPaddingRelative(dp(14), dp(9), dp(14), dp(9))
        btn.setOnClickListener { openUrl(this, lang, AppInfo.DONATE_URL) }
        donate.setOnClickListener { openUrl(this, lang, AppInfo.DONATE_URL) }
        donate.background = rippleOf(pal, null)
        donate.isClickable = true
        donate.addView(btn, lpv(wrap, wrap))
        card.addView(donate, lpv(match, wrap))
        return card
    }

    private fun buildSupportCard(): View {
        val card = cardView(this, pal)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dp(16), dp(14), dp(14), dp(14))
        row.background = rippleOf(pal, null)
        row.isClickable = true
        row.isFocusable = true
        row.addView(badgeView(this, IconView.BUG, 40), lpv(dp(40), dp(40)).m(0, 0, dp(14), 0))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.addView(tv(this, s("bug_title"), 16f, pal.text, true))
        t.addView(tv(this, s("bug_desc"), 13f, pal.sub).apply {
            layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(2), 0, 0)
        })
        row.addView(t, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IconView(this, IconView.CHEVRON, pal.sub), lpv(dp(20), dp(20)))
        row.setOnClickListener { showBugReport(this, pal, lang) }
        card.addView(row, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun buildFooter(): View {
        val v: TextView = tv(this, "\u00A9 2026 " + AppInfo.AUTHOR, 12f, pal.sub)
        v.gravity = Gravity.CENTER
        v.layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, dp(24), 0, 0)
        return v
    }
}
