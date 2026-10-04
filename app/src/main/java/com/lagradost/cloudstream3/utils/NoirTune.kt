package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * NOIR fix15: tuning adaptif & pintar yang menyesuaikan source.
 *
 * 1) Bandwidth EWMA diambil dari ExoPlayer (onBandwidthEstimate) dan dipakai
 *    untuk menurunkan prioritas kualitas yang melebihi kemampuan jaringan —
 *    default link jadi adaptif, bukan selalu yang tertinggi (anti-buffering).
 * 2) Deteksi konten musik vs film per-source: atribut audio disetel berbeda
 *    (musik = CONTENT_TYPE_MUSIC biar pipeline audio memilih profil musik;
 *    film = CONTENT_TYPE_MOVIE) — "menyesuaikan sourcenya".
 */
object NoirTune {

    @Volatile
    var bandwidthBps: Long = 0L
        private set

    /** EWMA 70/30 biar stabil tapi responsif. */
    fun sample(bps: Long) {
        if (bps <= 0) return
        bandwidthBps = if (bandwidthBps <= 0) bps
        else (bandwidthBps * 0.7 + bps * 0.3).toLong()
    }

    // Kebutuhan bitrate kasar per tinggi video (progressive MP4/HLS).
    private fun needBps(quality: Int?): Long = when {
        quality == null -> 0L
        quality >= 2160 -> 25_000_000L
        quality >= 1440 -> 12_000_000L
        quality >= 1080 -> 6_000_000L
        quality >= 720 -> 3_500_000L
        quality >= 480 -> 2_000_000L
        quality >= 360 -> 1_000_000L
        else -> 500_000L
    }

    /**
     * Penalty prioritas: kualitas yang butuh bandwidth lebih dari 80% EWMA
     * didemosi jauh sehingga default jatuh ke kualitas yang aman. Bila semua
     * melebihi (jaringan sangat pelan) urutan relatif tetap sama.
     */
    fun adaptivePenalty(quality: Int?): Int {
        val bw = bandwidthBps
        if (bw <= 0L) return 0
        return if (needBps(quality) > bw * 0.8) 1_000_000 else 0
    }

    /** Source musik? (provider mendeklarasikan TvType.Music). */
    fun isMusicSource(link: ExtractorLink?): Boolean {
        val api = getApiFromNameNull(link?.source) ?: return false
        return api.supportedTypes.contains(TvType.Music)
    }
}
