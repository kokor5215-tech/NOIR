package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * NOIR fix16 — Qobuz & Tidal WAJIB masuk, dan ini caranya.
 *
 * Riset empiris 2026-10-02 (sandbox, tanpa akun):
 *  - Qobuz  : `www.qobuz.com/api.json/0.2/catalog/search` dengan X-App-Id
 *             widget hidup → katalog + metadata bit-depth 24-bit; dan
 *             `track/getFileUrl` bertanda MD5 (app-id + secret publik web
 *             player, pola sama dengan extension qobuz-web SpotiFLAC)
 *             mengembalikan stream preview FLAC/MP3 30 detik.
 *  - Tidal  : `tidal.com/v1/search` dengan `x-tidal-token` publik web
 *             hidup → track/album/artist lengkap.
 *  - Stream PENUH keduanya memang mewajibkan sesi berbayar / proxy zarz
 *             (sering mati berhari-hari) — karena itu tanpa akun kita
 *             sajikan: pencarian + metadata Hi-Res + preview Qobuz, dan
 *             untuk play penuh dicocokkan silang ke source lossless lain
 *             (SoundCloud via runtime SpotiFLAC) persis filosofi SpotiFLAC
 *             "Spotify link in -> cross-service match -> lossless out".
 */
object NoirHiRes {

    // ── Qobuz ────────────────────────────────────────────────────────────
    private const val QZ_WIDGET_APP_ID = "735532640"
    private const val QZ_PREVIEW_APP_ID = "712109809"
    private const val QZ_PREVIEW_SECRET = "589be88e4538daea11f509d29e4a23b1"
    private const val QZ_BASE = "https://www.qobuz.com/api.json/0.2"

    data class QzImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("small") val small: String? = null,
    )

    data class QzArtist(@JsonProperty("name") val name: String? = null)

    data class QzAlbum(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("artist") val artist: QzArtist? = null,
        @JsonProperty("image") val image: QzImage? = null,
        @JsonProperty("maximum_bit_depth") val bitDepth: Int? = null,
    )

    data class QzTrack(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("artist") val artist: QzArtist? = null,
        @JsonProperty("performer") val performer: QzArtist? = null,
        @JsonProperty("album") val album: QzAlbum? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("maximum_bit_depth") val bitDepth: Int? = null,
        @JsonProperty("hires") val hires: Boolean? = null,
    )

    data class QzItems<T>(@JsonProperty("items") val items: List<T>? = null)

    data class QzSearchResult(
        @JsonProperty("tracks") val tracks: QzItems<QzTrack>? = null,
        @JsonProperty("albums") val albums: QzItems<QzAlbum>? = null,
    )

    data class QzFileUrl(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("sample") val sample: Boolean? = null,
        @JsonProperty("duration") val duration: Double? = null,
    )

    data class NoirTrack(
        val source: String,      // "qobuz" | "tidal" | "soundcloud"
        val id: String,
        val title: String,
        val artist: String?,
        val cover: String?,
        val badge: String?,      // mis. "24-Bit", "FLAC"
    )

    /** Cari di katalog Qobuz (widget publik, tanpa akun). */
    suspend fun qobuzSearch(query: String, limit: Int = 15): List<NoirTrack> =
        withContext(Dispatchers.IO) {
            try {
                val res = AppUtils.parseJson<QzSearchResult>(
                    app.get(
                        "$QZ_BASE/catalog/search",
                        params = mapOf("query" to query, "limit" to "$limit"),
                        headers = mapOf("X-App-Id" to QZ_WIDGET_APP_ID),
                        timeout = 10,
                    ).text
                )
                val fromTracks = res.tracks?.items.orEmpty().mapNotNull { t ->
                    val id = t.id ?: return@mapNotNull null
                    NoirTrack(
                        source = "qobuz",
                        id = "$id",
                        title = t.title ?: return@mapNotNull null,
                        artist = t.performer?.name ?: t.artist?.name
                            ?: t.album?.artist?.name,
                        cover = t.album?.image?.large ?: t.album?.image?.small,
                        badge = t.bitDepth?.takeIf { it > 16 }?.let { "$it-Bit" }
                            ?: if (t.hires == true) "Hi-Res" else "FLAC",
                    )
                }
                val fromAlbums = res.albums?.items.orEmpty().mapNotNull { a ->
                    val id = a.id ?: return@mapNotNull null
                    NoirTrack(
                        source = "qobuz",
                        id = "$id",
                        title = a.title ?: return@mapNotNull null,
                        artist = a.artist?.name,
                        cover = a.image?.large ?: a.image?.small,
                        badge = a.bitDepth?.takeIf { it > 16 }?.let { "$it-Bit" } ?: "FLAC",
                    )
                }
                fromTracks + fromAlbums
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
        }

    /**
     * URL stream preview Qobuz via API bertanda MD5 — pola tanda sama persis
     * dengan extension qobuz-web SpotiFLAC (objectName+methodName+params
     * urut+timestamp+secret → MD5).
     */
    suspend fun qobuzPreviewUrl(trackId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val params = linkedMapOf(
                    "track_id" to trackId,
                    "format_id" to "5",
                    "intent" to "stream",
                )
                val ts = (System.currentTimeMillis() / 1000).toString()
                val raw = StringBuilder("trackgetFileUrl")
                params.keys.sorted().forEach { k -> raw.append(k).append(params[k]) }
                raw.append(ts).append(QZ_PREVIEW_SECRET)
                val sig = MessageDigest.getInstance("MD5")
                    .digest(raw.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val query = params.entries.joinToString("&") {
                    "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                } + "&request_ts=$ts&request_sig=$sig"
                val res = AppUtils.parseJson<QzFileUrl>(
                    app.get(
                        "$QZ_BASE/track/getFileUrl?$query",
                        headers = mapOf("X-App-Id" to QZ_PREVIEW_APP_ID),
                        timeout = 10,
                    ).text
                )
                res.url?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

    // ── Tidal ────────────────────────────────────────────────────────────
    private const val TL_BASE = "https://tidal.com/v1"
    private const val TL_TOKEN = "49YxDN9a2aFV6RTG"

    data class TlArtist(@JsonProperty("name") val name: String? = null)

    data class TlAlbum(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class TlTrack(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("artist") val artist: TlArtist? = null,
        @JsonProperty("artists") val artists: List<TlArtist>? = null,
        @JsonProperty("album") val album: TlAlbum? = null,
    )

    data class TlItems<T>(@JsonProperty("items") val items: List<T>? = null)

    data class TlSearchResult(@JsonProperty("tracks") val tracks: TlItems<TlTrack>? = null)

    /** Cari di Tidal (token web publik, tanpa akun). */
    suspend fun tidalSearch(query: String, limit: Int = 15): List<NoirTrack> =
        withContext(Dispatchers.IO) {
            try {
                val res = AppUtils.parseJson<TlSearchResult>(
                    app.get(
                        "$TL_BASE/search",
                        params = mapOf(
                            "query" to query,
                            "limit" to "$limit",
                            "types" to "TRACKS",
                            "countryCode" to "US",
                        ),
                        headers = mapOf("x-tidal-token" to TL_TOKEN),
                        timeout = 10,
                    ).text
                )
                res.tracks?.items.orEmpty().mapNotNull { t ->
                    val id = t.id ?: return@mapNotNull null
                    NoirTrack(
                        source = "tidal",
                        id = "$id",
                        title = t.title ?: return@mapNotNull null,
                        artist = t.artist?.name ?: t.artists?.firstOrNull()?.name,
                        cover = t.album?.cover?.let { c ->
                            "https://resources.tidal.com/images/" +
                                c.replace('-', '/') + "/320x320.jpg"
                        },
                        badge = "TIDAL",
                    )
                }
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
        }
}
