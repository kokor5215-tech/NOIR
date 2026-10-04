package com.lagradost.cloudstream3.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * NOIR — haptic feedback halus ala aplikasi peraih Apple Design Awards
 * (kategori Interaction/Inclusivity: "haptic feedback woven throughout").
 *
 * Prinsip: haptik hanya sebagai KONFIRMASI taktil mikro pada aksi eksplisit
 * (pindah tab, toggle, pilih tren) — bukan getar panjang yang mengganggu.
 * performHapticFeedback otomatis menghormati setting sistem "haptic off"
 * (inklusivitas), jadi tidak perlu preferensi tambahan.
 */
object NoirHaptic {
    /**
     * NOIR intro: hentakan rendah sekali saat "dum" audio — intro terasa
     * di dada, bukan cuma di mata & telinga (multisensorik ala Apple).
     * Menghormati mode hening & setting getar sistem.
     */
    fun thump(context: android.content.Context?) {
        context ?: return
        try {
            val am = context.getSystemService(
                android.content.Context.AUDIO_SERVICE
            ) as? android.media.AudioManager
            if (am?.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) return
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator
            if (vib?.hasVibrator() == true && android.os.Build.VERSION.SDK_INT >= 26) {
                // VibrationEffect hanya ada di API 26+ (minSdk app 24)
                vib.vibrate(
                    android.os.VibrationEffect.createOneShot(70, 160)
                )
            }
        } catch (_: Throwable) {
        }
    }

    fun tap(v: View?) {
        v ?: return
        try {
            v.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30)
                    HapticFeedbackConstants.GESTURE_START   // tick halus (API 30+)
                else
                    HapticFeedbackConstants.VIRTUAL_KEY     // fallback halus
            )
        } catch (_: Throwable) {
            // beberapa ROM melempar exception pada konstanta baru — abaikan
        }
    }
}
