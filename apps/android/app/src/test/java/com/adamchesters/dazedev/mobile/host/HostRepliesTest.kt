package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostRepliesTest {
    @Test fun readsBuiltInAndCustomAgentsFromCapabilities() {
        val reply =
            JSONObject(
                """
                {"agents":[
                  {"id":"codex","name":"Codex","version":"1.0","models":[{"id":"gpt-x","efforts":["low","high"]}],"modelFlag":true,"effortFlag":true,"prompt":true,"custom":false},
                  {"id":"amp","name":"Amp","version":"","models":[],"modelFlag":false,"effortFlag":false,"prompt":false,"custom":false},
                  {"id":"house-bot","name":"House Bot","version":"2.0","models":[],"modelFlag":false,"effortFlag":false,"prompt":false,"custom":true,"futureField":1}
                ],"protocol":1,"transport":"ssh-tmux","features":["custom-agents"]}
                """.trimIndent(),
            )
        val agents = HostReplies.agents(reply)
        assertEquals(listOf("codex", "amp", "house-bot"), agents.map { it.id })
        assertEquals(listOf("Codex", "Amp", "House Bot"), agents.map { it.name })
        assertEquals(listOf(true, false, false), agents.map { it.prompt })
        assertEquals(listOf(false, false, true), agents.map { it.custom })
        assertEquals(listOf("low", "high"), agents[0].efforts["gpt-x"])
    }

    @Test fun olderHostsWithoutNamesOrPromptFlagsKeepWorking() {
        val reply = JSONObject("""{"agents":[{"id":"claude","version":"","models":[],"modelFlag":true,"effortFlag":false}]}""")
        val agent = HostReplies.agents(reply).single()
        assertEquals("claude", agent.name)
        assertTrue(agent.prompt)
        assertFalse(agent.custom)
    }

    @Test fun launchSendsOnlyTheAgentIdNotACommand() {
        val args = SessionLaunch("bot-1", "/home/me", "house-bot", operation = "op-12345678").arguments()
        assertEquals("house-bot", args.getString("agent"))
        assertEquals("", args.getString("brief"))
        assertFalse(args.has("command"))
        assertFalse(args.has("install"))
    }
}
