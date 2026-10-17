package com.adamchesters.dazedev.mobile.host

/** How long host connections stay open after the app leaves the screen. */
enum class BackgroundWindow(
    /** Null: no time limit. Zero: no background service at all. */
    val millis: Long?,
    val label: String,
) {
    Off(0, "Off"),
    FiveMinutes(5 * 60_000L, "5 minutes"),
    FifteenMinutes(15 * 60_000L, "15 minutes"),
    OneHour(60 * 60_000L, "1 hour"),
    UntilDisconnect(null, "Until I disconnect"),
    ;

    /** Whether a foreground service keeps connections open while the app is in the background. */
    val keepsConnections get() = this != Off

    companion object {
        val Default = FiveMinutes

        /**
         * Reads the saved setting. Before this setting existed there was an on/off "keep connected"
         * switch: on becomes [UntilDisconnect], off (or never set) becomes [Default].
         */
        fun stored(
            name: String?,
            legacyKeepConnected: Boolean,
        ): BackgroundWindow = entries.firstOrNull { it.name == name } ?: if (legacyKeepConnected) UntilDisconnect else Default
    }
}
