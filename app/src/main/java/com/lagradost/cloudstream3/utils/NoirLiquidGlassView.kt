package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * NOIR LIQUID GLASS — latar intro monokrom ala Apple Liquid Glass yang
 * BENAR-BENAR bergerak: tiga blob kaca putih lembut ber-drift pelan + satu
 * "sheen" diagonal (kilau kaca) yang melintas perlahan seperti cahaya di
 * permukaan gelas basah. 100% monokrom — estetika dari gerak & kedalaman,
 * bukan dari warna.
 *
 * Performa: 15 fps, hanya gradient radial/linear (murah di GPU canvas).
 */
class NoirLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val drift = object : Runnable {
        override fun run() {
            t += 0.016f
            invalidate()
            postDelayed(this, 1000L / 15L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(drift, 1000L / 15L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(drift)
        super.onDetachedFromWindow()
    }

    private fun white(a: Float): Int = Color.argb((255 * a).toInt(), 245, 245, 247)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Void — kaca hidup di atas kegelapan.
        canvas.drawColor(0xFF050508.toInt())
        val r = maxOf(w, h)

        // Tiga blob kaca ber-drift (fase berbeda = terasa cair).
        paint.shader = RadialGradient(
            w * (0.30f + sin0(t * 0.7f) * 0.22f), h * (0.25f + cos0(t * 0.5f) * 0.20f),
            r * 0.75f, intArrayOf(white(0.13f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = RadialGradient(
            w * (0.72f + sin0(t * 0.6f + 2.4f) * 0.20f), h * (0.55f + cos0(t * 0.45f + 1.2f) * 0.22f),
            r * 0.80f, intArrayOf(white(0.10f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = RadialGradient(
            w * (0.5f + sin0(t * 0.5f + 4.4f) * 0.30f), h * (0.85f + cos0(t * 0.4f + 3f) * 0.15f),
            r * 0.70f, intArrayOf(white(0.08f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // SHEEN diagonal — kilau kaca melintas perlahan (~9 detik siklus).
        canvas.save()
        canvas.rotate(-18f, w / 2f, h / 2f)
        val sweep = (sin0(t * 0.35f) * 0.5f + 0.5f) // 0..1 pelan
        val cx = -w * 0.4f + sweep * w * 1.8f
        val bandW = w * 0.55f
        paint.shader = LinearGradient(
            cx - bandW, 0f, cx + bandW, 0f,
            intArrayOf(Color.TRANSPARENT, white(0.085f), white(0.14f), white(0.085f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(-w, -h, 2 * w, 2 * h, paint)
        canvas.restore()

        // Vignette — tepi tenggelam, cahaya fokus di tengah.
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.45f, r * 0.95f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xCC050508.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun sin0(x: Float) = kotlin.math.sin(x.toDouble()).toFloat()
    private fun cos0(x: Float) = kotlin.math.cos(x.toDouble()).toFloat()
}
