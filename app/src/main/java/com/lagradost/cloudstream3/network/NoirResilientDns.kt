package com.lagradost.cloudstream3.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * NOIR FIX — diagnosis mendalam masalah "keluar app lama, balik lagi =
 * disconnect/connecting terus, search ngawur, provider hilang":
 *
 * AKAR MASALAH (dua lapis yang saling memperparah):
 * 1. App memakai DNS-over-HTTPS (DoH) penuh TANPA fallback. Setelah
 *    perangkat tidur lama, sesi HTTP/2 & socket DoH mati/terputus dan
 *    riwayat OkHttp pun mencatat bug "stale DNS/connection" di sekitar
 *    DnsOverHttps — akibatnya resolusi nama gagal massal dan SEMUA
 *    request (search, homepage, poster) gagal di tahap DNS.
 * 2. Kegagalan massal itu lalu dihitung NoirProviderHealth sebagai
 *    "provider mati" dan memblokir mereka 30 menit. Jadinya: provider
 *    tidak muncul & hasil kosong, sampai user force-stop.
 *
 * SOLUSI: DoH dibungkus fallback otomatis ke DNS sistem Android.
 * Bila DoH sehat dipakai DoH (privasi tetap); bila DoH mati/stale,
 * resolusi langsung lewat DNS bawaan OS tanpa user sadar apa pun.
 * Pola "wrap DoH + system fallback" ini rekomendasi komunitas OkHttp
 * untuk kasus exactly seperti ini.
 */
class NoirResilientDns(private val delegate: Dns) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            delegate.lookup(hostname)
        } catch (t: Throwable) {
            // DoH gagal (stale socket, timeout, dsb) — jangan biarkan
            // seluruh app lumpuh; pakai DNS sistem sebagai jaring pengaman.
            Dns.SYSTEM.lookup(hostname)
        }
    }

    companion object {
        private fun doh(client: OkHttpClient, url: String, ips: List<String>): Dns =
            NoirResilientDns(
                DnsOverHttps.Builder()
                    .client(client)
                    .url(url.toHttpUrl())
                    .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                    .build()
            )

        fun google(client: OkHttpClient): Dns = doh(
            client, "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")
        )

        fun cloudflare(client: OkHttpClient): Dns = doh(
            client, "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        )

        fun adguard(client: OkHttpClient): Dns = doh(
            client, "https://dns.adguard.com/dns-query",
            listOf("94.140.14.140", "94.140.14.141")
        )

        fun dnsWatch(client: OkHttpClient): Dns = doh(
            client, "https://resolver2.dns.watch/dns-query",
            listOf("84.200.69.80", "84.200.70.40")
        )

        fun quad9(client: OkHttpClient): Dns = doh(
            client, "https://dns.quad9.net/dns-query",
            listOf("9.9.9.9", "149.112.112.112")
        )

        fun dnsSb(client: OkHttpClient): Dns = doh(
            client, "https://doh.dns.sb/dns-query",
            listOf("185.222.222.222", "45.11.45.11")
        )

        fun canadianShield(client: OkHttpClient): Dns = doh(
            client, "https://private.canadianshield.cira.ca/dns-query",
            listOf("149.112.121.10", "149.112.122.10")
        )
    }
}
