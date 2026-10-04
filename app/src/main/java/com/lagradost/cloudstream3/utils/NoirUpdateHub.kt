package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/**
 * NOIR UPDATE HUB — pusat konfigurasi "hidup tanpa build ulang".
 *
 * Tiga lapisan update ala riset (lihat LAPORAN bagian M):
 *  L1. EXTENSION/PROVIDER: plugin .cs3 di-update otomatis oleh app dari
 *      repository JSON milikmu — TANPA build ulang APK sama sekali. Isi
 *      [NOIR_EXTENSIONS_REPO_URL] sekali di bawah, dan repository itu
 *      ditambahkan otomatis saat app pertama kali jalan.
 *  L2. APK: InAppUpdater sudah memonitor GitHub Releases (BuildConfig
 *      UPDATE_GITHUB_USER/REPO). Workflow GitHub Actions di template
 *      NOIR-repo-template mem-build & merilis APK setiap kamu push tag —
 *      app mengunduh + memasang sendiri. Kamu tidak pernah menyentuh Colab.
 *  L3. Hot-patch dex (Tinker/Robust/Sophix ala WeChat) = ilmu tingkat
 *      akhir; sengaja tidak dipakai: kompleks, rapuh antar-ROM, dan
 *      risiko keamanan. L1+L2 sudah menutup 99% kebutuhan nyata.
 */
object NoirUpdateHub {

    /**
     * ISI DENGAN URL REPOSITORY EXTENSION KAMU, contoh:
     * https://raw.githubusercontent.com/USERNAME/REPO/main/repository.json
     *
     * Biarkan "" (kosong) jika belum punya — bootstrap hanya aktif bila terisi.
     */
    const val NOIR_EXTENSIONS_REPO_URL = ""

    /** Nama repository yang tampil di Setelan -> Extensions. */
    const val NOIR_EXTENSIONS_REPO_NAME = "Noir Extensions"

    /**
     * Tambahkan repository extension Noir sekali saja (idempoten —
     * RepositoryManager.addRepository sudah distinctBy url).
     */
    suspend fun bootstrap(context: Context) {
        val url = NOIR_EXTENSIONS_REPO_URL
        if (url.isBlank()) return
        try {
            val exists = RepositoryManager.getRepositories()
                .any { it.url == url }
            if (!exists) {
                RepositoryManager.addRepository(
                    RepositoryData(NOIR_EXTENSIONS_REPO_NAME, url)
                )
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
