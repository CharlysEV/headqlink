package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAddressRecoveryPolicyTest {

    private fun watch(
        nativeOwner: Boolean = true,
        fixedByUser: Boolean = false,
        bssidUsable: Boolean = false,
        bssidIsGroupsOwn: Boolean = true,
        alreadyWatching: Boolean = false,
    ) = GroupAddressRecoveryPolicy.shouldWatch(nativeOwner, fixedByUser, bssidUsable, bssidIsGroupsOwn, alreadyWatching)

    @Test
    fun `a masked address on our own group is watched`() {
        assertTrue(watch())
    }

    @Test
    fun `a stand-in address is watched, because it cannot be graded`() {
        assertTrue(watch(bssidUsable = true, bssidIsGroupsOwn = false))
    }

    @Test
    fun `the group's own readable address needs no watcher`() {
        assertFalse(watch(bssidUsable = true, bssidIsGroupsOwn = true))
    }

    @Test
    fun `a typed address, a client role or a running watcher start nothing`() {
        assertFalse(watch(fixedByUser = true))
        assertFalse(watch(nativeOwner = false))
        assertFalse(watch(alreadyWatching = true))
    }
}
