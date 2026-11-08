package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
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
}

class MainHook : IXposedHookLoadPackage {

    private val tag = "[IOSLock]"
    private val lastLog = ConcurrentHashMap<String, Long>()
    private val lastMsg = ConcurrentHashMap<String, String>()
    private val dumped = ConcurrentHashMap.newKeySet<String>()
    private val treeRegex = Regex("(?i)clock|stack|notif|keyguard|lock|media")

    private val attachedClocks = CopyOnWriteArrayList<WeakReference<View>>()
    @Volatile private var activeClocks: List<View> = emptyList()
    @Volatile private var stackRef: WeakReference<View>? = null

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
        log("v0.4.3 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"

        hook(cl, clockView, "onAttachedToWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            if (!isRealClock(v)) { logT("clockIgnored", describeView(v), discrete = true); return@hook }
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
        hook(cl, clockView, "updateClockViewParams", before = { p ->
            logT("updateClockViewParams", "arg=${p.args.getOrNull(0)}", discrete = true)
        }, after = { applyState() })

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })

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

    private fun findViews(v: View, depth: Int, out: MutableList<View>, pred: (View) -> Boolean) {
        if (depth > 14 || out.size > 8) return
        if (pred(v)) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findViews(v.getChildAt(i), depth + 1, out, pred)
        }
    }

    // Jam asli = id lockscreen_clock_view. "lockscreen_measure_clock_view" hanya untuk mengukur (selalu tersembunyi).
    private fun isRealClock(v: View): Boolean {
        val id = idName(v)
        if (id == "lockscreen_measure_clock_view") return false
        return id == "lockscreen_clock_view" || v.javaClass.name.endsWith("KeyguardClockView")
    }

    private fun refreshTargets(root: View) {
        val top = root.rootView
        // Tidak memakai isShown: saat transisi layar menyala, jam bisa sedang tersembunyi sesaat.
        val known = attachedClocks.mapNotNull { it.get() }.filter { it.isAttachedToWindow && it.width > 0 }
        activeClocks = if (known.isNotEmpty()) known else {
            val out = ArrayList<View>()
            findViews(top, 0, out) { isRealClock(it) && it.width > 0 }
            out
        }
        val sv = stackRef?.get()
        if (sv == null || !sv.isAttachedToWindow) {
            val out = ArrayList<View>()
            findViews(top, 0, out) { idName(it) == "notification_stack_scroller" }
            out.firstOrNull()?.let { stackRef = WeakReference(it) }
        }
    }

    private fun dumpTree(v: View, depth: Int, sb: StringBuilder, count: IntArray) {
        if (depth > 12 || count[0] > 120) return
        val n = v.javaClass.name
        val id = idName(v)
        if (treeRegex.containsMatchIn(n + id)) {
            sb.append("\n").append("  ".repeat(depth)).append(n.substringAfterLast('.'))
                .append(" id=").append(id).append(" shown=").append(v.isShown)
                .append(" ").append(v.width).append("x").append(v.height)
            count[0]++
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb, count)
        }
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
                once("tree") {
                    val sb = StringBuilder("tree:")
                    dumpTree(root.rootView, 0, sb, intArrayOf(0))
                    log(sb.toString())
                }
            } else if (applied) {
                progress = 0f
                applyState()
            }
            logT(
                "touchSeen",
                "locked=$locked clocks=${activeClocks.size} stack=${stackRef?.get() != null}",
                discrete = true
            )
            activeClocks.firstOrNull()?.let { logT("clockAtTouch", describeView(it), discrete = true) }
            tracking = locked && activeClocks.isNotEmpty() &&
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
        an.duration = 220
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
            if (!Config.gesture || !locked) {
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
            if (sv != null) {
                val d = sv.resources.displayMetrics.density
                sv.alpha = p
                sv.translationY = (1f - p) * 48f * d
            }
        } catch (t: Throwable) { once("applyFail") { log("applyState gagal: $t") } }
    }

    private fun reset(clocks: List<View>, sv: View?) {
        for (c in clocks) { c.scaleX = 1f; c.scaleY = 1f; c.translationY = 0f }
        sv?.let { it.alpha = 1f; it.translationY = 0f }
    }

    private fun describeView(v: View): String =
        "${v.javaClass.name} id=${idName(v)} size=${v.width}x${v.height} y=${v.y} " +
            "scale=${v.scaleX}/${v.scaleY} transY=${v.translationY} alpha=${v.alpha} vis=${v.visibility} shown=${v.isShown}"

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
