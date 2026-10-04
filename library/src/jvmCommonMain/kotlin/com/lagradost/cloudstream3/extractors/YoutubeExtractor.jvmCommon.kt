package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

actual open class YoutubeExtractor actual constructor() : ExtractorApi() {

    actual override val mainUrl = "https://www.youtube.com"
    actual override val name = "YouTube"
    actual override val requiresReferer = false

    // NOIR fix13: instance Piped publik buat fallback. YouTube sering menahan
    // stream video-only/audio terpisah per-IP (403) sehingga merge macet
    // selamanya; Piped menyajikan audio+video gabung dalam satu HLS yang
    // langsung playable.
    private val pipedApis = listOf(
        "https://piped-api.jeppedevelper.ru",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
    )

    actual override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = extractYouTubeId(url)
        val watchUrl = "$mainUrl/watch?v=$videoId"
        var emitted = 0
        var progressive = 0
        var voUsable = false

        try {
            val info = StreamInfo.getInfo(watchUrl)
            val isLive = info.streamType == StreamType.LIVE_STREAM
                || info.streamType == StreamType.AUDIO_LIVE_STREAM
                || info.streamType == StreamType.POST_LIVE_STREAM
                || info.streamType == StreamType.POST_LIVE_AUDIO_STREAM

            if (isLive && info.hlsUrl != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube Live",
                        url = info.hlsUrl
                    ) {
                        type = ExtractorLinkType.M3U8
                    }
                )
                emitted++
            } else {
                // NOIR fix13: probe HEAD satu URL video-only DARI PERANGKAT INI.
                // YouTube menerapkan bot-check per-IP: di sandbox semua 206, tapi
                // di jaringan pengguna stream client VISIONOS bisa 403 → merge
                // video+audio buffering selamanya. Kalau tak terjangkau, link
                // video-only tidak ditawarkan sama sekali.
                voUsable = info.videoOnlyStreams.orEmpty()
                    .firstOrNull()?.content?.let { first ->
                        withTimeoutOrNull(5000) {
                            runCatching { app.head(first).isSuccessful }.getOrDefault(false)
                        } ?: false
                    } ?: false

                val (prog, total) = processVideo(info, voUsable, subtitleCallback, callback)
                progressive = prog
                emitted += total
            }
        } catch (t: Throwable) {
            // NOIR: NewPipe gagal total (blokir/ubah API) — jangan menyerah,
            // lanjut ke fallback Piped di bawah.
        }

        // NOIR fix13: tanpa progressive, atau saat video-only tak terjangkau,
        // tambah HLS gabungan dari Piped sebagai jalur aman. Bila NewPipe kosong
        // total dan Piped juga gagal — baru lempar error.
        if (progressive == 0 || !voUsable) {
            val ok = pipedFallback(videoId, callback)
            if (emitted == 0 && !ok) {
                throw ErrorLoadingException("YouTube: semua sumber gagal")
            }
        }
    }

    private suspend fun processVideo(
        info: StreamInfo,
        includeVideoOnly: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Pair<Int, Int> {
        var emitted = 0
        var progressive = 0

        // NOIR fix13: stream progresif (audio+video gabung, client ANDROID)
        // ditawarkan paling dulu — inilah yang pasti playable tanpa merge.
        // Kode lama membuang stream ini sama sekali.
        info.videoStreams.orEmpty().forEach { video ->
            if (video.content.isNullOrBlank()) return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)} ${video.height}p",
                    url = video.content
                ) {
                    quality = video.height
                }
            )
            emitted++
            progressive++
        }

        // Opsi HD: video-only + audio track terpisah (di-merge player) —
        // hanya bila probe dari perangkat menyatakan terjangkau.
        // NOIR fix14: audio diurutkan bitrate menurun & dibatasi 3 terbaik —
        // sebelumnya urutan bawaan NewPipe menaruh 48kbps paling dulu sehingga
        // merge default memakai audio kualitas rendah ("suara pecah").
        if (includeVideoOnly) {
            val audioStreams = info.audioStreams.orEmpty()
                .sortedByDescending { it.averageBitrate }
                .take(3)
            info.videoOnlyStreams.orEmpty().forEach { video ->
                if (video.content.isNullOrBlank()) return@forEach
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube ${normalizeCodec(video.codec)}",
                        url = video.content
                    ) {
                        quality = video.height
                        audioTracks = audioStreams.map { newAudioFile(it.content) }
                    }
                )
                emitted++
            }
        }

        info.subtitles.forEach { subtitle ->
            subtitleCallback(
                newSubtitleFile(
                    lang = subtitle.displayLanguageName
                        ?: subtitle.languageTag
                        ?: "Unknown",
                    url = subtitle.content
                )
            )
        }

        return Pair(progressive, emitted)
    }

    // NOIR fix13: ambil HLS gabungan (audio+video satu berkas) dari instance
    // Piped pertama yang menjawab.
    private suspend fun pipedFallback(
        videoId: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        for (api in pipedApis) {
            try {
                val res = AppUtils.parseJson<PipedStreams>(
                    app.get("$api/streams/$videoId", timeout = 8).text
                )
                val hls = res.hls?.takeIf { it.isNotBlank() } ?: continue
                val best = res.videoStreams.orEmpty()
                    .mapNotNull { it.quality?.filter(Char::isDigit)?.toIntOrNull() }
                    .maxOrNull() ?: Qualities.P720.value
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube HLS",
                        url = hls
                    ) {
                        type = ExtractorLinkType.M3U8
                        quality = best
                    }
                )
                // stream progresif gabungan dari Piped sebagai opsi tambahan
                res.videoStreams.orEmpty()
                    .filter { !it.url.isNullOrBlank() }
                    .mapNotNull { v ->
                        val q = v.quality?.filter(Char::isDigit)?.toIntOrNull()
                        if (q != null) Pair(q, v) else null
                    }
                    .sortedByDescending { it.first }
                    .take(2)
                    .forEach { (q, v) ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "YouTube ${normalizeCodec(v.codec)} ${q}p",
                                url = v.url!!
                            ) {
                                quality = q
                            }
                        )
                    }
                return true
            } catch (_: Exception) {
                // instance mati / JSON berubah — coba berikutnya
            }
        }
        return false
    }

    private data class PipedStreams(
        val hls: String? = null,
        val videoStreams: List<PipedVideo>? = null,
    )

    private data class PipedVideo(
        val url: String? = null,
        val quality: String? = null,
        val codec: String? = null,
    )

    private fun extractYouTubeId(url: String): String {
        val regex = Regex(
            "(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:.*v=|v/|u/\\w/|embed/|shorts/|live/))([\\w-]{11})"
        )

        return regex.find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid YouTube URL: $url")
    }

    private fun normalizeCodec(codec: String?): String {
        if (codec.isNullOrBlank()) return ""
        val c = codec.lowercase()
        return when {
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp9") -> "VP9"
            c.startsWith("avc1") || c.startsWith("h264") -> "H264"
            c.startsWith("hev1") || c.startsWith("hvc1") || c.startsWith("hevc") -> "H265"
            else -> codec.substringBefore('.').uppercase()
        }
    }
}
