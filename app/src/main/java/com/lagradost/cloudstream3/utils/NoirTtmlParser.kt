package com.lagradost.cloudstream3.utils

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/**
 * TTML parser untuk feed lirik BLyrics/BiniLyrics.
 * Membaca timestamp <p> (dan timestamp <span> bila baris tidak punya begin),
 * menyatukan teks word-span, serta mempertahankan baris translation/roman
 * tertanam jika provider mengirimkannya.
 */
internal object NoirTtmlParser {
    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    private data class Draft(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val language: String,
    )

    private class Builder(
        var startMs: Long,
        var endMs: Long,
        val role: String,
        val language: String,
    ) {
        val main = StringBuilder()
        val translation = StringBuilder()
        val romanization = StringBuilder()
        var translationDepth = 0
        var romanDepth = 0
        var lastSpanEndMs = -1L
    }

    fun parse(ttml: String, source: String): NoirLyrics.Pack? {
        if (ttml.isBlank() || ttml.length > 1_000_000) return null
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(ttml))

            val mainDrafts = ArrayList<Draft>()
            val translationDrafts = ArrayList<Draft>()
            val romanDrafts = ArrayList<Draft>()
            var current: Builder? = null
            val spanRoles = java.util.ArrayDeque<String>()
            var lyricOffsetMs = 0L

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.orEmpty().lowercase(Locale.ROOT)
                        if (name == "audio") {
                            lyricOffsetMs = parseTime(parser.getAttributeValue(null, "lyricOffset"))
                                ?: lyricOffsetMs
                        }
                        if (name == "p") {
                            val role = parser.getAttributeValue(TTM_NS, "role")
                                ?: parser.getAttributeValue(null, "role").orEmpty()
                            current = Builder(
                                startMs = parseTime(parser.getAttributeValue(null, "begin")) ?: -1,
                                endMs = parseTime(parser.getAttributeValue(null, "end")) ?: -1,
                                role = role.lowercase(Locale.ROOT),
                                language = parser.getAttributeValue(XML_NS, "lang")
                                    ?: parser.getAttributeValue(null, "lang").orEmpty(),
                            )
                        } else if (name == "span") {
                            current?.let { b ->
                                if (b.startMs < 0) {
                                    b.startMs = parseTime(parser.getAttributeValue(null, "begin")) ?: -1
                                }
                                parseTime(parser.getAttributeValue(null, "end"))?.let {
                                    b.lastSpanEndMs = it
                                }
                                val role = (parser.getAttributeValue(TTM_NS, "role")
                                    ?: parser.getAttributeValue(null, "role")).orEmpty()
                                    .lowercase(Locale.ROOT)
                                spanRoles.addLast(role)
                                if (role.contains("translation")) b.translationDepth++
                                if (role.contains("roman")) b.romanDepth++
                            }
                        } else if (name == "br") {
                            current?.let { appendText(it, " ") }
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        current?.let { appendText(it, parser.text.orEmpty()) }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name.orEmpty().lowercase(Locale.ROOT)
                        if (name == "span") {
                            // Attributes are not reliably available on END_TAG;
                            // retain each span role from its matching START_TAG.
                            val role = if (spanRoles.isEmpty()) "" else spanRoles.removeLast()
                            current?.let { b ->
                                if (role.contains("translation")) b.translationDepth =
                                    (b.translationDepth - 1).coerceAtLeast(0)
                                if (role.contains("roman")) b.romanDepth = (b.romanDepth - 1).coerceAtLeast(0)
                            }
                        } else if (name == "p") {
                            current?.let { b ->
                                val start = if (b.startMs >= 0) {
                                    (b.startMs + lyricOffsetMs).coerceAtLeast(0)
                                } else -1L
                                val end = when {
                                    b.endMs >= 0 -> (b.endMs + lyricOffsetMs).coerceAtLeast(start.coerceAtLeast(0))
                                    b.lastSpanEndMs >= 0 -> (b.lastSpanEndMs + lyricOffsetMs)
                                        .coerceAtLeast(start.coerceAtLeast(0))
                                    else -> -1L
                                }
                                val text = clean(if (b.role.contains("translation")) {
                                    b.translation.toString().ifBlank { b.main.toString() }
                                } else if (b.role.contains("roman")) {
                                    b.romanization.toString().ifBlank { b.main.toString() }
                                } else {
                                    b.main.toString()
                                })
                                when {
                                    text.isBlank() || text == "♪" -> Unit
                                    b.role.contains("translation") -> translationDrafts.add(
                                        Draft(start, end, text, b.language)
                                    )
                                    b.role.contains("roman") -> romanDrafts.add(
                                        Draft(start, end, text, b.language)
                                    )
                                    else -> {
                                        mainDrafts.add(Draft(start, end, text, b.language))
                                        clean(b.translation.toString()).takeIf { it.isNotBlank() }?.let {
                                            translationDrafts.add(Draft(start, end, it, b.language))
                                        }
                                        clean(b.romanization.toString()).takeIf { it.isNotBlank() }?.let {
                                            romanDrafts.add(Draft(start, end, it, b.language))
                                        }
                                    }
                                }
                            }
                            current = null
                        }
                    }
                }
                parser.next()
            }

            if (mainDrafts.isEmpty()) return null
            val sorted = mainDrafts.sortedWith(compareBy<Draft> { if (it.startMs < 0) Long.MAX_VALUE else it.startMs })
            val lines = sorted.mapIndexed { i, d ->
                val nextStart = sorted.drop(i + 1).firstOrNull { it.startMs >= 0 }?.startMs ?: -1
                val fallbackEnd = if (nextStart >= 0) (nextStart - 1).coerceAtLeast(d.startMs) else
                    if (d.startMs >= 0) d.startMs + 8_000 else -1
                val end = if (d.endMs >= d.startMs && d.endMs >= 0) d.endMs else fallbackEnd
                NoirLyrics.LyricLine(d.startMs, end, d.text)
            }

            fun alignSidecar(sidecar: List<Draft>): List<String>? {
                if (sidecar.isEmpty()) return null
                val sideSorted = sidecar.sortedWith(
                    compareBy<Draft> { if (it.startMs < 0) Long.MAX_VALUE else it.startMs }
                )
                if (sideSorted.size == lines.size) return sideSorted.map { it.text }
                // If an endpoint omits instrumental/non-vocal rows from a sidecar,
                // map each translated row to the nearest timed source row.
                val aligned = MutableList(lines.size) { "" }
                sideSorted.forEach { side ->
                    val idx = if (side.startMs >= 0) {
                        lines.indices.minByOrNull { j -> kotlin.math.abs(lines[j].startMs - side.startMs) }
                    } else null
                    if (idx != null && idx >= 0) aligned[idx] = side.text
                }
                return aligned.takeIf { it.any(String::isNotBlank) }
            }

            val synced = lines.any { it.startMs >= 0 }
            NoirLyrics.Pack(
                source = source,
                synced = synced,
                lines = lines,
                embeddedTranslations = alignSidecar(translationDrafts),
                embeddedRomanizations = alignSidecar(romanDrafts),
            )
        }.getOrNull()
    }

    private fun appendText(builder: Builder, text: String) {
        when {
            builder.translationDepth > 0 -> builder.translation.append(text)
            builder.romanDepth > 0 -> builder.romanization.append(text)
            else -> builder.main.append(text)
        }
    }

    private fun clean(text: String): String =
        text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    /** TTML time forms: 1.25s, 00:01.250, 00:00:01.250, and signed offsets. */
    private fun parseTime(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sign = if (value.startsWith('-')) -1 else 1
        val clean = value.removePrefix("-").removePrefix("+").removeSuffix("s")
        val parts = clean.split(':')
        val seconds = runCatching {
            when (parts.size) {
                1 -> parts[0].toDouble()
                2 -> parts[0].toDouble() * 60.0 + parts[1].toDouble()
                3 -> parts[0].toDouble() * 3600.0 + parts[1].toDouble() * 60.0 + parts[2].toDouble()
                else -> return null
            }
        }.getOrNull() ?: return null
        return (seconds * 1_000).toLong() * sign
    }
}
