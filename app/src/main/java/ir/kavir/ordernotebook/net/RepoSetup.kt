package ir.kavir.ordernotebook.net

import android.content.Context
import ir.kavir.ordernotebook.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * One-tap setup of the user's OWN orders repository: checks it (creating a private one if the token may),
 * writes the Telegram workflow + script (update in place, never duplicated), config/notify.json and registers the
 * bot token / chat id as Actions secrets (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID). Secret values are never stored or logged.
 */
class RepoSetup(private val ctx: Context, private val prefs: Prefs) {
    data class Step(val title: String, val ok: Boolean, val detail: String = "")

    companion object {
        const val WORKFLOW = ".github/workflows/telegram-orders.yml"
        const val OLD_WORKFLOW = ".github/workflows/telegram.yml"
        const val WORKFLOW_FILE = "telegram-orders.yml"
        const val SECRET_TOKEN = "TELEGRAM_BOT_TOKEN"
        const val SECRET_CHAT = "TELEGRAM_CHAT_ID"
        private val tokenRe = Regex("""^\d{3,}:[A-Za-z0-9_-]{10,}$""")
        private val chatRe = Regex("""^(-?\d{3,}|@[A-Za-z0-9_]{4,})$""")

        fun validateInput(token: String, chat: String): String? = when {
            !tokenRe.matches(token.trim()) -> "Bot Token درست نیست (نمونه: 123456789:AAE...)"
            !chatRe.matches(chat.trim()) -> "Chat ID درست نیست (عدد، مثلاً 123456789 یا -100123…، یا @channel)"
            else -> null
        }
    }

    private fun asset(name: String): ByteArray = ctx.assets.open("github/$name").use { it.readBytes() }

    private fun putIfChanged(api: GitHub, path: String, bytes: ByteArray, msg: String): Boolean {
        val remote = api.sha(path)
        if (remote == GitHubApi.blobSha(bytes)) return false
        api.put(path, bytes, msg, remote)
        return true
    }

    /** Runs when GitHub is connected: builds the repo structure + Telegram workflow and script (no secrets involved yet). */
    suspend fun prepare(): List<Step> = setup(null, null)

    suspend fun run(botToken: String, chatId: String): List<Step> = setup(botToken, chatId)

    private suspend fun setup(botToken: String?, chatId: String?): List<Step> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        fun fail(t: String, d: String): List<Step> { steps += Step(t, false, d); return steps }
        if (botToken != null && chatId != null) validateInput(botToken, chatId)?.let { return@withContext fail("بررسی ورودی", it) }
        if (!prefs.ghConfigured) return@withContext fail("اتصال GitHub", "ابتدا توکن و مخزن GitHub را در بخش اتصال وارد کنید")
        val api = GitHub(prefs)
        try {
            // 1) repository (created automatically when it does not exist and the token is allowed to)
            val info = try { api.info() } catch (e: GitHub.ApiException) {
                if (e.code != 404) throw e
                api.createPrivateRepo(); steps += Step("ساخت مخزن Private", true, prefs.ghRepo); api.info()
            }
            if (!info.isPrivate) return@withContext fail("مخزن سفارش‌ها", "این مخزن عمومی است. سفارش‌ها و اطلاعات مشتری فقط در مخزن Private ذخیره می‌شوند")
            if (!info.canPush) return@withContext fail("دسترسی نوشتن", "توکن اجازه‌ی نوشتن در این مخزن را ندارد")
            steps += Step("مخزن Private", true, info.fullName)

            // 2) workflow + script + config (created when missing, updated in place when different)
            val wf = putIfChanged(api, WORKFLOW, asset("telegram-orders.yml"), "Setup: Telegram workflow")
            // earlier versions used .github/workflows/telegram.yml; remove only OUR old file so two workflows never run
            api.sha(OLD_WORKFLOW)?.let { sha ->
                val old = api.getText(OLD_WORKFLOW).orEmpty()
                if (old.contains("telegram_notify.py")) api.delete(OLD_WORKFLOW, sha, "Setup: replace old Telegram workflow")
            }
            for (keep in listOf("Order/orders/.gitkeep", "Order/images/.gitkeep", "Order/data/.gitkeep"))
                if (api.sha(keep) == null) api.put(keep, ByteArray(0), "Setup: create ${keep.substringBeforeLast('/')}", null)
            val sc = putIfChanged(api, ".github/scripts/telegram_notify.py", asset("telegram_notify.py"), "Setup: Telegram script")
            if (api.sha("README.md") == null) api.put("README.md",
                "# Order Notebook data\n\nPrivate storage for orders (orders/<n>/). Written by the Order Notebook app; Telegram messages are sent by GitHub Actions.\n".toByteArray(), "Setup: README", null)
            val cfg = JSONObject().put("enabled", prefs.autoSend).put("lang", prefs.telegramLang).put("template", prefs.telegramTemplate).toString(2).toByteArray()
            putIfChanged(api, "config/notify.json", cfg, "Config: Telegram notifications")
            steps += Step("Workflow و اسکریپت تلگرام", true, if (wf || sc) "ساخته/به‌روز شد" else "از قبل به‌روز بود")

            if (botToken == null || chatId == null) return@withContext steps // connection only: secrets come later from Telegram Settings
            // 3) secrets
            api.putSecret(SECRET_TOKEN, botToken.trim())
            api.putSecret(SECRET_CHAT, chatId.trim())
            val names = api.secretNames()
            if (SECRET_TOKEN !in names || SECRET_CHAT !in names) return@withContext fail("بررسی Secrets", "Secretها بعد از ثبت پیدا نشدند")
            steps += Step("ثبت و تأیید Secrets", true, "$SECRET_TOKEN ، $SECRET_CHAT")
            steps
        } catch (e: GitHub.ApiException) { fail("خطای GitHub", e.message ?: "") }
        catch (e: IOException) { fail("خطای اتصال", e.message ?: "") }
    }
}
