package com.lagradost.cloudstream3.utils

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.Scriptable
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * NOIR fix15 — "KAWIN" DENGAN SPOTIFLAC.
 *
 * SpotiFLAC (spotiflac.com) punya sistem extension terbuka: registry.json
 * resmi + paket .sflx (ZIP berisi manifest.json + index.js) yang aslinya
 * berjalan di engine goja (JS). Kita jalankan extension yang SAMA di Rhino
 * (engine JS yang sudah jadi dependensi app via NewPipe) dengan shim kecil
 * (log / storage / http.get / utils / URL / URLSearchParams / registerExtension).
 *
 * Diverifikasi empiris 2026-10-02 di sandbox: index.js SoundCloud resmi
 * termuat & initialize()=true; algoritma client_id-nya bekerja terhadap
 * bundle live a-v2.sndcdn.com; api-v2 search mengembalikan track nyata.
 *
 * Hanya extension yang TIDAK butuh signedSession/proxy komunitas yang
 * dijalankan (SoundCloud). Extension Qobuz/Tidal butuh proxy api.zarz.moe
 * yang sering mati berhari-hari — tidak kita paksa masuk.
 */
object NoirSpotiflac {

    private const val REGISTRY_OFFICIAL =
        "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"
    private const val REGISTRY_COMMUNITY =
        "https://raw.githubusercontent.com/zarzet/SpotiFLAC-Extension/main/registry.json"
    private const val SC_EXT_FALLBACK =
        "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/extensions/soundcloud.sflx"

    // Runtime features yang sanggup kita penuhi (tanpa signedSession zarz).
    private val SUPPORTED_FEATURES = setOf("preparedContext@1", "downloadSegments@1")

    data class RegistryExt(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("version") val version: String? = null,
        @JsonProperty("download_url") val downloadUrl: String? = null,
        @JsonProperty("category") val category: String? = null,
    )

    data class Registry(
        @JsonProperty("version") val version: Int? = null,
        @JsonProperty("extensions") val extensions: List<RegistryExt>? = null,
    )

    data class ScTrack(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("artists") val artists: String? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("external_urls") val url: String? = null,
        @JsonProperty("duration_ms") val durationMs: Long? = null,
    )

    data class ScStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("err") val err: String? = null,
    )

    @Volatile
    private var scope: Scriptable? = null
    private val loadMutex = Mutex()

    val isLoaded: Boolean get() = scope != null

    /** Ambil daftar extension dari registry resmi (fallback komunitas). */
    suspend fun fetchRegistry(): List<RegistryExt> = withContext(Dispatchers.IO) {
        for (reg in listOf(REGISTRY_OFFICIAL, REGISTRY_COMMUNITY)) {
            try {
                val parsed = AppUtils.parseJson<Registry>(app.get(reg, timeout = 10).text)
                val exts = parsed.extensions.orEmpty().filter { !it.downloadUrl.isNullOrBlank() }
                if (exts.isNotEmpty()) return@withContext exts
            } catch (e: Exception) {
                // registry mati — coba berikutnya
            }
        }
        emptyList()
    }

    /** Extension mana yang kita jalankan: SoundCloud (self-contained). */
    private fun pick(exts: List<RegistryExt>): RegistryExt? =
        exts.firstOrNull { it.id == "soundcloud" }

    suspend fun ensureLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (scope != null) return@withContext true
        loadMutex.withLock {
            if (scope != null) true
            else {
                val js = loadExtensionJs(context)
                if (js == null) false else initRuntime(js)
            }
        }
    }

    private suspend fun loadExtensionJs(context: Context): String? {
        val dir = File(context.cacheDir, "noir_spotiflac").apply { mkdirs() }
        val cache = File(dir, "soundcloud.index.js")
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < 24L * 3600L * 1000L
        if (fresh) return cache.readText()

        val url = pick(fetchRegistry())?.downloadUrl ?: SC_EXT_FALLBACK
        return try {
            val bytes = app.baseClient.newCall(Request.Builder().url(url).build())
                .execute().use { it.body?.bytes() } ?: return staleOrNull(cache)
            val js = unzipIndexJs(bytes) ?: return staleOrNull(cache)
            cache.writeText(js)
            js
        } catch (e: Exception) {
            logError(e)
            staleOrNull(cache)
        }
    }

    private fun staleOrNull(cache: File): String? =
        if (cache.exists()) cache.readText() else null

    private fun unzipIndexJs(bytes: ByteArray): String? {
        var js: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "index.js") {
                    js = zin.readBytes().toString(Charsets.UTF_8)
                }
                entry = zin.nextEntry
            }
        }
        return js
    }

    private fun initRuntime(js: String): Boolean {
        val cx = JsContext.enter()
        try {
            cx.optimizationLevel = -1
            val sc = cx.initStandardObjects()
            sc.put("__bridge", sc, JsContext.javaToJS(HttpBridge(), sc))
            cx.evaluateString(sc, BOOTSTRAP, "noir-spotiflac-bootstrap", 1, null)
            cx.evaluateString(sc, js, "spotiflac-soundcloud", 1, null)
            val ok = JsContext.toString(
                cx.evaluateString(sc, "String(!!__ext && __ext.initialize({}) === true)", "init", 1, null)
            ) == "true"
            if (ok) scope = sc
            return ok
        } catch (e: Exception) {
            logError(e)
            return false
        } finally {
            JsContext.exit()
        }
    }

    /** Cari track lewat extension resmi. */
    suspend fun search(query: String, limit: Int = 20): List<ScTrack> =
        withContext(Dispatchers.IO) {
            val sc = scope ?: return@withContext emptyList()
            synchronized(this@NoirSpotiflac) {
                val cx = JsContext.enter()
                try {
                    val json = JsContext.toString(
                        cx.evaluateString(
                            sc,
                            "JSON.stringify(__ext.searchTracks(${jsonQuote(query)}, $limit)||[])",
                            "search", 1, null
                        )
                    )
                    AppUtils.parseJson<List<ScTrack>>(json)
                } catch (e: Exception) {
                    logError(e)
                    emptyList()
                } finally {
                    JsContext.exit()
                }
            }
        }

    /** Ambil URL stream (HLS AAC diutamakan) untuk satu track. */
    suspend fun streamUrl(trackId: String): Pair<String, Boolean>? =
        withContext(Dispatchers.IO) {
            val sc = scope ?: return@withContext null
            synchronized(this@NoirSpotiflac) {
                val cx = JsContext.enter()
                try {
                    val res = JsContext.toString(
                        cx.evaluateString(
                            sc,
                            "(function(){var t=null;try{t=__ext.getTrack(${jsonQuote(trackId)});}catch(e){}" +
                                "if(!t)return JSON.stringify({err:'track'});" +
                                "var tr=(t.media&&t.media.transcodings)||[];var tc=null;" +
                                "for(var i=0;i<tr.length;i++){if(tr[i].format&&tr[i].format.protocol==='hls'){tc=tr[i];break;}}" +
                                "if(!tc&&tr.length)tc=tr[0];if(!tc)return JSON.stringify({err:'tc'});" +
                                "try{return JSON.stringify({url:resolveStreamURL(tc,t),hls:tc.format.protocol==='hls'});" +
                                "}catch(e){return JSON.stringify({err:String(e)});}})()",
                            "stream", 1, null
                        )
                    )
                    val p = AppUtils.parseJson<ScStream>(res)
                    if (p.url.isNullOrBlank()) null else p.url to (p.hls == true)
                } catch (e: Exception) {
                    logError(e)
                    null
                } finally {
                    JsContext.exit()
                }
            }
        }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    /** Bridge Java: http (OkHttp+Conscrypt, retry TLS) + log. */
    class HttpBridge {
        fun get(url: String, headersBlock: String): String {
            var code = -1
            var body = ""
            var lastErr = ""
            for (attempt in 0 until 4) {
                try {
                    val c = app.baseClient.newCall(
                        Request.Builder().url(url).apply {
                            for (line in headersBlock.split('\n')) {
                                val ix = line.indexOf(':')
                                if (ix > 0) header(
                                    line.substring(0, ix).trim(),
                                    line.substring(ix + 1).trim()
                                )
                            }
                        }.build()
                    ).execute()
                    c.use {
                        code = it.code
                        body = it.body?.string().orEmpty()
                    }
                    break
                } catch (e: Exception) {
                    lastErr = e.message.orEmpty()
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                }
            }
            if (code == -1) {
                return "{\"statusCode\":0,\"error\":${jsonQuote(lastErr)},\"body\":\"\"}"
            }
            return "{\"statusCode\":$code,\"body\":${jsonQuote(body)}}"
        }

        fun log(m: String) {
            android.util.Log.i("NoirSpotiflac", m)
        }
    }

    // Shim host API — permukaan sama persis dengan yang dipakai extension
    // resmi (log/storage/http/utils/registerExtension) + polyfill Web API
    // (URL & URLSearchParams) yang aslinya disediakan runtime goja.
    private const val BOOTSTRAP = """
function print(m){__bridge.log(String(m));}
var log={info:function(m){print('[I] '+m)},warn:function(m){print('[W] '+m)},error:function(m){print('[E] '+m)},debug:function(m){}};
var __store={};var storage={get:function(k){return __store[k]||null},set:function(k,v){__store[k]=v}};
var utils={randomUserAgent:function(){return 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'},isDownloadCancelled:function(){return false}};
var __ext=null;function registerExtension(o){__ext=o;}
function URLSearchParams(init){this._m=[];var s=init||'';if(s.charAt(0)==='?')s=s.substring(1);if(s){var parts=s.split('&');for(var i=0;i<parts.length;i++){var kv=parts[i].split('=');this._m.push([decodeURIComponent(kv[0]||''),decodeURIComponent((kv[1]||'').replace(/\+/g,'%20'))]);}}}
URLSearchParams.prototype.set=function(k,v){for(var i=0;i<this._m.length;i++){if(this._m[i][0]===k){this._m[i][1]=String(v);return;}}this._m.push([k,String(v)]);};
URLSearchParams.prototype.get=function(k){for(var i=0;i<this._m.length;i++){if(this._m[i][0]===k)return this._m[i][1];}return null;};
URLSearchParams.prototype.toString=function(){var out=[];for(var i=0;i<this._m.length;i++){out.push(encodeURIComponent(this._m[i][0])+'='+encodeURIComponent(this._m[i][1]));}return out.join('&');};
function URL(input,base){var s=String(input);if(base&&s.indexOf('://')<0){var b=String(base);if(s.charAt(0)==='/'){var m=b.match(/^(https?:\/\/[^/]+)/);s=(m?m[1]:'')+s;}else{s=b.replace(/[?#].*${'$'}/,'').replace(/\/[^/]*${'$'}/,'/')+s;}}
var m=s.match(/^(https?:)\/\/([^/?#]+)([^?#]*)?(\?[^#]*)?(#.*)?${'$'}/i);if(!m)throw new Error('Invalid URL: '+s);this.protocol=m[1].toLowerCase();this.host=m[2];this.pathname=m[3]||'/';this.search=m[4]||'';this.hash=m[5]||'';this.origin=this.protocol+'//'+this.host;}
URL.prototype.toString=function(){return this.protocol+'//'+this.host+this.pathname+this.search+this.hash;};
var http={get:function(url,headers){var h=headers||{};var lines=[];for(var k in h){if(Object.prototype.hasOwnProperty.call(h,k))lines.push(k+': '+h[k]);}var r=__bridge.get(String(url),lines.join(String.fromCharCode(10)));return JSON.parse(r);}};
"""
}
