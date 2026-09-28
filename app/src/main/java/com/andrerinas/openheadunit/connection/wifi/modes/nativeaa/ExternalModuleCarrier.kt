package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * A route that carries the Android Auto handshake over a head unit's external Bluetooth module
 * instead of this unit's own radio. The manager holds one of these while that route is up.
 */
interface ExternalModuleCarrier {

    /** Ask the module side to bring the phone's Android Auto link up. Safe from any thread. */
    fun requestWake()

    /** End the carrier and unblock whatever is reading. Safe from any thread. */
    fun close()
}
