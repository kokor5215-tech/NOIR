package com.lagradost.cloudstream3.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import com.lagradost.cloudstream3.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * NOIR — IDENT AUDIO ORISINAL "GULUNGAN - BAYANGAN - CAHAYA".
 * Konsep milik Noir sendiri (bukan ta-dum): identitas film-noir.
 *
 *  1) GULUNGAN  : klik halus proyektor film pada 180/290/400/510 ms,
 *                 sinkron dengan huruf N-O-I-R muncul satu per satu.
 *  2) BAYANGAN  : drone "berasap" D2+A2 sedikit detune, membengkak pelan
 *                 mulai 550 ms — seperti kabut malam.
 *  3) CAHAYA    : satu denting bell kaca D5 pada 1450 ms — cahaya tunggal
 *                 menembus gelap, momen wordmark bersinar.
 *
 * Motif interval D-A (perfect fifth khas Noir). Disintesis offline via
 * numpy, mastering tanh -1 dBTP, dibundel: res/raw/noir_ident.wav.
 * Bila aset gagal dimuat, fallback sintesis bell runtime supaya intro
 * tidak pernah sunyi mendadak.
 */
object NoirChime {

    private const val TAG = "NoirChime"

    const val TICK1_MS = 180L
    const val SWELL_MS = 550L
    const val BELL_MS = 1450L

    private const val SAMPLE_RATE = 44100
    private const val DURATION_SEC = 2.6
    private const val MASTER_GAIN = 0.16

    private val BELL_PARTIALS = arrayOf(
        Triple(1.00, 1.00, 1.90),
        Triple(2.00, 0.42, 1.10),
        Triple(2.40, 0.26, 0.80),
        Triple(3.01, 0.16, 0.55),
        Triple(4.52, 0.09, 0.32)
    )

    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var worker: Thread? = null

    @Volatile
    private var cancelled = false

    /** Putar ta-dum sekali. Aman dipanggil berulang. */
    fun play(context: Context) {
        stop()
        cancelled = false

        // Hormati volume media user. Kalau media di-nol-kan, jangan bunyi.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        if (maxVol <= 0 || curVol <= 0) return
        val volumeScale = 0.45f + 0.55f * (curVol.toFloat() / maxVol)

        // Jalur utama: ident WAV bundel (kualitas mastering penuh).
        try {
            val mp = MediaPlayer.create(context, R.raw.noir_ident)
            if (mp != null) {
                mp.setVolume(volumeScale, volumeScale)
                mp.setOnCompletionListener { p -> p.release() }
                synchronized(this) {
                    if (cancelled) mp.release() else { player = mp; mp.start() }
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aset ta-dum gagal, fallback bell: ${e.message}")
        }

        // Fallback: sintesis bell runtime (v1).
        worker = Thread {
            try {
                val samples = render(volumeScale)
                val skipped = cancelled || Thread.currentThread().isInterrupted
                val newTrack = if (skipped) null else buildTrack(samples)
                if (newTrack != null) {
                    synchronized(this) {
                        if (cancelled) newTrack.release()
                        else { track = newTrack; newTrack.play() }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chime dilewati: ${e.message}")
            }
        }.apply { isDaemon = true; name = "NoirChime"; start() }
    }

    private fun buildTrack(samples: ShortArray): AudioTrack? {
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(samples, 0, samples.size) }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack gagal dibuat: ${e.message}")
            null
        }
    }

    fun stop() {
        val pending: AudioTrack?
        val pendingPlayer: MediaPlayer?
        synchronized(this) {
            cancelled = true
            pending = track; track = null
            pendingPlayer = player; player = null
        }
        worker?.interrupt(); worker = null
        pending?.let {
            try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        pendingPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
    }

    // ---------------- fallback bell synth (v1) ----------------

    private fun render(volumeScale: Float): ShortArray {
        val total = (SAMPLE_RATE * DURATION_SEC).toInt()
        val buf = DoubleArray(total)
        addBell(buf, 440.00, 0.00, 1.00)
        addBell(buf, 659.25, 0.34, 0.78)
        val fadeSamples = (SAMPLE_RATE * 0.22).toInt()
        for (i in 0 until fadeSamples) {
            val idx = total - fadeSamples + i
            buf[idx] *= 1.0 - (i.toDouble() / fadeSamples)
        }
        var peak = 0.0
        for (v in buf) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak < 1e-9) peak = 1.0
        val scale = MASTER_GAIN * volumeScale / peak
        val out = ShortArray(total)
        for (i in buf.indices) {
            val v = buf[i] * scale
            out[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun addBell(buf: DoubleArray, fundamental: Double, startTimeSec: Double, amp: Double) {
        val startSample = (startTimeSec * SAMPLE_RATE).toInt()
        if (startSample >= buf.size) return
        val attackSamples = (SAMPLE_RATE * 0.008).toInt()
        for (i in startSample until buf.size) {
            val t = (i - startSample).toDouble() / SAMPLE_RATE
            var sample = 0.0
            for ((ratio, partialAmp, decay) in BELL_PARTIALS) {
                val freq = fundamental * ratio
                if (freq > SAMPLE_RATE / 2.0) continue
                sample += partialAmp * exp(-t / decay) * sin(2.0 * PI * freq * t)
            }
            val attack = if (attackSamples > 0 && (i - startSample) < attackSamples) {
                (i - startSample).toDouble() / attackSamples
            } else 1.0
            buf[i] += sample * amp * attack
        }
    }
}
