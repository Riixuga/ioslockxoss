package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    // jam wallpaper mengecil mengikuti tahap 2 (perintah systemui.wallpaper.touch)
    val wpExp: Boolean get() = prop("debug.ioslock.wpexp") != "0"
    val wpMin: Float get() = prop("debug.ioslock.wpmin").toFloatOrNull() ?: 0.6f
    val wpAlpha: Float get() = prop("debug.ioslock.wpalpha").toFloatOrNull() ?: 1f

    // tahap 2: swipe lanjutan setelah stack muncul (jam mengecil)
    val stage2: Boolean get() = prop("debug.ioslock.stage2") != "0"
    val stage2Dp: Float get() = prop("debug.ioslock.stage2dp").toFloatOrNull() ?: 120f
    // jarak swipe tahap 1 (dp)
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 300f
    // posisi atas stack saat diam (0..1 dari tinggi layar); 0 = ikut posisi sistem
    val restFrac: Float get() = prop("debug.ioslock.restfrac").toFloatOrNull() ?: 0f

    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f
    val pileFrac: Float get() = prop("debug.ioslock.pilefrac").toFloatOrNull() ?: 0.84f
    val pileScale: Float get() = prop("debug.ioslock.pilescale").toFloatOrNull() ?: 0.80f
    val peekDp: Float get() = prop("debug.ioslock.peekdp").toFloatOrNull() ?: 14f

    // gear jam (hasil uji: tidak memengaruhi jam wallpaper, dan membuat stack teracak)
    val gearSwipe: Boolean get() = prop("debug.ioslock.gearswipe") == "1"
    val gear: String get() = prop("debug.ioslock.gear").trim().uppercase()
    val gearAt: Float get() = prop("debug.ioslock.gearat").toFloatOrNull() ?: 1.0f

    // alat penemuan
    val spy: Boolean get() = prop("debug.ioslock.spy") == "1"
    val wpSend: String get() = prop("debug.ioslock.wpsend")
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

    // posisi asli tiap kartu: [dasarY, tulisY, dasarZ, tulisZ]
    private val rowState = WeakHashMap<View, FloatArray>()

    // perintah ke wallpaper
    @Volatile private var wpManager: Any? = null
    @Volatile private var wpToken: Any? = null
    @Volatile private var ownCall = false
    private var lastWpSend = 0L
    private var sentWp = false
    private var lastCustomSend = ""

    // kontrol gear jam (opsional, default mati)
    @Volatile private var interactorRef: WeakReference<Any>? = null
    @Volatile private var gearClass: Class<*>? = null
    @Volatile private var ownGear = false
    private var lastGearSet: String? = null

    // state gestur. progress: 0..1 = tahap 1 (stack muncul), 1..2 = tahap 2 (jam mengecil)
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
        log("v0.10 loaded into SystemUI")

        val clockView = "com.transsion.keyguard.clock.ui.KeyguardClockView"
        val clockInteractor = "com.transsion.keyguard.clock.domain.interactor.KeyguardClockInteractor"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"
        val stateCtl = "com.android.systemui.statusbar.StatusBarStateControllerImpl"

        hook(cl, clockView, "onAttachedToWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            if (!isRealClock(v)) return@hook
            attachedClocks.add(WeakReference(v))
        })
        hook(cl, clockView, "onDetachedFromWindow", after = { p ->
            val v = p.thisObject as? View ?: return@hook
            attachedClocks.removeAll { it.get() == null || it.get() === v }
        })

        // Gear jam: dicatat dengan jejak lengkap (untuk mencari pemicunya)
        hook(cl, clockInteractor, "switchClockGear", before = { p ->
            if (ownGear) return@hook
            val inst = p.thisObject
            if (inst != null) interactorRef = WeakReference(inst)
            val cur = p.args.getOrNull(0)
            if (cur is Enum<*>) {
                gearClass = if (cur.javaClass.isEnum) cur.javaClass else cur.javaClass.superclass
            }
            logT("switchClockGear", "args=${p.args.toList()} state=$barState stack=${callerFull()}", discrete = true)
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

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
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

        // Perintah System UI -> wallpaper (juga untuk mengambil manager dan token)
        hook(cl, "android.app.WallpaperManager", "sendWallpaperCommand", before = { p ->
            if (ownCall) return@hook
            wpManager = p.thisObject
            wpToken = p.args.getOrNull(0)
            val action = p.args.getOrNull(1)?.toString() ?: "?"
            if (Config.spy || action != "systemui.wallpaper.touch") {
                val rest = p.args.drop(1).joinToString(" ") { brief(it) }
                logT("WPCMD:$action", rest, discrete = true)
            }
        })

        hookSpy(cl)
    }

    // ---- mode spy: catat semua saluran keluar System UI ----

    private fun spyIntent(kind: String, a: Any?) {
        if (!Config.spy) return
        val intent = a as? Intent ?: return
        logT(
            "$kind:${intent.action}:${intent.component}",
            "act=${intent.action} cmp=${intent.component} pkg=${intent.getPackage()} extras=${intent.extras?.keySet()}",
            discrete = true
        )
    }

    private fun hookSpy(cl: ClassLoader) {
        for (cls in listOf("android.provider.Settings\$System", "android.provider.Settings\$Secure", "android.provider.Settings\$Global")) {
            val which = cls.substringAfterLast('$')
            hook(cl, cls, "putStringForUser", before = { p ->
                if (!Config.spy) return@hook
                val name = p.args.getOrNull(1)?.toString() ?: return@hook
                logT("SET:$which:$name", "$name=${p.args.getOrNull(2)}", discrete = true)
            })
        }
        for (m in listOf("sendBroadcast", "sendBroadcastAsUser")) {
            hook(cl, "android.app.ContextImpl", m, before = { p -> spyIntent("BCAST", p.args.getOrNull(0)) })
        }
        for (m in listOf("startService", "startServiceAsUser", "bindService", "bindServiceAsUser")) {
            hook(cl, "android.app.ContextImpl", m, before = { p -> spyIntent("SVC", p.args.getOrNull(0)) })
        }
        for (m in listOf("call", "insert", "update", "notifyChange")) {
            hook(cl, "android.content.ContentResolver", m, before = { p ->
                if (!Config.spy) return@hook
                val first = p.args.getOrNull(0)?.toString() ?: "?"
                logT("CR:$m:$first", p.args.take(3).joinToString(" | ") { brief(it) }, discrete = true)
            })
        }
    }

    private fun brief(a: Any?): String = try {
        when (a) {
            null -> "null"
            is View -> a.javaClass.simpleName
            is Bundle -> "Bundle{" + a.keySet().joinToString(",") { k -> k + "=" + a.get(k) } + "}"
            else -> a.toString().take(160)
        }
    } catch (t: Throwable) { "?" }

    // ---- kirim perintah uji ke wallpaper: "aksi,x,y,z,kunci=nilai,..." ----

    private fun maybeSendCustom() {
        val s = Config.wpSend
        if (s == lastCustomSend) return
        lastCustomSend = s
        if (s.isEmpty()) return
        val wm = wpManager
        val tk = wpToken
        if (wm == null || tk == null) { log("wpsend: belum ada manager/token (tunggu layar menyala)"); return }
        val parts = s.split(",")
        if (parts.size < 4) { log("wpsend: format salah: $s"); return }
        val b = Bundle()
        for (kv in parts.drop(4)) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i)
            val v = kv.substring(i + 1)
            when {
                v == "true" || v == "false" -> b.putBoolean(k, v == "true")
                v.toIntOrNull() != null -> b.putInt(k, v.toInt())
                v.toFloatOrNull() != null -> b.putFloat(k, v.toFloat())
                else -> b.putString(k, v)
            }
        }
        try {
            ownCall = true
            XposedHelpers.callMethod(
                wm, "sendWallpaperCommand", tk, parts[0],
                parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0, b
            )
            log("wpsend terkirim: $s")
        } catch (t: Throwable) {
            log("wpsend gagal: $t")
        } finally {
            ownCall = false
        }
    }

    // ---- gear jam (opsional) ----

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
        activeClocks = if (known.isNotEmpty()) known else {
            val found = ArrayList<View>()
            findViews(top, 0, found) { isRealClock(it) && it.width > 0 }
            found
        }
        val sv = stackRef?.get()
        if (sv == null || !sv.isAttachedToWindow) {
            val found = ArrayList<View>()
            findViews(top, 0, found) { idName(it) == "notification_stack_scroller" }
            val first = found.firstOrNull()
            if (first != null) stackRef = WeakReference(first)
        }
    }

    // ---- konversi progres <-> jarak (tahap 1 dan tahap 2 punya panjang berbeda) ----

    private fun maxProgress(): Float = if (Config.stage2) 2f else 1f

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
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) maybeSendCustom()

        if (!Config.gesture) { if (applied) { progress = 0f; applyState() }; return }
        val dm = root.resources.displayMetrics

        if (a == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            taken = false
            downX = ev.rawX
            downY = ev.rawY
            val locked = isLocked(root.context)
            if (locked) {
                refreshTargets(root)
            } else if (applied) {
                progress = 0f
                applyState()
            }
            logT(
                "touchSeen",
                "locked=$locked state=$barState stack=${stackRef?.get() != null} wp=${wpManager != null}",
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
            val up = dy > slop && progress < maxProgress()
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
                    progress = distToProgress(d.coerceAtLeast(0f)).coerceIn(0f, maxProgress())
                    applyState()
                    logT("progress", "p=$progress")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    taken = false
                    // lepas di tahap 1: menempel ke posisi diam; di tahap 2: tetap di tempat
                    val target = when {
                        progress < 0.35f -> 0f
                        progress < 1.25f -> 1f
                        else -> progress
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
            val clocks = activeClocks
            val sv = stackRef?.get()
            val ctx = clocks.firstOrNull()?.context ?: sv?.context
            val locked = ctx != null && isLocked(ctx)
            val onKeyguard = locked && onKeyguardState()
            if (!Config.gesture || !onKeyguard) {
                if (applied) { reset(sv); applied = false }
                if (!locked) progress = 0f
                return
            }
            applied = true
            val p = progress
            val p1 = min(p, 1f)
            val q = (p - 1f).coerceIn(0f, 1f)

            // gear jam (opsional)
            if (Config.gearSwipe && Config.gear.isEmpty()) {
                setGear(if (p >= Config.gearAt) "SMALL" else "LARGE")
            }

            // jam wallpaper: mengecil hanya di tahap 2
            if (Config.wpExp) {
                if (q > 0.001f) {
                    sentWp = true
                    sendWp(Config.wpAlpha, 1f - (1f - Config.wpMin) * q, false)
                } else if (sentWp) {
                    sentWp = false
                    sendWp(1f, 1f, true)
                }
            }

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

    // p1: 0..1 (tumpukan di bawah -> posisi diam), q: 0..1 (posisi diam -> posisi sistem)
    private fun applyStack(sv: View, p1: Float, q: Float) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        sv.alpha = (p1 * 2.5f).coerceAtMost(1f)
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

        // posisi diam (garis merah): bagian atas stack di restFrac * tinggi layar
        val naturalTop = loc[1] + cards[0].second
        val redTop = if (Config.restFrac > 0f) dm.heightPixels * Config.restFrac else 0f
        val restDelta = if (redTop > naturalTop) redTop - naturalTop else 0f
        val shift = restDelta * (1f - q)

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

    private fun reset(sv: View?) {
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

    private fun callerFull(): String =
        Throwable().stackTrace.drop(3)
            .filter { it.className.contains("systemui") || it.className.contains("transsion") }
            .take(10).joinToString(" < ") { "${it.className}.${it.methodName}" }

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
