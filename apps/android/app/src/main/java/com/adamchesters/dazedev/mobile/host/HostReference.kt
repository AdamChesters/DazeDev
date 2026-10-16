package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import org.json.JSONObject

data class HostReference(
    val name: String = "",
    val kind: String = "ssh",
    val address: String = "",
    val user: String = "",
    val port: Int = 22,
    val socket: String = "",
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("name", name)
            .put("kind", kind)
            .put("address", address)
            .put("user", user)
            .put("port", port)
            .put("socket", socket)

    fun connectionProfile(): ConnectionProfile =
        ConnectionProfile(
            name = name,
            host = address,
            username = user,
            port = port,
            socket = socket,
            authMethod = if (kind == "tailscale") AuthMethod.TailscaleSsh else AuthMethod.PrivateKey,
        )

    companion object {
        fun fromJson(value: JSONObject): HostReference =
            HostReference(
                value.optString("name"),
                value.optString("kind", "ssh"),
                value.optString("address"),
                value.optString("user"),
                value.optInt("port", 22),
                value.optString("socket"),
            )

        fun fromProfile(profile: ConnectionProfile): HostReference =
            HostReference(
                profile.name,
                if (profile.authMethod == AuthMethod.TailscaleSsh) "tailscale" else "ssh",
                profile.host,
                profile.username,
                profile.port,
                profile.socket,
            )
    }
}
