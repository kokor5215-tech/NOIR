package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.preference.PreferenceManager

/**
 * NOIR THEME ENGINE — satu sumber kebenaran warna lapisan NOIR.
 *
 * Prinsip anti-"gado-gado": tiap tema mendefinisikan SATU aksen + SATU
 * resep kaca + SATU resep aura. SEMUA elemen NOIR (aura, pill navigasi,
 * panel settings, search pill, hairline progress) mengambil warna dari
 * sini, sehingga tidak ada elemen yang lepas dari elemen lain.
 *
 * Default: MONOCHROME — grayscale bercahaya + kaca ala Apple Liquid
 * Glass; warna hanya datang samar dari poster, bukan dari UI.
 */
object NoirTheme {

    data class Spec(
        val id: Int,
        val name: String,
        val desc: String,
        val auraSaturation: Float,    // 0 = aura monokrom penuh
        val auraLumVibrant: Float,
        val auraLumMuted: Float,
        val accent: Int,              // hairline, progress, aksen kecil
        val glassFill: Int,           // isi kaca panel/pill
        val glassStroke: Int,         // hairline kaca
        val accentFromAura: Boolean,  // true = aksen hidup mengikuti poster
    )

    val SPECS = arrayOf(
        Spec(
            0, "Monochrome Noir",
            "Bawaan. Grayscale bercahaya + kaca Apple; semua elemen satu suara putih/abu.",
            // NOIR FIX: saturasi aura 0.06 membuat aura terlihat hitam mati
            // di layar pengguna. Naikkan ke 0.5 supaya latar tetap "berenang
            // warna" lembut (aturan aurora: chroma tenang, bukan neon),
            // sementara UI (teks, kaca, aksen) tetap grayscale.
            0.50f, 0.66f, 0.50f,
            0xFFF2F2F7.toInt(), 0x26FFFFFF, 0x66FFFFFF, false
        ),
        Spec(
            1, "Aurora Colorful",
            "Aura saturasi penuh dari poster; aksen & progress ikut warna dominan.",
            1.35f, 0.62f, 0.48f,
            0xFF8B5CF6.toInt(), 0x26FFFFFF, 0x66FFFFFF, true
        ),
        Spec(
            2, "Cinema Gold",
            "Amber hangat seperti cahaya proyektor; kaca ikut bersemu emas.",
            0.50f, 0.55f, 0.42f,
            0xFFE3B778.toInt(), 0x26E3B778, 0x55E3B778, false
        ),
        Spec(
            3, "Ocean Deep",
            "Biru laut dalam yang tenang; kaca bersemu sian lembut.",
            0.60f, 0.52f, 0.40f,
            0xFF6FD3E8.toInt(), 0x266FD3E8, 0x556FD3E8, false
        ),
    )

    private const val PREF_KEY = "noir_theme_id"

    fun current(context: Context): Spec {
        val id = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_KEY, 0)
        return SPECS.getOrElse(id) { SPECS[0] }
    }

    fun set(context: Context, id: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(PREF_KEY, id.coerceIn(0, SPECS.lastIndex)).apply()
    }

    /** Panel kaca sesuai tema + rim cahaya atas (resep dock kit). */
    fun glass(context: Context, radiusDp: Float): LayerDrawable {
        val s = current(context)
        val r = dp(context, radiusDp)
        val base = GradientDrawable().apply { setColor(sa(s.glassFill, com.lagradost.cloudstream3.utils.NoirTuning.glassScale)); cornerRadius = r }
        val topHi = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sa(0x59FFFFFF, com.lagradost.cloudstream3.utils.NoirTuning.glassScale), 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val stroke = GradientDrawable().apply {
            setStroke(dp(context, 1f).toInt(), sa(s.glassStroke, com.lagradost.cloudstream3.utils.NoirTuning.glassScale))
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, topHi, stroke))
    }

    /** DOCK GLASS (adaptasi Aurora Dock Kit 3.4): piring GELAP bernuansa
     *  tema + rim cahaya tiga lapis (top highlight, bottom lip, edge) +
     *  inner bloom aksen. Ikon putih tetap terbaca — kaca susu adalah
     *  kegagalan yang dicoba-dibatalkan oleh guide. */
    fun navBackground(context: Context): LayerDrawable {
        val s = current(context)
        val r = dp(context, 32f)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sa(plate(s, 0x59, 0.11f), com.lagradost.cloudstream3.utils.NoirTuning.glassScale), sa(plate(s, 0x7A, 0.07f), com.lagradost.cloudstream3.utils.NoirTuning.glassScale))
        ).apply { cornerRadius = r }
        val bloom = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(withAlpha(s.accent, 0.14f), 0x00000000)
            gradientRadius = dp(context, 180f)
            setGradientCenter(0.5f, 0.1f)
        }
        val topHi = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sa(0x94FFFFFF.toInt(), com.lagradost.cloudstream3.utils.NoirTuning.glassScale), 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val lip = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(sa(0x1AFFFFFF, com.lagradost.cloudstream3.utils.NoirTuning.glassScale), 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val stroke = GradientDrawable().apply {
            setStroke(dp(context, 1f).toInt(), 0x33FFFFFF)
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, bloom, topHi, lip, stroke))
    }

    /** Pengali alpha kaca dari NoirTuning (Tipis/Normal/Tebal). */
    private fun sa(c: Int, f: Float): Int {
        val a = (((c ushr 24) * f).toInt()).coerceIn(0, 255)
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    /** Tambah alpha fraksi (0.0 sampai 1.0) ke sebuah warna. */
    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (fraction.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** Piring gelap bernuansa hue aksen (bukan hitam/abu netral). */
    private fun plate(s: Spec, alpha: Int, v: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(s.accent, hsv)
        hsv[1] = (hsv[1] * 0.6f).coerceAtMost(0.45f)
        hsv[2] = v
        return Color.HSVToColor(alpha, hsv)
    }

    /** LIQUID GLASS beranda (search pill): isi lebih padat + highlight atas
     *  + hairline terang — kaca hidup, bukan wadah hitam. */
    fun liquidGlass(context: Context, radiusDp: Float): LayerDrawable {
        val r = dp(context, radiusDp)
        val base = GradientDrawable().apply {
            setColor(0x33FFFFFF.toInt() and 0xFFFFFFFF.toInt())
            cornerRadius = r
        }
        val highlight = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sa(0x59FFFFFF, com.lagradost.cloudstream3.utils.NoirTuning.glassScale), 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val bloom = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(withAlpha(current(context).accent, 0.12f), 0x00000000)
            gradientRadius = dp(context, 160f)
            setGradientCenter(0.5f, 0.2f)
        }
        val lip = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(sa(0x1AFFFFFF, com.lagradost.cloudstream3.utils.NoirTuning.glassScale), 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val stroke = GradientDrawable().apply {
            setStroke(dp(context, 1f).toInt(), 0x99FFFFFF.toInt())
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, bloom, highlight, lip, stroke))
    }

    private fun withAlphaTop(c: Int): Int = (c and 0x00FFFFFF) or 0x26000000
    private fun darken(c: Int): Int = (c and 0x00FFFFFF) or 0x14000000

    private fun dp(context: Context, v: Float): Float =
        v * context.resources.displayMetrics.density
}
