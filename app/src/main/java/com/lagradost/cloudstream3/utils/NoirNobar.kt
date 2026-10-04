package com.lagradost.cloudstream3.utils

import android.util.Base64

/**
 * NOIR — watch party (nobar) tanpa server.
 *
 * Riset komunitas (winwatchparty, SameRow, ViewingParty, Cineby): semua
 * implementasi watch party TIDAK men-stream video — mereka hanya
 * menyinkronkan state pemutaran. Metode "pakai link doang" yang valid =
 * menanam stempel waktu jam-dunia di link (pola schedulePlayback ala NTP):
 *   host  : link membawa (epoch, posisi).
 *   tamu  : membuka halaman yang sama, dan saat player ready langsung
 *           seek ke posisi + (sekarang - epoch).
 * Tidak ada backend, tidak ada akun — cukup share link via NoirShare.
 */
object NoirNobar {
    var pendingEpochMs: Long? = null
    var pendingPosMs: Long? = null

    fun makeLink(apiName: String, url: String, positionMs: Long): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), flags)
        val meta = "${System.currentTimeMillis()},$positionMs"
        return "noirshare://" + b64(apiName) + "_=_" + b64(url) + "_=_" + b64(meta)
    }

    /** Target sinkron; dipanggil sekali saat player ready. */
    fun consumeTarget(): Long? {
        val e = pendingEpochMs ?: return null
        val p = pendingPosMs ?: return null
        pendingEpochMs = null
        pendingPosMs = null
        return p + (System.currentTimeMillis() - e)
    }
}
