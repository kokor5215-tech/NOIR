package com.lagradost.cloudstream3.ui.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.NoirOfflineLyricsTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.NoirLyrics
import com.lagradost.cloudstream3.utils.NoirSpotiflacProvider

/**
 * NOIR fix18 — player musik KHUSUS, gaya Apple Music: sampul dinamis,
 * kontrol audio, lirik tersinkron, terjemahan/romanisasi. Layout terpisah
 * dari player film. Pemilihan track diberi generation guard agar tap prev/next
 * cepat tidak bisa menyalakan stream lagu yang sudah diganti.
 *
 * Sumber suara = provider "Noir Music" (SoundCloud/Qobuz/Tidal); lirik dari
 * [NoirLyrics] (YouTube Music, Musixmatch, BiniLyrics, bLyrics, LRCLIB).
 */
@OptIn(UnstableApi::class)
class NoirMusicPlayerActivity : AppCompatActivity() {

    data class QueueItem(
        val url: String,
        val title: String,
        val artist: String?,
        val cover: String?,
        val source: String?,
    )

    companion object {
        private const val EXTRA_INDEX = "index"

        /** Antrean sesi: diisi SearchFragment saat hasil musik dirender. */
        var queue: List<QueueItem> = emptyList()

        private fun parseCard(card: SearchResponse): QueueItem {
            // Nama kartu: "Judul - Artis (Label)"
            val raw = card.name
            val source = Regex("\\(([^)]+)\\)$").find(raw)?.groupValues?.get(1)
            val base = raw.replace(Regex("\\s*\\([^)]*\\)$"), "").trim()
            val parts = base.split(" - ", limit = 2)
            return QueueItem(
                url = card.url,
                title = parts[0].trim(),
                artist = parts.getOrNull(1)?.trim(),
                cover = card.posterUrl,
                source = source,
            )
        }

        /** Diisi SearchFragment tiap render hasil: antrean sesi prev/next. */
        fun fillQueue(cards: List<SearchResponse>) {
            queue = cards.map { parseCard(it) }
        }

        /** Pintu masuk tunggal dari hasil pencarian. */
        fun openFrom(context: Context, card: SearchResponse) {
            val idx = queue.indexOfFirst { it.url == card.url }
            val intent = Intent(context, NoirMusicPlayerActivity::class.java)
            if (idx >= 0) {
                intent.putExtra(EXTRA_INDEX, idx)
            } else {
                queue = listOf(parseCard(card))
                intent.putExtra(EXTRA_INDEX, 0)
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private var player: ExoPlayer? = null
    private var activeQueue: List<QueueItem> = emptyList()
    private var index = 0
    private var trackGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false
    private var lyricsPack: NoirLyrics.Pack? = null
    private var translations: List<String>? = null
    private var romanized: List<String>? = null
    private var translationJob: Job? = null
    private var modelPromptDialog: AlertDialog? = null
    private var showTrans = false
    private var showRoman = false
    private var activeLine = -1

    // Views
    private lateinit var bg: ImageView
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var playBtn: ImageView
    private lateinit var lyricsRv: RecyclerView
    private lateinit var lyricsStatus: TextView
    private lateinit var lyricsLabel: TextView
    private lateinit var transToggle: TextView
    private lateinit var romanToggle: TextView
    private lateinit var badge: TextView
    private val lyricsAdapter = LyricsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_noir_music_player)
        activeQueue = queue.toList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceAtLeast(0)

        bg = findViewById(R.id.noir_music_bg)
        cover = findViewById(R.id.noir_music_cover)
        title = findViewById(R.id.noir_music_title)
        artist = findViewById(R.id.noir_music_artist)
        seek = findViewById(R.id.noir_music_seek)
        pos = findViewById(R.id.noir_music_pos)
        dur = findViewById(R.id.noir_music_dur)
        playBtn = findViewById(R.id.noir_music_play)
        lyricsRv = findViewById(R.id.noir_music_lyrics)
        lyricsStatus = findViewById(R.id.noir_music_lyrics_status)
        lyricsLabel = findViewById(R.id.noir_music_lyrics_label)
        transToggle = findViewById(R.id.noir_music_trans_toggle)
        romanToggle = findViewById(R.id.noir_music_roman_toggle)
        badge = findViewById(R.id.noir_music_source_badge)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, bg).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        title.isSelected = true
        lyricsRv.layoutManager = LinearLayoutManager(this)
        lyricsRv.adapter = lyricsAdapter

        findViewById<ImageView>(R.id.noir_music_back).setOnClickListener { finish() }
        findViewById<FrameLayout>(R.id.noir_music_play_wrap).setOnClickListener { togglePlay() }
        findViewById<ImageView>(R.id.noir_music_prev).setOnClickListener { step(-1) }
        findViewById<ImageView>(R.id.noir_music_next).setOnClickListener { step(1) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) pos.text = fmt(p.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                sb?.progress?.let { player?.seekTo(it.toLong()) }
            }
        })

        transToggle.setOnClickListener {
            showTrans = !showTrans
            stylePill(transToggle, showTrans)
            lyricsAdapter.notifyDataSetChanged()
            if (showTrans && translations == null) {
                loadTranslation()
            } else if (!showTrans) {
                modelPromptDialog?.dismiss()
                if (translationJob?.isActive == true) {
                    cancelTranslationWork()
                } else if (lyricsPack != null && translations == null && !showRoman) {
                    lyricsStatus.visibility = View.GONE
                    lyricsStatus.setOnClickListener(null)
                }
            }
        }
        lyricsStatus.setOnClickListener {
            if (translationJob?.isActive == true) cancelTranslationWork()
        }
        romanToggle.setOnClickListener {
            showRoman = !showRoman
            stylePill(romanToggle, showRoman)
            lyricsAdapter.notifyDataSetChanged()
            if (showRoman && romanized == null) loadRomanization()
        }

        loadTrack(index)
        handler.post(tick)
    }

    private fun stylePill(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.noir_music_pill_on else R.drawable.noir_music_pill)
        v.setTextColor(if (on) 0xFF111111.toInt() else 0xFFAAAAAA.toInt())
    }

    /** Render metadata + latar + ambil stream dari provider, lalu prepare. */
    private fun loadTrack(i: Int) {
        val item = activeQueue.getOrNull(i) ?: return
        val generation = ++trackGeneration
        cancelTranslationWork()
        modelPromptDialog?.dismiss()
        modelPromptDialog = null
        index = i
        player?.release()
        player = null
        title.text = item.title
        artist.text = item.artist ?: ""
        badge.text = item.source ?: ""
        seek.progress = 0
        seek.max = 0
        pos.text = fmt(0)
        dur.text = ""
        lyricsPack = null
        translations = null
        romanized = null
        activeLine = -1
        lyricsAdapter.submit(emptyList(), null, null)
        lyricsStatus.visibility = View.VISIBLE
        lyricsStatus.text = getString(R.string.noir_music_loading)
        lyricsLabel.text = getString(R.string.noir_music_lyrics)

        val corner = RoundedCornersTransformation(56f)
        cover.load(item.cover) {
            crossfade(true)
            transformations(corner)
        }
        // Latar: gambar besar; blur asli di API 31+ (RenderEffect).
        bg.load(item.cover) {
            crossfade(true)
            transformations(RoundedCornersTransformation(0f))
        }
        bg.scaleX = 1.35f
        bg.scaleY = 1.35f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bg.setRenderEffect(
                android.graphics.RenderEffect
                    .createBlurEffect(70f, 70f, android.graphics.Shader.TileMode.CLAMP)
            )
        }

        ioSafe {
            var picked: Pair<String, Boolean>? = null
            runCatching {
                NoirSpotiflacProvider().loadLinks(item.url, false, {}) { link ->
                    if (picked == null) {
                        picked = link.url to (link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8)
                    }
                }
            }.onFailure { logError(it) }
            main {
                if (generation != trackGeneration || isFinishing || isDestroyed) return@main
                val (url, hls) = picked ?: run {
                    lyricsStatus.text = getString(R.string.noir_music_load_failed)
                    return@main
                }
                preparePlayer(url, hls, item, generation)
            }
        }
    }

    private fun preparePlayer(url: String, hls: Boolean, item: QueueItem, generation: Int) {
        if (generation != trackGeneration || isFinishing || isDestroyed) return
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        exo.setMediaItem(mediaItem)
        player = exo
        var requestedLyrics = false
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != trackGeneration) return
                when (state) {
                    Player.STATE_READY -> {
                        if (exo.duration > 0) dur.text = fmt(exo.duration)
                        if (!requestedLyrics) {
                            requestedLyrics = true
                            val secs = (exo.duration / 1000).takeIf { it > 0 } ?: 0
                            fetchLyrics(item, secs, generation)
                        }
                    }
                    Player.STATE_ENDED -> {
                        playBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != trackGeneration) return
                playBtn.setImageResource(
                    if (isPlaying) R.drawable.ic_baseline_pause_24
                    else R.drawable.ic_baseline_play_arrow_24
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != trackGeneration) return
                logError(error)
                lyricsStatus.visibility = View.VISIBLE
                lyricsStatus.text = getString(R.string.noir_music_load_failed)
                playBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun fetchLyrics(item: QueueItem, durationSec: Long, generation: Int) {
        ioSafe {
            val pack = runCatching {
                NoirLyrics.fetch(item.title, item.artist, durationSec)
            }.getOrNull()
            main {
                if (generation != trackGeneration || isFinishing || isDestroyed) return@main
                if (pack == null || pack.lines.isEmpty()) {
                    lyricsStatus.visibility = View.VISIBLE
                    lyricsStatus.text = getString(R.string.noir_music_no_lyrics)
                    return@main
                }
                lyricsPack = pack
                translations = pack.embeddedTranslations
                romanized = pack.embeddedRomanizations
                lyricsLabel.text = getString(R.string.noir_music_lyrics) +
                    " · " + pack.source + if (pack.synced) "" else " (tanpa waktu)"
                lyricsStatus.visibility = View.GONE
                lyricsAdapter.submit(pack.lines, translations, romanized)
                // Jika opsi sudah dinyalakan saat lirik masih dimuat, jalankan
                // provider sidecar/terjemahan setelah pack tersedia.
                if (showTrans && translations == null) loadTranslation()
                if (showRoman && romanized == null) loadRomanization()
            }
        }
    }

    private fun loadTranslation() {
        if (lyricsPack == null || translationJob?.isActive == true) return
        // Try local cache first; cached translations remain usable even if the
        // user later removes the model. A missing model is prompted afterward.
        startLocalTranslation(trackGeneration, downloadFirst = false)
    }

    private fun showModelDownloadPrompt(generation: Int) {
        if (generation != trackGeneration || isFinishing || isDestroyed) return
        showTranslationStatus(getString(R.string.noir_music_model_not_downloaded))
        modelPromptDialog?.dismiss()
        modelPromptDialog = AlertDialog.Builder(this)
            .setTitle(R.string.noir_music_translation_local_title)
            .setMessage(R.string.noir_music_model_download_prompt)
            .setPositiveButton(R.string.noir_music_model_download) { _, _ ->
                if (generation == trackGeneration) startLocalTranslation(generation, downloadFirst = true)
            }
            .setNeutralButton(R.string.noir_music_model_license) { _, _ -> showAiModelLicense() }
            .setNegativeButton(R.string.noir_music_not_now, null)
            .setOnDismissListener { modelPromptDialog = null }
            .create()
            .also { it.show() }
    }

    private fun showAiModelLicense() {
        val notice = runCatching {
            assets.open("NOIR_AI_NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrElse { getString(R.string.noir_music_model_license_unavailable) }
        AlertDialog.Builder(this)
            .setTitle(R.string.noir_music_model_license_title)
            .setMessage(notice)
            .setPositiveButton(R.string.noir_music_close, null)
            .show()
    }

    private fun startLocalTranslation(generation: Int, downloadFirst: Boolean) {
        val pack = lyricsPack ?: return
        translationJob?.cancel()
        translationJob = lifecycleScope.launch {
            try {
                if (downloadFirst) {
                    showTranslationStatus(getString(R.string.noir_music_model_downloading, 0, "0.00 GB"))
                    NoirOfflineLyricsTranslator.downloadModel(applicationContext) { received, total, verifying ->
                        val message = if (verifying) {
                            getString(R.string.noir_music_model_verifying)
                        } else {
                            val percent = if (total > 0) ((received * 100L) / total).toInt().coerceIn(0, 100) else 0
                            getString(R.string.noir_music_model_downloading, percent, formatGigabytes(received))
                        }
                        runOnUiThread {
                            if (generation == trackGeneration && !isFinishing && !isDestroyed) {
                                showTranslationStatus(message)
                            }
                        }
                    }
                }
                if (generation != trackGeneration || isFinishing || isDestroyed) return@launch

                showTranslationStatus(getString(R.string.noir_music_model_loading))
                val result = NoirLyrics.translate(
                    applicationContext,
                    pack.lines.map { it.text },
                    "id",
                ) { completed, total ->
                    runOnUiThread {
                        if (generation == trackGeneration && !isFinishing && !isDestroyed) {
                            showTranslationStatus(getString(R.string.noir_music_translating, completed, total))
                        }
                    }
                }
                if (generation != trackGeneration || isFinishing || isDestroyed) return@launch

                if (result != null && result.size == pack.lines.size) {
                    translations = result
                    lyricsAdapter.submit(pack.lines, translations, romanized)
                    lyricsStatus.visibility = View.GONE
                    lyricsStatus.setOnClickListener(null)
                } else if (showTrans) {
                    when (NoirOfflineLyricsTranslator.readiness(applicationContext)) {
                        NoirOfflineLyricsTranslator.Readiness.MISSING_MODEL -> showModelDownloadPrompt(generation)
                        NoirOfflineLyricsTranslator.Readiness.UNSUPPORTED_ABI -> showTranslationStatus(
                            getString(R.string.noir_music_translation_unsupported_device)
                        )
                        NoirOfflineLyricsTranslator.Readiness.LOW_MEMORY -> showTranslationStatus(
                            getString(R.string.noir_music_translation_low_memory)
                        )
                        NoirOfflineLyricsTranslator.Readiness.READY -> showTranslationStatus(
                            getString(R.string.noir_music_translation_failed)
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (generation == trackGeneration && showTrans && !isFinishing && !isDestroyed) {
                    showTranslationStatus(getString(R.string.noir_music_translation_cancelled))
                }
                throw e
            } catch (e: NoirOfflineLyricsTranslator.InsufficientStorageException) {
                if (generation == trackGeneration && showTrans && !isFinishing && !isDestroyed) {
                    showTranslationStatus(getString(R.string.noir_music_model_insufficient_storage))
                }
            } catch (e: NoirOfflineLyricsTranslator.LowMemoryException) {
                if (generation == trackGeneration && showTrans && !isFinishing && !isDestroyed) {
                    showTranslationStatus(getString(R.string.noir_music_translation_low_memory))
                }
            } catch (e: Exception) {
                com.lagradost.cloudstream3.mvvm.logError(e)
                if (generation == trackGeneration && showTrans && !isFinishing && !isDestroyed) {
                    showTranslationStatus(getString(R.string.noir_music_model_download_failed))
                }
            }
        }
    }

    private fun showTranslationStatus(message: String) {
        lyricsStatus.visibility = View.VISIBLE
        lyricsStatus.text = message
        lyricsStatus.setOnClickListener {
            if (translationJob?.isActive == true) cancelTranslationWork()
        }
    }

    private fun cancelTranslationWork() {
        translationJob?.cancel()
        translationJob = null
        NoirOfflineLyricsTranslator.cancelCurrentGeneration()
        if (!showTrans && translations == null && !isFinishing && !isDestroyed) {
            lyricsStatus.visibility = View.GONE
            lyricsStatus.setOnClickListener(null)
        }
    }

    private fun formatGigabytes(bytes: Long): String =
        String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)

    private fun loadRomanization() {
        val pack = lyricsPack ?: return
        val generation = trackGeneration
        ioSafe {
            val res = runCatching { NoirLyrics.romanize(pack.lines.map { it.text }) }.getOrNull()
            main {
                if (generation != trackGeneration || isFinishing || isDestroyed) return@main
                if (res != null && res.size == pack.lines.size) {
                    romanized = res
                    lyricsAdapter.submit(pack.lines, translations, romanized)
                } else if (showRoman && romanized == null) {
                    showTranslationStatus(getString(R.string.noir_music_romanization_unavailable))
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null) {
                val p = exo.currentPosition
                val d = exo.duration
                if (!seeking) {
                    seek.progress = p.toInt()
                    seek.max = if (d > 0) d.toInt() else 0
                    pos.text = fmt(p)
                }
                updateActiveLine(p)
            }
            handler.postDelayed(this, 250)
        }
    }

    private fun updateActiveLine(posMs: Long) {
        val pack = lyricsPack ?: return
        if (!pack.synced) return
        var idx = -1
        pack.lines.forEachIndexed { i, l ->
            if (l.startMs >= 0 && l.startMs <= posMs) idx = i
        }
        if (idx >= 0) {
            val line = pack.lines[idx]
            if (line.endMs >= 0 && posMs > line.endMs) idx = -1
        }
        if (idx != activeLine) {
            val old = activeLine
            activeLine = idx
            if (old >= 0) lyricsAdapter.notifyItemChanged(old)
            if (idx >= 0) {
                lyricsAdapter.notifyItemChanged(idx)
                // Gulir halus agar baris aktif di sepertiga atas panel.
                val lm = lyricsRv.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(idx, lyricsRv.height / 3)
            }
        }
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun step(delta: Int) {
        val next = index + delta
        if (activeQueue.getOrNull(next) != null) loadTrack(next)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelTranslationWork()
        modelPromptDialog?.dismiss()
        modelPromptDialog = null
        player?.release()
        player = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    // ── Adapter lirik karaoke ────────────────────────────────────────────
    private inner class LyricsAdapter :
        RecyclerView.Adapter<LyricsAdapter.VH>() {

        private var lines: List<NoirLyrics.LyricLine> = emptyList()
        private var trans: List<String>? = null
        private var roman: List<String>? = null

        @SuppressLint("NotifyDataSetChanged")
        fun submit(
            l: List<NoirLyrics.LyricLine>,
            t: List<String>?,
            r: List<String>?,
        ) {
            lines = l
            trans = t
            roman = r
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val main: TextView = v.findViewById(R.id.noir_lyric_main)
            val sub: TextView = v.findViewById(R.id.noir_lyric_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_noir_lyric, parent, false)
            return VH(v)
        }

        override fun getItemCount() = lines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val line = lines[position]
            val active = position == activeLine && lyricsPack?.synced == true
            holder.main.text = line.text
            holder.main.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            holder.main.textSize = if (active) 18f else 16f
            holder.main.setTypeface(
                null,
                if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            holder.main.alpha = if (active) 1f else 0.75f

            // Baris kedua: romanisasi (italic) + terjemahan, bila diaktifkan.
            val parts = ArrayList<String>()
            if (showRoman) roman?.getOrNull(position)?.takeIf { it.isNotBlank() }
                ?.let { parts.add(it) }
            if (showTrans) trans?.getOrNull(position)?.takeIf { it.isNotBlank() }
                ?.let { parts.add(it) }
            if (parts.isEmpty()) {
                holder.sub.visibility = View.GONE
            } else {
                holder.sub.visibility = View.VISIBLE
                holder.sub.text = parts.joinToString("\n")
                holder.sub.setTextColor(if (active) 0xCCFFFFFF.toInt() else 0x4DFFFFFF)
            }
        }
    }
}
