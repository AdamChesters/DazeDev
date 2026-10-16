package com.adamchesters.dazedev.mobile.host

/**
 * Protocol 2 replies and events. Each frame is u32 length, u8 flags (1 = last part),
 * u16 id length, id, data. Parts of different messages can interleave, so parts are
 * joined per id until the last one arrives.
 */
class MessageFrames(
    private val maxMessage: Int = MAX_MESSAGE,
) {
    private var buffer = ByteArray(65536)
    private var size = 0
    private val parts = HashMap<String, java.io.ByteArrayOutputStream>()

    /** Adds bytes from the channel and returns each message that is now complete. */
    fun push(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size,
    ): List<ByteArray> {
        append(bytes, offset, count)
        val messages = ArrayList<ByteArray>()
        var start = 0
        while (size - start >= 4) {
            val length = readInt(start)
            require(length in 3..maxMessage.toLong() + 1024) { "Invalid host message frame" }
            if (size - start - 4 < length) break
            val body = start + 4
            val idLength = ((buffer[body + 1].toInt() and 0xff) shl 8) or (buffer[body + 2].toInt() and 0xff)
            require(3 + idLength <= length) { "Invalid host message frame" }
            val id = String(buffer, body + 3, idLength, Charsets.UTF_8)
            val part = parts.getOrPut(id) { java.io.ByteArrayOutputStream() }
            val dataLength = length.toInt() - 3 - idLength
            require(part.size().toLong() + dataLength <= maxMessage) { "Host message exceeds limits" }
            part.write(buffer, body + 3 + idLength, dataLength)
            if (buffer[body].toInt() and 1 == 1) messages.add(parts.remove(id)!!.toByteArray())
            start = body + length.toInt()
        }
        System.arraycopy(buffer, start, buffer, 0, size - start)
        size -= start
        return messages
    }

    private fun readInt(at: Int): Long =
        ((buffer[at].toLong() and 0xff) shl 24) or ((buffer[at + 1].toLong() and 0xff) shl 16) or
            ((buffer[at + 2].toLong() and 0xff) shl 8) or (buffer[at + 3].toLong() and 0xff)

    private fun append(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        System.arraycopy(bytes, offset, buffer, size, count)
        size += count
    }

    companion object {
        /** The helper's own cap (MAX_MESSAGE in host.py). */
        const val MAX_MESSAGE = 64 * 1024 * 1024

        /** A protocol 2 request: u32 big-endian length, then the JSON bytes. */
        fun request(json: ByteArray): ByteArray {
            val n = json.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + json
        }

        /** Error text for the app. Protocol 2 sends {code, message}; a conflict keeps the "CONFLICT:" prefix. */
        fun errorText(error: Any?): String? =
            when (error) {
                null, org.json.JSONObject.NULL -> {
                    null
                }

                is org.json.JSONObject -> {
                    val message = error.optString("message").ifEmpty { "Host error" }
                    if (error.optString("code") == "conflict") "CONFLICT: $message" else message
                }

                else -> {
                    error.toString()
                }
            }
    }
}

/** Splits a byte stream into protocol lines without reading one byte at a time. */
class HostLines(
    private val maxLine: Int = 4096,
) {
    private val line = java.io.ByteArrayOutputStream()

    /**
     * Feeds bytes and calls [onLine] for each full line. When [onLine] returns false,
     * reading stops after that line and the number of bytes used is returned, so the
     * caller can pass the rest to a binary decoder. Otherwise returns [count].
     */
    fun feed(
        bytes: ByteArray,
        count: Int,
        onLine: (String) -> Boolean,
    ): Int {
        var start = 0
        for (i in 0 until count) {
            if (bytes[i].toInt() != 10) continue
            append(bytes, start, i - start)
            val more = onLine(line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r'))
            line.reset()
            start = i + 1
            if (!more) return start
        }
        append(bytes, start, count - start)
        return count
    }

    private fun append(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return
        require(line.size() + length < maxLine) { "Host protocol line too long" }
        line.write(bytes, offset, length)
    }
}

/** Shell and Python checks that run before the helper. */
object HostLoader {
    // Kept to syntax that old Python 3 releases can parse, so the version check can report.
    val script =
        "import sys;n=sys.argv[1];v=sys.version_info;sys.stdout.write('DAZED:'+n+':python:%d.%d\\n'%(v[0],v[1]));sys.stdout.flush();" +
            "v<(3,9) and sys.exit(3);import os,shutil;" +
            "shutil.which('tmux',path=os.path.expanduser('~/.local/bin')+os.pathsep+os.environ.get('PATH','')) or (print('DAZED:'+n+':no-tmux',flush=True),sys.exit(4));" +
            "import base64;print('DAZED:'+n+':bootstrap',flush=True);" +
            "exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))"

    /** Returns the found version when it is too old, else null. */
    fun tooOld(
        line: String,
        nonce: String,
    ): String? {
        val version = line.removePrefix("DAZED:$nonce:python:").takeIf { it != line } ?: return null
        val parts = version.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 2) return version
        return if (parts[0] < 3 || (parts[0] == 3 && parts[1] < 9)) version else null
    }

    fun closedMessage(
        stderr: String,
        exitStatus: Int,
        host: String,
    ): String {
        val detail =
            stderr
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeLast(3)
                .joinToString(" ")
                .take(400)
        return when {
            exitStatus == 127 || Regex("python3.*(not found|No such file)").containsMatchIn(stderr) -> {
                "Python 3 was not found on $host. Install Python 3.9 or newer and tmux, then connect again."
            }

            detail.isNotEmpty() -> {
                "The DazeDev helper stopped on $host: $detail"
            }

            else -> {
                "Host stream closed. Reconnect to continue."
            }
        }
    }
}

/** Keeps the last bytes a command wrote to stderr. */
class TailBuffer(
    private val limit: Int = 4096,
) : java.io.OutputStream() {
    private val bytes = ArrayDeque<Byte>()

    @Synchronized override fun write(value: Int) {
        bytes.addLast(value.toByte())
        if (bytes.size > limit) bytes.removeFirst()
    }

    @Synchronized override fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        for (i in offset until offset + length) write(data[i].toInt())
    }

    @Synchronized fun text(): String = String(bytes.toByteArray(), Charsets.UTF_8)
}

class HostTimeoutException(
    method: String,
    timeoutMs: Long,
) : Exception("The host did not answer ($method) within ${timeoutMs / 1000} s. Check the host, then retry.")
