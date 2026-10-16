package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileMergeTest {
    private fun fixture() = JSONObject(requireNotNull(javaClass.classLoader?.getResource("profile-v1.json")).readText())

    private fun copy(p: JSONObject) = JSONObject(p.toString())

    private fun workspace(p: JSONObject) = p.getJSONArray("workspaces").getJSONObject(0)

    @Test fun reapplyCombinesDifferentFieldsAndKeepsCurrentRevisionAndUnknownFields() {
        val base = fixture()
        val draft = copy(base)
        workspace(draft).put("name", "Phone edit")
        val current = copy(base).put("revision", 8).put("newHostField", JSONObject().put("future", true))
        workspace(current).put("colors", JSONObject().put("background", "#1456a0"))
        val result = ProfileMerge.reapply(base, draft, current)
        assertEquals(8, result.getInt("revision"))
        assertEquals("Phone edit", workspace(result).getString("name"))
        assertEquals("#1456a0", workspace(result).getJSONObject("colors").getString("background"))
        assertTrue(result.getJSONObject("newHostField").getBoolean("future"))
        assertEquals(workspace(current).getJSONObject("layout").toString(), workspace(result).getJSONObject("layout").toString())
        assertEquals(7, base.getInt("revision"))
        assertFalse(draft.has("newHostField"))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesConcurrentRename() {
        val base = fixture()
        val draft = copy(base)
        workspace(draft).put("name", "Phone")
        val current = copy(base)
        workspace(current).put("name", "Desktop")
        ProfileMerge.reapply(base, draft, current)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesDeletionOfConcurrentlyEditedWorkspace() {
        val base = fixture()
        val draft = SharedProfile(base)
        draft.editWorkspace(workspace(base).getString("id"), remove = true)
        val current = copy(base)
        workspace(current).put("name", "Desktop")
        ProfileMerge.reapply(base, draft.document, current)
    }

    @Test fun preservesConcurrentPaneMetadataWhenEditingWorkspaceName() {
        val base = fixture()
        val draft = copy(base)
        workspace(draft).put("name", "Phone")
        val current = copy(base)
        workspace(current).getJSONArray("panes").getJSONObject(0).put("futureMetadata", "New")
        val result = ProfileMerge.reapply(base, draft, current)
        assertEquals("New", workspace(result).getJSONArray("panes").getJSONObject(0).getString("futureMetadata"))
    }

    @Test fun reappliesAddedTerminalWithoutReplacingNewHostPreferences() {
        val base = fixture()
        val draft = SharedProfile(base)
        draft.addSession(
            "mobile",
            "machine",
            JSONObject()
                .put("id", "$3")
                .put("stamp", "1:2:3")
                .put("name", "Added")
                .put("agent", "codex")
                .put("model", "test-model")
                .put("effort", "high")
                .put("identity", "signed-in-account")
                .put("operation", "operation-123"),
            "/work",
        )
        val current = copy(base).put("revision", 8)
        current.getJSONObject("preferences").put("futurePreference", "Keep")
        val result = ProfileMerge.reapply(base, draft.document, current)
        assertEquals(2, result.getJSONArray("workspaces").length())
        assertEquals("Keep", result.getJSONObject("preferences").getString("futurePreference"))
        val pane =
            result
                .getJSONArray("workspaces")
                .getJSONObject(1)
                .getJSONArray("panes")
                .getJSONObject(0)
        assertFalse(pane.getBoolean("shell"))
        assertEquals("test-model", pane.getString("model"))
        assertEquals("high", pane.getString("effort"))
        assertEquals("signed-in-account", pane.getString("identity"))
        assertEquals("operation-123", pane.getString("operation"))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesConcurrentArrayAdditionsWithUnclearOrder() {
        val base = fixture()
        val d = SharedProfile(base)
        val c = SharedProfile(base)

        fun add(
            p: SharedProfile,
            name: String,
        ) = p.addSession("mobile", "machine", JSONObject().put("id", "$3").put("stamp", "1:2:3").put("name", name), "")
        add(d, "Phone")
        add(c, "Desktop")
        ProfileMerge.reapply(base, d.document, c.document)
    }
}
