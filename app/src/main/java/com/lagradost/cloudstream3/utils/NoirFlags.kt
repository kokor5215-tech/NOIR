package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import com.lagradost.cloudstream3.utils.SubtitleHelper.getCountryIso

/**
 * NOIR — ikon bendera profesional (SVG circle-flags, MIT) sebagai
 * VectorDrawable; menggantikan emoji yang tidak konsisten antar-perangkat.
 *
 * Aset: res/drawable/flag_<iso>.xml (117 negara, dikonversi dari
 * github.com/HatScripts/circle-flags).
 */
object NoirFlags {

    /** Resource id ikon bendera untuk kode bahasa/negara; 0 bila tak ada. */
    fun res(context: Context, iso: String?): Int {
        val cc = getCountryIso(iso)?.lowercase() ?: return 0
        return context.resources.getIdentifier(
            "flag_$cc", "drawable", context.packageName
        )
    }

    fun drawable(context: Context, iso: String?): Drawable? {
        val r = res(context, iso)
        if (r == 0) return null
        return try {
            androidx.core.content.ContextCompat.getDrawable(context, r)
        } catch (_: Throwable) { null }
    }

    /**
     * "[ikon bendera] Nama" sebagai Spannable — siap dipakai di item dialog
     * / TextView mana pun (ImageSpan dirender native, rapi & tajam).
     */
    fun span(context: Context, iso: String?, text: String): CharSequence {
        val d = drawable(context, iso) ?: return text
        val size = (18 * context.resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, size, size)
        val out = SpannableString("  $text")
        out.setSpan(
            ImageSpan(d, ImageSpan.ALIGN_BASELINE),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return out
    }
}
