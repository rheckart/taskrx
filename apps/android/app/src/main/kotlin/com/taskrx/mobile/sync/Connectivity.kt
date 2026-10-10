package com.taskrx.mobile.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device can reach the network, as the sync code asks it. */
interface Connectivity {
    /** Null until the first reading. */
    val connected: StateFlow<Boolean?>

    fun isConnected(): Boolean
}

/**
 * [ConnectivityManager]-backed. "Connected" means a network that has validated internet
 * access, the same notion NetInfo's `isConnected` gave the Expo app.
 */
class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow<Boolean?>(null)

    override val connected: StateFlow<Boolean?> = state.asStateFlow()

    init {
        state.value = isConnected()
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = true
                }

                override fun onLost(network: Network) {
                    state.value = false
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            },
        )
    }

    override fun isConnected(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
