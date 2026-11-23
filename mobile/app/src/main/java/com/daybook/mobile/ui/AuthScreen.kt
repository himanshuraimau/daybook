package com.daybook.mobile.ui

import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.daybook.mobile.R

class AuthScreen(private val h: Host) {
    private var mode = "login"
    fun show() {
        val v = h.inflate(R.layout.screen_auth, "auth")
        val email = v.findViewById<EditText>(R.id.auth_email)
        val password = v.findViewById<EditText>(R.id.auth_password)
        val error = v.findViewById<TextView>(R.id.auth_error)
        val submit = v.findViewById<TextView>(R.id.auth_submit)
        val toggle = v.findViewById<TextView>(R.id.auth_toggle)
        fun setMode(value: String) {
            mode = value
            submit.text = if (value == "signup") "Create account" else "Log in"
            toggle.text = if (value == "login") "Create account" else "I already have an account"
            password.setAutofillHints(if (value == "signup") "newPassword" else View.AUTOFILL_HINT_PASSWORD)
            error.text = if (!h.api.configured) "This build needs an HTTPS Daybook API address." else ""
        }
        setMode(mode)
        toggle.setOnClickListener { setMode(if (mode == "login") "signup" else "login") }
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) { error.text = "Enter a valid email"; return@setOnClickListener }
            if (mode == "signup" && secret.length < 8) { error.text = "Use at least 8 characters"; return@setOnClickListener }
            if (secret.isEmpty()) { error.text = "Enter your password"; return@setOnClickListener }
            submit.isEnabled = false
            error.text = ""
            val selected = mode
            h.perform({
                val account = if (selected == "signup") h.api.signup(address, secret) else h.api.login(address, secret)
                h.runOnUi { submit.isEnabled = true; h.signedIn(account, selected == "signup") }
            }, { reason -> submit.isEnabled = true; error.text = reason })
        }
    }
}
