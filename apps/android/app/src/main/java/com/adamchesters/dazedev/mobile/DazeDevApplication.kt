package com.adamchesters.dazedev.mobile

import android.app.Application
import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.data.StoredCredentials
import com.adamchesters.dazedev.mobile.host.HelperSources
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.SshHostLinkFactory
import com.adamchesters.dazedev.mobile.service.KeepConnected
import com.adamchesters.dazedev.mobile.ui.BrowserLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Creates the host registry once per process, so host connections outlive any screen. */
class DazeDevApplication : Application() {
    lateinit var hosts: HostRegistry
        private set
    lateinit var credentials: StoredCredentials
        private set

    override fun onCreate() {
        super.onCreate()
        val store = ProfileStore(this)
        credentials = StoredCredentials(this)
        hosts =
            HostRegistry(
                store = store,
                links = SshHostLinkFactory(::knownHosts),
                helpers = ::helperSources,
                credentials = credentials,
                openBrowser = { url -> BrowserLauncher.open(this, url) },
            )
        hosts.keepConnected.value = store.keepConnected
        KeepConnected(this, hosts, hosts.keepConnected).start()
        val saved = store.loadAll().map { it.id }.toSet()
        hosts.scope.launch { runCatching { credentials.prune(saved) } }
    }

    fun knownHosts(id: String) = File(filesDir, "ssh/$id/known_hosts")

    private suspend fun helperSources() =
        withContext(Dispatchers.IO) {
            HelperSources(assets.open("host.py").use { it.readBytes() }, assets.open("browser.py").use { it.readBytes() })
        }
}
