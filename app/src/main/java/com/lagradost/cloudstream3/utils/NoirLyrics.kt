package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * NOIR fix18 — provider lirik berlapis; implementasi dan parser ditulis
 * mandiri setelah mempelajari provider Morphe (MorpheApp/morphe-patches,
 * https://github.com/MorpheApp/morphe-patches; GPLv3 source attribution).
 * Tidak menyalin kode provider/parser Morphe ke sini.
 *  1. YouTube Music InnerTube: search → next → browse ANDROID_MUSIC →
 *     timedLyricsData karaoke per baris (live-tested: 26 baris).
 *  2. Musixmatch Android public endpoints: search → richsync/subtitles
 *     (live-tested: token + search + synced LRC; bisa CAPTCHA/rate-limit).
 *  3. BiniLyrics (lyrics-api.binimum.org → lrc.red TTML, word timing).
 *  4. bLyrics (lyrics-api.boidu.dev → TTML, word timing).
 *  5. LRCLIB: exact/search, synced LRC atau teks polos.
 *
 * BiniLyrics dan bLyrics TTML telah diuji langsung dari sandbox. LRC/TTML
 * dinormalisasi ke timestamp per baris; sidecar translation/romanization
 * dipertahankan bila feed menyediakannya. Cache provider 7 hari.
 *
 * Terjemahan fix19 memakai Hy-MT2 1.8B Q4_K_M melalui llama.cpp CPU lokal.
 * Model 1.13 GB diunduh saat diminta, checksum SHA-256 diverifikasi, lalu
 * prompt lirik hanya diproses di perangkat. Romanisasi hanya dipakai jika
 * disediakan sidecar lirik; tidak ada fallback transliterasi/terjemahan cloud.
 */
object NoirLyrics {

    data class LyricLine(val startMs: Long, val endMs: Long, val text: String)

    data class Pack(
        val source: String,
        val synced: Boolean,
        val lines: List<LyricLine>,
        /** Sidecar dari TTML bila provider menyertakan terjemahan/romanisasi. */
        val embeddedTranslations: List<String>? = null,
        val embeddedRomanizations: List<String>? = null,
    )

    private const val YT_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val YT_NEXT =
        "https://music.youtube.com/youtubei/v1/next?key=$YT_KEY&alt=json"
    private const val YT_BROWSE =
        "https://music.youtube.com/youtubei/v1/browse?key=$YT_KEY&alt=json"
    private const val YT_SEARCH =
        "https://music.youtube.com/youtubei/v1/search?key=$YT_KEY&alt=json"
    private const val WEB_REMIX_VERSION = "1.20260914.01.00"
    private const val ANDROID_MUSIC_VERSION = "7.21.50"
    private const val LRC_BASE = "https://lrclib.net/api/"
    private const val BLYRICS_URL = "https://lyrics-api.boidu.dev/getLyrics"
    private const val BINIMUM_URL = "https://lyrics-api.binimum.org/"
    private const val MUSIXMATCH_BASE = "https://apic.musixmatch.com/ws/1.1/"
    private const val MUSIXMATCH_APP_ID = "android-player-v1.0"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val musixmatchMutex = Mutex()
    private var musixmatchToken: String? = null
    private var musixmatchTokenAt = 0L
    private var musixmatchBlockedUntil = 0L
    private var musixmatchLastRequestAt = 0L

    private fun norm(s: String?): String =
        (s ?: "").lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cacheFile(key: String): File? {
        val ctx = CommonActivity.activity ?: return null
        val dir = File(ctx.cacheDir, "noir_lyrics")
        if (!dir.exists()) dir.mkdirs()
        val md5 = MessageDigest.getInstance("MD5")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$md5.json")
    }

    private fun freshCacheFile(key: String): File? {
        val f = cacheFile(key) ?: return null
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) {
            runCatching { f.delete() }
            return null
        }
        return f.takeIf { it.length() > 0 }
    }

    private fun readStringArray(o: JSONObject, key: String): List<String>? {
        val a = o.optJSONArray(key) ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    /**
     * Fallback berlapis yang meniru strategi provider Morphe tanpa menyalin
     * parser mereka: YTMusic → Musixmatch → BiniLyrics → bLyrics → LRCLIB.
     */
    suspend fun fetch(title: String, artist: String?, durationSec: Long): Pack? =
        withContext(Dispatchers.IO) {
            try {
                val key = "lyr|${norm(title)}|${norm(artist)}|$durationSec"
                freshCacheFile(key)?.let { f ->
                    runCatching {
                        val o = JSONObject(f.readText())
                        val rows = o.getJSONArray("lines")
                        val out = ArrayList<LyricLine>()
                        for (i in 0 until rows.length()) {
                            val e = rows.getJSONObject(i)
                            out.add(LyricLine(e.optLong("s", -1), e.optLong("e", -1), e.optString("t", "")))
                        }
                        Pack(
                            source = o.optString("src"),
                            synced = o.optBoolean("syn"),
                            lines = out,
                            embeddedTranslations = readStringArray(o, "tr"),
                            embeddedRomanizations = readStringArray(o, "ro"),
                        )
                    }.getOrNull()?.let { return@withContext it }
                }

                val pack = ytMusic(title, artist)
                    ?: musixmatch(title, artist, durationSec)
                    ?: binimumLyrics(title, artist, durationSec)
                    ?: bLyrics(title, artist, durationSec)
                    ?: lrcLib(title, artist, durationSec)
                    ?: return@withContext null

                if (pack.lines.isNotEmpty()) {
                    cacheFile(key)?.runCatching {
                        val o = JSONObject()
                        o.put("src", pack.source)
                        o.put("syn", pack.synced)
                        val arr = JSONArray()
                        pack.lines.forEach {
                            arr.put(
                                JSONObject()
                                    .put("s", it.startMs).put("e", it.endMs).put("t", it.text)
                            )
                        }
                        o.put("lines", arr)
                        pack.embeddedTranslations?.let { values ->
                            o.put("tr", JSONArray().also { a -> values.forEach { a.put(it) } })
                        }
                        pack.embeddedRomanizations?.let { values ->
                            o.put("ro", JSONArray().also { a -> values.forEach(a::put) })
                        }
                        writeText(o.toString())
                    }
                }
                pack
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

    // ── YouTube Music (InnerTube) ───────────────────────────────────────
    private fun ytHeaders(clientVersion: String) = mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "X-YouTube-Client-Version" to clientVersion,
        "Cookie" to "SOCS=CAI",
        "Origin" to "https://music.youtube.com",
    )

    private fun ytContext(clientName: String, clientVersion: String) =
        JSONObject().put(
            "client",
            JSONObject().put("clientName", clientName)
                .put("clientVersion", clientVersion).put("hl", "en")
        ).put("user", JSONObject())

    private suspend fun ytPost(url: String, body: JSONObject, cv: String): JSONObject? =
        runCatching {
            JSONObject(
                app.post(
                    url,
                    requestBody = body.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    headers = ytHeaders(cv),
                    timeout = 10,
                ).text
            )
        }.getOrNull()

    private fun collectVideoIds(node: Any?, out: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> {
                val rend = node.optJSONObject("musicResponsiveListItemRenderer")
                if (rend != null) {
                    val vid = rend.optJSONObject("playlistItemData")?.optString("videoId")
                    if (!vid.isNullOrBlank()) {
                        var title = ""
                        runCatching {
                            val runs = rend.getJSONArray("flexColumns")
                                .getJSONObject(0)
                                .getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                .getJSONObject("text").getJSONArray("runs")
                            val sb = StringBuilder()
                            for (i in 0 until runs.length()) {
                                sb.append(runs.getJSONObject(i).optString("text"))
                            }
                            title = sb.toString()
                        }
                        out.add(vid to title)
                    }
                }
                node.keys().forEach { k -> collectVideoIds(node.opt(k), out) }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) collectVideoIds(node.opt(i), out)
            }
        }
    }

    /** search → next → browse(ANDROID_MUSIC) → timedLyricsData. */
    private suspend fun ytMusic(title: String, artist: String?): Pack? {
        try {
            val query = listOf(title, artist).filterNotNull().joinToString(" ")
            val search = ytPost(
                YT_SEARCH,
                JSONObject().put("context", ytContext("WEB_REMIX", WEB_REMIX_VERSION))
                    .put("query", query),
                WEB_REMIX_VERSION,
            ) ?: return null
            val cands = ArrayList<Pair<String, String>>()
            collectVideoIds(search, cands)
            val seen = HashSet<String>()
            val uniq = cands.filter { seen.add(it.first) }.take(4)
            if (uniq.isEmpty()) return null

            // Kandidat dengan judul paling mirip dicoba duluan.
            val want = norm(title)
            val ranked = uniq.sortedByDescending { (_, t) ->
                val n = norm(t)
                when {
                    n == want -> 3
                    n.startsWith(want) -> 2
                    n.contains(want) -> 1
                    else -> 0
                }
            }

            for ((vid, _) in ranked.take(3)) {
                val next = ytPost(
                    YT_NEXT,
                    JSONObject().put("context", ytContext("WEB_REMIX", WEB_REMIX_VERSION))
                        .put("videoId", vid).put("playlistId", "RDAMVM$vid"),
                    WEB_REMIX_VERSION,
                ) ?: continue
                val browseId = extractLyricsBrowseId(next) ?: continue
                val browse = ytPost(
                    YT_BROWSE,
                    JSONObject().put("context", ytContext("ANDROID_MUSIC", ANDROID_MUSIC_VERSION))
                        .put("browseId", browseId),
                    ANDROID_MUSIC_VERSION,
                ) ?: continue
                val pack = parseTimedLyrics(browse)
                if (pack != null) return pack
            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }

    private fun extractLyricsBrowseId(next: JSONObject): String? {
        return runCatching {
            val tabs = next.getJSONObject("contents")
                .getJSONObject("singleColumnMusicWatchNextResultsRenderer")
                .getJSONObject("tabbedRenderer")
                .getJSONObject("watchNextTabbedResultsRenderer")
                .getJSONArray("tabs")
            for (i in 0 until tabs.length()) {
                val be = tabs.optJSONObject(i)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("endpoint")
                    ?.optJSONObject("browseEndpoint") ?: continue
                val cfg = be.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig") ?: continue
                if (cfg.optString("pageType") == "MUSIC_PAGE_TYPE_TRACK_LYRICS") {
                    return be.optString("browseId")
                }
            }
            null
        }.getOrNull()
    }

    private fun parseTimedLyrics(browse: JSONObject): Pack? {
        return runCatching {
            val data = browse.getJSONObject("contents")
                .getJSONObject("elementRenderer")
                .getJSONObject("newElement")
                .getJSONObject("type")
                .getJSONObject("componentType")
                .getJSONObject("model")
                .getJSONObject("timedLyricsModel")
                .getJSONObject("lyricsData")
            val arr = data.optJSONArray("timedLyricsData") ?: return null
            val lines = ArrayList<LyricLine>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val text = e.optString("lyricLine", "").trim()
                if (text.isEmpty()) continue
                val cue = e.optJSONObject("cueRange")
                val start = cue?.optString("startTimeMilliseconds", "")?.toLongOrNull() ?: -1
                val end = cue?.optString("endTimeMilliseconds", "")?.toLongOrNull() ?: -1
                lines.add(LyricLine(start, end, text))
            }
            if (lines.none { it.startMs >= 0 }) null
            else Pack("YouTube Music (karaoke)", true, withLineEnds(lines))
        }.getOrNull()
    }

    // ── Musixmatch (Android public client endpoints; throttled + cooldown) ──
    private suspend fun musixmatch(title: String, artist: String?, durationSec: Long): Pack? =
        musixmatchMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                if (now < musixmatchBlockedUntil) return@withLock null
                val token = musixmatchGetToken() ?: return@withLock null
                val searchUrl = MUSIXMATCH_BASE + "track.search" +
                    "?page_size=10&page=1&s_track_rating=desc" +
                    "&q_track=${enc(title)}&q_artist=${enc(artist.orEmpty())}" +
                    (if (durationSec > 0) "&q_duration=$durationSec" else "") +
                    "&usertoken=${enc(token)}&format=json&app_id=$MUSIXMATCH_APP_ID&t=${requestId()}"
                val search = musixmatchJson(searchUrl) ?: return@withLock null
                val searchHeader = search.optJSONObject("message")?.optJSONObject("header")
                if (!acceptMxmHeader(searchHeader)) return@withLock null
                val trackList = search.optJSONObject("message")?.optJSONObject("body")
                    ?.optJSONArray("track_list") ?: return@withLock null
                val targetTitle = norm(title)
                val targetArtist = norm(artist)
                val candidates = (0 until trackList.length()).mapNotNull { i ->
                    trackList.optJSONObject(i)?.optJSONObject("track")
                }.map { track ->
                    var score = matchScore(track.optString("track_name"), targetTitle)
                    score += matchScore(track.optString("artist_name"), targetArtist)
                    val candidateDuration = track.optLong("track_length", 0)
                    if (durationSec > 0 && candidateDuration > 0) {
                        val diff = kotlin.math.abs(candidateDuration - durationSec)
                        score += when {
                            diff <= 3 -> 3
                            diff <= 8 -> 1
                            else -> -3
                        }
                    }
                    if (track.optInt("has_lyrics", 0) == 1) score++
                    track to score
                }.sortedByDescending { it.second }.take(3)

                for ((track, score) in candidates) {
                    if (score < 5) continue
                    val trackId = track.optLong("track_id", -1)
                    if (trackId <= 0) continue
                    val macroUrl = MUSIXMATCH_BASE + "macro.subtitles.get" +
                        "?namespace=lyrics_richsynched&optional_calls=track.richsync" +
                        "&subtitle_format=lrc&track_id=$trackId" +
                        "&f_subtitle_length_max_deviation=40&usertoken=${enc(token)}" +
                        "&format=json&app_id=$MUSIXMATCH_APP_ID&t=${requestId()}"
                    val macro = musixmatchJson(macroUrl) ?: continue
                    val macroHeader = macro.optJSONObject("message")?.optJSONObject("header")
                    if (!acceptMxmHeader(macroHeader)) return@withLock null
                    val calls = macro.optJSONObject("message")?.optJSONObject("body")
                        ?.optJSONObject("macro_calls") ?: continue

                    val rich = calls.optJSONObject("track.richsync.get")
                        ?.optJSONObject("message")
                    val richHeader = rich?.optJSONObject("header")
                    if (richHeader?.optInt("status_code", 0) == 200) {
                        val richBody = rich.optJSONObject("body")?.optJSONObject("richsync")
                            ?.optString("richsync_body")
                        val lines = richBody?.let(::parseRichSync)
                        if (!lines.isNullOrEmpty()) {
                            return@withLock Pack("Musixmatch", true, lines)
                        }
                    }

                    val subtitles = calls.optJSONObject("track.subtitles.get")
                        ?.optJSONObject("message")
                    val subHeader = subtitles?.optJSONObject("header")
                    if (subHeader?.optInt("status_code", 0) == 200) {
                        val rows = subtitles.optJSONObject("body")?.optJSONArray("subtitle_list")
                        val body = rows?.optJSONObject(0)?.optJSONObject("subtitle")
                            ?.optString("subtitle_body")
                        if (!body.isNullOrBlank()) {
                            val lines = parseLrc(body)
                            if (lines.isNotEmpty()) return@withLock Pack("Musixmatch", true, lines)
                        }
                    }

                    val plain = calls.optJSONObject("track.lyrics.get")
                        ?.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("lyrics")
                        ?.optString("lyrics_body")
                    if (!plain.isNullOrBlank()) {
                        val lines = plain.split('\n').map { it.trim() }
                            .filter { it.isNotEmpty() && it != "♪" }
                            .map { LyricLine(-1, -1, it) }
                        if (lines.isNotEmpty()) return@withLock Pack("Musixmatch", false, lines)
                    }
                }
                null
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

    private suspend fun musixmatchGetToken(): String? {
        val now = System.currentTimeMillis()
        musixmatchToken?.takeIf { now - musixmatchTokenAt < 20 * 60 * 1000 }?.let { return it }
        val url = MUSIXMATCH_BASE + "token.get?user_language=en&app_id=$MUSIXMATCH_APP_ID&t=${requestId()}"
        val root = musixmatchJson(url) ?: return null
        val header = root.optJSONObject("message")?.optJSONObject("header")
        if (!acceptMxmHeader(header)) return null
        val token = root.optJSONObject("message")?.optJSONObject("body")?.optString("user_token")
            ?.takeIf { it.isNotBlank() } ?: return null
        musixmatchToken = token
        musixmatchTokenAt = System.currentTimeMillis()
        return token
    }

    private suspend fun musixmatchJson(url: String): JSONObject? {
        val wait = (500L - (System.currentTimeMillis() - musixmatchLastRequestAt)).coerceAtLeast(0)
        if (wait > 0) delay(wait)
        musixmatchLastRequestAt = System.currentTimeMillis()
        return runCatching {
            JSONObject(
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 17)",
                        "Cookie" to "AWSELB=0; AWSELBCORS=0",
                        "Accept" to "application/json",
                    ),
                    timeout = 12,
                ).text
            )
        }.getOrNull()
    }

    /** Avoid hammering Musixmatch from datacenter/captcha-blocked networks. */
    private fun acceptMxmHeader(header: JSONObject?): Boolean {
        val status = header?.optInt("status_code", -1) ?: -1
        if (status == 200) return true
        val hint = header?.optString("hint").orEmpty().lowercase(Locale.ROOT)
        if (status == 401 || hint.contains("captcha") || hint.contains("renew")) {
            musixmatchToken = null
            musixmatchBlockedUntil = System.currentTimeMillis() + 30 * 60 * 1000
        }
        return false
    }

    private fun requestId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun matchScore(actual: String?, expected: String): Int {
        if (expected.isBlank()) return 0
        val a = norm(actual)
        if (a.isBlank()) return 0
        return when {
            a == expected -> 5
            a.startsWith(expected) || expected.startsWith(a) -> 3
            a.contains(expected) || expected.contains(a) -> 1
            else -> 0
        }
    }

    private fun parseRichSync(body: String): List<LyricLine>? = runCatching {
        val array = JSONArray(body)
        val lines = ArrayList<LyricLine>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val start = (row.optDouble("ts", -1.0) * 1000).toLong()
            val end = (row.optDouble("te", -1.0) * 1000).toLong()
            var text = row.optString("x").trim()
            if (text.isBlank()) {
                val words = row.optJSONArray("l")
                if (words != null) {
                    val builder = StringBuilder()
                    for (j in 0 until words.length()) {
                        val chunk = words.optJSONObject(j)?.optString("c").orEmpty()
                        if (chunk.isBlank()) continue
                        if (builder.isNotEmpty() && !chunk.first().isWhitespace() &&
                            builder.last() != ' ' && !chunk.first().isPunctuation()
                        ) builder.append(' ')
                        builder.append(chunk)
                    }
                    text = builder.toString().trim()
                }
            }
            if (text.isNotBlank() && text != "♪") lines.add(LyricLine(start, end, text))
        }
        if (lines.isEmpty()) null else withLineEnds(lines)
    }.getOrNull()

    private fun Char.isPunctuation(): Boolean = !isLetterOrDigit() && !isWhitespace()

    private fun withLineEnds(input: List<LyricLine>): List<LyricLine> {
        val sorted = input.sortedBy { if (it.startMs < 0) Long.MAX_VALUE else it.startMs }
        return sorted.mapIndexed { i, line ->
            val next = sorted.drop(i + 1).firstOrNull { it.startMs >= 0 }?.startMs ?: -1
            val fallback = if (next > line.startMs) next - 1 else line.startMs + 8_000
            val end = if (line.endMs > line.startMs) line.endMs else fallback
            line.copy(endMs = end)
        }
    }

    // ── BiniLyrics (lyrics-api.binimum.org → TTML at lrc.red) ───────────
    private suspend fun binimumLyrics(title: String, artist: String?, durationSec: Long): Pack? {
        return runCatching {
            val url = BINIMUM_URL + "?track=${enc(title)}&artist=${enc(artist.orEmpty())}" +
                (if (durationSec > 0) "&duration=$durationSec" else "")
            val root = JSONObject(app.get(url, timeout = 10).text)
            val results = root.optJSONArray("results") ?: return null
            val wantedTitle = norm(title)
            val wantedArtist = norm(artist)
            val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .map { row ->
                    var score = matchScore(row.optString("track_name"), wantedTitle)
                    score += matchScore(row.optString("artist_name"), wantedArtist)
                    val candidateDuration = row.optLong("duration", 0)
                    if (durationSec > 0 && candidateDuration > 0) {
                        val durationDiff = kotlin.math.abs(candidateDuration - durationSec)
                        score += when {
                            durationDiff <= 3L -> 3
                            durationDiff <= 8L -> 1
                            else -> -3
                        }
                    }
                    score += when (row.optString("timing_type")) {
                        "word" -> 2
                        "line" -> 1
                        else -> 0
                    }
                    row to score
                }.sortedByDescending { it.second }.take(3)
            for ((row, score) in candidates) {
                if (score < 8) continue
                val lyricsUrl = row.optString("lyricsUrl")
                if (!lyricsUrl.startsWith("https://lrc.red/")) continue
                val ttml = app.get(lyricsUrl, timeout = 12).text
                val pack = NoirTtmlParser.parse(ttml, "BiniLyrics")
                if (pack != null && pack.lines.size >= 3) return pack
            }
            null
        }.getOrNull()
    }

    // ── bLyrics (Boidu API returns Apple-style TTML) ────────────────────
    private suspend fun bLyrics(title: String, artist: String?, durationSec: Long): Pack? {
        return runCatching {
            val url = "$BLYRICS_URL?s=${enc(title)}&a=${enc(artist.orEmpty())}" +
                (if (durationSec > 0) "&d=$durationSec" else "")
            val root = JSONObject(app.get(url, timeout = 12).text)
            val ttml = root.optString("ttml")
            if (ttml.isBlank()) return null
            NoirTtmlParser.parse(ttml, "bLyrics")?.takeIf { it.lines.size >= 3 }
        }.getOrNull()
    }

    // ── LRCLIB ──────────────────────────────────────────────────────────
    private suspend fun lrcLib(title: String, artist: String?, durationSec: Long): Pack? {
        try {
            val artistQ = artist ?: ""
            var json: JSONObject? = null
            // exact dulu (durasi ikut dicocokkan) — 404 wajar, lanjut search.
            runCatching {
                val u = LRC_BASE + "get?track_name=" + enc(title) +
                    "&artist_name=" + enc(artistQ) +
                    (if (durationSec > 0) "&duration=$durationSec" else "")
                json = JSONObject(app.get(u, timeout = 10).text)
            }
            if (json == null) {
                runCatching {
                    val u = LRC_BASE + "search?track_name=" + enc(title) +
                        "&artist_name=" + enc(artistQ)
                    val arr = JSONArray(app.get(u, timeout = 10).text)
                    var best: JSONObject? = null
                    var bestScore = Int.MIN_VALUE
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        if (c.optBoolean("instrumental")) continue
                        var s = matchScore(c.optString("trackName"), norm(title))
                        val foundArtist = c.optString("artistName")
                        val wantedArtist = norm(artistQ)
                        val artistScore = matchScore(foundArtist, wantedArtist)
                        s += artistScore
                        if (wantedArtist.isNotBlank() && foundArtist.isNotBlank() && artistScore == 0) s -= 5
                        if (c.optString("syncedLyrics").isNotBlank()) s += 2
                        if (c.optString("plainLyrics").isNotBlank()) s++
                        val foundDuration = c.optLong("duration", 0)
                        if (durationSec > 0 && foundDuration > 0) {
                            val diff = kotlin.math.abs(foundDuration - durationSec)
                            s += when {
                                diff <= 3L -> 3
                                diff <= 8L -> 1
                                else -> -3
                            }
                        }
                        if (s > bestScore) {
                            bestScore = s
                            best = c
                        }
                    }
                    json = best?.takeIf { bestScore >= 7 }
                }
            }
            val o = json ?: return null
            if (o.optBoolean("instrumental")) return null
            val synced = o.optString("syncedLyrics")
            if (!synced.isNullOrBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return Pack("LRCLIB", true, lines)
            }
            val plain = o.optString("plainLyrics")
            if (!plain.isNullOrBlank()) {
                val lines = plain.split("\n").map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(-1, -1, it) }
                if (lines.isNotEmpty()) return Pack("LRCLIB", false, lines)
            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }

    /** Parser LRC: [mm:ss.xx] (multi-tag per baris) + meta [offset:±ms]. */
    fun parseLrc(lrc: String): List<LyricLine> {
        var offset = 0L
        val out = ArrayList<LyricLine>()
        val timeRe = Regex("\\[(\\d{1,2}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]")
        lrc.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val off = Regex("\\[offset:([+-]?\\d+)\\]").find(line)
            if (off != null) {
                offset = off.groupValues[1].toLongOrNull() ?: 0
                return@forEach
            }
            val stamps = timeRe.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty() || text == "♪") return@forEach
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val secRaw = m.groupValues[2].replace(":", ".")
                val sec = secRaw.toDoubleOrNull() ?: 0.0
                val ms = (min * 60_000 + (sec * 1000).toLong() + offset)
                    .coerceAtLeast(0)
                out.add(LyricLine(ms, -1, text))
            }
        }
        // endMs = awal baris berikutnya (untuk durasi sorot karaoke).
        out.sortBy { it.startMs }
        return out.mapIndexed { i, l ->
            val nextStart = out.drop(i + 1).firstOrNull { it.startMs > l.startMs }?.startMs ?: -1
            val end = if (nextStart > l.startMs) nextStart - 1 else l.startMs + 8000
            l.copy(endMs = end)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Terjemahan AI luring (Hy-MT2 melalui llama.cpp) ────────────────
    /** Terjemahkan lirik sepenuhnya di perangkat; model tidak mengirim prompt ke jaringan. */
    suspend fun translate(
        context: Context,
        lines: List<String>,
        target: String,
        onProgress: (completedLines: Int, totalLines: Int) -> Unit = { _, _ -> },
    ): List<String>? = withContext(Dispatchers.IO) {
        try {
            // Versi mesin ada di kunci supaya cache lama hasil Google/MyMemory
            // tidak pernah dikira sebagai terjemahan AI lokal.
            val key = "tr|local-hymt2-q4-v1|$target|" + lines.joinToString("\n")
            freshCacheFile(key)?.takeIf { it.length() > 2 }?.let { f ->
                runCatching {
                    val arr = JSONArray(f.readText())
                    (0 until arr.length()).map { arr.optString(it) }
                }.getOrNull()?.takeIf { it.size == lines.size }
                    ?.let { return@withContext it }
            }

            val result = NoirOfflineLyricsTranslator.translate(
                context.applicationContext,
                lines,
                target,
                onProgress,
            )?.takeIf { it.size == lines.size }

            if (result != null) {
                cacheFile(key)?.runCatching {
                    val arr = JSONArray()
                    result.forEach { arr.put(it) }
                    writeText(arr.toString())
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoirOfflineLyricsTranslator.LowMemoryException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * Tidak memakai layanan transliterasi online. Romanisasi hanya tersedia
     * jika provider lirik menyertakannya sebagai sidecar TTML.
     */
    suspend fun romanize(lines: List<String>): List<String>? = null

}
