package com.adamchesters.dazedev.mobile.host

import java.util.Base64

class HostFrames(nonce: String) {
    private val prefix = "DAZED:$nonce:"
    private val body = StringBuilder()
    private var next = 0
    private var total = 0
    fun line(line: String): String? {
        if (!line.startsWith(prefix)) return null
        val match = FRAME.matchEntire(line.substring(prefix.length)) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: error("Invalid frame index")
        val count = match.groupValues[2].toIntOrNull() ?: error("Invalid frame count")
        require(count in 1..MAX_FRAMES && index == next && (index == 0 || count == total)) { "Out-of-order host frame" }
        if (index == 0) total = count
        body.append(match.groupValues[3])
        require(body.length <= MAX_FRAMES * 48) { "Host reply is too large" }
        next++
        if (next != total) return null
        val decoded = Base64.getDecoder().decode(body.toString())
        body.setLength(0); next = 0; total = 0
        return decoded.toString(Charsets.UTF_8)
    }
    private companion object {
        // Matches the helper's own cap of 350,000 frames of 48 characters.
        const val MAX_FRAMES = 350_000
        val FRAME = Regex("@([0-9]+)/([0-9]+):([A-Za-z0-9+/=]*)")
    }
}

/** Splits a byte stream into protocol lines without reading one byte at a time. */
class HostLines(private val maxLine: Int = 4096) {
    private val line = java.io.ByteArrayOutputStream()
    fun feed(bytes: ByteArray, count: Int, onLine: (String) -> Unit) {
        var start = 0
        for (i in 0 until count) {
            if (bytes[i].toInt() != 10) continue
            append(bytes, start, i - start)
            onLine(line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r'))
            line.reset()
            start = i + 1
        }
        append(bytes, start, count - start)
    }
    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        require(line.size() + length < maxLine) { "Host protocol line too long" }
        line.write(bytes, offset, length)
    }
}

/** Shell and Python checks that run before the helper. */
object HostLoader {
    // Kept to syntax that old Python 3 releases can parse, so the version check can report.
    val script = "import sys;n=sys.argv[1];v=sys.version_info;sys.stdout.write('DAZED:'+n+':python:%d.%d\\n'%(v[0],v[1]));sys.stdout.flush();" +
        "v<(3,9) and sys.exit(3);import os,shutil;" +
        "shutil.which('tmux',path=os.path.expanduser('~/.local/bin')+os.pathsep+os.environ.get('PATH','')) or (print('DAZED:'+n+':no-tmux',flush=True),sys.exit(4));" +
        "import base64;print('DAZED:'+n+':bootstrap',flush=True);" +
        "exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))"

    /** Returns the found version when it is too old, else null. */
    fun tooOld(line: String, nonce: String): String? {
        val version = line.removePrefix("DAZED:$nonce:python:").takeIf { it != line } ?: return null
        val parts = version.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 2) return version
        return if (parts[0] < 3 || (parts[0] == 3 && parts[1] < 9)) version else null
    }

    fun closedMessage(stderr: String, exitStatus: Int, host: String): String {
        val detail = stderr.lines().map(String::trim).filter(String::isNotEmpty).takeLast(3).joinToString(" ").take(400)
        return when {
            exitStatus == 127 || Regex("python3.*(not found|No such file)").containsMatchIn(stderr) ->
                "Python 3 was not found on $host. Install Python 3.9 or newer and tmux, then connect again."
            detail.isNotEmpty() -> "The DazeDev helper stopped on $host: $detail"
            else -> "Host stream closed. Reconnect to continue."
        }
    }
}

/** Keeps the last bytes a command wrote to stderr. */
class TailBuffer(private val limit: Int = 4096) : java.io.OutputStream() {
    private val bytes = ArrayDeque<Byte>()
    @Synchronized override fun write(value: Int) {
        bytes.addLast(value.toByte())
        if (bytes.size > limit) bytes.removeFirst()
    }
    @Synchronized override fun write(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) write(data[i].toInt())
    }
    @Synchronized fun text(): String = String(bytes.toByteArray(), Charsets.UTF_8)
}

class HostTimeoutException(method: String, timeoutMs: Long) :
    Exception("The host did not answer ($method) within ${timeoutMs / 1000} s. Check the host, then retry.")
