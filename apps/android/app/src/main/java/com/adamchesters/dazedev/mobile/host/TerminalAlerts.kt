package com.adamchesters.dazedev.mobile.host

/**
 * Reads OSC sequences (ESC ] code ; data, ended by BEL or ESC \) from terminal output. Sequences may
 * be split across chunks; longer ones than [maxLength] are skipped. Output is UTF-8, so 8-bit C1
 * controls are not recognised: their byte values occur inside multibyte characters.
 */
class OscScanner(
    private val maxLength: Int = 4096,
    private val onOsc: (Int, String) -> Unit,
) {
    private enum class State { Text, Escape, Osc, OscEscape }

    private var state = State.Text
    private val osc = java.io.ByteArrayOutputStream()
    private var overflow = false

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size,
    ) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xff
            state =
                when (state) {
                    State.Text -> {
                        if (b == 0x1b) State.Escape else State.Text
                    }

                    State.Escape -> {
                        if (b == ']'.code) {
                            start()
                        } else if (b == 0x1b) {
                            State.Escape
                        } else {
                            State.Text
                        }
                    }

                    State.Osc -> {
                        when (b) {
                            0x07 -> finish()
                            0x1b -> State.OscEscape
                            0x18, 0x1a -> State.Text
                            else -> append(b)
                        }
                    }

                    State.OscEscape -> {
                        if (b == '\\'.code) {
                            finish()
                        } else if (b == ']'.code) {
                            start()
                        } else {
                            State.Text
                        }
                    }
                }
        }
    }

    private fun start(): State {
        osc.reset()
        overflow = false
        return State.Osc
    }

    private fun append(b: Int): State {
        if (osc.size() >= maxLength) overflow = true else osc.write(b)
        return State.Osc
    }

    private fun finish(): State {
        if (!overflow) {
            val text = osc.toString(Charsets.UTF_8.name())
            val code = text.substringBefore(';').toIntOrNull()
            if (code != null) onOsc(code, if (';' in text) text.substringAfter(';') else "")
        }
        osc.reset()
        return State.Text
    }
}

/** The desktop's terminal-alert rules (lib/terminal-alerts.ts). */
object TerminalAlerts {
    private val control = Regex("[\\x00-\\x1f\\x7f]")

    /** Accepts only explicit notifications: OSC 9 text (not progress reports) and OSC 777 notify. */
    fun notification(
        osc: Int,
        data: String,
    ): String? {
        if (osc == 9 && data.isNotEmpty() && !Regex("^\\d+;").containsMatchIn(data)) return data.replace(control, "").take(240)
        if (osc == 777 && data.startsWith("notify;")) {
            return data
                .substring(7)
                .split(';')
                .filter(String::isNotEmpty)
                .joinToString(": ")
                .replace(control, "")
                .take(240)
        }
        return null
    }

    const val LONG_COMMAND_MS = 10_000L

    /** OSC 133 D: report a finished command when nobody is watching, or when it ran long enough to look away. */
    fun commandFinished(
        startedAt: Long,
        now: Long,
        watched: Boolean,
    ) = !watched || now - startedAt >= LONG_COMMAND_MS
}

/** One throttle window per terminal, so a busy terminal cannot hide alerts from others. */
class AlertThrottle(
    private val interval: Long = 3_000,
) {
    private val last = HashMap<String, Long>()

    fun allow(
        key: String,
        now: Long,
    ): Boolean {
        val previous = last[key]
        if (previous != null && now - previous < interval) return false
        last[key] = now
        return true
    }
}

/** Turns one terminal's output into alerts: explicit notifications and finished commands. */
class TerminalAlertWatcher(
    private val clock: () -> Long,
    private val watched: () -> Boolean,
    private val onAlert: (String) -> Unit,
) {
    private var commandStarted = 0L
    private val scanner =
        OscScanner { code, data ->
            when (code) {
                9, 777 -> {
                    TerminalAlerts.notification(code, data)?.let(onAlert)
                }

                133 -> {
                    when (data.substringBefore(';')) {
                        "C" -> {
                            commandStarted = clock()
                        }

                        "D" -> {
                            if (commandStarted != 0L) {
                                val started = commandStarted
                                commandStarted = 0
                                if (TerminalAlerts.commandFinished(started, clock(), watched())) onAlert("Command finished")
                            }
                        }
                    }
                }
            }
        }

    fun feed(bytes: ByteArray) = scanner.feed(bytes)
}
