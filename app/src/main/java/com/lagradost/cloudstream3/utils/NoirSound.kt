package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R

/**
 * NOIR SOUND v3 — konfigurasi untuk NoirAudioProcessor (DSP in-app).
 *
 * SEJARAH: v1 (gain bertumpuk) clipping; v2 (efek vendor audiofx) tetap
 * "suara listrik" di perangkat nyata karena HAL DSP OEM bug (lihat riset:
 * Symfonium/Unity/Streamify). v3 tidak menyentuh efek vendor sama sekali:
 * semua olah sampel terjadi di pipeline media3, deterministik.
 *
 * Profil hanya kurva EQ LANDAI (maks +2 dB) — karakter tonal, bukan volume.
 * Limiter & high-pass sudah tetap di processor (anti-pecah).
 */
object NoirSound {

    data class Profile(
        val name: String,
        val desc: String,
        val lowDb: Float,   // warmth @100Hz (<= +2)
        val highDb: Float   // air @8kHz (<= +2)
    )

    val PROFILES = arrayOf(
        Profile("Noir Signature", "Hangat + udara halus; bersih di speaker HP.", 1.2f, 1.0f),
        Profile("Bioskop", "Bodi lebih kokoh untuk film & ledakan.", 2.0f, 0.6f),
        Profile("Vokal Jernih", "Dialog diangkat; bas netral.", 0.3f, 1.4f),
        Profile("Musik", "Smile lembut untuk OST & lagu.", 1.5f, 1.5f),
        Profile("Malam", "Sangat netral; tidak mengagetkan di volume rendah.", 0.6f, 0.4f)
    )

    /** Preset terakhir (dibaca processor saat configure). */
    @Volatile
    var lastPreset: Int = 0

    /** Gain gesture volume >100% (0..8 dB) — diproses IN-APP, anti-clip. */
    @Volatile
    var boostDb: Float = 0f

    /** Cache pref AGC (disegarkan factory; processor tak punya Context). */
    @Volatile
    var agcOn: Boolean = false

    private fun agcKey(ctx: Context) = ctx.getString(R.string.noir_sound_agc_key)

    /** "Pengeras film pelan": AGC mengangkat passage pelan, puncak tetap aman. */
    fun agcEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(agcKey(context), true)

    @Volatile
    var lastEnabled: Boolean = false

    fun refresh(context: Context) {
        lastPreset = presetIndex(context)
        lastEnabled = isEnabled(context)
        agcOn = lastEnabled && agcEnabled(context)
    }

    private fun enabledKey(ctx: Context) = ctx.getString(R.string.noir_sound_enabled_key)
    private fun presetKey(ctx: Context) = ctx.getString(R.string.noir_sound_preset_key)

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(enabledKey(context), false)

    fun presetIndex(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(presetKey(context), 0).coerceIn(0, PROFILES.lastIndex)

    /**
     * Dipanggil CS3IPlayer saat sesi audio siap. v3: hanya menyegarkan cache
     * preset — TIDAK ada efek vendor yang dilampirkan (sumber "suara listrik").
     * DSP nyata dipasang lewat NoirRenderersFactory di pipeline media3.
     */
    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        lastPreset = presetIndex(context)
    }
}
