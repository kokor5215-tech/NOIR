package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * NOIR TUNING — preferensi "rasa" visual (Settings > UI), terinspirasi
 * chip kontrol Aurora Dock Kit (glass / motion / motes) tapi native.
 *
 * Nilai di-cache volatile; refresh() dipanggil saat Settings berubah dan
 * saat layar utama lahir. Aturan guide dihormati: motion menggerakkan
 * tempo DAN chroma bersama (calm 0.72 / ambient 1 / lively 1.22).
 */
object NoirTuning {

    const val KEY_GLASS = "noir_glass_key"
    const val KEY_MOTION = "noir_motion_key"
    const val KEY_MOTES = "noir_motes_key"
    const val KEY_DOCK = "noir_dock_feedback_key"
    const val KEY_ANIM = "noir_anim_key"

    /** 0 tipis / 1 normal / 2 tebal -> pengali alpha kaca & rim. */
    @Volatile var glassScale = 1f
    /** Kecepatan drift aura. */
    @Volatile var driftSpeed = 1f
    /** Skala chroma (guide 2.3: motion scales chroma too). */
    @Volatile var chromaScale = 1f
    /** 0 mati / 1 aurora (bernapas) / 2 life (lahir-mati). */
    @Volatile var motesMode = 2
    /** Feedback tekan dock (squeeze + pegas). */
    @Volatile var dockFeedback = true
    /** Master switch: matikan semua gerakan Noir. */
    @Volatile var animOff = false

    fun refresh(context: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(context)
        glassScale = when (p.getInt(KEY_GLASS, 1)) {
            0 -> 0.6f; 2 -> 1.5f; else -> 1f
        }
        val m = p.getInt(KEY_MOTION, 1).coerceIn(0, 2)
        driftSpeed = floatArrayOf(0.6f, 1f, 1.3f)[m]
        chromaScale = floatArrayOf(0.72f, 1f, 1.22f)[m]
        motesMode = p.getInt(KEY_MOTES, 2).coerceIn(0, 2)
        dockFeedback = p.getBoolean(KEY_DOCK, true)
        animOff = p.getBoolean(KEY_ANIM, false)
    }
}
