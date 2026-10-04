package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fully local lyric translation backed by Tencent Hy-MT2 1.8B Q4_K_M.
 *
 * The model is downloaded only after the user asks for translation, stored in
 * private app storage, pinned to an immutable Hugging Face revision and checked
 * against the published SHA-256. Translation prompts are passed only to the
 * local llama.cpp JNI runtime; this class has no translation-service client.
 */
internal object NoirOfflineLyricsTranslator {
    private const val MODEL_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
    private const val MODEL_REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
    private const val MODEL_URL =
        "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/" +
            MODEL_REVISION + "/" + MODEL_NAME
    private const val MODEL_BYTES = 1_133_080_448L
    private const val MODEL_SHA256 =
        "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
    private const val CONTEXT_SIZE = 4096
    private const val MAX_LINES_PER_BLOCK = 12
    private const val MAX_SOURCE_CHARS_PER_BLOCK = 1500
    private const val IDLE_UNLOAD_MS = 4 * 60 * 1000L

    private val engineMutex = Mutex()
    private val downloadMutex = Mutex()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleUnloadJob: Job? = null

    @Volatile
    private var engineHandle: Long = 0L

    @Volatile
    private var generationRunning: Boolean = false

    private val downloadClient by lazy {
        app.baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    enum class Readiness {
        READY,
        MISSING_MODEL,
        UNSUPPORTED_ABI,
        LOW_MEMORY,
    }

    class InsufficientStorageException : IOException("Not enough free storage for Hy-MT2")
    class LowMemoryException(cause: Throwable? = null) : IOException("Not enough memory for Hy-MT2", cause)

    fun readiness(context: Context): Readiness {
        if (!Process.is64Bit() || !Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }) {
            return Readiness.UNSUPPORTED_ABI
        }

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return Readiness.LOW_MEMORY
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val isLowMemoryDevice = activityManager.isLowRamDevice ||
            memory.totalMem < 3_500_000_000L ||
            (engineHandle == 0L && memory.availMem < 1_800_000_000L)
        if (isLowMemoryDevice) return Readiness.LOW_MEMORY

        return if (isModelInstalled(context)) Readiness.READY else Readiness.MISSING_MODEL
    }

    fun isModelInstalled(context: Context): Boolean {
        val model = modelFile(context)
        return model.isFile && model.length() == MODEL_BYTES
    }

    /** Download can be resumed after cancellation; no model bytes enter the APK. */
    suspend fun downloadModel(
        context: Context,
        onProgress: (receivedBytes: Long, totalBytes: Long, verifying: Boolean) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val appContext = context.applicationContext
            when (readiness(appContext)) {
                Readiness.UNSUPPORTED_ABI -> throw IOException("This device does not have a supported 64-bit CPU")
                Readiness.LOW_MEMORY -> throw LowMemoryException()
                Readiness.READY -> return@withLock
                Readiness.MISSING_MODEL -> Unit
            }

            val dir = modelDirectory(appContext)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create private model storage")

            val finalFile = File(dir, MODEL_NAME)
            val partialFile = File(dir, "$MODEL_NAME.part")
            if (finalFile.exists() && finalFile.length() != MODEL_BYTES) {
                finalFile.delete()
            }
            if (partialFile.exists() && partialFile.length() > MODEL_BYTES) {
                partialFile.delete()
            }
            if (finalFile.isFile && finalFile.length() == MODEL_BYTES) return@withLock

            val remaining = (MODEL_BYTES - partialFile.length()).coerceAtLeast(0L)
            val freeBytes = StatFs(dir.absolutePath).availableBytes
            if (remaining > 0L && freeBytes < remaining + 32L * 1024 * 1024) {
                throw InsufficientStorageException()
            }

            onProgress(partialFile.length(), MODEL_BYTES, false)
            if (remaining > 0L) transferToPartialFile(partialFile, onProgress)
            if (partialFile.length() != MODEL_BYTES) {
                throw IOException("The model download is incomplete; it can be resumed on the next attempt")
            }

            onProgress(MODEL_BYTES, MODEL_BYTES, true)
            val actualHash = sha256(partialFile)
            if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                partialFile.delete()
                throw IOException("Hy-MT2 integrity check failed; the incomplete model was removed")
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("Could not replace the previous model file")
            }
            if (!partialFile.renameTo(finalFile)) {
                throw IOException("Could not move the verified model into private storage")
            }
        }
    }

    suspend fun translate(
        context: Context,
        lines: List<String>,
        target: String,
        onProgress: (completedLines: Int, totalLines: Int) -> Unit = { _, _ -> },
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val appContext = context.applicationContext
        if (!isModelInstalled(appContext)) return@withContext null
        if (readiness(appContext) == Readiness.UNSUPPORTED_ABI || readiness(appContext) == Readiness.LOW_MEMORY) {
            return@withContext null
        }

        val targetName = languageName(target)
        val output = lines.toMutableList()
        val translatable = lines.mapIndexedNotNull { index, text ->
            val trimmed = text.trim()
            if (trimmed.isBlank() || isStageDirection(trimmed)) null
            else LineEntry(index, text)
        }
        if (translatable.isEmpty()) return@withContext output

        engineMutex.withLock {
            idleUnloadJob?.cancel()
            try {
                val handle = ensureEngineLoaded(appContext)
                var blockStart = 0
                while (blockStart < translatable.size) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val block = ArrayList<LineEntry>(MAX_LINES_PER_BLOCK)
                    var chars = 0
                    while (blockStart + block.size < translatable.size && block.size < MAX_LINES_PER_BLOCK) {
                        val candidate = translatable[blockStart + block.size]
                        if (block.isNotEmpty() && chars + candidate.text.length > MAX_SOURCE_CHARS_PER_BLOCK) break
                        block.add(candidate)
                        chars += candidate.text.length
                    }
                    if (block.isEmpty()) return@withLock null

                    val before = translatable.take(blockStart).takeLast(3).map { entry ->
                        entry to output[entry.index]
                    }
                    val after = translatable.drop(blockStart + block.size).take(3)
                    val prompt = buildBatchPrompt(targetName, block, before, after)
                    val budget = (128 + block.size * 56).coerceIn(256, 1024)
                    val batchOutput = generate(handle, prompt, budget)
                    val parsed = parseMarkedRows(batchOutput)

                    val missing = block.filter { parsed[it.index].isNullOrBlank() }
                    for (entry in block) {
                        val translated = parsed[entry.index]?.trim().takeUnless { it.isNullOrBlank() }
                        if (translated != null) output[entry.index] = translated
                    }

                    // Markers make alignment deterministic. If a model response
                    // drops a marker, retry only that row with neighboring context;
                    // never shift subsequent lines to fill the gap.
                    for (entry in missing) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val translated = translateOne(
                            handle = handle,
                            targetName = targetName,
                            entry = entry,
                            previous = before,
                            following = after,
                        ) ?: return@withLock null
                        output[entry.index] = translated
                    }

                    blockStart += block.size
                    onProgress(blockStart, translatable.size)
                }
                output
            } finally {
                scheduleIdleUnload()
            }
        }
    }

    /** Cancel an in-flight native token loop; safe to call from the UI thread. */
    fun cancelCurrentGeneration() {
        val handle = engineHandle
        if (generationRunning && handle != 0L) {
            runCatching { NoirLlamaBridge.nativeCancel(handle) }
        }
    }

    private fun ensureEngineLoaded(context: Context): Long {
        val existing = engineHandle
        if (existing != 0L) return existing

        val file = modelFile(context)
        if (!file.isFile || file.length() != MODEL_BYTES) {
            throw IOException("The verified Hy-MT2 model is not installed")
        }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val handle = try {
            NoirLlamaBridge.nativeLoadModel(file.absolutePath, CONTEXT_SIZE, threads)
        } catch (e: LinkageError) {
            throw IOException("The local AI runtime is unavailable for this Android build", e)
        } catch (e: OutOfMemoryError) {
            throw LowMemoryException(e)
        }
        if (handle == 0L) throw IOException("Hy-MT2 could not be loaded")
        engineHandle = handle
        return handle
    }

    private suspend fun generate(handle: Long, prompt: String, maxTokens: Int): String {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        generationRunning = true
        try {
            NoirLlamaBridge.nativeResetCancel(handle)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val bytes = NoirLlamaBridge.nativeGenerate(
                handle = handle,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = 0.7f,
                topP = 0.8f,
                topK = 20,
                repeatPenalty = 1.05f,
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return bytes.toString(Charsets.UTF_8)
        } catch (e: LinkageError) {
            throw IOException("The local AI runtime is unavailable for this Android build", e)
        } catch (e: OutOfMemoryError) {
            throw LowMemoryException(e)
        } finally {
            generationRunning = false
        }
    }

    private suspend fun translateOne(
        handle: Long,
        targetName: String,
        entry: LineEntry,
        previous: List<Pair<LineEntry, String>>,
        following: List<LineEntry>,
    ): String? {
        val prompt = buildString {
            append("Translate this single song-lyric line into ")
            append(targetName)
            append(". Make it natural and faithful, preserving tone and context; do not add explanation. ")
            append("Return exactly one line as ⟦L")
            append(entry.index.toString().padStart(4, '0'))
            append("⟧ followed by its translation.\n")
            if (previous.isNotEmpty()) {
                append("Previous context (do not repeat):\n")
                previous.forEach { (old, translated) ->
                    append("Source: ").append(safePromptText(old.text))
                        .append(" | ").append(targetName).append(" translation: ").append(safePromptText(translated)).append('\n')
                }
            }
            if (following.isNotEmpty()) {
                append("Upcoming source context (do not output):\n")
                following.forEach { append("- ").append(safePromptText(it.text)).append('\n') }
            }
            append("Current line:\n⟦L")
            append(entry.index.toString().padStart(4, '0'))
            append("⟧ ").append(safePromptText(entry.text))
        }
        val raw = generate(handle, formatAsHyMt2UserPrompt(prompt), 192)
        val marked = parseMarkedRows(raw)[entry.index]?.trim()
        if (!marked.isNullOrBlank()) return marked

        // A single-row request can be safely recovered without positional
        // ambiguity if the model omitted only the marker.
        val plain = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Translate this single", ignoreCase = true) }
            .filterNot { it.startsWith("Source:", ignoreCase = true) }
            .lastOrNull()
            ?.replace(Regex("^⟦L\\d{4}⟧\\s*"), "")
            ?.trim()
        return plain?.takeIf { it.isNotBlank() && it.length <= 1200 }
    }

    private fun buildBatchPrompt(
        targetName: String,
        current: List<LineEntry>,
        previous: List<Pair<LineEntry, String>>,
        following: List<LineEntry>,
    ): String = buildString {
        append("Translate these song lyrics into ").append(targetName).append(".\n")
        append("Write fluent, natural lyrics in ").append(targetName)
            .append("; preserve the intended meaning, emotional tone, point of view, names, and recurring phrasing across lines. ")
            .append("Translate idioms and metaphors by meaning, not mechanically. Do not invent details, force rhymes, or add commentary.\n")
        append("Return only the current lines, exactly once each, in the same order. Keep every ID unchanged and use exactly this format: ⟦L0000⟧ translated line. ")
            .append("Never omit, merge, split, duplicate, reorder, translate, or renumber IDs. Keep sung non-lexical sounds as they are.\n")

        if (previous.isNotEmpty()) {
            append("Previous approved context (use for consistency; do not output again):\n")
            previous.forEach { (entry, translated) ->
                append("Source: ").append(safePromptText(entry.text))
                    .append(" | Translation: ").append(safePromptText(translated)).append('\n')
            }
        }
        if (following.isNotEmpty()) {
            append("Upcoming source context (do not output these lines):\n")
            following.forEach { append("- ").append(safePromptText(it.text)).append('\n') }
        }
        append("Current lines:\n")
        current.forEach { entry ->
            append("⟦L").append(entry.index.toString().padStart(4, '0')).append("⟧ ")
                .append(safePromptText(entry.text)).append('\n')
        }
        append("Output only the marked translations.")
    }

    private fun formatAsHyMt2UserPrompt(text: String): String = text

    private fun parseMarkedRows(text: String): Map<Int, String> {
        val answerStart = text.lastIndexOf("<answer>", ignoreCase = true)
        val answerText = if (answerStart >= 0) {
            text.substring(answerStart + "<answer>".length).substringBefore("</answer>", missingDelimiterValue = "")
        } else {
            Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .replace(text, "")
        }
        val marker = Regex("^\\s*⟦L(\\d{4})⟧(?:[ \\t:：-]?)(.*)$")
        val result = LinkedHashMap<Int, String>()
        var activeId: Int? = null
        var activeText = StringBuilder()

        fun flush() {
            val id = activeId ?: return
            result[id] = activeText.toString().trim()
            activeId = null
            activeText = StringBuilder()
        }

        answerText.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val match = marker.matchEntire(line)
            if (match != null) {
                flush()
                activeId = match.groupValues[1].toIntOrNull()
                activeText.append(match.groupValues[2].trim())
            } else if (activeId != null && line.isNotBlank()) {
                if (activeText.isNotEmpty()) activeText.append(' ')
                activeText.append(line.trim())
            }
        }
        flush()
        return result
    }

    private fun safePromptText(text: String): String = text
        .replace("⟦", "‹")
        .replace("⟧", "›")
        .replace("<｜", "‹｜")
        .replace("｜>", "｜›")
        .replace("<think>", "‹think›", ignoreCase = true)
        .replace("</think>", "‹/think›", ignoreCase = true)
        .replace("<answer>", "‹answer›", ignoreCase = true)
        .replace("</answer>", "‹/answer›", ignoreCase = true)

    private fun isStageDirection(text: String): Boolean =
        Regex(
            "^\\[(?:verse|chorus|pre[- ]?chorus|post[- ]?chorus|bridge|intro|outro|instrumental|interlude|refrain|hook|solo|spoken|ad[- ]?lib|rap)(?:\\s+[^]]*)?]$",
            RegexOption.IGNORE_CASE,
        ).matches(text)

    private fun languageName(target: String): String = when (target.trim().lowercase(Locale.ROOT)) {
        "id", "in", "ind", "indonesian" -> "Indonesian"
        "en", "eng", "english" -> "English"
        "zh", "zh-cn", "chinese", "中文" -> "Chinese"
        "zh-hant", "traditional chinese" -> "Traditional Chinese"
        "ja", "japanese" -> "Japanese"
        "ko", "korean" -> "Korean"
        "ms", "malay" -> "Malay"
        "tl", "filipino" -> "Filipino"
        "vi", "vietnamese" -> "Vietnamese"
        "th", "thai" -> "Thai"
        "es", "spanish" -> "Spanish"
        "fr", "french" -> "French"
        "pt", "portuguese" -> "Portuguese"
        "de", "german" -> "German"
        "it", "italian" -> "Italian"
        "ru", "russian" -> "Russian"
        "ar", "arabic" -> "Arabic"
        "hi", "hindi" -> "Hindi"
        "tr", "turkish" -> "Turkish"
        "fa", "persian" -> "Persian"
        "he", "hebrew" -> "Hebrew"
        "uk", "ukrainian" -> "Ukrainian"
        "nl", "dutch" -> "Dutch"
        "pl", "polish" -> "Polish"
        "cs", "czech" -> "Czech"
        "bn", "bengali" -> "Bengali"
        "ta", "tamil" -> "Tamil"
        "te", "telugu" -> "Telugu"
        "mr", "marathi" -> "Marathi"
        "ur", "urdu" -> "Urdu"
        "km", "khmer" -> "Khmer"
        "my", "burmese" -> "Burmese"
        "gu", "gujarati" -> "Gujarati"
        "bo", "tibetan" -> "Tibetan"
        "kk", "kazakh" -> "Kazakh"
        "mn", "mongolian" -> "Mongolian"
        "ug", "uyghur" -> "Uyghur"
        "yue", "cantonese" -> "Cantonese"
        else -> target.trim().ifBlank { "Indonesian" }
    }

    private fun modelDirectory(context: Context): File =
        File(context.noBackupFilesDir, "noir_models")

    private fun modelFile(context: Context): File =
        File(modelDirectory(context), MODEL_NAME)

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        val scheduledHandle = engineHandle
        if (scheduledHandle == 0L) return
        idleUnloadJob = processScope.launch {
            delay(IDLE_UNLOAD_MS)
            engineMutex.withLock {
                if (engineHandle == scheduledHandle && !generationRunning) {
                    releaseEngine()
                }
            }
        }
    }

    private fun releaseEngine() {
        val oldHandle = engineHandle
        engineHandle = 0L
        if (oldHandle != 0L) runCatching { NoirLlamaBridge.nativeFree(oldHandle) }
    }

    private suspend fun transferToPartialFile(
        partialFile: File,
        onProgress: (receivedBytes: Long, totalBytes: Long, verifying: Boolean) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val resumeAt = partialFile.length().coerceAtMost(MODEL_BYTES)
        val request = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "Noir-Local-Translation/1.0")
            .apply { if (resumeAt > 0L) header("Range", "bytes=$resumeAt-") }
            .get()
            .build()
        val call = downloadClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { res ->
                        if (res.code != 200 && res.code != 206) {
                            throw IOException("Hy-MT2 download returned HTTP ${res.code}")
                        }
                        val body = res.body ?: throw IOException("Hy-MT2 download returned no data")
                        val append = if (res.code == 206) {
                            val range = res.header("Content-Range")
                            val start = range?.let { Regex("^bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                            if (start != resumeAt) throw IOException("The model server returned an unexpected byte range")
                            true
                        } else {
                            // Some mirrors ignore Range. Restart cleanly rather than append a full file twice.
                            false
                        }
                        val initial = if (append) resumeAt else 0L
                        var received = initial
                        var lastReportAt = 0L
                        onProgress(received, MODEL_BYTES, false)

                        body.byteStream().use { input ->
                            FileOutputStream(partialFile, append).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var count: Int
                                while (input.read(buffer).also { count = it } >= 0) {
                                    if (!continuation.isActive) throw CancellationException("Model download cancelled")
                                    if (count == 0) continue
                                    output.write(buffer, 0, count)
                                    received += count
                                    val now = System.nanoTime()
                                    if (now - lastReportAt >= 300_000_000L) {
                                        onProgress(received.coerceAtMost(MODEL_BYTES), MODEL_BYTES, false)
                                        lastReportAt = now
                                    }
                                    if (received > MODEL_BYTES) {
                                        throw IOException("The model server sent more data than expected")
                                    }
                                }
                                output.flush()
                                output.fd.sync()
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                } catch (e: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class LineEntry(val index: Int, val text: String)
}
