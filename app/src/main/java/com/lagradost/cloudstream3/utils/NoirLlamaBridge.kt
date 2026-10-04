package com.lagradost.cloudstream3.utils

/** Thin JNI surface for the CPU-only llama.cpp runtime used by Hy-MT2. */
object NoirLlamaBridge {
    init {
        System.loadLibrary("noir_llama")
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long

    /** Returns UTF-8 bytes so non-ASCII lyric text is preserved exactly. */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
    ): ByteArray

    external fun nativeResetCancel(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeFree(handle: Long)
}
