package com.adamchesters.dazedev.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Raw control characters make git treat a source as binary and hide its diffs; use escapes such as '\u0000'. */
class SourceTextTest {
    @Test fun sourcesHaveNoControlCharacters() {
        // Unit tests run in the app module folder.
        val module = File("").absoluteFile
        val roots = listOf(File(module, "src"), File(module, "../terminal"), File(module, "../scripts"), File(module, "build.gradle.kts"), File(module, "../build.gradle.kts"))
        val sources =
            roots
                .flatMap { it.walkTopDown().filter(File::isFile).toList() }
                .filter { it.extension in setOf("kt", "kts", "js", "mjs", "py") }
        assertTrue("No sources found under $module", sources.size > 50)
        val found =
            sources.mapNotNull { file ->
                val bytes = file.readBytes()
                val bad = bytes.indices.filter { i -> bytes[i].toInt().let { (it in 0..31 && it != 9 && it != 10 && it != 13) || it == 127 } }
                if (bad.isEmpty()) null else "${file.relativeTo(module)} at byte ${bad.first()} (${bad.size} in all)"
            }
        assertEquals("Control characters in sources", emptyList<String>(), found)
    }
}
