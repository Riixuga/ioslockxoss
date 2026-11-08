package com.miko.ioslock

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
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

    @Volatile private var clockRef: WeakReference<View>? = null
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
        log("v0.4.1 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"

        hook(cl, clockView, "onAttachedToWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            clockRef = WeakReference(v)
            once("clockHierarchy") { log("clock attached: " + describeView(v)) }
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

    private fun handleTouch(p: XC_MethodHook.MethodHookParam) {
        logT(
            "touchSeen",
            "gesture=${Config.gesture} clock=${clockRef?.get() != null} shown=${clockRef?.get()?.isShown}",
            discrete = true
        )
        if (!Config.gesture) { if (applied) { progress = 0f; applyState() }; return }
        val ev = p.args[0] as? MotionEvent ?: return
        val clock = clockRef?.get() ?: return
        if (!clock.isShown) { if (progress != 0f) { progress = 0f; applyState() }; return }
        val dm = clock.resources.displayMetrics
        val a = ev.actionMasked

        if (a == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            downX = ev.rawX; downY = ev.rawY; taken = false
            tracking = ev.rawY < dm.heightPixels - Config.bottomZoneDp * dm.density
            return
        }
        if (!tracking) return

        if (!taken && a == MotionEvent.ACTION_MOVE) {
            val dy = downY - ev.rawY
            val dx = ev.rawX - downX
            val slop = ViewConfiguration.get(clock.context).scaledTouchSlop
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
            val clock = clockRef?.get()
            val sv = stackRef?.get()
            if (!Config.gesture) {
                if (applied) { reset(clock, sv); applied = false }
                return
            }
            val onKeyguard = clock?.isShown == true
            if (!onKeyguard) { if (applied) { reset(clock, sv); applied = false }; return }
            applied = true
            val p = progress
            if (clock != null) {
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

    private fun reset(clock: View?, sv: View?) {
        clock?.let { it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        sv?.let { it.alpha = 1f; it.translationY = 0f }
    }

    private fun describeView(v: View): String =
        "${v.javaClass.name} id=${idName(v)} size=${v.width}x${v.height} y=${v.y} " +
            "scale=${v.scaleX}/${v.scaleY} transY=${v.translationY} alpha=${v.alpha} vis=${v.visibility}"

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
