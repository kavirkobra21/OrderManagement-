package ir.kavir.ordernotebook.net

import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.Prefs
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.util.TelegramText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The ONLY place that talks to Telegram or triggers the Telegram GitHub Action, and only from the
 * "Sync to Telegram" button (or the per-order Resend button). Saving, editing or deleting an order never gets here.
 *
 *  - "device"  : this phone calls the Telegram Bot API directly (Bot Token / Chat ID stay encrypted on the phone)
 *  - "actions" : the phone uploads pending changes + dispatches the workflow; GitHub Actions calls Telegram
 *
 * Both methods are idempotent: message ids / content hashes are stored after every successful call, so a repeated
 * sync only sends new orders, edits changed ones and removes deleted ones.
 */
class TelegramSync(private val repo: Repo) {
    data class Summary(val sent: Int = 0, val edited: Int = 0, val deleted: Int = 0, val failed: Int = 0, val errors: List<String> = emptyList()) {
        val ok: Boolean get() = failed == 0 && errors.isEmpty()
        fun text(): String = (if (ok) "✓ همگام‌سازی کامل شد" else "✗ همگام‌سازی با خطا همراه بود") +
            "\nارسال‌شده: $sent • ویرایش‌شده: $edited • حذف‌شده: $deleted • ناموفق: $failed" +
            errors.take(5).joinToString("") { "\n• $it" }
    }

    companion object { private val lock = Mutex() }

    private val prefs: Prefs get() = repo.prefs
    private val dao get() = repo.dao

    /** Runs a manual sync with the saved method; never throws. */
    suspend fun run(onProgress: (String) -> Unit): Summary {
        if (!lock.tryLock()) return Summary(failed = 1, errors = listOf("همگام‌سازی دیگری در حال اجراست"))
        try {
            val s = try {
                if (prefs.telegramMethod == Prefs.METHOD_DEVICE) viaDevice(onProgress) else viaActions(onProgress)
            } catch (e: IOException) {
                Summary(failed = 1, errors = listOf("خطای اتصال: ${e.message}"))
            } catch (e: GitHub.ApiException) {
                Summary(failed = 1, errors = listOf(e.message ?: "خطای GitHub"))
            } catch (e: Exception) {
                Summary(failed = 1, errors = listOf(e.message ?: e.toString()))
            }
            prefs.tgSyncAt = System.currentTimeMillis(); prefs.tgSyncSummary = s.text()
            return s
        } finally { lock.unlock() }
    }

    // ================================================================== My Device

    private suspend fun viaDevice(progress: (String) -> Unit): Summary = withContext(Dispatchers.IO) {
        val tg = Telegram.from(prefs)
        tg.configError()?.let { return@withContext Summary(failed = 1, errors = listOf("$it (Telegram Settings → روش ارسال: My Device)")) }
        var sent = 0; var edited = 0; var deleted = 0; var failed = 0
        val errors = mutableListOf<String>()
        fun err(m: String) { failed++; errors += m }

        // 1) messages of permanently deleted orders
        val tomb = prefs.tgTombstones
        if (tomb.isNotEmpty()) progress("حذف پیام‌های سفارش‌های حذف‌شده…")
        for (id in tomb) {
            val r = tg.deleteMessage(id)
            if (r.ok) { deleted++; prefs.tgTombstones = prefs.tgTombstones - id } else err("حذف پیام $id: ${r.message}")
        }

        // 2) orders
        val customers = dao.allCustomers().associateBy { it.id }
        val orders = dao.allOrders().sortedBy { it.id }
        orders.forEachIndexed { i, o ->
            val c = customers[o.customerId]
            val label = "#${if (o.syncNo > 0) o.syncNo else o.id}"
            progress("سفارش $label (${i + 1} از ${orders.size})…")
            try {
                if (c?.deletedAt != null) {                       // in the trash: take it out of the chat, send nothing
                    var changed = false
                    for (id in listOf(o.tgMessageId, o.tgPhotoMessageId).filter { it > 0 }) {
                        val r = tg.deleteMessage(id)
                        if (r.ok) { deleted++; changed = true } else throw TgFail("حذف $label: ${r.message}")
                    }
                    if (changed || o.tgTextHash.isNotEmpty()) {
                        dao.setTgText(o.id, 0, ""); dao.clearTgPhoto(o.id); dao.resetTgSent(o.id)
                    }
                    return@forEachIndexed
                }
                val text = TelegramText.build(prefs.telegramTemplate, o, c)
                val hash = GitHubApi.sha256(text.toByteArray(Charsets.UTF_8))
                if (o.tgMessageId == 0L) {
                    val r = tg.sendText(text)
                    if (!r.ok) throw TgFail("ارسال $label: ${r.message}")
                    dao.setTgText(o.id, r.messageId, hash); sent++
                } else if (o.tgTextHash != hash) {
                    val r = tg.editText(o.tgMessageId, text)
                    if (r.ok) { dao.setTgText(o.id, o.tgMessageId, hash); edited++ }
                    else if (r.notFound) {                         // someone deleted it in Telegram: send again (still no duplicate)
                        val n = tg.sendText(text)
                        if (!n.ok) throw TgFail("ارسال $label: ${n.message}")
                        dao.setTgText(o.id, n.messageId, hash); sent++
                    } else throw TgFail("ویرایش $label: ${r.message}")
                }
                val cur = dao.order(o.id) ?: o
                val ph = syncPhoto(tg, cur, c)
                sent += ph.first; edited += ph.second
                for (a in dao.attachmentsOfOrderNow(o.id).filter { !it.tgSent }) {
                    val f = repo.fileOf(a)
                    if (!f.exists()) continue
                    val r = tg.sendPhoto(f, null)
                    if (!r.ok) throw TgFail("عکس $label: ${r.message}")
                    dao.setTgSent(a.id); sent++
                }
            } catch (e: TgFail) { err(e.message ?: "خطا") }
            delay(60) // stay far below Telegram's rate limits
        }
        Summary(sent, edited, deleted, failed, errors)
    }

    private class TgFail(m: String) : Exception(m)

    /** the notebook-page image of the order: sent once, edited in place when the content changed */
    private suspend fun syncPhoto(tg: Telegram, o: Order, c: ir.kavir.ordernotebook.data.Customer?): Pair<Int, Int> {
        val png = repo.sync.renderPng(o, c)
        val h = GitHubApi.sha256(png)
        if (o.tgPhotoMessageId != 0L && o.tgImageHash == h) return 0 to 0
        val f = File.createTempFile("order_${o.id}_", ".png", repo.ctx.cacheDir)
        try {
            f.writeBytes(png)
            if (o.tgPhotoMessageId == 0L) {
                val r = tg.sendPhoto(f, null)
                if (!r.ok) throw TgFail("عکس سفارش: ${r.message}")
                dao.setTgPhoto(o.id, r.messageId, h); return 1 to 0
            }
            val r = tg.editPhoto(o.tgPhotoMessageId, f)
            if (r.ok) { dao.setTgPhoto(o.id, o.tgPhotoMessageId, h); return 0 to 1 }
            if (r.notFound) {
                val n = tg.sendPhoto(f, null)
                if (!n.ok) throw TgFail("عکس سفارش: ${n.message}")
                dao.setTgPhoto(o.id, n.messageId, h); return 1 to 0
            }
            throw TgFail("ویرایش عکس سفارش: ${r.message}")
        } finally { f.delete() }
    }

    /** "Resend to Telegram" of one order on the device method: always a NEW message. */
    suspend fun resendViaDevice(orderId: Long): String = withContext(Dispatchers.IO) {
        val tg = Telegram.from(prefs)
        tg.configError()?.let { return@withContext "✗ $it" }
        val o = dao.order(orderId) ?: return@withContext "✗ سفارش پیدا نشد"
        val c = dao.customer(o.customerId)
        try {
            val text = TelegramText.build(prefs.telegramTemplate, o, c)
            val r = tg.sendText(text)
            if (!r.ok) return@withContext "✗ ${r.message}"
            dao.setTgText(o.id, r.messageId, GitHubApi.sha256(text.toByteArray(Charsets.UTF_8)))
            "✓ پیام جدید در تلگرام ارسال شد"
        } catch (e: IOException) { "✗ خطای اتصال: ${e.message}" }
    }

    // ================================================================== GitHub Actions

    private suspend fun viaActions(progress: (String) -> Unit): Summary = withContext(Dispatchers.IO) {
        if (!prefs.ghConfigured) return@withContext Summary(failed = 1, errors = listOf("ابتدا اتصال GitHub را انجام دهید"))
        val ctx = repo.ctx
        progress("بررسی Workflow تلگرام در GitHub…")
        val steps = RepoSetup(ctx, prefs).prepare()            // makes sure the manual-only workflow + script are current
        steps.firstOrNull { !it.ok }?.let { return@withContext Summary(failed = 1, errors = listOf("${it.title}: ${it.detail}")) }

        progress("آپلود تغییرات جدید سفارش‌ها در GitHub…")
        repo.sync.process()
        val stuck = dao.ops().filter { it.kind == ir.kavir.ordernotebook.data.OpKind.GITHUB || it.kind == ir.kavir.ordernotebook.data.OpKind.GH_DELETE }
        if (stuck.isNotEmpty()) {
            val why = stuck.firstOrNull { it.lastError.isNotBlank() }?.lastError ?: "اینترنت یا توکن را بررسی کنید"
            return@withContext Summary(failed = stuck.size, errors = listOf("${stuck.size} تغییر هنوز در GitHub ذخیره نشده: $why"))
        }

        val api = GitHub(prefs)
        val tomb = prefs.tgTombstones
        if (tomb.isNotEmpty()) {
            val body = JSONObject().put("messages", JSONArray(tomb.toList())).toString(2).toByteArray()
            api.put("Order/data/telegram_deleted.json", body, "Telegram: messages to delete", api.sha("Order/data/telegram_deleted.json"))
        }
        val wf = RepoSetup.WORKFLOW_FILE
        val before = api.latestRun(wf)?.id ?: 0L
        progress("ارسال درخواست به GitHub Actions…")
        api.dispatch(wf, api.info().defaultBranch, emptyMap())
        var run: GitHub.Run? = null
        repeat(48) { n ->
            if (run != null) return@repeat
            delay(5000)
            progress("در انتظار پایان اجرای Action… (${(n + 1) * 5} ثانیه)")
            val r = api.latestRun(wf)
            if (r != null && r.id != before && r.status == "completed") run = r
        }
        val r = run ?: return@withContext Summary(failed = 1, errors = listOf("اجرای Action هنوز تمام نشده؛ بعداً وضعیت آخرین اجرا را ببینید"))
        // counts written by the script
        val st = runCatching { api.getText("Order/data/telegram_status.json") }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val errs = st?.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        if (r.conclusion == "success") {
            if (tomb.isNotEmpty()) {
                prefs.tgTombstones = prefs.tgTombstones - tomb
                runCatching { api.sha("Order/data/telegram_deleted.json")?.let { api.put("Order/data/telegram_deleted.json", JSONObject().put("messages", JSONArray()).toString().toByteArray(), "Telegram: clear", it) } }
            }
            Summary(st?.optInt("sent") ?: 0, st?.optInt("edited") ?: 0, st?.optInt("deleted") ?: 0, st?.optInt("failed") ?: 0, errs)
        } else {
            Summary(st?.optInt("sent") ?: 0, st?.optInt("edited") ?: 0, st?.optInt("deleted") ?: 0, maxOf(1, st?.optInt("failed") ?: 1),
                errs.ifEmpty { listOf("اجرای Action ناموفق بود (${r.conclusion}): ${r.url}") })
        }
    }
}
