package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject

/** Reapply a retained edit only when it does not overwrite a newer host edit. */
object ProfileMerge {
    private object Absent
    fun reapply(base:JSONObject,draft:JSONObject,current:JSONObject):JSONObject {
        listOf(base,draft,current).forEach {SharedProfile(it)}
        val revision=current.getInt("revision")
        val before=JSONObject(base.toString()).put("revision",revision)
        val desired=JSONObject(draft.toString()).put("revision",revision)
        return merge(before,desired,current,"profile",0) as JSONObject
    }
    private fun same(a:Any?,b:Any?):Boolean = when {
        a===Absent || b===Absent -> a===b
        a is JSONObject && b is JSONObject -> a.keySetCompat()==b.keySetCompat() && a.keySetCompat().all {same(a.get(it),b.get(it))}
        a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all {same(a.get(it),b.get(it))}
        else -> a==b
    }
    private fun JSONObject.keySetCompat()=keys().asSequence().toSet()
    private fun merge(base:Any?,draft:Any?,current:Any?,path:String,depth:Int):Any? {
        require(depth<=64){"Draft structure is too deep. Your draft is kept."}
        if(same(base,draft))return current
        if(same(base,current) || same(draft,current))return draft
        if(base is JSONObject && draft is JSONObject && current is JSONObject) {
            val result=JSONObject()
            for(key in base.keySetCompat()+draft.keySetCompat()+current.keySetCompat()) {
                val value=merge(if(base.has(key))base.get(key)else Absent,if(draft.has(key))draft.get(key)else Absent,if(current.has(key))current.get(key)else Absent,"$path.$key",depth+1)
                if(value!==Absent)result.put(key,value)
            }
            return result
        }
        if(base is JSONArray && draft is JSONArray && current is JSONArray) {
            fun records(a:JSONArray):LinkedHashMap<String,JSONObject>? {
                val out=linkedMapOf<String,JSONObject>()
                for(i in 0 until a.length()) {
                    val row=a.optJSONObject(i)?:return null
                    val id=row.optString("id");if(id.isBlank() || out.put(id,row)!=null)return null
                }
                return out
            }
            val b=records(base);val d=records(draft);val c=records(current)
            if(b!=null && d!=null && c!=null) {
                val order=when {
                    b.keys.toList()==d.keys.toList() -> c.keys.toList()
                    b.keys.toList()==c.keys.toList() || d.keys.toList()==c.keys.toList() -> d.keys.toList()
                    else -> error("Conflicting order at $path. Your draft is kept; reload and edit again.")
                }
                val values=(b.keys+d.keys+c.keys).associateWith {id->merge(b[id]?:Absent,d[id]?:Absent,c[id]?:Absent,"$path[$id]",depth+1)}
                return JSONArray().apply {order.forEach {id->values[id]?.let {if(it!==Absent)put(it)}}}
            }
        }
        error("Both devices changed $path. Your draft is kept; reload and edit again.")
    }
}
