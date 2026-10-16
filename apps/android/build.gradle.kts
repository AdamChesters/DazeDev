buildscript {
    repositories {
        google()
    }
    dependencies {
        // AGP 8.13 bundles an R8 that cannot read Kotlin 2.4 metadata; this newer R8 can.
        classpath("com.android.tools:r8:9.5.23")
    }
}

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
