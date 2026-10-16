package com.adamchesters.dazedev.mobile.data

enum class AuthMethod {
    PrivateKey,
    Password,
    TailscaleSsh,
}

data class ConnectionProfile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.PrivateKey,
    val privateKeyUri: String? = null,
    val privateKeyName: String? = null,
    val workingDirectory: String = "",
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val name: String = "",
    val socket: String = "",
    val fingerprint: String = "",
    /** The private key is stored encrypted in app storage instead of read from [privateKeyUri]. */
    val keyStored: Boolean = false,
    val rememberPassphrase: Boolean = false,
) {
    fun validationError(secret: String): String? = problem(secret.isEmpty())

    fun validationError(secret: CharArray): String? = problem(secret.isEmpty())

    private fun problem(secretEmpty: Boolean): String? =
        when {
            host.isBlank() || host.any { it.isWhitespace() || it.isISOControl() } ||
                host.contains('/') || host.contains('@') -> {
                "Enter a hostname or IP address."
            }

            port !in 1..65_535 -> {
                "Enter a valid SSH port."
            }

            username.isBlank() || username.any { it.isWhitespace() || it.isISOControl() } -> {
                "Enter the SSH username."
            }

            authMethod == AuthMethod.PrivateKey && privateKeyUri.isNullOrBlank() && !keyStored -> {
                "Choose an SSH private key."
            }

            authMethod == AuthMethod.Password && secretEmpty -> {
                "Enter the SSH password."
            }

            else -> {
                null
            }
        }
}
