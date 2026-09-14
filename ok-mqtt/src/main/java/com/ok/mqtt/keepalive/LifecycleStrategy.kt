package com.ok.mqtt.keepalive

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Adjusts behavior on process foreground / background transitions.
 * Observer registration must run on the main thread.
 */
class LifecycleStrategy(
    private val onForegroundChanged: (Boolean) -> Unit = {}
) : KeepaliveStrategy {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handle: KeepaliveHandle? = null
    private var observer: DefaultLifecycleObserver? = null

    override fun start(handle: KeepaliveHandle) {
        this.handle = handle
        runOnMain {
            // stop previous observer if any
            observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
            val obs = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    onBackground()
                }
            }
            observer = obs
            ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
            val state = ProcessLifecycleOwner.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.STARTED)) {
                onForeground()
            } else {
                onBackground()
            }
        }
    }

    override fun stop() {
        runOnMain {
            observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
            observer = null
            handle = null
        }
    }

    override fun onForeground() {
        onForegroundChanged(true)
        handle?.onLivenessCheckRequested()
    }

    override fun onBackground() {
        onForegroundChanged(false)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
