package com.adamchesters.dazedev.mobile.ssh

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

object Secrets {
    /** UTF-8 bytes of [chars] without an intermediate String; the caller clears the result. */
    fun utf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }
}
