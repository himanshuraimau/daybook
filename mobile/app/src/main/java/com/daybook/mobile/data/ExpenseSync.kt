package com.daybook.mobile.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.IOException
import java.time.YearMonth
import java.util.concurrent.Executors

object ExpenseSync {
    private const val JOB_ID = 7412
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun run(context: Context, month: YearMonth = YearMonth.now()): Boolean = run(context, listOf(month))
    /** Uploads pending work, then refreshes each of [months] and syncs the budget profile. Returns true to retry later. */
    fun run(context: Context, months: List<YearMonth>): Boolean {
        val session = SessionVault(context).get() ?: return false
        val api = ApiClient()
        var retry = false
        for (month in months.distinct()) retry = runForAccount(context, month, session, api) || retry
        val budgets = BudgetStore(context)
        return BudgetSync.run(budgets, session, api) || retry
    }
    @Synchronized internal fun runForAccount(context: Context, month: YearMonth, session: AccountSession, api: ExpenseGateway): Boolean {
        if (!api.configured) return false
        val store = ExpenseStore(context)
        try {
        for (pass in 0 until 100) {
            val pending = store.pending(session.ownerId)
            if (pending.isEmpty()) break
            for (item in pending) {
                try {
                    when {
                        item.status.startsWith("deleting") && item.serverId != null -> {
                            try { api.delete(session, item) } catch (error: ApiError) { if (error.status != 404) throw error }
                            store.deleteSynced(session.ownerId, item.clientId)
                        }
                        item.serverId == null -> store.confirm(session.ownerId, item, api.create(session, item))
                        else -> store.confirm(session.ownerId, item, api.update(session, item))
                    }
                } catch (error: ApiError) {
                    if (error.status == 401) return false
                    if (error.status in 400..499) { store.failed(session.ownerId, item.clientId); return false }
                    return true
                } catch (_: IOException) { return true }
            }
        }
        if (store.pending(session.ownerId).isNotEmpty()) return true
        return try {
            val remote = api.listMonth(session, month.toString())
            val ids = remote.map { it.clientId }.toSet()
            for (local in store.all(session.ownerId)) {
                if (local.status == "synced" && local.serverId != null && local.occurredOn.startsWith(month.toString()) && local.clientId !in ids) store.deleteSynced(session.ownerId, local.clientId)
            }
            remote.forEach { store.replaceSynced(session.ownerId, it) }
            false
        } catch (error: ApiError) { error.status != 401 } catch (_: IOException) { true }
        } finally { store.close() }
    }
}

class SyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        executor.execute {
            val retry = try { ExpenseSync.run(applicationContext) } catch (_: Exception) { true }
            com.daybook.mobile.AppSignals.dataChanged(applicationContext)
            if (!stopped) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
