package com.ok.mqtt.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks validated internet connectivity via [ConnectivityManager.NetworkCallback].
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _validated = MutableStateFlow(false)
    val validated: StateFlow<Boolean> = _validated.asStateFlow()

    private val pendingValidated = AtomicReference<CompletableFuture<Boolean>?>(null)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateValidated()
        }

        override fun onLost(network: Network) {
            updateValidated()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateValidated()
        }

        override fun onUnavailable() {
            setValidated(false)
        }
    }

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            connectivityManager.registerNetworkCallback(request, callback)
        }
        updateValidated()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        pendingValidated.getAndSet(null)?.cancel(false)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Completes when the default network has [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
     * Immediate if already validated.
     */
    fun awaitValidated(): CompletableFuture<Boolean> {
        if (_validated.value) {
            return CompletableFuture.completedFuture(true)
        }
        val existing = pendingValidated.get()
        if (existing != null && !existing.isDone) return existing
        val future = CompletableFuture<Boolean>()
        pendingValidated.set(future)
        // Re-check in case it flipped while creating the future
        if (_validated.value) {
            future.complete(true)
            pendingValidated.compareAndSet(future, null)
        }
        return future
    }

    fun isValidated(): Boolean = _validated.value

    private fun updateValidated() {
        val network = connectivityManager.activeNetwork
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val ok = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        setValidated(ok)
    }

    private fun setValidated(value: Boolean) {
        val previous = _validated.value
        _validated.value = value
        if (value) {
            pendingValidated.getAndSet(null)?.complete(true)
        }
        if (previous != value) {
            listeners.forEach { it(value) }
        }
    }
}
