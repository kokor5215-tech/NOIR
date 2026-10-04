package com.lagradost.cloudstream3.utils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.palette.graphics.Palette
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * NOIR "Aura" v2 — ambient mesh ala Apple Music, ditingkatkan dengan aturan
 * estetika aurora dari expert (superdesign.dev, fireworkwebdesign, designmd):
 *
 *  1. VOID BASE wajib: latar hampir hitam (#050508) supaya cahaya "menyala".
 *  2. BLUR/SATURATE: gradient radial lembut + saturasi 1.35 supaya warna
 *     tidak jadi lumpur abu-abu (kesalahan v1: warna terlalu dim/redup).
 *  3. MAKS 4 HUE: vibrant poster + muted poster + campuran + satu aksen
 *     KOMPLEMENTER (hue diputar 180 derajat, alpha kecil) = kedalaman
 *     "high-tension" tanpa jadi norak.
 *  4. DRIFT LAMBAT 8-15 detik (v1 terlalu cepat) — bernafas, bukan berdenyut.
 *  5. VIGNETTE tepi: cahaya fokus di tengah, tepi tenggelam ke void =
 *     kedalaman sinematik.
 *
 * PERFORMA tetap: drift 15fps, berhenti saat detached / saat scroll /
 * saat sistem menyetel reduce-motion.
 */
private const val NOIR_DRIFT_FPS = 15L

class NoirAuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var t = 0f
    private var drifting = false

    private var curA = 0xFF232326.toInt()
    private var curB = 0xFF1A1A1D.toInt()
    private var fromA = curA
    private var fromB = curB
    private var toA = curA
    private var toB = curB
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    private val driftRunnable = object : Runnable {
        override fun run() {
            // v2: drift lebih lambat (siklus ~12 dtk) — "breathe, not pulse".
            t += 0.018f * com.lagradost.cloudstream3.utils.NoirTuning.driftSpeed
            invalidate()
            postDelayed(this, 1000L / NOIR_DRIFT_FPS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startDrift()
    }

    override fun onDetachedFromWindow() {
        stopDrift()
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startDrift() {
        if (com.lagradost.cloudstream3.utils.NoirTuning.animOff) return
        if (!drifting) {
            drifting = true
            postDelayed(driftRunnable, 1000L / NOIR_DRIFT_FPS)
        }
    }

    private fun stopDrift() {
        drifting = false
        removeCallbacks(driftRunnable)
    }

    /** NOIR theme: warna hidup untuk aksen (progress hairline dll). */
    fun liveColor(): Int = curA

    fun setDriftEnabled(enabled: Boolean) {
        if (enabled) startDrift() else stopDrift()
    }

    /** v2: JANGAN dim berlebihan — saturate 1.35 + lum secukupnya = menyala. */
    private fun grade(c: Int, satBoost: Float, lumFactor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * satBoost * com.lagradost.cloudstream3.utils.NoirTuning.chromaScale).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * lumFactor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Floor near-black ber-tint hue dominan (chroma kecil, v 0.03). */
    private fun tintedFloor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(curA, hsv)
        hsv[1] = (hsv[1] * 0.5f).coerceAtMost(0.30f)
        hsv[2] = 0.030f
        return Color.HSVToColor(hsv)
    }

    private fun drawMotes(canvas: Canvas, w: Float, h: Float) {
        val mode = com.lagradost.cloudstream3.utils.NoirTuning.motesMode
        if (mode == 0) return
        for (i in 0 until 7) {
            val seed = i * 12.9898f
            val period = 3.2f + (i % 5) * 0.7f
            val life = ((t * 0.8f + i * 1.7f) % period) / period
            val o = if (mode == 1)
                0.35f + 0.30f * sin(t * 1.1f + seed).toFloat()   // aurora: bernapas
            else
                sin(life * PI).toFloat().pow(1.4f)               // life: lahir-mati
            if (o <= 0.02f) continue
            val x = w * (0.5f + sin(t * 0.6f + seed) * 0.40f)
            val y = h * (0.5f + cos(t * 0.5f + seed * 1.31f) * 0.38f)
            val rr = maxOf(w, h) * (0.05f + 0.045f * ((i * 7) % 5) / 4f)
            paint.shader = RadialGradient(
                x, y, rr,
                intArrayOf(withAlpha(curA, 0.30f * o), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    /** Hue komplementer (diputar 180 derajat) untuk aksen kedalaman. */
    private fun complementary(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt(),
    )

    private fun withAlpha(c: Int, a: Float): Int = Color.argb(
        (255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c),
    )

    fun setColors(a: Int, b: Int) {
        fromA = curA
        fromB = curB
        // v3: resep grade datang dari NoirTheme — monochrome vs colorful
        // selalu konsisten di semua permukaan.
        val spec = NoirTheme.current(context)
        toA = grade(a, spec.auraSaturation, spec.auraLumVibrant)
        toB = grade(b, spec.auraSaturation * 0.95f, spec.auraLumMuted)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                curA = lerpColor(fromA, toA, f)
                curB = lerpColor(fromB, toB, f)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // FLOOR: near-black ber-TINT hue dominan (Aurora Dock Kit 2.3) —
        // hitam murni menyebabkan halation & kesan "wallpaper murahan".
        val floor = tintedFloor()
        canvas.drawColor(floor)
        val r = maxOf(w, h)
        val mix = lerpColor(curA, curB, 0.5f)
        val comp = complementary(curA)

        // Blob 1 — vibrant poster, glow lebih kuat (alpha .68)
        paint.shader = RadialGradient(
            w * (0.5f + sin(t) * 0.32f), h * (0.28f + cos(t * 0.6f) * 0.26f), r * 0.85f,
            intArrayOf(withAlpha(curA, 0.68f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // Blob 2 — muted poster, fase berbeda
        paint.shader = RadialGradient(
            w * (0.5f + sin(t + 2.1f) * 0.34f), h * (0.38f + cos(t * 0.6f + 2.1f) * 0.26f), r * 0.80f,
            intArrayOf(withAlpha(curB, 0.60f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // Blob 3 — campuran, lapisan tengah
        paint.shader = RadialGradient(
            w * (0.5f + sin(t + 4.2f) * 0.30f), h * (0.66f + cos(t * 0.6f + 4.2f) * 0.22f), r * 0.90f,
            intArrayOf(withAlpha(mix, 0.42f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // Blob 4 — aksen KOMPLEMENTER alpha kecil: kedalaman high-tension
        paint.shader = RadialGradient(
            w * (0.5f + sin(t * 0.8f + 1.3f) * 0.38f), h * (0.75f + cos(t * 0.5f + 3f) * 0.20f), r * 0.70f,
            intArrayOf(withAlpha(comp, 0.20f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // MOTES "life": gelembung lahir-hidup-mati-lahir lagi dengan jam
        // masing-masing (fase negatif, tak pernah sefase) — akuarium hidup.
        drawMotes(canvas, w, h)
        // VIGNETTE — tepi tenggelam ke floor tint, cahaya fokus di tengah.
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, r * 0.95f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(floor, 0.80f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /**
     * Unduh poster (versi kecil), ekstrak vibrant+muted via Palette,
     * terapkan dengan animasi. Gagal jaringan = aura tetap warna sebelumnya.
     */
    fun applyFromUrl(url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                }
                conn.inputStream.use { ins ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeStream(ins, null, opts) ?: return@Thread
                    val p = Palette.from(bmp).generate()
                    val dom = p.getDominantColor(0xFF3A3A3C.toInt())
                    val c1 = p.getVibrantColor(dom)
                    val c2 = p.getMutedColor(dom)
                    post { setColors(c1, c2) }
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (_: Exception) {
                // jaringan goyah: biarkan aura warna lama, jangan crash
            }
        }.start()
    }
}
