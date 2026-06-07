package com.daedalus.echo.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isScoStarted = false

    fun start(outputFile: File, useBluetoothMic: Boolean) {
        if (useBluetoothMic) {
            try {
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    isScoStarted = true
                    Log.i("AudioRecorder", "Bluetooth SCO started")
                }
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Failed to start Bluetooth SCO", e)
            }
        }

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000) // 16kHz
            setAudioChannels(1)
            setAudioEncodingBitRate(64000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    fun stop() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recorder", e)
        } finally {
            recorder?.release()
            recorder = null
        }

        if (isScoStarted) {
            try {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                Log.i("AudioRecorder", "Bluetooth SCO stopped")
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error stopping Bluetooth SCO", e)
            }
            isScoStarted = false
        }
    }

    fun pause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.pause()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error pausing recorder", e)
            }
        }
    }

    fun resume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.resume()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error resuming recorder", e)
            }
        }
    }
}
