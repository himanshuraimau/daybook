package com.daybook.mobile.data

import com.daybook.mobile.domain.BudgetProfile
import com.daybook.mobile.domain.FixedSpend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BudgetSyncTest {
    private val rent = FixedSpend("rent", "Rent", 1_000_000, "Bills")
    private val a = AccountSession("owner-a", "a@x.test", "t")
    private val b = AccountSession("owner-b", "b@x.test", "t")
    private lateinit var store: BudgetStore

    private class Fake : BudgetGateway {
        override var configured = true
        var server: BudgetProfile? = null
        var offline = false
        var puts = 0
        override fun getBudget(session: AccountSession): BudgetProfile? { if (offline) throw IOException("offline"); return server }
        override fun putBudget(session: AccountSession, profile: BudgetProfile): BudgetProfile {
            if (offline) throw IOException("offline")
            puts++
            val current = server
            if (current != null && current.updatedAt > profile.updatedAt) throw ApiError(409, "conflict", "newer")
            server = profile
            return profile
        }
    }

    @Before fun setUp() { RuntimeEnvironment.getApplication().getSharedPreferences("budget", 0).edit().clear().commit(); store = BudgetStore(RuntimeEnvironment.getApplication()) }
    @After fun tearDown() { RuntimeEnvironment.getApplication().getSharedPreferences("budget", 0).edit().clear().commit() }

    @Test fun savedProfileSurvivesReopenAndIsMarkedPending() {
        store.save(a.ownerId, 5_000_000, 27, listOf(rent), now = 1000)
        val reopened = BudgetStore(RuntimeEnvironment.getApplication())
        val p = reopened.get(a.ownerId)!!
        assertEquals(5_000_000L, p.incomeMinor); assertEquals(27, p.payday); assertEquals(listOf(rent), p.fixed)
        assertEquals(4_000_000L, p.spendableMinor)
        assertTrue(reopened.dirty(a.ownerId))
    }

    @Test fun profilesAreSeparatedByAccount() {
        store.save(a.ownerId, 5_000_000, 27, listOf(rent), now = 1000)
        assertNull(store.get(b.ownerId))
        assertFalse(store.dirty(b.ownerId))
    }

    @Test fun editMadeOfflineUploadsOnceWhenOnline() {
        val api = Fake().apply { offline = true }
        store.save(a.ownerId, 5_000_000, 27, listOf(rent), now = 1000)
        assertTrue(BudgetSync.run(store, a, api))          // offline: retry later, still pending
        assertTrue(store.dirty(a.ownerId))
        api.offline = false
        assertFalse(BudgetSync.run(store, a, api))
        assertFalse(store.dirty(a.ownerId))
        assertEquals(1, api.puts)
        assertFalse(BudgetSync.run(store, a, api))          // nothing left to upload
        assertEquals(1, api.puts)
        assertEquals(5_000_000L, api.server!!.incomeMinor)
    }

    @Test fun newerServerCopyWinsOverAStaleLocalEdit() {
        val api = Fake().apply { server = BudgetProfile(7_000_000, 1, emptyList(), System.currentTimeMillis() + 100_000) }
        store.save(a.ownerId, 5_000_000, 27, listOf(rent), now = 1000)
        assertFalse(BudgetSync.run(store, a, api))
        assertEquals(7_000_000L, store.get(a.ownerId)!!.incomeMinor)
        assertFalse(store.dirty(a.ownerId))
    }

    @Test fun cleanPhoneAdoptsTheServerProfile() {
        val api = Fake().apply { server = BudgetProfile(6_000_000, 5, listOf(rent), 5000) }
        assertFalse(BudgetSync.run(store, a, api))
        assertEquals(6_000_000L, store.get(a.ownerId)!!.incomeMinor)
        assertFalse(store.dirty(a.ownerId))
        assertNull(store.get(b.ownerId))
    }

    @Test fun olderServerCopyDoesNotOverwriteALocalProfile() {
        store.adopt(a.ownerId, BudgetProfile(6_000_000, 5, emptyList(), 9000))
        val api = Fake().apply { server = BudgetProfile(1_000_000, 1, emptyList(), 100) }
        BudgetSync.run(store, a, api)
        assertEquals(6_000_000L, store.get(a.ownerId)!!.incomeMinor)
    }

    @Test fun unconfiguredBuildDoesNothingAndKeepsTheEdit() {
        val api = Fake().apply { configured = false }
        store.save(a.ownerId, 5_000_000, 27, listOf(rent), now = 1000)
        assertFalse(BudgetSync.run(store, a, api))
        assertTrue(store.dirty(a.ownerId))
        assertNotNull(store.get(a.ownerId))
    }

    @Test fun corruptStoredDataReadsAsNoProfile() {
        RuntimeEnvironment.getApplication().getSharedPreferences("budget", 0).edit().putString("profile_owner-a", "{oops").commit()
        assertNull(store.get(a.ownerId))
    }

    @Test fun eachSaveGetsAStrictlyLaterTimestamp() {
        val first = store.save(a.ownerId, 5_000_000, 27, emptyList(), now = 5000)
        val second = store.save(a.ownerId, 5_000_000, 27, emptyList(), now = 4000)
        assertTrue(second.updatedAt > first.updatedAt)
    }
}
