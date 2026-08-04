package com.daedalusapps.echo.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "SpeechService"
private const val UTTERANCE_ID = "daedalus_reply"

/** A selectable voice for the engine's current language, labeled in stable order. */
data class VoiceInfo(val id: String, val label: String)

/**
 * A voice is usable only if it doesn't require a network connection and its data is actually
 * installed on the device. Voices failing this (e.g. `en-us-x-star11-local` with data not
 * downloaded) synthesize nothing engine-side — no exception, just silence.
 */
fun isVoiceUsable(networkRequired: Boolean, features: Set<String>?): Boolean {
    if (networkRequired) return false
    if (features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) return false
    return true
}

private fun Voice.isUsable(): Boolean = isVoiceUsable(isNetworkConnectionRequired, features)

/** Thin seam over Android's audio focus APIs so TTS focus requests are unit-testable. */
interface AudioFocusManager {
    fun request()
    fun abandon()
}

/**
 * Requests transient "may duck" audio focus while a reply is spoken, so other apps' audio (e.g.
 * music) lowers in volume instead of being interrupted, and restores it once speech ends.
 */
class AndroidAudioFocusManager(context: Context) : AudioFocusManager {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    override fun request() {
        audioManager.requestAudioFocus(focusRequest)
    }

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

/**
 * Coordinates audio-focus request/abandon around a speaking session, extracted from
 * [AndroidSpeechService] so it's unit-testable without a real [TextToSpeech].
 *
 * Focus is requested on [onSpeakingChanged] going true (the engine's onStart), not before
 * `speak()` is called. On-device diagnosis found that requesting
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` immediately before `tts.speak()` made the system TTS
 * engine's own audio setup fail instantly on at least one device (synthesis dispatched then
 * onError/onDone within ~2ms, no exception thrown) — every spoken reply was silent. Requesting
 * focus only once the engine reports speech has actually started avoids racing the engine's own
 * AudioTrack setup. Do not move this back to a pre-speak request.
 *
 * Focus is abandoned at most once per request, regardless of how many times [onSpeakingChanged]
 * reports not-speaking (natural completion, stop(), error, shutdown() can all fire it).
 */
class SpeechFocusCoordinator(private val focusManager: AudioFocusManager) {

    @Volatile
    private var focusHeld = false

    fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            focusManager.request()
            focusHeld = true
            return
        }
        if (focusHeld) {
            focusManager.abandon()
            focusHeld = false
        }
    }
}

/**
 * Thin seam over [TextToSpeech] so callers (e.g. ConversationViewModel) are unit-testable with a
 * mock, and so TTS init/availability failures are exposed rather than thrown — callers fall back
 * to text-only silently.
 */
interface SpeechService {
    /** True once TTS has finished initializing successfully with a usable language. */
    val isAvailable: Boolean

    fun speak(text: String)
    fun stop()
    fun shutdown()

    /** Sets the speaking rate (1.0 = normal). */
    fun setSpeechRate(rate: Float)

    /** Voices available for the engine's current/default language only, "Voice 1"/"Voice 2"…. */
    fun availableVoices(): List<VoiceInfo>

    /**
     * Selects the voice with the given [VoiceInfo.id]; an empty id restores the engine's default
     * voice. Returns false if the id is unknown.
     */
    fun setVoice(id: String): Boolean

    /** Speaks [text] regardless of any caller-side conversation state — used for previews. */
    fun preview(text: String)

    /**
     * Registers a callback invoked with true when an utterance starts being spoken and false when
     * it stops. May be called from a non-main thread (TextToSpeech's UtteranceProgressListener
     * callbacks do not arrive on the main thread) — callers must not assume main-thread delivery.
     */
    fun setOnSpeakingChangedListener(listener: (Boolean) -> Unit)

    /**
     * Registers a callback invoked with true once the engine has finished initializing
     * successfully, or false if initialization failed. Also invoked immediately, with the
     * current known state, at registration time — init is asynchronous and may already have
     * finished before a caller registers, so registration alone must not miss that result. May
     * be called from a non-main thread, same as [setOnSpeakingChangedListener].
     */
    fun setOnReadyChangedListener(listener: (Boolean) -> Unit)
}

/** [SpeechService] backed by Android's built-in [TextToSpeech] engine. */
class AndroidSpeechService(
    context: Context,
    focusManager: AudioFocusManager = AndroidAudioFocusManager(context)
) : SpeechService {

    @Volatile
    override var isAvailable: Boolean = false
        private set

    // Volatile: written on the caller's thread (init/shutdown) but read from the engine's
    // utterance-progress callback thread by onError's voice-revert path.
    @Volatile
    private var tts: TextToSpeech? = null

    private val focusCoordinator = SpeechFocusCoordinator(focusManager)

    // The engine's voice as it was at init, so picking "system default" (an empty id) can put it
    // back — without it, deselecting a custom voice would leave the custom voice speaking until
    // the process restarted while the UI claimed the default was in use.
    @Volatile
    private var defaultVoice: Voice? = null

    // Rate/voice requested before the engine finished initializing; applied once it has. Volatile
    // because they are written by the caller and read from the engine's init callback.
    @Volatile
    private var pendingRate: Float? = null

    @Volatile
    private var pendingVoiceId: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed with status $status")
                setReady(false)
                return@TextToSpeech
            }
            val result = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            isAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isAvailable) {
                Log.w(TAG, "TextToSpeech language unavailable: $result")
                setReady(false)
                return@TextToSpeech
            }
            defaultVoice = tts?.voice ?: tts?.defaultVoice
            pendingRate?.let { tts?.setSpeechRate(it) }
            pendingVoiceId?.let { applyVoice(it) }
            setReady(true)
        }
        // Callbacks below arrive on a non-main thread (TextToSpeech's internal worker), which is
        // why setOnSpeakingChangedListener is documented as not main-thread-bound.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = setSpeaking(true)
            override fun onDone(utteranceId: String?) = setSpeaking(false)
            // Runtime fallback: a persisted voice can still fail engine-side (e.g. its data was
            // uninstalled after selection). Revert to the default voice so the *next* utterance
            // is audible — the failed one is not retried, and the pref is left as-is.
            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                val engine = tts
                val default = defaultVoice
                if (engine != null && default != null && engine.voice != default) {
                    engine.voice = default
                }
                setSpeaking(false)
            }
        })
    }

    private fun setSpeaking(speaking: Boolean) {
        focusCoordinator.onSpeakingChanged(speaking)
        speakingChangedListener?.invoke(speaking)
    }

    override fun speak(text: String) {
        if (!isAvailable) return
        tts?.speak(text, QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun setSpeechRate(rate: Float) {
        if (isAvailable) tts?.setSpeechRate(rate) else pendingRate = rate
    }

    override fun availableVoices(): List<VoiceInfo> {
        val engine = tts
        if (engine == null || !isAvailable) return emptyList()
        val voices = engine.voices ?: return emptyList()
        val language = engine.voice?.locale ?: engine.defaultVoice?.locale ?: Locale.getDefault()
        return voices
            .filter { it.locale == language && it.isUsable() }
            .map { it.name }
            .sorted()
            .mapIndexed { index, name -> VoiceInfo(id = name, label = "Voice ${index + 1}") }
    }

    override fun setVoice(id: String): Boolean {
        if (tts == null || !isAvailable) {
            pendingVoiceId = id
            return true
        }
        return applyVoice(id)
    }

    /**
     * Applies [id] to a live engine; an empty id restores the voice captured at init. If [id]
     * resolves to a voice whose data isn't usable (e.g. not installed on-device), falls back to
     * the default voice so the engine keeps speaking instead of going silent, and returns false —
     * self-heals devices already stuck with a bad persisted voice pref.
     */
    private fun applyVoice(id: String): Boolean {
        val engine = tts ?: return false
        if (id.isEmpty()) {
            engine.voice = defaultVoice ?: return false
            return true
        }
        val resolved = engine.voices?.firstOrNull { it.name == id }
        if (resolved == null || !resolved.isUsable()) {
            // ?.let, not a bare assignment: TextToSpeech.setVoice(null) dereferences its argument
            // and throws, and defaultVoice is null when the engine reported no voice at init.
            defaultVoice?.let { engine.voice = it }
            return false
        }
        engine.voice = resolved
        return true
    }

    override fun preview(text: String) = speak(text)

    @Volatile
    private var speakingChangedListener: ((Boolean) -> Unit)? = null

    override fun setOnSpeakingChangedListener(listener: (Boolean) -> Unit) {
        speakingChangedListener = listener
    }

    // null until init finishes (success or failure); once known, a listener registered late must
    // still learn the outcome rather than waiting on a callback that already happened.
    @Volatile
    private var readyState: Boolean? = null

    @Volatile
    private var readyChangedListener: ((Boolean) -> Unit)? = null

    private fun setReady(ready: Boolean) {
        readyState = ready
        readyChangedListener?.invoke(ready)
    }

    override fun setOnReadyChangedListener(listener: (Boolean) -> Unit) {
        readyChangedListener = listener
        readyState?.let { listener(it) }
    }

    override fun stop() {
        tts?.stop()
        // stop() flushes the utterance; onDone/onError delivery after a flush isn't guaranteed,
        // so the not-speaking signal is emitted here rather than waiting on the progress listener.
        setSpeaking(false)
    }

    // stop() first: shutdown() releases the engine binding but does not reliably cut off an
    // utterance already handed to it. Dropping the reference makes a second shutdown — or a
    // stop()/speak() arriving after it — a no-op instead of a call into a dead engine.
    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isAvailable = false
        setSpeaking(false)
    }
}
