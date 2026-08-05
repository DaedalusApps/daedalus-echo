package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalusapps.echo.ai.AndroidSpeechService
import com.daedalusapps.echo.ai.ChatTurn
import com.daedalusapps.echo.ai.EmbeddingService
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.OFFLINE_GUARDRAIL
import com.daedalusapps.echo.ai.Role
import com.daedalusapps.echo.ai.SpeechService
import com.daedalusapps.echo.ai.TranscriptionService
import com.daedalusapps.echo.ai.VoiceInfo
import com.daedalusapps.echo.ai.aiTextBudget
import com.daedalusapps.echo.ai.analyzeTranscript
import com.daedalusapps.echo.ai.expandWithTopicSiblings
import com.daedalusapps.echo.ai.isWhisperReady
import com.daedalusapps.echo.ai.sourceText
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.model.DateUtils
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.recording.AudioRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A single chat turn in a conversation session. */
data class ChatMessage(val role: Role, val text: String, val timestampMillis: Long)

/** Whether "New conversation" should be enabled: there is something to rotate away from, and no generation in flight. */
fun canStartNewSession(messages: List<ChatMessage>, isGenerating: Boolean): Boolean =
    messages.isNotEmpty() && !isGenerating

/** Visual/interaction state of the single morphing center button on the voice-only instant-send
 *  surface (#29 / ED.4). */
enum class VoiceButtonState { IDLE, RECORDING, TRANSCRIBING, GENERATING }

/** Derives [VoiceButtonState] with precedence RECORDING > TRANSCRIBING > GENERATING > IDLE.
 *  Recording wins even if [isGenerating] flips true mid-recording (e.g. via endSession) — the
 *  user must always be able to stop a recording they started (mic-hostage lesson). */
fun voiceButtonState(isRecordingVoice: Boolean, isTranscribing: Boolean, isGenerating: Boolean): VoiceButtonState =
    when {
        isRecordingVoice -> VoiceButtonState.RECORDING
        isTranscribing -> VoiceButtonState.TRANSCRIBING
        isGenerating -> VoiceButtonState.GENERATING
        else -> VoiceButtonState.IDLE
    }

// `internal` (rather than `private`) so ai/OfflineGuardrailTest.kt can assert the guardrail
// appears exactly once, mirroring the other prompt-guardrail tests there.
internal const val IDEATION_SYSTEM_PROMPT = "You are a thoughtful ideation partner in a live " +
    "conversation with the user, like a working session with a colleague. Be concise, help " +
    "develop their thinking, and ask good clarifying follow-up questions rather than lecturing." +
    "\n\n" + OFFLINE_GUARDRAIL

// `internal` (rather than `private`) so ai/OfflineGuardrailTest.kt can assert the guardrail
// appears exactly once, mirroring IDEATION_SYSTEM_PROMPT above.
internal const val SUMMARY_PROMPT = "Summarize this conversation so far as a concise rolling " +
    "summary, capturing key decisions, ideas, and open threads. Write it as prose (no bullet " +
    "points) so it can be folded into a system prompt. Keep it under 200 words.\n\n" +
    OFFLINE_GUARDRAIL

// aiTextBudget() already derives its char budget from the model's 4096-token context minus
// prompt/output headroom (see AI_TEXT_BUDGET_DEFAULT in Categories.kt). Conversation turns
// accumulate without bound, so reuse that budget rather than a fresh token calculation, but keep
// only a fraction of it as extra safety margin beyond the reply headroom already baked in.
private const val CONVERSATION_CONTEXT_FRACTION = 0.75

// Fraction of contextBudgetChars reserved for retrieved note context (#56).
private const val NOTE_CONTEXT_FRACTION = 0.3

private const val NOTE_RETRIEVAL_TOP_K = 3

// Minimum cosine similarity for a retrieved note to be injected (#56). Below this a note is
// unrelated to the turn and injecting it derails the small model — conversation mode injects
// notes silently, unlike Ask Library where the user explicitly asked a library question, so it
// must fail closed to "no notes" rather than surface a barely-related one.
private const val NOTE_RELEVANCE_MIN_SCORE = 0.4f

// Keeps the last two exchanges (user + model turns) intact in the live context on rollover.
private const val TAIL_MESSAGE_COUNT = 4

// Hard ceiling on the injected summary, as a fraction of the context budget. The model is asked
// for a short summary but its output length is not guaranteed, and each rollover feeds the
// previous summary back in, so without a clamp a compounding summary could grow the real sent
// context past the budget the trip-check assumes.
private const val SUMMARY_BUDGET_FRACTION = 0.25

/** SharedPreferences key for whether spoken replies (Android TTS) are on in conversation mode. */
const val CONVERSATION_TTS_ENABLED_KEY = "conversation_tts_enabled"

/** SharedPreferences key for the spoken-reply rate (Float, default 1.0). */
const val CONVERSATION_TTS_RATE_KEY = "conversation_tts_rate"

/** SharedPreferences key for the selected voice id (String, default "" = system default). */
const val CONVERSATION_TTS_VOICE_KEY = "conversation_tts_voice"

/** SharedPreferences key for whether a voice transcription is sent immediately (Boolean, default false). */
const val CONVERSATION_INSTANT_SEND_KEY = "conversation_instant_send"

/** SharedPreferences key for the hands-free auto-listen loop (Boolean, default false). */
const val CONVERSATION_AUTO_LISTEN_KEY = "conversation_auto_listen"

private const val TTS_RATE_DEFAULT = 1.0f
private const val TTS_VOICE_DEFAULT = ""

private val SESSION_FILENAME_REGEX = Regex("""conv_(\d{14})\.md""")
private val TURN_HEADER_REGEX = Regex("""^\*\*(Me|Agent)\*\* \((\d{2}):(\d{2})\):$""")

/**
 * Text-only conversation core (#20 / EB.3) plus the rolling-summary live-context cap (#21 /
 * EB.4): persistent per-day session files, single-shot send guard, consecutive-role merging so
 * a failed send never bricks the session, and a bounded live context so long conversations don't
 * grow the prompt without limit (see [buildLiveContext]).
 *
 * Also includes ending a session into an analyzed library note (EB.5): endSession() saves the
 * transcript as a Recording, runs it through the same analysis pipeline a transcribed local
 * recording gets, marks the session file as ended, and rotates to a fresh session.
 *
 * Also includes on-device spoken replies (#24 / EC.2): a lazily-built [SpeechService] speaks a
 * MODEL reply when [ttsEnabled] is on, never binding the engine for a user who leaves it off (see
 * [ttsDelegate]).
 *
 * Also includes the voice/rate picker (#25 / EC.3): [setTtsRate]/[setTtsVoice] persist and apply
 * a speaking rate and voice id, previewing the change while spoken replies are on. The persisted
 * values are applied at the single point the engine is (lazily) built, so a warm engine always
 * starts configured the way the user last left it — see [ttsDelegate].
 *
 * Also includes instant send after voice transcription (#26 / ED.1): when [instantSend] is on, a
 * successful non-blank transcription is sent immediately through the same pipeline as [send],
 * instead of being posted into [voiceTranscript] for the user to review/edit first. See
 * [stopVoiceInput].
 *
 * Also includes the hands-free auto-listen loop (#30 / ED.5): when [autoListen] and [instantSend]
 * are both on and the conversation screen is visible, a reply completing automatically starts the
 * next voice recording — see [maybeAutoListen].
 */
class ConversationViewModel @JvmOverloads constructor(
    application: Application,
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val repo: RecordingRepository = RecordingRepository(AppDatabase.getInstance(application).recordingDao()),
    private val embedder: EmbeddingService = EmbeddingService(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val contextBudgetChars: Int = (aiTextBudget(application) * CONVERSATION_CONTEXT_FRACTION).toInt(),
    private val audioRecorderProvider: () -> AudioRecorder = { AudioRecorder(application) },
    private val transcriptionServiceProvider: () -> TranscriptionService = { TranscriptionService(application) },
    private val ttsProvider: () -> SpeechService = { AndroidSpeechService(application) }
) : AndroidViewModel(application) {

    // Rolling summary of messages already folded out of the live context, and the index into
    // _messages up to which that summary applies. The session FILE always has every turn
    // verbatim (see appendToFile) — only what gets sent to the LLM is capped.
    private var rollingSummary: String? = null
    private var summarizedThroughIndex: Int = 0

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Push-to-talk voice input (#23 / EC.1): tap-to-start/stop recording, then transcribe off the
    // main thread. Lazy so construction doesn't touch AudioManager/model files until used.
    private val audioRecorder by lazy { audioRecorderProvider() }
    private val transcriptionService by lazy { transcriptionServiceProvider() }
    private var voiceRecordingFile: File? = null

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _voiceTranscript = MutableStateFlow<String?>(null)
    val voiceTranscript: StateFlow<String?> = _voiceTranscript

    fun clearVoiceTranscript() { _voiceTranscript.value = null }

    // Spoken replies via Android TTS (#24 / EC.2). Lazy so construction doesn't bind the
    // TextToSpeech engine for users who never turn spoken replies on — see stopSpeaking().
    private val ttsDelegate = lazy {
        val service = ttsProvider()
        service.setSpeechRate(_ttsRate.value)
        val voiceId = _ttsVoiceId.value
        // A no-longer-existing persisted voice id returns false here; that is a silent fallback
        // to the system default, not an error — the pref is intentionally left untouched.
        if (voiceId.isNotEmpty()) service.setVoice(voiceId)
        // The listener may be invoked off the main thread (TextToSpeech's UtteranceProgressListener
        // callbacks are not guaranteed to arrive on it); MutableStateFlow.value is thread-safe, so
        // no dispatching back to the main thread is needed here.
        service.setOnSpeakingChangedListener { speaking ->
            _isSpeaking.value = speaking
            // Speech ending for any reason (natural completion, another stop, an error) resets
            // whichever bubble's replay icon was showing Stop — a replay is not exempt from this.
            if (!speaking) {
                _speakingMessageId.value = null
                // Only the auto-speak utterance armed by performSend() consumes this — a
                // replay finishing never touches awaitingAutoListen, so it never triggers here.
                if (awaitingAutoListen) {
                    awaitingAutoListen = false
                    maybeAutoListen()
                }
            }
        }
        // Same thread-safety note as above applies to the ready callback: it also fires
        // immediately with the current known state at registration, so a caller that checks after
        // init already finished still sees the right value instead of hanging on "not ready".
        service.setOnReadyChangedListener { ready ->
            _ttsReady.value = ready
            // A replay tapped while init was still running (see pendingReplayId) plays now that
            // the engine can speak; a failed init drops it rather than leaving it armed forever.
            val pending = pendingReplayId
            pendingReplayId = null
            if (ready && pending != null) startReplay(pending)
        }
        service
    }
    private val tts by ttsDelegate

    // Whether TTS is actively speaking: drives a speaker/mute icon's active state.
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    // Id (stringified index into _messages) of the AGENT message currently being replayed via
    // replayMessage() (#28 / ED.3), or null when no per-message replay is active. This is distinct
    // from isSpeaking/auto-speak: the automatic reply-speak in performSend never sets this, since
    // it is not a replay.
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId

    // Whether the (lazily built) speech engine has finished initializing. Three states, because
    // "still starting" and "the engine failed to start" must not look the same: null = not built
    // yet or init still running, true = ready, false = init failed.
    private val _ttsReady = MutableStateFlow<Boolean?>(null)
    val ttsReady: StateFlow<Boolean?> = _ttsReady

    private val _ttsEnabled = MutableStateFlow(
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getBoolean(CONVERSATION_TTS_ENABLED_KEY, false)
    )
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled

    private val _ttsRate = MutableStateFlow(
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getFloat(CONVERSATION_TTS_RATE_KEY, TTS_RATE_DEFAULT)
    )
    val ttsRate: StateFlow<Float> = _ttsRate

    private val _ttsVoiceId = MutableStateFlow(
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getString(CONVERSATION_TTS_VOICE_KEY, TTS_VOICE_DEFAULT) ?: TTS_VOICE_DEFAULT
    )
    val ttsVoiceId: StateFlow<String> = _ttsVoiceId

    /**
     * Stops any in-progress speech, e.g. when the screen is dismissed or the user mutes.
     *
     * Also the single place the engine gets built: it is touched only when spoken replies are on
     * (or were on earlier this session), so the toggle staying off means the engine is never
     * bound. Building it here rather than at speak() time matters — TextToSpeech initializes
     * asynchronously and reports unavailable until it finishes, so an engine first touched when a
     * reply is ready would silently drop that reply.
     */
    fun stopSpeaking() {
        // Cleared BEFORE stopping: the engine may report speaking=false synchronously from stop()
        // (a flushed utterance may never deliver its completion callback), so clearing afterwards
        // would let the speaking listener read the flag as "the reply finished" and open the mic.
        // Every deliberate speech-stopping action (send, endSession, startNewSession, muting,
        // screen dispose) routes through here — an interrupted reply must not still fire the
        // hands-free mic once it's cut short.
        awaitingAutoListen = false
        pendingReplayId = null
        _speakingMessageId.value = null
        if (_ttsEnabled.value || ttsDelegate.isInitialized()) tts.stop()
    }

    /**
     * Replays a single AGENT message's text via the speech engine (#28 / ED.3) — an explicit
     * per-bubble request, distinct from the automatic reply-speak in [performSend]. Unlike every
     * other TTS entry point, this touches [tts] unconditionally, bypassing the `_ttsEnabled ||
     * initialized` guard: replay must work even with spoken replies OFF, building the engine on
     * demand without ever flipping the [ttsEnabled] preference. Any current speech (auto-speak or
     * another replay) is stopped first, so only one utterance ever plays at a time.
     *
     * A freshly built engine is not usable yet — TextToSpeech initializes asynchronously and
     * reports unavailable until it finishes — so the very first replay with spoken replies OFF
     * would otherwise do nothing at all. Such a tap is remembered in [pendingReplayId] and played
     * by the ready listener instead, rather than being silently dropped.
     */
    fun replayMessage(id: String) {
        if (messageForId(id) == null) return
        // Same ordering requirement as stopSpeaking(): this stop() may report speaking=false
        // synchronously, and a replay taking the engine over from an auto-spoken reply must not
        // be mistaken for that reply finishing.
        awaitingAutoListen = false
        tts.stop()
        if (tts.isAvailable) startReplay(id) else pendingReplayId = id
    }

    // A replay requested before the lazily built engine finished initializing. Volatile because it
    // is written from the caller (main) thread and read from the ready callback, which — like the
    // speaking callback — may arrive on another thread.
    @Volatile
    private var pendingReplayId: String? = null

    /**
     * Speaks the message [id] points at and marks it as the one replaying. May run on the ready
     * callback's thread; the state it touches is thread-safe. The message is re-resolved here
     * because a pending replay can outlive the list it referred to (e.g. a session rotation).
     */
    private fun startReplay(id: String) {
        val message = messageForId(id) ?: return
        _speakingMessageId.value = id
        tts.speak(message.text)
    }

    private fun messageForId(id: String): ChatMessage? =
        id.toIntOrNull()?.let { _messages.value.getOrNull(it) }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        // Muting mid-reply must silence the reply already being spoken.
        if (!enabled) stopSpeaking()
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CONVERSATION_TTS_ENABLED_KEY, enabled)
            .apply()
    }

    /**
     * Sets the spoken-reply rate: persists it, and — mirroring [setTtsEnabled]'s no-bind-when-
     * disabled guarantee — applies it to the engine only if spoken replies are enabled or the
     * engine has already been built. A disabled user who has never warmed the engine must not
     * construct one just to change a setting they aren't using.
     *
     * The short preview is spoken only while spoken replies are ON: with the toggle off the user
     * has asked for silence, so an already-built engine is reconfigured mutely.
     */
    fun setTtsRate(rate: Float) {
        _ttsRate.value = rate
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat(CONVERSATION_TTS_RATE_KEY, rate)
            .apply()
        if (_ttsEnabled.value || ttsDelegate.isInitialized()) {
            // If this call is what first builds the engine, the lazy initializer above already
            // applied the just-updated _ttsRate.value — applying it again would double-call.
            val alreadyBuilt = ttsDelegate.isInitialized()
            val engine = tts
            if (alreadyBuilt) engine.setSpeechRate(rate)
            if (_ttsEnabled.value && engine.isAvailable) engine.preview("This is a preview of the speech rate.")
        }
    }

    /** Sets the selected voice; same persist/apply/preview behavior as [setTtsRate]. */
    fun setTtsVoice(id: String) {
        _ttsVoiceId.value = id
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(CONVERSATION_TTS_VOICE_KEY, id)
            .apply()
        if (_ttsEnabled.value || ttsDelegate.isInitialized()) {
            val alreadyBuilt = ttsDelegate.isInitialized()
            val engine = tts
            if (alreadyBuilt) engine.setVoice(id)
            if (_ttsEnabled.value && engine.isAvailable) engine.preview("This is a preview of the selected voice.")
        }
    }

    /**
     * Voices available for the picker UI (#25); empty when TTS is unavailable. Also empty —
     * without touching the engine — when spoken replies are off and the engine was never built:
     * merely checking must not bind a TextToSpeech engine the user isn't using.
     */
    fun availableVoices(): List<VoiceInfo> =
        if (_ttsEnabled.value || ttsDelegate.isInitialized()) tts.availableVoices() else emptyList()

    // Instant send after voice transcription (#26 / ED.1): when on, a successful non-blank
    // transcription is sent immediately through the same pipeline as send(), instead of being
    // posted into voiceTranscript for the user to review/edit in the input field first.
    private val _instantSend = MutableStateFlow(
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getBoolean(CONVERSATION_INSTANT_SEND_KEY, false)
    )
    val instantSend: StateFlow<Boolean> = _instantSend

    /**
     * Sets instant send. Turning it OFF also turns auto-listen off (#30, mirroring notetaker's
     * #72): auto-listen only functions when instant send is on (see [maybeAutoListen]), so
     * leaving it on with instant send off would silently strand a toggle the user can see but
     * that no longer does anything. Turning it ON, alone, leaves auto-listen untouched — the
     * dependency runs one direction only.
     */
    fun setInstantSend(enabled: Boolean) {
        _instantSend.value = enabled
        if (!enabled && _autoListen.value) _autoListen.value = false
        persistInstantSendAndAutoListen()
    }

    // Hands-free auto-listen loop (#30): when on, AND instant send is also on, a reply completing
    // (see awaitingAutoListen/maybeAutoListen) automatically starts the next voice recording, so
    // the user never has to tap the mic again mid-conversation.
    private val _autoListen = MutableStateFlow(
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getBoolean(CONVERSATION_AUTO_LISTEN_KEY, false)
    )
    val autoListen: StateFlow<Boolean> = _autoListen

    init {
        // Normalizes an inconsistent persisted state (#30, mirroring notetaker's #72):
        // autoListen=true with instantSend=false is not producible by the setters below, but two
        // paths still deliver it — an install upgrading from a pre-coupling build, where
        // setAutoListen(true) alone was legal, and a restored backup carrying such a pair.
        // Auto-listen depends on instant send to function at all (see maybeAutoListen), so the
        // conservative choice is to turn auto-listen OFF rather than silently turning instant send
        // ON — the user never explicitly opted into instant send in that scenario, and enabling it
        // for them would start sending their voice input automatically without consent.
        if (_autoListen.value && !_instantSend.value) {
            _autoListen.value = false
            persistInstantSendAndAutoListen()
        }
    }

    /**
     * Sets auto-listen. Turning it ON also turns instant send on (#30, mirroring notetaker's
     * #72), since auto-listen only functions when instant send is also on (see [maybeAutoListen])
     * — flipping this on alone would silently do nothing. Turning it OFF, alone, leaves instant
     * send untouched.
     */
    fun setAutoListen(enabled: Boolean) {
        _autoListen.value = enabled
        if (enabled && !_instantSend.value) _instantSend.value = true
        persistInstantSendAndAutoListen()
    }

    /** Persists both flows' current values in one editor apply, per the setters above. */
    private fun persistInstantSendAndAutoListen() {
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CONVERSATION_INSTANT_SEND_KEY, _instantSend.value)
            .putBoolean(CONVERSATION_AUTO_LISTEN_KEY, _autoListen.value)
            .apply()
    }

    // Whether the conversation screen is currently on-screen (#30): auto-listen must only fire
    // while the user is actually looking at it — a trigger arriving while backgrounded is
    // dropped, not queued. Set by the screen via a lifecycle observer (ON_RESUME/ON_PAUSE).
    private var conversationVisible = false

    fun setConversationVisible(visible: Boolean) {
        conversationVisible = visible
    }

    // Armed right when performSend() hands the just-arrived reply to tts.speak() for auto-speak
    // (never by replayMessage()/startReplay(), which speak the same way but are a distinct,
    // explicit per-bubble action) — so the speaking-changed listener can tell "the reply we just
    // sent finished speaking" apart from "an unrelated replay finished speaking" and only
    // auto-listen for the former. Volatile because that listener callback can arrive off the main
    // thread (see ttsDelegate's KDoc).
    @Volatile
    private var awaitingAutoListen = false

    // Auto-listen must never spin on silence: a blank transcription in stopVoiceInput() arms
    // this, and the very next auto-listen trigger is skipped (and consumes the flag) instead of
    // starting another recording. A manual mic press (startVoiceInput) clears it, since that is
    // the user explicitly taking back control.
    @Volatile
    private var suppressNextAutoListen = false

    /**
     * Starts the next voice recording hands-free after a reply completes (#30), if the user has
     * opted into both instant send and auto-listen, the screen is visible, and the last
     * transcription wasn't blank. Reuses [startVoiceInput]'s own busy guards, so a trigger that
     * can't currently start (already recording/transcribing/generating) silently no-ops.
     */
    private fun maybeAutoListen() {
        if (!_instantSend.value || !_autoListen.value || !conversationVisible) return
        if (suppressNextAutoListen) {
            suppressNextAutoListen = false
            return
        }
        startVoiceInput()
    }

    /** File backing the current session; exposed internally so tests can assert on its content. */
    internal lateinit var sessionFile: File
        private set

    /** Locating and parsing the session file touches disk, so it runs off the main thread. */
    private val loadJob = viewModelScope.launch {
        val (file, restored) = withContext(ioDispatcher) {
            val dir = conversationsDir(application)
            val existing = findTodaysSessionFile(dir)
            if (existing != null) existing to parseSessionFile(existing) else newSessionFile(dir) to emptyList()
        }
        sessionFile = file
        _messages.value = restored
    }

    fun clearError() { _error.value = null }

    /** Starts recording a voice turn to a temp file in cacheDir. No-op while busy. */
    fun startVoiceInput() {
        if (_isRecordingVoice.value || _isTranscribing.value || _isGenerating.value) return
        suppressNextAutoListen = false
        val application = getApplication<Application>()
        if (!isWhisperReady(application)) {
            _error.value = "Voice input needs the transcription model — download it in Settings."
            return
        }
        val dir = File(application.cacheDir, "voice_input").also { it.mkdirs() }
        val file = File(dir, "voice_${clock()}.m4a")
        try {
            audioRecorder.start(file, false)
            voiceRecordingFile = file
            _isRecordingVoice.value = true
            _error.value = null
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Failed to start voice recording", e)
            _error.value = e.message ?: "Failed to start recording"
            voiceRecordingFile = null
            _isRecordingVoice.value = false
        }
    }

    /**
     * Starts voice input the way a big mic button on a voice-only surface would (mirrors
     * notetaker's P9.3): stops any in-progress spoken reply first, so the recording never starts
     * while a reply is still audibly being spoken over it, then delegates to [startVoiceInput].
     */
    fun startVoiceInputInterruptingSpeech() {
        stopSpeaking()
        startVoiceInput()
    }

    /**
     * Stops the in-progress voice recording and transcribes it off the main thread. The result is
     * exposed through [voiceTranscript] for the UI to place in the input field (instant-send
     * consumption is a later issue, #26). The temp audio file is always deleted once transcription
     * finishes, succeeds or not.
     */
    fun stopVoiceInput() {
        if (!_isRecordingVoice.value) return
        val file = voiceRecordingFile
        voiceRecordingFile = null
        audioRecorder.stop()
        _isRecordingVoice.value = false
        if (file == null) return

        _isTranscribing.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val text = withContext(ioDispatcher) { transcriptionService.transcribe(file) }
                if (text.isBlank()) {
                    _error.value = "Didn't catch that"
                    // Never spin the auto-listen loop on silence: skip the next trigger (#30).
                    suppressNextAutoListen = true
                } else if (_instantSend.value) {
                    // The _isGenerating claim below must happen synchronously, with no suspend
                    // point between the check and the set, so it is atomic with send()'s own
                    // synchronous claim on the main thread: whichever runs first wins the race.
                    // If something else is already generating, the transcript must not be
                    // dropped — fall back to voiceTranscript exactly like instant send OFF.
                    if (_isGenerating.value) {
                        // voiceTranscript lands in the input field — which the instant-send
                        // surface hides, so on its own this would silently swallow the user's
                        // words. Surfacing them through the error snackbar as well means they are
                        // never lost without the user seeing what did not send.
                        _voiceTranscript.value = text
                        _error.value = "Busy — not sent: \"$text\""
                    } else {
                        stopSpeaking()
                        _isGenerating.value = true
                        _error.value = null
                        // Launched, not awaited: transcription is over the moment the claim is
                        // made, so this coroutine's finally must run now — otherwise the mic
                        // button would keep spinning "transcribing" and the temp audio file
                        // would stay on disk for the whole generation.
                        generationJob = viewModelScope.launch { performSend(text.trim()) }
                    }
                } else {
                    _voiceTranscript.value = text
                }
            } catch (e: Exception) {
                Log.e("ConversationViewModel", "Voice transcription failed", e)
                _error.value = e.message ?: "Transcription failed"
            } finally {
                _isTranscribing.value = false
                withContext(ioDispatcher) { file.delete() }
            }
        }
    }

    /**
     * Abandons an in-progress voice recording without transcribing it, releasing the mic and
     * dropping the temp file. Called when the conversation screen goes away, so a recording the
     * user walked away from cannot keep the mic held for the life of the process.
     */
    fun cancelVoiceInput() {
        if (!_isRecordingVoice.value) return
        val file = voiceRecordingFile
        voiceRecordingFile = null
        audioRecorder.stop()
        _isRecordingVoice.value = false
        file?.delete()
    }

    /** Rotates to a fresh session file (a new "meeting"); the previous transcript stays on disk. */
    fun startNewSession() {
        if (_isGenerating.value) return
        stopSpeaking()
        viewModelScope.launch {
            loadJob.join()
            sessionFile = withContext(ioDispatcher) { newSessionFile(conversationsDir(getApplication())) }
            _messages.value = emptyList()
            _error.value = null
        }
    }

    // Tracks the coroutine running performSend() or endSession()'s analysis, so stopGenerating()
    // has something to cancel either way.
    private var generationJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        stopSpeaking()
        // Claimed synchronously on the caller (main) thread so a rapid double-send cannot slip
        // through before the coroutine body runs.
        _isGenerating.value = true
        _error.value = null
        generationJob = viewModelScope.launch { performSend(trimmed) }
    }

    /** Cancels the in-flight generation started by [send] or [endSession]. */
    fun stopGenerating() {
        generationJob?.cancel()
        // No surprise mic after a cancelled turn.
        awaitingAutoListen = false
    }

    /**
     * Ends the current "meeting with the agent": converts the transcript into a [Recording]
     * through the normal save path, runs it through the same analysis pipeline a transcribed
     * local recording gets, marks the session file as ended so it is never auto-resumed, then
     * rotates to a fresh session. A no-op if the session has no messages yet.
     *
     * Analysis runs unconditionally, unlike a local audio recording, which only auto-analyzes when
     * the `auto_process` pref is on: that pref gates work kicked off automatically by capture
     * finishing, whereas tapping End is itself the explicit request for the summarized note.
     *
     * Tracked in [generationJob] — the same field [send] uses — so [stopGenerating] can actually
     * cancel it. A cancellation here is treated exactly like the existing analysis-failure path:
     * the session stays live and resumable — no rename, no message clear, no rotation — and the
     * Recording row saved before cancellation may remain (a harmless upsert; retrying End
     * overwrites it). The one exception is a Stop that arrives once the rotation has begun: that
     * tail is [NonCancellable], so the End simply completes. Unlike a failure, no error is
     * surfaced: cancellation isn't a failure (mirrors [performSend]'s `CancellationException`
     * handling), while a real [TimeoutCancellationException] from the analysis LLM call still
     * surfaces as an error.
     */
    fun endSession() {
        if (_isGenerating.value) return
        stopSpeaking()
        // Claimed synchronously on the caller (main) thread so a double-tap — or a send() landing
        // in the same frame — cannot slip past the guard before the coroutine body runs.
        _isGenerating.value = true
        _error.value = null
        generationJob = viewModelScope.launch {
            try {
                loadJob.join()
                val currentMessages = _messages.value
                if (currentMessages.isEmpty()) return@launch

                val filename = sessionFile.name
                val transcript = buildTranscript(currentMessages)
                repo.save(
                    Recording(
                        filename = filename,
                        transcript = transcript,
                        createdAt = clock()
                    )
                )
                analyzeTranscript(getApplication(), llm, embedder, repo, filename, transcript)

                // Renamed only once the work above succeeded, so a failure leaves the session
                // intact and resumable; retrying End re-saves under the same filename (the
                // primary key), which updates that row rather than adding a second one.
                // NonCancellable because the rotation spans suspension points: a Stop landing
                // between them would leave the session half-ended — renamed on disk, yet still
                // live in memory pointing at a path that no longer exists, so the next End could
                // never rename it again. Either the whole rotation happens or none of it.
                withContext(NonCancellable) {
                    val ended = withContext(ioDispatcher) {
                        val endedFile = File(sessionFile.parentFile, "${sessionFile.nameWithoutExtension}.ended.md")
                        sessionFile.renameTo(endedFile)
                    }
                    if (!ended) throw IOException("Could not mark ${sessionFile.name} as ended")

                    sessionFile = withContext(ioDispatcher) { newSessionFile(conversationsDir(getApplication())) }
                    _messages.value = emptyList()
                }
            } catch (e: TimeoutCancellationException) {
                // A real failure from the analysis LLM call's own timeout, not a stopGenerating()
                // cancellation — must be caught before the CancellationException branch below so
                // it still reaches the user as an error.
                Log.e("ConversationViewModel", "endSession failed", e)
                _error.value = e.message ?: "Failed to end session"
            } catch (e: CancellationException) {
                // stopGenerating() cancellation, not a failure: no error, session left exactly as
                // it was before this attempt. Rethrown so the coroutine actually completes as
                // cancelled.
                throw e
            } catch (e: Exception) {
                Log.e("ConversationViewModel", "endSession failed", e)
                _error.value = e.message ?: "Failed to end session"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** Renders messages as a speaker-labeled meeting transcript, e.g. "Me: ..." / "Agent: ...". */
    private fun buildTranscript(messages: List<ChatMessage>): String =
        messages.joinToString("\n\n") { message ->
            val label = if (message.role == Role.USER) "Me" else "Agent"
            "$label: ${message.text}"
        }

    /**
     * The actual send pipeline. Callers MUST have already claimed [_isGenerating] (synchronously,
     * on the main thread) before launching this — it only releases the claim, in the `finally`
     * block.
     */
    private suspend fun performSend(trimmed: String) {
        // Set only on the success path, when the reply won't be auto-spoken (#30): auto-speak's
        // completion instead arms awaitingAutoListen, below. Checked after the finally block so
        // startVoiceInput()'s busy guard sees isGenerating already cleared.
        var autoListenAfterReply = false
        try {
            loadJob.join()
            val userMessage = ChatMessage(Role.USER, trimmed, clock())
            _messages.value = _messages.value + userMessage
            appendToFile(userMessage)

            val noteContext = retrieveNoteContext(trimmed)
            llm.ensureLoaded()
            val (systemPrompt, turns) = buildLiveContext(_messages.value, noteContext)
            val reply = llm.generate(systemPrompt, turns)
            val modelMessage = ChatMessage(Role.MODEL, reply, clock())
            _messages.value = _messages.value + modelMessage
            appendToFile(modelMessage)
            if (_ttsEnabled.value && tts.isAvailable) {
                // The arriving reply takes the engine over from a replay tapped mid-generation,
                // whose bubble would otherwise keep showing Stop for speech that is no longer its.
                _speakingMessageId.value = null
                awaitingAutoListen = true
                tts.speak(reply)
            } else {
                autoListenAfterReply = true
            }
        } catch (e: TimeoutCancellationException) {
            // generate()'s 3-minute timeout surfaces as a CancellationException subtype, but it is
            // a real failure rather than a stopGenerating() cancellation — must be caught before
            // the CancellationException branch below so it still reaches the user as an error.
            Log.e("ConversationViewModel", "Generation failed", e)
            _error.value = e.message ?: "Failed to generate a response"
        } catch (e: CancellationException) {
            // stopGenerating() cancellation, not a failure: no error, no model turn. Rethrown so
            // the coroutine actually completes as cancelled.
            throw e
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Generation failed", e)
            _error.value = e.message ?: "Failed to generate a response"
        } finally {
            _isGenerating.value = false
        }
        if (autoListenAfterReply) maybeAutoListen()
    }

    /**
     * Maps messages to LLM turns, merging consecutive same-role messages: the chat template
     * requires strictly alternating roles, and a failed generation leaves two user turns in a row.
     */
    private fun toChatTurns(messages: List<ChatMessage>): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (message in messages) {
            val last = turns.lastOrNull()
            if (last != null && last.role == message.role) {
                turns[turns.lastIndex] = ChatTurn(message.role, last.text + "\n\n" + message.text)
            } else {
                turns.add(ChatTurn(message.role, message.text))
            }
        }
        return turns
    }

    /**
     * Retrieves relevant notes from the user's library for [query] via semantic search + topic-
     * sibling expansion (the same retrieval [RecordingViewModel.askLibraryQuestion] uses), rendered
     * as a short bullet list for injection into the system prompt. Returns null if embeddings
     * aren't available, nothing relevant is found, or retrieval fails for any reason — a
     * conversation turn must never break because note lookup did.
     *
     * Unlike askLibraryQuestion, this does NOT backfill missing embeddings: a conversation turn
     * must stay fast, semanticSearch already skips notes without embeddings, and analysis embeds
     * notes at creation time.
     *
     * Runs on [ioDispatcher] (#56): the cosine scan over every embedded note and the topic-sibling
     * expansion are non-trivial work that must not block Main.
     */
    private suspend fun retrieveNoteContext(query: String): String? = withContext(ioDispatcher) {
        try {
            if (!embedder.isReady) return@withContext null
            embedder.ensureLoaded()
            val queryEmbed = embedder.embed(query) ?: return@withContext null

            // Ended conversation notes are excluded (#56): they're embedded like any other note,
            // but being conversational text they tend to outrank real recordings for
            // conversational queries, and the live session already carries its own history.
            val all = repo.allRecordings.first()
                .filter {
                    (it.summary.isNotBlank() || it.shortSummary.isNotBlank()) &&
                        !DateUtils.isConversationNote(it.filename)
                }
            val seeds = repo.semanticSearch(
                queryEmbed,
                all,
                topK = NOTE_RETRIEVAL_TOP_K,
                minScore = NOTE_RELEVANCE_MIN_SCORE
            )
            if (seeds.isEmpty()) return@withContext null

            val noteBudget = (contextBudgetChars * NOTE_CONTEXT_FRACTION).toInt()
            val expanded = expandWithTopicSiblings(seeds, all, noteBudget)
            val lines = expanded.map { note ->
                val title = note.title.ifBlank { note.filename }
                "- $title: ${sourceText(note).replace('\n', ' ')}"
            }

            // Cumulative budget guard: an uncapped LLM-written shortSummary must not be able to
            // blow noteBudget on its own. The first line is always kept even if it alone exceeds
            // the budget (seeds are budget-exempt in expandWithTopicSiblings too).
            val kept = mutableListOf<String>()
            var used = 0
            for (line in lines) {
                used += line.length
                if (kept.isNotEmpty() && used > noteBudget) break
                kept.add(line)
            }
            kept.joinToString("\n")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Note retrieval failed", e)
            null
        }
    }

    /**
     * Builds the (systemPrompt, turns) pair to send to the LLM, capped to [contextBudgetChars].
     * While the unsummarized tail of [messages] fits the budget, it is sent in full. Once it
     * would overflow, the older portion (everything but the last [TAIL_MESSAGE_COUNT] messages)
     * is folded into a rolling summary — compounding any prior summary — which is injected into
     * the system prompt so the turn list keeps starting with USER and alternating. The summary is
     * clamped to [SUMMARY_BUDGET_FRACTION] of the budget so compounding cannot grow it without
     * bound. If the summarize call fails, this falls back to the previous summary plus the tail
     * for this send only; the rolling summary state is left untouched so the next rollover
     * retries it.
     */
    private suspend fun buildLiveContext(
        messages: List<ChatMessage>,
        noteContext: String?
    ): Pair<String, List<ChatTurn>> {
        fun systemPromptWith(summary: String?, includeNotes: Boolean = true): String {
            var prompt = IDEATION_SYSTEM_PROMPT
            if (includeNotes && noteContext != null) {
                prompt += "\n\nRelevant notes from the user's library (cite them when the user " +
                    "asks about their notes; if the answer is not in them, say so):\n$noteContext"
            }
            if (summary != null) {
                prompt += "\n\nSummary of the conversation so far: $summary"
            }
            return prompt
        }

        // Guards every return point against the note context (or a compounding summary) pushing
        // the sent payload past contextBudgetChars (#56): notes are regenerable next turn, unlike
        // conversation history, so they're dropped first rather than truncating turns.
        fun finalize(summary: String?, turns: List<ChatTurn>): Pair<String, List<ChatTurn>> {
            val prompt = systemPromptWith(summary)
            val total = prompt.length + turns.sumOf { it.text.length }
            if (total > contextBudgetChars && noteContext != null) {
                return systemPromptWith(summary, includeNotes = false) to turns
            }
            return prompt to turns
        }

        val liveMessages = messages.subList(summarizedThroughIndex, messages.size)
        val systemPrompt = systemPromptWith(rollingSummary)
        val liveTurns = toChatTurns(liveMessages)
        val contextChars = systemPrompt.length + liveTurns.sumOf { it.text.length }

        // Nothing to gain from rolling over if the entire unsummarized region is already just
        // the tail — there is no older portion left to summarize away.
        if (contextChars <= contextBudgetChars || liveMessages.size <= TAIL_MESSAGE_COUNT) {
            return finalize(rollingSummary, liveTurns)
        }

        // buildGemmaPrompt only folds the system prompt — and with it the injected summary — into
        // a LEADING USER turn, so the tail must start on one. A complete history is odd-length at
        // this point (the just-sent user turn is last), which puts a MODEL turn at the raw tail
        // start; that turn is pushed into the summarized span instead, so nothing is skipped.
        var tailStart = messages.size - TAIL_MESSAGE_COUNT
        while (tailStart < messages.size - 1 && messages[tailStart].role != Role.USER) tailStart++
        val olderMessages = messages.subList(summarizedThroughIndex, tailStart)
        val tailMessages = messages.subList(tailStart, messages.size)
        val olderText = buildString {
            rollingSummary?.let { append("Previous summary: $it\n\n") }
            olderMessages.forEach { message ->
                append(if (message.role == Role.USER) "User: " else "Assistant: ")
                append(message.text)
                append("\n\n")
            }
        }

        // A blank generation is treated as a failure: accepting it would advance
        // summarizedThroughIndex and drop the older span with nothing standing in for it.
        val newSummary = try {
            llm.generate(SUMMARY_PROMPT, olderText).trim().ifBlank { null }
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Summary generation failed, falling back to tail truncation", e)
            null
        }

        if (newSummary == null) {
            // Keep any summary earned by an earlier rollover: dropping it would discard context
            // that is already safely folded down, for no budget gain (this send is a strict
            // subset of the over-budget context measured above).
            return finalize(rollingSummary, toChatTurns(tailMessages))
        }
        val clamped = newSummary.take((contextBudgetChars * SUMMARY_BUDGET_FRACTION).toInt())
        rollingSummary = clamped
        summarizedThroughIndex = tailStart
        return finalize(clamped, toChatTurns(tailMessages))
    }

    private suspend fun appendToFile(message: ChatMessage) {
        withContext(ioDispatcher) {
            val label = if (message.role == Role.USER) "Me" else "Agent"
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(message.timestampMillis))
            sessionFile.appendText("**$label** ($time):\n${message.text}\n\n")
        }
    }

    /** Finds the most recent conv_*.md file created today, if any, to resume an unfinished session. */
    private fun findTodaysSessionFile(dir: File): File? {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(clock()))
        return dir.listFiles()
            ?.filter { SESSION_FILENAME_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.startsWith(today) == true }
            ?.maxByOrNull { it.name }
    }

    /** Builds a not-yet-taken session filename, so rotating twice never reuses an existing file. */
    private fun newSessionFile(dir: File): File {
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        var millis = clock()
        var file = File(dir, "conv_${format.format(Date(millis))}.md")
        while (file.exists()) {
            millis += 1000
            file = File(dir, "conv_${format.format(Date(millis))}.md")
        }
        return file
    }

    /** Parses this ViewModel's own markdown session format back into messages (see [appendToFile]). */
    private fun parseSessionFile(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        var currentRole: Role? = null
        var currentTime: String? = null
        val text = StringBuilder()

        fun flush() {
            val role = currentRole
            val time = currentTime
            val body = text.toString().trim()
            text.setLength(0)
            // Content before the first turn header (corrupted or foreign file) is discarded,
            // as are empty turns; neither is representable as a message.
            if (role != null && time != null && body.isNotEmpty()) {
                messages.add(ChatMessage(role, body, reconstructMillis(time)))
            }
        }

        file.forEachLine { line ->
            val match = TURN_HEADER_REGEX.matchEntire(line)
            if (match != null) {
                flush()
                currentRole = if (match.groupValues[1] == "Me") Role.USER else Role.MODEL
                currentTime = "${match.groupValues[2]}:${match.groupValues[3]}"
            } else {
                text.append(line).append("\n")
            }
        }
        flush()
        return messages
    }

    /** Reconstructs an approximate timestamp (today's date + parsed HH:mm) for a reloaded turn. */
    private fun reconstructMillis(hhmm: String): Long {
        val (hour, minute) = hhmm.split(":").map { it.toInt() }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = clock()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun conversationsDir(application: Application): File =
        File(application.filesDir, "conversations").also { it.mkdirs() }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        cancelVoiceInput()
        embedder.close()
        if (ttsDelegate.isInitialized()) tts.shutdown()
    }
}
