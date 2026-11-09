package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

object Config {
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun prop(key: String): String {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.first < 400) return it.second }
        val v = try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"), "get", key, ""
            ) as? String ?: ""
        } catch (t: Throwable) { "" }
        cache[key] = now to v
        return v
    }

    // Menyala otomatis; matikan dengan: setprop debug.ioslock.gesture 0
    val gesture: Boolean get() = prop("debug.ioslock.gesture") != "0"
    // jam wallpaper dikunci tinggi saat diam, kecil di tahap 2 (setting key_switch_gear_to_small)
    val gearForce: Boolean get() = prop("debug.ioslock.gearforce") != "0"
    // tahap 2 dimulai setelah progres melewati 1 + gearAt
    val gearAt: Float get() = prop("debug.ioslock.gearat").toFloatOrNull() ?: 0.3f

    // posisi bagian atas stack (0..1 dari tinggi layar)
    val restFrac: Float get() = prop("debug.ioslock.restfrac").toFloatOrNull() ?: 0.575f
    val smallFrac: Float get() = prop("debug.ioslock.smallfrac").toFloatOrNull() ?: 0.345f
    // posisi tengah tumpukan di awal, dan posisi teks jumlah notifikasi
    val pileFrac: Float get() = prop("debug.ioslock.pilefrac").toFloatOrNull() ?: 0.84f
    val countFrac: Float get() = prop("debug.ioslock.countfrac").toFloatOrNull() ?: 0.845f
    val pileScale: Float get() = prop("debug.ioslock.pilescale").toFloatOrNull() ?: 0.80f
    val peekDp: Float get() = prop("debug.ioslock.peekdp").toFloatOrNull() ?: 14f

    // jarak swipe tiap tahap (dp)
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 300f
    val stage2Dp: Float get() = prop("debug.ioslock.stage2dp").toFloatOrNull() ?: 100f
    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f
}

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val GEAR_KEY = "key_switch_gear_to_small"
    }

    private val tag = "[IOSLock]"
    private val lastLog = ConcurrentHashMap<String, Long>()
    private val lastMsg = ConcurrentHashMap<String, String>()
    private val dumped = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var stackRef: WeakReference<View>? = null

    // 0 = SHADE, 1 = KEYGUARD, 2 = SHADE_LOCKED, -1 = belum diketahui
    @Volatile private var barState = -1

    // posisi asli tiap kartu: [dasarY, tulisY, dasarZ, tulisZ]
    private val rowState = WeakHashMap<View, FloatArray>()

    // kontrol setting gear jam wallpaper
    @Volatile private var ownWrite = false
    @Volatile private var sysGearValue = ""
    private var lastGearWritten: String? = null

    // label
    private var labelCenter: TextView? = null
    private var labelCount: TextView? = null

    // state gestur. progress: 0..1 = tahap 1, 1..2 = tahap 2
    @Volatile private var progress = 0f
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var baseProgress = 0f
    private var tracking = false
    private var taken = false
    private var applied = false
    private var animator: ValueAnimator? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.11 loaded into SystemUI")

        val clockHelper = "com.transsion.keyguard.clock.KeyguardClockHelper"
        val clockInteractor = "com.transsion.keyguard.clock.domain.interactor.KeyguardClockInteractor"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"
        val stateCtl = "com.android.systemui.statusbar.StatusBarStateControllerImpl"

        // Setting gear jam wallpaper: ganti nilai yang ditulis System UI
        hook(cl, "android.provider.Settings\$Secure", "putStringForUser", before = { p ->
            val name = p.args.getOrNull(1)?.toString() ?: return@hook
            if (name != GEAR_KEY) return@hook
            val sysVal = p.args.getOrNull(2)?.toString() ?: ""
            if (!ownWrite) sysGearValue = sysVal
            val forced = if (!ownWrite && shouldForceGear()) desiredGear() else null
            logT("GEARSET", "sys=$sysVal forced=$forced own=$ownWrite progress=$progress state=$barState", discrete = true)
            if (forced != null) p.args[2] = forced
        })

        // Hanya dicatat (informasi)
        hook(cl, clockInteractor, "switchClockGear", before = { p ->
            logT("switchClockGear", "args=${p.args.toList()} progress=$progress state=$barState", discrete = true)
        })

        // Layar menyala / mati: progres swipe lama tidak boleh tersisa
        hook(cl, clockHelper, "powerModeChanged", after = {
            animator?.cancel()
            progress = 0f
            lastGearWritten = null
            applyState()
        })

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
            if (s == 1 && barState != 1) {
                animator?.cancel()
                progress = 0f
                lastGearWritten = null
            }
            barState = s
            logT("barState", "state=$s", discrete = true)
            applyState()
        })

        // Ambil View stack notifikasi dari controller-nya (field mView)
        hook(cl, stack, "updateVisibility", after = { p ->
            val v = try {
                XposedHelpers.getObjectField(p.thisObject, "mView") as? View
            } catch (t: Throwable) { null }
            if (v != null) stackRef = WeakReference(v)
        })

        // Tangkap sentuhan di window shade
        hook(cl, shadeWindow, "dispatchTouchEvent", before = { p -> handleTouch(p) })
    }

    // ---- setting gear ----

    private fun shouldForceGear(): Boolean = Config.gesture && Config.gearForce && barState == 1

    // "1" = jam biasa (kecil), "0" = jam tinggi
    private fun desiredGear(): String = if (progress >= 1f + Config.gearAt) "1" else "0"

    private fun ensureGear(ctx: Context) {
        if (!Config.gearForce) return
        val want = desiredGear()
        if (lastGearWritten == want) return
        lastGearWritten = want
        try {
            ownWrite = true
            Settings.Secure.putInt(ctx.contentResolver, GEAR_KEY, want.toInt())
        } catch (t: Throwable) {
            once("gearWriteFail") { log("tulis gear gagal: $t") }
        } finally {
            ownWrite = false
        }
    }

    private fun restoreGear(ctx: Context?) {
        if (lastGearWritten == null) return
        lastGearWritten = null
        val sys = sysGearValue.toIntOrNull() ?: return
        if (ctx == null) return
        try {
            ownWrite = true
            Settings.Secure.putInt(ctx.contentResolver, GEAR_KEY, sys)
        } catch (t: Throwable) {
            once("gearRestoreFail") { log("pulihkan gear gagal: $t") }
        } finally {
            ownWrite = false
        }
    }

    // ---- utilitas ----

    private fun isLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (t: Throwable) { false }

    private fun onKeyguardState(): Boolean = barState == 1 || barState == -1

    private fun findStack(root: View) {
        val sv = stackRef?.get()
        if (sv != null && sv.isAttachedToWindow) return
        val found = ArrayList<View>()
        findViews(root.rootView, 0, found) { idName(it) == "notification_stack_scroller" }
        val first = found.firstOrNull()
        if (first != null) stackRef = WeakReference(first)
    }

    private fun findViews(v: View, depth: Int, out: MutableList<View>, pred: (View) -> Boolean) {
        if (depth > 14 || out.size > 8) return
        if (pred(v)) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findViews(v.getChildAt(i), depth + 1, out, pred)
        }
    }

    private fun hasCards(): Boolean {
        val g = stackRef?.get() as? ViewGroup ?: return false
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) return true
        }
        return false
    }

    // jumlah notifikasi (kartu musik dan senter tidak dihitung)
    private fun notifCount(g: ViewGroup): Int {
        var n = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.javaClass.name.contains("ExpandableNotificationRow") && c.height > 0) {
                var k = 0
                try {
                    k = (XposedHelpers.callMethod(c, "getNumberOfNotificationChildren") as? Int) ?: 0
                } catch (t: Throwable) { }
                n += if (k > 0) k else 1
            }
        }
        return n
    }

    // ---- konversi progres <-> jarak ----

    private fun progressToDist(p: Float): Float {
        val l1 = Config.rangeDp
        val l2 = Config.stage2Dp
        return if (p <= 1f) p * l1 else l1 + (p - 1f) * l2
    }

    private fun distToProgress(d: Float): Float {
        val l1 = Config.rangeDp
        val l2 = Config.stage2Dp
        return if (d <= l1) d / l1 else 1f + (d - l1) / l2
    }

    // ---- sentuhan ----

    private fun handleTouch(p: XC_MethodHook.MethodHookParam) {
        val ev = p.args[0] as? MotionEvent ?: return
        val root = p.thisObject as? View ?: return
        if (!Config.gesture) { if (applied) { progress = 0f; applyState() }; return }
        val dm = root.resources.displayMetrics
        val a = ev.actionMasked

        if (a == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            taken = false
            downX = ev.rawX
            downY = ev.rawY
            val locked = isLocked(root.context)
            if (locked) {
                findStack(root)
            } else if (applied) {
                progress = 0f
                applyState()
            }
            logT("touchSeen", "locked=$locked state=$barState stack=${stackRef?.get() != null} cards=${hasCards()}", discrete = true)
            // tanpa kartu sama sekali: biarkan swipe asli (membuka kunci)
            tracking = locked && onKeyguardState() && hasCards() &&
                ev.rawY < dm.heightPixels - Config.bottomZoneDp * dm.density
            return
        }
        if (!tracking) return

        if (!taken && a == MotionEvent.ACTION_MOVE) {
            val dy = downY - ev.rawY
            val dx = ev.rawX - downX
            val slop = ViewConfiguration.get(root.context).scaledTouchSlop
            val up = dy > slop
            val down = progress > 0f && -dy > slop
            if ((up || down) && abs(dy) > abs(dx) * 1.2f) {
                try {
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject, arrayOf<Any?>(c))
                    c.recycle()
                } catch (t: Throwable) { log("cancel gagal: $t") }
                taken = true
                startY = ev.rawY
                baseProgress = progress
                log("takeover swipe, base=$baseProgress")
            }
        }

        if (taken) {
            when (a) {
                MotionEvent.ACTION_MOVE -> {
                    val moved = (startY - ev.rawY) / dm.density
                    val d = progressToDist(baseProgress) + moved
                    progress = distToProgress(d.coerceAtLeast(0f)).coerceIn(0f, 2f)
                    applyState()
                    logT("progress", "p=$progress")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    taken = false
                    // tiga keadaan diam: 0 (tersembunyi), 1 (stack di bawah jam tinggi), 2 (jam kecil)
                    val target = when {
                        progress < 0.35f -> 0f
                        progress < 1.5f -> 1f
                        else -> 2f
                    }
                    if (target != progress) snap(target)
                }
            }
            p.result = true
        }
    }

    private fun snap(target: Float) {
        animator?.cancel()
        val an = ValueAnimator.ofFloat(progress, target)
        an.duration = 280
        an.addUpdateListener { progress = it.animatedValue as Float; applyState() }
        animator = an
        an.start()
    }

    // ---- penerapan efek ----

    private fun applyState() {
        try {
            val sv = stackRef?.get()
            val ctx = sv?.context
            val locked = ctx != null && isLocked(ctx)
            val onKeyguard = locked && onKeyguardState()
            if (!Config.gesture || !onKeyguard) {
                if (applied) { reset(sv, ctx); applied = false }
                if (!locked) progress = 0f
                return
            }
            applied = true
            val p = progress
            val p1 = min(p, 1f)
            val q = (p - 1f).coerceIn(0f, 1f)
            if (ctx != null) ensureGear(ctx)
            if (sv != null) applyStack(sv, p1, q)
        } catch (t: Throwable) { once("applyFail") { log("applyState gagal: $t") } }
    }

    private fun baseOfY(v: View): Float {
        val st = rowState[v]
        return if (st != null && abs(v.translationY - st[1]) < 0.01f) st[0] else v.translationY
    }

    private fun baseOfZ(v: View): Float {
        val st = rowState[v]
        return if (st != null && abs(v.translationZ - st[3]) < 0.01f) st[2] else v.translationZ
    }

    // p1: 0..1 (tumpukan di bawah -> bawah jam tinggi), q: 0..1 (jam tinggi -> jam kecil, stack naik)
    private fun applyStack(sv: View, p1: Float, q: Float) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        sv.alpha = (p1 * 2.5f).coerceAtMost(1f)
        // saat tersembunyi, stack tidak boleh menerima sentuhan
        sv.visibility = if (p1 < 0.02f) View.INVISIBLE else View.VISIBLE
        sv.translationY = 0f
        val g = sv as? ViewGroup ?: return
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)

        val cards = ArrayList<Pair<View, Float>>()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) {
                cards.add(Pair(c, baseOfY(c)))
            }
        }
        cards.sortBy { it.second }
        val n = cards.size

        val restTop = dm.heightPixels * Config.restFrac
        val smallTop = dm.heightPixels * Config.smallFrac
        val targetTop = restTop + (smallTop - restTop) * q

        updateLabels(sv, p1, targetTop, notifCount(g))
        if (n == 0) return

        val naturalTop = loc[1] + cards[0].second
        val shift = targetTop - naturalTop

        val pileCenter = dm.heightPixels * Config.pileFrac
        for (i in 0 until n) {
            val card = cards[i].first
            val baseY = cards[i].second
            val baseZ = baseOfZ(card)
            val h = card.height.toFloat()

            val base2 = baseY + shift
            val naturalCenter = loc[1] + base2 + h / 2f
            val targetCenter = pileCenter + i * Config.peekDp * d
            val wy = base2 + (1f - p1) * (targetCenter - naturalCenter)

            val collapsed = (Config.pileScale - 0.05f * i).coerceAtLeast(0.55f)
            val s = 1f - (1f - p1) * (1f - collapsed)
            val wz = baseZ + (1f - p1) * (n - i) * 3f * d

            card.pivotX = card.width / 2f
            card.pivotY = h / 2f
            card.translationY = wy
            card.translationZ = wz
            card.scaleX = s
            card.scaleY = s
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz)
        }
    }

    // ---- label ----

    private fun makeLabel(ctx: Context, sp: Float, bold: Boolean, d: Float): TextView {
        val t = TextView(ctx)
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        t.setShadowLayer(3f * d, 0f, 1f * d, 0x66000000)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.alpha = 0f
        t.visibility = View.INVISIBLE
        t.isClickable = false
        t.isFocusable = false
        return t
    }

    private fun ensureLabels(root: ViewGroup) {
        if (labelCenter?.parent === root && labelCount?.parent === root) return
        (labelCenter?.parent as? ViewGroup)?.removeView(labelCenter)
        (labelCount?.parent as? ViewGroup)?.removeView(labelCount)
        val ctx = root.context
        val d = ctx.resources.displayMetrics.density
        val lc = makeLabel(ctx, 17f, true, d)
        lc.text = "Notification Center"
        val ln = makeLabel(ctx, 14f, false, d)
        root.addView(lc, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ln, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labelCenter = lc
        labelCount = ln
    }

    private fun updateLabels(sv: View, p1: Float, targetTop: Float, count: Int) {
        val root = sv.rootView as? ViewGroup ?: return
        ensureLabels(root)
        val lc = labelCenter ?: return
        val ln = labelCount ?: return
        val dm = root.resources.displayMetrics
        val d = dm.density
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)

        // "Notification Center": muncul di atas stack
        val a1 = ((p1 - 0.3f) / 0.5f).coerceIn(0f, 1f)
        lc.visibility = if (a1 > 0.01f) View.VISIBLE else View.INVISIBLE
        lc.alpha = a1
        val hc = if (lc.height > 0) lc.height.toFloat() else 28f * d
        lc.translationY = targetTop - rl[1] - hc - 6f * d

        // "N Notification(s)": muncul saat stack belum dimunculkan
        val a2 = if (count > 0) (1f - p1 * 3f).coerceIn(0f, 1f) else 0f
        ln.visibility = if (a2 > 0.01f) View.VISIBLE else View.INVISIBLE
        ln.alpha = a2
        if (count > 0) {
            val t = if (count == 1) "\u2022 1 Notification" else "\u2022 $count Notifications"
            if (ln.text.toString() != t) ln.text = t
        }
        val hn = if (ln.height > 0) ln.height.toFloat() else 24f * d
        ln.translationY = dm.heightPixels * Config.countFrac - rl[1] - hn / 2f
    }

    private fun hideLabels() {
        labelCenter?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
        labelCount?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
    }

    private fun reset(sv: View?, ctx: Context?) {
        restoreGear(ctx)
        hideLabels()
        if (sv == null) return
        sv.alpha = 1f
        sv.visibility = View.VISIBLE
        sv.translationY = 0f
        val g = sv as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val st = rowState[c]
            if (st != null) {
                if (abs(c.translationY - st[1]) < 0.01f) c.translationY = st[0]
                if (abs(c.translationZ - st[3]) < 0.01f) c.translationZ = st[2]
                c.scaleX = 1f
                c.scaleY = 1f
                rowState.remove(c)
            }
        }
    }

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "none" else v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) { "0x" + Integer.toHexString(v.id) }

    private fun hook(
        cl: ClassLoader, cls: String, method: String,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ) {
        try {
            val clazz = XposedHelpers.findClassIfExists(cls, cl)
            if (clazz == null) { log("MISS class $cls"); return }
            val set = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try { before?.invoke(p) } catch (t: Throwable) { log("err before $method: $t") }
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    try { after?.invoke(p) } catch (t: Throwable) { log("err after $method: $t") }
                }
            })
            log("hook ${cls.substringAfterLast('.')}.$method -> ${set.size} method(s)")
        } catch (t: Throwable) {
            log("hook fail $cls.$method: $t")
        }
    }

    private fun once(key: String, block: () -> Unit) { if (dumped.add(key)) block() }

    private fun logT(key: String, msg: String, discrete: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastLog[key] ?: 0L
        val changed = lastMsg[key] != msg
        if ((discrete && changed) || now - last >= 250) {
            lastLog[key] = now
            lastMsg[key] = msg
            log("$key: $msg")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("$tag $msg")
        android.util.Log.i("IOSLock", msg)
    }
}
