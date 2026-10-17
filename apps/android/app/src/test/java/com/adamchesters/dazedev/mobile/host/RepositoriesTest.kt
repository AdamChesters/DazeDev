package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RepositoriesTest {
    @Test fun inventoryListsRepositoriesAndLogins() {
        val value =
            JSONObject()
                .put("home", "/home/u")
                .put("warnings", JSONArray().put("A configured repository root is missing or inaccessible."))
                .put("identities", JSONArray().put(JSONObject().put("login", "first")).put(JSONObject().put("login", "second").put("active", true)).put(JSONObject().put("login", "bad login")))
                .put(
                    "repos",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("path", "/home/u/dev/zeta")
                                .put("name", "zeta")
                                .put("branch", "main")
                                .put("dirty", true)
                                .put("remote", "git@github.com:a/zeta.git"),
                        ).put(JSONObject().put("path", "/home/u/dev/Alpha").put("branch", "")),
                )
        val inventory = Repositories.inventory(value)
        assertEquals(listOf("Alpha", "zeta"), inventory.repositories.map { it.name })
        assertTrue(inventory.repositories[1].dirty)
        assertEquals(listOf("first", "second"), inventory.identities)
        assertEquals("second", inventory.activeIdentity)
        assertEquals(1, inventory.warnings.size)
    }

    @Test fun githubPagesAreFlattenedAndNamesChecked() {
        val pages =
            JSONArray()
                .put(JSONArray().put(JSONObject().put("full_name", "a/one").put("description", JSONObject.NULL)).put(JSONObject().put("full_name", "a/two").put("description", "Two")))
                .put(JSONArray().put(JSONObject().put("full_name", "bad name/x")).put(JSONObject().put("full_name", "a/one")))
        val repos = Repositories.github(pages)
        assertEquals(listOf("a/one", "a/two"), repos.map { it.fullName })
        assertEquals("", repos[0].description)
        assertEquals("Two", repos[1].description)
    }

    @Test fun clonesGoIntoTheFirstRootUnderTheRepositoryName() {
        assertEquals("/home/u/dev/two", Repositories.cloneDestination("/home/u/dev/", "a/two"))
        assertEquals("a/two", Repositories.cloneArgs("a/two", "/home/u/dev", "").getString("repo"))
        try {
            Repositories.cloneDestination("/r", "../../etc")
            fail("Expected an invalid repository name to be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun worktreesNeedAValidBranchAndAskBeforeRunningScripts() {
        assertEquals("/home/u/dev/app-feature-x", Repositories.worktreeDestination("/home/u/dev/app/", "feature/x"))
        assertNull(Repositories.branchProblem("feature/new-ui"))
        for (bad in listOf("", "-x", "a..b", "a b", "x.lock", "a~1", "a:b", "/a", "a/")) assertTrue(bad, Repositories.branchProblem(bad) != null)
        val first = Repositories.worktreeArgs("/r/app", "feature", "/r/app-feature", "")
        assertFalse(first.has("runScript"))
        assertTrue(Repositories.worktreeArgs("/r/app", "feature", "/r/app-feature", "", runScript = true).getBoolean("runScript"))
        try {
            Repositories.worktreeArgs("/r/app", "feature", "-rf", "")
            fail("Expected a folder starting with - to be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
