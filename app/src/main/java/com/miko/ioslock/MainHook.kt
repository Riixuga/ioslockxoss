package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import android.os.PowerManager
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

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
    val listBottomFrac: Float get() = prop("debug.ioslock.listbottom").toFloatOrNull() ?: 0.83f
    // keadaan 2: posisi tepi bawah pita tumpukan TERAKHIR (0..1 tinggi layar) = garis merah.
    // Ketebalan pita dihitung otomatis supaya dasar tumpukan tepat di sini.
    val bottom2Frac: Float get() = prop("debug.ioslock.bottom2").toFloatOrNull() ?: 0.807f
    // pita tidak boleh lebih tebal dari sekian kali tinggi kartu (supaya tetap menumpuk di belakang kartu depan, tanpa celah)
    val peekMaxFrac: Float get() = prop("debug.ioslock.peekmaxfrac").toFloatOrNull() ?: 0.95f
    // ketebalan minimal sebuah kartu yang menumpuk (dp)
    val minPeekDp: Float get() = prop("debug.ioslock.minpeekdp").toFloatOrNull() ?: 12f
    // meluncur tambahan saat kartu muncul; 0 = kartu keluar dari dasar tumpukan saja (lihat originFrac)
    val slideDp: Float get() = prop("debug.ioslock.slidedp").toFloatOrNull() ?: 0f
    // animasi buka tumpukan (keadaan 1 -> 2): semua kartu keluar dari satu titik di dasar tumpukan
    // (di atas teks "N Notifications"), kartu depan lebih dulu, kartu terakhir tetap di tempatnya.
    // originFrac = tepi bawah titik asal itu (0..1 tinggi layar; dipakai kalau dasar tumpukan keadaan 2
    // lebih tinggi dari ini, misalnya hanya 1-2 notifikasi). openStagger = seberapa bergantian (0 = serentak, maks 0.8)
    val originFrac: Float get() = prop("debug.ioslock.originfrac").toFloatOrNull() ?: 0.80f
    val openStagger: Float get() = prop("debug.ioslock.openstagger").toFloatOrNull() ?: 0.35f
    // keadaan 2: jumlah kartu yang tampil penuh (sisanya menumpuk di belakang)
    val full2: Int get() = prop("debug.ioslock.full2").toIntOrNull() ?: 1
    // keadaan 2: jumlah pita tumpukan yang terlihat di bawah kartu penuh terakhir
    val bands2: Int get() = prop("debug.ioslock.bands2").toIntOrNull() ?: 2
    // keadaan 3: tumpukan bertingkat tanpa celah dengan jarak SERAGAM antar kartu.
    // Jarak kartu 1 -> kartu 2 (A ke B) dipakai untuk semua kartu berikutnya.
    // cascadeMax = jumlah kartu yang terlihat (sisanya bersembunyi di belakang yang terakhir)
    // cascadeCap = jarak A ke B sebagai bagian dari tinggi kartu ke-2 (1 = penuh). Kalau tumpukan tidak muat
    //   sampai batas bawah (listBottomFrac), semua jarak dikecilkan sama rata.
    // scale3Step = pengecilan skala tiap tingkat, overlapDp = tumpang tindih minimal antar kartu (tidak pernah ada celah)
    val cascadeMax: Int get() = prop("debug.ioslock.cascademax").toIntOrNull() ?: 30
    val cascadeCap: Float get() = prop("debug.ioslock.cascadecap").toFloatOrNull() ?: 0.86f
    val scale3Step: Float get() = prop("debug.ioslock.scale3step").toFloatOrNull() ?: 0.03f
    val overlapDp: Float get() = prop("debug.ioslock.overlapdp").toFloatOrNull() ?: 8f
    // swipe naik keadaan 2 -> 3: kartu keluar dari tumpukan satu per satu (kartu depan lebih dulu).
    // 0 = semua kartu renggang bersamaan, makin besar makin bergantian (maksimum 0.8)
    val stagger: Float get() = prop("debug.ioslock.stagger").toFloatOrNull() ?: 0.3f
    // tiap pita tumpukan lebih tipis dari pita di depannya (1 = sama tebal),
    // dan pengecilan skala tiap pita (0.05 = 5% per pita)
    val bandDecay: Float get() = prop("debug.ioslock.banddecay").toFloatOrNull() ?: 0.8f
    val scaleStep: Float get() = prop("debug.ioslock.scalestep").toFloatOrNull() ?: 0.05f
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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        log("v0.21 loaded into SystemUI")

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

        // State status bar: hanya ambil alih saat KEYGUARD (bukan shade / QS di lockscreen)
        hook(cl, stateCtl, "setState", after = { p ->
            val s = p.args.getOrNull(0) as? Int ?: return@hook
            if (s == 1 && barState != 1) {
                animator?.cancel()
                progress = 0f
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
                                bouncerHook = false
                                lastGearWritten = null
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
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0 && isReal(c)) return true
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

    // jumlah notifikasi: semua kartu (termasuk kartu musik dan senter); grup dihitung per anak
    private fun notifCount(g: ViewGroup): Int {
        var n = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.height > 0 && c.width > 0 && c.visibility != View.GONE && isReal(c)) {
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
                if (!locked) progress = 0f
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

    // Tata letak keadaan 3: tumpukan bertingkat tanpa celah dengan jarak SERAGAM.
    // Kartu 0 tampil penuh. Tiap kartu di bawahnya berada di belakang kartu di atasnya, dan jarak tepi bawah
    // antar kartu sama dengan jarak kartu A -> kartu B (step = tinggi kartu ke-2 * capBase, dengan tumpang
    // tindih minimal overlapMin). Jadi semua kartu tersusun rapi dengan jarak yang sama, skalanya mengecil
    // sedikit tiap tingkat. Kalau tumpukan tidak muat sampai limit, semua jarak dikecilkan sama rata
    // (lam < 1); kalau masih tidak muat, jumlah kartu yang terlihat dikurangi.
    private fun layoutCascade(
        hs: FloatArray, top: Float, limit: Float, minPeek: Float, overlapMin: Float,
        maxVisible: Int, capBase: Float, scaleStep: Float
    ): StateLayout {
        val n = hs.size
        val out = StateLayout(n)
        fun sc(i: Int): Float = 1f - scaleStep * min(i, 4)
        out.bottom[0] = top + hs[0]
        out.scale[0] = 1f
        out.kind[0] = 0
        if (n == 1) return out

        // tebal terlihat paling tipis (pita)
        fun lo(i: Int): Float = min(minPeek, hs[i] * sc(i))

        // jarak A -> B: dipakai untuk semua kartu
        val step = max(min(hs[1] * sc(1) * capBase, hs[1] * sc(1) - overlapMin), lo(1))
        // batas atas jarak kartu i (tidak boleh melebihi tinggi kartu itu sendiri, supaya tidak ada celah)
        fun hi(i: Int): Float = max(min(step, hs[i] * sc(i) - overlapMin), lo(i))

        val room = limit - out.bottom[0]

        // jumlah kartu yang terlihat: dikurangi sampai semuanya muat dengan ketebalan minimum
        var m = min(n, max(maxVisible, 1))
        while (m > 1) {
            var s = 0f
            for (i in 1 until m) s += lo(i)
            if (s <= room) break
            m--
        }

        fun t(i: Int, lam: Float): Float = (lam * step).coerceIn(lo(i), hi(i))
        fun total(lam: Float): Float {
            var s = 0f
            for (i in 1 until m) s += t(i, lam)
            return s
        }

        // lam = 1: jarak penuh seperti A -> B. Kalau tidak muat, kecilkan semua jarak sama rata (bisection)
        var lam = 1f
        if (total(1f) > room) {
            var l = 0f
            var h = 1f
            for (iter in 0 until 28) {
                val mid = (l + h) / 2f
                if (total(mid) > room) h = mid else l = mid
            }
            lam = l
        }

        for (i in 1 until n) {
            if (i < m) {
                out.bottom[i] = out.bottom[i - 1] + t(i, lam)
                out.scale[i] = sc(i)
                out.kind[i] = 1
            } else {
                // kartu yang tidak terlihat bersembunyi di belakang kartu terakhir yang terlihat
                out.bottom[i] = out.bottom[m - 1]
                out.scale[i] = sc(m)
                out.kind[i] = 2
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
            val sO = la.scale[n - 1]
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

    // a: 0..1 animasi buka tumpukan: semua kartu keluar bergantian dari dasar tumpukan (kartu depan dulu)
    // q: 0..1 jam mengecil dan daftar naik
    // Keadaan 2 (q=0): hanya kartu depan penuh, sisanya menumpuk di belakangnya; dasar pita terakhir
    // tepat di bottom2Frac (garis merah).
    // Keadaan 3 (q=1): semua kartu tersusun dengan jarak seragam seperti kartu A ke B; kartu atas penuh,
    // tiap kartu di bawahnya mengecil sedikit, berakhir paling jauh di batas bawah (listBottomFrac).
    // Kartu yang menumpuk adalah kartu asli (tanpa clip, tanpa disembunyikan): digeser ke belakang
    // kartu di atasnya dan diperkecil, sehingga hanya bagian bawahnya yang menyembul.
    // Tata letak dua keadaan dihitung terpisah, lalu composeStack menggabungkannya dengan a dan q.
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
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)

        val cards = ArrayList<Pair<View, Float>>()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0 && isReal(c)) {
                cards.add(Pair(c, baseOfY(c)))
            }
        }
        cards.sortBy { it.second }
        val n = cards.size

        val restTop = dm.heightPixels * Config.restFrac
        val smallTop = dm.heightPixels * Config.smallFrac
        val targetTop = restTop + (smallTop - restTop) * q

        updateLabels(sv, a, targetTop, notifCount(g))
        if (n == 0) return

        val limit = dm.heightPixels * Config.listBottomFrac
        val bottom2 = dm.heightPixels * Config.bottom2Frac
        val minPeek = Config.minPeekDp * d
        val slide = (1f - a) * Config.slideDp * d

        val baseYs = FloatArray(n) { cards[it].second }
        val hs = FloatArray(n) { visH(cards[it].first) }
        val decay = Config.bandDecay.coerceIn(0.3f, 1f)
        val step = Config.scaleStep.coerceIn(0f, 0.12f)
        val la = layoutState(baseYs, hs, restTop, 0f, Config.full2, Config.bands2, bottom2,
            minPeek, Config.peekMaxFrac.coerceIn(0.3f, 1f), decay, step)
        val lb = layoutCascade(hs, smallTop, limit, minPeek, Config.overlapDp * d, Config.cascadeMax,
            Config.cascadeCap.coerceIn(0.2f, 1f), Config.scale3Step.coerceIn(0f, 0.08f))

        // titik asal animasi buka: dasar tumpukan (tepi bawah kartu terakhir di keadaan 2),
        // minimal originFrac supaya tetap di dekat teks "N Notifications" walau kartunya sedikit
        var origin = dm.heightPixels * Config.originFrac
        for (i in 0 until n) origin = max(origin, la.bottom[i])

        val bottoms = FloatArray(n)
        val scales = FloatArray(n)
        composeStack(la, lb, a, q, Config.stagger, Config.openStagger, origin, bottoms, scales)

        // info untuk debug: logcat | grep IOSLock
        val sb = StringBuilder("n=$n q=${"%.2f".format(q)}")

        for (i in 0 until n) {
            val card = cards[i].first
            val baseY = cards[i].second
            val baseZ = baseOfZ(card)
            val h = hs[i]
            val naturalCenter = loc[1] + baseY + h / 2f

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
            card.clipBounds = null
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz)
        }
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
    }

    private fun reset(sv: View?, ctx: Context?) {
        restoreGear(ctx)
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
