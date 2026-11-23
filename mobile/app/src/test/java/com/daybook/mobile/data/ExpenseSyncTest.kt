package com.daybook.mobile.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExpenseSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val month = YearMonth.of(2026, 11)
    private val accountA = AccountSession("account-a", "a@example.test", "token-a")
    private val accountB = AccountSession("account-b", "b@example.test", "token-b")

    @Before fun clearBefore() { context.deleteDatabase("daybook.db") }
    @After fun clearAfter() { context.deleteDatabase("daybook.db") }

    @Test fun offlineCreateSurvivesRestartAndSyncsOnlyForItsAccount() {
        val id = ExpenseStore(context).use { it.add(accountA.ownerId, 1500, "Food", "2026-11-23", "Lunch") }
        val gateway = FakeGateway().apply { offline = true }
        assertTrue(ExpenseSync.runForAccount(context, month, accountA, gateway))
        ExpenseStore(context).use { assertEquals("pending", it.one(accountA.ownerId, id)?.status) }

        gateway.offline = false
        assertFalse(ExpenseSync.runForAccount(context, month, accountB, gateway))
        assertEquals(0, gateway.created)
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        ExpenseStore(context).use {
            assertEquals("synced", it.one(accountA.ownerId, id)?.status)
            assertTrue(it.pending(accountA.ownerId).isEmpty())
            assertTrue(it.all(accountB.ownerId).isEmpty())
        }
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
    }

    @Test fun editDuringUploadIsPatchedAfterServerCreate() {
        val id = ExpenseStore(context).use { it.add(accountA.ownerId, 1000, "Food", "2026-11-23", "Before") }
        val gateway = FakeGateway().apply {
            onCreate = {
                ExpenseStore(context).use { store ->
                    assertTrue(store.changePending(accountA.ownerId, id, 2500, "Food", "2026-11-23", "After"))
                }
            }
        }
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
        assertEquals(1, gateway.updated)
        assertEquals(2500L, gateway.server.getValue("account-a:$id").amountMinor)
        ExpenseStore(context).use {
            assertEquals(2500L, it.one(accountA.ownerId, id)?.amountMinor)
            assertEquals("synced", it.one(accountA.ownerId, id)?.status)
        }
    }

    @Test fun deleteDuringUploadIsAppliedAfterServerCreate() {
        val id = ExpenseStore(context).use { it.add(accountA.ownerId, 1000, "Food", "2026-11-23", "Delete") }
        val gateway = FakeGateway().apply {
            onCreate = { ExpenseStore(context).use { store -> assertTrue(store.deletePending(accountA.ownerId, id)) } }
        }
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
        assertEquals(1, gateway.deleted)
        assertTrue(gateway.server.isEmpty())
        ExpenseStore(context).use {
            assertTrue(it.pending(accountA.ownerId).isEmpty())
            assertTrue(it.all(accountA.ownerId).isEmpty())
        }
    }

    @Test fun expiredSessionLeavesPendingExpenseForTheOriginalAccount() {
        val id = ExpenseStore(context).use { it.add(accountA.ownerId, 990, "Other", "2026-11-23", "Keep") }
        val gateway = FakeGateway().apply { unauthorized = true }
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(0, gateway.created)
        ExpenseStore(context).use {
            assertEquals("pending", it.one(accountA.ownerId, id)?.status)
            assertEquals(1, it.pending(accountA.ownerId).size)
        }
        gateway.unauthorized = false
        assertFalse(ExpenseSync.runForAccount(context, month, accountB, gateway))
        assertEquals(0, gateway.created)
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
    }

    @Test fun lostCreateResponseRetriesWithTheSameClientIdWithoutDuplicate() {
        val id = ExpenseStore(context).use { it.add(accountA.ownerId, 745, "Transport", "2026-11-23", "Bus") }
        val gateway = FakeGateway().apply { loseCreateResponse = true }
        assertTrue(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
        ExpenseStore(context).use { assertEquals("pending", it.one(accountA.ownerId, id)?.status) }
        assertFalse(ExpenseSync.runForAccount(context, month, accountA, gateway))
        assertEquals(1, gateway.created)
        assertEquals(1, gateway.server.size)
        ExpenseStore(context).use {
            assertEquals("synced", it.one(accountA.ownerId, id)?.status)
            assertTrue(it.pending(accountA.ownerId).isEmpty())
        }
    }

    private class FakeGateway : ExpenseGateway {
        override val configured = true
        val server = mutableMapOf<String, LocalExpense>()
        var offline = false
        var unauthorized = false
        var loseCreateResponse = false
        var created = 0
        var updated = 0
        var deleted = 0
        var onCreate: (() -> Unit)? = null
        private fun key(session: AccountSession, item: LocalExpense) = "${session.ownerId}:${item.clientId}"
        override fun create(session: AccountSession, expense: LocalExpense): LocalExpense {
            if (unauthorized) throw ApiError(401, "unauthorized", "Log in again")
            if (offline) throw IOException("No connection")
            val key = key(session, expense)
            server[key]?.let { return it }
            val createdExpense = expense.copy(serverId = "server-${expense.clientId}", version = 1, status = "synced")
            server[key] = createdExpense
            created++
            onCreate?.invoke()
            if (loseCreateResponse) { loseCreateResponse = false; throw IOException("Response lost") }
            return createdExpense
        }
        override fun update(session: AccountSession, expense: LocalExpense): LocalExpense {
            if (offline) throw IOException("No connection")
            val key = key(session, expense)
            val old = server.getValue(key)
            val updatedExpense = expense.copy(serverId = old.serverId, version = old.version + 1, status = "synced")
            server[key] = updatedExpense
            updated++
            return updatedExpense
        }
        override fun delete(session: AccountSession, expense: LocalExpense) {
            if (offline) throw IOException("No connection")
            server.remove(key(session, expense))
            deleted++
        }
        override fun listMonth(session: AccountSession, month: String): List<LocalExpense> = server.values.filter {
            it.ownerId == session.ownerId && it.occurredOn.startsWith(month)
        }
    }
}
