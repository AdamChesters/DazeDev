package com.adamchesters.dazedev.mobile.voice

import android.content.Context
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.ModelCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** Immutable pins for the already-supported Medium revision; no SDK unverified load/download. */
internal class VerifiedModelFiles(private val context: Context) {
    private val manifest = JSONObject(context.assets.open("models/moonshine-medium.json").bufferedReader().use {it.readText()})
    private val directory = File(context.noBackupFilesDir, "verified-models/medium-${manifest.getString("revision")}")
    private val legacy = ModelCache.directoryFor(context,ModelSpec.stt("en",JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,false),null)
    private val marker = VerifiedMarker(File(directory, "verified.json"))
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() {cancelled=true;connection?.disconnect()}
    private fun checkCancelled() {if(cancelled || Thread.currentThread().isInterrupted)throw InterruptedException("Model preparation cancelled")}
    private fun valid(file:File, pin:JSONObject):Boolean = ModelFileIntegrity.matches(file,pin.getLong("size"),pin.getString("sha256"),::checkCancelled)
    /** Hashes a file once; later checks compare size and modification time with the saved result. */
    private fun verified(file:File, pin:JSONObject):Boolean {
        val size=pin.getLong("size");val hash=pin.getString("sha256")
        if(marker.matches(file,size,hash))return true
        if(!valid(file,pin))return false
        marker.record(file,hash)
        return true
    }
    fun present():Boolean = runCatching {
        val files=manifest.getJSONArray("files")
        (0 until files.length()).all {i->val pin=files.getJSONObject(i);verified(File(directory,pin.getString("name")),pin) || valid(File(legacy,pin.getString("name")),pin)}
    }.getOrDefault(false)
    fun prepare(progress:(Float,String)->Unit):File {
        checkCancelled();directory.mkdirs()
        val files=manifest.getJSONArray("files")
        for(i in 0 until files.length()) {
            checkCancelled();val pin=files.getJSONObject(i);val name=pin.getString("name")
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
            val target=File(directory,name)
            if(verified(target,pin)){progress((i+1f)/files.length(),name);continue}
            val partial=File(directory,"$name.partial")
            val old=File(legacy,name)
            if(valid(old,pin)) {
                try {
                    old.inputStream().use {input->partial.outputStream().use {output->val bytes=ByteArray(65536);while(true){checkCancelled();val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n)}}}
                    check(valid(partial,pin));checkCancelled();check(partial.renameTo(target))
                    marker.record(target,pin.getString("sha256"))
                } finally {partial.delete()}
                progress((i+1f)/files.length(),name);continue
            }
            val url=URL(pin.getString("url"));require(url.protocol=="https" && url.host=="download.moonshine.ai")
            val size=pin.getLong("size")
            ModelDownload.fetch(url,partial,size,::checkCancelled,{connection=it}) {written->progress((i+written.toFloat()/size)/files.length(),name)}
            connection=null
            if(!valid(partial,pin)){partial.delete();error("Model file is damaged. Retry to repair the local cache.")}
            checkCancelled();check(partial.renameTo(target)){"Could not save the verified model"}
            marker.record(target,pin.getString("sha256"))
        }
        checkCancelled();return directory
    }
}

/** Saved results of full hash checks, keyed by file name, size and modification time. */
internal class VerifiedMarker(private val file:File) {
    @Synchronized fun matches(target:File,size:Long,hash:String):Boolean {
        if(!target.isFile || target.length()!=size)return false
        val entry=read().optJSONObject(target.name)?:return false
        return entry.optLong("size")==size && entry.optLong("modified")==target.lastModified() && entry.optString("sha256")==hash
    }
    @Synchronized fun record(target:File,hash:String) {
        val all=read().put(target.name,JSONObject().put("size",target.length()).put("modified",target.lastModified()).put("sha256",hash))
        val temp=File(file.parentFile,file.name+".tmp");temp.writeText(all.toString())
        if(!temp.renameTo(file))temp.delete()
    }
    private fun read():JSONObject = runCatching {JSONObject(file.readText())}.getOrDefault(JSONObject())
}

/** Downloads into a partial file and resumes with an HTTP Range request when the server allows it. */
internal object ModelDownload {
    fun fetch(url:URL,partial:File,size:Long,checkCancelled:()->Unit,onConnection:(HttpURLConnection)->Unit={},progress:(Long)->Unit={}) {
        var start=if(partial.isFile && partial.length() in 1 until size)partial.length() else 0L
        if(start==0L)partial.delete()
        val http=url.openConnection() as HttpURLConnection
        http.connectTimeout=10000;http.readTimeout=10000;http.instanceFollowRedirects=false
        if(start>0)http.setRequestProperty("Range","bytes=$start-")
        onConnection(http)
        try {
            checkCancelled()
            val code=http.responseCode
            val resumed=start>0 && code==206 && http.getHeaderField("Content-Range")?.startsWith("bytes $start-")==true
            require(code==200 || resumed){"Model download failed; retry preparation."}
            if(!resumed)start=0L
            http.inputStream.use {input->FileOutputStream(partial,resumed).use {output->
                val bytes=ByteArray(65536);var written=start
                while(true) {
                    checkCancelled();val n=input.read(bytes);if(n<0)break
                    written+=n
                    if(written>size){output.close();partial.delete();error("Model download exceeded expected size")}
                    output.write(bytes,0,n);progress(written)
                }
            }}
        } finally {http.disconnect()}
    }
}

internal object ModelFileIntegrity {
    fun matches(file:File,size:Long,expected:String,checkCancelled:()->Unit={}):Boolean {
        if(!file.isFile || file.length()!=size)return false
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(65536);while(true){checkCancelled();val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it)}==expected
    }
}
