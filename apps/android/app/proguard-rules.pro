# JSch uses reflection for some algorithm implementations.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Moonshine's Java facade is called from Kotlin and loads its bundled JNI library.
-keep class ai.moonshine.voice.** { *; }
-dontwarn ai.moonshine.voice.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JSch's Bouncy Castle classes are loaded by name and reference only parts of Bouncy Castle.
-dontwarn org.bouncycastle.**
