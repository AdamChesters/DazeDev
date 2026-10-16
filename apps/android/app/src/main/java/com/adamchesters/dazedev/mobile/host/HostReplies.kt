package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import org.json.JSONObject

/** Reads helper replies into app models. */
object HostReplies {
    fun session(s: JSONObject) = TmuxSession(s.getString("name"), s.getInt("windows"), s.getInt("attached"), 0, s.optString("agent", "shell"), s.getString("id"), s.getString("stamp"))

    fun agents(capabilities: JSONObject): List<AgentCapability> {
        val advertised = capabilities.getJSONArray("agents")
        return (0 until advertised.length()).map { index ->
            val agent = advertised.getJSONObject(index)
            val models = agent.getJSONArray("models")
            val efforts =
                (0 until models.length()).associate { i ->
                    val m = models.getJSONObject(i)
                    val e = m.getJSONArray("efforts")
                    m.getString("id") to (0 until e.length()).map { e.getString(it) }
                }
            AgentCapability(agent.getString("id"), agent.optString("version"), efforts.keys.toList(), efforts, agent.getBoolean("modelFlag"), agent.getBoolean("effortFlag"))
        }
    }
}
