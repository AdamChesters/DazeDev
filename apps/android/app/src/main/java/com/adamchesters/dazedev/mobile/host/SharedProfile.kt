package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject

data class TerminalRow(val paneId: String, val name: String, val sessionId: String, val stamp: String,
    val hostId: String, val hostKey: String, val directory: String)
data class WorkspaceFolder(val id: String, val name: String, val rows: List<TerminalRow>, val background: String = "")

class SharedProfile(document: JSONObject) {
    val document = JSONObject(document.toString())
    val revision: Int get() = document.getInt("revision")
    init {
        require(document.getInt("schema") == 1) { "Unsupported workspace schema. Existing host data is preserved." }
        require(revision >= 0 && document.optJSONArray("workspaces") != null && document.optJSONObject("preferences") != null) { "Invalid host profile. Existing host data is preserved." }
        folders()
    }
    fun rebindOwner(hostId: String, fingerprint: String) {
        val workspaces = document.getJSONArray("workspaces")
        for (i in 0 until workspaces.length()) {
            val w = workspaces.getJSONObject(i);val old = w.getString("hostId");w.put("hostId",hostId)
            val panes = w.getJSONArray("panes")
            for (j in 0 until panes.length()) {
                val p = panes.getJSONObject(j)
                if (p.optString("hostId") == old) p.put("hostId",hostId)
                if (p.optString("hostKey") == fingerprint) p.put("hostId",hostId)
                else if (p.optString("hostId") == hostId) p.put("hostKey",fingerprint)
            }
        }
    }
    fun folders(): List<WorkspaceFolder> {
        val w = document.getJSONArray("workspaces")
        return (0 until w.length()).map { i ->
            val folder=w.getJSONObject(i);val panes=folder.getJSONArray("panes")
            val records=(0 until panes.length()).map { panes.getJSONObject(it) }
            val byId=records.associateBy { it.getString("id") }
            val order=mutableListOf<String>()
            fun walk(layout: JSONObject, depth: Int) {
                require(depth <= 64) { "Workspace layout too deep" }
                when(layout.optString("kind")) {
                    "pane" -> order.add(layout.getString("id"))
                    "split" -> {walk(layout.getJSONObject("first"),depth+1);walk(layout.getJSONObject("second"),depth+1)}
                }
            }
            folder.optJSONObject("layout")?.let {walk(it,0)}
            order.addAll(records.map {it.getString("id")}.filterNot {it in order})
            WorkspaceFolder(folder.getString("id"),folder.optString("name","Workspace"),order.distinct().mapNotNull { id ->
                byId[id]?.let {p -> TerminalRow(id,p.optString("name","Terminal"),p.optString("sessionId"),p.optString("stamp"),p.optString("hostId"),p.optString("hostKey"),p.optString("directory"))}
            },folder.optJSONObject("colors")?.optString("background").orEmpty())
        }
    }
    fun editWorkspace(id:String,name:String?=null,remove:Boolean=false,move:Int=0,background:String?=null) {
        val array=document.getJSONArray("workspaces")
        val rows=(0 until array.length()).map {array.getJSONObject(it)}.toMutableList()
        val index=rows.indexOfFirst {it.getString("id")==id}
        require(index>=0) {"Workspace no longer exists. Reload before editing."}
        val row=rows[index]
        if(name!=null){require(name.isNotBlank());row.put("name",name)}
        if(background!=null){require(background.matches(Regex("#[0-9a-fA-F]{6}")));val colors=row.optJSONObject("colors")?:JSONObject();colors.put("background",background);if(!colors.has("foreground"))colors.put("foreground","#edede8");row.put("colors",colors)}
        if(remove)rows.removeAt(index)
        else if(move!=0){rows.removeAt(index);rows.add((index+move).coerceIn(0,rows.size),row)}
        document.put("workspaces",JSONArray(rows))
    }
    fun addSession(hostId: String, fingerprint: String, session: JSONObject, directory: String, workspaceId:String="", workspaceName:String=session.getString("name")):String {
        val pid=java.util.UUID.randomUUID().toString()
        val pane=JSONObject().put("id",pid).put("hostId",hostId).put("hostKey",fingerprint).put("sessionId",session.getString("id"))
            .put("stamp",session.getString("stamp")).put("name",session.getString("name")).put("directory",directory)
            .put("shell",session.optString("agent","shell")=="shell").put("repo","").put("identity",session.optString("identity")).put("agent",session.optString("agent","shell"))
            .put("model",session.optString("model")).put("effort",session.optString("effort")).put("operation",session.optString("operation"))
        val workspaces=document.getJSONArray("workspaces")
        if(workspaceId.isNotBlank()) {
            val existing=(0 until workspaces.length()).map {workspaces.getJSONObject(it)}.firstOrNull {it.getString("id")==workspaceId}
                ?:error("Workspace no longer exists. Reload before adding a terminal.")
            existing.getJSONArray("panes").put(pane)
            val previous=existing.getJSONObject("layout")
            existing.put("layout",JSONObject().put("kind","split").put("id",java.util.UUID.randomUUID().toString()).put("axis","row").put("ratio",0.5)
                .put("first",previous).put("second",JSONObject().put("kind","pane").put("id",pid)))
        } else workspaces.put(JSONObject().put("id",java.util.UUID.randomUUID().toString())
            .put("hostId",hostId).put("name",workspaceName).put("panes",JSONArray().put(pane))
            .put("layout",JSONObject().put("kind","pane").put("id",pid)).put("focused",pid))
        return if(workspaceId.isNotBlank())workspaceId else workspaces.getJSONObject(workspaces.length()-1).getString("id")
    }
}
