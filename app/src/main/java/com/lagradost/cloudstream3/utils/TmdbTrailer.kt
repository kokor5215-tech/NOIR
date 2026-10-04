package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/**
 * NOIR — trailer otomatis dari TMDB.
 *
 * Masalah yang dipecahkan:
 *   Kebanyakan provider tidak mengisi field trailer, sehingga tombol
 *   trailer bawaan di halaman detail sering kosong padahal app sudah
 *   punya pemutar trailer yang lengkap.
 *
 * Solusi:
 *   Bila LoadResponse belum punya trailer dan punya id TMDB, ambil
 *   trailer resmi dari API TMDB (kunci API sudah ada di proyek ini,
 *   dipakai TmdbProvider & SearchSuggestionApi) lalu suntikkan lewat
 *   addTrailer() — UI trailer bawaan langsung menyala.
 *
 * Gagal ambil trailer TIDAK boleh mengganggu halaman detail:
 *   semua kesalahan ditelan + dilog.
 */
object TmdbTrailer {
    private const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

    @Serializable
    data class Video(
        @JsonProperty("key") @SerialName("key") val key: String? = null,
        @JsonProperty("site") @SerialName("site") val site: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("official") @SerialName("official") val official: Boolean? = null,
    )

    @Serializable
    data class Videos(
        @JsonProperty("results") @SerialName("results") val results: List<Video>? = null,
    )

    /** Suntik trailer TMDB ke [response] bila belum punya. Aman gagal. */
    suspend fun enrich(response: LoadResponse) {
        try {
            if (response.trailers.isNotEmpty()) return
            val id = response.getTMDbId()?.toIntOrNull() ?: return
            val kind = if (response.type == TvType.Movie) "movie" else "tv"
            val videos = app.get(
                "https://api.themoviedb.org/3/$kind/$id/videos?api_key=$TMDB_KEY&language=en-US",
                cacheTime = 60,
                cacheUnit = TimeUnit.MINUTES,
            ).parsedSafe<Videos>() ?: return

            val yt = videos.results?.filter {
                it.site == "YouTube" && !it.key.isNullOrBlank()
            } ?: return

            val pick = yt.firstOrNull { it.type == "Trailer" && it.official == true }
                ?: yt.firstOrNull { it.type == "Trailer" }
                ?: yt.firstOrNull { it.type == "Teaser" }
                ?: return

            response.addTrailer("https://www.youtube.com/watch?v=${pick.key}")
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
