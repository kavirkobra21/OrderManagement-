package ir.kavir.ordernotebook.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Secrets (bot token, GitHub token) live in EncryptedSharedPreferences, never in the APK. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "secure_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("settings_fallback", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d

    var telegramLang: String get() = s("tgLang", "fa"); set(v) = sp.edit().putString("tgLang", v).apply()
    /** "actions" = GitHub Actions sends (default) | "device" = this phone talks to Telegram directly. Kept until changed manually. */
    var telegramMethod: String get() = s("tgMethod", METHOD_ACTIONS); set(v) = sp.edit().putString("tgMethod", if (v == METHOD_DEVICE) METHOD_DEVICE else METHOD_ACTIONS).apply()
    /** only used by the "My Device" method; stored encrypted on the phone and never written to GitHub, logs or backups */
    var botToken: String get() = s("botToken"); set(v) = sp.edit().putString("botToken", v.trim()).apply()
    var chatId: String get() = s("chatId"); set(v) = sp.edit().putString("chatId", v.trim()).apply()
    var tgSyncAt: Long get() = sp.getLong("tgSyncAt", 0); set(v) = sp.edit().putLong("tgSyncAt", v).apply()
    var tgSyncSummary: String get() = s("tgSyncSummary"); set(v) = sp.edit().putString("tgSyncSummary", v).apply()
    /** Telegram message ids of permanently deleted orders, removed from the chat on the next manual sync */
    var tgTombstones: Set<Long>
        get() = s("tgTomb").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        set(v) = sp.edit().putString("tgTomb", v.joinToString(",")).apply()
    fun addTombstones(ids: Collection<Long>) { val add = ids.filter { it > 0 }; if (add.isNotEmpty()) tgTombstones = tgTombstones + add }

    var autoSend: Boolean get() = sp.getBoolean("autoSend", true); set(v) = sp.edit().putBoolean("autoSend", v).apply()
    /** customer-facing order text with {placeholders}; edited in Settings */
    var formTemplate: String get() = s("formTemplate", ir.kavir.ordernotebook.util.FormTemplate.DEFAULT); set(v) = sp.edit().putString("formTemplate", v).apply()
    /** custom Telegram message ("" = built-in default format); rendered by the GitHub Action */
    var telegramTemplate: String get() = s("tgTemplate", ""); set(v) = sp.edit().putString("tgTemplate", v).apply()
    var ghToken: String get() = s("ghToken"); set(v) = sp.edit().putString("ghToken", v.trim()).apply()
    var ghRepo: String get() = s("ghRepo", "kavirkobra21/OrderManagement-"); set(v) = sp.edit().putString("ghRepo", ir.kavir.ordernotebook.net.GitHubApi.parseRepo(v)).apply()
    /** repository whose Releases the in-app updater checks (the app's own build repo, not the orders repo) */
    var updateRepo: String get() = s("updateRepo", "kavirkobra21/OrderManagement-"); set(v) = sp.edit().putString("updateRepo", ir.kavir.ordernotebook.net.GitHubApi.parseRepo(v)).apply()
    var ghLastSync: Long get() = sp.getLong("ghLastSync", 0); set(v) = sp.edit().putLong("ghLastSync", v).apply()
    var invoiceCounter: Long get() = sp.getLong("invoice", 0); set(v) = sp.edit().putLong("invoice", v).apply()

    companion object { const val METHOD_ACTIONS = "actions"; const val METHOD_DEVICE = "device" }

    val ghConfigured: Boolean get() = ghToken.isNotBlank() && ir.kavir.ordernotebook.net.GitHubApi.isValidRepo(ghRepo)

    fun nextInvoice(): String { invoiceCounter += 1; return invoiceCounter.toString() }

    /** non-secret + secret settings as a map for backup */
    fun export(): Map<String, String> = mapOf(
        "telegramLang" to telegramLang, "autoSend" to autoSend.toString(),
        "ghRepo" to ghRepo, "invoiceCounter" to invoiceCounter.toString(),
    )

    fun import(m: Map<String, String>) {
        m["telegramLang"]?.let { telegramLang = it }
        m["autoSend"]?.let { autoSend = it.toBoolean() }
        m["ghRepo"]?.let { ghRepo = it }
        m["invoiceCounter"]?.toLongOrNull()?.let { invoiceCounter = it }
    }
}
