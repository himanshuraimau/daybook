package com.daybook.mobile

import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityTest {
    @Test fun accountScreenSwitchesBetweenLoginAndSignupWithValidation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val submit = activity.findViewById<TextView>(R.id.auth_submit)
        val toggle = activity.findViewById<TextView>(R.id.auth_toggle)
        assertEquals("Log in", submit.text.toString())

        toggle.performClick()
        assertEquals("Create account", submit.text.toString())
        toggle.performClick()
        assertEquals("Log in", submit.text.toString())

        activity.findViewById<EditText>(R.id.auth_email).setText("not-an-email")
        submit.performClick()
        assertTrue(activity.findViewById<TextView>(R.id.auth_error).text.contains("valid email"))
        activity.findViewById<EditText>(R.id.auth_email).setText("me@example.com")
        toggle.performClick()
        activity.findViewById<EditText>(R.id.auth_password).setText("short")
        submit.performClick()
        assertTrue(activity.findViewById<TextView>(R.id.auth_error).text.contains("at least 8"))
        controller.pause().stop().destroy()
    }
}
