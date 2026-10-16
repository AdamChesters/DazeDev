package com.adamchesters.dazedev.mobile.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class HostKeyNotice(val host: String, val type: String, val fingerprint: String)
data class SavedHostKey(val type: String, val fingerprint: String)

class HostKeyChangedException(val notice: HostKeyNotice) : Exception(
    "The SSH host key for ${notice.host} has changed. DazeDev did not connect. " +
        "Forget the old key in Saved host keys only if you know the server key was replaced.",
)

/** Reads and edits one OpenSSH known_hosts file. Host names may be hashed. */
object KnownHostKeys {
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** The algorithm name stored at the start of an SSH public key blob. */
    fun keyType(blob: ByteArray): String = runCatching {
        val buffer = ByteBuffer.wrap(blob)
        val length = buffer.int
        require(length in 1..64 && length <= buffer.remaining())
        String(blob, 4, length, Charsets.US_ASCII)
    }.getOrDefault("unknown")

    private fun parse(line: String): SavedHostKey? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 3 || fields[0].startsWith("#")) return null
        val offset = if (fields[0].startsWith("@")) 1 else 0
        if (fields.size < offset + 3) return null
        val blob = runCatching { Base64.getDecoder().decode(fields[offset + 2]) }.getOrNull() ?: return null
        return SavedHostKey(fields[offset + 1], fingerprint(blob))
    }

    fun list(file: File): List<SavedHostKey> =
        if (!file.isFile) emptyList() else file.readLines().mapNotNull(::parse).distinct()

    /** Removes every entry with this fingerprint. Returns true when the file changed. */
    fun forget(file: File, fingerprint: String): Boolean {
        if (!file.isFile) return false
        val lines = file.readLines()
        val kept = lines.filter { parse(it)?.fingerprint != fingerprint }
        if (kept.size == lines.size) return false
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(kept.joinToString("") { it + "\n" })
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not update saved host keys.")
        }
        return true
    }
}

/**
 * Wraps JSch's known hosts so a changed key is never offered for trust.
 * Unknown keys are recorded so the first-use prompt can show the fingerprint.
 */
internal class GuardedHostKeys(private val inner: HostKeyRepository) : HostKeyRepository {
    @Volatile var changed: HostKeyNotice? = null
        private set
    @Volatile var unknown: HostKeyNotice? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val result = inner.check(host, key)
        val notice = HostKeyNotice(host, KnownHostKeys.keyType(key), KnownHostKeys.fingerprint(key))
        when (result) {
            HostKeyRepository.CHANGED -> changed = notice
            HostKeyRepository.NOT_INCLUDED -> unknown = notice
        }
        return result
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        check(changed == null) { "Refusing to replace a changed host key." }
        // JSch only hashes names for its own repository type, so hash here (OpenSSH |1| format).
        val host = hostkey.host
        val stored = if (host.startsWith("|1|")) hostkey else {
            val salt = ByteArray(20).also { SecureRandom().nextBytes(it) }
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
            val hash = mac.doFinal(host.toByteArray(Charsets.UTF_8))
            val encoder = Base64.getEncoder()
            HostKey("|1|${encoder.encodeToString(salt)}|${encoder.encodeToString(hash)}", HostKey.GUESS, Base64.getDecoder().decode(hostkey.key))
        }
        inner.add(stored, ui)
    }

    override fun remove(host: String, type: String?) = inner.remove(host, type)
    override fun remove(host: String, type: String?, key: ByteArray?) = inner.remove(host, type, key)
    override fun getKnownHostsRepositoryID(): String? = inner.knownHostsRepositoryID
    override fun getHostKey(): Array<HostKey>? = inner.hostKey
    override fun getHostKey(host: String?, type: String?): Array<HostKey>? = inner.getHostKey(host, type)
}
