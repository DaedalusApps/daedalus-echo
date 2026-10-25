package com.daedalusapps.echo.ai

import android.content.Context
import java.io.File

data class LocalModel(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val filename: String,
    val sizeBytes: Long,
    val useGpu: Boolean = false
)

val GEMMA3_1B = LocalModel(
    id          = "gemma3_1b",
    displayName = "Gemma 3 1B",
    description = "~555 MB · Newer architecture · Better instruction following",
    downloadUrl = "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma3-1B-it-int4.task",
    filename    = "gemma3-1B-it-int4.task",
    sizeBytes   = 555_000_000L,
    useGpu      = false
)

/**
 * Preferred meeting-summary model. It uses LiteRT-LM rather than the retired
 * MediaPipe task format. The Gemma licence must be accepted before downloading
 * the file; it is imported deliberately through Settings.
 */
const val GEMMA4_E2B_FILE = "gemma-4-E2B-it.litertlm"
const val GEMMA4_E2B_PREF = "use_gemma4_e2b"
const val GEMMA4_E2B_MIN_BYTES = 2_300_000_000L

fun gemma4E2bFile(context: Context): File =
    File(modelsDir(context), GEMMA4_E2B_FILE)

fun useGemma4E2b(context: Context): Boolean =
    context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        .getBoolean(GEMMA4_E2B_PREF, false) && gemma4E2bFile(context).length() >= GEMMA4_E2B_MIN_BYTES

/**
 * Parakeet TDT v3 is the Italian-first transcription engine.  It is a
 * multilingual NeMo transducer exported to int8 ONNX by sherpa-onnx, so all
 * inference remains on the phone.  The archive is deliberately downloaded
 * only on demand: it is much larger than the former English-only Whisper
 * base model, but gives materially better Italian and mixed-European-language
 * coverage on a Galaxy S25-class device.
 */
const val PARAKEET_MODEL_ID = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
const val PARAKEET_ENCODER_FILE = "encoder.int8.onnx"
const val PARAKEET_DECODER_FILE = "decoder.int8.onnx"
const val PARAKEET_JOINER_FILE = "joiner.int8.onnx"
const val PARAKEET_TOKENS_FILE = "tokens.txt"
// Created only after all model files have been written completely.
const val PARAKEET_READY_MARKER = ".install-complete"
// Individual files avoid archive extraction on the phone. Each is verified by SHA-256.
const val PARAKEET_MODEL_URL_BASE =
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
const val PARAKEET_TOTAL_BYTES = 670_478_772L

// Kept only for source compatibility with the deprecated downloader. New flows never call it.
@Deprecated("Use Parakeet") const val WHISPER_ENCODER_FILE = "base.en-encoder.int8.onnx"
@Deprecated("Use Parakeet") const val WHISPER_DECODER_FILE = "base.en-decoder.int8.onnx"
@Deprecated("Use Parakeet") const val WHISPER_TOKENS_FILE = "base.en-tokens.txt"
@Deprecated("Use Parakeet") const val WHISPER_ENCODER_URL = ""
@Deprecated("Use Parakeet") const val WHISPER_DECODER_URL = ""
@Deprecated("Use Parakeet") const val WHISPER_TOKENS_URL = ""
@Deprecated("Use Parakeet") const val WHISPER_TOTAL_BYTES = 0L

// Universal Sentence Encoder Lite — used by EmbeddingService for semantic note search
const val EMBEDDING_MODEL_FILE = "universal_sentence_encoder.tflite"
const val EMBEDDING_MODEL_SIZE_BYTES = 26_000_000L  // ~26 MB
const val EMBEDDING_MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

fun embeddingModelFile(context: Context): File =
    File(modelsDir(context), EMBEDDING_MODEL_FILE)

fun modelsDir(context: Context): File =
    File(context.filesDir, "models").also { it.mkdirs() }

fun parakeetModelDir(context: Context): File =
    File(modelsDir(context), PARAKEET_MODEL_ID)

@Deprecated("Use parakeetModelDir")
fun whisperModelDir(context: Context): File = File(modelsDir(context), "deprecated-whisper")

fun isParakeetReady(context: Context): Boolean {
    val dir = parakeetModelDir(context)
    return File(dir, PARAKEET_READY_MARKER).isFile &&
        File(dir, PARAKEET_ENCODER_FILE).isFile &&
        File(dir, PARAKEET_DECODER_FILE).isFile &&
        File(dir, PARAKEET_JOINER_FILE).isFile &&
        File(dir, PARAKEET_TOKENS_FILE).isFile
}

fun modelFile(context: Context): File =
    File(modelsDir(context), GEMMA3_1B.filename)

/** Either supported local summary engine satisfies first-run setup. */
fun hasSummaryModel(context: Context): Boolean =
    modelFile(context).isFile || gemma4E2bFile(context).length() >= GEMMA4_E2B_MIN_BYTES

/**
 * First-run setup provisions speech recognition and semantic search only.
 * Gemma 4 is imported deliberately from Settings, so it is never downloaded implicitly.
 */
fun areAllModelsReady(context: Context): Boolean =
    isParakeetReady(context) && embeddingModelFile(context).exists()

fun selectedModel(context: Context): LocalModel = GEMMA3_1B
