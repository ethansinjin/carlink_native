package com.carlink.cluster

/**
 * Tracks whether a live ClusterMainSession exists.
 *
 * When RendererServiceBinder.terminate() kills the session (e.g., USB re-enumeration
 * triggers CarAppActivity destruction), this flag goes false so MainActivity knows
 * to re-launch CarAppActivity and re-establish the Templates Host binding chain.
 */
object ClusterBindingState {
    @Volatile
    var sessionAlive = false
}
