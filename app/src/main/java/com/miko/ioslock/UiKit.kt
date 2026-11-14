package com.miko.ioslock

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// ukuran
// ---------------------------------------------------------------------------------------------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

// ---------------------------------------------------------------------------------------------
// warna: biru dengan gradient ungu kebiruan dan sedikit cyan; terang/gelap mengikuti sistem
// ---------------------------------------------------------------------------------------------

object Brand {
    val blue = 0xFF2F6BFF.toInt()
    val indigo = 0xFF5B5BF0.toInt()
    val violet = 0xFF8B5CF6.toInt()
    val cyan = 0xFF22D3EE.toInt()
}

class Pal(val night: Boolean) {
    val bg = if (night) 0xFF0A0C13.toInt() else 0xFFF2F4FA.toInt()
    val card = if (night) 0xFF141824.toInt() else 0xFFFFFFFF.toInt()
    val stroke = if (night) 0xFF232A3E.toInt() else 0xFFE3E7F1.toInt()
    val text = if (night) 0xFFF1F3FA.toInt() else 0xFF0F1626.toInt()
    val sub = if (night) 0xFF9AA4BC.toInt() else 0xFF5A6479.toInt()
    val chip = if (night) 0xFF1D2335.toInt() else 0xFFEAEEFB.toInt()
    val track = if (night) 0xFF2E3650.toInt() else 0xFFCDD3E3.toInt()
    val inputBg = if (night) 0xFF0F1320.toInt() else 0xFFF4F6FC.toInt()
    val dialog = if (night) 0xFF171C2B.toInt() else 0xFFFFFFFF.toInt()
    val accent = if (night) 0xFF8FB0FF.toInt() else 0xFF2F6BFF.toInt()
}

fun Context.palette(): Pal = Pal(isNight())

// ---------------------------------------------------------------------------------------------
// latar header: gradient biru -> ungu kebiruan, cahaya cyan tipis, sudut bawah membulat
// ---------------------------------------------------------------------------------------------

class HeaderDrawable(private val radius: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val r = RectF()

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        r.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(r, floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius), Path.Direction.CW)
        c.clipPath(clip)
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF1C64FF.toInt(), 0xFF4F5DF5.toInt(), 0xFF8B5CF6.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(r, p)
        p.shader = RadialGradient(
            w * 0.9f, h * 0.05f, w * 0.6f,
            intArrayOf(0x7722D3EE, 0x0022D3EE), null, Shader.TileMode.CLAMP
        )
        c.drawRect(r, p)
        p.shader = RadialGradient(
            w * 0.02f, h * 1.0f, w * 0.65f,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        c.drawRect(r, p)
        p.shader = null
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

// ---------------------------------------------------------------------------------------------
// ikon (digambar lewat canvas, tanpa file gambar)
// ---------------------------------------------------------------------------------------------

class IconView(ctx: Context, private val kind: Int, private var tint: Int) : View(ctx) {
    companion object {
        const val RESET = 0
        const val GEAR = 1
        const val INFO = 2
        const val BUG = 3
        const val TELEGRAM = 4
        const val COFFEE = 5
        const val GLOBE = 6
        const val CHEVRON = 7
        const val PILL = 8
        const val POWER = 9
        const val USER = 10
        const val CHECK = 11
        const val BACK = 12
        const val TAG = 13
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val box = RectF()

    fun setTint(c: Int) {
        tint = c
        invalidate()
    }

    private fun flipIfRtl(c: Canvas, s: Float) {
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) c.scale(-1f, 1f, s / 2f, s / 2f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        if (s <= 0f) return
        stroke.color = tint
        fill.color = tint
        stroke.strokeWidth = s * 0.085f
        c.save()
        c.translate((w - s) / 2f, (h - s) / 2f)
        when (kind) {
            RESET -> drawReset(c, s)
            GEAR -> drawGear(c, s)
            INFO -> drawInfo(c, s)
            BUG -> drawBug(c, s)
            TELEGRAM -> drawTelegram(c, s)
            COFFEE -> drawCoffee(c, s)
            GLOBE -> drawGlobe(c, s)
            CHEVRON -> {
                flipIfRtl(c, s)
                path.reset()
                path.moveTo(s * 0.38f, s * 0.24f)
                path.lineTo(s * 0.64f, s * 0.5f)
                path.lineTo(s * 0.38f, s * 0.76f)
                c.drawPath(path, stroke)
            }
            PILL -> drawPill(c, s)
            POWER -> drawPower(c, s)
            USER -> drawUser(c, s)
            CHECK -> {
                path.reset()
                path.moveTo(s * 0.24f, s * 0.52f)
                path.lineTo(s * 0.43f, s * 0.70f)
                path.lineTo(s * 0.78f, s * 0.30f)
                c.drawPath(path, stroke)
            }
            BACK -> {
                flipIfRtl(c, s)
                c.drawLine(s * 0.80f, s * 0.5f, s * 0.22f, s * 0.5f, stroke)
                path.reset()
                path.moveTo(s * 0.46f, s * 0.26f)
                path.lineTo(s * 0.22f, s * 0.5f)
                path.lineTo(s * 0.46f, s * 0.74f)
                c.drawPath(path, stroke)
            }
            TAG -> drawTag(c, s)
        }
        c.restore()
    }

    // layar + panah putar (sama dengan ikon tombol di menu power)
    private fun drawReset(c: Canvas, s: Float) {
        box.set(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f)
        c.drawRoundRect(box, s * 0.16f, s * 0.16f, stroke)
        val ar = s * 0.19f
        box.set(s * 0.5f - ar, s * 0.5f - ar, s * 0.5f + ar, s * 0.5f + ar)
        c.drawArc(box, -60f, 290f, false, stroke)
        val th = Math.toRadians(230.0)
        val ex = s * 0.5f + ar * cos(th).toFloat()
        val ey = s * 0.5f + ar * sin(th).toFloat()
        val tx = (-sin(th)).toFloat()
        val ty = cos(th).toFloat()
        val nx = cos(th).toFloat()
        val ny = sin(th).toFloat()
        val hl = s * 0.10f
        val hw = s * 0.075f
        path.reset()
        path.moveTo(ex + tx * hl, ey + ty * hl)
        path.lineTo(ex + nx * hw, ey + ny * hw)
        path.lineTo(ex - nx * hw, ey - ny * hw)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawGear(c: Canvas, s: Float) {
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.22f, stroke)
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.08f, stroke)
        val old = stroke.strokeWidth
        stroke.strokeWidth = s * 0.11f
        for (i in 0 until 8) {
            c.save()
            c.rotate(i * 45f, s * 0.5f, s * 0.5f)
            c.drawLine(s * 0.5f, s * 0.5f - s * 0.34f, s * 0.5f, s * 0.5f - s * 0.27f, stroke)
            c.restore()
        }
        stroke.strokeWidth = old
    }

    private fun drawInfo(c: Canvas, s: Float) {
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.36f, stroke)
        c.drawCircle(s * 0.5f, s * 0.33f, s * 0.05f, fill)
        c.drawLine(s * 0.5f, s * 0.46f, s * 0.5f, s * 0.68f, stroke)
    }

    private fun drawBug(c: Canvas, s: Float) {
        box.set(s * 0.34f, s * 0.36f, s * 0.66f, s * 0.80f)
        c.drawRoundRect(box, s * 0.16f, s * 0.16f, stroke)
        c.drawCircle(s * 0.5f, s * 0.27f, s * 0.08f, stroke)
        c.drawLine(s * 0.45f, s * 0.21f, s * 0.38f, s * 0.12f, stroke)
        c.drawLine(s * 0.55f, s * 0.21f, s * 0.62f, s * 0.12f, stroke)
        c.drawLine(s * 0.5f, s * 0.44f, s * 0.5f, s * 0.80f, stroke)
        c.drawLine(s * 0.34f, s * 0.48f, s * 0.20f, s * 0.43f, stroke)
        c.drawLine(s * 0.34f, s * 0.60f, s * 0.18f, s * 0.60f, stroke)
        c.drawLine(s * 0.34f, s * 0.72f, s * 0.20f, s * 0.78f, stroke)
        c.drawLine(s * 0.66f, s * 0.48f, s * 0.80f, s * 0.43f, stroke)
        c.drawLine(s * 0.66f, s * 0.60f, s * 0.82f, s * 0.60f, stroke)
        c.drawLine(s * 0.66f, s * 0.72f, s * 0.80f, s * 0.78f, stroke)
    }

    private fun drawTelegram(c: Canvas, s: Float) {
        path.reset()
        path.moveTo(s * 0.86f, s * 0.18f)
        path.lineTo(s * 0.14f, s * 0.48f)
        path.lineTo(s * 0.40f, s * 0.58f)
        path.lineTo(s * 0.56f, s * 0.82f)
        path.close()
        c.drawPath(path, stroke)
        c.drawLine(s * 0.40f, s * 0.58f, s * 0.86f, s * 0.18f, stroke)
    }

    private fun drawCoffee(c: Canvas, s: Float) {
        path.reset()
        path.moveTo(s * 0.20f, s * 0.42f)
        path.lineTo(s * 0.64f, s * 0.42f)
        path.lineTo(s * 0.60f, s * 0.72f)
        path.quadTo(s * 0.59f, s * 0.80f, s * 0.50f, s * 0.80f)
        path.lineTo(s * 0.34f, s * 0.80f)
        path.quadTo(s * 0.25f, s * 0.80f, s * 0.24f, s * 0.72f)
        path.close()
        c.drawPath(path, stroke)
        box.set(s * 0.52f, s * 0.46f, s * 0.80f, s * 0.68f)
        c.drawArc(box, -90f, 180f, false, stroke)
        c.drawLine(s * 0.32f, s * 0.31f, s * 0.32f, s * 0.20f, stroke)
        c.drawLine(s * 0.42f, s * 0.31f, s * 0.42f, s * 0.15f, stroke)
        c.drawLine(s * 0.52f, s * 0.31f, s * 0.52f, s * 0.20f, stroke)
    }

    private fun drawGlobe(c: Canvas, s: Float) {
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.36f, stroke)
        box.set(s * 0.35f, s * 0.14f, s * 0.65f, s * 0.86f)
        c.drawOval(box, stroke)
        c.drawLine(s * 0.14f, s * 0.5f, s * 0.86f, s * 0.5f, stroke)
        c.drawLine(s * 0.22f, s * 0.32f, s * 0.78f, s * 0.32f, stroke)
        c.drawLine(s * 0.22f, s * 0.68f, s * 0.78f, s * 0.68f, stroke)
    }

    private fun drawPill(c: Canvas, s: Float) {
        box.set(s * 0.12f, s * 0.46f, s * 0.88f, s * 0.78f)
        c.drawRoundRect(box, s * 0.16f, s * 0.16f, stroke)
        c.drawLine(s * 0.24f, s * 0.30f, s * 0.76f, s * 0.30f, stroke)
        c.drawLine(s * 0.34f, s * 0.16f, s * 0.66f, s * 0.16f, stroke)
    }

    private fun drawPower(c: Canvas, s: Float) {
        box.set(s * 0.24f, s * 0.26f, s * 0.76f, s * 0.78f)
        c.drawArc(box, -60f, 300f, false, stroke)
        c.drawLine(s * 0.5f, s * 0.14f, s * 0.5f, s * 0.48f, stroke)
    }

    private fun drawUser(c: Canvas, s: Float) {
        c.drawCircle(s * 0.5f, s * 0.34f, s * 0.14f, stroke)
        box.set(s * 0.22f, s * 0.58f, s * 0.78f, s * 1.14f)
        c.drawArc(box, 180f, 180f, false, stroke)
    }

    private fun drawTag(c: Canvas, s: Float) {
        path.reset()
        path.moveTo(s * 0.18f, s * 0.20f)
        path.lineTo(s * 0.52f, s * 0.20f)
        path.lineTo(s * 0.84f, s * 0.52f)
        path.lineTo(s * 0.52f, s * 0.84f)
        path.lineTo(s * 0.18f, s * 0.50f)
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(s * 0.34f, s * 0.36f, s * 0.045f, fill)
    }
}

// ---------------------------------------------------------------------------------------------
// toggle: track gradient saat nyala, tombol putih dengan cincin cyan tipis
// ---------------------------------------------------------------------------------------------

class ToggleView(ctx: Context, private val pal: Pal) : View(ctx) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun toggle() = setChecked(!checked, user = true, animate = true)

    fun setChecked(v: Boolean, user: Boolean = false, animate: Boolean = true) {
        if (v == checked && !user) {
            if (!animate) { t = if (v) 1f else 0f; invalidate() }
            return
        }
        checked = v
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (animate && isAttachedToWindow) {
            val from = t
            val a = ValueAnimator.ofFloat(from, to)
            a.duration = 200L
            a.interpolator = DecelerateInterpolator(1.6f)
            a.addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            anim = a
            a.start()
        } else {
            t = to
            invalidate()
        }
        if (user) onChange?.invoke(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((52 * d).toInt(), (32 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2f
        r.set(0f, 0f, w, h)
        track.shader = null
        track.color = pal.track
        c.drawRoundRect(r, rad, rad, track)
        if (t > 0.01f) {
            track.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Brand.blue, Brand.indigo, Brand.violet), null, Shader.TileMode.CLAMP
            )
            track.alpha = (255f * t).toInt().coerceIn(0, 255)
            c.drawRoundRect(r, rad, rad, track)
            track.shader = null
        }
        val kr = rad - 4f * d
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val p = if (rtl) 1f - t else t
        val cx = rad + (w - 2f * rad) * p
        val cy = h / 2f
        knob.color = 0x33000000
        c.drawCircle(cx, cy + 1.2f * d, kr, knob)
        knob.color = Color.WHITE
        c.drawCircle(cx, cy, kr, knob)
        if (t > 0.01f) {
            ring.strokeWidth = 1.6f * d
            ring.color = Brand.cyan
            ring.alpha = (200f * t).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, kr - 0.8f * d, ring)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// slider: track gradient, tombol putih dengan cincin cyan
// ---------------------------------------------------------------------------------------------

class GradientSlider(ctx: Context, private val pal: Pal) : View(ctx) {
    /** 0..1 */
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** (nilai 0..1, selesai digeser) */
    var onChange: ((Float, Boolean) -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val knobR = 13f * d
    private val trackH = 6f * d

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * d).toInt())
    }

    private fun left() = knobR + 2f * d
    private fun right() = width - knobR - 2f * d

    override fun onDraw(c: Canvas) {
        val l = left()
        val rt = right()
        val cy = height / 2f
        val x = l + (rt - l) * value
        r.set(l, cy - trackH / 2f, rt, cy + trackH / 2f)
        trackP.shader = null
        trackP.color = pal.track
        c.drawRoundRect(r, trackH / 2f, trackH / 2f, trackP)
        r.set(l, cy - trackH / 2f, x, cy + trackH / 2f)
        trackP.shader = LinearGradient(
            l, 0f, rt, 0f,
            intArrayOf(Brand.blue, Brand.indigo, Brand.violet), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, trackH / 2f, trackH / 2f, trackP)
        trackP.shader = null
        knobP.color = 0x33000000
        c.drawCircle(x, cy + 1.5f * d, knobR, knobP)
        knobP.color = Color.WHITE
        c.drawCircle(x, cy, knobR, knobP)
        ringP.strokeWidth = 2.6f * d
        ringP.color = Brand.cyan
        c.drawCircle(x, cy, knobR - 1.3f * d, ringP)
        knobP.color = Brand.indigo
        c.drawCircle(x, cy, 3.4f * d, knobP)
    }

    private fun update(x: Float, done: Boolean) {
        val l = left()
        val rt = right()
        val v = ((x - l) / (rt - l)).coerceIn(0f, 1f)
        value = v
        onChange?.invoke(v, done)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, false)
            MotionEvent.ACTION_UP -> {
                update(e.x, true)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                onChange?.invoke(value, true)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

// ---------------------------------------------------------------------------------------------
// pratinjau layar kunci mini: pil dan dock mengikuti posisi slider
// ---------------------------------------------------------------------------------------------

class PhonePreviewView(ctx: Context) : View(ctx) {
    var frac = AppPrefs.PILL_DEFAULT
        set(v) {
            field = v
            invalidate()
        }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((116 * d).toInt(), (214 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = 20f * d
        r.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clip)

        // wallpaper
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0B2A78.toInt(), 0xFF4B3FD0.toInt(), 0xFF7B54E6.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(r, p)
        p.shader = RadialGradient(
            w * 0.15f, h * 0.98f, w * 0.95f,
            intArrayOf(0x8822D3EE.toInt(), 0x0022D3EE), null, Shader.TileMode.CLAMP
        )
        c.drawRect(r, p)
        p.shader = null

        // kamera dan jam
        p.color = 0xA0000000.toInt()
        c.drawCircle(w / 2f, 9f * d, 3.2f * d, p)
        p.color = Color.WHITE
        p.alpha = 225
        p.textAlign = Paint.Align.CENTER
        p.textSize = 27f * d
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        c.drawText("11:23", w / 2f, h * 0.20f, p)
        p.alpha = 255
        p.typeface = Typeface.DEFAULT

        // dock: kartu keluar dari pil ke atas
        val pillH = 18f * d
        val pillCY = h * frac
        val pillTop = pillCY - pillH / 2f
        val cardH = 21f * d
        val gap = 5f * d
        for (k in 2 downTo 0) {
            val bottom = pillTop - 6f * d - k * (cardH + gap)
            val top = bottom - cardH
            val inset = w * 0.08f
            r.set(inset, top, w - inset, bottom)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.alpha = 78 - k * 14
            c.drawRoundRect(r, 7f * d, 7f * d, p)
            p.alpha = 190 - k * 40
            c.drawCircle(inset + 8f * d, (top + bottom) / 2f, 3.4f * d, p)
            r.set(inset + 16f * d, top + 5.5f * d, w - inset - 12f * d, top + 8.5f * d)
            c.drawRoundRect(r, 2f * d, 2f * d, p)
            r.set(inset + 16f * d, top + 11.5f * d, w - inset - 30f * d, top + 14.5f * d)
            c.drawRoundRect(r, 2f * d, 2f * d, p)
        }

        // pil
        val pw = 74f * d
        r.set(w / 2f - pw / 2f, pillTop, w / 2f + pw / 2f, pillTop + pillH)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.alpha = 70
        c.drawRoundRect(r, pillH / 2f, pillH / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.color = Brand.cyan
        p.alpha = 200
        c.drawRoundRect(r, pillH / 2f, pillH / 2f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.alpha = 240
        p.textSize = 8.4f * d
        p.textAlign = Paint.Align.CENTER
        c.drawText("\u2022 3 Notifications", w / 2f, pillCY + 3f * d, p)

        // tombol senter / kamera
        p.alpha = 60
        c.drawCircle(w * 0.15f, h * 0.955f, 6.5f * d, p)
        c.drawCircle(w * 0.85f, h * 0.955f, 6.5f * d, p)
        p.alpha = 255
        c.restore()

        // bingkai ponsel
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        p.color = Color.WHITE
        p.alpha = 70
        r.set(0.8f * d, 0.8f * d, w - 0.8f * d, h - 0.8f * d)
        c.drawRoundRect(r, rad, rad, p)
        p.alpha = 255
        p.style = Paint.Style.FILL
    }
}

// ---------------------------------------------------------------------------------------------
// pembangun view
// ---------------------------------------------------------------------------------------------

fun tv(ctx: Context, text: CharSequence, sp: Float, color: Int, bold: Boolean = false): TextView {
    val t = TextView(ctx)
    t.text = text
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    t.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    return t
}

fun lpv(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.m(start: Int, top: Int, end: Int, bottom: Int): LinearLayout.LayoutParams {
    marginStart = start
    topMargin = top
    marginEnd = end
    bottomMargin = bottom
    return this
}

fun rippleOf(pal: Pal, content: Drawable?): Drawable = RippleDrawable(
    ColorStateList.valueOf(if (pal.night) 0x33FFFFFF else 0x22000000),
    content,
    if (content == null) ColorDrawable(Color.WHITE) else null
)

fun cardView(ctx: Context, pal: Pal): LinearLayout {
    val l = LinearLayout(ctx)
    l.orientation = LinearLayout.VERTICAL
    val bg = GradientDrawable()
    bg.cornerRadius = ctx.dpf(22f)
    bg.setColor(pal.card)
    bg.setStroke(ctx.dp(1), pal.stroke)
    l.background = bg
    l.clipToOutline = true
    return l
}

fun sectionLabel(ctx: Context, pal: Pal, text: String): TextView {
    val t = tv(ctx, text, 13f, pal.accent, true)
    t.setPaddingRelative(ctx.dp(6), 0, ctx.dp(6), 0)
    t.layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .m(0, ctx.dp(20), 0, ctx.dp(8))
    return t
}

fun dividerView(ctx: Context, pal: Pal, insetStartDp: Int): View {
    val v = View(ctx)
    v.setBackgroundColor(pal.stroke)
    v.layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).m(ctx.dp(insetStartDp), 0, 0, 0)
    return v
}

/** Kotak ikon dengan gradient biru -> ungu kebiruan. */
fun badgeView(ctx: Context, kind: Int, sizeDp: Int): FrameLayout {
    val f = FrameLayout(ctx)
    val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Brand.blue, Brand.indigo, Brand.violet))
    bg.cornerRadius = ctx.dpf(sizeDp * 0.32f)
    f.background = bg
    val isz = ctx.dp((sizeDp * 0.54f).toInt())
    f.addView(IconView(ctx, kind, Color.WHITE), FrameLayout.LayoutParams(isz, isz, Gravity.CENTER))
    return f
}

/** Tombol ikon bulat transparan putih untuk header. */
fun headerIconButton(ctx: Context, kind: Int, desc: String, onClick: () -> Unit): FrameLayout {
    val f = FrameLayout(ctx)
    val bg = GradientDrawable()
    bg.shape = GradientDrawable.OVAL
    bg.setColor(0x2EFFFFFF)
    f.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), bg, null)
    f.isClickable = true
    f.isFocusable = true
    f.contentDescription = desc
    val isz = ctx.dp(22)
    f.addView(IconView(ctx, kind, Color.WHITE), FrameLayout.LayoutParams(isz, isz, Gravity.CENTER))
    f.setOnClickListener { onClick() }
    return f
}

fun gradientButton(ctx: Context, text: String, iconKind: Int = -1): LinearLayout {
    val l = LinearLayout(ctx)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER
    val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Brand.blue, Brand.indigo, Brand.violet))
    bg.cornerRadius = ctx.dpf(22f)
    l.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), bg, null)
    l.setPaddingRelative(ctx.dp(18), ctx.dp(11), ctx.dp(18), ctx.dp(11))
    l.isClickable = true
    l.isFocusable = true
    if (iconKind >= 0) {
        val sz = ctx.dp(18)
        l.addView(IconView(ctx, iconKind, Color.WHITE), lpv(sz, sz).m(0, 0, ctx.dp(8), 0))
    }
    l.addView(tv(ctx, text, 14f, Color.WHITE, true))
    return l
}

fun textButton(ctx: Context, pal: Pal, text: String): TextView {
    val t = tv(ctx, text, 14f, pal.accent, true)
    t.gravity = Gravity.CENTER
    t.setPaddingRelative(ctx.dp(16), ctx.dp(11), ctx.dp(16), ctx.dp(11))
    t.background = rippleOf(pal, null)
    t.isClickable = true
    t.isFocusable = true
    return t
}

// ---------------------------------------------------------------------------------------------
// jendela: bilah sistem transparan, ikon status putih di atas header gradient
// ---------------------------------------------------------------------------------------------

fun setupWindow(a: Activity, pal: Pal) {
    val w = a.window
    w.statusBarColor = Color.TRANSPARENT
    w.navigationBarColor = Color.TRANSPARENT
    w.setBackgroundDrawable(ColorDrawable(pal.bg))
    if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        val ic = w.insetsController
        if (ic != null) {
            // status bar: ikon putih (di atas gradient). Navigation bar: ikon mengikuti terang/gelap
            ic.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            ic.setSystemBarsAppearance(
                if (pal.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    } else {
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}

/** cb(top, bottom) = tinggi status bar dan navigation bar (px). */
@Suppress("DEPRECATION")
fun onInsets(v: View, cb: (Int, Int) -> Unit) {
    v.setOnApplyWindowInsetsListener { _, ins ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            cb(i.top, i.bottom)
        } else {
            cb(ins.systemWindowInsetTop, ins.systemWindowInsetBottom)
        }
        ins
    }
    v.requestApplyInsets()
}

fun layoutDirOf(lang: String): Int =
    if (lang == Strings.AR) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

// ---------------------------------------------------------------------------------------------
// tautan
// ---------------------------------------------------------------------------------------------

fun openUrl(ctx: Context, lang: String, url: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (t: Throwable) {
        Toast.makeText(ctx, Strings.get(lang, "link_failed"), Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------------------------------------
// dialog (sheet) bergaya kartu
// ---------------------------------------------------------------------------------------------

class Sheet(private val act: Activity, pal: Pal, lang: String) {
    val dialog = Dialog(act)
    val content = LinearLayout(act)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = FrameLayout(act)
        outer.layoutDirection = layoutDirOf(lang)
        outer.setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(20))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(act.dp(22), act.dp(22), act.dp(22), act.dp(16))
        val sv = ScrollView(act)
        val bg = GradientDrawable()
        bg.cornerRadius = act.dpf(28f)
        bg.setColor(pal.dialog)
        bg.setStroke(act.dp(1), pal.stroke)
        sv.background = bg
        sv.clipToOutline = true
        sv.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(outer)
        val win = dialog.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun show() {
        if (!act.isFinishing) dialog.show()
    }
}

fun showRestartConfirm(act: Activity, pal: Pal, lang: String) {
    val s = Sheet(act, pal, lang)
    val c = s.content
    c.addView(tv(act, Strings.get(lang, "restart_title"), 19f, pal.text, true))
    c.addView(tv(act, Strings.get(lang, "restart_msg"), 14f, pal.sub).apply {
        layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, act.dp(8), 0, act.dp(20))
    })
    val row = LinearLayout(act)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    val cancel = textButton(act, pal, Strings.get(lang, "cancel"))
    cancel.setOnClickListener { s.dialog.dismiss() }
    val ok = gradientButton(act, Strings.get(lang, "restart_yes"), IconView.RESET)
    ok.setOnClickListener {
        s.dialog.dismiss()
        Toast.makeText(act, Strings.get(lang, "restarting"), Toast.LENGTH_SHORT).show()
        RootUtil.restartSystemUi { good ->
            if (!good) Toast.makeText(act, Strings.get(lang, "root_needed"), Toast.LENGTH_LONG).show()
        }
    }
    row.addView(cancel)
    row.addView(ok, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(act.dp(8), 0, 0, 0))
    c.addView(row, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    s.show()
}

fun showLanguageSheet(act: Activity, pal: Pal, lang: String, onPicked: () -> Unit) {
    val s = Sheet(act, pal, lang)
    val c = s.content
    c.addView(tv(act, Strings.get(lang, "settings"), 19f, pal.text, true))
    c.addView(tv(act, Strings.get(lang, "language"), 13.5f, pal.sub).apply {
        layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, act.dp(4), 0, act.dp(14))
    })
    val options = listOf(
        Pair(Strings.EN, "English"),
        Pair(Strings.ID, "Bahasa Indonesia"),
        Pair(Strings.AR, "العربية")
    )
    for ((code, label) in options) {
        val selected = code == lang
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
        val bg = GradientDrawable()
        bg.cornerRadius = act.dpf(16f)
        bg.setColor(if (selected) pal.chip else Color.TRANSPARENT)
        bg.setStroke(act.dp(if (selected) 2 else 1), if (selected) Brand.blue else pal.stroke)
        row.background = RippleDrawable(ColorStateList.valueOf(0x22808080), bg, null)
        row.isClickable = true
        row.isFocusable = true
        val g = IconView(act, IconView.GLOBE, if (selected) pal.accent else pal.sub)
        row.addView(g, lpv(act.dp(22), act.dp(22)).m(0, 0, act.dp(12), 0))
        val name = tv(act, label, 16f, pal.text, selected)
        row.addView(name, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) row.addView(IconView(act, IconView.CHECK, pal.accent), lpv(act.dp(22), act.dp(22)))
        row.setOnClickListener {
            s.dialog.dismiss()
            if (code != lang) {
                AppPrefs.setLang(act, code)
                onPicked()
            }
        }
        c.addView(row, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, 0, 0, act.dp(8)))
    }
    val close = textButton(act, pal, Strings.get(lang, "cancel"))
    close.setOnClickListener { s.dialog.dismiss() }
    c.addView(close, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.END
        topMargin = act.dp(4)
    })
    s.show()
}

// ---------------------------------------------------------------------------------------------
// laporan bug: Device, TranOS version, Problem -> dikirim ke chat Telegram pengembang
// ---------------------------------------------------------------------------------------------

private fun getprop(key: String): String = try {
    val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
    val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
    p.waitFor()
    out
} catch (t: Throwable) {
    ""
}

private fun deviceName(): String {
    val maker = Build.MANUFACTURER ?: ""
    val model = Build.MODEL ?: ""
    val m = if (maker.isEmpty()) "" else maker.substring(0, 1).uppercase() + maker.substring(1)
    return if (model.startsWith(maker, true)) model else "$m $model".trim()
}

/** Versi TranOS/XOS dari property Transsion; kalau tidak ada, pakai build display. Panggil di thread latar. */
private fun tranOsVersion(): String {
    val keys = listOf("ro.tranos.version", "ro.tranos.os.version", "ro.transsion.os.version", "ro.os.version")
    var v = ""
    for (k in keys) {
        v = getprop(k)
        if (v.isNotEmpty()) break
    }
    val disp = Build.DISPLAY ?: ""
    return if (v.isNotEmpty()) "$v ($disp)" else disp
}

private fun inputField(act: Activity, pal: Pal, value: String, hint: String, lines: Int): EditText {
    val e = EditText(act)
    e.setText(value)
    e.hint = hint
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    e.setTextColor(pal.text)
    e.setHintTextColor(pal.sub and 0x00FFFFFF or 0x99000000.toInt())
    val bg = GradientDrawable()
    bg.cornerRadius = act.dpf(14f)
    bg.setColor(pal.inputBg)
    bg.setStroke(act.dp(1), pal.stroke)
    e.background = bg
    e.setPaddingRelative(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
    e.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    e.gravity = Gravity.START or (if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL)
    if (lines > 1) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.minLines = lines
        e.maxLines = 8
    } else {
        e.inputType = InputType.TYPE_CLASS_TEXT
        e.setSingleLine(true)
    }
    return e
}

private fun cleanOldLogs(dir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
    dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
}

/** Gabungkan info perangkat, isi laporan, dan log modul jadi satu file teks di cache/logs. */
private fun writeLogFile(act: Activity, report: String, moduleLog: String, rootOk: Boolean): File? = try {
    val dir = File(act.cacheDir, "logs")
    dir.mkdirs()
    cleanOldLogs(dir)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val f = File(dir, "ioslock_log_$stamp.txt")
    val sb = StringBuilder()
    sb.append("IOSLock bug report\n")
    sb.append("Time: ").append(Date().toString()).append('\n')
    sb.append("App: IOSLock ").append(AppInfo.VERSION).append('\n')
    sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
    sb.append("Build: ").append(Build.DISPLAY).append('\n')
    sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n")
    sb.append("---- Report ----\n").append(report).append("\n\n")
    sb.append("---- Module log ----\n")
    if (rootOk) sb.append(moduleLog) else sb.append("(root access denied, module log not available)\n")
    f.writeText(sb.toString())
    f
} catch (t: Throwable) {
    null
}

/** Bagikan laporan + file log ke Telegram. Pengguna memilih chat @mkbtgr di daftar bagikan Telegram. */
private fun shareReportWithLog(act: Activity, lang: String, report: String, file: File) {
    val uri = Uri.parse("content://" + LogFileProvider.AUTHORITY + "/" + Uri.encode(file.name))
    val i = Intent(Intent.ACTION_SEND)
    i.type = "text/plain"
    i.putExtra(Intent.EXTRA_STREAM, uri)
    i.putExtra(Intent.EXTRA_TEXT, report)
    i.putExtra(Intent.EXTRA_SUBJECT, "IOSLock bug report")
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("IOSLock log", uri)
    Toast.makeText(act, Strings.get(lang, "pick_chat"), Toast.LENGTH_LONG).show()
    val telegrams = listOf(
        "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thunderdog.challegram", "org.telegram.plus"
    )
    for (pkg in telegrams) {
        try {
            val t = Intent(i)
            t.setPackage(pkg)
            act.startActivity(t)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: Throwable) {
        }
    }
    try {
        act.startActivity(Intent.createChooser(i, "Telegram"))
    } catch (t: Throwable) {
        openUrl(act, lang, AppInfo.REPORT_URL + "?text=" + Uri.encode(report))
    }
}

fun showBugReport(act: Activity, pal: Pal, lang: String) {
    val s = Sheet(act, pal, lang)
    val c = s.content
    fun label(key: String) = tv(act, Strings.get(lang, key), 13f, pal.sub, true).apply {
        layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(act.dp(4), act.dp(12), 0, act.dp(6))
    }

    val head = LinearLayout(act)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(badgeView(act, IconView.BUG, 38), lpv(act.dp(38), act.dp(38)).m(0, 0, act.dp(12), 0))
    head.addView(tv(act, Strings.get(lang, "bug_header"), 19f, pal.text, true))
    c.addView(head, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val device = inputField(act, pal, deviceName(), Strings.get(lang, "f_device"), 1)
    val tran = inputField(act, pal, "…", Strings.get(lang, "f_tranos"), 1)
    val problem = inputField(act, pal, "", Strings.get(lang, "hint_problem"), 4)

    c.addView(label("f_device"))
    c.addView(device, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    c.addView(label("f_tranos"))
    c.addView(tran, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    c.addView(label("f_problem"))
    c.addView(problem, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // lampirkan log (default nyala)
    val attachRow = LinearLayout(act)
    attachRow.orientation = LinearLayout.HORIZONTAL
    attachRow.gravity = Gravity.CENTER_VERTICAL
    val attachTexts = LinearLayout(act)
    attachTexts.orientation = LinearLayout.VERTICAL
    attachTexts.addView(tv(act, Strings.get(lang, "log_attach"), 14.5f, pal.text, true))
    attachTexts.addView(tv(act, Strings.get(lang, "log_attach_desc"), 12f, pal.sub).apply {
        layoutParams = lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, act.dp(2), 0, 0)
    })
    val attach = ToggleView(act, pal)
    attach.contentDescription = Strings.get(lang, "log_attach")
    attach.setChecked(true, user = false, animate = false)
    attachRow.addView(attachTexts, lpv(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).m(act.dp(4), 0, act.dp(12), 0))
    attachRow.addView(attach, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    attachRow.setOnClickListener { attach.toggle() }
    c.addView(attachRow, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(0, act.dp(16), 0, 0))

    val note = tv(act, Strings.get(lang, "bug_note_log"), 12.5f, pal.sub)
    note.layoutParams = lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(act.dp(4), act.dp(14), 0, act.dp(14))
    c.addView(note)
    attach.onChange = { on -> note.text = Strings.get(lang, if (on) "bug_note_log" else "bug_note") }

    val row = LinearLayout(act)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    val cancel = textButton(act, pal, Strings.get(lang, "cancel"))
    cancel.setOnClickListener { s.dialog.dismiss() }
    val send = gradientButton(act, Strings.get(lang, "send"), IconView.TELEGRAM)
    send.setOnClickListener {
        val problemText = problem.text.toString().trim()
        if (problemText.isEmpty()) {
            Toast.makeText(act, Strings.get(lang, "problem_required"), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val report = "Device: ${device.text.toString().trim()}\n" +
            "TranOS version: ${tran.text.toString().trim()}\n" +
            "Problem: $problemText\n\n" +
            "IOSLock ${AppInfo.VERSION} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        // laporan selalu disalin juga, sebagai cadangan kalau Telegram tidak mengisi pesannya
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IOSLock report", report))
        } catch (t: Throwable) { }

        if (!attach.checked) {
            s.dialog.dismiss()
            Toast.makeText(act, Strings.get(lang, "copied"), Toast.LENGTH_LONG).show()
            openUrl(act, lang, AppInfo.REPORT_URL + "?text=" + Uri.encode(report))
            return@setOnClickListener
        }

        // dengan log: kumpulkan log modul lewat root, simpan sebagai file, lalu bagikan ke Telegram
        send.isEnabled = false
        send.alpha = 0.6f
        Toast.makeText(act, Strings.get(lang, "log_collecting"), Toast.LENGTH_SHORT).show()
        RootUtil.collectLog { text, rootOk ->
            if (act.isFinishing || act.isDestroyed) return@collectLog
            s.dialog.dismiss()
            if (!rootOk) Toast.makeText(act, Strings.get(lang, "log_no_root"), Toast.LENGTH_LONG).show()
            val file = writeLogFile(act, report, text, rootOk)
            if (file != null) {
                shareReportWithLog(act, lang, report, file)
            } else {
                Toast.makeText(act, Strings.get(lang, "copied"), Toast.LENGTH_LONG).show()
                openUrl(act, lang, AppInfo.REPORT_URL + "?text=" + Uri.encode(report))
            }
        }
    }
    row.addView(cancel)
    row.addView(send, lpv(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).m(act.dp(8), 0, 0, 0))
    c.addView(row, lpv(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // versi TranOS dibaca di thread latar, lalu mengisi kolomnya kalau belum diubah pengguna
    Thread {
        val v = tranOsVersion()
        act.runOnUiThread {
            if (tran.text.toString() == "…") tran.setText(v)
        }
    }.start()

    s.show()
}
