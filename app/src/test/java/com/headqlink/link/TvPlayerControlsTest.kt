package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.VideoSize
import com.andrerinas.openheadunit.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class TvPlayerControlsTest {
    @After fun reset() { RuntimeEnvironment.getApplication().getSharedPreferences("cfg", 0).edit().clear().commit() }

    @Test fun channelNameAndOptionsDisappearWithThePlayerControlsAndCanBeShownAgain() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_HeadQLink)
        Str.init(activity)
        val player = CarStyle.player(activity)
        player.player = mock<Player> {
            on { applicationLooper } doReturn Looper.getMainLooper()
            on { availableCommands } doReturn Player.Commands.EMPTY
            on { playbackState } doReturn Player.STATE_READY
            on { playWhenReady } doReturn true
            on { currentTimeline } doReturn Timeline.EMPTY
            on { currentTracks } doReturn Tracks.EMPTY
            on { mediaMetadata } doReturn MediaMetadata.EMPTY
            on { playbackParameters } doReturn PlaybackParameters.DEFAULT
            on { videoSize } doReturn VideoSize.UNKNOWN
        }
        val overlay = LinearLayout(activity)
        val name = TextView(activity).apply { text = "Canal TV" }
        val fill = TextView(activity)
        overlay.addView(name)
        overlay.addView(fill)
        val root = FrameLayout(activity)
        root.addView(player)
        root.addView(overlay)
        activity.setContentView(root)
        try {
            TvPlayerControls.bind(activity, player, overlay, fill)
            player.showController()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, overlay.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TvPlayerControls.HIDE_AFTER_MS.toLong() + 100))
            assertEquals(View.GONE, overlay.visibility)
            player.showController()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, overlay.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fillUsesZoomAndSurvivesChangingChannelUntilFitIsSelected() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val first = CarStyle.player(context)
        val fill = TextView(context)
        TvPlayerControls.bind(context, first, View(context), fill)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, first.resizeMode)
        fill.performClick()
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, first.resizeMode)
        assertEquals(context.getString(R.string.hql_tv_fit), fill.text.toString())
        val nextChannel = CarStyle.player(context)
        val nextFill = TextView(context)
        TvPlayerControls.bind(context, nextChannel, View(context), nextFill)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, nextChannel.resizeMode)
        nextFill.performClick()
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, nextChannel.resizeMode)
    }
}
