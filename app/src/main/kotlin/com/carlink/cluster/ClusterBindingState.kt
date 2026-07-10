package com.carlink.cluster

/**
 * Cross-boundary flag tracking whether a live [ClusterMainSession] currently holds the
 * Templates Host binding. MainActivity cannot directly query the out-of-process
 * CarAppActivity session, so this shared object substitutes for that lookup.
 *
 * Writes (ClusterMainSession only):
 * - `true` in onCreateScreen when a session starts.
 * - `false` in the session's onDestroy observer, but only for the *primary* session
 *   (secondary sessions from the primary/secondary multiplexing do not touch the flag).
 *
 * Reads (MainActivity.launchCarAppActivity):
 * - If `true`, the launch is deferred and retried after 4s, preventing a second
 *   CarAppActivity from being started while the Host is still tearing down the old
 *   session (which would cause the Host to reject the new bind).
 *
 * @Volatile: onDestroy can be dispatched from a Host-owned thread, so the MainActivity
 * UI-thread read must see the write without CPU-cache staleness.
 */
object ClusterBindingState {
    @Volatile
    var sessionAlive = false
}
