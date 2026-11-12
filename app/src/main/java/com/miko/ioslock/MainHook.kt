package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RecordingCanvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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

    // baca system property (untuk log diagnosa)
    fun sys(key: String): String = prop(key)

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
    // keadaan 2: posisi tepi bawah tumpukan (0..1 tinggi layar) = garis merah. Default SAMA dengan batas bawah
    // keadaan 3 (listBottomFrac), jadi kartu paling bawah berhenti di garis yang sama di kedua keadaan.
    val bottom2Frac: Float get() = prop("debug.ioslock.bottom2").toFloatOrNull() ?: listBottomFrac
    // pita tidak boleh lebih tebal dari sekian kali tinggi kartu (supaya tetap menumpuk di belakang kartu depan, tanpa celah)
    val peekMaxFrac: Float get() = prop("debug.ioslock.peekmaxfrac").toFloatOrNull() ?: 0.9f
    // ketebalan minimal sebuah kartu yang menumpuk (dp)
    val minPeekDp: Float get() = prop("debug.ioslock.minpeekdp").toFloatOrNull() ?: 12f
    // meluncur tambahan saat kartu muncul; 0 = kartu keluar dari dasar tumpukan saja (lihat originFrac)
    val slideDp: Float get() = prop("debug.ioslock.slidedp").toFloatOrNull() ?: 0f
    // animasi buka tumpukan (keadaan 1 -> 2): semua kartu keluar dari satu titik di dasar tumpukan
    // (di atas teks "N Notifications"), kartu depan lebih dulu, kartu terakhir tetap di tempatnya.
    // Kalau pil nyala (pill 1), titik asal = pil itu sendiri (posisi dan ukurannya); originFrac dan openScale
    // di bawah hanya dipakai kalau pil dimatikan.
    // originFrac = tepi bawah titik asal itu (0..1 tinggi layar). openStagger = seberapa bergantian (0 = serentak, maks 0.8)
    val originFrac: Float get() = prop("debug.ioslock.originfrac").toFloatOrNull() ?: 0.855f
    val openStagger: Float get() = prop("debug.ioslock.openstagger").toFloatOrNull() ?: 0.35f
    // skala awal kartu saat tumpukan dibuka (kecil, di garis asal), lalu membesar ke ukuran keadaan 2
    val openScale: Float get() = prop("debug.ioslock.openscale").toFloatOrNull() ?: 0.55f
    // keadaan 2 -> 3: kartu ke-4 dan seterusnya tetap di posisi awal sampai kartu di depannya
    // mencapai lockAt (0..1 dari gerakan kartu itu). Makin kecil = makin cepat kartu berikutnya ikut bergerak.
    val lockAt: Float get() = prop("debug.ioslock.lockat").toFloatOrNull() ?: 0.5f
    // keadaan 2: jumlah kartu penuh (kartu depan), ini batas maksimum; kalau tidak muat sampai garis (mis. ada
    // media player) jumlahnya dikurangi otomatis. Kartu sisanya menumpuk di belakang kartu penuh terakhir seperti dock stage 3.
    val full2: Int get() = prop("debug.ioslock.full2").toIntOrNull() ?: 3
    // keadaan 2: jumlah pita tumpukan yang terlihat di bawah kartu penuh terakhir (dipakai kalau dockstack2 0)
    val bands2: Int get() = prop("debug.ioslock.bands2").toIntOrNull() ?: 2
    // keadaan 2: kalau jumlah kartu <= noStack2, tumpukan dihilangkan. 0 = selalu menumpuk
    val noStack2: Int get() = prop("debug.ioslock.nostack2").toIntOrNull() ?: 0
    val gap2Dp: Float get() = prop("debug.ioslock.gap2dp").toFloatOrNull() ?: 10f
    // keadaan 2: skala kartu depan pada mode dock (1 = normal). Hanya berlaku kalau ada kartu di belakangnya.
    val front2Scale: Float get() = prop("debug.ioslock.front2scale").toFloatOrNull() ?: 1f
    // keadaan 2: mode dock (kartu di belakang kartu penuh menumpuk seperti stage 3). Default nyala.
    // Matikan dengan: setprop debug.ioslock.dockstack2 0
    val dockStack2: Boolean get() = prop("debug.ioslock.dockstack2") != "0"
    // keadaan 2: kalau kartu penuh kurang muat, jarak antar kartu boleh negatif sampai sekian dp (kartu menumpuk
    // sedikit di belakang kartu di depannya). Kalau masih kurang, jumlah kartu penuh dikurangi: kartu di bawahnya
    // menumpuk sebagai S, sama seperti notifikasi lain (mis. kalau kartu depan adalah media player yang tinggi).
    val overlap2Dp: Float get() = prop("debug.ioslock.overlap2dp").toFloatOrNull() ?: 12f
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

    // pil kaca (blur) di belakang teks "N Notifications" (matikan: setprop debug.ioslock.pill 0).
    // Saat nyala, kartu keluar dari pil ini dan mengecil kembali ke pil: posisi tengah dan ukuran kartu
    // di awal animasi buka = posisi tengah dan ukuran pil.
    val pillOn: Boolean get() = prop("debug.ioslock.pill") != "0"
    // radius blur (px) dan tint putih (alpha 0..255) pil. Blur platform memakai nilai ini apa adanya,
    // blur wallpaper memakai setengahnya sebagai sigma.
    val pillBlurPx: Float get() = prop("debug.ioslock.pillblur").toFloatOrNull() ?: 60f
    val pillTintA: Int get() = prop("debug.ioslock.pilltint").toIntOrNull() ?: 56
    // jarak teks ke tepi pil: kiri-kanan dan atas-bawah (dp)
    val pillPadHDp: Float get() = prop("debug.ioslock.pillpadh").toFloatOrNull() ?: 16f
    val pillPadVDp: Float get() = prop("debug.ioslock.pillpadv").toFloatOrNull() ?: 7f
    // radius sudut pil (dp). Negatif = bulat penuh (setengah tinggi pil)
    val pillRadiusDp: Float get() = prop("debug.ioslock.pillradius").toFloatOrNull() ?: -1f
    // cara blur pil: 0 = otomatis (urutan: blur XOS -> blur wallpaper -> blur platform -> kaca biasa),
    // 1 = blur platform saja (blur belakang-window lewat SurfaceFlinger, tidak ada hasilnya kalau ROM mematikannya),
    // 2 = blur wallpaper saja, 3 = kaca putih transparan tanpa blur,
    // 4 = blur XOS (DepthUtil) saja
    val pillMode: Int get() = prop("debug.ioslock.pillmode").toIntOrNull() ?: 0
    // kalibrasi blur wallpaper kalau isi pil tidak pas dengan wallpaper asli: zoom (1 = pas center-crop)
    // dan geser horizontal / vertikal (px)
    val pillWpZoom: Float get() = prop("debug.ioslock.pillwpzoom").toFloatOrNull() ?: 1f
    val pillWpDx: Float get() = prop("debug.ioslock.pillwpdx").toFloatOrNull() ?: 0f
    val pillWpDy: Float get() = prop("debug.ioslock.pillwpdy").toFloatOrNull() ?: 0f
    // lama animasi pil muncul (ms): tumbuh dari kecil, melewati ukuran penuh sedikit, lalu menetap. 0 = tanpa animasi
    val pillBounceMs: Long get() = prop("debug.ioslock.pillbounce").toLongOrNull() ?: 650L
    // blur XOS: nama class (bisa diganti lewat setprop tanpa build ulang) dan argumen angka/boolean untuk
    // DepthUtil.enableEffectRowBlur. Nilai default argumen belum tentu cocok, lihat log "xos:" untuk signature aslinya.
    val xosDepthClass: String get() = prop("debug.ioslock.xosdepth").ifEmpty { "GANTI.DENGAN.PACKAGE.DepthUtil" }
    val xosTranClass: String get() = prop("debug.ioslock.xostran").ifEmpty { "GANTI.DENGAN.PACKAGE.TranView" }
    val pillXosBool: Boolean get() = prop("debug.ioslock.xosbool") != "0"
    val pillXosInt: Int get() = prop("debug.ioslock.xosint").toIntOrNull() ?: 0
    val pillXosFloat: Float get() = prop("debug.ioslock.xosfloat").toFloatOrNull() ?: 0f
    // 1 = catat background tiap bagian kartu notifikasi (untuk mencari tahu blur apa yang dipakai kartu)
    val cardBgDump: Boolean get() = prop("debug.ioslock.cardbgdump") != "0"

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

    // posisi asli tiap kartu: [dasarY, tulisY, dasarZ, tulisZ, tulisX]
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

    // pil kaca di belakang teks "N Notifications"
    private var pill: PillView? = null
    private var pillBlur: Drawable? = null
    private var pillBlurTries = 0
    private var pillLastBlur = -1
    private var pillLastTint = -1
    private var pillLastRad = -1f
    // geometri pil terakhir dalam piksel layar: [left, top, lebar, tinggi]
    private var pillGeo = FloatArray(4)
    // wallpaper kunci (sudah diperkecil) untuk blur pil
    @Volatile private var wpBitmap: Bitmap? = null
    @Volatile private var wpLoading = false
    private var wpTries = 0

    // ClassLoader System UI (untuk mencari class XOS lewat refleksi)
    private var sysCl: ClassLoader? = null
    private val xosDepthName: String get() = Config.xosDepthClass
    private val xosTranName: String get() = Config.xosTranClass

    // animasi muncul pil
    private var pillWasRest = false
    private var pillLastBounce = 0L
    private var pillBounceAnim: ValueAnimator? = null
    @Volatile private var pillBounceS = 1f
    @Volatile private var pillBounceA = 1f

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
        sysCl = cl
        log("v0.39 loaded into SystemUI")

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
            if (s != 1) clearMask(stackRef?.get())   // keluar dari keyguard = fade dimatikan
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
            f.addAction(Intent.ACTION_WALLPAPER_CHANGED)
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
                            Intent.ACTION_WALLPAPER_CHANGED -> {
                                // wallpaper berganti: baca ulang untuk blur pil
                                wpBitmap = null
                                wpTries = 0
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

    // debug: class background tiap bagian kartu (sekali saja), untuk mencari tahu blur apa yang dipakai kartu
    // (logcat | grep IOSLock | grep cardBg)
    private fun logCardBg(c: View) {
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 3 || sb.length > 900) return
            val bg = v.background
            if (bg != null) {
                sb.append(" [").append(v.javaClass.simpleName).append(':').append(bg.javaClass.name).append(']')
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(c, 0)
        log("cardBg ${c.javaClass.simpleName}:$sb")
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
            sliderDrag = sliderOn && ev.rawX >= dm.widthPixels - 56f * dd &&
                ev.rawY >= sliderTrackTop - 24f * dd && ev.rawY <= sliderTrackTop + sliderTrackH + 24f * dd
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
                clearMask(sv)
                if (applied) { reset(sv, ctx); applied = false }
                if (!locked) { progress = 0f; scrollY = 0f }
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

    // Tata letak keadaan 2 (mode pita, dipakai kalau dockstack2 0): kartu depan penuh, sisanya menumpuk
    // di belakangnya sebagai pita. limit: posisi tepi bawah pita TERAKHIR (garis merah).
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

    // Tempatkan kartu S (semua kartu di belakang kartu penuh / pita terakhir) sebagai tumpukan tipis di bawah baseBottom.
    // Tepi bawah kartu TERAKHIR dipaskan persis di limit, yaitu garis bawah yang sama dengan keadaan 3,
    // berapa pun tinggi kartu depannya. Kartu S di antaranya menumpuk dengan kurva yang sama seperti dock keadaan 3.
    // Kalau ruangnya kurang dari minDock, tumpukan tetap setebal minDock supaya masih terlihat.
    private fun placeDock(
        out: StateLayout, from: Int, baseBottom: Float, baseScale: Float, hs: FloatArray, gap: Float,
        limit: Float, minDock: Float, ref: Float, dScale: Float
    ) {
        val n = hs.size
        if (from >= n) return
        val off = FloatArray(n)
        var o = 0f
        for (i in from until n) {
            o += hs[i] + gap
            off[i] = o
        }
        val dock = max(limit - baseBottom, minDock)
        val c = dock * 1.3f
        val lastRaw = 1f - exp(-off[n - 1] / c)
        for (i in from until n) {
            val raw = 1f - exp(-off[i] / c)
            out.bottom[i] = baseBottom + dock * (if (lastRaw > 0.001f) raw / lastRaw else raw)
            out.scale[i] = baseScale * (1f - dScale * min(1f, off[i] / ref))
            out.kind[i] = 1
        }
    }

    // Tata letak keadaan 2 gaya "dock": 'full' kartu depan tampil penuh (100%), semua kartu sesudahnya (S)
    // menumpuk tipis di bawah kartu penuh terakhir dan kartu paling bawah berakhir PERSIS di limit (lihat placeDock).
    // Kartu penuh TIDAK pernah dinaikkan: posisi atasnya tetap di top. Kalau totalnya lebih tinggi dari ruang
    // yang tersedia (sampai limit, dikurangi minDock), jarak antar kartu penuh dikecilkan; kalau masih kurang,
    // jaraknya boleh negatif sampai overlapMax (kartu menumpuk sedikit di belakang kartu di depannya).
    // Kalau masih kurang juga, applyStack mengurangi jumlah kartu penuh (full), jadi kartu di bawahnya jadi S.
    // frontScale: skala kartu penuh terakhir (di depan tumpukan), hanya kalau memang ada kartu di belakangnya.
    private fun layoutDockStack(
        hs: FloatArray, top: Float, gap: Float, full: Int,
        limit: Float, ref: Float, dScale: Float, frontScale: Float, minDock: Float, overlapMax: Float
    ): StateLayout {
        val n = hs.size
        val out = StateLayout(n)
        val k = full.coerceIn(1, n)

        var sumH = 0f
        for (i in 0 until k) sumH += hs[i]
        val reserve = if (n > k) minDock else 0f
        val avail = limit - reserve - top
        val g = if (k > 1) ((avail - sumH) / (k - 1)).coerceIn(-overlapMax, gap) else gap

        var y = top
        for (i in 0 until k) {
            y += hs[i]
            out.bottom[i] = y
            // kartu penuh terakhir hanya membesar kalau memang ada kartu di belakangnya
            out.scale[i] = if (i == k - 1 && n > k) frontScale else 1f
            out.kind[i] = 0
            y += g
        }
        placeDock(out, k, out.bottom[k - 1], 1f, hs, gap, limit, minDock, ref, dScale)
        return out
    }

    // Apakah 'full' kartu depan masih muat dari top sampai limit (dengan sisa minDock untuk tumpukan S kalau
    // masih ada kartu di belakangnya), setelah jarak antar kartu dikecilkan sampai -overlapMax.
    private fun dockFits(
        hs: FloatArray, top: Float, full: Int, limit: Float, minDock: Float, overlapMax: Float
    ): Boolean {
        val n = hs.size
        val k = full.coerceIn(1, n)
        var sum = 0f
        for (i in 0 until k) sum += hs[i]
        val avail = limit - (if (n > k) minDock else 0f) - top
        return if (k > 1) (avail - sum) / (k - 1) >= -overlapMax else sum <= avail
    }

    // Seberapa jauh kartu ke-i sudah keluar dari titik asal pada kemajuan a (0..1) animasi buka tumpukan:
    // kartu depan keluar lebih dulu, kartu di belakangnya menyusul, kartu terakhir paling akhir.
    private fun openProgress(i: Int, n: Int, a: Float, lag: Float): Float {
        val l = if (n > 1) lag.coerceIn(0f, 0.8f) else 0f
        val start = if (n > 1) l * i / (n - 1) else 0f
        val t = ((a - start) / (1f - l)).coerceIn(0f, 1f)
        return t + (t * t * (3f - 2f * t) - t) * 0.5f
    }

    // Gabungkan keadaan 2 (la) dan keadaan 3 (lb) jadi posisi tiap kartu pada kemajuan (a, q).
    // Kartu ke-1..3 (index 0..2) bergerak bertahap dan tetap berurutan.
    // Kartu ke-4 dan seterusnya TERKUNCI di posisi keadaan 2 (la) sampai kartu di depannya mencapai
    // lockAt dari geraknya; lalu mulai bergerak sendiri ke posisi keadaan 3 (lb).
    // Saat a < 1 (animasi buka tumpukan, keadaan 1 -> 2) semua kartu berangkat dari satu titik asal
    // (origin = tepi bawah pil, atau dasar tumpukan kalau pil mati) lalu keluar bergantian ke posisinya di keadaan 2.
    // outOpen = seberapa jauh kartu sudah keluar dari titik asal (0 = masih di pil, 1 = sudah di posisi keadaan 2);
    // ukuran dan geser horizontal kartu saat animasi buka dihitung di applyStack dari nilai ini.
    private fun composeStack(
        la: StateLayout, lb: StateLayout, a: Float, q: Float, stagger: Float,
        openStagger: Float, origin: Float, outBottom: FloatArray, outScale: FloatArray, outOpen: FloatArray
    ) {
        val n = outBottom.size
        val lag = stagger.coerceIn(0f, 0.8f)
        val lockAt = Config.lockAt.coerceIn(0.05f, 0.95f)
        val m = n - 1

        // progres tiap kartu: 0 = masih di posisi keadaan 2, 1 = sudah di posisi keadaan 3
        val e = FloatArray(n)
        // q saat kartu sebelumnya mencapai lockAt (dipakai sebagai gerbang kartu berikutnya)
        var gate = 0f
        for (i in 1 until n) {
            val start: Float
            val span: Float
            if (i <= 2) {
                start = if (m > 1) lag * (i - 1) / (m - 1) else 0f
                span = 1f - lag
            } else {
                start = gate.coerceAtMost(0.999f)
                span = 1f - start
            }
            val t = ((q - start) / span).coerceIn(0f, 1f)
            // campuran linear (mengikuti jari) dan smoothstep (awal/akhir lembut)
            e[i] = t + (t * t * (3f - 2f * t) - t) * 0.5f
            gate = start + lockAt * span
        }

        var prev = la.bottom[0] + (lb.bottom[0] - la.bottom[0]) * q
        outBottom[0] = prev
        outScale[0] = 1f
        for (i in 1 until n) {
            val ei = e[i]
            if (i <= 2) {
                // kartu 2 dan 3: berurutan, mengikuti kartu di depannya
                val dA = la.bottom[i] - la.bottom[i - 1]
                val dB = lb.bottom[i] - lb.bottom[i - 1]
                val cap = max(la.bottom[i], lb.bottom[i])
                prev = min(prev + dA + (dB - dA) * ei, cap)
                outBottom[i] = prev
                outScale[i] = la.scale[i] + (lb.scale[i] - la.scale[i]) * ei
            } else {
                // kartu ke-4 dan seterusnya: tidak mengikuti kartu di depan, posisinya absolut
                outBottom[i] = la.bottom[i] + (lb.bottom[i] - la.bottom[i]) * ei
                outScale[i] = la.scale[i] + (lb.scale[i] - la.scale[i]) * ei
            }
        }

        for (i in 0 until n) outOpen[i] = 1f
        if (a < 1f) {
            for (i in 0 until n) {
                val ea = openProgress(i, n, a, openStagger)
                outOpen[i] = ea
                outBottom[i] = origin + (outBottom[i] - origin) * ea
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

    // a: 0..1 animasi buka tumpukan: semua kartu keluar bergantian dari pil "N Notifications" sambil
    //    membesar dari ukuran pil ke ukuran keadaan 2 (dan sebaliknya, mengecil masuk ke pil saat ditutup).
    //    Kalau pil dimatikan (pill 0): keluar dari titik di garis originFrac dengan skala awal openScale.
    // q: 0..1 jam mengecil dan daftar naik
    // Keadaan 2 (q=0): kartu penuh sebanyak full2, sisanya menumpuk di belakang kartu penuh terakhir
    // seperti dock keadaan 3 (dockStack2). Kalau dockStack2 mati, kartu di belakang jadi pita (layoutState).
    // Kalau kartu <= noStack2 dan muat, semua kartu penuh tanpa tumpukan.
    // Kalau kartu penuh tidak muat sampai garis (mis. kartu depan media player), jumlah kartu penuh dikurangi dan
    // kartu di bawahnya menumpuk sebagai S, sama seperti notifikasi lain. Kartu paling bawah selalu tepat di garis
    // bawah yang sama dengan keadaan 3.
    // Keadaan 3 (q=1): semua kartu penuh dengan jarak kecil; atas dipotong di garis dengan fade. Kartu yang melewati batas bawah
    // (listBottomFrac) tetap kecil dan menumpuk di bawah ("dock") sampai daftar di-swipe naik atau slider digeser.
    // Wallpaper digelapkan sedikit saat masuk keadaan 3.
    private fun applyStack(sv: View, a: Float, q: Float) {
        val dm = sv.resources.displayMetrics
        val d = dm.density
        // stack tetap opaque selama mengecil, baru memudar di ujung animasi
        sv.alpha = (a * 8f).coerceAtMost(1f)
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

        if (Config.cardBgDump) once("cardBg") { logCardBg(cards[0].first) }

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
            // keadaan 2 gaya dock: kartu penuh sebanyak full2, sisanya menumpuk di belakang kartu penuh terakhir
            Config.dockStack2 -> {
                val refPx = (Config.dockRefDp * d).coerceAtLeast(1f)
                val dScale = Config.dockScale.coerceIn(0f, 0.3f)
                val overlapPx = Config.overlap2Dp * d
                // jumlah kartu penuh = sebanyak yang muat sampai garis (maksimal full2). Kalau kartu depan tinggi
                // (mis. media player), kartu penuh berkurang dan kartu di bawahnya menumpuk sebagai S seperti biasa.
                var full = min(Config.full2.coerceAtLeast(1), n)
                while (full > 1 && !dockFits(hs, restTop, full, bottom2, minPeek, overlapPx)) full--
                layoutDockStack(
                    hs, restTop, Config.gap2Dp * d, full, bottom2, refPx, dScale,
                    Config.front2Scale.coerceIn(1f, 1.2f), minPeek, overlapPx
                )
            }
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

        // updateLabels juga menghitung ulang pillGeo (geometri pil), jadi harus dipanggil sebelum titik asal dihitung
        updateLabels(sv, a, targetTop, count)

        // titik asal animasi buka. Pil nyala: tepi bawah pil (kartu diskala ke ukuran pil, jadi pusat kartu = pusat pil).
        // Pil mati: dasar tumpukan keadaan 2, minimal originFrac (garis merah).
        val pg = pillGeo
        val toPill = Config.pillOn && pg[2] > 1f && pg[3] > 1f
        var origin: Float
        if (toPill) {
            origin = pg[1] + pg[3]
        } else {
            origin = dm.heightPixels * Config.originFrac
            for (i in 0 until n) origin = max(origin, la.bottom[i])
        }
        val pillCX = pg[0] + pg[2] / 2f
        val openScale = Config.openScale.coerceIn(0.2f, 1f)

        val bottoms = FloatArray(n)
        val scales = FloatArray(n)
        val opens = FloatArray(n)
        composeStack(la, lb, a, q, Config.stagger, Config.openStagger, origin, bottoms, scales, opens)

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

            // ukuran kartu saat animasi buka: dari ukuran pil (lebar pil / lebar kartu, tinggi pil / tinggi kartu)
            // ke skala keadaan 2. ea = 1 di luar animasi buka, jadi sx = sy = s.
            val ea = opens[i]
            val sx0 = if (toPill) (pg[2] / card.width.toFloat()).coerceIn(0.05f, 1f) else openScale
            val sy0 = if (toPill) (pg[3] / h).coerceIn(0.05f, 1f) else openScale
            val sx = sx0 + (s - sx0) * ea
            val sy = sy0 + (s - sy0) * ea

            val center = bottoms[i] - h * sy / 2f
            sb.append(" [").append(card.javaClass.simpleName.take(14)).append(" k=").append(kind)
                .append(" h=").append(card.height).append("/").append(h.toInt()).append("]")

            val wy = baseY + (center + slide - naturalCenter)
            // kartu yang lebih atas selalu di depan
            val wz = baseZ + (n - i) * 3f * d

            // geser horizontal: pusat kartu menuju pusat pil di awal animasi buka, lalu kembali ke posisi aslinya
            val cardCX = loc[0] + card.left + card.width / 2f
            val wx = if (toPill) (1f - ea) * (pillCX - cardCX) else 0f
            val st = rowState[card]
            val prevWx = if (st != null && st.size > 4) st[4] else 0f
            var appliedWx = prevWx
            if (wx != 0f || prevWx != 0f) {
                // hanya ditimpa kalau translationX masih nilai yang kita pasang sendiri (bukan swipe/sistem)
                if (abs(card.translationX - prevWx) < 0.5f) {
                    card.translationX = wx
                    appliedWx = wx
                }
            }

            card.pivotX = card.width / 2f
            card.pivotY = h / 2f
            card.translationY = wy
            card.translationZ = wz
            card.scaleX = sx
            card.scaleY = sy
            // potong bagian atas kartu yang ter-scroll melewati garis atas daftar; fade-nya digambar oleh mask
            val csy = center + slide
            val iy0 = (h / 2f + (smallTop - csy) / s).toInt()
            card.clipBounds = if (q > 0f && iy0 > 0) {
                Rect(0, min(iy0, card.height), card.width, card.height)
            } else null
            rowState[card] = floatArrayOf(baseY, wy, baseZ, wz, appliedWx)
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

    private fun countText(count: Int): String =
        if (count == 1) "\u2022 1 Notification" else "\u2022 $count Notifications"

    private fun ensureLabels(root: ViewGroup) {
        if (labelCenter?.parent === root && labelCount?.parent === root) return
        (labelCenter?.parent as? ViewGroup)?.removeView(labelCenter)
        (labelCount?.parent as? ViewGroup)?.removeView(labelCount)
        val ctx = root.context

        // pil tidak dibuat di sini: dipasang oleh ensurePillHost tepat di belakang stack kartu
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

        // geometri pil dihitung selalu (juga saat label disembunyikan): animasi kartu memakainya sebagai titik asal
        pillGeo = pillRect(root, count)

        // layar mati atau layar PIN: kedua teks dan pil disembunyikan
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

        // animasi muncul pil: harus dipanggil SEBELUM teks diberi alpha, supaya teks tidak sempat
        // terlihat satu frame penuh sebelum animasi mulai
        tickPillBounce(a, pillAlpha(a, count))

        ln.visibility = if (a2 * pillBounceA > 0.01f) View.VISIBLE else View.INVISIBLE
        ln.alpha = a2 * pillBounceA
        if (abs(ln.scaleX - pillBounceS) > 0.0005f) {
            ln.scaleX = pillBounceS
            ln.scaleY = pillBounceS
        }
        if (count > 0) {
            val t = countText(count)
            if (ln.text.toString() != t) ln.text = t
        }
        val hn = if (ln.height > 0) ln.height.toFloat() else 24f * d
        ln.translationY = dm.heightPixels * Config.countFrac - rl[1] - hn / 2f

        ensurePillHost(sv, root)
        updatePill(root, a, count)
    }

    private fun hideLabels() {
        labelCenter?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
        labelCount?.let { it.alpha = 0f; it.visibility = View.INVISIBLE }
        hidePill()
        hideSlider()
    }

    // ---- pil kaca di belakang "N Notifications" ----

    // Geometri pil dalam piksel layar: [left, top, lebar, tinggi]. Pusat pil = pusat teks "N Notifications"
    // (tengah layar, countFrac dari atas); ukurannya mengikuti lebar teks + jarak pillPadH/pillPadV.
    private fun pillRect(root: ViewGroup, count: Int): FloatArray {
        val dm = root.resources.displayMetrics
        val d = dm.density
        val ln = labelCount
        val tw = ln?.paint?.measureText(countText(max(count, 1))) ?: (140f * d)
        val th = if (ln != null && ln.height > 0) ln.height.toFloat() else 24f * d
        val w = tw + 2f * Config.pillPadHDp * d
        val h = th + 2f * Config.pillPadVDp * d
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels * Config.countFrac
        return floatArrayOf(cx - w / 2f, cy - h / 2f, w, h)
    }

    // Blur pil punya tiga cara (dipilih lewat pillmode):
    //  - blur wallpaper (default): wallpaper kunci dibaca lewat WallpaperManager, digambar ulang di dalam pil
    //    pada posisi center-crop seperti wallpaper asli, lalu diburamkan dengan RenderEffect. Berjalan sendiri di
    //    dalam proses System UI, jadi tidak bergantung pada dukungan blur di SurfaceFlinger.
    //  - blur platform: ViewRootImpl.createBackgroundBlurDrawable (blur belakang-window lewat SurfaceFlinger).
    //    Tidak ada hasilnya kalau ROM mematikan cross-window blur, atau kalau yang di belakang pil bukan window lain.
    //  - kaca putih transparan tanpa blur (cadangan terakhir).

    // Baca wallpaper kunci (kalau tidak ada, wallpaper sistem) di thread terpisah, diperkecil secukupnya
    // (tidak perlu lebih tajam dari separuh layar karena toh diburamkan).
    private fun loadWallpaperAsync(ctx: Context, dm: DisplayMetrics) {
        if (wpLoading || wpTries >= 3) return
        wpLoading = true
        wpTries++
        val app = ctx.applicationContext ?: ctx
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val pv = pill
        Thread {
            try {
                val wm = WallpaperManager.getInstance(app)
                var src = "lock"
                var got = try { wm.getWallpaperFile(WallpaperManager.FLAG_LOCK) } catch (t: Throwable) { null }
                if (got == null) {
                    src = "system"
                    got = try { wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) } catch (t: Throwable) { null }
                }
                val file = got
                if (file == null) {
                    log("pil: file wallpaper tidak tersedia")
                    return@Thread
                }
                file.use { f ->
                    val o = BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    BitmapFactory.decodeFileDescriptor(f.fileDescriptor, null, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) {
                        log("pil: wallpaper $src tidak bisa dibaca")
                        return@use
                    }
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= sw / 2 && o.outHeight / (sample * 2) >= sh / 2) sample *= 2
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = sample
                    val bmp = BitmapFactory.decodeFileDescriptor(f.fileDescriptor, null, o2)
                    if (bmp != null) {
                        wpBitmap = bmp
                        log("pil: wallpaper $src ${o.outWidth}x${o.outHeight} -> ${bmp.width}x${bmp.height}")
                        pv?.let { v -> v.post { v.invalidate() } }
                    } else {
                        log("pil: decode wallpaper $src gagal")
                    }
                }
            } catch (t: Throwable) {
                log("pil: baca wallpaper gagal: $t")
            } finally {
                wpLoading = false
            }
        }.start()
    }

    // Buat drawable blur belakang-window milik platform (ViewRootImpl.createBackgroundBlurDrawable). View harus
    // sudah menempel di window. Kalau gagal (ROM beda), dicoba lagi beberapa kali.
    private fun ensurePillBlur(pv: PillView) {
        if (pillBlur != null || pillBlurTries >= 6 || !pv.isAttachedToWindow) return
        pillBlurTries++
        val bd = try {
            val vri = XposedHelpers.callMethod(pv, "getViewRootImpl")
            if (vri == null) null else XposedHelpers.callMethod(vri, "createBackgroundBlurDrawable") as? Drawable
        } catch (t: Throwable) {
            once("pillBlurFail") { log("pil: createBackgroundBlurDrawable gagal: $t") }
            null
        }
        if (bd != null) {
            pillBlur = bd
            pillLastBlur = -1
            pillLastTint = -1
            pillLastRad = -1f
            // diagnosa: kalau crossWindowBlur=false atau sfSupport=0, blur platform memang tidak akan terlihat
            val en = try {
                (pv.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled
            } catch (t: Throwable) { null }
            log("pil: blur platform ${bd.javaClass.name} crossWindowBlur=$en " +
                "sfSupport=${Config.sys("ro.surface_flinger.supports_background_blur")} " +
                "disableBlurs=${Config.sys("persist.sys.sf.disable_blurs")}")
        } else if (pillBlurTries >= 6) {
            log("pil: blur platform tidak tersedia")
        }
    }

    // ---- animasi muncul pil (grow in + bounce) ----

    // alpha pil terhadap kemajuan a: terlihat bersama teks "N Notifications", tapi memudar sedikit lebih lambat
    // dari teks supaya kartu yang keluar dari pil terlihat muncul dari kaca
    private fun pillAlpha(a: Float, count: Int): Float = when {
        !Config.pillOn || count <= 0 -> 0f
        Config.countAlways -> 1f
        else -> (1f - (a - 0.1f) * 2.5f).coerceIn(0f, 1f)
    }

    // Pil "muncul" kalau sudah terlihat dalam keadaan diam (a ~ 0): saat layar menyala, saat jumlah notifikasi
    // dari 0 jadi ada, dan saat kartu selesai mengecil kembali ke pil. Dipanggil sebelum teks diberi alpha,
    // supaya teks tidak sempat terlihat satu frame penuh sebelum animasi mulai.
    private fun tickPillBounce(a: Float, pa: Float) {
        if (a >= 0.02f || pa < 0.01f) {
            pillWasRest = false
            return
        }
        if (!pillWasRest) {
            pillWasRest = true
            startPillBounce()
        }
    }

    // kurva pegas teredam: naik dari 0, melewati 1 (sekitar 20%), turun sedikit di bawah 1, lalu menetap di 1
    private fun bounceCurve(t: Float): Float {
        if (t >= 1f) return 1f
        return max(0f, 1f - exp(-4.8f * t) * cos(2f * Math.PI.toFloat() * 1.5f * t))
    }

    private fun startPillBounce() {
        val ms = Config.pillBounceMs
        if (ms <= 0L) return
        val now = SystemClock.uptimeMillis()
        if (now - pillLastBounce < 350L) return
        pillLastBounce = now
        pillBounceAnim?.cancel()
        pillBounceS = 0.0001f
        pillBounceA = 0f
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = ms
        an.interpolator = LinearInterpolator()
        an.addUpdateListener {
            val t = it.animatedFraction
            pillBounceS = bounceCurve(t)
            pillBounceA = if (t >= 1f) 1f else min(1f, t * 4f)
            applyState()
        }
        pillBounceAnim = an
        an.start()
    }

    // ---- tempat pil ----

    // Pil dipasang di view induk stack notifikasi, tepat SEBELUM stack, jadi tergambar di belakang kartu
    // notifikasi tapi di atas wallpaper. Teks "N Notifications" ada di root, jadi tetap di atas pil.
    // Dicek tiap frame (sistem bisa mengubah urutan anak); kalau induk stack tidak bisa dipasangi, pakai root.
    private fun ensurePillHost(sv: View, root: ViewGroup) {
        var host = (sv.parent as? ViewGroup) ?: root
        var pv = pill
        if (pv == null) {
            pv = PillView(host.context)
            pv.visibility = View.INVISIBLE
            pill = pv
        }
        val before = host.indexOfChild(sv)
        if (pv.parent === host && (before < 0 || host.indexOfChild(pv) == before - 1)) return

        (pv.parent as? ViewGroup)?.removeView(pv)
        // pil baru dipasang: semua state blur dihitung ulang
        pillBlur = null
        pillBlurTries = 0
        pillLastBlur = -1
        pillLastTint = -1
        pillLastRad = -1f
        pv.mode = -1
        pv.xosState = 0
        pv.background = null

        val lp = ViewGroup.LayoutParams(max(2, pillGeo[2].toInt()), max(2, pillGeo[3].toInt()))
        try {
            val idx = host.indexOfChild(sv)
            host.addView(pv, if (idx >= 0) idx else host.childCount, lp)
        } catch (t: Throwable) {
            once("pillHostFail") { log("pil: induk stack ${host.javaClass.name} menolak anak baru, pakai root: $t") }
            host = root
            (pv.parent as? ViewGroup)?.removeView(pv)
            root.addView(pv, root.childCount, lp)
        }
        log("pil: dipasang di ${host.javaClass.simpleName} index ${host.indexOfChild(pv)} (stack di ${host.indexOfChild(sv)})")
    }

    // ---- blur XOS (DepthUtil) ----

    private class XosTarget(val m: java.lang.reflect.Method, val inst: Any?)

    private fun sigOf(m: java.lang.reflect.Method): String =
        m.declaringClass.simpleName + "." + m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" +
            (if (java.lang.reflect.Modifier.isStatic(m.modifiers)) " static" else "")

    // Catat method DepthUtil dan TranView yang berhubungan dengan blur (sekali saja), supaya kelihatan
    // signature aslinya di ROM ini: logcat | grep IOSLock | grep xos
    private fun dumpXos(cl: ClassLoader, depth: Class<*>) {
        try {
            val re = Regex("blur|effect|row|depth", RegexOption.IGNORE_CASE)
            val sb = StringBuilder()
            var n = 0
            for (m in depth.declaredMethods) {
                if (n >= 40) break
                if (re.containsMatchIn(m.name)) { sb.append("\n  ").append(sigOf(m)); n++ }
            }
            val hasInst = try { XposedHelpers.getStaticObjectField(depth, "INSTANCE") != null } catch (t: Throwable) { false }
            val hasComp = try { XposedHelpers.getStaticObjectField(depth, "Companion") != null } catch (t: Throwable) { false }
            log("xos: DepthUtil INSTANCE=$hasInst Companion=$hasComp methods:$sb")
            val tran = XposedHelpers.findClassIfExists(xosTranName, cl)
            if (tran == null) {
                log("xos: TranView tidak ada")
            } else {
                val sb2 = StringBuilder()
                var k = 0
                for (m in tran.declaredMethods) {
                    if (k >= 30) break
                    if (m.name.contains("lur", true) || m.name.contains("ffect", true)) { sb2.append("\n  ").append(sigOf(m)); k++ }
                }
                log("xos: TranView methods:$sb2")
            }
        } catch (t: Throwable) {
            log("xos: dump gagal: $t")
        }
    }

    // Isi argumen dari tipenya. Parameter View pertama = pil (parameter View berikutnya = induk pil), Context =
    // context pil, angka dan boolean dari Config (debug.ioslock.xosint / xosfloat / xosbool). Kalau tidak ada
    // parameter yang menerima View, method itu dilewati.
    private fun buildXosArgs(types: Array<Class<*>>, pv: PillView): Array<Any?>? {
        var gotView = false
        val parent = pv.parent
        val out = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val p = types[i]
            out[i] = when {
                p == Boolean::class.javaPrimitiveType || p == Boolean::class.javaObjectType -> Config.pillXosBool
                p == Int::class.javaPrimitiveType || p == Int::class.javaObjectType -> Config.pillXosInt
                p == Float::class.javaPrimitiveType || p == Float::class.javaObjectType -> Config.pillXosFloat
                p == Long::class.javaPrimitiveType || p == Long::class.javaObjectType -> 0L
                p == Double::class.javaPrimitiveType || p == Double::class.javaObjectType -> 0.0
                p == String::class.java -> ""
                p == Context::class.java -> pv.context
                p != Any::class.java && p.isAssignableFrom(pv.javaClass) -> {
                    if (gotView && parent != null && p.isAssignableFrom(parent.javaClass)) parent
                    else { gotView = true; pv }
                }
                parent != null && p != Any::class.java && p.isAssignableFrom(parent.javaClass) -> parent
                else -> null
            }
        }
        return if (gotView) out else null
    }

    // Panggil DepthUtil.enableEffectRowBlur(pil, ...) lewat refleksi: blur yang sama dengan latar kartu
    // notifikasi XOS. Method dicari di class (static), lewat INSTANCE (Kotlin object), atau lewat Companion.
    // Overload dengan parameter paling sedikit dicoba lebih dulu.
    private fun applyXosBlur(pv: PillView): Boolean {
        val cl = sysCl ?: return false
        val depth = XposedHelpers.findClassIfExists(xosDepthName, cl)
        if (depth == null) {
            once("xosMiss") { log("xos: class $xosDepthName tidak ada di ROM ini") }
            return false
        }
        once("xosDump") { dumpXos(cl, depth) }

        val targets = ArrayList<XosTarget>()
        fun collect(c: Class<*>, inst: Any?) {
            for (m in c.declaredMethods) {
                if (m.name == "enableEffectRowBlur" && !m.isSynthetic) targets.add(XosTarget(m, inst))
            }
        }
        val instObj = try { XposedHelpers.getStaticObjectField(depth, "INSTANCE") } catch (t: Throwable) { null }
        collect(depth, instObj)
        val comp = try { XposedHelpers.getStaticObjectField(depth, "Companion") } catch (t: Throwable) { null }
        if (comp != null) collect(comp.javaClass, comp)
        if (targets.isEmpty()) {
            once("xosNoMethod") { log("xos: enableEffectRowBlur tidak ditemukan di DepthUtil") }
            return false
        }
        targets.sortBy { it.m.parameterTypes.size }

        for (t in targets) {
            val args = buildXosArgs(t.m.parameterTypes, pv)
            if (args == null) {
                log("xos: lewati ${sigOf(t.m)} (tidak ada parameter View)")
                continue
            }
            try {
                t.m.isAccessible = true
                val recv: Any? = if (java.lang.reflect.Modifier.isStatic(t.m.modifiers)) null else t.inst
                if (recv == null && !java.lang.reflect.Modifier.isStatic(t.m.modifiers)) {
                    log("xos: lewati ${sigOf(t.m)} (tidak ada instance)")
                    continue
                }
                t.m.invoke(recv, *args)
                log("xos: dipanggil ${sigOf(t.m)} args=${args.joinToString { it?.javaClass?.simpleName ?: "null" }}")
                return true
            } catch (e: Throwable) {
                log("xos: ${sigOf(t.m)} gagal: ${e.cause ?: e}")
            }
        }
        return false
    }

    // Blur XOS dipasang sekali per pil, setelah pil menempel di window dan punya ukuran.
    private fun xosReady(pv: PillView): Boolean {
        if (pv.xosState == 1) return true
        if (pv.xosState == 2) return false
        if (!pv.isAttachedToWindow || pv.width <= 0 || pv.height <= 0) return false
        val ok = try { applyXosBlur(pv) } catch (t: Throwable) {
            log("xos: error tak terduga: $t")
            false
        }
        pv.xosState = if (ok) 1 else 2
        return ok
    }

    // ---- memilih cara blur ----

    // Urutan percobaan (pillmode 0 = otomatis): 3 = blur XOS (DepthUtil), 2 = blur wallpaper, 1 = blur platform,
    // 0 = kaca putih transparan. Yang belum siap (misal wallpaper masih dimuat) dilewati sementara.
    private fun resolvePillMode(pv: PillView, root: ViewGroup, dm: DisplayMetrics): Int {
        val order = when (Config.pillMode) {
            1 -> intArrayOf(1, 0)
            2 -> intArrayOf(2, 1, 0)
            3 -> intArrayOf(0)
            4 -> intArrayOf(3, 0)
            else -> intArrayOf(2, 3, 1, 0)
        }
        for (m in order) {
            when (m) {
                3 -> if (xosReady(pv)) return 3
                2 -> {
                    if (wpBitmap != null) return 2
                    loadWallpaperAsync(root.context, dm)
                }
                1 -> {
                    ensurePillBlur(pv)
                    if (pillBlur != null) return 1
                }
                else -> return 0
            }
        }
        return 0
    }

    private fun updatePill(root: ViewGroup, a: Float, count: Int) {
        val pv = pill ?: return
        val host = pv.parent as? ViewGroup ?: return
        val pr = pillGeo
        val pa = pillAlpha(a, count)
        if (pa < 0.01f || pr[2] < 2f) {
            hidePill()
            return
        }
        val dm = root.resources.displayMetrics
        val d = dm.density
        val w = pr[2].toInt()
        val h = pr[3].toInt()
        val lp = pv.layoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            pv.requestLayout()
        }
        // pil bukan anak root: posisi layar yang diinginkan dikurangi posisi layar bawaan pil di induknya
        val hl = IntArray(2)
        host.getLocationOnScreen(hl)
        pv.translationX = pr[0] - (hl[0] + pv.left)
        pv.translationY = pr[1] - (hl[1] + pv.top)
        if (pv.visibility != View.VISIBLE) pv.visibility = View.VISIBLE

        val radius = if (Config.pillRadiusDp >= 0f) Config.pillRadiusDp * d else pr[3] / 2f
        val blurR = (Config.pillBlurPx * pa).toInt()
        val tintA = (Config.pillTintA * pa).toInt().coerceIn(0, 255)

        val mode = resolvePillMode(pv, root, dm)
        val prev = pv.mode
        if (prev != mode) {
            pv.mode = mode
            // mode 3: background dipasang oleh blur XOS sendiri, jangan disentuh
            if (mode == 1) pv.background = pillBlur else if (prev == 1) pv.background = null
            pv.invalidate()
            logT("pilMode", "mode=$mode (3 xos, 2 wallpaper, 1 platform, 0 kaca)", discrete = true)
        }

        // animasi muncul: skala dan alpha dari pillBounceS / pillBounceA (1 kalau tidak sedang beranimasi)
        if (abs(pv.scaleX - pillBounceS) > 0.0005f) {
            pv.scaleX = pillBounceS
            pv.scaleY = pillBounceS
        }
        val va = pillBounceA * (if (mode == 3) pa else 1f)
        if (abs(pv.alpha - va) > 0.004f) pv.alpha = va

        when (mode) {
            2 -> {
                val bmp = wpBitmap
                if (bmp != null) {
                    // wallpaper digambar center-crop ke layar penuh: skala = sisi terbesar yang menutup layar
                    val sw = dm.widthPixels.toFloat()
                    val sh = dm.heightPixels.toFloat()
                    val zoom = Config.pillWpZoom.coerceIn(0.5f, 3f)
                    val s0 = max(sw / bmp.width, sh / bmp.height) * zoom
                    val dx = (sw - bmp.width * s0) / 2f + Config.pillWpDx
                    val dy = (sh - bmp.height * s0) / 2f + Config.pillWpDy
                    pv.setWallpaper(bmp, s0, dx, dy, pr[0], pr[1])
                    logT("pilWp", "bmp=${bmp.width}x${bmp.height} s=${"%.3f".format(s0)} dx=${dx.toInt()} dy=${dy.toInt()}", discrete = true)
                }
                pv.setLook(radius, tintA, Config.pillBlurPx * 0.5f * pa, pa)
            }
            1 -> {
                pv.setLook(radius, 0, 0f, pa)
                val bd = pillBlur
                if (bd != null) {
                    try {
                        if (blurR != pillLastBlur) {
                            XposedHelpers.callMethod(bd, "setBlurRadius", blurR)
                            pillLastBlur = blurR
                        }
                        if (tintA != pillLastTint) {
                            XposedHelpers.callMethod(bd, "setColor", Color.argb(tintA, 255, 255, 255))
                            pillLastTint = tintA
                        }
                        if (abs(radius - pillLastRad) > 0.5f) {
                            XposedHelpers.callMethod(bd, "setCornerRadius", radius)
                            pillLastRad = radius
                        }
                    } catch (t: Throwable) {
                        once("pillBlurSet") { log("pil: atur blur platform gagal, pakai kaca biasa: $t") }
                        pillBlur = null
                        pillBlurTries = 99
                        pv.mode = 0
                        pv.background = null
                        pv.invalidate()
                    }
                }
            }
            // 3 (XOS) dan 0 (kaca): hanya tint putih transparan di atas blur XOS / tanpa blur
            else -> pv.setLook(radius, tintA, 0f, pa)
        }
    }

    private fun hidePill() {
        pillWasRest = false
        pillBounceAnim?.cancel()
        pillBounceAnim = null
        pillBounceS = 1f
        pillBounceA = 1f
        val ln = labelCount
        if (ln != null && ln.scaleX != 1f) {
            ln.scaleX = 1f
            ln.scaleY = 1f
        }
        val pv = pill ?: return
        if (pv.scaleX != 1f) {
            pv.scaleX = 1f
            pv.scaleY = 1f
        }
        if (pv.alpha != 1f) pv.alpha = 1f
        if (pv.visibility != View.INVISIBLE) pv.visibility = View.INVISIBLE
    }

    // pil. mode 1: latar dari drawable blur platform (dipasang sebagai background, tint digambar drawable itu).
    // mode 2: onDraw menggambar wallpaper (posisi layar sebenarnya) ke RenderNode ber-RenderEffect blur, dipotong
    // sebesar pil, lalu tint putih di atasnya. mode 3: blur XOS dipasang dari luar (DepthUtil), onDraw hanya
    // menambah tint. mode 0: hanya tint putih transparan.
    // Bentuk bulat dipotong lewat outline (clipToOutline), supaya blur yang dipasang sistem ikut terpotong.
    private class PillView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()
        private val clipPath = Path()
        private val node = RenderNode("ioslock_pill_blur")
        private var effectSigma = -1f

        // 0 = kaca putih transparan, 1 = blur platform, 2 = blur wallpaper, 3 = blur XOS (-1 = belum dipilih)
        var mode = -1
        // blur XOS: 0 = belum dicoba, 1 = terpasang, 2 = gagal
        var xosState = 0
        private var radius = 0f
        private var fillA = 0
        private var sigma = 0f
        private var alphaK = 1f
        private var wp: Bitmap? = null
        private var wpS = 1f
        private var wpDx = 0f
        private var wpDy = 0f
        private var posX = 0f
        private var posY = 0f

        init {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // dipasang ulang ke window: efek XOS harus dipasang lagi
            xosState = 0
        }

        fun setLook(r: Float, a: Int, sg: Float, k: Float) {
            if (abs(r - radius) > 0.5f) {
                radius = r
                invalidateOutline()
            }
            if (a != fillA || abs(sg - sigma) > 0.4f || abs(k - alphaK) > 0.01f) {
                fillA = a
                sigma = sg
                alphaK = k
                invalidate()
            }
        }

        // b digambar dengan skala s dan geser (dx, dy) di koordinat layar; (px, py) = posisi pil di layar
        fun setWallpaper(b: Bitmap, s: Float, dx: Float, dy: Float, px: Float, py: Float) {
            if (b !== wp || abs(s - wpS) > 0.0005f || abs(dx - wpDx) > 0.5f || abs(dy - wpDy) > 0.5f ||
                abs(px - posX) > 0.5f || abs(py - posY) > 0.5f
            ) {
                wp = b
                wpS = s
                wpDx = dx
                wpDy = dy
                posX = px
                posY = py
                invalidate()
            }
        }

        private fun drawBlurred(canvas: Canvas, w: Int, h: Int) {
            val b = wp ?: return
            if (b.isRecycled) return
            val rc = canvas as? RecordingCanvas ?: return
            val sg = sigma.coerceAtLeast(1f)
            if (abs(sg - effectSigma) > 0.4f) {
                node.setRenderEffect(RenderEffect.createBlurEffect(sg, sg, Shader.TileMode.CLAMP))
                effectSigma = sg
            }
            // node dibuat lebih besar dari pil supaya tepi pil ikut memakai piksel di sekitarnya (bukan tepi yang di-clamp)
            val m = (sg * 3f).toInt() + 2
            node.setPosition(-m, -m, w + m, h + m)
            node.setAlpha(alphaK)
            val rec = node.beginRecording(w + 2 * m, h + 2 * m)
            try {
                rec.translate(m - posX, m - posY)
                rec.translate(wpDx, wpDy)
                rec.scale(wpS, wpS)
                rec.drawBitmap(b, 0f, 0f, bmpPaint)
            } finally {
                node.endRecording()
            }
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            rc.drawRenderNode(node)
            canvas.restoreToCount(save)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (mode == 2) drawBlurred(canvas, w, h)
            // mode 1: tint sudah digambar drawable blur platform
            if (mode == 1 || fillA <= 0) return
            paint.color = Color.argb(fillA, 255, 255, 255)
            box.set(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(box, radius, radius, paint)
        }
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
                        // mask hanya boleh digambar saat keyguard
                        if (!maskOn || barState != 1 || view !== stackRef?.get()) return
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

    // matikan fade atas daftar. Dipanggil setiap keluar dari keyguard supaya notifikasi di home screen tidak ikut terpotong.
    private fun clearMask(sv: View?) {
        if (!maskOn && maskStrength == 0f && maskFade == 0f) return
        maskOn = false
        maskStrength = 0f
        maskFade = 0f
        sv?.invalidate()
    }

    // on = ada kartu yang ter-scroll melewati garis atas. Kekuatan fade naik bertahap seiring jarak gulir.
    private fun updateMask(sv: View, on: Boolean, scroll: Float, line: Float, d: Float) {
        ensureMaskHook(sv)
        val fade = Config.topFadeDp * d
        val st = if (on && fade > 1f) (scroll / (fade * 0.8f)).coerceIn(0f, 1f) else 0f
        val active = on && maskReady
        val changed = active != maskOn || abs(st - maskStrength) > 0.01f
        maskOn = active
        maskLine = line
        maskFade = fade
        maskStrength = st
        if (changed) sv.invalidate()
    }

    // ---- dim wallpaper (keadaan 3) ----

    // Jam yang ditemukan di root (dipakai untuk meletakkan overlay gelap tepat di bawahnya)
    private fun findClockView(root: ViewGroup): View? {
        val found = ArrayList<View>()
        findViews(root, 0, found) { v ->
            v.javaClass.name.contains("Clock") && v.isShown && v.height > 0
        }
        val c = found.firstOrNull()
        if (c != null) once("clockFound") {
            log("jam: ${c.javaClass.name} di ${(c.parent as? View)?.javaClass?.name}")
        }
        return c
    }

    // Overlay gelap untuk wallpaper di keadaan 3. Diletakkan TEPAT DI BAWAH jam, supaya jam tidak ikut gelap.
    // Kalau jam tidak ditemukan di root yang sama, overlay tetap di paling bawah (perilaku lama).
    private fun ensureDim(root: ViewGroup): View {
        val clock = findClockView(root)
        val host = (clock?.parent as? ViewGroup) ?: root
        val sameHost = clock != null && clock.parent === host

        val cur = dimView ?: View(root.context).also {
            it.setBackgroundColor(Color.BLACK)
            it.alpha = 0f
            it.visibility = View.INVISIBLE
            it.isClickable = false
            it.isFocusable = false
            dimView = it
        }

        val placed = cur.parent === host && (
            if (sameHost) host.indexOfChild(cur) == host.indexOfChild(clock) - 1
            else host.indexOfChild(cur) == 0
        )
        if (!placed) {
            (cur.parent as? ViewGroup)?.removeView(cur)
            val idx = if (sameHost) host.indexOfChild(clock) else 0
            host.addView(cur, idx, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return cur
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
                if (st.size > 4 && st[4] != 0f && abs(c.translationX - st[4]) < 0.5f) c.translationX = 0f
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
