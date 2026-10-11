package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
import com.bumptech.glide.request.target.CustomTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PlaybackMetadataTest {
    @Test fun sessionPublishesChannelAndPlaybackAndRegistersChannelControls() {
        var playing = true
        var skips = 0
        val player = mock<Player> {
            on { playbackState } doReturn Player.STATE_READY
            on { currentPosition } doReturn 0L
        }
        whenever(player.playWhenReady).thenAnswer { playing }
        val metadata = PlaybackMetadata(RuntimeEnvironment.getApplication(), player, "Radio",
            { playing = true }, {}, { skips-- }, { skips++ })
        val session = session(metadata)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        try {
            metadata.setChannel("Radio Uno", "Artista · Canción", "")
            assertEquals("Radio Uno", publishedMetadata(session).getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertEquals("Artista · Canción", publishedMetadata(session).getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            assertEquals(PlaybackStateCompat.STATE_PLAYING, publishedState(session).state)
            assertTrue(session.isActive)
            (sessionField(session, "mCallback") as MediaSessionCompat.Callback).onSkipToNext()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, skips)
            playing = false
            listener.firstValue.onEvents(player, mock())
            assertEquals(PlaybackStateCompat.STATE_PAUSED, publishedState(session).state)
            assertFalse(session.isActive)
            metadata.setChannel("TV Dos", "TV", "")
            assertEquals("TV Dos", publishedMetadata(session).getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        } finally { metadata.close() }
        assertFalse(session.isActive)
        verify(player).removeListener(listener.firstValue)
    }

    @Test fun changingChannelClearsOldLogoAndRejectsLateArtwork() {
        val player = mock<Player> {
            on { playbackState } doReturn Player.STATE_READY
            on { playWhenReady } doReturn true
            on { currentPosition } doReturn 0L
        }
        val metadata = PlaybackMetadata(RuntimeEnvironment.getApplication(), player, "TV", {}, {}, null, null)
        val session = session(metadata)
        try {
            metadata.setChannel("Canal Uno", "TV", "file:///missing/channel-one.png")
            val old = target(metadata)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFF00FF00.toInt())
            old.onResourceReady(bitmap, null)
            assertNotNull(publishedMetadata(session).getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
            metadata.setChannel("Canal Dos", "TV", "file:///missing/channel-two.png")
            assertNull(publishedMetadata(session).getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
            old.onResourceReady(bitmap, null)
            assertNull(publishedMetadata(session).getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
            target(metadata).onResourceReady(bitmap, null)
            assertEquals("Canal Dos", publishedMetadata(session).getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertNotNull(publishedMetadata(session).getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON))
        } finally { metadata.close() }
    }

    // Robolectric no comunica el Binder de MediaSession con su controlador. Comprobamos los
    // valores que AndroidX guarda al publicarlos, y el callback realmente registrado en la sesión.
    private fun publishedMetadata(session: MediaSessionCompat) = sessionField(session, "mMetadata") as MediaMetadataCompat
    private fun publishedState(session: MediaSessionCompat) = sessionField(session, "mPlaybackState") as PlaybackStateCompat

    private fun sessionField(session: MediaSessionCompat, name: String): Any? {
        val impl = MediaSessionCompat::class.java.getDeclaredField("mImpl").apply { isAccessible = true }.get(session)
        var type: Class<*>? = impl.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(impl)
            } catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing media session field $name")
    }

    private fun session(metadata: PlaybackMetadata): MediaSessionCompat =
        PlaybackMetadata::class.java.getDeclaredField("session").apply { isAccessible = true }.get(metadata) as MediaSessionCompat

    @Suppress("UNCHECKED_CAST")
    private fun target(metadata: PlaybackMetadata): CustomTarget<Bitmap> =
        PlaybackMetadata::class.java.getDeclaredField("image").apply { isAccessible = true }.get(metadata) as CustomTarget<Bitmap>
}
