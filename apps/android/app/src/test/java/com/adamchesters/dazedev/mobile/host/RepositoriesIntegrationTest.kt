package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.Fixtures
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Worktrees through the helper on the disposable real host fixture; the dirty checkout must stay as it was. */
class RepositoriesIntegrationTest {
    private fun git(
        folder: File,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", *args)
                .directory(folder)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    @Test fun worktreeCreatesABranchFolderAndLeavesTheDirtyCheckout(): Unit =
        runBlocking {
            val folder = File(Fixtures.env("DAZEDEV_TEST_REAL_FIXTURE", "Requires disposable real host fixture"))
            val port = JSONObject(File(folder, "fixture-ports.json").readText()).getInt("127.0.0.1")
            val socket = File(folder, "fixture-socket.txt").readText()
            val source = File(File(folder, "fixture-host-source.txt").readText()).readBytes()
            val known = File.createTempFile("dazedev-repos-test", "hosts")
            val prompts = HostKeyPromptCoordinator()
            val ssh = SshConnection(known, prompts)
            val trust = launch { prompts.challenge.filterNotNull().collect { prompts.answer(it.id, true) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HostClient(ssh, scope)
            val repo = File(folder, "home/repo-${UUID.randomUUID().toString().take(8)}")
            val destination = File(repo.parentFile, repo.name + "-feature-x")
            try {
                repo.mkdirs()
                git(repo, "init", "-q", "-b", "main")
                File(repo, "file.txt").writeText("one\n")
                git(repo, "add", "file.txt")
                git(repo, "commit", "-q", "-m", "first")
                File(repo, "file.txt").writeText("uncommitted\n")
                val key = File(folder, "fixture-key.pem").readBytes()
                try {
                    ssh.connect(ConnectionProfile("127.0.0.1", port, "key-user", AuthMethod.PrivateKey, "content://fixture/key"), "fixture-passphrase".toCharArray(), key)
                } finally {
                    key.fill(0)
                }
                client.start(source, socket)
                val args = Repositories.worktreeArgs(repo.absolutePath, "feature/x", Repositories.worktreeDestination(repo.absolutePath, "feature/x"), "")
                val info = Repositories.info(client.request("worktree", args, 120_000) as JSONObject)
                assertEquals(repo.canonicalPath, File(info.path).canonicalPath)
                assertEquals("main", info.branch)
                assertTrue(info.dirty)
                assertEquals("uncommitted\n", File(repo, "file.txt").readText())
                assertEquals("one\n", File(destination, "file.txt").readText())
                // A repository script is never run without an answer.
                File(repo, "scripts").mkdirs()
                File(repo, "scripts/new-worktree.sh").writeText("touch ran-script\n")
                val asked = client.request("worktree", Repositories.worktreeArgs(repo.absolutePath, "feature/y", destination.absolutePath + "-y", ""), 120_000) as JSONObject
                assertTrue(asked.getBoolean("needsConfirmation"))
                assertTrue(!File(repo, "ran-script").exists() && !File(destination.absolutePath + "-y").exists())
            } finally {
                client.close()
                ssh.disconnect()
                trust.cancelAndJoin()
                scope.cancel()
                known.delete()
                destination.deleteRecursively()
                repo.deleteRecursively()
            }
        }
}
