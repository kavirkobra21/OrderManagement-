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

    init {
        // Bot Token / Chat ID now live only in GitHub Secrets; wipe anything older versions stored here
        if (sp.contains("botToken") || sp.contains("chatId")) sp.edit().remove("botToken").remove("chatId").apply()
    }

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d

    var telegramLang: String get() = s("tgLang", "fa"); set(v) = sp.edit().putString("tgLang", v).apply()
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
