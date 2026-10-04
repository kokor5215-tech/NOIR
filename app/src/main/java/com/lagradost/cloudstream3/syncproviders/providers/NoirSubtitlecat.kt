package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * NOIR SUBTITLECAT — sumber subtitle ekstra, TANPA LOGIN, fokus bahasa
 * Indonesia. Scrape ringan subtitlecat.com (16+ juta subtitle mesin di
 * semua bahasa, termasuk ID) — menutup celah saat OpenSubtitles
 * rate-limit (kunci komunitas) dan SubDL butuh akun.
 *
 * Alur: index.php?search=X -> daftar rilis (.html) -> halaman rilis
 * memuat link .srt langsung per bahasa -> saring bahasa target + "en"
 * sebagai cadangan. Link .srt bisa diunduh langsung tanpa auth.
 */
class NoirSubtitlecat : SubtitleAPI() {

    override val name = "Subtitlecat"
    override val idPrefix = "noirsubtitlecat"
    override val requiresLogin = false

    companion object {
        const val HOST = "https://www.subtitlecat.com"
        // Link di HTML bisa absolut ATAU relatif ("/subs/..." atau "subs/...")
        private val RELEASE_PAGE_REGEX =
            Regex("""href="((?:https?://www\.subtitlecat\.com)?/?subs/\d+/[^"]+\.html)"""")
        private val SRT_LINK_REGEX =
            Regex("""href="((?:https?://www\.subtitlecat\.com)?/subs/\d+/[^"]+-([A-Za-z]{2,6}(?:-[A-Za-z]{2,6})?)\.srt)"""")

        /** Jadikan URL absolut (link relatif -> HOST). */
        fun absolute(url: String): String = when {
            url.startsWith("http") -> url
            url.startsWith("/") -> HOST + url
            else -> "$HOST/$url"
        }
    }

    private fun langFromTag(tag: String?): String {
        val t = tag?.lowercase()?.substringBefore("-")?.trim().orEmpty()
        return when (t) {
            "in", "ind", "id" -> "id"
            "" -> "id" // default pengguna Noir: Indonesia
            else -> t
        }
    }

    private fun decode(name: String): String =
        try { URLDecoder.decode(name, "UTF-8") } catch (_: Throwable) { name }

    override suspend fun search(
        auth: AuthData?,
        query: AbstractSubtitleEntities.SubtitleSearch
    ): List<AbstractSubtitleEntities.SubtitleEntity>? {
        val wanted = langFromTag(query.lang)
        val cleanQuery = query.query
            .replace(Regex("""\(\s*\d{4}\s*\)"""), " ")
            .replace(Regex("[^A-Za-z0-9 .:&'-]"), " ")
            .trim()
        if (cleanQuery.isBlank()) return null

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchHtml = try {
            app.get("$HOST/index.php?search=$encoded").text
        } catch (_: Throwable) {
            return null
        }

        val releasePages = RELEASE_PAGE_REGEX.findAll(searchHtml)
            .map { absolute(it.groupValues[1]) }
            .distinct()
            .take(6)
            .toList()
        if (releasePages.isEmpty()) return null

        // Ambil semua halaman rilis paralel supaya cepat.
        val pagesHtml = coroutineScope {
            releasePages.map { url ->
                async {
                    try { app.get(url).text } catch (_: Throwable) { null }
                }
            }.map { it.await() }
        }

        val yearStr = query.year?.toString()
        val kept = mutableSetOf<String>()
        val entities = ArrayList<AbstractSubtitleEntities.SubtitleEntity>()

        for (html in pagesHtml) {
            if (html == null) continue
            for (m in SRT_LINK_REGEX.findAll(html)) {
                val url = absolute(m.groupValues[1])
                val lang = m.groupValues[2].lowercase().substringBefore("-")
                // hanya bahasa target + Inggris sebagai cadangan
                if (lang != wanted && !(lang == "en" && wanted != "en")) continue
                if (!kept.add(url)) continue

                val fileName = decode(url.substringAfterLast('/'))
                val releaseName = fileName
                    .substringBeforeLast("-$lang.srt")
                    .replace('.', ' ')
                    .replace('_', ' ')
                    .trim()

                val nameNorm = releaseName.lowercase()
                val queryNorm = cleanQuery.lowercase()
                // bila judul sama sekali tidak cocok, buang (anti-nyasar)
                val firstToken = queryNorm.substringBefore(' ')
                if (firstToken.length >= 3 && !nameNorm.contains(firstToken)) continue

                entities.add(
                    AbstractSubtitleEntities.SubtitleEntity(
                        idPrefix = idPrefix,
                        name = releaseName,
                        lang = lang,
                        data = url,
                        source = name,
                        year = query.year
                    )
                )
                if (entities.size >= 24) break
            }
            if (entities.size >= 24) break
        }

        // paling relevan dulu: judul mengandung query utuh, lalu tahun cocok
        val qLower = cleanQuery.lowercase()
        return entities.sortedWith(
            compareByDescending<AbstractSubtitleEntities.SubtitleEntity> {
                it.name.lowercase().contains(qLower)
            }.thenByDescending { yearStr != null && it.name.contains(yearStr) }
                .thenByDescending { it.lang == wanted }
        ).ifEmpty { null }
    }

    override suspend fun load(
        auth: AuthData?,
        subtitle: AbstractSubtitleEntities.SubtitleEntity
    ): String = subtitle.data.replace(" ", "%20") // nama rilis memuat spasi
}
