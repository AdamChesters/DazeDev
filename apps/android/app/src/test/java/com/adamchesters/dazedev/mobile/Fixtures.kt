package com.adamchesters.dazedev.mobile

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/** Fixture tests skip on a plain run, but fail when the local check script asks for them. */
object Fixtures {
    val required get() = System.getenv("DAZEDEV_REQUIRE_FIXTURES") == "1"
    fun env(name: String, reason: String): String {
        val value = System.getenv(name)?.takeIf(String::isNotBlank)
        if (value == null) {
            if (required) fail("$reason: $name is missing")
            assumeTrue(reason, false)
        }
        return requireNotNull(value)
    }
}
