package com.daybook.mobile.data

import com.daybook.mobile.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ApiError(val status: Int, val code: String, message: String) : IOException(message)

interface ExpenseGateway {
    val configured: Boolean
    fun create(session: AccountSession, expense: LocalExpense): LocalExpense
    fun update(session: AccountSession, expense: LocalExpense): LocalExpense
    fun delete(session: AccountSession, expense: LocalExpense)
    fun listMonth(session: AccountSession, month: String): List<LocalExpense>
}

class ApiClient : ExpenseGateway {
    private val origin = BuildConfig.API_BASE_URL.trimEnd('/')
    override val configured: Boolean get() = origin.startsWith("https://")

    private fun call(path: String, method: String = "GET", session: AccountSession? = null, body: JSONObject? = null): JSONObject {
        if (!configured) throw IOException("Set DAYBOOK_API_BASE_URL to your HTTPS server address before building the app")
        val connection = URL("$origin$path").openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val status = connection.responseCode
            val raw = (if (status < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(raw)
            if (status >= 400) {
                val issue = result.optJSONObject("error")
                throw ApiError(status, issue?.optString("code") ?: "http_error", issue?.optString("message") ?: "Request failed")
            }
            result
        } finally { connection.disconnect() }
    }
    private fun session(response: JSONObject): AccountSession {
        val account = response.getJSONObject("account")
        return AccountSession(account.getString("id"), account.getString("email"), response.getString("token"))
    }
    fun login(email: String, password: String): AccountSession =
        session(call("/api/auth/login", "POST", body = JSONObject().put("email", email).put("password", password)))
    fun signup(email: String, password: String): AccountSession =
        session(call("/api/auth/signup", "POST", body = JSONObject().put("email", email).put("password", password)))
    fun me(session: AccountSession): Boolean = try { call("/api/auth/me", session = session); true } catch (error: ApiError) { if (error.status == 401) false else throw error }
    fun logout(session: AccountSession) { call("/api/auth/logout", "POST", session) }
    override fun create(session: AccountSession, expense: LocalExpense): LocalExpense {
        val response = call("/api/expenses", "POST", session, JSONObject()
            .put("clientId", expense.clientId).put("amountMinor", expense.amountMinor).put("currency", "INR")
            .put("category", expense.category).put("occurredOn", expense.occurredOn).put("note", expense.note))
        return decode(session.ownerId, response.getJSONObject("expense"))
    }
    override fun update(session: AccountSession, expense: LocalExpense): LocalExpense {
        val response = call("/api/expenses/${expense.serverId}", "PATCH", session, JSONObject()
            .put("amountMinor", expense.amountMinor).put("currency", "INR").put("category", expense.category)
            .put("occurredOn", expense.occurredOn).put("note", expense.note).put("version", expense.version))
        return decode(session.ownerId, response.getJSONObject("expense"))
    }
    fun get(session: AccountSession, serverId: String): LocalExpense = decode(session.ownerId, call("/api/expenses/$serverId", session = session).getJSONObject("expense"))
    override fun delete(session: AccountSession, expense: LocalExpense) { call("/api/expenses/${expense.serverId}?version=${expense.version}", "DELETE", session) }
    override fun listMonth(session: AccountSession, month: String): List<LocalExpense> {
        val from = "$month-01"
        val to = java.time.YearMonth.parse(month).atEndOfMonth().toString()
        val result = mutableListOf<LocalExpense>()
        var cursor: String? = null
        do {
            val page = call("/api/expenses?from=$from&to=$to" + (cursor?.let { "&cursor=$it" } ?: ""), session = session)
            val array = page.getJSONArray("expenses")
            for (index in 0 until array.length()) result.add(decode(session.ownerId, array.getJSONObject(index)))
            cursor = page.optString("nextCursor").takeIf { it.isNotEmpty() && it != "null" }
        } while (cursor != null)
        return result
    }
    private fun decode(ownerId: String, obj: JSONObject) = LocalExpense(
        obj.getString("clientId"), ownerId, obj.getString("id"), obj.getLong("amountMinor"),
        obj.getString("category"), obj.getString("occurredOn"), obj.optString("note"), obj.getInt("version"), "synced")
}
