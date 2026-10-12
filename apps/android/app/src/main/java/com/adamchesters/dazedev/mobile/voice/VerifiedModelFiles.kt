package com.adamchesters.dazedev.mobile.voice

import android.content.Context
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.ModelCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** Immutable pins for the already-supported Medium revision; no SDK unverified load/download. */
internal class VerifiedModelFiles(private val context: Context) {
    private val manifest = JSONObject(context.assets.open("models/moonshine-medium.json").bufferedReader().use {it.readText()})
    private val directory = File(context.noBackupFilesDir, "verified-models/medium-${manifest.getString("revision")}")
    private val legacy = ModelCache.directoryFor(context,ModelSpec.stt("en",JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,false),null)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() {cancelled=true;connection?.disconnect()}
    private fun checkCancelled() {if(cancelled || Thread.currentThread().isInterrupted)throw InterruptedException("Model preparation cancelled")}
    private fun valid(file:File, pin:JSONObject):Boolean = ModelFileIntegrity.matches(file,pin.getLong("size"),pin.getString("sha256"),::checkCancelled)
    fun present():Boolean = runCatching {
        val files=manifest.getJSONArray("files")
        (0 until files.length()).all {i->val pin=files.getJSONObject(i);valid(File(directory,pin.getString("name")),pin) || valid(File(legacy,pin.getString("name")),pin)}
    }.getOrDefault(false)
    fun prepare(progress:(Float,String)->Unit):File {
        checkCancelled();directory.mkdirs()
        val files=manifest.getJSONArray("files")
        for(i in 0 until files.length()) {
            checkCancelled();val pin=files.getJSONObject(i);val name=pin.getString("name")
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
            val target=File(directory,name)
            if(valid(target,pin)){progress((i+1f)/files.length(),name);continue}
            val partial=File(directory,"$name.partial")
            val old=File(legacy,name)
            if(valid(old,pin)) {
                try {
                    old.inputStream().use {input->partial.outputStream().use {output->val bytes=ByteArray(65536);while(true){checkCancelled();val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n)}}}
                    check(valid(partial,pin));checkCancelled();check(partial.renameTo(target))
                } finally {partial.delete()}
                progress((i+1f)/files.length(),name);continue
            }
            val url=URL(pin.getString("url"));require(url.protocol=="https" && url.host=="download.moonshine.ai")
            val http=url.openConnection() as HttpURLConnection
            http.connectTimeout=10000;http.readTimeout=10000;http.instanceFollowRedirects=false
            connection=http
            try {
                checkCancelled();require(http.responseCode==200){"Model download failed; retry preparation."}
                http.inputStream.use {input->partial.outputStream().use {output->
                    val bytes=ByteArray(65536);var written=0L
                    while(true){checkCancelled();val n=input.read(bytes);if(n<0)break;written+=n;require(written<=pin.getLong("size")){"Model download exceeded expected size"};output.write(bytes,0,n);progress((i+written.toFloat()/pin.getLong("size"))/files.length(),name)}
                }}
                require(valid(partial,pin)){"Model file is damaged. Retry to repair the local cache."}
                checkCancelled();check(partial.renameTo(target)){"Could not save the verified model"}
            } finally {connection=null;http.disconnect();partial.delete()}
        }
        checkCancelled();return directory
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
