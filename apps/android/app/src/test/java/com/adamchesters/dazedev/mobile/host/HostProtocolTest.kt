package com.adamchesters.dazedev.mobile.host

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class HostProtocolTest {
    @Test fun linesSplitAcrossReadsAndIgnoreCarriageReturns() {
        val lines=mutableListOf<String>();val splitter=HostLines()
        for(part in listOf("DAZED:a:boot","strap\r\nsecond\n","","third")) {val bytes=part.toByteArray();splitter.feed(bytes,bytes.size,lines::add)}
        splitter.feed("\n".toByteArray(),1,lines::add)
        assertEquals(listOf("DAZED:a:bootstrap","second","third"),lines)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOverlongLines() {
        val bytes=ByteArray(5000){'x'.code.toByte()};HostLines().feed(bytes,bytes.size){}
    }
    @Test fun framesReassembleWithTheSameRegexForEveryLine() {
        val payload=Base64.getEncoder().encodeToString("""{"id":"1","result":true}""".toByteArray())
        val frames=HostFrames("n")
        assertNull(frames.line("DAZED:n:@0/2:${payload.take(10)}"))
        assertEquals("""{"id":"1","result":true}""",frames.line("DAZED:n:@1/2:${payload.drop(10)}"))
        assertNull(frames.line("DAZED:other:@0/1:$payload"))
    }
    @Test fun loaderReportsOldPython() {
        assertEquals("3.8",HostLoader.tooOld("DAZED:n:python:3.8","n"))
        assertEquals("2.7",HostLoader.tooOld("DAZED:n:python:2.7","n"))
        assertNull(HostLoader.tooOld("DAZED:n:python:3.9","n"))
        assertNull(HostLoader.tooOld("DAZED:n:python:3.12","n"))
        assertTrue(HostLoader.script.contains("v<(3,9)"))
    }
    @Test fun closedHelperExplainsMissingPythonAndShowsStderr() {
        assertTrue(HostLoader.closedMessage("sh: 1: exec: python3: not found\n",127,"box").startsWith("Python 3 was not found on box"))
        assertEquals("The DazeDev helper stopped on box: line two Boom",HostLoader.closedMessage("one\nline two\n\nBoom\n",1,"box").replace("one ",""))
        assertEquals("Host stream closed. Reconnect to continue.",HostLoader.closedMessage("",0,"box"))
    }
    @Test fun tailBufferKeepsOnlyTheEnd() {
        val tail=TailBuffer(4);tail.write("abcdef".toByteArray())
        assertEquals("cdef",tail.text())
    }
}
