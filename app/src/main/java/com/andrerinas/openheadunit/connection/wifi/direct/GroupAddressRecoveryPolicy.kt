package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether a group's address is worth waiting for after group info. The interface and its IPv6
 * link-local can land seconds after the callback, so one watcher per group re-asks for group info
 * once it appears, and the identity verdict is then graded on the address actually sent.
 */
object GroupAddressRecoveryPolicy {

    /** How long the watcher waits for the address before saying it cannot be read. */
    const val WINDOW_SECONDS = 15

    fun shouldWatch(
        nativeOwner: Boolean,
        fixedByUser: Boolean,
        bssidUsable: Boolean,
        bssidIsGroupsOwn: Boolean,
        alreadyWatching: Boolean,
    ): Boolean = nativeOwner && !fixedByUser && !(bssidUsable && bssidIsGroupsOwn) && !alreadyWatching
}
