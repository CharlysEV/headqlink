package com.andrerinas.openheadunit.aap

/**
 * headqlink: commands that reach `AapService` after a stop was already accepted.
 *
 * Every `startForegroundService()` puts the service on a promise to call `startForeground()`. If the
 * service is brought down while one of those starts is still queued (its `onStartCommand` not run
 * yet), Android does not wait the five seconds: it kills the app on the spot with
 * `ForegroundServiceDidNotStartInTimeException` (seen 2026-10-07 on «Desconectar»: the stop path had
 * already dropped the foreground state, and an unconditional `stopSelf()` raced a queued start).
 *
 * So the service stops with `stopSelf(lastStartId)`, which Android ignores while a newer start is
 * queued, and every later command first calls `startForeground()` (top of `onStartCommand`, as
 * always) and then asks [afterStopAccepted] what to do with itself.
 */
object ServiceStopRacePolicy {

    enum class Next {
        /** No stop accepted: handle the command as usual. */
        HANDLE,

        /** A stop was accepted and nothing is left to wait for: stop now with this command's id. */
        STOP_NOW,

        /** A stop was accepted and its teardown is still running: it stops the service when done. */
        LEAVE_TO_TEARDOWN,
    }

    /**
     * @param stopAccepted a stop (`ACTION_STOP_SERVICE`) was already handled by this instance.
     * @param teardownPending that stop is still waiting for its wireless teardown.
     */
    fun afterStopAccepted(stopAccepted: Boolean, teardownPending: Boolean): Next = when {
        !stopAccepted -> Next.HANDLE
        teardownPending -> Next.LEAVE_TO_TEARDOWN
        else -> Next.STOP_NOW
    }

    /**
     * Whether `onTaskRemoved` may restart the service with `startForegroundService()`. Not once a stop
     * was accepted: the user asked for it to go, and the restart is exactly the queued start a
     * stopping service then dies on.
     */
    fun restartOnTaskRemoved(stopAccepted: Boolean): Boolean = !stopAccepted
}
