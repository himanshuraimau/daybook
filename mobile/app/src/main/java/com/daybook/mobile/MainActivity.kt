package com.daybook.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.ApiClient
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.reminder.ReminderScheduler
import com.daybook.mobile.ui.AuthScreen
import com.daybook.mobile.ui.BudgetScreen
import com.daybook.mobile.ui.EntryScreen
import com.daybook.mobile.ui.HistoryScreen
import com.daybook.mobile.ui.HomeScreen
import com.daybook.mobile.ui.Host
import com.daybook.mobile.ui.InsightsScreen
import com.daybook.mobile.ui.SettingsScreen
import java.time.YearMonth
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), Host {
    companion object { private var processHasLaunched = false }

    override val context: Context get() = this
    override lateinit var store: ExpenseStore
    override lateinit var budgets: BudgetStore
    override val api = ApiClient()
    private lateinit var vault: SessionVault
    private val io = Executors.newSingleThreadExecutor()
    private var session: AccountSession? = null
    override val account: AccountSession get() = requireNotNull(session)
    override var promptVisible = true
    override var flamePop = false
    override var historyOffset = 0
    override var insightsLast = false
    private var screenName = ""
    private var entryReturn = "home"
    private var budgetReturn = "home"
    private var returningFromBackground = false
    private val auth = AuthScreen(this)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ReminderScheduler.set(this, true)
        else message("Notifications are off. Enable them in system settings to use reminders.")
        if (screenName == "settings") showSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = handleBack() })
        findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        store = ExpenseStore(this)
        budgets = BudgetStore(this)
        vault = SessionVault(this)
        session = vault.get()
        promptVisible = if (processHasLaunched) savedInstanceState?.getBoolean("prompt") ?: true else true
        processHasLaunched = true
        findViewById<View>(R.id.nav_home).setOnClickListener { showHome() }
        findViewById<View>(R.id.nav_history).setOnClickListener { historyOffset = 0; showHistory() }
        findViewById<View>(R.id.nav_insights).setOnClickListener { insightsLast = false; showInsights() }
        findViewById<View>(R.id.nav_settings).setOnClickListener { showSettings() }
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
            "budget" -> if (budgetReturn == "settings") showSettings() else showHome()
            "history", "insights", "settings" -> showHome()
            else -> finish()
        }
    }
    override fun onDestroy() { io.shutdownNow(); store.close(); super.onDestroy() }

    // ---- Host ----
    override fun inflate(layout: Int, name: String): View {
        screenName = name
        val container = findViewById<FrameLayout>(R.id.screen)
        container.removeAllViews()
        val view = layoutInflater.inflate(layout, container, false)
        container.addView(view)
        findViewById<View>(R.id.nav).visibility = if (name == "auth" || name == "entry" || name == "budget") View.GONE else View.VISIBLE
        for ((id, target) in listOf(R.id.nav_home to "home", R.id.nav_history to "history", R.id.nav_insights to "insights", R.id.nav_settings to "settings"))
            findViewById<View>(id).isSelected = target == name
        return view
    }
    override fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    override fun runOnUi(block: () -> Unit) = runOnUiThread { block() }
    override fun perform(work: () -> Unit, failure: (String) -> Unit) {
        io.execute { try { work() } catch (error: Exception) { runOnUiThread { failure(error.message ?: "Please try again") } } }
    }
    override fun requestNotificationPermission() { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    override fun refreshFromServer(months: List<YearMonth>) {
        if (!api.configured || session == null) return
        ExpenseSync.schedule(this)
        perform({
            if (!api.me(account)) {
                runOnUiThread {
                    vault.clear(); session = null; showAuth()
                    AlertDialog.Builder(this, R.style.DaybookDialog).setMessage("Your session expired. Log in again to sync expenses saved on this phone.").setPositiveButton("OK", null).show()
                }
                return@perform
            }
            ExpenseSync.run(applicationContext, months + Cycles.containing(java.time.LocalDate.now(), budgets.get(account.ownerId)?.payday ?: 1).months())
            runOnUiThread { when (screenName) { "home" -> showHome(); "history" -> showHistory(false); "insights" -> showInsights() } }
        }, { })
    }

    override fun signedIn(session: AccountSession, isNew: Boolean) {
        vault.save(session); this.session = session
        if (isNew && budgets.get(session.ownerId) == null) { budgetReturn = "home"; showBudget(true) } else showHome()
        refreshFromServer()
        if (intent.getBooleanExtra("open_add", false)) showEntry()
    }
    override fun signOut() {
        val old = account; vault.clear(); session = null
        perform({ try { api.logout(old) } catch (_: Exception) {} }, { })
        showAuth()
    }

    override fun showAuth() = auth.show()
    override fun showHome() { if (session == null) showAuth() else HomeScreen(this).show() }
    override fun showHistory(fetch: Boolean) { if (session == null) showAuth() else HistoryScreen(this).show(fetch) }
    override fun showInsights() { if (session == null) showAuth() else InsightsScreen(this).show() }
    override fun showSettings() { if (session == null) showAuth() else SettingsScreen(this).show() }
    override fun showEntry(clientId: String?) {
        if (session == null) { showAuth(); return }
        entryReturn = if (screenName == "history") "history" else if (screenName == "insights") "insights" else "home"
        EntryScreen(this).show(clientId)
    }
    override fun returnFromEntry() { when (entryReturn) { "history" -> showHistory(false); "insights" -> showInsights(); else -> showHome() } }
    override fun showBudget(onboarding: Boolean) {
        if (session == null) { showAuth(); return }
        if (!onboarding) budgetReturn = if (screenName == "settings") "settings" else "home"
        BudgetScreen(this).show(onboarding) { if (budgetReturn == "settings") showSettings() else showHome() }
    }
}
