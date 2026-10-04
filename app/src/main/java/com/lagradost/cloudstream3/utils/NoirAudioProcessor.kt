package com.lagradost.cloudstream3.utils

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * NOIR SOUND v3 — DSP 100% DI DALAM APP via media3 AudioProcessor.
 *
 * KENAPA: riset (Symfonium/Unity/Streamify issue tracker) menunjukkan efek
 * audio vendor (DynamicsProcessing/Equalizer/BassBoost via audiofx) sering
 * crackling/"suara listrik" karena HAL DSP OEM yang bug — di luar kendali
 * app. Satu-satunya jalan bersih: olah sampel sendiri di pipeline ExoPlayer,
 * deterministik di semua perangkat.
 *
 * BATASAN ANTI-PECAH (ilmu audio digital):
 *  1. Semua matematika float; EQ landai maks +2 dB; TIDAK ada gain netto.
 *  2. Soft-knee limiter tanh mulai 0.85 => puncak tak pernah >= 1.0
 *     (0 dBFS), berapapun sumbernya — mustahil clipping digital.
 *  3. High-pass 28 Hz membuang DC/rumble yang membuat speaker kecil kotor.
 *  4. Biquad RBJ cookbook (stabil numerik), state per-channel, reset bersih.
 */
@UnstableApi
class NoirAudioProcessor : AudioProcessor {

    private var inputFormat: AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    // state biquad [channel][filter][2]
    private val z = Array(2) { Array(3) { FloatArray(2) } }
    private var coeffs: Array<FloatArray> = emptyArray()

    // AGC (pengangkat passage pelan) — envelope + gain halus
    private var env = 0f
    private var smoothGain = 1f

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..2
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        coeffs = buildCoeffs(inputAudioFormat.sampleRate)
        for (c in z) for (f in c) { f[0] = 0f; f[1] = 0f }
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        inputFormat != AudioProcessor.AudioFormat.NOT_SET && coeffs.isNotEmpty()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            return
        }
        val out = ByteBuffer.allocateDirect(remaining).order(ByteOrder.LITTLE_ENDIAN)
        if (coeffs.isEmpty() && NoirSound.boostDb <= 0f && !NoirSound.agcOn) {
            // semua fitur mati: passthrough murni, jangan sentuh audio
            for (i in 0 until remaining) out.put(inputBuffer.get())
            out.flip()
            outputBuffer = out
            return
        }
        val chs = inputFormat.channelCount
        val n = remaining / 2
        // State live: boost gesture >100% & AGC "pengeras film pelan".
        val boost = NoirSound.boostDb
        val agc = NoirSound.agcOn
        val boostLin = if (boost > 0f) Math.pow(10.0, boost / 20.0).toFloat() else 1f
        val eqOn = coeffs.isNotEmpty() && NoirSound.lastEnabled
        for (i in 0 until n) {
            // Baca PCM16 LITTLE-ENDIAN manual (byte order buffer tak dijamin):
            // salah endianness = suara jadi sampah/"listrik".
            val lo = inputBuffer.get().toInt() and 0xFF
            val hi = inputBuffer.get().toInt()
            var x = (((hi shl 8) or lo) / 32768f)
            // AGC: ikuti puncak pelan, angkat menuju target 0.22 (maks +12 dB),
            // gain diubah sangat perlahan -> tanpa pompa/napas terdengar.
            if (agc) {
                val pk = if (x < 0f) -x else x
                if (pk > env) env = pk else env *= 0.99985f
                val desired = (0.22f / (if (env > 1e-4f) env else 1e-4f))
                    .coerceIn(1f, 4f)
                smoothGain += (desired - smoothGain) * 0.002f
                x *= smoothGain
            }
            if (boostLin > 1f) x *= boostLin
            val ch = if (chs == 2) i % 2 else 0
            if (eqOn) {
                for (f in coeffs.indices) {
                    val c = coeffs[f]
                    val s = z[ch][f]
                    val y = c[0] * x + s[0]
                    s[0] = c[1] * x - c[3] * y + s[1]
                    s[1] = c[2] * x - c[4] * y
                    x = y
                }
            }
            x = softLimit(x)
            val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
            out.put((v and 0xFF).toByte())          // low byte dulu (LE)
            out.put(((v shr 8) and 0xFF).toByte())  // high byte
        }
        out.flip()
        outputBuffer = out
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val b = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return b
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        for (c in z) for (f in c) { f[0] = 0f; f[1] = 0f }
    }

    override fun reset() {
        flush()
    }

    /** Soft-knee: linier sampai 0.85, lalu tanh — puncak tak pernah >= 1. */
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.85f) x
        else Math.signum(x) * (0.85f + 0.14f * tanh((a - 0.85f) / 0.14f))
    }

    private fun buildCoeffs(sr: Int): Array<FloatArray> {
        val p = try {
            NoirSound.PROFILES[NoirSound.lastPreset.coerceIn(0, NoirSound.PROFILES.lastIndex)]
        } catch (_: Throwable) {
            NoirSound.PROFILES[0]
        }
        return arrayOf(
            highPass(28f, sr),
            peak(100f, p.lowDb, 0.7f, sr),
            peak(8000f, p.highDb, 0.7f, sr)
        )
    }

    // ---------------- RBJ Audio EQ Cookbook (persis) ----------------

    private fun highPass(fc: Float, sr: Int): FloatArray {
        val w = 2.0 * Math.PI * fc / sr
        val alpha = sin(w) / (2 * 0.7071)
        val b0 = (1 + cos(w)) / 2
        val b1 = -(1 + cos(w))
        val b2 = (1 + cos(w)) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cos(w)
        val a2 = 1 - alpha
        return floatArrayOf(
            (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat()
        )
    }

    private fun peak(fc: Float, gainDb: Float, q: Float, sr: Int): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * Math.PI * fc / sr
        val alpha = sin(w) / (2 * q)
        val b0 = 1 + alpha * a
        val b1 = -2 * cos(w)
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cos(w)
        val a2 = 1 - alpha / a
        return floatArrayOf(
            (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat()
        )
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    }
}
