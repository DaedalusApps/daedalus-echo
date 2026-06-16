package com.daedalusapps.echo.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Transcription"
private const val TARGET_SAMPLE_RATE = 16000

/** Whisper's encoder operates on a fixed ~30s window; longer audio must be chunked. */
private const val CHUNK_DURATION_SECONDS = 30
private const val CHUNK_SAMPLES = CHUNK_DURATION_SECONDS * TARGET_SAMPLE_RATE

class TranscriptionService(private val context: Context) {

    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        if (!isWhisperReady(context)) return@withContext ""
        Log.i(TAG, "Using Whisper for ${audioFile.name}")
        transcribeWithWhisper(audioFile)
    }

    private fun transcribeWithWhisper(audioFile: File): String {
        val dir = whisperModelDir(context)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, WHISPER_ENCODER_FILE).absolutePath,
                    decoder = File(dir, WHISPER_DECODER_FILE).absolutePath,
                    language = "en",
                    task = "transcribe",
                ),
                tokens = File(dir, WHISPER_TOKENS_FILE).absolutePath,
                numThreads = 4,
            )
        )
        val recognizer = OfflineRecognizer(config = config)
        return try {
            val pcm = decodeToPcmFloat(audioFile)
            Log.i(TAG, "Decoded ${pcm.size} float samples, feeding to Whisper")

            val parts = mutableListOf<String>()
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK_SAMPLES, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples = chunk, sampleRate = TARGET_SAMPLE_RATE)
                recognizer.decode(stream)
                val chunkText = recognizer.getResult(stream).text.trim()
                stream.release()
                if (chunkText.isNotEmpty()) parts.add(chunkText)
                offset = end
            }

            val text = parts.joinToString(" ")
            Log.i(TAG, "Whisper complete: ${text.length} chars across ${parts.size} chunk(s)")
            text
        } finally {
            recognizer.release()
        }
    }

    private fun decodeToPcmFloat(file: File): FloatArray {
        val (buffer, size) = decodeToPcm(file)
        return FloatArray(size) { buffer[it] / 32768f }
    }

    // Returns the over-allocated backing buffer and the number of valid samples written.
    // Callers must use the returned size, not buffer.size.
    private fun decodeToPcm(file: File): Pair<ShortArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = fmt
                break
            }
        }
        check(trackIndex >= 0) { "No audio track found in ${file.name}" }

        extractor.selectTrack(trackIndex)
        val mime = format!!.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Use a primitive ShortArray buffer to avoid boxing (mutableListOf<Short> would allocate
        // ~24 bytes per sample as boxed objects — ~115 MB for a 5-min recording vs ~9.6 MB here).
        var pcmBuffer = ShortArray(TARGET_SAMPLE_RATE * 60) // initial capacity: 1 minute
        var pcmSize = 0
        val info = MediaCodec.BufferInfo()
        var sawEos = false

        while (!sawEos) {
            val inputIdx = codec.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) {
                val inputBuf = codec.getInputBuffer(inputIdx)!!
                val sampleSize = extractor.readSampleData(inputBuf, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sawEos = true
                } else {
                    codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outputIdx = codec.dequeueOutputBuffer(info, 10_000)
            while (outputIdx >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIdx)!!
                val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = ShortArray(shortBuf.remaining())
                shortBuf.get(samples)

                val mono = if (channelCount > 1) {
                    ShortArray(samples.size / channelCount) { i ->
                        var sum = 0L
                        for (ch in 0 until channelCount) sum += samples[i * channelCount + ch]
                        (sum / channelCount).toShort()
                    }
                } else samples

                val resampled = if (srcSampleRate != TARGET_SAMPLE_RATE) {
                    resample(mono, srcSampleRate, TARGET_SAMPLE_RATE)
                } else mono

                val needed = pcmSize + resampled.size
                if (needed > pcmBuffer.size) {
                    pcmBuffer = pcmBuffer.copyOf(maxOf(needed, pcmBuffer.size * 2))
                }
                resampled.copyInto(pcmBuffer, pcmSize)
                pcmSize += resampled.size

                codec.releaseOutputBuffer(outputIdx, false)
                outputIdx = codec.dequeueOutputBuffer(info, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        return Pair(pcmBuffer, pcmSize)
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val srcPos = i * ratio
            val lo = srcPos.toInt().coerceIn(0, input.size - 1)
            val hi = (lo + 1).coerceIn(0, input.size - 1)
            val frac = srcPos - lo
            ((input[lo] * (1 - frac) + input[hi] * frac).toInt().toShort())
        }
    }
}
