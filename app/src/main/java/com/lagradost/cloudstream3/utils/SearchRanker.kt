package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SearchResponse

/**
 * NOIR — pengurut hasil pencarian berbasis kemiripan teks.
 *
 * Masalah yang dipecahkan:
 *   Hasil pencarian sebelumnya diurutkan round-robin antar penyedia
 *   (ambil hasil ke-1 dari tiap penyedia, lalu ke-2, dst). Itu adil,
 *   tapi tidak peduli seberapa cocok judulnya dengan yang user ketik.
 *   Akibatnya judul yang persis sama bisa terdorong jauh ke bawah hanya
 *   karena penyedia yang menemukannya "giliran belakang".
 *
 * Solusinya:
 *   Setiap judul diberi skor relevansi terhadap kueri, lalu diurutkan
 *   menurun secara STABIL. Stabil artinya urutan round-robin yang lama
 *   tetap menjadi pemecah seri — jadi perilaku lama tidak rusak, hanya
 *   hasil yang lebih cocok yang diangkat ke atas.
 *
 * Soal salah ketik:
 *   Ditangani oleh Levenshtein.partialRatio (sudah ada di proyek ini,
 *   dari FuzzyKot, MIT). "narutp" tetap mendapat skor tinggi terhadap
 *   "Naruto".
 *
 * Soal lupa judul:
 *   partialRatio cocok untuk itu — ia mencari kemiripan terbaik pada
 *   sebagian teks, jadi mengetik "ship" masih bisa menemukan
 *   "One Piece: Film Z".
 *
 * Biaya: O(n · |q| · |t|). Untuk 500 hasil dengan judul pendek ini
 * di bawah satu milidetik per judul, jadi aman di thread IO.
 */
object SearchRanker {

    // NOIR perf: Regex di-compile SEKALI. Sebelumnya tiap panggilan normalize()
    // meng-compile pattern baru — pada ratusan hasil × beberapa provider per
    // detik, ini menyumbang jank nyata saat render pencarian.
    private val WS = Regex("\\s+")

    /** Normalisasi supaya perbandingan tidak sensitif huruf besar/spasi ganda. */
    fun normalize(s: String): String =
        s.lowercase().trim().replace(WS, " ")

    /**
     * Skor relevansi judul terhadap kueri.
     * Semakin tinggi semakin cocok. Skala sengaja diberi jarak lebar
     * supaya kategori kuat tidak tercampur dengan skor fuzzy 0–100.
     */
    fun relevanceScore(query: String, title: String): Int {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0

        val base = when {
            t == q -> 1000

            t.startsWith(q) -> 900 + minOf(99, q.length)

            // Salah satu kata pada judul diawali kueri
            // (mis. kueri "piece" vs judul "one piece").
            t.split(' ').any { it.startsWith(q) } -> 750 + minOf(99, q.length)

            // Semua kata pada kueri muncul di judul, urutan bebas
            // (mis. kueri "attack titan" vs judul "Attack on Titan").
            q.split(' ').all { tok -> t.contains(tok) } -> 700 + minOf(99, q.length)

            t.contains(q) -> 600 + minOf(99, q.length)

            // Tidak ada kecocokan langsung: andalkan kemiripan fuzzy.
            // partialRatio menangani salah ketik dan kueri separuh judul.
            else -> Levenshtein.partialRatio(q, t) { it }
        }

        // Penentu seri: pada skor yang sama, judul yang lebih pendek
        // (lebih dekat dengan kueri) menang atas varian panjang.
        val lengthPenalty = minOf(25, (t.length - q.length).coerceAtLeast(0))
        return base - lengthPenalty
    }

    /**
     * Urutkan hasil pencarian. STABIL: urutan masuk dipertahankan untuk
     * skor yang sama, jadi round-robin antar penyedia tetap berlaku
     * sebagai pemecah seri.
     */
    fun rank(query: String, items: List<SearchResponse>): List<SearchResponse> {
        val q = normalize(query)
        if (q.length < 2) return items          // terlalu pendek, jangan diacak
        if (items.size < 2) return items

        return items.sortedByDescending { relevanceScore(q, it.name) }
    }
}
