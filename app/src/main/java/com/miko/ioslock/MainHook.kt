package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    // jam wallpaper mengecil mengikuti swipe (perintah systemui.wallpaper.touch)
    val wpExp: Boolean get() = prop("debug.ioslock.wpexp") != "0"
    val wpMin: Float get() = prop("debug.ioslock.wpmin").toFloatOrNull() ?: 0.6f
    val wpAlpha: Float get() = prop("debug.ioslock.wpalpha").toFloatOrNull() ?: 1f
    // gear jam: LARGE saat diam, SMALL setelah swipe melewati gearat
    val gearSwipe: Boolean get() = prop("debug.ioslock.gearswipe") != "0"
    val gearAt: Float get() = prop("debug.ioslock.gearat").toFloatOrNull() ?: 0.5f
    // paksa gear mutlak: LARGE atau SMALL (kosongkan = otomatis)
    val gear: String get() = prop("debug.ioslock.gear").trim().uppercase()

    val forceClockLevel: Int? get() = prop("debug.ioslock.level").toIntOrNull()
    val pushDp: Float get() = prop("debug.ioslock.pushdp").toFloatOrNull() ?: 120f
    val minScale: Float get() = prop("debug.ioslock.minscale").toFloatOrNull() ?: 0.5f
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 300f
    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f
    val pileFrac: Float get() = prop("debug.ioslock.pilefrac").toFloatOrNull() ?: 0.84f
    val pileScale: Float get() = prop("debug.ioslock.pilescale").toFloatOrNull() ?: 0.80f
    val peekDp: Float get() = prop("debug.ioslock.peekdp").toFloatOrNull() ?: 14f
    val clockId: String get() = prop("debug.ioslock.clockid")
    val dumpToken: String get() = prop("debug.ioslock.dump")
    // pelacakan class jam (log banyak), default mati
    val discover: Boolean get() = prop("debug.ioslock.discover") == "1"
}

class MainHook : IXposedHookLoadPackage {

    private val tag = "[IOSLock]"
    private val lastLog = ConcurrentHashMap<String, Long>()
    private val lastMsg = ConcurrentHashMap<String, String>()
    private val dumped = ConcurrentHashMap.newKeySet<String>()
    private val discoverRegex = Regex("(?i)(ClockControllerImpl|WallpaperTouch|KeyguardClockInteractor|ClockViewBinder)")
    private val traceCount = AtomicInteger(0)

    private val attachedClocks = CopyOnWriteArrayList<WeakReference<View>>()
    @Volatile private var activeClocks: List<View> = emptyList()
    @Volatile private var stackRef: WeakReference<View>? = null

    // 0 = SHADE, 1 = KEYGUARD, 2 = SHADE_LOCKED, -1 = belum diketahui
    @Volatile private var barState = -1

    // posisi asli tiap kartu: [dasarY, tulisY, dasarZ, tulisZ]
    private val rowState = WeakHashMap<View, FloatArray>()

    // perintah ke wallpaper
    @Volatile private var wpManager: Any? = null
    @Volatile private var wpToken: Any? = null
    @Volatile private var ownCall = false
    private var lastWpSend = 0L
    private var sentWp = false

    // kontrol gear jam
    @Volatile private var interactorRef: WeakReference<Any>? = null
    @Volatile private var gearClass: Class<*>? = null
    @Volatile private var ownGear = false
    private var lastGearSet: String? = null

    // state gestur
    @Volatile private var progress = 0f
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var baseProgress = 0f
    private var tracking = false
    private var taken = false
    private var applied = false
    private var animator: ValueAnimator? = null
    private var lastDumpToken = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.9 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val clockHelper = "com.transsion.keyguard.clock.KeyguardClockHelper"
        val clockInteractor = "com.transsion.keyguard.clock.domain.interactor.KeyguardClockInteractor"
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
            logT("setClockLevel", "arg=${p.args.getOrNull(0)}", discrete = true)
            Config.forceClockLevel?.let { lv ->
                try { p.args[0] = lv } catch (t: Throwable) { log("force level gagal: $t") }
            }
        }, after = { applyState() })
        hook(cl, clockView, "updateClockViewParams", after = { applyState() })

        // Gear jam: LARGE (jam tinggi) / SMALL (jam biasa)
        hook(cl, clockInteractor, "switchClockGear", before = { p ->
            if (ownGear) return@hook
            val inst = p.thisObject
            if (inst != null) interactorRef = WeakReference(inst)
            val cur = p.args.getOrNull(0)
            if (cur is Enum<*>) {
                gearClass = if (cur.javaClass.isEnum) cur.javaClass else cur.javaClass.superclass
            }
            logT("switchClockGear", "args=${p.args.toList()} progress=$progress state=$barState", discrete = true)
            var want = Config.gear
            if (want.isEmpty() && Config.gearSwipe && Config.gesture && barState == 1) {
                want = if (progress >= Config.gearAt) "SMALL" else "LARGE"
            }
            if (want.isNotEmpty() && cur is Enum<*>) {
                val t = enumByName(want)
                if (t != null) {
                    p.args[0] = t
                    lastGearSet = want
                }
            }
        })

        // Kapan jam bawaan disembunyikan karena tema / wallpaper
        hook(cl, clockHelper, "updateKeyguardClockVisibility", before = { p ->
            logT("clockVisibility", "args=${p.args.toList()}", discrete = true)
        })

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
            barState = s
            logT("barState", "state=$s", discrete = true)
            if (s == 1) resetDumps()
            applyState()
        })

        // Ambil View stack notifikasi dari controller-nya (field mView)
        hook(cl, stack, "updateVisibility", after = { p ->
            val v = try {
                XposedHelpers.getObjectField(p.thisObject, "mView") as? View
            } catch (t: Throwable) { null }
            if (v != null) {
                stackRef = WeakReference(v)
                once("stackView") { log("stack view: " + describeView(v)) }
            }
        })

        // Tangkap sentuhan di window shade
        hook(cl, shadeWindow, "dispatchTouchEvent", before = { p -> handleTouch(p) })

        // Perintah System UI -> wallpaper (juga untuk mengambil manager dan token)
        hook(cl, "android.app.WallpaperManager", "sendWallpaperCommand", before = { p ->
            if (ownCall) return@hook
            wpManager = p.thisObject
            wpToken = p.args.getOrNull(0)
        })

        if (Config.discover) startDiscovery(cl)
    }

    private fun resetDumps() {
        dumped.remove("tree2")
        dumped.remove("custom")
        dumped.remove("dump:clock")
    }

    // ---- gear jam ----

    private fun enumByName(name: String): Any? {
        val ec = gearClass ?: return null
        val consts = ec.enumConstants ?: return null
        for (c in consts) {
            if ((c as Enum<*>).name == name) return c
        }
        return null
    }

    private fun setGear(name: String) {
        if (lastGearSet == name) return
        val inter = interactorRef?.get() ?: return
        val target = enumByName(name) ?: return
        lastGearSet = name
        try {
            ownGear = true
            XposedHelpers.callMethod(inter, "switchClockGear", target, false, false)
        } catch (t: Throwable) {
            once("setGearFail") { log("setGear gagal: $t") }
        } finally {
            ownGear = false
        }
    }

    // ---- pelacakan opsional ----

    private fun startDiscovery(cl: ClassLoader) {
        Thread {
            try {
                val names = ArrayList<String>()
                val pathList = XposedHelpers.getObjectField(cl, "pathList")
                val elements = XposedHelpers.getObjectField(pathList, "dexElements") as Array<*>
                for (e in elements) {
                    if (e == null) continue
                    val df = XposedHelpers.getObjectField(e, "dexFile") ?: continue
                    val en = XposedHelpers.callMethod(df, "entries") as java.util.Enumeration<*>
                    while (en.hasMoreElements()) {
                        val name = en.nextElement() as String
                        if (names.size >= 14) break
                        val simple = name.substringAfterLast('.')
                        if (simple.contains('$')) continue
                        if (discoverRegex.containsMatchIn(simple)) names.add(name)
                    }
                }
                log("discovered: " + names.joinToString(" | "))
                for (n in names) trace(cl, n)
            } catch (t: Throwable) {
                log("discovery gagal: $t")
            }
        }.start()
    }

    private fun trace(cl: ClassLoader, cls: String) {
        try {
            val c = XposedHelpers.findClassIfExists(cls, cl) ?: return
            val short = c.simpleName
            var n = 0
            for (m in c.declaredMethods) {
                val name = m.name
                if (n >= 70) break
                if (Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
                if (name.startsWith("access$") || name.contains('$')) continue
                if (name.startsWith("get") || name.startsWith("is") || name.startsWith("has")) continue
                if (name == "toString" || name == "hashCode" || name == "equals") continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val key = "TR:$short.$name"
                            if (!allow(key)) return
                            log("$key(" + p.args.joinToString { brief(it) } + ")")
                        } catch (t: Throwable) { }
                    }
                })
                n++
            }
            log("trace $short: $n method(s) hooked")
        } catch (t: Throwable) {
            log("trace gagal $cls: $t")
        }
    }

    private fun allow(key: String): Boolean {
        if (traceCount.get() > 2500) return false
        val now = System.currentTimeMillis()
        val last = lastLog[key] ?: 0L
        if (now - last < 250) return false
        lastLog[key] = now
        traceCount.incrementAndGet()
        return true
    }

    private fun brief(a: Any?): String = try {
        when (a) {
            null -> "null"
            is View -> a.javaClass.simpleName
            is Bundle -> "Bundle{" + a.keySet().joinToString(",") { k -> k + "=" + a.get(k) } + "}"
            else -> a.toString().take(160)
        }
    } catch (t: Throwable) { "?" }

    // ---- perintah ke wallpaper ----

    private fun sendWp(alpha: Float, scale: Float, force: Boolean) {
        val wm = wpManager ?: return
        val tk = wpToken ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastWpSend < 16) return
        lastWpSend = now
        try {
            val b = Bundle()
            b.putFloat("alpha", alpha)
            b.putFloat("scale", scale)
            ownCall = true
            XposedHelpers.callMethod(wm, "sendWallpaperCommand", tk, "systemui.wallpaper.touch", 0, 0, 0, b)
        } catch (t: Throwable) {
            once("wpSendFail") { log("sendWp gagal: $t") }
        } finally {
            ownCall = false
        }
    }

    // ---- utilitas view ----

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

    private fun isRealClock(v: View): Boolean {
        val id = idName(v)
        if (id == "lockscreen_measure_clock_view") return false
        return id == "lockscreen_clock_view" || v.javaClass.name.endsWith("KeyguardClockView")
    }

    private fun refreshTargets(root: View) {
        val top = root.rootView
        val known = attachedClocks.mapNotNull { it.get() }.filter { it.isAttachedToWindow && it.width > 0 }
        var list: List<View> = if (known.isNotEmpty()) known else {
            val found = ArrayList<View>()
            findViews(top, 0, found) { isRealClock(it) && it.width > 0 }
            found
        }
        val extra = Config.clockId
        if (extra.isNotEmpty()) {
            val found = ArrayList<View>()
            findViews(top, 0, found) { idName(it) == extra }
            val current = list
            list = current + found.filter { it !in current }
        }
        activeClocks = list

        val sv = stackRef?.get()
        if (sv == null || !sv.isAttachedToWindow) {
            val found = ArrayList<View>()
            findViews(top, 0, found) { idName(it) == "notification_stack_scroller" }
            val first = found.firstOrNull()
            if (first != null) stackRef = WeakReference(first)
        }
    }

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

    private fun scanBig(v: View, depth: Int, sb: StringBuilder, count: IntArray) {
        if (depth > 18 || count[0] > 30) return
        val isBig = v.isShown && v.width >= 300 && v.height >= 120 &&
            !(v.width >= 1000 && v.height >= 2000)
        if (isBig) {
            sb.append("\n  ").append(v.javaClass.name.substringAfterLast('.'))
            sb.append(" id=").append(idName(v))
            sb.append(" ").append(v.width).append("x").append(v.height)
            if (v is TextView) {
                sb.append(" text='").append(v.text?.toString()?.take(12)).append("'")
            }
            sb.append(" <").append(parentNames(v))
            count[0]++
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) scanBig(v.getChildAt(i), depth + 1, sb, count)
        }
    }

    private fun parentNames(v: View): String {
        val out = StringBuilder()
        var par: ViewParent? = v.parent
        var n = 0
        while (par != null && n < 3) {
            val pv = par as? View ?: break
            out.append(" ").append(pv.javaClass.simpleName).append("#").append(idName(pv))
            par = pv.parent
            n++
        }
        return out.toString()
    }

    // ---- sentuhan ----

    private fun handleTouch(p: XC_MethodHook.MethodHookParam) {
        if (!Config.gesture) { if (applied) { progress = 0f; applyState() }; return }
        val root = p.thisObject as? View ?: return
        val ev = p.args[0] as? MotionEvent ?: return
        val dm = root.resources.displayMetrics
        val a = ev.actionMasked

        if (a == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            taken = false
            downX = ev.rawX
            downY = ev.rawY
            val token = Config.dumpToken
            if (token != lastDumpToken) {
                lastDumpToken = token
                resetDumps()
            }
            val locked = isLocked(root.context)
            if (locked) {
                refreshTargets(root)
                once("tree2") {
                    val sb = StringBuilder("tree2:")
                    dumpAll(root.rootView, 0, 5, sb, intArrayOf(0))
                    log(sb.toString())
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
                "locked=$locked state=$barState clocks=${activeClocks.size} stack=${stackRef?.get() != null} wp=${wpManager != null} gear=${interactorRef?.get() != null}",
                discrete = true
            )
            tracking = locked && onKeyguardState() &&
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
        an.duration = 280
        an.addUpdateListener { progress = it.animatedValue as Float; applyState() }
        animator = an
        an.start()
    }

    // ---- penerapan efek ----

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

            // jam bawaan System UI (tidak terlihat, tapi dipertahankan sebagai cadangan)
            for (clock in clocks) {
                val d = clock.resources.displayMetrics.density
                val s = 1f - (1f - Config.minScale) * p
                clock.pivotX = clock.width / 2f
                clock.pivotY = 0f
                clock.scaleX = s
                clock.scaleY = s
                clock.translationY = -Config.pushDp * d * p
            }

            // gear jam: LARGE saat diam, SMALL setelah melewati gearAt
            if (Config.gearSwipe && Config.gear.isEmpty()) {
                setGear(if (p >= Config.gearAt) "SMALL" else "LARGE")
            }

            // jam wallpaper: skala mengikuti progres
            if (Config.wpExp) {
                if (p > 0.001f) {
                    sentWp = true
                    sendWp(Config.wpAlpha, 1f - (1f - Config.wpMin) * p, false)
                } else if (sentWp) {
                    sentWp = false
                    sendWp(1f, 1f, true)
                }
            }

            if (sv != null) applyStack(sv, p)
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

    private fun applyStack(sv: View, p: Float) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        sv.alpha = (p * 2.5f).coerceAtMost(1f)
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
        if (n == 0) return

        val pileCenter = dm.heightPixels * Config.pileFrac
        for (i in 0 until n) {
            val card = cards[i].first
            val baseY = cards[i].second
            val baseZ = baseOfZ(card)
            val h = card.height.toFloat()
            val naturalCenter = loc[1] + baseY + h / 2f
            val targetCenter = pileCenter + i * Config.peekDp * d
            val wy = baseY + (1f - p) * (targetCenter - naturalCenter)

            val collapsed = (Config.pileScale - 0.05f * i).coerceAtLeast(0.55f)
            val s = 1f - (1f - p) * (1f - collapsed)
            val wz = baseZ + (1f - p) * (n - i) * 3f * d

            card.pivotX = card.width / 2f
            card.pivotY = h / 2f
            card.translationY = wy
            card.translationZ = wz
            card.scaleX = s
            card.scaleY = s
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz)
        }
    }

    private fun reset(clocks: List<View>, sv: View?) {
        for (c in clocks) {
            c.scaleX = 1f
            c.scaleY = 1f
            c.translationY = 0f
        }
        // kembalikan wallpaper ke keadaan normal
        if (sentWp) {
            sentWp = false
            sendWp(1f, 1f, true)
        }
        lastGearSet = null
        if (sv == null) return
        sv.alpha = 1f
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
