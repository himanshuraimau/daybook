package com.daybook.mobile

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.ApiClient
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.data.LocalExpense
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.reminder.ReminderScheduler
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object { private var processHasLaunched = false }
    private lateinit var store: ExpenseStore
    private lateinit var vault: SessionVault
    private val api = ApiClient()
    private val io = Executors.newSingleThreadExecutor()
    private var session: AccountSession? = null
    private var screenName = ""
    private var promptVisible = true
    private var historyMonth = YearMonth.now()
    private var authMode = "login"
    private var entryReturn = "home"
    private var returningFromBackground = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ReminderScheduler.set(this, true)
        else message("Notifications are off. Enable them in system settings to use reminders.")
        if (screenName == "settings") showSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        val root = findViewById<View>(android.R.id.content)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        store = ExpenseStore(this)
        vault = SessionVault(this)
        session = vault.get()
        promptVisible = if (processHasLaunched) savedInstanceState?.getBoolean("prompt") ?: true else true
        processHasLaunched = true
        findViewById<TextView>(R.id.nav_home).setOnClickListener { showHome() }
        findViewById<TextView>(R.id.nav_history).setOnClickListener { showHistory() }
        findViewById<TextView>(R.id.nav_settings).setOnClickListener { showSettings() }
        ReminderScheduler.schedule(this)
        if (session == null) showAuth() else {
            if (intent.getBooleanExtra("open_add", false)) showEntry() else showHome()
            refreshFromServer()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("prompt", promptVisible); super.onSaveInstanceState(outState) }
    override fun onStart() {
        super.onStart()
        if (returningFromBackground && session != null) { returningFromBackground = false; promptVisible = true; showHome(); refreshFromServer() }
    }
    override fun onStop() { if (!isChangingConfigurations) returningFromBackground = true; super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_add", false) && session != null) showEntry()
    }
    private fun handleBack() {
        when (screenName) {
            "entry" -> returnFromEntry()
            "history", "settings" -> showHome()
            else -> finish()
        }
    }
    override fun onDestroy() {
        io.shutdownNow(); store.close(); super.onDestroy()
    }

    private fun inflate(layout: Int, name: String): View {
        screenName = name
        val container = findViewById<FrameLayout>(R.id.screen)
        container.removeAllViews()
        val view = layoutInflater.inflate(layout, container, false)
        container.addView(view)
        findViewById<View>(R.id.nav).visibility = if (name == "auth" || name == "entry") View.GONE else View.VISIBLE
        for ((id, target) in listOf(R.id.nav_home to "home", R.id.nav_history to "history", R.id.nav_settings to "settings")) {
            findViewById<TextView>(id).apply {
                setTextColor(getColor(if (target == name) R.color.primary else R.color.muted))
                setTypeface(null, if (target == name) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        return view
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply { currency = Currency.getInstance("INR") }.format(BigDecimal.valueOf(paise, 2))
    private fun account(): AccountSession = requireNotNull(session)
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun perform(work: () -> Unit, failure: (String) -> Unit) {
        io.execute { try { work() } catch (error: Exception) { runOnUiThread { failure(error.message ?: "Please try again") } } }
    }
    private fun refreshFromServer(month: YearMonth = YearMonth.now()) {
        if (!api.configured || session == null) return
        ExpenseSync.schedule(this)
        perform({
            if (!api.me(account())) {
                runOnUiThread {
                    vault.clear(); session = null; showAuth()
                    AlertDialog.Builder(this).setMessage("Your session expired. Log in again to sync expenses saved on this phone.").setPositiveButton("OK", null).show()
                }
                return@perform
            }
            ExpenseSync.run(applicationContext, month)
            runOnUiThread { when (screenName) { "home" -> showHome(); "history" -> showHistory(false) } }
        }, { })
    }

    private fun showAuth() {
        val view = inflate(R.layout.screen_auth, "auth")
        val email = view.findViewById<EditText>(R.id.auth_email)
        val password = view.findViewById<EditText>(R.id.auth_password)
        val error = view.findViewById<TextView>(R.id.auth_error)
        val submit = view.findViewById<TextView>(R.id.auth_submit)
        val toggle = view.findViewById<TextView>(R.id.auth_toggle)
        fun mode(value: String) {
            authMode = value
            submit.text = if (value == "signup") "Create account" else "Log in"
            toggle.text = if (value == "login") "Create account" else "Log in"
            password.setAutofillHints(if (value == "signup") "newPassword" else View.AUTOFILL_HINT_PASSWORD)
            error.text = if (!api.configured) "This build needs an HTTPS Daybook API address." else ""
        }
        mode(authMode)
        toggle.setOnClickListener { mode(if (authMode == "login") "signup" else "login") }
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { error.text = "Enter a valid email"; return@setOnClickListener }
            if (authMode == "signup" && secret.length < 8) { error.text = "Use at least 8 characters"; return@setOnClickListener }
            if (secret.isEmpty()) { error.text = "Enter your password"; return@setOnClickListener }
            submit.isEnabled = false
            error.text = ""
            val selected = authMode
            perform({
                val account = if (selected == "signup") api.signup(address, secret) else api.login(address, secret)
                vault.save(account); session = account
                runOnUiThread {
                    submit.isEnabled = true
                    showHome(); refreshFromServer(); if (intent.getBooleanExtra("open_add", false)) showEntry()
                }
            }, { reason -> submit.isEnabled = true; error.text = reason })
        }
    }

    private fun showHome() {
        if (session == null) { showAuth(); return }
        val view = inflate(R.layout.screen_home, "home")
        val all = store.all(account().ownerId)
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        view.findViewById<TextView>(R.id.today_date).text = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)).uppercase()
        view.findViewById<TextView>(R.id.today_total).text = money(all.filter { it.occurredOn == today }.sumOf { it.amountMinor })
        val monthItems = all.filter { it.occurredOn.startsWith(month) }
        val monthTotal = monthItems.sumOf { it.amountMinor }
        view.findViewById<TextView>(R.id.month_total).text = money(monthTotal)
        view.findViewById<View>(R.id.open_prompt).visibility = if (promptVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.home_add).setOnClickListener { showEntry() }
        view.findViewById<View>(R.id.prompt_add).setOnClickListener { promptVisible = false; showEntry() }
        view.findViewById<View>(R.id.prompt_dismiss).setOnClickListener { promptVisible = false; view.findViewById<View>(R.id.open_prompt).visibility = View.GONE }
        val waiting = store.unsyncedCount(account().ownerId)
        view.findViewById<TextView>(R.id.sync_banner).apply {
            text = if (waiting == 0) "" else "$waiting expense${if (waiting == 1) "" else "s"} waiting to sync · Retry"
            setOnClickListener { store.retryFailed(account().ownerId); ExpenseSync.schedule(this@MainActivity); refreshFromServer() }
        }
        val categories = view.findViewById<LinearLayout>(R.id.categories)
        if (monthItems.isEmpty()) categories.addView(label("Add your first expense today", 16, R.color.muted))
        for ((category, amount) in monthItems.groupBy { it.category }.mapValues { (_, items) -> items.sumOf { it.amountMinor } }.entries.sortedByDescending { it.value }) {
            categories.addView(label("$category     ${money(amount)}", 16, R.color.ink).apply { setPadding(0, dp(12), 0, dp(4)) })
            categories.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (monthTotal > 0) ((amount.toDouble() / monthTotal) * 1000).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
                contentDescription = "$category: ${money(amount)} of ${money(monthTotal)}"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        }
        val recent = view.findViewById<LinearLayout>(R.id.recent)
        if (all.isEmpty()) recent.addView(label("Nothing recorded today. Add an expense to start your daybook.", 16, R.color.muted))
        all.take(5).forEach { recent.addView(expenseRow(it)) }
    }
    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(getColor(color)); gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun expenseRow(item: LocalExpense) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72)
        addView(label("${item.note.ifBlank { item.category }} · ${item.category}\n${item.occurredOn}${when (item.status) { "pending" -> " · Pending sync"; "failed" -> " · Sync failed · Retry"; else -> " · Synced" }}", 15, R.color.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(money(item.amountMinor), 17, R.color.ink).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            if (item.status == "failed") AlertDialog.Builder(this@MainActivity).setTitle("Sync failed")
                .setMessage("Your expense is saved on this phone.")
                .setPositiveButton("Retry") { _, _ -> store.retryFailed(account().ownerId); ExpenseSync.schedule(this@MainActivity); refreshFromServer(YearMonth.parse(item.occurredOn.substring(0, 7))) }
                .setNeutralButton("Review") { _, _ -> showEntry(item.clientId) }.show()
            else showEntry(item.clientId)
        }
    }

    private fun showHistory(fetch: Boolean = true) {
        if (session == null) { showAuth(); return }
        val view = inflate(R.layout.screen_history, "history")
        view.findViewById<TextView>(R.id.history_month).text = historyMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        view.findViewById<View>(R.id.prev_month).setOnClickListener { historyMonth = historyMonth.minusMonths(1); showHistory() }
        view.findViewById<View>(R.id.next_month).setOnClickListener { historyMonth = historyMonth.plusMonths(1); showHistory() }
        val list = view.findViewById<LinearLayout>(R.id.history_list)
        val items = store.all(account().ownerId).filter { it.occurredOn.startsWith(historyMonth.toString()) }
        if (items.isEmpty()) list.addView(label("No expenses recorded this month.", 16, R.color.muted))
        for ((day, entries) in items.groupBy { it.occurredOn }) {
            list.addView(label("$day     ${money(entries.sumOf { it.amountMinor })}", 18, R.color.ink).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(20), 0, dp(8)) })
            entries.forEach { list.addView(expenseRow(it)) }
        }
        if (fetch) refreshFromServer(historyMonth)
    }

    private fun showEntry(clientId: String? = null) {
        entryReturn = if (screenName == "history") "history" else "home"
        val view = inflate(R.layout.screen_entry, "entry")
        val existing = clientId?.let { store.one(account().ownerId, it) }
        view.findViewById<TextView>(R.id.entry_title).text = if (existing == null) "Add expense" else "Edit expense"
        val amount = view.findViewById<EditText>(R.id.entry_amount)
        val note = view.findViewById<EditText>(R.id.entry_note)
        val dateView = view.findViewById<TextView>(R.id.entry_date)
        val error = view.findViewById<TextView>(R.id.entry_error)
        if (existing?.status == "failed") {
            if (existing.serverId == null) error.text = "Couldn't sync. Your expense is saved here. Edit and save, or retry from Home."
            else {
                error.text = "Couldn't sync. Tap here to load the server copy before editing again."
                error.setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Load server copy?")
                        .setMessage("This will discard the unsynced changes saved on this phone.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Load") { _, _ ->
                            perform({ val fresh = api.get(account(), existing.serverId); store.acceptServerCopy(account().ownerId, fresh)
                                runOnUiThread { message("Server copy loaded"); returnFromEntry() }
                            }, { reason -> error.text = reason })
                        }.show()
                }
            }
        }
        var chosenDate = existing?.occurredOn ?: LocalDate.now().toString()
        dateView.text = chosenDate
        dateView.setOnClickListener {
            val current = LocalDate.parse(chosenDate)
            DatePickerDialog(this, { _, year, month, day -> chosenDate = LocalDate.of(year, month + 1, day).toString(); dateView.text = chosenDate }, current.year, current.monthValue - 1, current.dayOfMonth).show()
        }
        if (existing != null) {
            amount.setText(BigDecimal.valueOf(existing.amountMinor, 2).stripTrailingZeros().toPlainString())
            note.setText(existing.note)
        }
        val group = view.findViewById<RadioGroup>(R.id.entry_categories)
        for (category in ExpenseStore.CATEGORIES) group.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = category; textSize = 16f; minimumHeight = dp(48); tag = category
            isChecked = category == (existing?.category ?: "Food")
        })
        view.findViewById<View>(R.id.entry_back).setOnClickListener { returnFromEntry() }
        amount.requestFocus()
        val save = view.findViewById<TextView>(R.id.entry_save)
        save.setOnClickListener {
            val minor = try { BigDecimal(amount.text.toString()).movePointRight(2).longValueExact() } catch (_: Exception) { 0L }
            val category = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String
            val noteValue = note.text.toString().trim()
            if (minor !in 1..100_000_000_000 || category == null || noteValue.length > 240) { error.text = "Enter a positive amount, category, and note up to 240 characters"; return@setOnClickListener }
            val latest = existing?.let { store.one(account().ownerId, it.clientId) }
            if (existing == null) {
                store.add(account().ownerId, minor, category, chosenDate, noteValue)
                message("Saved on this phone. Waiting to sync.")
                ExpenseSync.schedule(this); showHome(); refreshFromServer()
            } else if (latest != null && latest.status != "synced") {
                if (store.changePending(account().ownerId, latest.clientId, minor, category, chosenDate, noteValue)) {
                    message("Saved on this phone. Waiting to sync.")
                    ExpenseSync.schedule(this); returnFromEntry()
                } else error.text = "This expense changed. Reopen it and try again."
            } else {
                save.isEnabled = false
                val changed = (latest ?: existing).copy(amountMinor = minor, category = category, occurredOn = chosenDate, note = noteValue)
                perform({ val updated = api.update(account(), changed); store.replaceSynced(account().ownerId, updated)
                    runOnUiThread { message("Expense updated"); returnFromEntry() }
                }, { reason -> save.isEnabled = true; error.text = reason })
            }
        }
        if (existing != null) view.findViewById<TextView>(R.id.entry_delete).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Delete expense?").setMessage("This entry will be removed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        val latest = store.one(account().ownerId, existing.clientId)
                        if (latest != null && latest.status != "synced") { store.deletePending(account().ownerId, latest.clientId); ExpenseSync.schedule(this@MainActivity); returnFromEntry() }
                        else if (latest != null) perform({ api.delete(account(), latest); store.deleteSynced(account().ownerId, latest.clientId)
                            runOnUiThread { returnFromEntry() }
                        }, { reason -> error.text = reason })
                    }.show()
            }
        }
    }
    private fun returnFromEntry() { if (entryReturn == "history") showHistory(false) else showHome() }

    private fun showSettings() {
        if (session == null) { showAuth(); return }
        val view = inflate(R.layout.screen_settings, "settings")
        val toggle = view.findViewById<Switch>(R.id.reminder_switch)
        val time = view.findViewById<TextView>(R.id.reminder_time)
        val help = view.findViewById<TextView>(R.id.reminder_help)
        fun updateTime() { time.text = "Time    ${java.time.LocalTime.of(ReminderScheduler.hour(this), ReminderScheduler.minute(this)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}" }
        updateTime()
        toggle.isChecked = ReminderScheduler.enabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                toggle.isChecked = false; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else ReminderScheduler.set(this, checked)
        }
        time.setOnClickListener { TimePickerDialog(this, { _, hour, minute -> ReminderScheduler.set(this, toggle.isChecked, hour, minute); updateTime() }, ReminderScheduler.hour(this), ReminderScheduler.minute(this), false).show() }
        view.findViewById<TextView>(R.id.account_email).text = "Email    ${account().email}"
        val waiting = store.unsyncedCount(account().ownerId)
        view.findViewById<TextView>(R.id.account_sync).text = if (waiting == 0) "All synced" else "$waiting expense${if (waiting == 1) "" else "s"} waiting to sync"
        view.findViewById<View>(R.id.sign_out).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Sign out?")
                .setMessage(if (waiting > 0) "$waiting unsynced expense${if (waiting == 1) " is" else "s are"} saved on this phone for this account. Sign in to this account again to sync." else "You can sign in again later.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ ->
                    val old = account(); vault.clear(); session = null
                    perform({ try { api.logout(old) } catch (_: Exception) {} }, { })
                    showAuth()
                }.show()
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            help.text = "Notifications are off. Tap to open system settings."
            help.setOnClickListener { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        }
    }
}
