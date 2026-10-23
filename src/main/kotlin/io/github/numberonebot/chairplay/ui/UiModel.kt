package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.session.BringUpStatus
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/** Where the connection to the iPhone stands, as the start screen tells it. */
enum class Phase {
    /** No Wi-Fi network is set; nothing connects until one is. */
    SETUP,

    /** Not connecting: ui.autoconnect is off and Connect was not pressed. */
    IDLE,

    /** Opening the network, the AirPlay listener and Bonjour. */
    STARTING,

    /** Calling the iPhone over Bluetooth. */
    CALLING,

    /** The iPhone answered and is to join the Wi-Fi (a new one: after the driver accepts CarPlay). */
    JOINING,

    /** CarPlay is running. */
    ACTIVE,

    /** The last bring-up failed; the next one starts shortly. */
    RETRYING,
}

/** What the main window shows. */
enum class Screen { START, SETTINGS, CARPLAY }

data class UiError(val message: String, val at: LocalTime)

data class UiState(
    val phase: Phase,
    val screen: Screen,
    val error: UiError? = null,
    val knownIphone: Boolean = false,
    val autoshow: Boolean,
    /** Saved settings that apply with the next connection, which has not started yet. */
    val reconnectPending: Set<String> = emptySet(),
    /** Saved settings that differ from what the app started with and apply at the next start. */
    val restartPending: Set<String> = emptySet(),
) {
    /** The web UI covers the window; otherwise CarPlay's picture shows and takes the keys. */
    val showsUi: Boolean get() = screen != Screen.CARPLAY

    val canShowCarPlay: Boolean get() = phase == Phase.ACTIVE
}

/**
 * The start and settings screens' state machine. CarPlay shows only with a session: with [autoshow] it covers the
 * start screen as the session starts, otherwise it waits for Show CarPlay; the settings screen, also opened by the
 * car button on the CarPlay home screen, stays open until left. A session's end brings the start screen back.
 * Safe from any thread; listeners hear each change on the thread that made it.
 */
class UiModel(
    wifiSet: Boolean,
    autoconnect: Boolean,
    autoshow: Boolean,
    private val clock: () -> LocalTime = LocalTime::now,
) {
    @Volatile
    var state = UiState(
        phase = when {
            !wifiSet -> Phase.SETUP
            autoconnect -> Phase.STARTING
            else -> Phase.IDLE
        },
        screen = Screen.START,
        autoshow = autoshow,
    )
        private set

    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()

    fun addListener(listener: (UiState) -> Unit) {
        listeners += listener
    }

    private fun update(change: (UiState) -> UiState) {
        val next: UiState
        synchronized(this) {
            val previous = state
            next = change(previous)
            if (next == previous) return
            state = next
        }
        listeners.forEach { it(next) }
    }

    fun bringUp(status: BringUpStatus) = update { state ->
        when (status) {
            BringUpStatus.Starting -> state.copy(phase = Phase.STARTING, reconnectPending = emptySet())
            BringUpStatus.CallingIphone -> state.copy(phase = Phase.CALLING)
            is BringUpStatus.Joining -> state.copy(phase = Phase.JOINING, knownIphone = status.knownIphone)
            is BringUpStatus.Failed -> state.copy(phase = Phase.RETRYING, error = UiError(status.message, clock()))
        }
    }

    /** Connect was pressed, or the app connects on its own as it starts. */
    fun connecting() = update { if (it.phase == Phase.IDLE) it.copy(phase = Phase.STARTING) else it }

    fun sessionActive() = update { state ->
        val screen = if (state.autoshow && state.screen == Screen.START) Screen.CARPLAY else state.screen
        state.copy(phase = Phase.ACTIVE, error = null, screen = screen)
    }

    /** The bring-up starts over after a session, so the start screen says so at once. */
    fun sessionEnded() = update { state ->
        if (state.phase != Phase.ACTIVE) return@update state
        state.copy(phase = Phase.STARTING, screen = if (state.screen == Screen.CARPLAY) Screen.START else state.screen)
    }

    /** Disconnect: no session and no bring-up until Connect; CarPlay's picture gives way to the start screen. */
    fun disconnected() = update { state ->
        state.copy(phase = Phase.IDLE, screen = if (state.screen == Screen.CARPLAY) Screen.START else state.screen)
    }

    /** The car button on the CarPlay home screen. */
    fun carButton() = update { it.copy(screen = Screen.SETTINGS) }

    /** Shows [screen]; CarPlay only with a session. Returns whether it is shown. */
    fun show(screen: Screen): Boolean {
        var shown = false
        update { state ->
            shown = screen != Screen.CARPLAY || state.phase == Phase.ACTIVE
            if (shown) state.copy(screen = screen) else state
        }
        return shown
    }

    /** A Wi-Fi network was saved; returns true when that ends the setup. */
    fun wifiSaved(): Boolean {
        var ended = false
        update { state ->
            ended = state.phase == Phase.SETUP
            if (ended) state.copy(phase = Phase.IDLE) else state
        }
        return ended
    }

    fun setAutoshow(autoshow: Boolean) = update { it.copy(autoshow = autoshow) }

    fun pending(reconnect: Set<String>, restart: Set<String>) =
        update { it.copy(reconnectPending = it.reconnectPending + reconnect, restartPending = restart) }
}
