package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.utils.SubtitleHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

/**
 * NOIR ISUBTITLES — sumber subtitle ekstra, TANPA LOGIN, sangat kaya untuk
 * bahasa Indonesia (komunitas subtitle film/seri Indonesia banyak mengunggah
 * di sini, termasuk rilis WEB-DL/Netflix yang sudah sinkron). Alur:
 *
 * 1. GET /search?kwd=JUDUL -> daftar judul ("/slug-subtitles").
 * 2. GET /slug-subtitles   -> tabel baris per subtitle: link unduh
 *    "/download/slug/bahasa/id" dan nama rilis.
 * 3. Link unduh mengarahkan ke berkas .zip yang langsung diunduh lalu
 *    diekstrak oleh pipeline SubtitleResource (addZipUrl).
 *
 * Bahasa target diprioritaskan; Inggris disertakan sebagai cadangan.
 */
class NoirISubtitles : SubtitleAPI() {

    override val name = "ISubtitles"
    override val idPrefix = "noirisubtitles"
    override val requiresLogin = false

    companion object {
        const val HOST = "https://isubtitles.org"

        // <h3><a href="/inception-subtitles" title="Inception  - (2010) Subtitles">
        private val SEARCH_RESULT_REGEX =
            Regex("""<h3>\s*<a href="/([a-z0-9][a-z0-9-]*?)-subtitles"""", RegexOption.IGNORE_CASE)

        // href="/download/inception/indonesian/10022636"
        private val DOWNLOAD_LINK_REGEX =
            Regex("""href="(/download/[^"]+)"""")

        // <td class="movie-release" data-title="Release / Movie"> ... <a ...>NAMA</a>
        private val RELEASE_NAME_REGEX =
            Regex("""data-title="Release / Movie">\s*(?:<a[^>]*>)?([^<]+)""")

        private val SEASON_EP_REGEX =
            Regex("""[Ss](\d{1,2})\s*[Eex](\d{1,3})|(\d{1,2})x(\d{2,3})""")

        // Penamaan alternatif: "Episode 7", "Ep 3", dst.
        private val EP_WORD_REGEX =
            Regex("""\bep(?:isode)?\.?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

        fun normalizeTitle(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun langFromTag(tag: String?): String {
        val t = tag?.lowercase()?.substringBefore("-")?.trim().orEmpty()
        return when (t) {
            "in", "ind", "id", "" -> "id" // default pengguna Noir: Indonesia
            else -> t
        }
    }

    private fun langSegment(downloadPath: String): String {
        // /download/slug/bahasa/123
        val parts = downloadPath.trimStart('/').split('/')
        return parts.getOrNull(2)?.lowercase().orEmpty()
    }

    override suspend fun search(
        auth: AuthData?,
        query: AbstractSubtitleEntities.SubtitleSearch
    ): List<AbstractSubtitleEntities.SubtitleEntity>? {
        val cleanQuery = query.query
            .replace(Regex("""\(\s*\d{4}\s*\)"""), " ")
            .replace(Regex("[^A-Za-z0-9 .:&'-]"), " ")
            .trim()
        if (cleanQuery.isBlank()) return null

        val wantedTag = langFromTag(query.lang)
        val wantedName = SubtitleHelper.fromTagToEnglishLanguageName(query.lang)
            ?.lowercase().orEmpty()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchHtml = try {
            app.get("$HOST/search?kwd=$encoded").text
        } catch (_: Throwable) {
            return null
        }

        // Ambil maksimal 6 kandidat judul; slugs unik.
        val slugs = SEARCH_RESULT_REGEX.findAll(searchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(6)
            .toList()
        if (slugs.isEmpty()) return null

        val queryNorm = normalizeTitle(cleanQuery)
        // Prioritaskan slug yang judulnya paling mirip dengan permintaan.
        val rankedSlugs = slugs.sortedByDescending { slug ->
            val slugNorm = normalizeTitle(slug.replace('-', ' '))
            when {
                slugNorm == queryNorm -> 100
                slugNorm.startsWith(queryNorm) || queryNorm.startsWith(slugNorm) -> 70
                slugNorm.contains(queryNorm) || queryNorm.contains(slugNorm) -> 50
                slugNorm.split(" ").firstOrNull() ==
                        queryNorm.split(" ").firstOrNull() -> 15
                else -> 0
            }
        }.take(3)

        // Ambil halaman judul paralel supaya cepat.
        val pagesHtml = coroutineScope {
            rankedSlugs.map { slug ->
                async {
                    try {
                        app.get("$HOST/$slug-subtitles").text
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.map { it.await() }
        }

        val entities = ArrayList<AbstractSubtitleEntities.SubtitleEntity>()
        val kept = mutableSetOf<String>()

        for (html in pagesHtml) {
            if (html == null) continue
            // Baris tabel diproses per potongan <tr> agar nama rilis
            // terpasang pada link unduh yang benar.
            for (chunk in html.split("<tr>")) {
                val dl = DOWNLOAD_LINK_REGEX.find(chunk) ?: continue
                val path = dl.groupValues[1]
                if (!kept.add(path)) continue

                val langSeg = langSegment(path)
                val isTarget = langSeg == wantedName ||
                        (wantedTag == "id" && langSeg == "indonesian") ||
                        langSeg == wantedTag
                val isEnglish = langSeg == "english"
                // Bahasa target dulu; Inggris hanya sebagai cadangan.
                if (!isTarget && !(isEnglish && wantedTag != "en")) continue

                val releaseName = RELEASE_NAME_REGEX.find(chunk)
                    ?.groupValues?.get(1)?.trim().orEmpty()

                val url = HOST + path
                val displayName = releaseName.ifBlank { cleanQuery }

                entities.add(
                    AbstractSubtitleEntities.SubtitleEntity(
                        idPrefix = idPrefix,
                        name = displayName,
                        lang = langSeg,
                        data = url,
                        source = name,
                        epNumber = query.epNumber,
                        seasonNumber = query.seasonNumber,
                        year = query.year
                    )
                )
                if (entities.size >= 36) break
            }
            if (entities.size >= 36) break
        }

        if (entities.isEmpty()) return null

        // Untuk seri: prioritaskan baris yang memuat nomor musim/episode
        // yang diminta, tanpa membuang sisanya (kadang penamaan bebas).
        val season = query.seasonNumber
        val ep = query.epNumber
        fun matchesEpisode(e: AbstractSubtitleEntities.SubtitleEntity): Boolean {
            if (season == null && ep == null) return true
            val m = SEASON_EP_REGEX.find(e.name)
            if (m != null) {
                val s = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull()
                val n = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull()
                return (season == null || s == season) && (ep == null || n == ep)
            }
            val w = EP_WORD_REGEX.find(e.name)
            if (w != null && ep != null) {
                return w.groupValues[1].toIntOrNull() == ep
            }
            return false
        }

        val withEpisodeMatch = entities.filter { matchesEpisode(it) }
        val pool = withEpisodeMatch.ifEmpty { entities }

        return pool.sortedWith(
            compareByDescending<AbstractSubtitleEntities.SubtitleEntity> {
                val l = it.lang.lowercase()
                l == wantedName || l == "indonesian" || l == wantedTag
            }.thenByDescending {
                normalizeTitle(it.name).contains(queryNorm)
            }.thenByDescending { it.lang.lowercase() != "english" }
        ).distinctBy { it.data }.ifEmpty { null }
    }

    override suspend fun SubtitleResource.getResources(
        auth: AuthData?,
        subtitle: AbstractSubtitleEntities.SubtitleEntity
    ) {
        // Link unduh mengarahkan ke berkas zip; pipeline mengekstrak otomatis.
        this.addZipUrl(subtitle.data) { entryName, _ -> entryName }
    }
}
