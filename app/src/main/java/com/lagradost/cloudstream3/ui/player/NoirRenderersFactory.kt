package com.lagradost.cloudstream3.ui.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.lagradost.cloudstream3.utils.NoirAudioProcessor
import com.lagradost.cloudstream3.utils.NoirSound

/**
 * NOIR SOUND v3: menyuntik NoirAudioProcessor (DSP in-app anti-pecah) ke
 * rantai audio media3 lewat titik resmi buildAudioSink() (signature 1.9.3:
 * Context, enableFloatOutput, enableAudioTrackPlaybackParams). Tidak memakai
 * efek vendor. Prosesor SELALU terpasang: boost gesture & AGC harus hidup
 * walau NoirSound mati; bila semua fitur idle prosesor = passthrough murni
 * sehingga biayanya dapat diabaikan.
 */
@UnstableApi
private fun buildNoirAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean
): AudioSink {
    NoirSound.refresh(context)
    return DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(NoirAudioProcessor()))
        .build()
}

@UnstableApi
open class NoirDefaultRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val ctx = context

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = buildNoirAudioSink(ctx, enableFloatOutput, enableAudioTrackPlaybackParams)
}

@UnstableApi
class NoirNextRenderersFactory(context: Context) : FixedNextRenderersFactory(context) {
    private val ctx = context

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = buildNoirAudioSink(ctx, enableFloatOutput, enableAudioTrackPlaybackParams)
}
