package com.daedalusapps.echo.ai

import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

/**
 * [AndroidSpeechService] needs a real [android.speech.tts.TextToSpeech] to unit-test speak()/
 * stop() end-to-end, which isn't reliably driveable under Robolectric — so the audio-focus
 * coordination is extracted into [SpeechFocusCoordinator] and tested at that seam instead.
 *
 * Focus is requested on speaking=true (engine onStart), not before speak() is called — see
 * [SpeechFocusCoordinator] kdoc for why.
 */
class SpeechFocusCoordinatorTest {

    @Test
    fun onSpeakingChanged_true_requestsFocus() {
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(true)

        verify(exactly = 1) { focusManager.request() }
    }

    @Test
    fun onSpeakingChanged_falseAfterTrue_abandonsFocus() {
        // Simulates AndroidSpeechService's onStart -> onDone utterance-progress path.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(true)
        coordinator.onSpeakingChanged(false)

        verifyOrder {
            focusManager.request()
            focusManager.abandon()
        }
    }

    @Test
    fun onSpeakingChanged_falseWithoutPriorTrue_doesNotAbandonFocus() {
        // No focus was ever requested (e.g. speak() never reached onStart) — abandon must not be
        // called for focus never held.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(false)

        verify(exactly = 0) { focusManager.abandon() }
    }

    @Test
    fun onSpeakingChanged_falseTwice_abandonsFocusOnlyOnce() {
        // Double-stop / stop-then-shutdown: abandon must not be re-issued for focus already
        // released.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(true)
        coordinator.onSpeakingChanged(false)
        coordinator.onSpeakingChanged(false)

        verify(exactly = 1) { focusManager.abandon() }
    }

    @Test
    fun secondSpeakingCycle_requestsAndAbandonsFocusAgain() {
        // Back-to-back replies: focus released by the first cycle must be re-acquired for the
        // second and released again, rather than the coordinator latching after one cycle.
        val focusManager = mockk<AudioFocusManager>(relaxed = true)
        val coordinator = SpeechFocusCoordinator(focusManager)

        coordinator.onSpeakingChanged(true)
        coordinator.onSpeakingChanged(false)
        coordinator.onSpeakingChanged(true)
        coordinator.onSpeakingChanged(false)

        verify(exactly = 2) { focusManager.request() }
        verify(exactly = 2) { focusManager.abandon() }
    }
}
