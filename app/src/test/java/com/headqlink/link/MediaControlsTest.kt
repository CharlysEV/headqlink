package com.headqlink.link

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class MediaControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun reset() {
        context.getSharedPreferences("cfg", 0).edit().clear().commit()
        CarTheme.apply(true)
    }

    @Test fun startingAnotherInternalSourcePausesThePreviousOne() {
        var pauses = 0
        val radio = MediaFocus(context) { pauses++ }
        val tv = MediaFocus(context) { }
        try {
            assertTrue(radio.acquire())
            assertTrue(tv.acquire())
            assertEquals(1, pauses)
            // A delayed notification from the old owner cannot pause it a second time.
            radio.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1, pauses)
        } finally { radio.close(); tv.close() }
    }

    @Test fun spotifyTakingFocusPausesAndDoesNotResumeWhenFocusReturns() {
        var pauses = 0
        val focus = MediaFocus(context) { pauses++ }
        try {
            assertTrue(focus.acquire())
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1, pauses)
            // The user's next explicit Play may request focus again.
            assertTrue(focus.acquire())
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(2, pauses)
        } finally { focus.close() }
        assertFalse(focus.acquire())
    }

    @Test fun firstSetupRequiresResponsibilityBeforeLeavingWelcome() {
        Str.init(context)
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, 2)
        val controller = Robolectric.buildActivity(SetupActivity::class.java, intent).create()
        try {
            val activity = controller.get()
            val next = activity.findViewById<View>(R.id.hql_next)
            val root = activity.findViewById<ViewGroup>(R.id.hql_step_container)
            val check = descendants(root).filterIsInstance<CheckBox>().single()
            assertFalse(check.isChecked)
            assertFalse(next.isEnabled)
            check.isChecked = true
            assertTrue(next.isEnabled)
            check.isChecked = false
            assertFalse(next.isEnabled)
            assertFalse(com.headqlink.link.Config(context).setupDone())
            check.isChecked = true
            next.performClick()
            assertTrue(descendants(root).filterIsInstance<CheckBox>().isEmpty())
            next.performClick()
            assertTrue(descendants(root).filterIsInstance<CheckBox>().isEmpty())
            assertTrue(texts(root).contains(context.getString(R.string.hql_req_microphone)))
            assertTrue(texts(root).contains(context.getString(R.string.hql_req_phone)))
            for ((title, permission) in listOf(
                R.string.hql_req_microphone to android.Manifest.permission.RECORD_AUDIO,
                R.string.hql_req_phone to android.Manifest.permission.READ_PHONE_STATE,
            )) {
                val label = descendants(root).filterIsInstance<TextView>()
                    .single { it.id == R.id.hql_req_title && it.text.toString() == context.getString(title) }
                val action = (label.parent as ViewGroup).findViewById<View>(R.id.hql_req_action)
                assertEquals(View.VISIBLE, action.visibility)
                action.performClick()
                assertArrayEquals(arrayOf(permission), shadowOf(activity).lastRequestedPermission.requestedPermissions)
            }
        } finally { controller.destroy() }
    }

    @Test fun welcomeTermsAppearBeforePreparationAndReadingThemDoesNotAcceptThem() {
        Str.init(context)
        val controller = Robolectric.buildActivity(SetupActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val container = activity.findViewById<ViewGroup>(R.id.hql_step_container)
            val welcome = container.getChildAt(0) as ViewGroup
            assertTrue(welcome.indexOfChild(activity.findViewById(R.id.hql_responsibility_section)) <
                welcome.indexOfChild(activity.findViewById(R.id.hql_before_start)))
            val check = activity.findViewById<CheckBox>(R.id.hql_responsibility_accept)
            val next = activity.findViewById<View>(R.id.hql_next)
            activity.findViewById<View>(R.id.hql_terms_link).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val detail = dialog.findViewById<TextView>(R.id.hql_terms_detail).text.toString()
            assertTrue(detail.contains("GNU AGPL-3.0"))
            assertTrue(detail.contains("QonectaLeap"))
            assertFalse(check.isChecked)
            assertFalse(next.isEnabled)
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertFalse(check.isChecked)
            assertFalse(com.headqlink.link.Config(context).setupDone())
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
}
