package com.daybook.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.UUID

data class LocalExpense(
    val clientId: String, val ownerId: String, val serverId: String?, val amountMinor: Long,
    val category: String, val occurredOn: String, val note: String, val version: Int,
    val status: String
)

class ExpenseStore(context: Context) : SQLiteOpenHelper(context, "daybook.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE expenses (client_id TEXT NOT NULL, owner_id TEXT NOT NULL, server_id TEXT,
            amount_minor INTEGER NOT NULL, category TEXT NOT NULL, occurred_on TEXT NOT NULL,
            note TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL,
            PRIMARY KEY(owner_id, client_id))""")
        db.execSQL("""CREATE TABLE pending_operations (client_id TEXT NOT NULL, owner_id TEXT NOT NULL,
            PRIMARY KEY(owner_id, client_id), FOREIGN KEY(owner_id, client_id) REFERENCES expenses(owner_id, client_id))""")
        db.execSQL("CREATE INDEX owner_date ON expenses(owner_id, occurred_on DESC)")
        db.execSQL("CREATE INDEX pending_owner ON pending_operations(owner_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1 || newVersion != 2) error("Unsupported Daybook database version")
        db.execSQL("""CREATE TABLE expenses_v2 (client_id TEXT NOT NULL, owner_id TEXT NOT NULL, server_id TEXT,
            amount_minor INTEGER NOT NULL, category TEXT NOT NULL, occurred_on TEXT NOT NULL,
            note TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL,
            PRIMARY KEY(owner_id, client_id))""")
        db.execSQL("""INSERT INTO expenses_v2 (client_id,owner_id,server_id,amount_minor,category,occurred_on,note,version,status)
            SELECT client_id,owner_id,server_id,amount_minor,category,occurred_on,note,version,status FROM expenses""")
        db.execSQL("""CREATE TABLE pending_operations_v2 (client_id TEXT NOT NULL, owner_id TEXT NOT NULL,
            PRIMARY KEY(owner_id, client_id), FOREIGN KEY(owner_id, client_id) REFERENCES expenses_v2(owner_id, client_id))""")
        db.execSQL("INSERT INTO pending_operations_v2 (client_id,owner_id) SELECT client_id,owner_id FROM pending_operations")
        db.execSQL("DROP TABLE pending_operations")
        db.execSQL("DROP TABLE expenses")
        db.execSQL("ALTER TABLE expenses_v2 RENAME TO expenses")
        db.execSQL("ALTER TABLE pending_operations_v2 RENAME TO pending_operations")
        db.execSQL("CREATE INDEX owner_date ON expenses(owner_id, occurred_on DESC)")
        db.execSQL("CREATE INDEX pending_owner ON pending_operations(owner_id)")
    }

    fun add(ownerId: String, amountMinor: Long, category: String, date: String, note: String): String {
        require(amountMinor in 1..100_000_000_000 && category in CATEGORIES && LocalDate.parse(date).toString() == date && note.length <= 240)
        val id = UUID.randomUUID().toString()
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("client_id", id); put("owner_id", ownerId); put("amount_minor", amountMinor)
                put("category", category); put("occurred_on", date); put("note", note); put("version", 1); put("status", "pending")
            }
            writableDatabase.insertOrThrow("expenses", null, values)
            writableDatabase.insertOrThrow("pending_operations", null, ContentValues().apply { put("client_id", id); put("owner_id", ownerId) })
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return id
    }
    fun all(ownerId: String, includeDeleting: Boolean = false): List<LocalExpense> = readableDatabase.rawQuery(
        "SELECT client_id,owner_id,server_id,amount_minor,category,occurred_on,note,version,status FROM expenses WHERE owner_id=? ${if (includeDeleting) "" else "AND status NOT IN ('deleting','deleting_failed')"} ORDER BY occurred_on DESC,rowid DESC",
        arrayOf(ownerId)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(LocalExpense(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getInt(7), cursor.getString(8)))
        }
    }
    fun one(ownerId: String, clientId: String): LocalExpense? = all(ownerId, true).firstOrNull { it.clientId == clientId }
    fun pending(ownerId: String): List<LocalExpense> = all(ownerId, true).filter { expense ->
        expense.status != "failed" && expense.status != "deleting_failed" &&
        readableDatabase.rawQuery("SELECT 1 FROM pending_operations WHERE owner_id=? AND client_id=?", arrayOf(ownerId, expense.clientId)).use { it.moveToFirst() }
    }
    fun unsyncedCount(ownerId: String): Int = readableDatabase.rawQuery("SELECT count(*) FROM expenses WHERE owner_id=? AND status!='synced'", arrayOf(ownerId)).use { it.moveToFirst(); it.getInt(0) }
    fun retryFailed(ownerId: String) {
        writableDatabase.execSQL("UPDATE expenses SET status='pending' WHERE owner_id=? AND status='failed'", arrayOf(ownerId))
        writableDatabase.execSQL("UPDATE expenses SET status='deleting' WHERE owner_id=? AND status='deleting_failed'", arrayOf(ownerId))
    }
    fun changePending(ownerId: String, clientId: String, amountMinor: Long, category: String, date: String, note: String): Boolean {
        val current = one(ownerId, clientId) ?: return false
        if (current.status == "synced" || current.status.startsWith("deleting")) return false
        val values = ContentValues().apply { put("amount_minor", amountMinor); put("category", category); put("occurred_on", date); put("note", note); put("status", "pending") }
        return writableDatabase.update("expenses", values, "owner_id=? AND client_id=?", arrayOf(ownerId, clientId)) == 1
    }
    fun deletePending(ownerId: String, clientId: String): Boolean {
        val current = one(ownerId, clientId) ?: return false
        if (current.status == "synced") return false
        return writableDatabase.update("expenses", ContentValues().apply { put("status", "deleting") },
            "owner_id=? AND client_id=?", arrayOf(ownerId, clientId)) == 1
    }
    fun confirm(ownerId: String, submitted: LocalExpense, server: LocalExpense) {
        writableDatabase.beginTransaction()
        try {
            val current = one(ownerId, submitted.clientId)
            if (current != null) {
                val unchanged = current.amountMinor == submitted.amountMinor && current.category == submitted.category &&
                    current.occurredOn == submitted.occurredOn && current.note == submitted.note && current.status != "deleting"
                val values = ContentValues().apply {
                    put("server_id", server.serverId); put("version", server.version)
                    put("status", if (unchanged) "synced" else current.status)
                }
                writableDatabase.update("expenses", values, "owner_id=? AND client_id=?", arrayOf(ownerId, submitted.clientId))
                if (unchanged) writableDatabase.delete("pending_operations", "owner_id=? AND client_id=?", arrayOf(ownerId, submitted.clientId))
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun failed(ownerId: String, clientId: String) {
        val deleting = one(ownerId, clientId)?.status?.startsWith("deleting") == true
        writableDatabase.update("expenses", ContentValues().apply { put("status", if (deleting) "deleting_failed" else "failed") }, "owner_id=? AND client_id=?", arrayOf(ownerId, clientId))
    }
    fun replaceSynced(ownerId: String, expense: LocalExpense) {
        val existing = one(ownerId, expense.clientId)
        if (existing != null && existing.status != "synced") return
        val values = ContentValues().apply {
            put("client_id", expense.clientId); put("owner_id", ownerId); put("server_id", expense.serverId)
            put("amount_minor", expense.amountMinor); put("category", expense.category); put("occurred_on", expense.occurredOn)
            put("note", expense.note); put("version", expense.version); put("status", "synced")
        }
        writableDatabase.insertWithOnConflict("expenses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun acceptServerCopy(ownerId: String, expense: LocalExpense) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("pending_operations", "owner_id=? AND client_id=?", arrayOf(ownerId, expense.clientId))
            writableDatabase.delete("expenses", "owner_id=? AND client_id=?", arrayOf(ownerId, expense.clientId))
            replaceSynced(ownerId, expense)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun deleteSynced(ownerId: String, clientId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("pending_operations", "owner_id=? AND client_id=?", arrayOf(ownerId, clientId))
            writableDatabase.delete("expenses", "owner_id=? AND client_id=?", arrayOf(ownerId, clientId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    companion object { val CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Other") }
}
