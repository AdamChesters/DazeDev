package com.adamchesters.dazedev.mobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit device checks; this harness never downloads models or uses a microphone. */
@RunWith(AndroidJUnit4::class)
class MoonshineDeviceTest {
    @Test fun preparedModelCachePresence() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=InstrumentationRegistry.getArguments().getString("expectedPrepared")
        require(expected in listOf("true","false")) { "Pass expectedPrepared; this test never downloads or loads a model." }
        val controller=VoiceInputController(context, {}, {_,_->}, {})
        try {assertEquals(expected=="true",kotlinx.coroutines.runBlocking {controller.hasPreparedModel()})}
        finally {controller.destroy()}
    }
    @Test fun modelManifest() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manifest=Transcriber.getSttDependencies("en",listOf(TranscriberOption("model_arch",JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING.toString())))
        val parsed=JSONObject(manifest)
        assertTrue(parsed.getJSONArray("groups").length()>0)
        File(context.filesDir,"moonshine-medium-manifest.json").writeText(manifest)
    }
    @Test fun offlineInferenceWithExplicitlyStagedFixture() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("modelDirectory")!=null && args.getString("pcmFixture")!=null){"Stage a qualified model and PCM fixture, then pass modelDirectory and pcmFixture. This test never downloads either."}
        val raw=File(requireNotNull(args.getString("pcmFixture"))).readBytes()
        require(raw.size%2==0 && raw.size<=320_000){"Use at most 10 seconds of 16 kHz mono signed 16-bit PCM."}
        val buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val samples=FloatArray(raw.size/2){buffer.short/32768f}
        val engine=Transcriber()
        val result=JSONObject().put("abi",android.os.Build.SUPPORTED_ABIS.first()).put("samples",samples.size)
        try {
            var started=System.nanoTime()
            engine.loadFromFiles(requireNotNull(args.getString("modelDirectory")),JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING)
            assertTrue(engine.isLoaded)
            result.put("loadMilliseconds",(System.nanoTime()-started)/1_000_000)
            repeat(2) {index->
                started=System.nanoTime()
                val transcript=engine.transcribeWithoutStreaming(samples,16_000)
                val text=JNI.moonshineTranscriptToString(transcript)
                val expected=args.getString("expectedPhrase")
                if(expected!=null)assertTrue("Expected phrase missing from local transcript",transcript.text().contains(expected,ignoreCase=true))
                result.put("run${index}Milliseconds",(System.nanoTime()-started)/1_000_000).put("run${index}Text",text)
            }
            result.put("pssKiB",android.os.Debug.getPss())
            File(context.filesDir,"moonshine-device-result.json").writeText(result.toString())
        } finally {engine.close()}
    }
}
