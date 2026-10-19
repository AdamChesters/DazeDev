package com.adamchesters.dazedev.mobile.releases

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun release(
        version: String,
        tag: String = "android/v$version",
        url: String = "https://github.com/AdamChesters/DazeDev/releases/download/$tag/DazeDev-Mobile-$version.apk",
    ) = JSONObject()
        .put("tag_name", tag)
        .put("draft", false)
        .put("prerelease", version.contains('-'))
        .put("body", "Changes")
        .put("assets", JSONArray().put(JSONObject().put("name", "DazeDev-Mobile-$version.apk").put("size", 120000000).put("browser_download_url", url)))

    private fun desktopOnly(tag: String) =
        JSONObject()
            .put("tag_name", tag)
            .put("draft", false)
            .put("prerelease", false)
            .put("body", "Desktop")
            .put("assets", JSONArray().put(asset(tag, "DazeDev_9.0.0_windows-x64.zip")).put(asset(tag, "latest.json")))

    private fun asset(
        tag: String,
        name: String,
        size: Int = 100,
    ) = JSONObject().put("name", name).put("size", size).put("browser_download_url", "https://github.com/AdamChesters/DazeDev/releases/download/$tag/$name")

    @Test fun desktopReleasesWithoutAnApkCannotPromptAnAndroidUpdate() {
        val json =
            JSONArray()
                .put(desktopOnly("v9.0.0"))
                .put(release("0.1.0-alpha.9"))
                .put(release("0.1.0-alpha.10"))
                .toString()
        assertEquals("0.1.0-alpha.10", findMobileUpdate(json, "0.1.0-alpha.2")?.version)
        assertNull(findMobileUpdate(json, "1.0.0"))
        assertNull(findMobileUpdate(JSONArray().put(desktopOnly("v9.0.0")).toString(), "0.1.0-alpha.2"))
    }

    @Test fun jointReleasesOfferTheApkVersionNotTheDesktopVersion() {
        val tag = "v0.3.9-alpha.0"
        val joint = release("0.1.0-alpha.9", tag).also { it.getJSONArray("assets").put(asset(tag, "DazeDev_0.3.9-alpha.0_windows-x64.zip")).put(asset(tag, "latest.json")) }
        val update = findMobileUpdate(JSONArray().put(joint).toString(), "0.1.0-alpha.6")!!
        assertEquals("0.1.0-alpha.9", update.version)
        assertEquals("https://github.com/AdamChesters/DazeDev/releases/download/$tag/DazeDev-Mobile-0.1.0-alpha.9.apk", update.url)
        assertEquals("https://github.com/AdamChesters/DazeDev/releases/tag/$tag", update.page)
        assertNull(findMobileUpdate(JSONArray().put(joint).toString(), "0.1.0-alpha.9"))
    }

    @Test fun theNewestApkWinsAcrossJointAndAndroidReleases() {
        val experimental = release("0.1.0-alpha.11")
        val olderJoint = release("0.1.0-alpha.10", "v0.4.0")
        val newerJoint = release("0.1.0-alpha.12", "v0.5.0")
        assertEquals("0.1.0-alpha.11", findMobileUpdate(JSONArray().put(olderJoint).put(experimental).toString(), "0.1.0-alpha.9")?.version)
        assertEquals(
            "0.1.0-alpha.12",
            findMobileUpdate(
                JSONArray()
                    .put(newerJoint)
                    .put(experimental)
                    .put(olderJoint)
                    .toString(),
                "0.1.0-alpha.9",
            )?.version,
        )
        assertEquals("0.1.0-alpha.10", findMobileUpdate(JSONArray().put(olderJoint).put(release("0.1.0-alpha.10")).toString(), "0.1.0-alpha.9")?.version)
    }

    @Test fun jointReleasesNeedAValidApkNameOnTheSameRelease() {
        val tag = "v0.4.0"
        val badName = desktopOnly(tag).also { it.getJSONArray("assets").put(asset(tag, "DazeDev-Mobile-latest.apk", 120000000)) }
        assertNull(findMobileUpdate(JSONArray().put(badName).toString(), "0.1.0-alpha.9"))
        val elsewhere = release("0.1.0-alpha.10", tag, "https://github.com/AdamChesters/DazeDev/releases/download/v0.3.9/DazeDev-Mobile-0.1.0-alpha.10.apk")
        assertNull(findMobileUpdate(JSONArray().put(elsewhere).toString(), "0.1.0-alpha.9"))
        val stableDesktop = release("2.0.0-beta.1", "v2.0.0").put("prerelease", false)
        assertNull(findMobileUpdate(JSONArray().put(stableDesktop).toString(), "1.0.0"))
        assertNull(findMobileUpdate(JSONArray().put(release("0.1.0-alpha.10", "build-0.4.0")).toString(), "0.1.0-alpha.9"))
    }

    @Test fun jointReleasesUseTheirSha256SumsTxt() {
        val tag = "v0.4.0"
        val joint = release("0.1.0-alpha.10", tag).also { it.getJSONArray("assets").put(asset(tag, "SHA256SUMS.txt")) }
        assertEquals("https://github.com/AdamChesters/DazeDev/releases/download/$tag/SHA256SUMS.txt", findMobileUpdate(JSONArray().put(joint).toString(), "0.1.0-alpha.9")!!.checksums)
        val foreign = release("0.1.0-alpha.10", tag).also { it.getJSONArray("assets").put(JSONObject().put("name", "SHA256SUMS.txt").put("size", 100).put("browser_download_url", "https://evil.example/SHA256SUMS.txt")) }
        assertNull(findMobileUpdate(JSONArray().put(foreign).toString(), "0.1.0-alpha.9")!!.checksums)
    }

    @Test fun stableInstallsDoNotReceivePrereleasesAndDraftsAreIgnored() {
        assertNull(findMobileUpdate(JSONArray().put(release("2.0.0-beta.1")).toString(), "1.0.0"))
        assertNull(findMobileUpdate(JSONArray().put(release("2.0.0").put("draft", true)).toString(), "1.0.0"))
    }

    @Test fun assetsMustUseTheExactPublicRepositoryAndRelease() {
        for (url in listOf("http://github.com/AdamChesters/DazeDev/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk", "https://evil.example/file.apk", "https://github.com/Someone/Private/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk", "https://github.com/AdamChesters/DazeDev/releases/download/android/v2.0.0/DazeDev-Mobile-2.0.0.apk?token=secret")) {
            assertNull(findMobileUpdate(JSONArray().put(release("2.0.0", url = url)).toString(), "1.0.0"))
        }
    }

    @Test fun historyIncludesBothPlatformsWithoutBecomingAnUpdateFeed() {
        val json = """[{"platform":"desktop","version":"9.0.0","date":"2026-10-09","notes":"Desktop"},{"platform":"mobile","version":"1.0.0","date":"2026-10-01","notes":"Mobile"}]"""
        val entries = mergeHistory(json)
        assertEquals(listOf("desktop", "mobile"), entries.map { it.platform })
        assertEquals(entries, mergeHistory(historyJson(entries)))
        assertEquals(entries, mergeHistory("not json", entries))
        assertNull(findMobileUpdate(json, "0.1.0-alpha.2"))
    }

    @Test fun checksumAssetAndReleasePageComeFromTheSameRelease() {
        val tag = "android/v2.0.0"
        val good = "https://github.com/AdamChesters/DazeDev/releases/download/$tag/SHA256SUMS"
        val withSums = release("2.0.0").also { it.getJSONArray("assets").put(JSONObject().put("name", "SHA256SUMS").put("size", 100).put("browser_download_url", good)) }
        val update = findMobileUpdate(JSONArray().put(withSums).toString(), "1.0.0")!!
        assertEquals(good, update.checksums)
        assertEquals("https://github.com/AdamChesters/DazeDev/releases/tag/$tag", update.page)
        assertEquals("DazeDev-Mobile-2.0.0.apk", update.fileName)
        assertNull(findMobileUpdate(JSONArray().put(release("2.0.0")).toString(), "1.0.0")!!.checksums)
        val foreign = release("2.0.0").also { it.getJSONArray("assets").put(JSONObject().put("name", "SHA256SUMS").put("size", 100).put("browser_download_url", "https://evil.example/SHA256SUMS")) }
        assertNull(findMobileUpdate(JSONArray().put(foreign).toString(), "1.0.0")!!.checksums)
    }

    @Test fun checksumLinesMatchTheExactFileName() {
        val hash = "AB".repeat(32)
        val sums = "${"0".repeat(64)}  DazeDev-Mobile-2.0.0.apk.idsig\r\n$hash *DazeDev-Mobile-2.0.0.apk\r\nnot a line\n"
        assertEquals(hash.lowercase(), expectedSha256(sums, "DazeDev-Mobile-2.0.0.apk"))
        assertNull(expectedSha256(sums, "DazeDev-Mobile-2.0.1.apk"))
        assertNull(expectedSha256("${"0".repeat(63)}  a.apk", "a.apk"))
    }

    @Test fun downloadsStayOnGitHubOverHttps() {
        assertTrue(ReleaseDownload.allowed("https://github.com/AdamChesters/DazeDev/releases/download/x/y.apk"))
        assertTrue(ReleaseDownload.allowed("https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x"))
        assertFalse(ReleaseDownload.allowed("http://github.com/a"))
        assertFalse(ReleaseDownload.allowed("https://github.com.evil.example/a"))
        assertFalse(ReleaseDownload.allowed("https://user@github.com/a"))
        assertFalse(ReleaseDownload.allowed("https://evilgithubusercontent.com/a"))
    }
}
