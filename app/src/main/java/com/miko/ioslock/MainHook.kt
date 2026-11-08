package com.miko.ioslock

import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

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

    val forceClockLevel: Int? get() = prop("debug.ioslock.level").toIntOrNull()
    val enableClockPush: Boolean get() = prop("debug.ioslock.push") == "1"
    val maxPushDp: Float get() = prop("debug.ioslock.pushdp").toFloatOrNull() ?: 120f
    val minClockScale: Float get() = prop("debug.ioslock.minscale").toFloatOrNull() ?: 0.5f
}

class MainHook : IXposedHookLoadPackage {

    private val tag = "[IOSLock]"
    private val lastLog = ConcurrentHashMap<String, Long>()
    private val lastMsg = ConcurrentHashMap<String, String>()
    private val dumped = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var panelProgress = 0f
    @Volatile private var clockRef: WeakReference<View>? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.2 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val helper = "com.transsion.keyguard.clock.KeyguardClockHelper"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val anim = "com.android.systemui.shade.NotificationCenterAnimHelper"
        val header = "com.transsion.notification.header.view.TrNotificationCenterHeader"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"

        listOf(clockView, helper, panel, anim, header, stack).forEach { dumpMethods(cl, it) }

        hook(cl, clockView, "onAttachedToWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            clockRef = WeakReference(v)
            once("clockHierarchy") { log("clock attached: " + describeView(v) + "\n" + parentChain(v)) }
        })
        hook(cl, clockView, "setClockLevel", before = { p ->
            logT("setClockLevel", "arg=${p.args.getOrNull(0)} caller=${caller()}", discrete = true)
            Config.forceClockLevel?.let { lv ->
                try { p.args[0] = lv } catch (t: Throwable) { log("force level gagal: $t") }
            }
        }, after = { p -> clockState("after setClockLevel", p.thisObject) })
        hook(cl, clockView, "getClockLevel", after = { p -> logT("getClockLevel", "ret=${p.result}", discrete = true) })
        hook(cl, clockView, "setFactor", before = { p -> logT("setFactor", "arg=${p.args.getOrNull(0)}") })
        hook(cl, clockView, "updateClockViewParams", before = { p ->
            logT("updateClockViewParams", "arg=${p.args.getOrNull(0)} caller=${caller()}", discrete = true)
        }, after = { p -> clockState("after updateClockViewParams", p.thisObject) })
        hook(cl, clockView, "getLevelHeight", after = { p -> logT("getLevelHeight", "ret=${p.result}", discrete = true) })

        hook(cl, helper, "handleClockPosition", before = { p ->
            dumpFields("KeyguardClockHelper#1", p.thisObject)
            logT("handleClockPosition", "caller=${caller()}", discrete = true)
        })
        hook(cl, "com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel", "getSmallClockTopMargin",
            after = { p -> logT("getSmallClockTopMargin", "ret=${p.result}", discrete = true) })
        hook(cl, "com.android.systemui.keyguard.ui.sections.TrClockSection", "applyConstraints",
            before = { logT("TrClockSection.applyConstraints", "caller=${caller()}", discrete = true) })

        hook(cl, panel, "positionClockAndNotifications",
            before = { p ->
                dumpFields("TrNotificationPanelViewController#1", p.thisObject)
                if (panelProgress > 0.5f) dumpFields("TrNotificationPanelViewController#2(progress>0.5)", p.thisObject)
                logT("positionClockAndNotifications", "progress=$panelProgress caller=${caller()}")
            },
            after = { if (Config.enableClockPush) applyClockPush() })
        hook(cl, panel, "updateExpandedHeight", before = { p -> logT("updateExpandedHeight", "h=${p.args.getOrNull(0)}") })
        hook(cl, panel, "expandToNotifications", before = { logT("expandToNotifications", "caller=${caller()}", discrete = true) })
        hook(cl, panel, "flingToLockShade", before = { p -> logT("flingToLockShade", "args=${p.args.toList()}", discrete = true) })

        hook(cl, anim, "changePanelCollapseAnim", before = { p ->
            val f = (p.args.getOrNull(0) as? Float) ?: 0f
            panelProgress = f
            logT("changePanelCollapseAnim", "f=$f (semua arg=${p.args.toList()})")
            if (Config.enableClockPush) applyClockPush()
        })
        hook(cl, anim, "startPanelAnim", before = { p -> logT("startPanelAnim", "args=${p.args.toList()}", discrete = true) })
        hook(cl, anim, "isPanelExpanded", after = { p -> logT("isPanelExpanded", "ret=${p.result}", discrete = true) })

        hook(cl, header, "updateClockTranslationY", before = { p -> logT("header.updateClockTranslationY", "y=${p.args.getOrNull(0)}") })

        hook(cl, stack, "updateVisibility", before = { p ->
            logT("stack.updateVisibility", "visible=${p.args.getOrNull(0)} caller=${caller()}", discrete = true)
        })
    }

    private fun applyClockPush() {
        val clock = clockRef?.get() ?: return
        val p = panelProgress.coerceIn(0f, 1f)
        val d = clock.resources.displayMetrics.density
        val s = 1f - (1f - Config.minClockScale) * p
        clock.post {
            clock.pivotX = clock.width / 2f
            clock.pivotY = 0f
            clock.scaleX = s
            clock.scaleY = s
            clock.translationY = -Config.maxPushDp * d * p
        }
    }

    private fun clockState(label: String, obj: Any?) {
        val v = (obj as? View) ?: clockRef?.get() ?: return
        logT("clockState", "$label -> ${describeView(v)}", discrete = true)
    }

    private fun describeView(v: View): String =
        "${v.javaClass.name} id=${idName(v)} size=${v.width}x${v.height} y=${v.y} " +
            "scale=${v.scaleX}/${v.scaleY} transY=${v.translationY} alpha=${v.alpha} vis=${v.visibility}"

    private fun parentChain(v: View): String {
        val sb = StringBuilder()
        var c: Any? = v.parent
        var i = 0
        while (c is View && i < 8) {
            sb.append("  ^ ").append(describeView(c)).append('\n'); c = c.parent; i++
        }
        return sb.toString()
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

    private fun dumpMethods(cl: ClassLoader, cls: String) {
        try {
            val c = XposedHelpers.findClassIfExists(cls, cl) ?: return
            val sb = StringBuilder("methods of ${c.simpleName}:")
            c.declaredMethods.sortedBy { it.name }.forEach { m ->
                sb.append("\n  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
            }
            log(sb.toString())
        } catch (t: Throwable) { log("dumpMethods fail $cls: $t") }
    }

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
                            x is View -> describeView(x)
                            f.type.isPrimitive || x is String || x is Number -> x.toString()
                            else -> x.javaClass.name
                        }
                    } catch (t: Throwable) { "?" }
                    sb.append("\n  ${c.simpleName}.${f.name}: ${f.type.name} = $value")
                }
                c = c.superclass
            }
            log(sb.toString())
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
