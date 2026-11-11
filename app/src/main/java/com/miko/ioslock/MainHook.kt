package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

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
    // jam wallpaper dikunci tinggi saat diam, kecil di keadaan 3
    val gearForce: Boolean get() = prop("debug.ioslock.gearforce") != "0"
    val gearAt: Float get() = prop("debug.ioslock.gearat").toFloatOrNull() ?: 0.3f

    // posisi bagian atas kartu teratas (0..1 tinggi layar): keadaan 2 (bawah jam tinggi) dan keadaan 3 (bawah jam biasa)
    val restFrac: Float get() = prop("debug.ioslock.restfrac").toFloatOrNull() ?: 0.575f
    val smallFrac: Float get() = prop("debug.ioslock.smallfrac").toFloatOrNull() ?: 0.345f
    // batas bawah daftar kartu keadaan 3 (di atas fingerprint dan teks bawah)
    val listBottomFrac: Float get() = prop("debug.ioslock.listbottom").toFloatOrNull() ?: 0.865f
    // keadaan 2: posisi tepi bawah pita tumpukan TERAKHIR (0..1 tinggi layar) = garis merah.
    // Ketebalan pita dihitung otomatis supaya dasar tumpukan tepat di sini.
    val bottom2Frac: Float get() = prop("debug.ioslock.bottom2").toFloatOrNull() ?: 0.855f
    // pita tidak boleh lebih tebal dari sekian kali tinggi kartu (supaya tetap menumpuk di belakang kartu depan, tanpa celah)
    val peekMaxFrac: Float get() = prop("debug.ioslock.peekmaxfrac").toFloatOrNull() ?: 0.9f
    // ketebalan minimal sebuah kartu yang menumpuk (dp)
    val minPeekDp: Float get() = prop("debug.ioslock.minpeekdp").toFloatOrNull() ?: 12f
    // meluncur tambahan saat kartu muncul; 0 = kartu keluar dari dasar tumpukan saja (lihat originFrac)
    val slideDp: Float get() = prop("debug.ioslock.slidedp").toFloatOrNull() ?: 0f
    // animasi buka tumpukan (keadaan 1 -> 2): semua kartu keluar dari satu titik di dasar tumpukan
    // (di atas teks "N Notifications"), kartu depan lebih dulu, kartu terakhir tetap di tempatnya.
    // originFrac = tepi bawah titik asal itu (0..1 tinggi layar; dipakai kalau dasar tumpukan keadaan 2
    // lebih tinggi dari ini, misalnya hanya 1-2 notifikasi). openStagger = seberapa bergantian (0 = serentak, maks 0.8)
    val originFrac: Float get() = prop("debug.ioslock.originfrac").toFloatOrNull() ?: 0.855f
    val openStagger: Float get() = prop("debug.ioslock.openstagger").toFloatOrNull() ?: 0.35f
    // skala awal kartu saat tumpukan dibuka (kecil, di garis asal), lalu membesar ke ukuran keadaan 2
    val openScale: Float get() = prop("debug.ioslock.openscale").toFloatOrNull() ?: 0.55f
    // keadaan 2: jumlah kartu yang tampil penuh (sisanya menumpuk di belakang)
    val full2: Int get() = prop("debug.ioslock.full2").toIntOrNull() ?: 1
    // keadaan 2: jumlah pita tumpukan yang terlihat di bawah kartu penuh terakhir
    val bands2: Int get() = prop("debug.ioslock.bands2").toIntOrNull() ?: 2
    // keadaan 2: kalau jumlah kartu <= noStack2, tumpukan dihilangkan (semua kartu tampil penuh, jarak gap2Dp)
    val noStack2: Int get() = prop("debug.ioslock.nostack2").toIntOrNull() ?: 3
    val gap2Dp: Float get() = prop("debug.ioslock.gap2dp").toFloatOrNull() ?: 10f
    // keadaan 2: semua kartu setelah kartu depan menumpuk di belakangnya PERSIS seperti tumpukan bawah ("dock") keadaan 3
    // (pakai dockScale / dockRefDp yang sama). 0 = kembali ke pita lama (maks bands2 pita, sisanya tersembunyi).
    val dockStack2: Boolean get() = prop("debug.ioslock.dockstack2") != "0"
    // tebal tumpukan keadaan 2 (dp); 0 = sama dengan dockDp. Dibatasi supaya dasar tumpukan tidak melewati bottom2Frac.
    val dock2Dp: Float get() = prop("debug.ioslock.dock2dp").toFloatOrNull() ?: 0f
    // keadaan 3: semua kartu tampil penuh dengan jarak kecil gap3Dp; bagian atas dipotong dengan fade.
    // Kartu yang tidak muat di bawah (batas listBottomFrac) tetap kecil dan menumpuk setebal dockDp,
    // lalu membesar saat daftar di-swipe naik. dockScale = pengecilan skala maksimum kartu di tumpukan bawah,
    // dockRefDp = seberapa jauh kartu di bawah batas sampai skalanya mencapai pengecilan maksimum.
    val gap3Dp: Float get() = prop("debug.ioslock.gap3dp").toFloatOrNull() ?: 8f
    val dockDp: Float get() = prop("debug.ioslock.dockdp").toFloatOrNull() ?: 30f
    val dockScale: Float get() = prop("debug.ioslock.dockscale").toFloatOrNull() ?: 0.12f
    val dockRefDp: Float get() = prop("debug.ioslock.dockrefdp").toFloatOrNull() ?: 240f
    // buka batas jumlah notifikasi yang ditampilkan sistem di lockscreen (matikan: setprop debug.ioslock.unlimit 0)
    val unlimit: Boolean get() = prop("debug.ioslock.unlimit") != "0"
    // slider scroll keadaan 3: lebar (dp), tampil/hilang (ms). Posisinya otomatis di tengah celah tepi layar dan kartu.
    val sliderDp: Float get() = prop("debug.ioslock.sliderdp").toFloatOrNull() ?: 4f
    val sliderHideMs: Long get() = prop("debug.ioslock.sliderhidems").toLongOrNull() ?: 900L
    val sliderFadeInMs: Long get() = prop("debug.ioslock.sliderfadein").toLongOrNull() ?: 150L
    val sliderFadeOutMs: Long get() = prop("debug.ioslock.sliderfadeout").toLongOrNull() ?: 350L
    // keadaan 3: kartu yang ter-scroll melewati garis atas (smallFrac) dipotong dengan fade setebal topFadeDp
    // (0 = potong datar). Wallpaper digelapkan sebesar dimAlpha (0..1) saat masuk keadaan 3 (0 = mati).
    val topFadeDp: Float get() = prop("debug.ioslock.topfade").toFloatOrNull() ?: 44f
    val dimAlpha: Float get() = prop("debug.ioslock.dim").toFloatOrNull() ?: 0.28f
    // home screen / shade (di luar lockscreen, setelah kunci dibuka): garis potong atas (0..1 tinggi layar,
    // tepat di bawah jam/tanggal; 0 = tanpa mask sama sekali) dan tebal fade (dp). Dibuat jauh lebih sempit
    // daripada topFadeDp / smallFrac milik lockscreen supaya notifikasi di home tidak ikut terpotong lebar.
    val shadeLineFrac: Float get() = prop("debug.ioslock.shadeline").toFloatOrNull() ?: 0.165f
    val shadeFadeDp: Float get() = prop("debug.ioslock.shadefade").toFloatOrNull() ?: 14f
    // swipe naik keadaan 2 -> 3: kartu keluar dari tumpukan satu per satu (kartu depan lebih dulu).
    // 0 = semua kartu renggang bersamaan, makin besar makin bergantian (maksimum 0.8)
    val stagger: Float get() = prop("debug.ioslock.stagger").toFloatOrNull() ?: 0.3f
    // tiap pita tumpukan lebih tipis dari pita di depannya (1 = sama tebal),
    // dan pengecilan skala tiap pita (0.05 = 5% per pita)
    val bandDecay: Float get() = prop("debug.ioslock.banddecay").toFloatOrNull() ?: 0.85f
    val scaleStep: Float get() = prop("debug.ioslock.scalestep").toFloatOrNull() ?: 0.035f
    // posisi teks "N Notifications", dan apakah tetap tampil saat kartu muncul
    val countFrac: Float get() = prop("debug.ioslock.countfrac").toFloatOrNull() ?: 0.845f
    val countAlways: Boolean get() = prop("debug.ioslock.countalways") == "1"
    // teks "Notification Center": jarak dari tepi kiri (dp) dan ukuran (sp)
    val labelLeftDp: Float get() = prop("debug.ioslock.labelleftdp").toFloatOrNull() ?: 28f
    val labelSp: Float get() = prop("debug.ioslock.labelsp").toFloatOrNull() ?: 15f

    // jarak swipe tiap tahap (dp)
    val rangeDp: Float get() = prop("debug.ioslock.rangedp").toFloatOrNull() ?: 220f
    val stage2Dp: Float get() = prop("debug.ioslock.stage2dp").toFloatOrNull() ?: 100f
    val bottomZoneDp: Float get() = prop("debug.ioslock.bottomdp").toFloatOrNull() ?: 120f

    // tombol "Reset UI" di menu power (matikan: setprop debug.ioslock.powerbtn 0)
    val powerBtn: Boolean get() = prop("debug.ioslock.powerbtn") != "0"
    // 1 = catat semua teks di tiap window baru (untuk mencari tahu menu power ada di mana)
    val pwrDump: Boolean get() = prop("debug.ioslock.pwrdump") == "1"
    // kalau jalur root tidak mematikan System UI dalam sekian ms, System UI mematikan dirinya sendiri (0 = tanpa cadangan)
    val restartFallbackMs: Long get() = prop("debug.ioslock.restartfallback").toLongOrNull() ?: 2000L
}

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val GEAR_KEY = "key_switch_gear_to_small"
        const val MODULE_PKG = "com.miko.ioslock"
        const val RESTART_ACTION = "com.miko.ioslock.RESTART_SYSTEMUI"
        const val RESET_UI_LABEL = "Reset UI"
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

    // pengaturan clip asli view induk stack: [clipChildren, clipToPadding]
    private val clipOrig = WeakHashMap<ViewGroup, BooleanArray>()

    private var drawHookView: WeakReference<View>? = null

    // kontrol setting gear jam wallpaper
    @Volatile private var ownWrite = false
    @Volatile private var sysGearValue = ""
    private var lastGearWritten: String? = null

    // layar mati / layar PIN
    @Volatile private var screenOff = false
    @Volatile private var bouncerHook = false
    private var bouncerScan = false
    private var lastBouncerScan = 0L
    private var receiverRegistered = false

    // label
    private var labelCenter: TextView? = null
    private var labelCount: TextView? = null

    // state gestur. progress: 0..1 = kartu muncul (keadaan 1 -> 2), 1..2 = naik (keadaan 2 -> 3)
    @Volatile private var progress = 0f
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var baseProgress = 0f
    private var tracking = false
    private var taken = false
    private var applied = false
    private var animator: ValueAnimator? = null

    // scroll daftar di keadaan 3 (px) dan slider di tepi kanan
    @Volatile private var scrollY = 0f
    @Volatile private var scrollMaxPx = 0f
    private var baseScroll = 0f
    private var slider: SliderView? = null
    private var sliderShown = false
    private var sliderDrag = false
    private var sliderTrackTop = 0f
    private var sliderTrackH = 0f
    private var sliderThumbH = 0f
    private var sliderOn = false
    @Volatile private var sliderPoke = false
    private var lastScrollSeen = 0f
    private val sliderHideRunnable = Runnable { fadeSlider(false) }

    // fade atas daftar (mask lewat hook dispatchDraw milik view stack)
    @Volatile private var maskOn = false
    @Volatile private var maskLine = 0f
    @Volatile private var maskFade = 0f
    @Volatile private var maskStrength = 0f
    private var maskHooked = false
    @Volatile private var maskReady = false
    private val maskSolid = Paint().apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val maskGrad = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    // dim wallpaper di keadaan 3
    private var dimView: View? = null

    // batas jumlah notifikasi: field int di stack yang ikut diangkat (jaring pengaman selain hook setter)
    private var limitFieldsCls: Class<*>? = null
    private var limitFields: List<java.lang.reflect.Field> = emptyList()
    private var lastChildDump = 0L

    // tombol Reset UI di menu power
    private val pwrTag = "ioslock_reset_ui"
    private var powerWordsCache: Pair<Set<String>, Set<String>>? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.27 loaded into SystemUI")

        val clockHelper = "com.transsion.keyguard.clock.KeyguardClockHelper"
        val clockInteractor = "com.transsion.keyguard.clock.domain.interactor.KeyguardClockInteractor"
        val panel = "com.android.systemui.shade.TrNotificationPanelViewController"
        val stack = "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayoutController"
        val shadeWindow = "com.android.systemui.shade.NotificationShadeWindowView"
        val stateCtl = "com.android.systemui.statusbar.StatusBarStateControllerImpl"
        val bouncer = "com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor"

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

        hook(cl, clockInteractor, "switchClockGear", before = { p ->
            logT("switchClockGear", "args=${p.args.toList()} progress=$progress state=$barState", discrete = true)
        })

        // Layar menyala / mati: progres swipe lama tidak boleh tersisa
        hook(cl, clockHelper, "powerModeChanged", after = {
            animator?.cancel()
            progress = 0f
            scrollY = 0f
            bouncerHook = false
            lastGearWritten = null
            applyState()
        })

        // Layar PIN (bouncer)
        hook(cl, bouncer, "show", after = { bouncerHook = true; logT("bouncerHook", "show", discrete = true); applyState() })
        hook(cl, bouncer, "onFullyShown", after = { bouncerHook = true; applyState() })
        hook(cl, bouncer, "hide", after = { bouncerHook = false; logT("bouncerHook", "hide", discrete = true); applyState() })
        hook(cl, bouncer, "onFullyHidden", after = { bouncerHook = false; applyState() })

        hook(cl, panel, "positionClockAndNotifications", after = { applyState() })

        // Buka batas jumlah notifikasi yang ditampilkan sistem di lockscreen
        // (kalau tidak, kartu di luar batas disembunyikan sistem dan tidak ikut ditata)
        val limitSetters = listOf(
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
            "com.android.systemui.statusbar.notification.stack.TrNotificationStackScrollLayout",
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController",
            stack,
            "com.android.systemui.statusbar.notification.stack.AmbientState"
        )
        for (c in limitSetters) {
            hook(cl, c, "setMaxDisplayedNotifications", before = { p ->
                if (shouldUnlimit() && p.args.isNotEmpty() && p.args[0] is Int) {
                    p.args[0] = 99
                }
            })
        }
        hook(cl, "com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator",
            "computeMaxKeyguardNotifications", after = { p ->
                if (shouldUnlimit() && p.result is Int) {
                    p.result = 99
                }
            })

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
            if (s == 1 && barState != 1) {
                animator?.cancel()
                progress = 0f
                scrollY = 0f
                bouncerHook = false
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

        // Menu power: setiap window baru di System UI dipindai; kalau isinya "Power Off" + "Restart"
        // maka tombol "Reset UI" ditambahkan di bawahnya (lihat bagian tombol Reset UI)
        hook(cl, "android.view.WindowManagerGlobal", "addView", after = { p ->
            val v = p.args.getOrNull(0) as? View ?: return@hook
            if (Config.powerBtn) schedulePowerScan(v)
        })
    }

    // ---- layar mati ----

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_SCREEN_ON)
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    try {
                        when (i?.action) {
                            Intent.ACTION_SCREEN_OFF -> {
                                screenOff = true
                                animator?.cancel()
                                progress = 0f
                                scrollY = 0f
                                bouncerHook = false
                                lastGearWritten = null
                                hideDim()
                                hideLabels()
                            }
                            Intent.ACTION_SCREEN_ON -> {
                                screenOff = false
                                applyState()
                            }
                        }
                    } catch (t: Throwable) { }
                }
            }, f)
        } catch (t: Throwable) {
            once("recvFail") { log("receiver gagal: $t") }
        }
    }

    // ---- layar PIN ----

    private fun isBouncerView(v: View): Boolean {
        val n = v.javaClass.name
        val id = idName(v)
        val hit = n.contains("KeyguardSecurityContainer") || n.contains("KeyguardBouncer") ||
            id.contains("bouncer", true) || id == "keyguard_security_container"
        return hit && v.isShown && v.height > 300 && v.alpha > 0.05f
    }

    private fun scanBouncer(root: View): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBouncerScan < 150) return bouncerScan
        lastBouncerScan = now
        val found = ArrayList<View>()
        findViews(root.rootView, 0, found) { isBouncerView(it) }
        val hit = found.isNotEmpty()
        if (hit != bouncerScan) {
            bouncerScan = hit
            log("bouncer scan=$hit " + (found.firstOrNull()?.let { it.javaClass.simpleName + "#" + idName(it) } ?: ""))
        }
        return hit
    }

    private fun labelsBlocked(root: View): Boolean {
        val pm = root.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: true
        return screenOff || !interactive || bouncerHook || scanBouncer(root)
    }

    // ---- setting gear ----

    private fun shouldForceGear(): Boolean = Config.gesture && Config.gearForce && barState == 1

    private fun shouldUnlimit(): Boolean = Config.gesture && Config.unlimit && onKeyguardState()

    // "1" = jam biasa (kecil), "0" = jam tinggi
    private fun desiredGear(): String = if (qOf(progress) >= Config.gearAt) "1" else "0"

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
            if (isLaidOut(g.getChildAt(i))) return true
        }
        return false
    }

    // kartu sungguhan di stack (bukan footer, header seksi, atau view kosong)
    private fun isCard(c: View): Boolean {
        val n = c.javaClass.name
        return !(n.contains("Footer") || n.contains("SectionHeader") || n.contains("EmptyShade") ||
            n.contains("Spacer"))
    }

    // tinggi kartu yang benar-benar terlihat. Baris notifikasi punya tinggi view (getHeight) yang bisa
    // jauh lebih besar dari tinggi yang digambar (getActualHeight), terutama notifikasi pesan/GIF.
    private val noActualH = ConcurrentHashMap.newKeySet<Class<*>>()
    private fun visH(c: View): Float {
        val cls = c.javaClass
        if (!noActualH.contains(cls)) {
            try {
                val v = XposedHelpers.callMethod(c, "getActualHeight") as? Int
                if (v != null && v > 0) return v.coerceAtMost(c.height).toFloat()
            } catch (t: Throwable) { noActualH.add(cls) }
        }
        return c.height.toFloat()
    }

    // kartu nyata: bukan view hantu (tidak terlihat atau terlalu pendek)
    private fun isReal(c: View): Boolean {
        if (!isCard(c)) return false
        val dp = c.resources.displayMetrics.density
        if (visH(c) < 56f * dp) return false
        return c.alpha > 0.05f
    }

    // kartu yang ikut dihitung dan ikut ditata. INVISIBLE sengaja ikut: kartu di luar batas jumlah
    // notifikasi lockscreen disembunyikan sistem dengan INVISIBLE (bukan GONE), dan harus dimunculkan lagi
    // di applyStack. Hanya GONE yang berarti kartu itu benar-benar dibuang sistem.
    private fun isLaidOut(c: View): Boolean =
        c.visibility != View.GONE && c.width > 0 && c.height > 0 && isReal(c)

    // jumlah notifikasi: semua kartu (termasuk kartu musik dan senter); grup dihitung per anak
    private fun notifCount(g: ViewGroup): Int {
        var n = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (isLaidOut(c)) {
                var k = 0
                if (c.javaClass.name.contains("ExpandableNotificationRow")) {
                    try {
                        k = (XposedHelpers.callMethod(c, "getNumberOfNotificationChildren") as? Int) ?: 0
                    } catch (t: Throwable) { }
                }
                n += if (k > 0) k else 1
            }
        }
        return n
    }

    // Kumpulkan kartu yang ikut ditata, urut dari atas. Kartu yang disembunyikan sistem (INVISIBLE) ikut
    // diambil dan dimunculkan lagi. Posisi aslinya tidak bisa dipercaya, jadi dipasang di bawah kartu
    // terakhir yang terlihat, sesuai urutan anak di stack.
    private fun collectCards(g: ViewGroup): ArrayList<Pair<View, Float>> {
        val shown = ArrayList<Pair<View, Float>>()
        val hidden = ArrayList<View>()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (!isLaidOut(c)) continue
            if (c.visibility == View.VISIBLE) shown.add(Pair(c, baseOfY(c))) else hidden.add(c)
        }
        shown.sortBy { it.second }
        if (hidden.isNotEmpty()) {
            val d = g.resources.displayMetrics.density
            var y = if (shown.isEmpty()) 0f else shown.last().second + visH(shown.last().first) + 8f * d
            for (c in hidden) {
                c.visibility = View.VISIBLE
                shown.add(Pair(c, y))
                y += visH(c) + 8f * d
            }
        }
        return shown
    }

    // Jaring pengaman: kalau hook setter batas tidak kena (nama method beda di ROM ini), field int batasnya
    // di stack diangkat langsung ke 99. -1 (tanpa batas) dibiarkan.
    private fun liftLimit(sv: View) {
        if (!shouldUnlimit()) return
        try {
            if (limitFieldsCls !== sv.javaClass) {
                val list = ArrayList<java.lang.reflect.Field>()
                var c: Class<*>? = sv.javaClass
                while (c != null && c != ViewGroup::class.java) {
                    for (f in c.declaredFields) {
                        if (f.type == Int::class.javaPrimitiveType &&
                            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                            (f.name.contains("MaxDisplayedNotifications", true) ||
                                f.name.contains("MaxKeyguardNotifications", true))
                        ) {
                            f.isAccessible = true
                            list.add(f)
                        }
                    }
                    c = c.superclass
                }
                limitFields = list
                limitFieldsCls = sv.javaClass
                log("limit fields: " + list.joinToString { it.declaringClass.simpleName + "." + it.name })
            }
            for (f in limitFields) {
                val cur = f.getInt(sv)
                if (cur in 0..98) {
                    f.setInt(sv, 99)
                    logT("liftLimit", "${f.name} $cur -> 99", discrete = true)
                }
            }
        } catch (t: Throwable) {
            once("liftFail") { log("liftLimit gagal: $t") }
        }
    }

    // daftar semua anak stack untuk debug (logcat | grep IOSLock | grep children)
    private fun dumpChildren(g: ViewGroup): String {
        val sb = StringBuilder()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            sb.append(" #").append(i).append(' ').append(c.javaClass.simpleName.take(18))
                .append(when (c.visibility) { View.VISIBLE -> " V "; View.INVISIBLE -> " I "; else -> " G " })
                .append(c.width).append('x').append(c.height).append('/').append(visH(c).toInt())
                .append(if (isLaidOut(c)) " ok" else " skip")
        }
        return sb.toString()
    }

    // ---- tahap swipe: 0..1 kartu muncul, 1..2 naik ----

    private fun aOf(p: Float): Float = min(p, 1f)
    private fun qOf(p: Float): Float = (p - 1f).coerceIn(0f, 1f)

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
        if (!Config.gesture) { if (applied) { progress = 0f; scrollY = 0f; applyState() }; return }
        val dm = root.resources.displayMetrics
        val a = ev.actionMasked

        // slider di tepi kanan (keadaan 3): seret untuk menggulir daftar
        if (a == MotionEvent.ACTION_DOWN) {
            val dd = dm.density
            sliderDrag = sliderOn && ev.rawX >= dm.widthPixels - 40f * dd &&
                ev.rawY >= sliderTrackTop - 16f * dd && ev.rawY <= sliderTrackTop + sliderTrackH + 16f * dd
            if (sliderDrag) {
                animator?.cancel()
                tracking = false
                taken = false
            }
        }
        if (sliderDrag) {
            when (a) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> dragSlider(ev.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sliderDrag = false
            }
            p.result = true
            return
        }

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
                scrollY = 0f
                applyState()
            }
            logT("touchSeen", "locked=$locked state=$barState stack=${stackRef?.get() != null} cards=${hasCards()}", discrete = true)
            // tanpa kartu sama sekali, atau saat layar PIN tampil: biarkan sentuhan asli
            tracking = locked && onKeyguardState() && hasCards() && !bouncerHook && !scanBouncer(root) &&
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
                baseScroll = scrollY
                log("takeover swipe, base=$baseProgress")
            }
        }

        if (taken) {
            when (a) {
                MotionEvent.ACTION_MOVE -> {
                    // swipe naik melewati keadaan 3 = menggulir daftar (kalau kartu tidak muat)
                    val l12 = Config.rangeDp + Config.stage2Dp
                    val moved = (startY - ev.rawY) / dm.density
                    val dist = progressToDist(baseProgress) + baseScroll / dm.density + moved
                    progress = distToProgress(min(dist, l12).coerceAtLeast(0f)).coerceIn(0f, 2f)
                    scrollY = ((dist - l12) * dm.density).coerceIn(0f, scrollMaxPx)
                    if (scrollMaxPx > 2f && progress >= 1.99f) sliderPoke = true
                    applyState()
                    logT("progress", "p=$progress")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    taken = false
                    // menempel ke keadaan terdekat: 1 (teks), 2 (kartu muncul), 3 (jam kecil)
                    val target = if (progress < 0.35f) 0f else round(progress).coerceIn(1f, 2f)
                    if (target != progress) snap(target)
                }
            }
            p.result = true
        }
    }

    private fun snap(target: Float) {
        animator?.cancel()
        val an = ValueAnimator.ofFloat(progress, target)
        // jarak dekat = singkat; melambat di akhir supaya kartu "mendarat" halus
        an.duration = (180f + 200f * abs(target - progress)).toLong()
        an.interpolator = DecelerateInterpolator(1.6f)
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
                if (!locked) { progress = 0f; scrollY = 0f }
                // di luar lockscreen (home / shade): mask keadaan 3 yang tertinggal diganti dengan mask home yang sempit
                if (sv != null) applyShadeMask(sv)
                return
            }
            applied = true
            if (ctx != null) {
                ensureReceiver(ctx)
                ensureGear(ctx)
            }
            val p = progress
            if (sv != null) applyStack(sv, aOf(p), qOf(p))
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

    // Lepas pemotongan (clip) di stack dan view induknya supaya pita tumpukan
    // yang berada di luar batas stack tidak ikut terpotong. Nilai asli disimpan untuk dipulihkan.
    private fun freeClip(sv: View) {
        var v: View? = sv
        var depth = 0
        while (v is ViewGroup && depth < 4) {
            if (!clipOrig.containsKey(v)) clipOrig[v] = booleanArrayOf(v.clipChildren, v.clipToPadding)
            v.clipChildren = false
            v.clipToPadding = false
            v = v.parent as? View
            depth++
        }
    }

    private fun restoreClip() {
        for ((k, o) in clipOrig.entries.toList()) {
            k.clipChildren = o[0]
            k.clipToPadding = o[1]
        }
        clipOrig.clear()
    }

    // ---- tata letak tumpukan ----

    // Hasil tata letak satu keadaan (keadaan 2 atau keadaan 3), per kartu:
    // bottom = tepi bawah kartu setelah diskala, scale = skala kartu,
    // kind: 0 = kartu penuh, 1 = pita tumpukan, 2 = tersembunyi di belakang pita terakhir.
    private class StateLayout(n: Int) {
        val bottom = FloatArray(n)
        val scale = FloatArray(n)
        val kind = IntArray(n)
    }

    private fun powf(b: Float, e: Int): Float = Math.pow(b.toDouble(), e.toDouble()).toFloat()

    // Jarak kumulatif pita ke-m di bawah kartu penuh terakhir. Tiap pita lebih tipis dari pita
    // di depannya (peek * decay^(m-1)), supaya tumpukan terlihat berkedalaman dan proporsional.
    private fun bandOffset(m: Int, peek: Float, decay: Float): Float {
        if (m <= 0) return 0f
        if (decay >= 0.999f) return m * peek
        return peek * (1f - powf(decay, m)) / (1f - decay)
    }

    // Tata letak keadaan 2: kartu depan penuh, sisanya menumpuk di belakangnya sebagai pita.
    // Dihitung dari posisi asli kartu di stack bawaan (baseYs, urut dari atas).
    // top: posisi atas kartu teratas. gap: jarak tambahan antar kartu penuh.
    // maxFull: jumlah kartu penuh paling banyak. maxBands: jumlah pita tumpukan yang terlihat.
    // limit: posisi tepi bawah pita TERAKHIR (garis merah). Ketebalan pita dihitung supaya dasar
    // tumpukan tepat di limit (dibatasi maxFrac * tinggi kartu supaya tidak ada celah).
    private fun layoutState(
        baseYs: FloatArray, hs: FloatArray, top: Float, gap: Float,
        maxFull: Int, maxBands: Int, limit: Float, minPeek: Float, maxFrac: Float,
        decay: Float, scaleStep: Float
    ): StateLayout {
        val n = baseYs.size
        val out = StateLayout(n)

        // tepi bawah kartu ke-i kalau dibentangkan (jarak asli antar kartu + gap tambahan)
        fun spread(i: Int): Float = top + (baseYs[i] - baseYs[0]) + i * gap + hs[i]

        // berapa kartu yang tampil penuh (minimal satu); pita dihitung dengan ketebalan minimal
        var k = 0
        for (i in 0 until min(maxFull.coerceAtLeast(1), n)) {
            val bands = min(n - 1 - i, maxBands)
            if (spread(i) + bandOffset(bands, minPeek, decay) <= limit) k = i + 1 else break
        }
        if (k < 1) k = 1
        val lastIdx = k - 1
        val lastBottom = spread(lastIdx)

        // ketebalan pita: dihitung supaya pita terakhir berakhir tepat di limit (garis merah)
        val nb = min(n - k, maxBands)
        var peek = minPeek
        if (nb > 0) {
            var minH = Float.MAX_VALUE
            for (i in k until n) minH = min(minH, hs[i])
            val room = (limit - lastBottom) / bandOffset(nb, 1f, decay)
            peek = min(maxFrac * minH, room).coerceAtLeast(minPeek)
        }

        for (i in 0 until n) {
            if (i < k) {
                out.bottom[i] = spread(i)
                out.scale[i] = 1f
                out.kind[i] = 0
            } else {
                val j = i - lastIdx
                val jj = min(j, maxBands)
                out.bottom[i] = lastBottom + bandOffset(jj, peek, decay)
                out.scale[i] = 1f - scaleStep * jj
                out.kind[i] = if (j > maxBands) 2 else 1
            }
        }
        return out
    }

    // Tata letak tanpa tumpukan: semua kartu tampil penuh, berurutan dengan jarak kecil (gap).
    private fun layoutList(hs: FloatArray, top: Float, gap: Float): StateLayout {
        val n = hs.size
        val out = StateLayout(n)
        var y = top
        for (i in 0 until n) {
            y += hs[i]
            out.bottom[i] = y
            out.scale[i] = 1f
            out.kind[i] = 0
            y += gap
        }
        return out
    }

    // Tata letak keadaan 2 gaya "dock": 'full' kartu depan tampil penuh (posisi tetap), semua kartu sesudahnya
    // menumpuk di belakangnya dengan rumus yang sama persis dengan tumpukan bawah keadaan 3:
    // tepi bawah = dasar kartu depan + dock * (1 - exp(-o / (1.3 * dock))), skala = 1 - dScale * min(1, o / ref),
    // o = jarak kartu itu dari dasar kartu depan kalau daftar dibentangkan. Makin dalam makin tipis dan makin kecil,
    // jadi berapa pun jumlah kartunya semuanya ikut menumpuk. dock dibatasi supaya dasar tumpukan <= limit.
    private fun layoutDockStack(
        hs: FloatArray, top: Float, gap: Float, full: Int,
        dock0: Float, limit: Float, ref: Float, dScale: Float
    ): StateLayout {
        val n = hs.size
        val out = StateLayout(n)
        val k = full.coerceIn(1, n)
        val spread = FloatArray(n)
        var y = top
        for (i in 0 until n) {
            y += hs[i]
            spread[i] = y
            y += gap
        }
        val fullBottom = spread[k - 1]
        val dock = min(dock0, limit - fullBottom).coerceAtLeast(0f)
        for (i in 0 until n) {
            if (i < k) {
                out.bottom[i] = spread[i]
                out.scale[i] = 1f
                out.kind[i] = 0
            } else {
                val o = spread[i] - fullBottom
                out.bottom[i] = if (dock < 1f) fullBottom else fullBottom + dock * (1f - exp(-o / (dock * 1.3f)))
                out.scale[i] = 1f - dScale * min(1f, o / ref)
                out.kind[i] = 1
            }
        }
        return out
    }

    // Seberapa jauh kartu ke-i sudah keluar dari titik asal pada kemajuan a (0..1) animasi buka tumpukan:
    // kartu depan keluar lebih dulu, kartu di belakangnya menyusul, kartu terakhir paling akhir.
    private fun openProgress(i: Int, n: Int, a: Float, lag: Float): Float {
        val l = if (n > 1) lag.coerceIn(0f, 0.8f) else 0f
        val start = if (n > 1) l * i / (n - 1) else 0f
        val t = ((a - start) / (1f - l)).coerceIn(0f, 1f)
        return t + (t * t * (3f - 2f * t) - t) * 0.5f
    }

    // Seberapa jauh kartu ke-i (1..n-1) sudah keluar dari tumpukan pada kemajuan q:
    // 0 = masih menumpuk, 1 = sudah renggang penuh. Dengan stagger > 0 kartu depan keluar lebih dulu
    // dan kartu di belakangnya menyusul; semuanya tepat selesai saat q = 1.
    private fun spreadProgress(i: Int, n: Int, q: Float, stagger: Float): Float {
        val lag = stagger.coerceIn(0f, 0.8f)
        val m = n - 1
        val start = if (m > 1) lag * (i - 1) / (m - 1) else 0f
        val t = ((q - start) / (1f - lag)).coerceIn(0f, 1f)
        // campuran linear (mengikuti jari, proporsional) dan smoothstep (awal/akhir lembut)
        return t + (t * t * (3f - 2f * t) - t) * 0.5f
    }

    // Gabungkan keadaan 2 (la) dan keadaan 3 (lb) jadi posisi tiap kartu pada kemajuan (a, q).
    // Jarak tiap kartu ke kartu di depannya bergerak dari tebal pita (menumpuk) ke jarak penuh (renggang),
    // jadi kartu selalu berurutan, tidak pernah saling menyeberang, dan tidak melewati batas bawah.
    // Kartu pertama mengikuti jari secara linear; tepi bawah tiap kartu dibatasi oleh posisi terjauh
    // kedua keadaan, jadi tidak ada kartu yang melampaui tempat akhirnya lalu mundur.
    // Saat a < 1 (animasi buka tumpukan, keadaan 1 -> 2) semua kartu berangkat dari satu titik asal
    // (origin = tepi bawah dasar tumpukan) lalu keluar bergantian ke posisinya di keadaan 2;
    // kartu terakhir berada di dasar tumpukan itu sejak awal, jadi tumpukannya tetap terlihat.
    private fun composeStack(
        la: StateLayout, lb: StateLayout, a: Float, q: Float, stagger: Float,
        openStagger: Float, origin: Float, outBottom: FloatArray, outScale: FloatArray
    ) {
        val n = outBottom.size
        var prev = la.bottom[0] + (lb.bottom[0] - la.bottom[0]) * q
        outBottom[0] = prev
        outScale[0] = 1f
        for (i in 1 until n) {
            val e = spreadProgress(i, n, q, stagger)
            val dA = la.bottom[i] - la.bottom[i - 1]
            val dB = lb.bottom[i] - lb.bottom[i - 1]
            val cap = max(la.bottom[i], lb.bottom[i])
            prev = min(prev + dA + (dB - dA) * e, cap)
            outBottom[i] = prev
            outScale[i] = la.scale[i] + (lb.scale[i] - la.scale[i]) * e
        }
        if (a < 1f) {
            // di titik asal semua kartu sekecil pita terakhir; makin keluar makin besar
            val sO = Config.openScale.coerceIn(0.2f, 1f)
            for (i in 0 until n) {
                val ea = openProgress(i, n, a, openStagger)
                outBottom[i] = origin + (outBottom[i] - origin) * ea
                outScale[i] = sO + (outScale[i] - sO) * ea
            }
        }
    }

    // Terapkan ulang efek tiap frame (sebelum digambar), supaya posisi
    // tidak ditimpa lagi oleh stack notifikasi bawaan.
    private fun ensureDrawHook(sv: View) {
        if (drawHookView?.get() === sv) return
        val vto = sv.viewTreeObserver
        if (!vto.isAlive) return
        drawHookView = WeakReference(sv)
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (applied && !screenOff) applyState()
                return true
            }
        })
    }

    // a: 0..1 animasi buka tumpukan: semua kartu keluar bergantian dari titik asal (garis bawah) sambil
    //    membesar dari kecil (openScale) ke ukuran keadaan 2
    // q: 0..1 jam mengecil dan daftar naik
    // Keadaan 2 (q=0): kartu depan penuh, semua kartu lain menumpuk di belakangnya seperti dock keadaan 3
    // (dasar tumpukan tidak melewati bottom2Frac). Kalau kartu <= noStack2 dan muat, semua kartu penuh tanpa tumpukan.
    // Keadaan 3 (q=1): semua kartu penuh dengan jarak kecil; atas dipotong di garis dengan fade. Kartu yang melewati batas bawah
    // (listBottomFrac) tetap kecil dan menumpuk di bawah ("dock") sampai daftar di-swipe naik atau slider digeser.
    // Wallpaper digelapkan sedikit saat masuk keadaan 3.
    private fun applyStack(sv: View, a: Float, q: Float) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        sv.alpha = (a * 2.5f).coerceAtMost(1f)
        // saat tersembunyi, stack tidak boleh menerima sentuhan
        sv.visibility = if (a < 0.02f) View.INVISIBLE else View.VISIBLE
        sv.translationY = 0f
        val g = sv as? ViewGroup ?: return
        freeClip(sv)
        ensureDrawHook(sv)
        liftLimit(sv)
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)

        // debug: kondisi semua anak stack (sebelum kartu tersembunyi dimunculkan lagi)
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastChildDump > 500) {
            lastChildDump = nowMs
            logT("children", dumpChildren(g), discrete = true)
        }

        val cards = collectCards(g)
        val n = cards.size

        val restTop = dm.heightPixels * Config.restFrac
        val smallTop = dm.heightPixels * Config.smallFrac
        val targetTop = restTop + (smallTop - restTop) * q
        val count = notifCount(g)

        if (n == 0) {
            scrollMaxPx = 0f
            scrollY = 0f
            updateLabels(sv, a, targetTop, count)
            hideSlider()
            updateMask(sv, false, 0f, smallTop, d)
            updateDim(sv, 0f)
            return
        }

        val limit = dm.heightPixels * Config.listBottomFrac
        val bottom2 = dm.heightPixels * Config.bottom2Frac
        val minPeek = Config.minPeekDp * d
        val slide = (1f - a) * Config.slideDp * d

        val baseYs = FloatArray(n) { cards[it].second }
        val hs = FloatArray(n) { visH(cards[it].first) }
        val decay = Config.bandDecay.coerceIn(0.3f, 1f)
        val step = Config.scaleStep.coerceIn(0f, 0.12f)

        // keadaan 2: tanpa tumpukan kalau kartunya sedikit (dan muat sampai garis), selain itu bertumpuk
        val list2 = if (n <= Config.noStack2) {
            layoutList(hs, restTop, Config.gap2Dp * d).takeIf { it.bottom[n - 1] <= bottom2 }
        } else null
        val la = when {
            list2 != null -> list2
            // keadaan 2 gaya dock: kartu depan penuh, SEMUA kartu lain menumpuk di belakangnya seperti dock keadaan 3
            Config.dockStack2 -> layoutDockStack(
                hs, restTop, Config.gap2Dp * d, Config.full2,
                (if (Config.dock2Dp > 0f) Config.dock2Dp else Config.dockDp) * d, bottom2,
                (Config.dockRefDp * d).coerceAtLeast(1f), Config.dockScale.coerceIn(0f, 0.3f)
            )
            else -> layoutState(baseYs, hs, restTop, 0f, Config.full2, Config.bands2, bottom2,
                minPeek, Config.peekMaxFrac.coerceIn(0.3f, 1f), decay, step)
        }

        // keadaan 3: semua kartu penuh berurutan dengan jarak kecil
        val lb = layoutList(hs, smallTop, Config.gap3Dp * d)

        // jarak gulir maksimum keadaan 3: isi daftar yang melewati batas bawah
        scrollMaxPx = max(0f, lb.bottom[n - 1] - limit)
        if (q < 0.999f) scrollY = 0f
        scrollY = scrollY.coerceIn(0f, scrollMaxPx)
        val scroll = scrollY

        updateLabels(sv, a, targetTop, count)

        // titik asal animasi buka: dasar tumpukan keadaan 2, minimal originFrac (garis merah)
        var origin = dm.heightPixels * Config.originFrac
        for (i in 0 until n) origin = max(origin, la.bottom[i])

        val bottoms = FloatArray(n)
        val scales = FloatArray(n)
        composeStack(la, lb, a, q, Config.stagger, Config.openStagger, origin, bottoms, scales)

        // gulir + "dock": kartu yang melewati batas bawah tetap kecil dan menumpuk setebal dock.
        // Makin dekat ujung gulir, dock makin tipis, sehingga di ujung kartu terakhir tampil penuh di batas bawah.
        val dock = if (scrollMaxPx > 0f) min(Config.dockDp * d, scrollMaxPx - scroll) else 0f
        val fullLimit = limit - dock
        val ref = (Config.dockRefDp * d).coerceAtLeast(1f)
        val dScale = Config.dockScale.coerceIn(0f, 0.3f)
        for (i in 0 until n) {
            var b = bottoms[i] - scroll
            var sc = scales[i]
            if (q > 0f && scrollMaxPx > 0f) {
                val o = b - fullLimit
                if (o > 0f) {
                    val nb = if (dock < 1f) fullLimit else fullLimit + dock * (1f - exp(-o / (dock * 1.3f)))
                    val ns = sc * (1f - dScale * min(1f, o / ref))
                    b += (nb - b) * q
                    sc += (ns - sc) * q
                }
            }
            bottoms[i] = b
            scales[i] = sc
        }

        // info untuk debug: logcat | grep IOSLock
        val sb = StringBuilder("n=$n q=${"%.2f".format(q)} scroll=${scroll.toInt()}/${scrollMaxPx.toInt()}")

        var rightEdge = 0f
        for (i in 0 until n) {
            val card = cards[i].first
            val baseY = cards[i].second
            val baseZ = baseOfZ(card)
            val h = hs[i]
            val naturalCenter = loc[1] + baseY + h / 2f
            rightEdge = max(rightEdge, (loc[0] + card.right).toFloat())

            val kind = if (q < 0.5f) la.kind[i] else lb.kind[i]
            val s = scales[i]
            val center = bottoms[i] - h * s / 2f
            sb.append(" [").append(card.javaClass.simpleName.take(14)).append(" k=").append(kind)
                .append(" h=").append(card.height).append("/").append(h.toInt()).append("]")

            val wy = baseY + (center + slide - naturalCenter)
            // kartu yang lebih atas selalu di depan
            val wz = baseZ + (n - i) * 3f * d

            card.pivotX = card.width / 2f
            card.pivotY = h / 2f
            card.translationY = wy
            card.translationZ = wz
            card.scaleX = s
            card.scaleY = s
            // potong bagian atas kartu yang ter-scroll melewati garis atas daftar; fade-nya digambar oleh mask
            val csy = center + slide
            val iy0 = (h / 2f + (smallTop - csy) / s).toInt()
            card.clipBounds = if (q > 0f && iy0 > 0) {
                Rect(0, min(iy0, card.height), card.width, card.height)
            } else null
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz)
        }

        // slider: tepat di tengah celah antara tepi kartu dan tepi layar
        val gapCenter = if (rightEdge > 0f && rightEdge < dm.widthPixels - 8f * d) {
            (rightEdge + dm.widthPixels) / 2f
        } else dm.widthPixels - 6f * d

        // fade atas, dim wallpaper, slider
        updateMask(sv, q >= 0.999f && scroll > 0.5f, scroll, smallTop, d)
        updateDim(sv, q)
        updateSlider(sv, q, smallTop, limit, gapCenter)
        logT("cards", sb.toString())
    }

    // ---- label ----

    private fun makeLabel(ctx: Context, sp: Float, bold: Boolean, gravity: Int): TextView {
        val t = TextView(ctx)
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // tanpa bayangan hitam
        t.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        t.gravity = gravity
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
        val lc = makeLabel(ctx, Config.labelSp, true, Gravity.START)
        lc.text = "Notification Center"
        val ln = makeLabel(ctx, 14f, false, Gravity.CENTER_HORIZONTAL)
        root.addView(lc, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ln, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labelCenter = lc
        labelCount = ln
    }

    private fun updateLabels(sv: View, a: Float, targetTop: Float, count: Int) {
        val root = sv.rootView as? ViewGroup ?: return
        ensureLabels(root)
        val lc = labelCenter ?: return
        val ln = labelCount ?: return

        // layar mati atau layar PIN: kedua teks disembunyikan
        if (labelsBlocked(root)) {
            hideLabels()
            return
        }

        val dm = root.resources.displayMetrics
        val d = dm.density
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)

        // "Notification Center": rata kiri, muncul di atas daftar
        val a1 = ((a - 0.3f) / 0.5f).coerceIn(0f, 1f)
        lc.visibility = if (a1 > 0.01f) View.VISIBLE else View.INVISIBLE
        lc.alpha = a1
        lc.setTextSize(TypedValue.COMPLEX_UNIT_SP, Config.labelSp)
        lc.setPadding((Config.labelLeftDp * d).toInt(), 0, 0, 0)
        val hc = if (lc.height > 0) lc.height.toFloat() else 26f * d
        lc.translationY = targetTop - rl[1] - hc - 6f * d

        // "N Notification(s)": saat kartu belum dimunculkan (atau selalu, jika countalways 1)
        val a2 = if (count > 0) {
            if (Config.countAlways) 1f else (1f - a * 3f).coerceIn(0f, 1f)
        } else 0f
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
        hideSlider()
    }

    // ---- slider scroll (tepi kanan, keadaan 3) ----

    // hanya bagian putih (thumb); trek abu-abu dihilangkan
    private class SliderView(ctx: Context) : View(ctx) {
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var thumbTop = 0f
        private var thumbH = 0f

        fun setThumb(top: Float, h: Float) {
            if (top != thumbTop || h != thumbH) {
                thumbTop = top
                thumbH = h
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val r = w / 2f
            canvas.drawRoundRect(0f, thumbTop, w, thumbTop + thumbH, r, r, thumbPaint)
        }
    }

    private fun ensureSlider(root: ViewGroup) {
        if (slider?.parent === root) return
        (slider?.parent as? ViewGroup)?.removeView(slider)
        val sv = SliderView(root.context)
        sv.alpha = 0f
        sv.visibility = View.INVISIBLE
        sv.isClickable = false
        sv.isFocusable = false
        val d = root.resources.displayMetrics.density
        root.addView(sv, ViewGroup.LayoutParams(max(2, (Config.sliderDp * d).toInt()), (200f * d).toInt()))
        slider = sv
        sliderOn = false
    }

    // sembunyikan seketika (keluar dari keadaan 3, layar mati, dll)
    private fun hideSlider() {
        val s = slider
        sliderOn = false
        if (s == null) return
        s.removeCallbacks(sliderHideRunnable)
        s.animate().cancel()
        s.alpha = 0f
        s.visibility = View.INVISIBLE
    }

    // fade in / fade out
    private fun fadeSlider(on: Boolean) {
        val s = slider ?: return
        if (on == sliderOn) return
        sliderOn = on
        s.animate().cancel()
        if (on) {
            s.visibility = View.VISIBLE
            s.animate().alpha(1f).setDuration(Config.sliderFadeInMs).start()
        } else {
            s.animate().alpha(0f).setDuration(Config.sliderFadeOutMs).withEndAction {
                if (!sliderOn) s.visibility = View.INVISIBLE
            }.start()
        }
    }

    // dipicu saat daftar bergerak: tampil, lalu hilang lagi setelah sliderHideMs tanpa gerakan
    private fun pokeSlider(s: View) {
        s.removeCallbacks(sliderHideRunnable)
        fadeSlider(true)
        s.postDelayed(sliderHideRunnable, Config.sliderHideMs)
    }

    private fun updateSlider(sv: View, q: Float, top: Float, bottom: Float, centerX: Float) {
        val root = sv.rootView as? ViewGroup ?: return
        ensureSlider(root)
        val s = slider ?: return
        if (q < 0.999f || scrollMaxPx < 2f || labelsBlocked(root)) {
            hideSlider()
            lastScrollSeen = scrollY
            sliderPoke = false
            return
        }
        val dm = root.resources.displayMetrics
        val d = dm.density
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)

        val trackTop = top + 8f * d
        val trackH = (bottom - trackTop).coerceAtLeast(40f * d)
        val viewport = bottom - top
        val thumbH = max(36f * d, trackH * viewport / (viewport + scrollMaxPx)).coerceAtMost(trackH)
        val thumbTop = (trackH - thumbH) * (scrollY / scrollMaxPx).coerceIn(0f, 1f)

        val w = max(2, (Config.sliderDp * d).toInt())
        val h = trackH.toInt()
        val lp = s.layoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            s.requestLayout()
        }
        s.translationX = centerX - rl[0] - w / 2f
        s.translationY = trackTop - rl[1]
        (s as SliderView).setThumb(thumbTop, thumbH)

        sliderTrackTop = trackTop
        sliderTrackH = trackH
        sliderThumbH = thumbH

        if (sliderPoke || abs(scrollY - lastScrollSeen) > 0.5f) {
            sliderPoke = false
            pokeSlider(s)
        }
        lastScrollSeen = scrollY
    }

    // seret slider: posisi jari menentukan posisi gulir daftar
    private fun dragSlider(y: Float) {
        val range = (sliderTrackH - sliderThumbH).coerceAtLeast(1f)
        val frac = ((y - sliderTrackTop - sliderThumbH / 2f) / range).coerceIn(0f, 1f)
        scrollY = frac * scrollMaxPx
        sliderPoke = true
        applyState()
    }

    // ---- fade atas daftar (mask) ----

    // Hook dispatchDraw milik view stack: kartu digambar di layer terpisah, lalu bagian di atas garis dihapus
    // (DST_OUT) dan bagian tepat di bawah garis dibuat memudar setebal topFadeDp, jadi potongan atas tidak datar.
    // Kalau class stack tidak punya dispatchDraw sendiri, potongan atas tetap datar (clipBounds saja).
    private fun ensureMaskHook(v: View) {
        if (maskHooked) return
        maskHooked = true
        try {
            var c: Class<*>? = v.javaClass
            var target: Class<*>? = null
            while (c != null && c != ViewGroup::class.java && c != View::class.java) {
                if (c.declaredMethods.any { it.name == "dispatchDraw" }) { target = c; break }
                c = c.superclass
            }
            if (target == null) {
                log("mask: dispatchDraw tidak ada di ${v.javaClass.name}, potongan atas tetap datar")
                return
            }
            XposedBridge.hookAllMethods(target, "dispatchDraw", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val view = p.thisObject as? View ?: return
                        if (!maskOn || view !== stackRef?.get()) return
                        val canvas = p.args[0] as? Canvas ?: return
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val dm = view.resources.displayMetrics
                        val l = -loc[0].toFloat()
                        val t = -loc[1].toFloat()
                        val r = l + dm.widthPixels
                        val b = t + dm.heightPixels
                        val save = canvas.saveLayer(l, t, r, b, null)
                        p.setObjectExtra("ioslSave", save)
                        p.setObjectExtra("ioslRect", floatArrayOf(l, t, r, maskLine - loc[1], maskFade, maskStrength))
                    } catch (t: Throwable) { }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val save = p.getObjectExtra("ioslSave") as? Int ?: return
                        val rc = p.getObjectExtra("ioslRect") as? FloatArray ?: return
                        val canvas = p.args[0] as? Canvas ?: return
                        val l = rc[0]; val t = rc[1]; val r = rc[2]; val line = rc[3]
                        val fade = rc[4]; val st = rc[5]
                        // di atas garis: dihapus penuh
                        canvas.drawRect(l, t, r, line, maskSolid)
                        // di bawah garis: memudar dari transparan ke utuh
                        if (fade > 1f && st > 0.01f) {
                            val a = (255f * st).toInt().coerceIn(0, 255)
                            maskGrad.shader = LinearGradient(
                                0f, line, 0f, line + fade,
                                Color.argb(a, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP
                            )
                            canvas.drawRect(l, line, r, line + fade, maskGrad)
                        }
                        canvas.restoreToCount(save)
                    } catch (t: Throwable) { }
                }
            })
            maskReady = true
            log("mask hook on ${target.name}.dispatchDraw")
        } catch (t: Throwable) {
            log("mask hook gagal: $t")
        }
    }

    // on = ada kartu yang ter-scroll melewati garis atas. Kekuatan fade naik bertahap seiring jarak gulir.
    private fun updateMask(sv: View, on: Boolean, scroll: Float, line: Float, d: Float) {
        ensureMaskHook(sv)
        val fade = Config.topFadeDp * d
        val st = if (on && fade > 1f) (scroll / (fade * 0.8f)).coerceIn(0f, 1f) else 0f
        setMask(sv, on && maskReady, line, fade, st)
    }

    // Mask di luar lockscreen (home screen / shade): garis potong di bawah jam dan fade tipis (shadeLineFrac,
    // shadeFadeDp). Dipanggil dari applyState setiap kali bukan keadaan lockscreen, jadi sekaligus menimpa mask
    // keadaan 3 (garis smallFrac, fade topFadeDp) yang tadinya tertinggal saat kunci dibuka.
    private fun applyShadeMask(sv: View) {
        val dm = sv.resources.displayMetrics
        val line = dm.heightPixels * Config.shadeLineFrac
        if (!Config.gesture || line < 1f) {
            if (maskOn) setMask(sv, false, maskLine, maskFade, 0f)
            return
        }
        ensureMaskHook(sv)
        val fade = Config.shadeFadeDp * dm.density
        setMask(sv, maskReady, line, fade, 1f)
        logT("shadeMask", "line=${line.toInt()} fade=${fade.toInt()} on=$maskReady state=$barState", discrete = true)
    }

    // Tulis parameter mask; view digambar ulang kalau ada yang berubah (termasuk garis dan tebal fade,
    // supaya perpindahan lockscreen -> home langsung terlihat).
    private fun setMask(sv: View, active: Boolean, line: Float, fade: Float, st: Float) {
        val changed = active != maskOn || abs(st - maskStrength) > 0.01f ||
            abs(line - maskLine) > 0.5f || abs(fade - maskFade) > 0.5f
        maskOn = active
        maskLine = line
        maskFade = fade
        maskStrength = st
        if (changed) sv.invalidate()
    }

    // ---- dim wallpaper (keadaan 3) ----

    // View hitam transparan di posisi paling bawah window shade: hanya menggelapkan yang ada di belakang
    // (wallpaper), kartu dan teks tetap di atasnya.
    private fun ensureDim(root: ViewGroup): View {
        val cur = dimView
        if (cur != null && cur.parent === root) return cur
        (cur?.parent as? ViewGroup)?.removeView(cur)
        val v = View(root.context)
        v.setBackgroundColor(Color.BLACK)
        v.alpha = 0f
        v.visibility = View.INVISIBLE
        v.isClickable = false
        v.isFocusable = false
        root.addView(v, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dimView = v
        return v
    }

    private fun updateDim(sv: View, q: Float) {
        val root = sv.rootView as? ViewGroup ?: return
        val v = ensureDim(root)
        val amt = if (labelsBlocked(root)) 0f else (q * Config.dimAlpha).coerceIn(0f, 0.85f)
        if (amt < 0.005f) {
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
            v.alpha = 0f
        } else {
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            v.alpha = amt
        }
    }

    private fun hideDim() {
        dimView?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
    }

    private fun reset(sv: View?, ctx: Context?) {
        restoreGear(ctx)
        scrollY = 0f
        hideDim()
        hideLabels()
        restoreClip()
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
                c.clipBounds = null
                rowState.remove(c)
            }
        }
    }

    // ---- tombol "Reset UI" di menu power ----

    // Tiap window baru dipindai beberapa kali (isi menu bisa baru terpasang setelah addView).
    // Kalau berisi teks "Power Off" dan "Restart", tombol Reset UI ditempel di bawah deretan tombol.
    private fun schedulePowerScan(v: View) {
        val n = v.javaClass.name
        if (n.contains("NotificationShadeWindowView") || n.contains("NavigationBar") ||
            n.contains("StatusBarWindowView") || n.contains("ScreenDecor")) return
        for (delay in longArrayOf(0L, 150L, 400L, 900L)) {
            v.postDelayed({ tryInjectPowerButton(v) }, delay)
        }
        if (Config.pwrDump) v.postDelayed({ dumpTexts(v) }, 600L)
    }

    private fun dumpTexts(v: View) {
        val sb = StringBuilder()
        fun walk(x: View, depth: Int) {
            if (depth > 20 || sb.length > 1500) return
            if (x is TextView && x.text.isNotEmpty()) sb.append(" [").append(x.text).append("]")
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i), depth + 1)
        }
        walk(v, 0)
        if (sb.isNotEmpty()) log("window ${v.javaClass.simpleName}:$sb")
    }

    // kata label "power off" dan "restart" (huruf kecil), termasuk terjemahan dari resource sistem
    private fun powerWords(ctx: Context): Pair<Set<String>, Set<String>> {
        powerWordsCache?.let { return it }
        val off = HashSet(listOf("power off", "shut down", "matikan", "matikan daya"))
        val rst = HashSet(listOf("restart", "reboot", "mulai ulang", "mulai ulang perangkat"))
        fun add(set: MutableSet<String>, name: String) {
            for (pkg in arrayOf("com.android.systemui", "android")) {
                try {
                    val id = ctx.resources.getIdentifier(name, "string", pkg)
                    if (id != 0) set.add(ctx.getString(id).trim().lowercase())
                } catch (t: Throwable) { }
            }
        }
        add(off, "global_action_power_off")
        add(rst, "global_action_restart")
        val r = Pair<Set<String>, Set<String>>(off, rst)
        powerWordsCache = r
        return r
    }

    private fun findLabel(v: View, depth: Int, words: Set<String>): TextView? {
        if (depth > 20) return null
        if (v is TextView && v.isShown) {
            val t = v.text?.toString()?.trim()?.lowercase()
            if (t != null && t in words) return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findLabel(v.getChildAt(i), depth + 1, words)
                if (r != null) return r
            }
        }
        return null
    }

    // cari view bulat (ikon tombol) di dalam satu item, untuk meniru ukurannya
    private fun findRound(v: ViewGroup, depth: Int, d: Float): View? {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c !is TextView && c.width >= 40f * d && c.width <= 130f * d && abs(c.width - c.height) <= 4f * d) return c
            if (c is ViewGroup && depth < 3) findRound(c, depth + 1, d)?.let { return it }
        }
        return null
    }

    private fun tryInjectPowerButton(root: View) {
        try {
            if (!Config.powerBtn || !root.isAttachedToWindow) return
            val g = root as? ViewGroup ?: return
            if (g.findViewWithTag<View>(pwrTag) != null) return
            val (offWords, rstWords) = powerWords(root.context)
            val ref = findLabel(g, 0, rstWords) ?: return
            if (findLabel(g, 0, offWords) == null) return
            if (ref.width == 0 || ref.height == 0) return
            injectResetButton(g, ref)
        } catch (t: Throwable) {
            once("pwrFail") { log("tombol power gagal: $t") }
        }
    }

    private fun injectResetButton(g: ViewGroup, ref: TextView) {
        val host = g as? FrameLayout
        if (host == null) {
            once("pwrNoFrame") { log("tombol power: root ${g.javaClass.name} bukan FrameLayout, dilewati") }
            return
        }
        val ctx = g.context
        val d = ctx.resources.displayMetrics.density
        val dmW = ctx.resources.displayMetrics.widthPixels
        val rl = IntArray(2)
        ref.getLocationOnScreen(rl)

        // satu item (ikon + teks) = induk terdekat label yang lebih tinggi dari label dan bukan selebar layar
        var item: ViewGroup? = null
        var up = ref.parent as? View
        var steps = 0
        while (up != null && up !== g && steps < 3) {
            if (up is ViewGroup && up.height >= ref.height * 1.8f && up.width < dmW * 0.7f) { item = up; break }
            up = up.parent as? View
            steps++
        }
        var circle = (68f * d).toInt()
        var gap = (12f * d).toInt()
        val iconView = item?.let { findRound(it, 0, d) }
        if (iconView != null) {
            circle = iconView.width
            val cl = IntArray(2)
            iconView.getLocationOnScreen(cl)
            gap = (rl[1] - (cl[1] + iconView.height)).coerceIn((4f * d).toInt(), (40f * d).toInt())
        }
        val rowGap = 30f * d

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.tag = pwrTag
        box.isClickable = true
        box.addView(ResetUiIconView(ctx), LinearLayout.LayoutParams(circle, circle))

        val lbl = TextView(ctx)
        lbl.text = RESET_UI_LABEL
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_PX, ref.textSize)
        lbl.typeface = ref.typeface
        lbl.setTextColor(ref.textColors)
        lbl.gravity = Gravity.CENTER
        lbl.includeFontPadding = ref.includeFontPadding
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = gap
        box.addView(lbl, lp)

        box.setOnClickListener { requestRestartUi(ctx) }
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }

        host.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        placeResetButton(host, box, ref, rowGap)

        // ikuti label Restart tiap frame (posisi, fade-in, animasi masuk menu)
        val vto = host.viewTreeObserver
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                try {
                    if (!box.isAttachedToWindow || !ref.isAttachedToWindow) {
                        val o = host.viewTreeObserver
                        if (o.isAlive) o.removeOnPreDrawListener(this)
                        return true
                    }
                    placeResetButton(host, box, ref, rowGap)
                } catch (t: Throwable) { }
                return true
            }
        })
        log("tombol Reset UI dipasang di ${host.javaClass.simpleName} (circle=${circle}px gap=${gap}px)")
    }

    // letakkan tombol: rata tengah layar, tepat di bawah label Restart
    private fun placeResetButton(host: ViewGroup, box: View, ref: View, rowGap: Float) {
        val rl = IntArray(2)
        ref.getLocationOnScreen(rl)
        val hl = IntArray(2)
        host.getLocationOnScreen(hl)
        val w = if (box.width > 0) box.width else box.measuredWidth
        box.translationX = (host.width - w) / 2f - host.paddingLeft
        box.translationY = rl[1] + ref.height + rowGap - hl[1] - host.paddingTop
        var a = 1f
        var v: View? = ref
        while (v != null && v !== host) { a *= v.alpha; v = v.parent as? View }
        box.alpha = a
        box.visibility = if (ref.isShown) View.VISIBLE else View.INVISIBLE
    }

    // Reset System UI: jalur utama lewat root (broadcast ke aplikasi modul, yang menjalankan su -c killall).
    // Cadangan: kalau root tidak ada / ditolak, System UI mematikan dirinya sendiri dan langsung dibangkitkan
    // lagi oleh sistem (proses persistent).
    private fun requestRestartUi(ctx: Context) {
        log("reset System UI diminta dari menu power")
        try {
            val i = Intent(RESTART_ACTION)
            i.component = ComponentName(MODULE_PKG, "$MODULE_PKG.RestartUiReceiver")
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            ctx.sendBroadcast(i)
        } catch (t: Throwable) {
            log("broadcast reset gagal: $t")
        }
        val fb = Config.restartFallbackMs
        if (fb > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                log("jalur root tidak menjawab, System UI mematikan dirinya sendiri")
                Process.killProcess(Process.myPid())
            }, fb)
        }
    }

    // ikon tombol: lingkaran kaca + bingkai layar dengan panah putar di dalamnya
    private class ResetUiIconView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(46, 255, 255, 255)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            color = Color.argb(96, 255, 255, 255)
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val box = RectF()
        private val tri = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) / 2f - ring.strokeWidth
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)

            // bingkai "layar"
            val half = r * 0.52f
            box.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(box, r * 0.16f, r * 0.16f, ink)

            // panah putar di dalam bingkai (busur 290 derajat, ujungnya di 230 derajat)
            val ar = half * 0.52f
            box.set(cx - ar, cy - ar, cx + ar, cy + ar)
            canvas.drawArc(box, -60f, 290f, false, ink)
            val th = Math.toRadians(230.0)
            val ex = cx + ar * cos(th).toFloat()
            val ey = cy + ar * sin(th).toFloat()
            val tx = (-sin(th)).toFloat()   // arah gerak (searah jarum jam)
            val ty = cos(th).toFloat()
            val nx = cos(th).toFloat()      // arah keluar dari pusat
            val ny = sin(th).toFloat()
            val hl = 4.6f * d
            val hw = 3.4f * d
            tri.reset()
            tri.moveTo(ex + tx * hl, ey + ty * hl)
            tri.lineTo(ex + nx * hw, ey + ny * hw)
            tri.lineTo(ex - nx * hw, ey - ny * hw)
            tri.close()
            canvas.drawPath(tri, head)
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
