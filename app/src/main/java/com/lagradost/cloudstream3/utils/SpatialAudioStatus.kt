package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * NOIR — status audio spasial (Dolby Atmos / Spatial Audio).
 *
 * ------------------------------------------------------------------
 * PENTING — kenapa tidak ada tombol on/off di sini
 * ------------------------------------------------------------------
 * `Spatializer.setEnabled()` di Android ditandai sebagai
 * `@SystemApi(client = PRIVILEGED_APPS)` dan butuh permission
 * `MODIFY_DEFAULT_AUDIO_EFFECTS` yang hanya diberikan ke aplikasi sistem.
 *
 * Artinya: **aplikasi pihak ketiga tidak diizinkan menyalakan/mematikan
 * audio spasial.** Menampilkan toggle di sini akan menghasilkan
 * SecurityException atau kegagalan diam-diam — lebih buruk daripada
 * tidak ada tombol sama sekali.
 *
 * Yang BISA dilakukan aplikasi, dan yang dilakukan proyek ini:
 *   1. Menyetel AudioAttributes dengan benar di pemutar
 *      (CS3IPlayer: USAGE_MEDIA + AUDIO_CONTENT_TYPE_MOVIE), supaya
 *      pipeline audio sistem memilih profil yang tepat. Tanpa ini,
 *      perangkat yang mampu pun bisa salah memperlakukan audionya.
 *   2. Menampilkan status sebenarnya kepada user.
 *   3. Mengarahkan user ke setelan sistem untuk menyalakannya.
 *
 * ------------------------------------------------------------------
 * Soal Dolby Atmos secara khusus
 * ------------------------------------------------------------------
 * Atmos bukan fitur yang bisa "dipasang" ke aplikasi. Ia butuh tiga hal
 * sekaligus: (a) konten yang memang di-encode dengan metadata Atmos,
 * (b) perangkat bersertifikat, dan (c) diaktifkan di tingkat sistem.
 * Sebagian besar file dari situs streaming adalah AAC/MP3 stereo biasa
 * tanpa metadata Atmos, jadi tidak ada yang bisa "diubah jadi Atmos".
 *
 * Kelas ini hanya melaporkan apa yang sebenarnya terjadi, tanpa klaim
 * yang tidak bisa ditepati.
 */
object SpatialAudioStatus {

    private const val TAG = "SpatialAudioStatus"

    /** Ringkasan status untuk ditampilkan di Pengaturan. */
    enum class State {
        /** Android terlalu tua (< 12L). */
        UNSUPPORTED_OS,

        /** Perangkat tidak punya kemampuan spasialisasi. */
        UNSUPPORTED_DEVICE,

        /** Perangkat mampu, tapi output audio saat ini tidak kompatibel
         *  (mis. speaker mono, atau headphone belum terhubung). */
        UNAVAILABLE_ROUTING,

        /** Tersedia tapi user mematikannya di setelan sistem. */
        AVAILABLE_DISABLED,

        /** Aktif dan berjalan. */
        ACTIVE,

        /** Gagal dibaca — anggap tidak tersedia. */
        UNKNOWN
    }

    fun query(context: Context): State {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return State.UNSUPPORTED_OS

        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return State.UNKNOWN
            val spat: Spatializer = am.spatializer ?: return State.UNKNOWN

            if (spat.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
                return State.UNSUPPORTED_DEVICE
            }
            if (!spat.isAvailable) return State.UNAVAILABLE_ROUTING
            if (!spat.isEnabled) return State.AVAILABLE_DISABLED

            State.ACTIVE
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membaca status spatializer: ${e.message}")
            State.UNKNOWN
        }
    }

    /** Teks singkat untuk ringkasan preferensi. */
    fun describe(context: Context, state: State): String = when (state) {
        State.ACTIVE ->
            "Aktif — audio spasial sedang berjalan di perangkat ini."
        State.AVAILABLE_DISABLED ->
            "Perangkat mendukung, tapi sedang nonaktif. Nyalakan di Setelan Sistem → Suara → Audio spasial."
        State.UNAVAILABLE_ROUTING ->
            "Perangkat mendukung, tapi output audio saat ini tidak kompatibel. Sambungkan headphone yang mendukung."
        State.UNSUPPORTED_DEVICE ->
            "Perangkat ini tidak mendukung audio spasial."
        State.UNSUPPORTED_OS ->
            "Butuh Android 12L atau lebih baru."
        State.UNKNOWN ->
            "Status tidak dapat dibaca di perangkat ini."
    }

    /**
     * Buka setelan suara sistem. Tidak ada intent spesifik untuk halaman
     * "audio spasial", jadi kita pakai halaman Suara dan fallback ke
     * setelan utama kalau tidak tersedia.
     */
    fun openSystemSettings(context: Context): Boolean {
        val candidates = listOf(
            Settings.ACTION_SOUND_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in candidates) {
            try {
                context.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) {
                // coba berikutnya
            }
        }
        return false
    }
}
