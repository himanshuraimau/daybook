package com.daybook.mobile

import android.content.res.Configuration
import android.view.View
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.ui.Appearance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppearanceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun night(ctx: android.content.Context) = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    @Before fun setUp() {
        com.daybook.mobile.data.ApiClient.baseUrl = ""
        FakeAndroidKeyStore.install()
        app.getSharedPreferences("appearance", 0).edit().clear().commit()
        SessionVault(app).save(AccountSession("owner-1", "me@example.com", "token"))
    }
    @After fun tearDown() { app.getSharedPreferences("appearance", 0).edit().clear().commit(); SessionVault(app).clear(); app.deleteDatabase("daybook.db") }

    @Test fun followsThePhoneUntilAChoiceIsMade() {
        assertEquals(Appearance.SYSTEM, Appearance.mode(app))
        assertEquals(night(app), night(Appearance.wrap(app)))
    }

    @Test fun lightAndDarkForceTheNightModeAndPersist() {
        Appearance.set(app, Appearance.DARK)
        assertEquals(Appearance.DARK, Appearance.mode(app))
        assertEquals(Configuration.UI_MODE_NIGHT_YES, night(Appearance.wrap(app)))
        Appearance.set(app, Appearance.LIGHT)
        assertEquals(Configuration.UI_MODE_NIGHT_NO, night(Appearance.wrap(app)))
        Appearance.set(app, Appearance.SYSTEM)
        assertEquals(night(app), night(Appearance.wrap(app)))
    }

    @Test fun unknownStoredValueFallsBackToSystem() {
        app.getSharedPreferences("appearance", 0).edit().putString("theme", "purple").commit()
        assertEquals(Appearance.SYSTEM, Appearance.mode(app))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsAnInvalidMode() { Appearance.set(app, "purple") }

    @Test fun settingsOffersSystemLightAndDarkAndSavesTheChoice() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        assertTrue(a.findViewById<View>(R.id.theme_system).isSelected)
        assertFalse(a.findViewById<View>(R.id.theme_dark).isSelected)
        a.findViewById<View>(R.id.theme_dark).performClick()
        assertEquals(Appearance.DARK, Appearance.mode(app))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()      // lets the activity rebuild itself
        // A newly opened activity picks the choice up and shows it as selected on the Settings tab.
        val again = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(Configuration.UI_MODE_NIGHT_YES, night(again))
        again.findViewById<View>(R.id.nav_settings).performClick()
        assertTrue(again.findViewById<View>(R.id.theme_dark).isSelected)
        assertFalse(again.findViewById<View>(R.id.theme_system).isSelected)
    }
}
