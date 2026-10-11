package com.headqlink.link

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class MediaFocusInterruptionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun webNavigationGuidanceDoesNotPauseOrAbandonFocus() {
        var pauses = 0
        var suspends = 0
        var resumes = 0
        val focus = MediaFocus(context, { pauses++ }, { suspends++ }, { resumes++ })
        val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        try {
            assertTrue(focus.acquire())
            assertFalse(audio.lastAudioFocusRequest.audioFocusRequest.willPauseWhenDucked())
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, pauses + suspends + resumes)
            assertNull(audio.lastAbandonedAudioFocusRequest)
        } finally { focus.close() }
    }

    @Test fun webCallRetainsFocusAndResumesOnlyOnGain() {
        var pauses = 0
        var suspends = 0
        var resumes = 0
        val focus = MediaFocus(context, { pauses++ }, { suspends++ }, { resumes++ })
        val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        try {
            assertTrue(focus.acquire())
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertTrue(focus.isInterrupted)
            assertEquals(1, suspends)
            assertNull(audio.lastAbandonedAudioFocusRequest)
            assertFalse(focus.acquire()) // An autoplay attempt cannot take focus back during the call.
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertFalse(focus.isInterrupted)
            assertEquals(1, resumes)
            assertEquals(0, pauses)
        } finally { focus.close() }
    }

    @Test fun webManualPauseOrPermanentLossDuringCallCancelsResume() {
        for (permanentLoss in listOf(false, true)) {
            var resumes = 0
            val focus = MediaFocus(context, {}, {}, { resumes++ })
            try {
                assertTrue(focus.acquire())
                focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                if (permanentLoss) focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS) else focus.release()
                focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(0, resumes)
            } finally { focus.close() }
        }
    }

    @Test fun nativeCallSuspendsAndGainResumesWithoutChangingPlaybackIntent() = withPlayer { player, change ->
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(player.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
        change(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(player.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
    }

    @Test fun nativeManualPauseDuringCallPreventsResume() = withPlayer { player, change ->
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        player.pause()
        change(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(player.playWhenReady)
    }

    @Test fun nativeNavigationGuidanceKeepsPlaybackAndGainRestoresFocus() = withPlayer { player, change ->
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(player.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        change(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(player.playWhenReady)
    }

    @Test fun nativePermanentLossDoesNotResumeOnLateGain() = withPlayer { player, change ->
        change(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(player.playWhenReady)
        change(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(player.playWhenReady)
    }

    private fun withPlayer(test: (ExoPlayer, (Int) -> Unit) -> Unit) {
        val player = ExoPlayer.Builder(context).build()
        val focus = MediaFocus.bind(context, player)
        try {
            player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource())
            player.prepare()
            player.play()
            val request = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest
            assertNotNull("ExoPlayer debe solicitar el foco", request)
            test(player) { change ->
                request.listener.onAudioFocusChange(change)
                shadowOf(Looper.getMainLooper()).idle()
            }
        } finally { focus.close(); player.release() }
    }
}
