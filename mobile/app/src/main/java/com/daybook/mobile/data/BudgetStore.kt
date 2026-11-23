package com.daybook.mobile.data

import android.content.Context
import com.daybook.mobile.domain.BudgetProfile
import com.daybook.mobile.domain.FixedSpend
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

interface BudgetGateway {
    val configured: Boolean
    /** The account's saved profile, or null when none exists. */
    fun getBudget(session: AccountSession): BudgetProfile?
    /** Stores [profile]; throws [ApiError] with status 409 when the server already holds a newer one. */
    fun putBudget(session: AccountSession, profile: BudgetProfile): BudgetProfile
}

/** One budget profile per account, kept on the phone; [dirty] marks an edit not yet uploaded. */
class BudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("budget", Context.MODE_PRIVATE)

    fun get(ownerId: String): BudgetProfile? = prefs.getString("profile_$ownerId", null)?.let { decode(it) }
    fun dirty(ownerId: String): Boolean = prefs.getBoolean("dirty_$ownerId", false)

    /** Saves a local edit stamped with the current time and queues it for upload. */
    fun save(ownerId: String, incomeMinor: Long, payday: Int, fixed: List<FixedSpend>, now: Long = System.currentTimeMillis()): BudgetProfile {
        val profile = BudgetProfile(incomeMinor, payday, fixed, maxOf(now, (get(ownerId)?.updatedAt ?: 0L) + 1))
        prefs.edit().putString("profile_$ownerId", encode(profile)).putBoolean("dirty_$ownerId", true).apply()
        return profile
    }
    /** Replaces the local copy with the server's and clears the pending upload. */
    fun adopt(ownerId: String, profile: BudgetProfile) {
        prefs.edit().putString("profile_$ownerId", encode(profile)).putBoolean("dirty_$ownerId", false).apply()
    }

    companion object {
        fun encode(p: BudgetProfile): String = JSONObject().put("incomeMinor", p.incomeMinor).put("payday", p.payday).put("updatedAt", p.updatedAt)
            .put("fixed", JSONArray().also { arr -> p.fixed.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("amountMinor", it.amountMinor).put("category", it.category).also { o -> if (it.isEmi) o.put("months", it.months).put("startKey", it.startKey) }) } }).toString()
        fun decode(raw: String): BudgetProfile? = try { decode(JSONObject(raw)) } catch (_: Exception) { null }
        fun decode(o: JSONObject): BudgetProfile {
            val arr = o.getJSONArray("fixed")
            return BudgetProfile(o.getLong("incomeMinor"), o.getInt("payday"),
                (0 until arr.length()).map { arr.getJSONObject(it).let { f -> FixedSpend(f.getString("id"), f.getString("name"), f.getLong("amountMinor"), f.getString("category"), if (f.has("months")) f.getInt("months") else null, if (f.has("startKey")) f.getString("startKey") else null) } },
                o.getLong("updatedAt"))
        }
    }
}

object BudgetSync {
    /** Uploads a pending edit or adopts a newer server copy. Returns true when it should be retried later. */
    @Synchronized fun run(store: BudgetStore, session: AccountSession, api: BudgetGateway): Boolean {
        if (!api.configured) return false
        val owner = session.ownerId
        return try {
            val local = store.get(owner)
            if (local != null && store.dirty(owner)) {
                try { store.adopt(owner, api.putBudget(session, local)) }
                catch (error: ApiError) {
                    when {
                        error.status == 409 -> api.getBudget(session)?.let { store.adopt(owner, it) }
                        error.status == 401 -> return false
                        error.status in 400..499 -> return false
                        else -> return true
                    }
                }
            } else {
                val remote = api.getBudget(session)
                if (remote != null && (local == null || remote.updatedAt > local.updatedAt)) store.adopt(owner, remote)
            }
            false
        } catch (error: ApiError) { error.status != 401 && error.status >= 500 }
        catch (_: IOException) { store.dirty(owner) }
    }
}
