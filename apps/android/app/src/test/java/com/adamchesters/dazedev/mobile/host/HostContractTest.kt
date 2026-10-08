package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HostContractTest {
    private fun fixture()=JSONObject(requireNotNull(javaClass.classLoader?.getResource("profile-v1.json")).readText())
    @Test fun preservesUnknownFieldsAndDesktopLayoutAcrossOwnerRebinding() {
        val source=fixture();val p=SharedProfile(source)
        val layout=source.getJSONArray("workspaces").getJSONObject(0).getJSONObject("layout").toString()
        p.rebindOwner("mobile-a","machine-a:user:socket-a")
        assertEquals(listOf("p2","p1"),p.folders().single().rows.map {it.paneId})
        assertEquals("desktop-b",p.folders().single().rows.first().hostId)
        assertEquals("mobile-a",p.folders().single().rows.last().hostId)
        assertEquals(layout,p.document.getJSONArray("workspaces").getJSONObject(0).getJSONObject("layout").toString())
        assertEquals("p1",p.document.getJSONArray("workspaces").getJSONObject(0).getString("focused"))
        assertEquals("yes",p.document.getJSONObject("futureTop").getString("keep"))
        assertTrue(p.document.getJSONArray("workspaces").getJSONObject(0).getBoolean("futureWorkspace"))
        assertEquals(2,p.document.getJSONArray("workspaces").getJSONObject(0).getJSONArray("panes").getJSONObject(0).getJSONArray("futurePane").length())
        assertEquals("desktop-a",source.getJSONArray("workspaces").getJSONObject(0).getString("hostId"))
    }
    @Test(expected=IllegalArgumentException::class) fun unknownSchemaIsRefused(){SharedProfile(fixture().put("schema",2))}
    @Test fun addingTerminalDoesNotChangeExistingWorkspace(){val p=SharedProfile(fixture());val before=p.document.getJSONArray("workspaces").getJSONObject(0).toString();p.addSession("mobile-a","machine-a:user:socket-a",JSONObject().put("id","$2").put("stamp","1:2:3").put("name","Added"),"/tmp");assertEquals(before,p.document.getJSONArray("workspaces").getJSONObject(0).toString());assertEquals(2,p.folders().size)}
    @Test fun orderedWorkspacesSupportThirtyTerminalsWithoutChangingLayoutOrFocus() {
        val p=SharedProfile(fixture());val first=p.document.getJSONArray("workspaces").getJSONObject(0)
        val originalFirst=first.toString();val id=first.getString("id")
        for(i in 3..30)p.addSession("mobile-a","machine-a:user:socket-a",JSONObject().put("id","$"+i).put("stamp","1:2:$i").put("name","Terminal $i"),"",id)
        assertEquals(30,p.folders().single().rows.size)
        assertEquals(listOf("p2","p1"),p.folders().single().rows.take(2).map {it.paneId})
        assertEquals("p1",first.getString("focused"))
        assertTrue(first.getBoolean("futureWorkspace"))
        assertNotEquals(originalFirst,first.toString())
        val sessions=p.folders().single().rows.map {it.sessionId}
        first.put("colors",JSONObject().put("futureColor",true).put("foreground","#eeeeee"))
        p.editWorkspace(id,name="Renamed",background="#1456a0")
        assertTrue(first.getJSONObject("colors").getBoolean("futureColor"))
        assertEquals("#eeeeee",first.getJSONObject("colors").getString("foreground"))
        assertEquals("#1456a0",p.folders().single().background)
        assertEquals(sessions,p.folders().single().rows.map {it.sessionId})
        p.editWorkspace(id,remove=true)
        assertTrue(p.folders().isEmpty())
        // Removing a profile row cannot call a transport or end a tmux session.
        assertEquals(7,p.revision)
    }
    @Test fun boundedNonceScopedFramesHandleLargeReplies(){val text="界".repeat(10000);val base=Base64.getEncoder().encodeToString(text.toByteArray());val chunks=base.chunked(48);val frames=HostFrames("test");assertNull(frames.line("DAZED:other:@0/1:e30="));var result:String?=null;chunks.forEachIndexed {i,c->result=frames.line("DAZED:test:@$i/${chunks.size}:$c")};assertEquals(text,result)}
    @Test(expected=IllegalArgumentException::class) fun reorderedFramesAreRefused(){HostFrames("test").line("DAZED:test:@1/2:eA==")}
    @Test(expected=IllegalArgumentException::class) fun excessiveFramesAreRefused(){HostFrames("test").line("DAZED:test:@0/999999:eA==")}
}
