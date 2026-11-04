package org.qamzor.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-managed archive passwords; every secret payload is encrypted by AndroidSecretVault/Android Keystore. */
internal object QamzorPasswordVault {
    data class Entry(val id: String, val label: String, val updatedAt: Long)
    private const val PREFS = "qamzor-password-vault"
    private const val KEY = "entries"

    @Synchronized
    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val label = item.optString("label")
            val updated = item.optLong("updatedAt")
            Entry(id, label, updated).takeIf {
                id.matches(Regex("[0-9a-f]{32}")) && label.isNotBlank() && label.length <= 120 && updated > 0
            }
        }.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun save(context: Context, label: String, password: String): Entry {
        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank() && cleanLabel.length <= 120) { "Enter a valid password label" }
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Password is empty or too long" }
        val id = UUID.randomUUID().toString().replace("-", "")
        AndroidSecretVault.put(context, "archive-password-$id", password.toByteArray(Charsets.UTF_8))
        val entry = Entry(id, cleanLabel, System.currentTimeMillis())
        persist(context, list(context) + entry)
        return entry
    }

    fun reveal(context: Context, id: String): String {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid password entry" }
        require(list(context).any { it.id == id }) { "Password entry no longer exists" }
        val bytes = AndroidSecretVault.get(context, "archive-password-$id")
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid password entry" }
        persist(context, list(context).filterNot { it.id == id })
        AndroidSecretVault.remove(context, "archive-password-$id")
    }

    private fun persist(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("updatedAt", it.updatedAt)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()) {
            "Could not save password vault metadata"
        }
    }
}
