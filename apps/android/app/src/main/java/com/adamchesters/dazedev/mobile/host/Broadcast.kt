package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.ssh.SshLink
import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Base64

enum class BroadcastScope(
    val label: String,
) {
    Terminal("This terminal"),
    Workspace("This workspace"),
    Host("This host"),
    All("All hosts"),
}

/** A saved terminal that may receive a broadcast. [fingerprint] identifies the machine, user and tmux socket. */
data class BroadcastCandidate(
    val label: String,
    val hostId: String?,
    val fingerprint: String,
    val sessionId: String,
    val stamp: String,
)

data class BroadcastRecipient(
    val label: String,
    val hostId: String,
    val sessionId: String,
    val stamp: String,
    val identity: String,
)

data class BroadcastSnapshot(
    val recipients: List<BroadcastRecipient>,
    val skipped: List<String>,
    val mirrors: Int,
) {
    /** Changes when the recipients change; sending needs the reviewed revision. */
    val revision get() = recipients.map { listOf(it.hostId, it.identity).joinToString("\u0000") }.sorted().joinToString("\n")
}

data class BroadcastResult(
    val delivered: List<String>,
    val failed: List<String>,
)

/**
 * Broadcast with the desktop's rules (lib/broadcast.ts): recipients are verified running tmux sessions, a
 * session reached through several saved terminals or host entries gets the text once, and one reviewed
 * message is submitted once. Nothing is retried.
 */
object Broadcast {
    const val MAX_TEXT = 16 * 1024

    /**
     * [sessions] gives a connected host's running sessions, or null when the host is not connected.
     * Candidates whose host is offline or whose session ended or restarted are skipped.
     */
    fun snapshot(
        candidates: List<BroadcastCandidate>,
        sessions: (String) -> List<Pair<String, String>>?,
    ): BroadcastSnapshot {
        val recipients = ArrayList<BroadcastRecipient>()
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        var mirrors = 0
        for (candidate in candidates) {
            val hostId = candidate.hostId
            val running = hostId?.let(sessions)
            if (hostId == null || running == null || candidate.fingerprint.isBlank() || (candidate.sessionId to candidate.stamp) !in running) {
                skipped.add(candidate.label)
                continue
            }
            val identity = listOf(candidate.fingerprint, candidate.sessionId, candidate.stamp).joinToString("\u0000")
            if (!seen.add(identity)) {
                mirrors++
                continue
            }
            recipients.add(BroadcastRecipient(candidate.label, hostId, candidate.sessionId, candidate.stamp, identity))
        }
        return BroadcastSnapshot(recipients, skipped, mirrors)
    }

    /** Returns a reason the text cannot be broadcast, or null. Tabs and line breaks are allowed. */
    fun problem(text: String): String? =
        when {
            text.isBlank() -> "Enter text to broadcast."
            text.toByteArray().size > MAX_TEXT -> "Broadcast text is limited to 16 KiB."
            text.any { (it.code < 32 && it != '\t' && it != '\n' && it != '\r') || it.code == 127 } -> "Enter text without terminal control characters."
            else -> null
        }

    /** One delivery of one reviewed message. A second call is refused. */
    class Delivery(
        private val snapshot: BroadcastSnapshot,
        private val text: String,
    ) {
        var sent = false
            private set

        suspend fun send(deliver: suspend (BroadcastRecipient, String) -> Unit): BroadcastResult {
            check(!sent) { "This broadcast has already been submitted." }
            problem(text)?.let { error(it) }
            sent = true
            val results =
                coroutineScope {
                    snapshot.recipients
                        .map { recipient ->
                            async {
                                try {
                                    deliver(recipient, text)
                                    recipient.label to null
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    recipient.label to (e.message ?: "Delivery failed.")
                                }
                            }
                        }.map { it.await() }
                }
            return BroadcastResult(results.filter { it.second == null }.map { it.first }, results.filter { it.second != null }.map { "${it.first}: ${it.second}" })
        }
    }

    // Checks the session is still the reviewed one, then pastes through a private tmux buffer (bracketed only
    // when the program asked for it) and presses Return. The text travels as base64 in an argument.
    private const val SCRIPT =
        "import base64,os,subprocess,sys\n" +
            "a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else [])\n" +
            "s=subprocess.check_output(a+['display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}'],text=True).strip()\n" +
            "if s!=sys.argv[3]: sys.exit('Session ended or restarted; nothing was sent.')\n" +
            "b='dazedev-broadcast-'+os.urandom(8).hex()\n" +
            "subprocess.run(a+['load-buffer','-b',b,'-'],input=base64.b64decode(sys.argv[4]),check=True)\n" +
            "subprocess.run(a+['paste-buffer','-p','-d','-b',b,'-t',sys.argv[2]],check=True)\n" +
            "subprocess.run(a+['send-keys','-t',sys.argv[2],'Enter'],check=True)\n" +
            "print('sent')\n"

    fun command(
        socket: String,
        recipient: BroadcastRecipient,
        text: String,
    ): String {
        require(Regex("\\$[0-9]+").matches(recipient.sessionId) && recipient.stamp.isNotBlank()) { "Refresh this session before broadcasting." }
        val data = Base64.getEncoder().encodeToString(text.toByteArray())
        return "exec python3 -I -c ${ShellQuoter.quote(SCRIPT)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(recipient.sessionId)} ${ShellQuoter.quote(recipient.stamp)} $data"
    }

    /** Sends [text] to one recipient over its host's SSH connection. Throws when delivery failed or is uncertain. */
    suspend fun deliver(
        ssh: SshLink,
        socket: String,
        recipient: BroadcastRecipient,
        text: String,
    ) {
        val result = ssh.execute(command(socket, recipient, text), 15_000)
        if (result.exitCode != 0 || result.stdout.trim() != "sent") {
            error(
                result.stderr
                    .lines()
                    .lastOrNull { it.isNotBlank() }
                    ?.take(200) ?: "Delivery failed or is uncertain; some text may have arrived.",
            )
        }
    }
}
