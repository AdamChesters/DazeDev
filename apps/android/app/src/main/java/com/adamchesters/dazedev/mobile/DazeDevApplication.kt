package com.adamchesters.dazedev.mobile

import android.app.Application
import androidx.core.net.toUri
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.host.HelperSources
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.SshHostLinkFactory
import com.adamchesters.dazedev.mobile.ui.BrowserLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Creates the host registry once per process, so host connections outlive any screen. */
class DazeDevApplication : Application() {
    lateinit var hosts: HostRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        hosts =
            HostRegistry(
                store = ProfileStore(this),
                links = SshHostLinkFactory(::knownHosts),
                helpers = ::helperSources,
                readKey = ::readPrivateKey,
                openBrowser = { url -> BrowserLauncher.open(this, url) },
            )
    }

    fun knownHosts(id: String) = File(filesDir, "ssh/$id/known_hosts")

    private suspend fun helperSources() =
        withContext(Dispatchers.IO) {
            HelperSources(assets.open("host.py").use { it.readBytes() }, assets.open("browser.py").use { it.readBytes() })
        }

    private suspend fun readPrivateKey(profile: ConnectionProfile): ByteArray =
        withContext(Dispatchers.IO) {
            val uri = requireNotNull(profile.privateKeyUri).toUri()
            contentResolver.openInputStream(uri)?.use {
                val buffer = ByteArray(1_048_577)
                try {
                    var total = 0
                    while (total < buffer.size) {
                        val count = it.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    require(total <= 1_048_576) { "Private key exceeds 1 MiB." }
                    buffer.copyOf(total)
                } finally {
                    buffer.fill(0)
                }
            } ?: error("Could not open this private key.")
        }
}
