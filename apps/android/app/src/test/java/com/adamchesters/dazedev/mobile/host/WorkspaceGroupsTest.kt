package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceGroupsTest {
    @Test fun sixtySixClonesRemainReadableAfterReload() {
        val source = source()
        val folder = source.folders().single()
        val local = SharedProfile.empty()
        val group = local.createLocalWorkspace("Many")
        repeat(100) { local.clonePane(group, source, folder.id, folder.rows.single().paneId) }
        assertEquals(
            100,
            SharedProfile(local.document)
                .folders()
                .single()
                .rows.size,
        )
        val malformed = JSONObject(local.document.toString()).put("schema", 99)
        assertThrows(IllegalArgumentException::class.java) { SharedProfile(malformed) }
        assertEquals(
            100,
            local
                .folders()
                .single()
                .rows.size,
        )
    }

    @Test fun staleSavedTerminalCanBePointedAtTheRestartedSession() {
        val profile = source()
        val row =
            profile
                .folders()
                .single()
                .rows
                .single()
        assertTrue(profile.rebindPaneSession(row.paneId, "\$9", "new-stamp"))
        val updated =
            SharedProfile(profile.document)
                .folders()
                .single()
                .rows
                .single()
        assertEquals("\$9", updated.sessionId)
        assertEquals("new-stamp", updated.stamp)
        assertEquals(row.name, updated.name)
        assertFalse(profile.rebindPaneSession("missing", "\$9", "new-stamp"))
        assertThrows(IllegalArgumentException::class.java) { profile.rebindPaneSession(row.paneId, "other", "stamp") }
    }

    @Test fun hostWorkspaceTerminalsCanBeRemovedAndDesktopPlainShellsAreMarked() {
        val profile = source()
        val folder = profile.folders().single()
        profile.addSession("host", "machine:user:socket", JSONObject().put("id", "$2").put("stamp", "stamp-2").put("name", "Second"), "/dev", folder.id)
        // A desktop plain shell has no tmux session; a tmux shell has one.
        val shell =
            JSONObject()
                .put("id", "shell-pane")
                .put("hostId", "host")
                .put("hostKey", "machine:user:socket")
                .put("name", "Terminal")
                .put("sessionId", "")
                .put("shell", true)
        profile.workspace(folder.id).getJSONArray("panes").put(shell)
        val rows = profile.folders().single().rows
        assertEquals(listOf(false, false, true), rows.map { it.plainShell })
        profile.groupTabs(folder.id, rows.take(2).map { it.paneId })
        profile.removePane(folder.id, rows[0].paneId)
        val after = SharedProfile(profile.document).folders().single()
        assertEquals(listOf(rows[1].paneId, "shell-pane"), after.rows.map { it.paneId })
        assertTrue(after.tabGroups.isEmpty())
        assertFalse(
            profile
                .workspace(folder.id)
                .getJSONObject("layout")
                .toString()
                .contains(rows[0].paneId),
        )
        profile.removePane(folder.id, rows[1].paneId)
        profile.removePane(folder.id, "shell-pane")
        // An empty workspace keeps the layout the desktop uses for one.
        val layout = profile.workspace(folder.id).getJSONObject("layout")
        assertEquals("pane", layout.getString("kind"))
        assertEquals("", layout.getString("id"))
        assertTrue(
            SharedProfile(profile.document)
                .folders()
                .single()
                .rows
                .isEmpty(),
        )
        assertThrows(IllegalArgumentException::class.java) { profile.removePane(folder.id, "shell-pane") }
    }

    private fun source(): SharedProfile {
        val profile = SharedProfile.empty()
        profile.addSession("host", "machine:user:socket", JSONObject().put("id", "$1").put("stamp", "stamp").put("name", "Session"), "/dev")
        profile.document.put("future", JSONObject().put("preserved", true))
        return profile
    }

    @Test fun cloningRetainsSessionIdentityAndLeavesTheSourceUntouched() {
        val source = source()
        val before = source.document.toString()
        val folder = source.folders().single()
        val row = folder.rows.single()
        val local = SharedProfile.empty()
        val group = local.createLocalWorkspace("Group")
        local.clonePane(group, source, folder.id, row.paneId)
        val clone =
            local
                .folders()
                .single()
                .rows
                .single()
        assertNotEquals(row.paneId, clone.paneId)
        assertEquals(row.sessionId, clone.sessionId)
        assertEquals(row.stamp, clone.stamp)
        assertEquals(row.hostKey, clone.hostKey)
        assertEquals(before, source.document.toString())
        assertEquals(
            SharedProfile.LOCAL_OWNER,
            local.document
                .getJSONArray("workspaces")
                .getJSONObject(0)
                .getString("hostId"),
        )
    }

    @Test fun tabGroupsRoundTripWithoutChangingPanesLayoutOrUnknownFields() {
        val profile = source()
        val folder = profile.folders().single()
        profile.addSession("host", "machine:user:socket", JSONObject().put("id", "$2").put("stamp", "two").put("name", "Second"), "/dev", folder.id)
        val panes =
            profile
                .folders()
                .single()
                .rows
                .map { it.paneId }
        val layout =
            profile.document
                .getJSONArray("workspaces")
                .getJSONObject(0)
                .getJSONObject("layout")
                .toString()
        profile.groupTabs(folder.id, panes)
        val restored = SharedProfile(JSONObject(profile.document.toString()))
        assertEquals(
            panes,
            restored
                .folders()
                .single()
                .tabGroups
                .single()
                .panes,
        )
        assertEquals(
            layout,
            restored.document
                .getJSONArray("workspaces")
                .getJSONObject(0)
                .getJSONObject("layout")
                .toString(),
        )
        assertTrue(restored.document.getJSONObject("future").getBoolean("preserved"))
        restored.ungroupTab(folder.id, panes[0])
        assertTrue(
            restored
                .folders()
                .single()
                .tabGroups
                .isEmpty(),
        )
    }

    @Test fun removingLocalViewsRetainsOriginalsAndClearsEmptyTabGroups() {
        val source = source()
        val before = source.document.toString()
        val folder = source.folders().single()
        val local = SharedProfile.empty()
        val group = local.createLocalWorkspace("Group")
        repeat(2) { local.clonePane(group, source, folder.id, folder.rows.single().paneId) }
        val panes =
            local
                .folders()
                .single()
                .rows
                .map { it.paneId }
        local.groupTabs(group, panes)
        local.removeLocalView(group, panes[0])
        assertEquals(
            listOf(panes[1]),
            local
                .folders()
                .single()
                .rows
                .map { it.paneId },
        )
        assertTrue(
            local
                .folders()
                .single()
                .tabGroups
                .isEmpty(),
        )
        local.removeLocalView(group, panes[1])
        assertTrue(
            SharedProfile(local.document)
                .folders()
                .single()
                .rows
                .isEmpty(),
        )
        assertEquals(before, source.document.toString())
        assertThrows(IllegalArgumentException::class.java) { source.removeLocalView(folder.id, folder.rows.single().paneId) }
    }

    @Test fun discardedGroupsDoNotReservePanesAndGroupIdsStayUnique() {
        val profile = source()
        val folder = profile.folders().single()
        repeat(3) { i -> profile.addSession("host", "machine:user:socket", JSONObject().put("id", "session-$i").put("stamp", "stamp-$i").put("name", "More"), "/dev", folder.id) }
        val panes =
            profile
                .folders()
                .single()
                .rows
                .map { it.paneId }

        fun group(
            id: String,
            members: List<String>,
        ) = JSONObject().put("id", id).put("panes", JSONArray(members))
        profile.document.getJSONArray("workspaces").getJSONObject(0).put(
            "tabGroups",
            JSONArray()
                .put(group("singleton", listOf(panes[0], "missing")))
                .put(group("", panes.take(2)))
                .put(group("tabs", listOf(panes[0], panes[0], panes[1])))
                .put(group("tabs", panes.drop(2)))
                .put(group("other", panes.drop(2))),
        )
        assertEquals(listOf(TerminalTabGroup("tabs", panes.take(2)), TerminalTabGroup("other", panes.drop(2))), profile.folders().single().tabGroups)
    }
}
