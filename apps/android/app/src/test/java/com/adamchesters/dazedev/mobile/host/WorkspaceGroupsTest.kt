package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceGroupsTest {
    private fun source():SharedProfile {
        val profile=SharedProfile.empty()
        profile.addSession("host","machine:user:socket",JSONObject().put("id","$1").put("stamp","stamp").put("name","Session"),"/dev")
        profile.document.put("future",JSONObject().put("preserved",true))
        return profile
    }
    @Test fun cloningRetainsSessionIdentityAndLeavesTheSourceUntouched() {
        val source=source();val before=source.document.toString();val folder=source.folders().single();val row=folder.rows.single()
        val local=SharedProfile.empty();val group=local.createLocalWorkspace("Group")
        local.clonePane(group,source,folder.id,row.paneId)
        val clone=local.folders().single().rows.single()
        assertNotEquals(row.paneId,clone.paneId)
        assertEquals(row.sessionId,clone.sessionId);assertEquals(row.stamp,clone.stamp);assertEquals(row.hostKey,clone.hostKey)
        assertEquals(before,source.document.toString())
        assertEquals(SharedProfile.LOCAL_OWNER,local.document.getJSONArray("workspaces").getJSONObject(0).getString("hostId"))
    }
    @Test fun tabGroupsRoundTripWithoutChangingPanesLayoutOrUnknownFields() {
        val profile=source();val folder=profile.folders().single()
        profile.addSession("host","machine:user:socket",JSONObject().put("id","$2").put("stamp","two").put("name","Second"),"/dev",folder.id)
        val panes=profile.folders().single().rows.map {it.paneId}
        val layout=profile.document.getJSONArray("workspaces").getJSONObject(0).getJSONObject("layout").toString()
        profile.groupTabs(folder.id,panes)
        val restored=SharedProfile(JSONObject(profile.document.toString()))
        assertEquals(panes,restored.folders().single().tabGroups.single().panes)
        assertEquals(layout,restored.document.getJSONArray("workspaces").getJSONObject(0).getJSONObject("layout").toString())
        assertTrue(restored.document.getJSONObject("future").getBoolean("preserved"))
        restored.ungroupTab(folder.id,panes[0]);assertTrue(restored.folders().single().tabGroups.isEmpty())
    }
}
