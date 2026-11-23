package com.daybook.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExpenseStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearBefore() { context.deleteDatabase("daybook.db") }
    @After fun clearAfter() { context.deleteDatabase("daybook.db") }

    @Test fun recordsAndPendingOperationsAreDurableAndAccountScoped() {
        val id: String
        ExpenseStore(context).use { store ->
            id = store.add("account-a", 1234, "Food", "2026-11-23", "Lunch")
            val submitted = store.one("account-a", id)
            assertNotNull(submitted)
            assertNull(store.one("account-b", id))
            assertFalse(store.changePending("account-b", id, 9999, "Other", "2026-11-23", "Wrong account"))
            assertFalse(store.deletePending("account-b", id))
            store.confirm("account-b", submitted!!, submitted.copy(serverId = "wrong-owner"))
            assertEquals(1, store.pending("account-a").size)
            assertTrue(store.pending("account-b").isEmpty())
        }
        ExpenseStore(context).use { store ->
            assertEquals(1234L, store.one("account-a", id)?.amountMinor)
            assertEquals(1, store.pending("account-a").size)
            store.replaceSynced("account-b", LocalExpense(id, "account-b", "server-b", 5000, "Bills", "2026-11-22", "", 1, "synced"))
            assertEquals(1234L, store.one("account-a", id)?.amountMinor)
            assertEquals(5000L, store.one("account-b", id)?.amountMinor)
            assertEquals(1, store.pending("account-a").size)
            assertTrue(store.pending("account-b").isEmpty())
        }
    }

    @Test fun editAndDeleteDuringUploadRemainQueuedUntilServerAcknowledges() {
        ExpenseStore(context).use { store ->
            val id = store.add("account-a", 1000, "Food", "2026-11-23", "Before")
            val submitted = store.one("account-a", id)!!
            assertTrue(store.changePending("account-a", id, 2000, "Food", "2026-11-23", "After"))
            store.confirm("account-a", submitted, submitted.copy(serverId = "server-a", version = 1))
            assertEquals(2000L, store.one("account-a", id)?.amountMinor)
            assertEquals("pending", store.one("account-a", id)?.status)
            assertEquals("server-a", store.one("account-a", id)?.serverId)
            assertEquals(1, store.pending("account-a").size)

            val updated = store.one("account-a", id)!!
            assertTrue(store.deletePending("account-a", id))
            assertTrue(store.all("account-a").isEmpty())
            assertEquals("deleting", store.pending("account-a").single().status)
            store.confirm("account-a", updated, updated.copy(version = 2))
            assertEquals("deleting", store.pending("account-a").single().status)
            store.deleteSynced("account-a", id)
            assertTrue(store.pending("account-a").isEmpty())
            assertTrue(store.all("account-a").isEmpty())
        }
    }

    @Test fun versionOneDatabaseMigratesWithoutLosingPendingExpense() {
        val file = context.getDatabasePath("daybook.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE expenses (client_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, server_id TEXT,
                amount_minor INTEGER NOT NULL, category TEXT NOT NULL, occurred_on TEXT NOT NULL,
                note TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE pending_operations (client_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL,
                FOREIGN KEY(client_id) REFERENCES expenses(client_id))""")
            db.execSQL("INSERT INTO expenses VALUES ('old-id','account-a',NULL,321,'Food','2026-11-23','Old',1,'pending')")
            db.execSQL("INSERT INTO pending_operations VALUES ('old-id','account-a')")
            db.version = 1
        }
        ExpenseStore(context).use { store ->
            assertEquals(321L, store.one("account-a", "old-id")?.amountMinor)
            assertEquals("old-id", store.pending("account-a").single().clientId)
            store.replaceSynced("account-b", LocalExpense("old-id", "account-b", "server-b", 654, "Food", "2026-11-23", "", 1, "synced"))
            assertEquals(321L, store.one("account-a", "old-id")?.amountMinor)
            assertEquals(654L, store.one("account-b", "old-id")?.amountMinor)
        }
    }
}
