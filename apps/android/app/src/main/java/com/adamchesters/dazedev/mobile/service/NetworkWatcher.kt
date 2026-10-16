package com.adamchesters.dazedev.mobile.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.adamchesters.dazedev.mobile.host.HostRegistry
import kotlinx.coroutines.launch

/**
 * Turns default-network callbacks into "lost" and "back" events. Losing a network that is no longer
 * the default is ignored; "back" fires only after a loss.
 */
internal class NetworkTransitions(
    private val onLost: () -> Unit,
    private val onBack: () -> Unit,
) {
    private var current: Any? = null
    private var lost = false

    fun available(network: Any) {
        current = network
        if (!lost) return
        lost = false
        onBack()
    }

    fun lost(network: Any) {
        if (network != current) return
        current = null
        lost = true
        onLost()
    }
}

/** Tells the host registry when the phone loses or regains its network. Runs for the whole process. */
class NetworkWatcher(
    context: Context,
    private val hosts: HostRegistry,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val transitions = NetworkTransitions(hosts::networkLost, hosts::networkAvailable)

    fun start() {
        // Callbacks arrive on a system thread; the registry runs on its own scope.
        connectivity?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    hosts.scope.launch { transitions.available(network) }
                }

                override fun onLost(network: Network) {
                    hosts.scope.launch { transitions.lost(network) }
                }
            },
        )
    }
}
