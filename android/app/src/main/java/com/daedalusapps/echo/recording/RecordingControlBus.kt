package com.daedalusapps.echo.recording

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local commands sent from the recording notification to the active recorder.
 * The foreground service keeps the process alive while the screen is locked.
 */
object RecordingControlBus {
    enum class Command { PAUSE, RESUME, STOP }

    private val mutableCommands = MutableSharedFlow<Command>(extraBufferCapacity = 1)
    val commands = mutableCommands.asSharedFlow()

    fun send(command: Command) {
        mutableCommands.tryEmit(command)
    }
}
