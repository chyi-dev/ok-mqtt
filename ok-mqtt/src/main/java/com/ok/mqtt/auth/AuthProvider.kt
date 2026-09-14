package com.ok.mqtt.auth

import com.ok.mqtt.Auth
import java.util.concurrent.CompletableFuture

/**
 * Supplies / refreshes credentials before connect and reconnect.
 */
fun interface AuthProvider {
    /**
     * @return auth to use for the next CONNECT; complete exceptionally to abort reconnect
     */
    fun refresh(): CompletableFuture<Auth>
}

/**
 * Fixed credentials that never refresh.
 */
class StaticAuthProvider(private val auth: Auth) : AuthProvider {
    override fun refresh(): CompletableFuture<Auth> =
        CompletableFuture.completedFuture(auth)
}
