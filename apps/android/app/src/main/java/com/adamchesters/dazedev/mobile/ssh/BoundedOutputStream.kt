package com.adamchesters.dazedev.mobile.ssh

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Consume excess output without allowing a remote server to exhaust app memory. */
internal class BoundedOutputStream(
    private val limit: Int = 1_048_576,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Volatile
    var exceeded: Boolean = false
        private set

    @Synchronized
    override fun write(value: Int) {
        if (buffer.size() < limit) buffer.write(value) else exceeded = true
    }

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val accepted = minOf(length, limit - buffer.size())
        buffer.write(bytes, offset, accepted)
        if (accepted < length) exceeded = true
    }

    @Synchronized
    fun text(): String = buffer.toString(StandardCharsets.UTF_8.name())
}
