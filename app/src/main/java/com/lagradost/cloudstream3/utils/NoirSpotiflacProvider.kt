package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder

/**
 * NOIR fix16 — provider musik agregator "Noir Music".
 *
 * Tiga sumber dicari paralel dalam SATU provider:
 *  1. SoundCloud — runtime extension resmi SpotiFLAC di Rhino
 *     (full track, HLS AAC);
 *  2. Qobuz      — katalog publik + preview FLAC bertanda MD5 (lihat
 *     [NoirHiRes]); tanpa akun stream penuh tidak legal tersedia, jadi
 *     yang diputar adalah preview 30 detik yang DILABELI jujur;
 *  3. Tidal      — katalog publik web; play penuh dicocokkan silang
 *     (cross-match) ke SoundCloud persis filosofi SpotiFLAC.
 *
 * Hasil di-dedup per lagu (judul+artis ternormalisasi), sumber terbaik
 * (paling bisa diputar) yang menang.
 */
class NoirSpotiflacProvider : MainAPI() {

    override var mainUrl = "https://soundcloud.com"
    override var name = "Noir Music"
    override val supportedTypes = setOf(TvType.Music)
    override var lang = "en"
    override val hasMainPage = false
    override val hasQuickSearch = false

    private fun norm(s: String?): String =
        (s ?: "").lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun titleKey(title: String?, artist: String?): String =
        norm(title) + "|" + norm(artist).split(" ").firstOrNull().orEmpty()

    override suspend fun search(query: String): List<SearchResponse>? =
        coroutineScope {
            val ctx = CommonActivity.activity
            val scReady = ctx?.let { NoirSpotiflac.ensureLoaded(it) } == true

            val scJob = if (scReady) async { NoirSpotiflac.search(query, 15) } else null
            val qzJob = async { NoirHiRes.qobuzSearch(query, 12) }
            val tlJob = async { NoirHiRes.tidalSearch(query, 12) }

            // Kumpulkan jadi satu bentuk + tag sumber di URL-nya.
            val merged = ArrayList<Triple<String, NoirHiRes.NoirTrack, String>>()

            scJob?.await()?.forEach { t ->
                val id = t.id ?: return@forEach
                merged.add(
                    Triple(
                        titleKey(t.name, t.artists),
                        NoirHiRes.NoirTrack(
                            source = "soundcloud",
                            id = id,
                            title = t.name ?: id,
                            artist = t.artists,
                            cover = t.coverUrl,
                            badge = "FULL",
                        ),
                        "noirsc://$id",
                    )
                )
            }
            qzJob.await().forEach { t ->
                val suffix = java.net.URLEncoder.encode(
                    listOfNotNull(t.artist, t.title).joinToString(" "), "UTF-8"
                )
                merged.add(
                    Triple(titleKey(t.title, t.artist), t, "noirqz://${t.id}?t=$suffix")
                )
            }
            tlJob.await().forEach { t ->
                val suffix = java.net.URLEncoder.encode(
                    listOfNotNull(t.artist, t.title).joinToString(" "), "UTF-8"
                )
                merged.add(
                    Triple(titleKey(t.title, t.artist), t, "noirtl://${t.id}?t=$suffix")
                )
            }
            if (merged.isEmpty()) return@coroutineScope null

            // Dedup: sumber paling bisa diputar menang
            // (soundcloud=full > qobuz=preview > tidal=cross-match).
            val rank = mapOf("soundcloud" to 0, "qobuz" to 1, "tidal" to 2)
            val best = LinkedHashMap<String, Pair<NoirHiRes.NoirTrack, String>>()
            merged
                .sortedBy { rank[it.second.source] ?: 3 }
                .forEach { (_, t, url) ->
                    best.putIfAbsent(titleKey(t.title, t.artist), t to url)
                }

            best.values.mapNotNull { (t, url) ->
                val label = when (t.source) {
                    "qobuz" -> "Qobuz ${t.badge ?: ""}".trim()
                    "tidal" -> "Tidal"
                    else -> "Noir Full"
                }
                newMovieSearchResponse(
                    name = listOfNotNull(t.title, t.artist).joinToString(" - ") + " ($label)",
                    url = url,
                    type = TvType.Music,
                    fix = false,
                ) {
                    this.posterUrl = t.cover
                }
            }
        }

    /** "noirqz://123?t=Foo%20Bar" → ("123", "Foo Bar") */
    private fun parseRef(url: String): Pair<String, String?> {
        val body = url.substringAfter("://")
        val id = body.substringBefore("?")
        val t = Regex("[?&]t=([^&]+)").find(body)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return id to t
    }

    private fun displayTitle(url: String): String =
        parseRef(url).second ?: url

    override suspend fun load(url: String): LoadResponse? {
        return newMovieLoadResponse(
            name = displayTitle(url),
            url = url,
            type = TvType.Music,
            dataUrl = url,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ctx = CommonActivity.activity ?: return false
        val (id, title) = parseRef(data)
        return when {
            data.startsWith("noirsc://") -> {
                if (!NoirSpotiflac.ensureLoaded(ctx)) return false
                val (url, hls) = NoirSpotiflac.streamUrl(id) ?: return false
                callback(
                    newExtractorLink(
                        source = name,
                        name = "SoundCloud ${if (hls) "HLS AAC" else "AAC"}",
                        url = url,
                    ) {
                        type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        quality = Qualities.P720.value
                    }
                )
                true
            }

            data.startsWith("noirqz://") -> {
                // Preview FLAC resmi (30 detik) — full file butuh langganan.
                val preview = NoirHiRes.qobuzPreviewUrl(id)
                if (preview != null) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Qobuz Preview FLAC (30 dtk)",
                            url = preview,
                        ) {
                            type = ExtractorLinkType.VIDEO
                            quality = Qualities.P1080.value
                        }
                    )
                    true
                } else {
                    // Album (bukan track) / gagal → cocokkan silang ke full.
                    crossMatchToFull(title, callback)
                }
            }

            data.startsWith("noirtl://") -> crossMatchToFull(title, callback)

            else -> false
        }
    }

    /** Cross-match judul ke source full (SoundCloud runtime SpotiFLAC). */
    private suspend fun crossMatchToFull(
        title: String?,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ctx = CommonActivity.activity ?: return false
        if (title.isNullOrBlank()) return false
        if (!NoirSpotiflac.ensureLoaded(ctx)) return false
        val hit = NoirSpotiflac.search(title, 5).firstOrNull { it.id != null } ?: return false
        val (url, hls) = NoirSpotiflac.streamUrl(hit.id!!) ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = "Noir Full (cross-match)",
                url = url,
            ) {
                type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                quality = Qualities.P720.value
            }
        )
        return true
    }
}
