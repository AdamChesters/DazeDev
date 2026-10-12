package com.adamchesters.dazedev.mobile.releases

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun release(version:String,tag:String="android/v$version",url:String="https://github.com/AdamChesters/DazeDev/releases/download/$tag/DazeDev-Mobile-$version.apk") = JSONObject()
        .put("tag_name",tag).put("draft",false).put("prerelease",version.contains('-')).put("body","Changes")
        .put("assets",JSONArray().put(JSONObject().put("name","DazeDev-Mobile-$version.apk").put("size",120000000).put("browser_download_url",url)))
    @Test fun onlyAndroidReleasesCanPromptAnAndroidUpdate() {
        val json=JSONArray().put(release("9.0.0","v9.0.0")).put(release("0.1.0-alpha.9")).put(release("0.1.0-alpha.10")).toString()
        assertEquals("0.1.0-alpha.10",findMobileUpdate(json,"0.1.0-alpha.2")?.version)
        assertNull(findMobileUpdate(json,"1.0.0"))
    }
    @Test fun stableInstallsDoNotReceivePrereleasesAndDraftsAreIgnored() {
        assertNull(findMobileUpdate(JSONArray().put(release("2.0.0-beta.1")).toString(),"1.0.0"))
        assertNull(findMobileUpdate(JSONArray().put(release("2.0.0").put("draft",true)).toString(),"1.0.0"))
    }
    @Test fun assetsMustUseTheExactPublicRepositoryAndRelease() {
        for(url in listOf("http://github.com/AdamChesters/DazeDev/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk", "https://evil.example/file.apk", "https://github.com/Someone/Private/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk", "https://github.com/AdamChesters/DazeDev/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk?token=secret"))
            assertNull(findMobileUpdate(JSONArray().put(release("2.0.0",url=url)).toString(),"1.0.0"))
    }
    @Test fun historyIncludesBothPlatformsWithoutBecomingAnUpdateFeed() {
        val json="""[{"platform":"desktop","version":"9.0.0","date":"2026-10-09","notes":"Desktop"},{"platform":"mobile","version":"1.0.0","date":"2026-10-01","notes":"Mobile"}]"""
        val entries=mergeHistory(json)
        assertEquals(listOf("desktop","mobile"),entries.map {it.platform})
        assertEquals(entries,mergeHistory(historyJson(entries)))
        assertEquals(entries,mergeHistory("not json",entries))
        assertNull(findMobileUpdate(json,"0.1.0-alpha.2"))
    }
}
