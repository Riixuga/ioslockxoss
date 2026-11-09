package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

object Config {
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun prop(key: String): String {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.first < 500) return it.second }
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
    val forceClockLevel: Int? get() = prop("debug.ioslock.level").toIntOrNull()
    val pushDp: Float get() = prop("debug.ioslock.pushdp").toFloatOrNull() ?: 120f
    val minScale: Float get() = prop("debug.ioslock.minscale").toFloatOrNull() ?: 0.5f
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 300f
    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f
    // id view jam tambahan yang ingin digeser/dikecilkan (lihat log visibleBig)
    val clockId: String get() = prop("debug.ioslock.clockid")
    // guard tambahan: hanya ambil alih jika tinggi shade <= 1
    val guardH: Boolean get() = prop("debug.ioslock.guardh") == "1"
}

class MainHook : IXposedHookLoadPackage {

    private val tag = "[IOSLock]"
    private val lastLog = ConcurrentHashMap<String, Long>()
    private val lastMsg = ConcurrentHashMap<String, String>()
    private val dumped = ConcurrentHashMap.newKeySet<String>()

    private val attachedClocks = CopyOnWriteArrayList<WeakReference<View>>()
    @Volatile private var activeClocks: List<View> = emptyList()
    @Volatile private var stackRef: WeakReference<View>? = null

    // 0 = SHADE, 1 = KEYGUARD, 2 = SHADE_LOCKED, -1 = belum diketahui
    @Volatile private var barState = -1
    @Volatile private var shadeH = 0f

    // posisi asli tiap kartu notifikasi: [dasar, nilai yang kita tulis]
    private val rowState = WeakHashMap<View, FloatArray>()

    // state gestur
    private var progress = 0f
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
        log("v0.5 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"
        val stateCtl = "com.android.systemui.statusbar.StatusBarStateControllerImpl"

        hook(cl, clockView, "onAttachedToWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            if (!isRealClock(v)) return@hook
            attachedClocks.add(WeakReference(v))
            logT("clockAttached", "total=${attachedClocks.size} ${describeView(v)}", discrete = true)
        })
        hook(cl, clockView, "onDetachedFromWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            attachedClocks.removeAll { it.get() == null || it.get() === v }
        })
        hook(cl, clockView, "setClockLevel", before = { p ->
            logT("setClockLevel", "arg=${p.args.getOrNull(0)} caller=${caller()}", discrete = true)
            Config.forceClockLevel?.let { lv ->
                try { p.args[0] = lv } catch (t: Throwable) { log("force level gagal: $t") }
            }
        }, after = { applyState() })
        hook(cl, clockView, "getClockLevel", after = { p ->
            logT("getClockLevel", "ret=${p.result}", discrete = true)
        })
        hook(cl, clockView, "updateClockViewParams", before = { p ->
            logT("updateClockViewParams", "arg=${p.args.getOrNull(0)}", discrete = true)
        }, after = { applyState() })

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })
        hook(cl, panel, "onHeightUpdated", after = { p ->
            (p.args.getOrNull(0) as? Float)?.let { shadeH = it; logT("shadeH", "h=$it") }
        })

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
            barState = s
            logT("barState", "state=$s", discrete = true)
            applyState()
        })

        // Ambil View stack notifikasi dari controller-nya
        hook(cl, stack, "updateVisibility", after = { p ->
            try {
                val v = XposedHelpers.callMethod(p.thisObject, "getView") as? View
                if (v != null) {
                    stackRef = WeakReference(v)
                    once("stackView") { log("stack view: " + describeView(v)) }
                }
            } catch (t: Throwable) { once("stackViewFail") { log("getView gagal: $t") } }
        })

        // Tangkap sentuhan di window shade
        hook(cl, shadeWindow, "dispatchTouchEvent", before = { p -> handleTouch(p) })
    }

    private fun isLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (t: Throwable) { false }

    private fun onKeyguardState(): Boolean = barState == 1 || barState == -1

    private fun findViews(v: View, depth: Int, out: MutableList<View>, pred: (View) -> Boolean) {
        if (depth > 14 || out.size > 8) return
        if (pred(v)) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findViews(v.getChildAt(i), depth + 1, out, pred)
        }
    }

    // Jam bawaan = id lockscreen_clock_view ("lockscreen_measure_clock_view" hanya pengukur)
    private fun isRealClock(v: View): Boolean {
        val id = idName(v)
        if (id == "lockscreen_measure_clock_view") return false
        return id == "lockscreen_clock_view" || v.javaClass.name.endsWith("KeyguardClockView")
    }

    private fun refreshTargets(root: View) {
        val top = root.rootView
        val known = attachedClocks.mapNotNull { it.get() }.filter { it.isAttachedToWindow && it.width > 0 }
        var list: List<View> = if (known.isNotEmpty()) known else {
            val out = ArrayList<View>()
            findViews(top, 0, out) { isRealClock(it) && it.width > 0 }
            out
        }
        val extra = Config.clockId
        if (extra.isNotEmpty()) {
            val out = ArrayList<View>()
            findViews(top, 0, out) { idName(it) == extra }
            list = list + out.filter { it !in list }
        }
        activeClocks = list

        val sv = stackRef?.get()
        if (sv == null || !sv.isAttachedToWindow) {
            val out = ArrayList<View>()
            findViews(top, 0, out) { idName(it) == "notification_stack_scroller" }
            out.firstOrNull()?.let { stackRef = WeakReference(it) }
        }
    }

    // Struktur view (semua, sampai kedalaman tertentu)
    private fun dumpAll(v: View, depth: Int, maxDepth: Int, sb: StringBuilder, count: IntArray) {
        if (depth > maxDepth || count[0] > 90) return
        sb.append("\n").append("  ".repeat(depth)).append(v.javaClass.simpleName)
            .append(" id=").append(idName(v)).append(" shown=").append(v.isShown)
            .append(" ").append(v.width).append("x").append(v.height)
        count[0]++
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpAll(v.getChildAt(i), depth + 1, maxDepth, sb, count)
        }
    }

    // View besar yang sedang terlihat (untuk mencari jam yang sebenarnya)
    private fun scanBig(v: View, depth: Int, sb: StringBuilder, count: IntArray) {
        if (depth > 18 || count[0] > 30) return
        if (v.isShown && v.width >= 300 && v.height >= 120 && !(v.width >= 1000 && v.height >= 2000)) {
            sb.append("\n  ").append(v.javaClass.name.substringAfterLast('.'))
                .append(" id=").append(idName(v))
                .append(" ").append(v.width).append("x").append(v.height)
            if (v is TextView) sb.append(" text='").append(v.text?.toString()?.take(12)).append("'")
                        var par: android.view.ViewParent? = v.parent
            var n = 0
            sb.append(" <")
            while (par is View && n < 3) {
                val pv: View = par
                sb.append(" ").append(pv.javaClass.simpleName).append("#").append(idName(pv))
                par = pv.parent
                n++
            }

    private fun handleTouch(p: XC_MethodHook.MethodHookParam) {
        if (!Config.gesture) { if (applied) { progress = 0f; applyState() }; return }
        val root = p.thisObject as? View ?: return
        val ev = p.args[0] as? MotionEvent ?: return
        val dm = root.resources.displayMetrics
        val a = ev.actionMasked

        if (a == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            taken = false
            downX = ev.rawX; downY = ev.rawY
            val locked = isLocked(root.context)
            if (locked) {
                refreshTargets(root)
                once("tree2") {
                    val sb = StringBuilder("tree2:")
                    dumpAll(root.rootView, 0, 5, sb, intArrayOf(0))
                    log(sb.toString())
                }
                activeClocks.firstOrNull { idName(it) == "lockscreen_clock_view" }?.let {
                    dumpFields("clock", it)
                }
                val big = StringBuilder("visibleBig:")
                scanBig(root.rootView, 0, big, intArrayOf(0))
                logT("visibleBig", big.toString(), discrete = true)
            } else if (applied) {
                progress = 0f
                applyState()
            }
            logT(
                "touchSeen",
                "locked=$locked state=$barState shadeH=$shadeH clocks=${activeClocks.size} stack=${stackRef?.get() != null}",
                discrete = true
            )
            tracking = locked && onKeyguardState() &&
                (!Config.guardH || shadeH <= 1f) &&
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
                // batalkan sentuhan di handler asli supaya tidak membuka kunci
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
                    progress = (baseProgress + (startY - ev.rawY) / (Config.rangeDp * dm.density)).coerceIn(0f, 1f)
                    applyState()
                    logT("progress", "p=$progress")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    taken = false
                    snap(if (progress > 0.35f) 1f else 0f)
                }
            }
            p.result = true
        }
    }

    private fun snap(target: Float) {
        animator?.cancel()
        val an = ValueAnimator.ofFloat(progress, target)
        an.duration = 260
        an.addUpdateListener { progress = it.animatedValue as Float; applyState() }
        animator = an
        an.start()
    }

    private fun applyState() {
        try {
            val clocks = activeClocks
            val sv = stackRef?.get()
            val ctx = clocks.firstOrNull()?.context ?: sv?.context
            val locked = ctx != null && isLocked(ctx)
            val onKeyguard = locked && onKeyguardState()
            if (!Config.gesture || !onKeyguard) {
                if (applied) { reset(clocks, sv); applied = false }
                if (!locked) progress = 0f
                return
            }
            applied = true
            val p = progress
            for (clock in clocks) {
                val d = clock.resources.displayMetrics.density
                val s = 1f - (1f - Config.minScale) * p
                clock.pivotX = clock.width / 2f
                clock.pivotY = 0f
                clock.scaleX = s
                clock.scaleY = s
                clock.translationY = -Config.pushDp * d * p
            }
            if (sv != null) applyStack(sv, p)
        } catch (t: Throwable) { once("applyFail") { log("applyState gagal: $t") } }
    }

    private fun baseOf(row: View): Float {
        val st = rowState[row]
        return if (st != null && abs(row.translationY - st[1]) < 0.01f) st[0] else row.translationY
    }

    // Animasi stack: kartu menumpuk di kartu teratas, lalu menyebar saat p naik ke 1
    private fun applyStack(sv: View, p: Float) {
        val d = sv.resources.displayMetrics.density
        sv.alpha = (p * 3f).coerceAtMost(1f)
        sv.translationY = 0f
        val g = sv as? ViewGroup ?: return
        val rows = ArrayList<Pair<View, Float>>()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.javaClass.name.contains("ExpandableNotificationRow")) {
                rows.add(c to baseOf(c))
            }
        }
        rows.sortBy { it.second }
        val base0 = rows.firstOrNull()?.second ?: return
        rows.forEachIndexed { i, (row, base) ->
            val off = (1f - p) * ((base0 - base) + i * 10f * d)
            val w = base + off
            row.translationY = w
            val s = 1f - (1f - p) * 0.05f * i
            row.pivotX = row.width / 2f
            row.scaleX = s
            row.scaleY = s
            rowState[row] = floatArrayOf(base, w)
        }
    }

    private fun reset(clocks: List<View>, sv: View?) {
        for (c in clocks) { c.scaleX = 1f; c.scaleY = 1f; c.translationY = 0f }
        if (sv != null) {
            sv.alpha = 1f
            sv.translationY = 0f
            (sv as? ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    val st = rowState[c] ?: continue
                    if (abs(c.translationY - st[1]) < 0.01f) c.translationY = st[0]
                    c.scaleX = 1f
                    c.scaleY = 1f
                    rowState.remove(c)
                }
            }
        }
    }

    private fun describeView(v: View): String =
        "${v.javaClass.name} id=${idName(v)} size=${v.width}x${v.height} y=${v.y} " +
            "scale=${v.scaleX}/${v.scaleY} transY=${v.translationY} alpha=${v.alpha} vis=${v.visibility} shown=${v.isShown}"

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "none" else v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) { "0x" + Integer.toHexString(v.id) }

    private fun dumpFields(key: String, obj: Any) {
        once("dump:$key") {
            val sb = StringBuilder("fields of $key:")
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    val value = try {
                        f.isAccessible = true
                        val x = f.get(obj)
                        when {
                            x == null -> "null"
                            x is View -> x.javaClass.simpleName + "#" + idName(x)
                            f.type.isPrimitive || x is String || x is Number -> x.toString()
                            else -> x.javaClass.name
                        }
                    } catch (t: Throwable) { "?" }
                    sb.append("\n  ${c.simpleName}.${f.name}: ${f.type.simpleName} = $value")
                }
                c = c.superclass
            }
            log(sb.toString())
        }
    }

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

    private fun caller(): String =
        Throwable().stackTrace.drop(3)
            .filter { it.className.contains("systemui") || it.className.contains("transsion") }
            .take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }

    private fun once(key: String, block: () -> Unit) { if (dumped.add(key)) block() }

    private fun logT(key: String, msg: String, discrete: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastLog[key] ?: 0L
        val changed = lastMsg[key] != msg
        if ((discrete && changed) || now - last >= 250) {
            lastLog[key] = now; lastMsg[key] = msg
            log("$key: $msg")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("$tag $msg")
        android.util.Log.i("IOSLock", msg)
    }
}
