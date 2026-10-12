package com.adamchesters.dazedev.mobile.tmux

import com.adamchesters.dazedev.mobile.ssh.SshCommandException
import com.adamchesters.dazedev.mobile.ssh.SshConnection

data class TmuxSession(
    val name: String,
    val windowCount: Int,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
    val currentCommand: String,
    val id: String = "",
    val stamp: String = "",
)

enum class AgentKind {
    Codex,
    Claude,
}

class TmuxClient(private val ssh: SshConnection) {
    suspend fun listSessions(): List<TmuxSession> {
        val result = ssh.execute(LIST_SESSIONS_COMMAND)
        if (result.exitCode != 0) {
            val detail = result.stderr.trim()
                .ifBlank { result.stdout.trim() }
                .ifBlank { "tmux is unavailable." }
            if (result.exitCode == 1 && isMissingTmuxServer(detail)) return emptyList()
            throw SshCommandException(detail)
        }
        return parseSessions(result.stdout)
    }

    suspend fun capturePane(sessionName: String): String {
        val target = exactPaneTarget(sessionName)
        return ssh.execute(
            "tmux capture-pane -p -J -S -$CAPTURE_HISTORY_LINES -t ${ShellQuoter.quote(target)}",
        ).requireSuccess("Reading tmux output").stdout.let(::normalizeCapture)
    }

    suspend fun sendInput(sessionName: String, input: String) {
        val normalized = input
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        require(normalized.isNotEmpty()) { "Enter a command or message." }
        require(normalized.length <= MAX_INPUT_LENGTH) {
            "Input must be $MAX_INPUT_LENGTH characters or fewer."
        }

        val target = ShellQuoter.quote(exactPaneTarget(sessionName))
        val literal = ShellQuoter.quote(normalized)
        ssh.execute(
            "tmux send-keys -t $target -l -- $literal && tmux send-keys -t $target Enter",
        ).requireSuccess("Sending input")
    }

    suspend fun endSession(sessionName: String) {
        require(sessionName.isNotBlank()) { "Choose a tmux session to end." }
        ssh.execute(endSessionCommand(sessionName)).requireSuccess("Ending tmux session")
    }

    suspend fun createAgentSession(
        name: String,
        workingDirectory: String,
        agent: AgentKind,
    ) {
        require(SESSION_NAME_PATTERN.matches(name)) {
            "Use 1 to 40 letters, numbers, hyphens, or underscores for the session name."
        }
        require(workingDirectory.isBlank() || workingDirectory.startsWith('/')) {
            "Use an absolute working directory, or leave blank for your home directory."
        }

        val startupCommand = agentStartupCommand(name, agent)

        val quotedName = ShellQuoter.quote(name)
        val quotedDirectory = workingDirectory.takeIf(String::isNotBlank)
            ?.let(ShellQuoter::quote) ?: "\"\$HOME\""
        val target = ShellQuoter.quote(exactPaneTarget(name))
        val quotedStartup = ShellQuoter.quote(startupCommand)

        ssh.execute(
            "tmux new-session -d -s $quotedName -c $quotedDirectory" +
                " && tmux send-keys -t $target -l -- $quotedStartup" +
                " && tmux send-keys -t $target Enter",
        ).requireSuccess("Creating tmux session")
    }

    companion object {
        private const val CAPTURE_HISTORY_LINES = 250
        private const val MAX_INPUT_LENGTH = 8_000
        private const val SESSION_FIELD_SEPARATOR = "|"
        private val SESSION_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,40}$")

        private val LIST_SESSIONS_COMMAND = buildString {
            append("if ! command -v tmux >/dev/null 2>&1; then ")
            append("printf 'tmux is not installed on the SSH server.\\n' >&2; exit 127; fi; ")
            append("tmux list-sessions -F ")
            append(ShellQuoter.quote(
                listOf(
                    "#{session_name}",
                    "#{session_windows}",
                    "#{session_attached}",
                    "#{session_activity}",
                    "#{pane_current_command}",
                ).joinToString(SESSION_FIELD_SEPARATOR),
            ))
        }

        fun parseSessions(output: String): List<TmuxSession> = output
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull(::parseSession)
            .sortedWith(
                compareByDescending<TmuxSession> { it.attachedClients > 0 }
                    .thenByDescending(TmuxSession::lastActivityEpochSeconds),
            )
            .toList()

        private fun parseSession(line: String): TmuxSession? {
            val fields = line.split(SESSION_FIELD_SEPARATOR, limit = 5)
            if (fields.size != 5 || fields[0].isBlank()) return null
            return TmuxSession(
                name = fields[0],
                windowCount = fields[1].trim().toIntOrNull() ?: return null,
                attachedClients = fields[2].trim().toIntOrNull() ?: return null,
                lastActivityEpochSeconds = fields[3].trim().toLongOrNull() ?: 0,
                currentCommand = fields[4].trimEnd('\r'),
            )
        }

        private fun isMissingTmuxServer(detail: String): Boolean =
            detail.contains("no server running", ignoreCase = true) ||
                (
                    detail.contains("error connecting to", ignoreCase = true) &&
                        detail.contains("no such file or directory", ignoreCase = true)
                    )

        internal fun agentStartupCommand(name: String, agent: AgentKind): String = when (agent) {
            AgentKind.Codex -> "exec codex --no-alt-screen"
            AgentKind.Claude ->
                "exec claude --ax-screen-reader --name ${ShellQuoter.quote(name)} " +
                    "--remote-control ${ShellQuoter.quote(name)}"
        }

        internal fun normalizeCapture(output: String): String {
            val compacted = mutableListOf<String>()
            var blankLinePending = false

            output
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .forEach { rawLine ->
                    val line = rawLine.trimEnd()
                    if (line.isBlank()) {
                        if (compacted.isNotEmpty()) blankLinePending = true
                    } else {
                        if (blankLinePending) compacted += ""
                        compacted += line
                        blankLinePending = false
                    }
                }

            return compacted.joinToString("\n")
        }

        internal fun endSessionCommand(sessionName: String): String =
            "tmux kill-session -t ${ShellQuoter.quote(exactSessionTarget(sessionName))}"

        internal fun exactSessionTarget(sessionName: String): String = "=$sessionName"

        internal fun exactPaneTarget(sessionName: String): String = "${exactSessionTarget(sessionName)}:"
    }
}
