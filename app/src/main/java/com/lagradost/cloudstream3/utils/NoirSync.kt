package com.lagradost.cloudstream3.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * NOIRSYNC — sinkronisasi subtitle ke audio video, 100% on-device.
 *
 * Terinspirasi ffsubsync (smacke/ffsubsync, algoritma "subsync"):
 *  1. Dekode audio video dengan MediaExtractor + MediaCodec -> PCM.
 *  2. Hitung amplop energi suara per frame 30 ms -> masker ucapan
 *     (threshold persentil + pembersihan gap pendek).
 *  3. Ubah cue subtitle (SRT/VTT/ASS) jadi amplop biner di resolusi sama.
 *  4. Cari offset terbaik lewat FFT cross-correlation dinormalisasi
 *     (prefix-sum) antara ucapan audio vs durasi subtitle.
 *  5. Tulis ulang seluruh timestamp, simpan file baru, hot-swap di player.
 *
 * Semua berjalan lokal: tidak ada Python, tidak ada server, tidak ada
 * upload. Video streaming (URL langsung) maupun file lokal didukung.
 */
object NoirSync {

    const val FRAME_MS = 30
    private const val MAX_DECODE_MINUTES = 40
    private const val MAX_SEARCH_MS = 180_000L

    data class SyncResult(
        val success: Boolean,
        val shiftMs: Long,      // nilai yang DITAMBAHKAN ke semua timestamp
        val confidence: Float,  // 0..1
        val syncedFile: File?,
        val message: String
    )

    // ================================================================
    // 1. Amplop energi audio dari stream/file video
    // ================================================================
    private fun extractAudioEnvelope(
        source: String,
        headers: Map<String, String>,
        onProgress: (Int) -> Unit
    ): IntArray? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            if (headers.isEmpty()) extractor.setDataSource(source)
            else extractor.setDataSource(source, headers)

            var audioTrack = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    audioTrack = i
                    mime = m
                    break
                }
            }
            if (audioTrack < 0) return null
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (_: Throwable) { -1L }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val frameSamples = sampleRate * FRAME_MS / 1000 * channels
            val maxFrames = (MAX_DECODE_MINUTES * 60_000L / FRAME_MS).toInt() + 512
            val energy = ArrayList<Float>(maxFrames)
            val info = MediaCodec.BufferInfo()
            var sampleCount = 0L        // total sampel PCM (semua channel)
            var frameAcc = 0.0
            var frameSamplesAcc = 0
            var eos = false
            var timeoutGuard = 0

            fun flushFrame() {
                if (frameSamplesAcc > 0) {
                    energy.add(ln(1.0 + frameAcc / frameSamplesAcc).toFloat())
                }
                frameAcc = 0.0
                frameSamplesAcc = 0
            }

            fun absorb(bb: java.nio.ByteBuffer, bytes: Int) {
                // PCM 16-bit little-endian langsung dari buffer codec
                var rem = bytes
                while (rem > 1) {
                    val v = bb.short.toInt()
                    frameAcc += (v.toLong() * v).toDouble()
                    frameSamplesAcc++
                    sampleCount++
                    rem -= 2
                    if (frameSamplesAcc >= frameSamples) flushFrame()
                }
            }

            while (!eos && energy.size < maxFrames && timeoutGuard < 40_000) {
                timeoutGuard++
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val read = extractor.readSampleData(inBuf, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) absorb(outBuf, info.size)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (durationUs > 0) {
                        onProgress(((sampleCount / channels) * 100L * 1_000_000L / sampleRate / durationUs).toInt().coerceIn(0, 99))
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
            flushFrame()
            onProgress(100)
            if (energy.size < 100) return null

            // --- masker ucapan: threshold persentil + pembersihan ---
            val sorted = energy.filter { it > 0f }.toFloatArray().also { it.sort() }
            if (sorted.isEmpty()) return null
            val thresh = sorted[(sorted.size * 0.60).toInt().coerceAtMost(sorted.size - 1)]
            val mask = IntArray(energy.size) { if (energy[it] > thresh) 1 else 0 }
            // tutup gap <5 frame, buang burst <4 frame
            var run = 0
            for (i in mask.indices) {
                if (mask[i] == 1) run++
                else {
                    if (run in 1..3) for (j in i - run until i) mask[j] = 0
                    run = 0
                }
            }
            run = 0
            for (i in mask.indices) {
                if (mask[i] == 0) {
                    if (run in 1..4) for (j in i - run until i) mask[j] = 1
                    run = 0
                } else run++
            }
            return mask
        } catch (t: Throwable) {
            logError(t)
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    // ================================================================
    // 2. Amplop subtitle (SRT / VTT / ASS) -> masker biner
    // ================================================================
    private val SRT_TS = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val ASS_TS = Regex("""(\d):(\d{2}):(\d{2})\.(\d{2})""")

    private fun parseCues(text: String): List<Pair<Long, Long>> {
        val isAss = text.contains("[Script Info]") || text.contains("Dialogue:")
        val cues = ArrayList<Pair<Long, Long>>()
        if (isAss) {
            for (line in text.lineSequence()) {
                if (!line.startsWith("Dialogue:")) continue
                val parts = line.split(',')
                if (parts.size < 10) continue
                val s = parseAssTs(parts[1].trim()) ?: continue
                val e = parseAssTs(parts[2].trim()) ?: continue
                if (e > s) cues.add(s to e)
            }
        } else {
            for (line in text.lineSequence()) {
                if (!line.contains("-->")) continue
                val ms = SRT_TS.findAll(line).map { m ->
                    ((m.groupValues[1].toLong() * 3600 +
                        m.groupValues[2].toLong() * 60 +
                        m.groupValues[3].toLong()) * 1000 +
                        m.groupValues[4].padEnd(3, '0').take(3).toLong())
                }.toList()
                if (ms.size >= 2 && ms[1] > ms[0]) cues.add(ms[0] to ms[1])
            }
        }
        return cues
    }

    private fun parseAssTs(s: String): Long? {
        val m = ASS_TS.matchEntire(s) ?: return null
        return (m.groupValues[1].toLong() * 3600 +
            m.groupValues[2].toLong() * 60 +
            m.groupValues[3].toLong()) * 1000 +
            m.groupValues[4].toLong() * 10
    }

    private fun subtitleEnvelope(text: String, frames: Int): IntArray {
        val env = IntArray(frames)
        for ((s, e) in parseCues(text)) {
            val f0 = (s / FRAME_MS).toInt().coerceIn(0, frames - 1)
            // durasi cue dibatasi 5 dtk (ucapan sering melewati teks tampil)
            val f1 = (min(e, s + 5000) / FRAME_MS).toInt().coerceIn(0, frames - 1)
            for (i in f0..f1) env[i] = 1
        }
        return env
    }

    // ================================================================
    // 3. FFT cross-correlation + normalisasi prefix-sum
    // ================================================================
    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** FFT in-place iteratif (Cooley-Tukey). inverse=true -> dibagi N. */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2 else -2) * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f; var cwi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /**
     * Cari lag (frame) di mana subtitle paling pas dengan ucapan audio.
     * korelasi[k] = Σ a[i]·b[i-k] ; puncak = posisi subtitle paling cocok.
     * @return (lagFrames, confidence)
     */
    fun findOffset(audio: IntArray, sub: IntArray, maxSearchMs: Long = MAX_SEARCH_MS): Pair<Int, Float> {
        val maxLag = (maxSearchMs / FRAME_MS).toInt()
        val n = nextPow2(audio.size + sub.size)
        val ar = FloatArray(n); val ai = FloatArray(n)
        val br = FloatArray(n); val bi = FloatArray(n)
        for (i in audio.indices) ar[i] = audio[i].toFloat()
        for (i in sub.indices) br[i] = sub[i].toFloat()
        fft(ar, ai, false); fft(br, bi, false)
        // C = A * conj(B)
        val cr = FloatArray(n); val ci = FloatArray(n)
        for (i in 0 until n) {
            cr[i] = ar[i] * br[i] + ai[i] * bi[i]
            ci[i] = ai[i] * br[i] - ar[i] * bi[i]
        }
        fft(cr, ci, true)

        // prefix-sum audio utk normalisasi jumlah ucapan di jendela lag
        val prefix = LongArray(audio.size + 1)
        for (i in audio.indices) prefix[i + 1] = prefix[i] + audio[i]
        val subCount = sub.sum().coerceAtLeast(1)

        var bestLag = 0
        var bestScore = Float.MIN_VALUE
        val scores = ArrayList<Float>(2 * maxLag + 1)
        for (lag in -maxLag..maxLag) {
            val idx = if (lag >= 0) lag else n + lag
            val start = lag.coerceAtLeast(0)
            val end = min(audio.size, lag + sub.size)
            if (end - start < sub.size / 3) continue
            val windowSum = (prefix[end] - prefix[start]).coerceAtLeast(1)
            val score = cr[idx] / windowSum
            scores.add(score)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (scores.isEmpty()) return 0 to 0f
        scores.sort()
        val p90 = scores[(scores.size * 0.90).toInt().coerceAtMost(scores.size - 1)]
        val conf = if (bestScore <= 0f) 0f
        else ((bestScore / (p90 + 1e-6f) - 1f) / 3f).coerceIn(0f, 1f)
        return bestLag to conf
    }

    // ================================================================
    // 4. Tulis ulang timestamp (geser semua cue)
    // ================================================================
    fun shiftTimestamps(text: String, shiftMs: Long): String {
        val isAss = text.contains("[Script Info]") || text.contains("Dialogue:")
        return if (isAss) {
            ASS_TS.replace(text) { m ->
                val ms = parseAssTs(m.value) ?: return@replace m.value
                formatAssTs(max(0L, ms + shiftMs))
            }
        } else {
            SRT_TS.replace(text) { m ->
                val ms = (m.groupValues[1].toLong() * 3600 +
                    m.groupValues[2].toLong() * 60 +
                    m.groupValues[3].toLong()) * 1000 +
                    m.groupValues[4].padEnd(3, '0').take(3).toLong()
                formatSrtTs(max(0L, ms + shiftMs), m.value.contains("."))
            }
        }
    }

    private fun formatSrtTs(ms: Long, dot: Boolean): String {
        val h = ms / 3600000; val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000; val r = ms % 1000
        val sep = if (dot) "." else ","
        return "%02d:%02d:%02d$sep%03d".format(h, m, s, r)
    }

    private fun formatAssTs(ms: Long): String {
        val h = ms / 3600000; val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000; val cs = (ms % 1000) / 10
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    // ================================================================
    // 5. Orkestrasi: decode -> korelasi -> tulis file
    // ================================================================
    suspend fun sync(
        context: Context,
        source: String,
        headers: Map<String, String>,
        subtitleText: String,
        outName: String,
        onProgress: (Int) -> Unit = {}
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val cues = parseCues(subtitleText)
            if (cues.size < 10) {
                return@withContext SyncResult(false, 0, 0f, null,
                    "Subtitle tidak punya cukup cue untuk disinkronkan.")
            }
            val audio = extractAudioEnvelope(source, headers, onProgress)
                ?: return@withContext SyncResult(false, 0, 0f, null,
                    "Gagal membaca audio video (format tidak didukung atau link kadaluarsa).")
            val sub = subtitleEnvelope(subtitleText, audio.size)
            if (sub.sum() < 20) {
                return@withContext SyncResult(false, 0, 0f, null,
                    "Durasi subtitle terlalu singkat untuk sinkronisasi.")
            }
            val (lag, conf) = findOffset(audio, sub)
            // diverifikasi via simulasi: lag negatif = subtitle telat,
            // maka shift = lag * FRAME_MS (negatif -> cue digeser lebih awal).
            val shiftMs = lag.toLong() * FRAME_MS
            val shifted = shiftTimestamps(subtitleText, shiftMs)
            val out = File(context.cacheDir, outName)
            out.writeText(shifted)
            val sec = shiftMs / 1000f
            SyncResult(
                true, shiftMs, conf, out,
                "Subtitle digeser ${"%.1f".format(sec)} detik " +
                    "(kepercayaan ${"%.0f".format(conf * 100)}%)."
            )
        } catch (t: Throwable) {
            logError(t)
            SyncResult(false, 0, 0f, null, "Sinkronisasi gagal: ${t.message ?: "error"}")
        }
    }
}
