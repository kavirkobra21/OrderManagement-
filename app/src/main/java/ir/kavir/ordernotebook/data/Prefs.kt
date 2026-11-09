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

    var botToken: String get() = s("botToken"); set(v) = sp.edit().putString("botToken", v.trim()).apply()
    var chatId: String get() = s("chatId"); set(v) = sp.edit().putString("chatId", v.trim()).apply()
    var telegramLang: String get() = s("tgLang", "fa"); set(v) = sp.edit().putString("tgLang", v).apply()
    var autoSend: Boolean get() = sp.getBoolean("autoSend", true); set(v) = sp.edit().putBoolean("autoSend", v).apply()
    var ghToken: String get() = s("ghToken"); set(v) = sp.edit().putString("ghToken", v.trim()).apply()
    var ghRepo: String get() = s("ghRepo", "kavirkobra21/OrderManagement-"); set(v) = sp.edit().putString("ghRepo", ir.kavir.ordernotebook.net.GitHubApi.parseRepo(v)).apply()
    var ghLastSync: Long get() = sp.getLong("ghLastSync", 0); set(v) = sp.edit().putLong("ghLastSync", v).apply()
    var invoiceCounter: Long get() = sp.getLong("invoice", 0); set(v) = sp.edit().putLong("invoice", v).apply()

    val tgConfigured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()
    val ghConfigured: Boolean get() = ghToken.isNotBlank() && ir.kavir.ordernotebook.net.GitHubApi.isValidRepo(ghRepo)

    fun nextInvoice(): String { invoiceCounter += 1; return invoiceCounter.toString() }

    /** non-secret + secret settings as a map for backup */
    fun export(): Map<String, String> = mapOf(
        "botToken" to botToken, "chatId" to chatId,
        "telegramLang" to telegramLang, "autoSend" to autoSend.toString(),
        "ghRepo" to ghRepo, "invoiceCounter" to invoiceCounter.toString(),
    )

    fun import(m: Map<String, String>) {
        m["botToken"]?.let { botToken = it }
        m["chatId"]?.let { chatId = it }
        m["telegramLang"]?.let { telegramLang = it }
        m["autoSend"]?.let { autoSend = it.toBoolean() }
        m["ghRepo"]?.let { ghRepo = it }
        m["invoiceCounter"]?.toLongOrNull()?.let { invoiceCounter = it }
    }
}
