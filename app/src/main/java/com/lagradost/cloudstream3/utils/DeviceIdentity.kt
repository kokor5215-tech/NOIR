package com.lagradost.cloudstream3.utils

import android.content.Context
import android.provider.Settings
import kotlin.math.abs

/**
 * NOIR — identitas perangkat.
 *
 * Dipertahankan dari fork sebelumnya karena berguna sebagai ID diagnostik
 * saat user melapor masalah. Sudah TIDAK terhubung ke sistem lisensi apa pun:
 * tidak ada pemeriksaan server, tidak ada penyimpanan status, tidak ada
 * jaringan. Murni turunan lokal dari ANDROID_ID.
 *
 * Catatan: nilainya berubah setelah factory reset. Jangan dipakai sebagai
 * kunci keamanan — ini hanya label diagnostik.
 */
object DeviceIdentity {

    /** 8 digit turunan ANDROID_ID, untuk ditampilkan & disalin user. */
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "default_device"
        return abs(androidId.hashCode()).toString().take(8)
    }
}
