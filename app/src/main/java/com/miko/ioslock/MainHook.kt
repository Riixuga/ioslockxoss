package com.miko.ioslock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
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

    // tahap 2: jam wallpaper (depth) MENGECIL tapi tetap terlihat (foto 4), bukan di-fade sampai hilang.
    // wpalpha = alpha jam depth di akhir tahap 2 (1 = tetap terlihat, 0 = hilang). wpmin = skala akhir (1 = tidak mengecil).
    // PENTING: nilainya RELATIF terhadap alpha/scale diam yang dipakai System UI sendiri.
    // DEFAULT MATI: jam depth digambar wallpaper (CustomPictorial.setClockShape alpha/scale); perintah
    // tambahan dari modul hanya membuat Jam berkedip/goyah, jadi biarkan sistem yang urus.
    val wpExp: Boolean get() = prop("debug.ioslock.wpexp") == "1"
    val wpMin: Float get() = prop("debug.ioslock.wpmin").toFloatOrNull() ?: 0.5f
    val wpAlpha: Float get() = prop("debug.ioslock.wpalpha").toFloatOrNull() ?: 1f
    // alternatif: kecilkan View jam milik System UI (dipakai kalau jam tidak digambar wallpaper)
    val clockShrink: Boolean get() = prop("debug.ioslock.clockshrink") == "1"
    // bubble "N Notifications": blur live dari lapisan wallpaper yang sedang tampil (bukan potongan statis)
    val liveBlur: Boolean get() = prop("debug.ioslock.liveblur") != "0"

    // tahap 2: swipe lanjutan setelah stack muncul
    val stage2: Boolean get() = prop("debug.ioslock.stage2") != "0"
    val stage2Dp: Float get() = prop("debug.ioslock.stage2dp").toFloatOrNull() ?: 120f
    // jarak swipe tahap 1 (dp)
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 300f
    // posisi atas stack saat diam tahap 1 (0..1 tinggi layar); 0 = ikut posisi sistem.
    // 0.55 = garis merah "x" (foto 1): puncak notifikasi berhenti di bawah jam besar (foto 3)
    val restFrac: Float get() = prop("debug.ioslock.restfrac").toFloatOrNull() ?: 0.55f
    // posisi atas stack di akhir tahap 2 (foto 4), 0..1 tinggi layar; 0 = ikut posisi sistem
    val endFrac: Float get() = prop("debug.ioslock.endfrac").toFloatOrNull() ?: 0.33f

    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f
    val pileFrac: Float get() = prop("debug.ioslock.pilefrac").toFloatOrNull() ?: 0.84f
    val pileScale: Float get() = prop("debug.ioslock.pilescale").toFloatOrNull() ?: 0.80f
    val peekDp: Float get() = prop("debug.ioslock.peekdp").toFloatOrNull() ?: 14f

    // teks "Notification Center" dan "N Notification(s)"
    val labels: Boolean get() = prop("debug.ioslock.labels") != "0"
    val countFrac: Float get() = prop("debug.ioslock.countfrac").toFloatOrNull() ?: 0.846f
    // bubble frosted (blur wallpaper) di belakang "N Notifications" saja, bukan "Notification Center"
    val bubble: Boolean get() = prop("debug.ioslock.bubble") != "0"
    // penjaga: selama di lockscreen SystemUI tidak boleh menurunkan alpha/scale jam depth.
    // DEFAULT MATI: di wallpaper depth nilainya 1.0/0.95 dan dimiliki sistem; menahannya bikin jam melompat.
    val holdDepth: Boolean get() = prop("debug.ioslock.holddepth") == "1"

    // gear jam sistem: tahap 2 memaksa SMALL (default MATI; jam depth mengecil lewat wallpaper, tidak hilang)
    val gearSwipe: Boolean get() = prop("debug.ioslock.gearswipe") == "1"
    // Paksa LARGE di lockscreen walau ada notifikasi. DEFAULT MATI: memaksa gear di setiap
    // switchClockGear menimpa gear depth milik sistem, sehingga jam depth (yang digambar wallpaper)
    // hilang dan yang tampil hanya jam biasa. Nyalakan hanya kalau memang ingin itu:
    //   setprop debug.ioslock.keepbig 1
    val keepBig: Boolean get() = prop("debug.ioslock.keepbig") == "1"
    val gear: String get() = prop("debug.ioslock.gear").trim().uppercase()

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

    // gear jam
    @Volatile private var interactorRef: WeakReference<Any>? = null
    @Volatile private var gearClass: Class<*>? = null
    @Volatile private var ownGear = false
    @Volatile private var sysGear: String? = null   // gear terakhir yang diminta sistem
    @Volatile private var gearSmall = false         // tahap 2 aktif -> paksa SMALL
    private var lastGearSet: String? = null

    // state gestur. progress: 0..1 = tahap 1 (stack muncul), 1..2 = tahap 2 (jam mengecil, stack naik)
    @Volatile private var progress = 0f
    // jumlah kartu notifikasi yang terlihat; 0 -> swipe ke atas tidak boleh mengubah apa pun
    @Volatile private var cardCount = 0
    private var lastP1 = 0f
    private var lastQ = 0f
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var baseProgress = 0f
    private var tracking = false
    private var taken = false
    private var applied = false
    private var animator: ValueAnimator? = null

    // label
    private var centerLabel: TextView? = null
    private var countLabel: TextView? = null

    // bubble frosted di belakang "N Notifications"
    private var bubbleView: CountBubble? = null
    private var wallBmp: Bitmap? = null
    private var wallTried = false
    private var bubbleBackdrop: Bitmap? = null
    private var bubbleKey = ""

    // swipe ke-3 (bouncer kunci): notifikasi & label memudar ke tengah, jam tidak diintervensi
    @Volatile private var bouncerRef: WeakReference<View>? = null
    @Volatile private var bouncerFade = 0f
    private var lastBouncerScan = 0L

    // nilai terakhir yang dikirim ke wallpaper: hindari spam perintah yang membuat jam berkedip
    private var lastWpSentA = -1f
    private var lastWpSentS = -1f

    // alpha/scale DIAM yang dipakai System UI sendiri untuk jam depth (direkam, bukan ditebak).
    // Jam depth digambar oleh wallpaper; skalanya bisa > 1. Semua target kita relatif ke angka ini.
    @Volatile private var restAlpha = 0f
    @Volatile private var restScale = 0f

    // view jam yang skala/alpha-nya kita ubah (hanya kalau clockshrink=1)
    private var clockTouched = false

    // true = wallpaper lockscreen mengirim "depthClockInfo" di perintahnya, artinya jam
    // digambar oleh wallpaper (CustomPictorial.setClockShape alpha/scale). Di mode ini modul
    // tidak boleh mengubah gear/shape jam sama sekali.
    @Volatile private var depthSeen = false

    // sumber blur live: lapisan wallpaper yang tampil di keyguard (untuk bubble "N Notifications")
    @Volatile private var liveRef: WeakReference<View>? = null
    private var liveScanAt = 0L

    // konteks terakhir: dipakai penjaga wallpaper untuk memastikan keyguard masih terkunci
    @Volatile private var appCtx: Context? = null

    // penegakan tiap frame + reset saat layar mati
    private var preDrawHost: WeakReference<View>? = null
    private var receiverRegistered = false

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (applied) {
            val sv = stackRef?.get()
            if (sv != null) {
                try {
                    val fade = updateBouncer(sv.rootView ?: sv)
                    applyStack(sv, lastP1, lastQ, fade)
                } catch (t: Throwable) { once("preDrawFail") { log("preDraw gagal: $t") } }
            }
        }
        true
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.15 loaded into SystemUI")

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

        // Gear jam. Dicatat dengan jejak lengkap (untuk mencari pemicu "jam mengecil" saat ada notifikasi ke-2)
        hook(cl, clockInteractor, "switchClockGear", before = { p ->
            if (ownGear) return@hook
            val inst = p.thisObject
            if (inst != null) interactorRef = WeakReference(inst)
            val cur = p.args.getOrNull(0)
            if (cur is Enum<*>) {
                gearClass = if (cur.javaClass.isEnum) cur.javaClass else cur.javaClass.superclass
                sysGear = cur.name
                once("gears") { log("gear tersedia: ${gearClass?.enumConstants?.joinToString { (it as Enum<*>).name }}") }
            }
            val kids = (stackRef?.get() as? ViewGroup)?.childCount ?: -1
            logT("switchClockGear", "args=${p.args.toList()} state=$barState kids=$kids stack=${callerFull()}", discrete = true)
            var want = Config.gear
            // Selama di lockscreen (termasuk saat gestur tweak aktif) jam dipaksa LARGE:
            // notifikasi masuk tidak boleh mengubah jam depth jadi kecil (foto 2N).
            // saat bouncer (swipe ke-3) tampil jangan ikut campur gear: penyebab jam berkedip
            val onLock = Config.gesture && bouncerFade < 0.01f && (barState == 1 || barState == -1 || applied)
            if (want.isEmpty() && onLock) {
                want = when {
                    Config.gearSwipe && gearSmall -> "SMALL"
                    Config.keepBig -> "LARGE"
                    else -> ""
                }
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
            // deteksi wallpaper depth: bundlenya membawa ClockInfo untuk jam yang digambar wallpaper
            val cmdBundle = p.args.getOrNull(5) as? Bundle
            if (cmdBundle != null && cmdBundle.containsKey("depthClockInfo") && !depthSeen) {
                depthSeen = true
                log("wallpaper depth terdeteksi (depthClockInfo): jam digambar wallpaper, gear/shape tidak disentuh")
            }
            // rekam nilai diam jam depth dari System UI sendiri (skalanya bisa 1.6, bukan 1.0)
            learnRest(p)
            // Penjaga: selama di lockscreen, alpha/scale jam depth tidak boleh diturunkan SystemUI
            val held = holdDepth(p)
            if (Config.spy || action != "systemui.wallpaper.touch") {
                val rest = p.args.drop(1).joinToString(" ") { brief(it) }
                logT("WPCMD:$action", if (held) "$rest (depth ditahan)" else rest, discrete = true)
            }
            if (Config.spy) {
                once("wpKeys:$action") {
                    log("wpKeys $action -> ${(p.args.getOrNull(5) as? Bundle)?.keySet()}")
                }
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

    // ---- perintah ke wallpaper ----

    // alpha/scale jam depth yang seharusnya dipakai sesuai progres gestur (faktor relatif, 1 = diam)
    private fun wpTargetAlpha(): Float = 1f - (1f - Config.wpAlpha) * (progress - 1f).coerceIn(0f, 1f)

    private fun wpTargetScale(): Float = 1f - (1f - Config.wpMin) * (progress - 1f).coerceIn(0f, 1f)

    private fun bundleFloat(b: Bundle, key: String): Float? = try {
        when (val v = b.get(key)) {
            is Float -> v
            is Int -> v.toFloat()
            is Double -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
    } catch (t: Throwable) { null }

    // Rekam alpha/scale DIAM jam depth dari System UI (nilai terbesar yang pernah diminta saat diam).
    // Tanpa angka ini kita tidak boleh menebak 1.0: jam depth bisa punya skala 1.6, dan memaksa 1.0
    // membuat jam besar berubah jadi jam biasa (bug "depth clock hilang").
    private fun learnRest(p: XC_MethodHook.MethodHookParam) {
        if (!onKeyguardState()) return
        appCtx?.let { if (!isLocked(it)) { restAlpha = 0f; restScale = 0f; return } }
        val b = p.args.getOrNull(5) as? Bundle ?: return
        for (k in b.keySet()) {
            val lk = k.lowercase()
            val v = bundleFloat(b, k) ?: continue
            if (v <= 0.01f || v > 4f) continue
            if (lk.contains("alpha")) { if (v > restAlpha) restAlpha = v }
            else if (lk.contains("scale")) { if (v > restScale) restScale = v }
        }
        if (Config.spy) logT("restDepth", "alpha=$restAlpha scale=$restScale", discrete = true)
    }

    // Penjaga perintah System UI -> wallpaper: kalau SystemUI meminta alpha/scale lebih kecil dari
    // nilai diam yang kita rekam (menghilangkan/mengecilkan jam depth), tulis ulang dengan nilai kita.
    // Kalau nilai diam belum diketahui, jangan sentuh sama sekali (biar jam depth apa adanya).
    private fun holdDepth(p: XC_MethodHook.MethodHookParam): Boolean {
        if (!Config.gesture || !Config.holdDepth) return false
        if (!applied || bouncerFade > 0.01f || !onKeyguardState()) return false
        appCtx?.let { if (!isLocked(it)) return false }
        val ra = restAlpha
        val rs = restScale
        if (ra <= 0f && rs <= 0f) return false
        val b = p.args.getOrNull(5) as? Bundle ?: return false
        val a = if (ra > 0f) ra * wpTargetAlpha() else -1f
        val s = if (rs > 0f) rs * wpTargetScale() else -1f
        var touched = false
        for (k in b.keySet()) {
            val lk = k.lowercase()
            val cur = bundleFloat(b, k) ?: continue
            if (lk.contains("alpha") && a >= 0f) {
                if (abs(cur - a) > 0.004f) { b.putFloat(k, a); touched = true }
            } else if (lk.contains("scale") && s >= 0f) {
                if (abs(cur - s) > 0.004f) { b.putFloat(k, s); touched = true }
            }
        }
        return touched
    }

    private fun sendWp(alpha: Float, scale: Float, force: Boolean) {
        val wm = wpManager ?: return
        val tk = wpToken ?: return
        // kirim hanya saat nilainya berubah: perintah per frame membuat jam berkedip
        if (!force && abs(alpha - lastWpSentA) < 0.004f && abs(scale - lastWpSentS) < 0.004f) return
        val now = System.currentTimeMillis()
        if (!force && now - lastWpSend < 33) return
        lastWpSend = now
        lastWpSentA = alpha
        lastWpSentS = scale
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

    // ---- jam: kecilkan/pudarkan View jam System UI saat tahap 2 ----

    // Hanya dipakai kalau clockshrink=1 (jam yang digambar System UI sendiri). Default mati,
    // karena jam depth dikelola wallpaper dan mengubah View-nya bisa memicu jam berkedip.
    private fun applyClock(q: Float) {
        val on = Config.clockShrink
        val s = if (on) 1f - (1f - Config.wpMin) * q.coerceIn(0f, 1f) else 1f
        val a = if (on) 1f - (1f - Config.wpAlpha) * q.coerceIn(0f, 1f) else 1f
        val touched = s < 0.999f || a < 0.999f
        if (!touched && !clockTouched) return
        clockTouched = touched
        val targets = ArrayList<View>(activeClocks)
        for (r in attachedClocks) r.get()?.let { if (targets.none { t -> t === it }) targets.add(it) }
        for (v in targets) {
            if (abs(v.scaleX - s) > 0.001f) { v.scaleX = s; v.scaleY = s }
            if (abs(v.alpha - a) > 0.001f) v.alpha = a
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

    // ---- reset saat layar mati: saat layar menyala berikutnya stack sudah dalam keadaan tertutup ----

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        try {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    animator?.cancel()
                    taken = false
                    tracking = false
                    progress = 0f
                    applyState()
                }
            }, IntentFilter(Intent.ACTION_SCREEN_OFF))
            log("receiver SCREEN_OFF terdaftar")
        } catch (t: Throwable) {
            receiverRegistered = false
            log("receiver gagal: $t")
        }
    }

    // pasang listener pre-draw: nilai kita ditegakkan tepat sebelum tiap frame digambar,
    // sehingga animasi bawaan sistem (muncul saat layar menyala, dsb) tidak bisa menimpa
    private fun ensurePreDraw(sv: View) {
        if (preDrawHost?.get() === sv) return
        try {
            preDrawHost?.get()?.viewTreeObserver?.removeOnPreDrawListener(preDraw)
        } catch (t: Throwable) { }
        try {
            sv.viewTreeObserver.addOnPreDrawListener(preDraw)
            preDrawHost = WeakReference(sv)
        } catch (t: Throwable) {
            once("preDrawAdd") { log("addOnPreDraw gagal: $t") }
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
        ensureReceiver(root.context)

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
                cardCount = stackRef?.get()?.let { countCards(it) } ?: 0
            } else if (applied) {
                progress = 0f
                applyState()
            }
            logT(
                "touchSeen",
                "locked=$locked state=$barState stack=${stackRef?.get() != null} wp=${wpManager != null}",
                discrete = true
            )
            // kalau bouncer kunci (swipe ke-3) sedang tampil, jangan ambil alih sentuhan
            val bouncerUp = locked && bouncerShowing(root)
            if (bouncerUp) bouncerRef = null
            tracking = locked && !bouncerUp && onKeyguardState() &&
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
                    if (cardCount == 0) {
                        // tanpa notifikasi (foto 2): jam & layar harus tetap persis seperti foto 1
                        progress = 0f
                        applyState()
                    } else {
                        val moved = (startY - ev.rawY) / dm.density
                        val d = progressToDist(baseProgress) + moved
                        progress = distToProgress(d.coerceAtLeast(0f)).coerceIn(0f, maxProgress())
                        applyState()
                        logT("progress", "p=$progress")
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    taken = false
                    // lepas: menempel ke salah satu dari 3 posisi (tertutup / foto 1 / foto 3)
                    val target = when {
                        progress < 0.35f -> 0f
                        progress < 1.5f -> 1f
                        else -> maxProgress()
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
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                progress = target
                applyState()
            }
        })
        animator = an
        an.start()
    }

    // ---- penerapan efek ----

    private fun applyState() {
        try {
            val clocks = activeClocks
            val sv = stackRef?.get()
            val ctx = clocks.firstOrNull()?.context ?: sv?.context
            if (ctx != null) { appCtx = ctx; ensureReceiver(ctx) }
            val locked = ctx != null && isLocked(ctx)
            val onKeyguard = locked && onKeyguardState()
            if (!Config.gesture || !onKeyguard) {
                if (applied) { reset(sv); applied = false }
                if (!locked) progress = 0f
                return
            }
            applied = true
            // swipe ke-3 / bouncer kunci: notifikasi dan label ikut memudar ke tengah
            val fade = if (sv != null) updateBouncer(sv.rootView ?: sv) else 0f
            val n = sv?.let { countCards(it) } ?: 0
            cardCount = n
            if (n == 0) {
                // Tidak ada notifikasi (foto 1 & foto 2): tidak ada yang boleh berubah.
                // Jam depth tetap besar; swipe ke atas tidak boleh memudarkannya.
                if (progress != 0f) {
                    animator?.cancel()
                    progress = 0f
                }
                gearSmall = false
                lastP1 = 0f
                lastQ = 0f
                applyClock(0f)
                if (sentWp) {
                    sentWp = false
                    if (restScale > 0f) sendWp(restAlpha, restScale, true)
                }
                if (sv != null) applyStack(sv, 0f, 0f, fade) else hideLabels()
                return
            }
            val p = progress
            val p1 = min(p, 1f)
            val q = (p - 1f).coerceIn(0f, 1f)
            lastP1 = p1
            lastQ = q

            // gear jam sistem: tahap 2 -> SMALL; selain itu ikut sistem (atau LARGE jika keepbig)
            if (Config.gearSwipe && Config.gear.isEmpty() && fade < 0.01f) {
                if (q > 0.55f) gearSmall = true else if (q < 0.45f) gearSmall = false
                val want = if (gearSmall) "SMALL" else if (Config.keepBig) "LARGE" else sysGear
                if (want != null && want != (lastGearSet ?: sysGear)) setGear(want)
            }

            // jam: di tahap 2 MENGECIL (foto 4) tapi tetap terlihat, tidak boleh di-fade sampai hilang.
            // Skala/alpha dikirim RELATIF terhadap nilai diam wallpaper (jam depth skalanya besar),
            // jadi jam depth tidak berubah jadi jam kecil. Saat bouncer tampil berhenti (jam berkedip).
            if (Config.wpExp && fade < 0.01f && restScale > 0f) {
                if (q > 0.001f) {
                    sentWp = true
                    sendWp(restAlpha * wpTargetAlpha(), restScale * wpTargetScale(), q >= 0.999f)
                } else if (sentWp) {
                    sentWp = false
                    sendWp(restAlpha, restScale, true)
                }
            } else if (sentWp) {
                sentWp = false
                if (restScale > 0f) sendWp(restAlpha, restScale, true)
            }
            // alternatif untuk jam yang digambar System UI (bukan wallpaper): kecilkan view-nya
            if (fade < 0.01f) applyClock(q)

            if (sv != null) {
                ensurePreDraw(sv)
                applyStack(sv, p1, q, fade)
            }
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

    // p1: 0..1 (tumpukan di bawah -> posisi diam), q: 0..1 (posisi diam -> posisi akhir tahap 2)
    // fade: 0..1 = bouncer kunci (swipe ke-3): kartu memudar dan tertarik ke tengah layar
    private fun applyStack(sv: View, p1: Float, q: Float, fade: Float = 0f) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        sv.alpha = (p1 * 2.5f).coerceAtMost(1f) * (1f - fade)
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
        if (Config.spy) {
            logT("stackKids", "n=$n all=${g.childCount} ${cards.joinToString { it.first.javaClass.simpleName + "#" + idName(it.first) }}", discrete = true)
        }
        if (n == 0) { hideLabels(); return }

        // ACUAN: dasar kartu PALING BAWAH tepat di garis teks "N Notifications" (tengah bawah).
        // Stack tumbuh ke atas dari garis itu; kalau kepanjangan, puncaknya berhenti di restFrac.
        val anchorBottom = dm.heightPixels * Config.countFrac
        val naturalTop = loc[1] + cards[0].second
        val lowest = cards[n - 1]
        val naturalBottom = loc[1] + lowest.second + lowest.first.height
        var shift1 = anchorBottom - naturalBottom
        val minTop = if (Config.restFrac > 0f) dm.heightPixels * Config.restFrac else Float.NEGATIVE_INFINITY
        if (naturalTop + shift1 < minTop) shift1 = minTop - naturalTop
        // tahap 2: puncak stack naik ke endFrac (foto 4)
        val shift2 = if (Config.endFrac > 0f) dm.heightPixels * Config.endFrac - naturalTop else shift1
        val shift = shift1 + (shift2 - shift1) * q

        var topVisual = naturalTop + shift
        var leftVisual = loc[0].toFloat()
        for (i in 0 until n) {
            val card = cards[i].first
            val baseY = cards[i].second
            val baseZ = baseOfZ(card)
            val h = card.height.toFloat()

            val base2 = baseY + shift
            val naturalCenter = loc[1] + base2 + h / 2f
            // tertutup: kartu menumpuk ke atas dari garis teks yang sama (kartu bawah tetap di acuan)
            val targetCenter = anchorBottom - h / 2f - (n - 1 - i) * Config.peekDp * d
            var wy = base2 + (1f - p1) * (targetCenter - naturalCenter)

            val collapsed = (Config.pileScale - 0.05f * i).coerceAtLeast(0.55f)
            var s = 1f - (1f - p1) * (1f - collapsed)
            val wz = baseZ + (1f - p1) * (n - i) * 3f * d

            // "fade ketengah": kartu ditarik ke tengah layar, mengecil sedikit, lalu memudar
            if (fade > 0f) {
                val cy = loc[1] + wy + h / 2f
                wy += (dm.heightPixels * 0.5f - cy) * fade
                s *= 1f - 0.12f * fade
                card.alpha = 1f - fade
            } else if (card.alpha != 1f) {
                card.alpha = 1f
            }

            card.pivotX = card.width / 2f
            card.pivotY = h / 2f
            card.translationY = wy
            card.translationZ = wz
            card.scaleX = s
            card.scaleY = s
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz)
            if (i == 0) {
                topVisual = loc[1] + wy + h / 2f - h * s / 2f
                leftVisual = (loc[0] + card.left).toFloat()
            }
        }

        updateLabels(sv, n, p1, topVisual, leftVisual, d, dm.heightPixels, fade)
    }

    private fun countCards(sv: View): Int {
        val g = sv as? ViewGroup ?: return 0
        var n = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) n++
        }
        return n
    }

    // ---- label: "Notification Center" (di atas stack) dan "N Notification(s)" (saat stack tertutup) ----

    private fun makeLabel(ctx: Context, sp: Float): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        // tanpa bayangan hitam (diminta): teks polos
        isClickable = false
        isFocusable = false
        alpha = 0f
        visibility = View.INVISIBLE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun ensureLabels(host: FrameLayout) {
        if (centerLabel?.parent !== host) {
            (centerLabel?.parent as? ViewGroup)?.removeView(centerLabel)
            val v = makeLabel(host.context, 17f)
            v.text = "Notification Center"
            v.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            host.addView(v)
            centerLabel = v
        }
        // bubble frosted di belakang "N Notifications" (ditambahkan sebelum label agar di belakang teks)
        if (bubbleView?.parent !== host) {
            (bubbleView?.parent as? ViewGroup)?.removeView(bubbleView)
            val d = host.resources.displayMetrics.density
            val v = CountBubble(host.context)
            v.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (72f * d).toInt()
            )
            host.addView(v)
            bubbleView = v
        }
        if (countLabel?.parent !== host) {
            (countLabel?.parent as? ViewGroup)?.removeView(countLabel)
            val v = makeLabel(host.context, 13f)
            host.addView(v)
            countLabel = v
        }
    }

    private fun hideLabels() {
        centerLabel?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
        countLabel?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
        bubbleView?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
    }

    private fun updateLabels(
        sv: View, n: Int, p1: Float, topVisual: Float, leftVisual: Float,
        d: Float, screenH: Int, fade: Float,
    ) {
        if (!Config.labels) { hideLabels(); return }
        val host = sv.rootView as? FrameLayout ?: return
        ensureLabels(host)
        val c = centerLabel ?: return
        val k = countLabel ?: return
        val hl = IntArray(2)
        host.getLocationOnScreen(hl)

        // "Notification Center": menempel di atas kartu paling atas, rata kiri dengan tepi kiri kartu
        // (garis merah "y" foto 3), bukan di tengah. Saat bouncer (swipe ke-3) ikut memudar ke tengah.
        val ca = (((p1 - 0.7f) / 0.3f).coerceIn(0f, 1f)) * (1f - fade)
        c.alpha = ca
        c.visibility = if (ca > 0f) View.VISIBLE else View.INVISIBLE
        val ch = if (c.height > 0) c.height.toFloat() else 28f * d
        c.translationY = topVisual - hl[1] - ch - 6f * d
        val leftX = (leftVisual - hl[0]).coerceAtLeast(8f * d)
        val ctw = c.paint.measureText(c.text.toString())
        val midX = ((sv.width - ctw) / 2f).coerceAtLeast(0f)
        c.translationX = leftX + (midX - leftX) * fade

        // "N Notification(s)": tampil saat stack tertutup, memudar saat stack ditarik
        val ka = ((1f - p1 * 3f).coerceIn(0f, 1f)) * (1f - fade)
        val t = "\u2022 $n Notification" + if (n != 1) "s" else ""
        if (k.text.toString() != t) k.text = t
        k.alpha = ka
        k.visibility = if (ka > 0f) View.VISIBLE else View.INVISIBLE
        val kh = if (k.height > 0) k.height.toFloat() else 20f * d
        k.translationY = screenH * Config.countFrac - hl[1] - kh / 2f

        // bubble: pill frosted (blur wallpaper) di belakang teks, bukan untuk "Notification Center"
        val bub = bubbleView ?: return
        val show = Config.bubble && ka > 0.02f
        bub.alpha = ka
        bub.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) return
        val bubH = if (bub.height > 0) bub.height.toFloat() else 72f * d
        bub.translationY = (k.translationY + kh / 2f) - bubH / 2f
        val pillW = k.paint.measureText(t) + 26f * d
        val pillH = kh + 10f * d
        bub.pillW = pillW
        bub.pillH = pillH
        bub.screenX = hl[0] + sv.width / 2f
        bub.screenY = hl[1] + k.translationY + kh / 2f
        // live blur: render ulang lapisan wallpaper yang sedang tampil, jadi blur ikut bergerak
        val live = liveWallSource(host)
        bub.liveSrc = live
        bub.liveAnimating = p1 > 0.01f
        bub.fallback = if (live?.get() == null) {
            backdropFor(bub.context, pillW, pillH, bub.screenX, bub.screenY, sv.width, screenH)
        } else null
        bub.invalidate()
    }

    // ---- cari lapisan wallpaper yang tampil di keyguard (sumber blur live) ----

    private fun liveWallSource(host: View): WeakReference<View>? {
        if (!Config.liveBlur) return null
        val cached = liveRef?.get()
        if (cached != null && cached.isShown && cached.width > 0 && cached.height > 0) return liveRef
        val now = System.currentTimeMillis()
        if (now - liveScanAt < 1000L) return liveRef?.takeIf { it.get() != null }
        liveScanAt = now
        val root = host.rootView
        val found = ArrayList<View>()
        findViews(root, 0, found) { v ->
            v !== root && v !== host && v.isShown && v.width > 0 && v.height > 0 &&
                v.width >= host.width * 0.9f && v.height >= host.height * 0.55f &&
                (
                    idName(v).contains("wallpaper") || idName(v).contains("background") ||
                        v.javaClass.simpleName.contains("Wallpaper") ||
                        v.javaClass.simpleName.contains("Background")
                    )
        }
        val v = found.firstOrNull()
        if (v == null) {
            once("noWallView") { log("lapisan wallpaper tidak ditemukan, bubble pakai potongan statis") }
            liveRef = null
            return null
        }
        if (liveRef?.get() !== v) {
            log("blur live dari ${v.javaClass.simpleName} #${idName(v)}")
            liveRef = WeakReference(v)
        }
        return liveRef
    }

    private fun reset(sv: View?) {
        hideLabels()
        bouncerFade = 0f
        bouncerRef = null
        applyClock(0f)
        // kembalikan wallpaper ke keadaan normal (nilai diam yang dipakai System UI)
        if (sentWp) {
            sentWp = false
            if (restScale > 0f) sendWp(restAlpha, restScale, true)
        }
        // nilai diam jam depth dipelajari ulang tiap kali masuk lockscreen
        restAlpha = 0f
        restScale = 0f
        // kembalikan gear jam ke permintaan sistem
        gearSmall = false
        val sg = sysGear
        val lg = lastGearSet
        lastGearSet = null
        if (lg != null && sg != null && lg != sg) setGear(sg)
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

    // ---- bouncer kunci (swipe ke-3): notifikasi & label memudar ke tengah ----

    private fun bouncerShowing(root: View): Boolean {
        val cached = bouncerRef?.get()
        if (cached != null) {
            if (cached.isShown && cached.alpha > 0.01f) return true
            bouncerRef = null
        }
        val now = System.currentTimeMillis()
        if (now - lastBouncerScan < 300L) return false
        lastBouncerScan = now
        val found = ArrayList<View>()
        findViews(root, 0, found) { v ->
            val cn = v.javaClass.simpleName
            val id = idName(v)
            (cn.contains("Bouncer") || cn.contains("KeyguardSecurity") ||
                id.contains("bouncer") || id.contains("keyguard_security")) &&
                v.isShown && v.alpha > 0.01f && v.width > 0 && v.height > 0
        }
        val v = found.firstOrNull() ?: return false
        bouncerRef = WeakReference(v)
        log("bouncer terdeteksi: ${v.javaClass.simpleName} ${idName(v)}")
        return true
    }

    // naik/turun bertahap tiap frame supaya fade-nya halus, bukan lompat
    private fun updateBouncer(root: View): Float {
        val target = if (bouncerShowing(root)) 1f else 0f
        val f = bouncerFade
        if (f == target) return f
        val step = 0.18f
        bouncerFade = if (target > f) (f + step).coerceAtMost(1f) else (f - step).coerceAtLeast(0f)
        return bouncerFade
    }

    // ---- bubble frosted: potongan wallpaper lockscreen yang diburamkan ----

    private fun lockWallpaper(ctx: Context): Bitmap? {
        if (wallTried) return wallBmp
        wallTried = true
        wallBmp = try {
            val wm = WallpaperManager.getInstance(ctx)
            // getBitmap() tidak ada di stub SDK ini, jadi drawable lockscreen digambar sendiri
            val dr = try { wm.getDrawable(WallpaperManager.FLAG_LOCK) } catch (e: Throwable) { null }
                ?: wm.getDrawable()
            if (dr == null) null else {
                val iw = if (dr.intrinsicWidth > 0) dr.intrinsicWidth else 1080
                val ih = if (dr.intrinsicHeight > 0) dr.intrinsicHeight else 2160
                val s = 540f / iw
                val bw = 540
                val bh = (ih * s).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                dr.setBounds(0, 0, bw, bh)
                dr.draw(c)
                bmp
            }
        } catch (t: Throwable) {
            once("wallFail") { log("wallpaper gagal dibaca: $t") }
            null
        }
        return wallBmp
    }

    private fun backdropFor(
        ctx: Context, pw: Float, ph: Float,
        cx: Float, cy: Float, screenW: Int, screenH: Int,
    ): Bitmap? {
        if (!Config.bubble) return null
        val wall = lockWallpaper(ctx) ?: return null
        if (wall.isRecycled || screenW <= 0 || screenH <= 0) return null
        val key = "${pw.toInt()}x${ph.toInt()}@${cx.toInt()},${cy.toInt()}/${wall.width}x${wall.height}"
        if (key == bubbleKey && bubbleBackdrop?.isRecycled == false) return bubbleBackdrop
        return try {
            val bw = wall.width.toFloat()
            val bh = wall.height.toFloat()
            val sw = (pw / screenW * bw).coerceAtLeast(6f)
            val sh = (ph / screenH * bh).coerceAtLeast(4f)
            val l = (cx / screenW * bw - sw / 2f).coerceIn(0f, (bw - sw).coerceAtLeast(0f))
            val t = (cy / screenH * bh - sh / 2f).coerceIn(0f, (bh - sh).coerceAtLeast(0f))
            val smallW = 40
            val smallH = (smallW * sh / sw).toInt().coerceIn(8, 40)
            val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            val c = Canvas(small)
            c.drawBitmap(
                wall,
                Rect(l.toInt(), t.toInt(), (l + sw).toInt(), (t + sh).toInt()),
                RectF(0f, 0f, smallW.toFloat(), smallH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            upscaleBlur(small)
            bubbleBackdrop?.recycle()
            bubbleBackdrop = small
            bubbleKey = key
            small
        } catch (e: Throwable) {
            once("bubbleFail") { log("bubble gagal: $e") }
            null
        }
    }

    private fun upscaleBlur(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        repeat(2) {
            boxBlur(px, w, h, 3, true)
            boxBlur(px, w, h, 3, false)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
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

// kotak blur sederhana (dipakai bubble untuk memblur potongan kecil)
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val src = px.copyOf()
    for (y in 0 until h) {
        for (x in 0 until w) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (k in -r..r) {
                val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                val p = src[yy * w + xx]
                sa += (p ushr 24) and 0xFF
                sr += (p ushr 16) and 0xFF
                sg += (p ushr 8) and 0xFF
                sb += p and 0xFF
                n++
            }
            px[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
    }
}

private fun bubbleLog(msg: String) {
    XposedBridge.log("[IOSLock] $msg")
    android.util.Log.i("IOSLock", msg)
}

// Pill frosted di belakang "N Notifications": blur LIVE dari lapisan wallpaper yang sedang tampil
// (dirender ulang tiap ~120 ms, jadi ikut bergerak bersama wallpaper/depth), bentuk kapsul seperti
// tombol flash/kamera, plus lapisan gelap transparan + tepi tipis.
private class CountBubble(ctx: Context) : View(ctx) {
    var pillW: Float = 0f
    var pillH: Float = 0f
    // koordinat layar titik tengah pill
    var screenX: Float = 0f
    var screenY: Float = 0f
    // sumber live (lapisan wallpaper di keyguard) dan cadangan potongan statis
    var liveSrc: WeakReference<View>? = null
    var fallback: Bitmap? = null
    // saat diam wallpaper tidak bergerak: cukup refresh 1 detik sekali (hemat CPU)
    var liveAnimating: Boolean = false

    private var liveBmp: Bitmap? = null
    private var liveAt = 0L
    private var liveBroken = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
        strokeWidth = 1f * ctx.resources.displayMetrics.density
    }
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = false
        isFocusable = false
        alpha = 0f
        visibility = INVISIBLE
    }

    // Render isi lapisan wallpaper tepat di area pill, sekarang juga (live).
    private fun liveShot(): Bitmap? {
        if (liveBroken) return null
        val src = liveSrc?.get() ?: return null
        if (!src.isShown || pillW <= 1f || pillH <= 1f) return null
        val now = SystemClock.uptimeMillis()
        val interval = if (liveAnimating) 120L else 1000L
        if (liveAt != 0L && now - liveAt < interval) return liveBmp
        return try {
            val w = 84
            val k = w / pillW
            val h = (pillH * k).toInt().coerceIn(8, 64)
            val bmp = liveBmp?.takeIf { !it.isRecycled && it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { liveBmp = it }
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val loc = IntArray(2)
            src.getLocationOnScreen(loc)
            val save = c.save()
            c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            c.scale(k, k)
            c.translate(
                loc[0] - (screenX - pillW / 2f),
                loc[1] - (screenY - pillH / 2f)
            )
            src.draw(c)
            c.restoreToCount(save)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            if (px.none { it != 0 }) {
                liveBroken = true
                bubbleLog("blur live kosong (lapisan wallpaper tidak bisa dirender), pakai potongan statis")
                return null
            }
            repeat(2) {
                boxBlur(px, w, h, 3, true)
                boxBlur(px, w, h, 3, false)
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            liveAt = now
            bmp
        } catch (t: Throwable) {
            liveBroken = true
            bubbleLog("blur live gagal: $t")
            null
        }
    }

    override fun onDraw(c: Canvas) {
        if (pillW <= 0f || pillH <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
        val r = pillH / 2f
        val b = liveShot() ?: fallback
        if (b != null && !b.isRecycled) {
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            val save = c.save()
            c.clipPath(path)
            c.drawBitmap(b, null, rect, bmpPaint)
            c.restoreToCount(save)
        }
        c.drawRoundRect(rect, r, r, scrim)
        c.drawRoundRect(rect, r, r, edge)
    }
}
