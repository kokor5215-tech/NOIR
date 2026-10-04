package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * NOIR — hero yang LELEH, bukan "nimpa".
 *
 * Masalah lama: banner hero berupa persegi tajam yang menimpa latar dinamis
 * (aura) sehingga terlihat seperti tambalan. Solusi ala Apple Music: bagian
 * bawah artwork di-mask DST_OUT dengan gradient alpha, sehingga gambar
 * MELARUT menjadi transparan dan aura dinamis di bawahnya terlihat tembus —
 * satu permukaan kontinu, tanpa tepi bawah yang putus.
 *
 * Performa: mask berupa satu LinearGradient statis (bukan blur realtime),
 * digambar di hardware layer — biaya per frame nyaris nol.
 */
class NoirFadeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var fadeShader: LinearGradient? = null
    private var lastH = -1

    init {
        // DST_OUT butuh buffer offscreen; hardware = komposit di GPU.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != lastH && h > 0) {
            lastH = h
            // mulai memudar dari 55% tinggi; 100% = sepenuhnya transparan
            fadeShader = LinearGradient(
                0f, h * 0.55f, 0f, h.toFloat(),
                0x00000000, 0xFF000000.toInt(),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = fadeShader ?: return
        fadePaint.shader = s
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
    }
}
