package com.lagradost.cloudstream3.utils

/**
 * NOIR — filter otomatis provider mati (v2).
 *
 * Masalah: provider mati membuang waktu — pencarian menunggu timeout,
 * beranda memuat baris kosong, user menunggu hasil yang tak datang.
 *
 * Solusi: lacak kegagalan beruntun per provider (exception/timeout pada
 * search / getMainPage / load). Tiga kali gagal beruntun => provider
 * "cooldown": dilewati oleh pencarian & fallback beranda. Satu sukses
 * mereset penghitung. Setelah cooldown habis provider diberi satu
 * kesempatan lagi sebelum bisa diblokir ulang.
 *
 * NOIR FIX v2 — diagnosis "keluar app lama, balik lagi search rusak":
 * 1. Kegagalan level JARINGAN (DNS/socket/UnknownHost) TIDAK dihitung.
 *    Saat perangkat tidur lama, jaringanlah yang mati, bukan
 *    provider-nya. Versi lama salah menghukum semua provider sehingga
 *    tidak ada satu pun yang muncul sampai user force-stop.
 * 2. resetAll() dipanggil MainActivity saat app kembali dari latar
 *    belakang yang lama — kondisi jaringan pasti sudah berubah.
 *
 * Hanya in-memory per sesi — tiap aplikasi dimulai semua dinilai ulang,
 * jadi provider yang sempat mati tapi hidup lagi tidak hilang permanen.
 */
object NoirProviderHealth {
    private const val FAIL_THRESHOLD = 3
    private const val COOLDOWN_MS = 10 * 60_000L

    private val fails = HashMap<String, Int>()
    private val blockedUntil = HashMap<String, Long>()

    /**
     * @param isNetworkError true jika gagal karena jaringan (DNS, socket,
     * timeout koneksi), bukan karena provider-nya. Kegagalan jaringan
     * tidak pernah menghukum provider.
     */
    @Synchronized
    fun recordFail(api: String, isNetworkError: Boolean = false) {
        if (isNetworkError) {
            // Ini salah jaringan, bukan salah provider — jangan tambah dosa,
            // malah bersihkan riwayatnya supaya penilaian tetap adil.
            fails.remove(api)
            return
        }
        val c = (fails[api] ?: 0) + 1
        fails[api] = c
        if (c >= FAIL_THRESHOLD) {
            blockedUntil[api] = System.currentTimeMillis() + COOLDOWN_MS
        }
    }

    @Synchronized
    fun recordOk(api: String) {
        fails[api] = 0
        blockedUntil.remove(api)
    }

    /**
     * Hapus seluruh riwayat hukuman — dipakai saat app kembali dari
     * latar belakang panjang (jaringan berganti, penilaian lama usang).
     */
    @Synchronized
    fun resetAll() {
        fails.clear()
        blockedUntil.clear()
    }

    @Synchronized
    fun isBlocked(api: String): Boolean {
        val until = blockedUntil[api] ?: return false
        if (System.currentTimeMillis() >= until) {
            blockedUntil.remove(api)
            // satu kesempatan: kegagalan berikut langsung memblokir lagi
            fails[api] = FAIL_THRESHOLD - 1
            return false
        }
        return true
    }
}
