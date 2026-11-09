package ir.kavir.ordernotebook.net

import ir.kavir.ordernotebook.data.Prefs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Direct Telegram Bot API client. Network problems are thrown as IOException (so the sync queue
 * keeps the job and retries later); Telegram-level errors come back as [Result.ok] == false.
 */
class Telegram(private val token: String, private val chatId: String) {

    data class Result(
        val ok: Boolean,
        val message: String,
        val messageId: Long = 0,
        /** the message to edit no longer exists (deleted in Telegram) */
        val notFound: Boolean = false,
    )

    companion object {
        fun from(p: Prefs) = Telegram(p.botToken, p.chatId)
        private const val MAX_PHOTO = 9_500_000L
    }

    private fun base() = "https://api.telegram.org/bot${token.trim()}"

    fun configError(): String? = when {
        token.isBlank() -> "Bot Token وارد نشده است"
        chatId.isBlank() -> "Chat ID وارد نشده است"
        else -> null
    }

    /** Used by the "Test Connection" button; never throws. */
    fun checkConnection(): Result {
        configError()?.let { return Result(false, it) }
        return try {
            val me = call("getMe", JSONObject(), raw = true)
            if (!me.ok) return Result(false, "Bot Token نامعتبر است: ${me.message}")
            val name = me.message
            val chat = call("getChat", JSONObject().put("chat_id", chatId), raw = true)
            if (!chat.ok) Result(false, "ربات @$name پیدا شد ولی چت در دسترس نیست. Chat ID را بررسی کنید و ربات را در تلگرام Start کنید. (${chat.message})")
            else Result(true, "اتصال برقرار است. ربات: @$name")
        } catch (e: IOException) {
            Result(false, "خطای اتصال: ${e.message}. ممکن است دسترسی به تلگرام در این شبکه محدود باشد.")
        }
    }

    @Throws(IOException::class)
    fun sendText(text: String): Result =
        call("sendMessage", JSONObject().put("chat_id", chatId).put("text", text))

    @Throws(IOException::class)
    fun editText(messageId: Long, text: String): Result =
        call("editMessageText", JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", text))

    @Throws(IOException::class)
    fun sendPhoto(file: File, caption: String? = null): Result {
        val fields = mutableMapOf("chat_id" to chatId)
        if (!caption.isNullOrBlank()) fields["caption"] = caption.take(1000)
        return if (file.length() > MAX_PHOTO) multipart("sendDocument", fields, "document", file)
        else multipart("sendPhoto", fields, "photo", file)
    }

    @Throws(IOException::class)
    fun editPhoto(messageId: Long, file: File): Result {
        val media = JSONObject().put("type", "photo").put("media", "attach://photo").toString()
        return multipart(
            "editMessageMedia",
            mapOf("chat_id" to chatId, "message_id" to messageId.toString(), "media" to media),
            "photo", file,
        )
    }

    // ------------------------------------------------------------------ http

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
        }

    private fun call(method: String, body: JSONObject, raw: Boolean = false): Result {
        val c = open(base() + "/" + method)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return parse(c.responseCode, read(c), raw)
        } finally { c.disconnect() }
    }

    private fun multipart(method: String, fields: Map<String, String>, fileField: String, file: File): Result {
        val boundary = "----" + UUID.randomUUID()
        val c = open(base() + "/" + method)
        try {
            c.doOutput = true
            c.setChunkedStreamingMode(16 * 1024)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { os ->
                fun w(s: String) = os.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(os) }
                w("\r\n--$boundary--\r\n")
            }
            return parse(c.responseCode, read(c), false)
        } finally { c.disconnect() }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        return (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    /** raw=true: for getMe/getChat, [Result.message] carries the bot username instead of "OK". */
    private fun parse(code: Int, body: String, raw: Boolean): Result {
        val j = try { JSONObject(body) } catch (e: Exception) { return Result(false, "پاسخ نامعتبر از تلگرام ($code)") }
        if (j.optBoolean("ok")) {
            val r = j.opt("result")
            val obj = r as? JSONObject
            val id = obj?.optLong("message_id", 0) ?: 0
            val msg = if (raw) (obj?.optString("username").orEmpty().ifBlank { obj?.optString("title").orEmpty() }) else "OK"
            return Result(true, msg, id)
        }
        val desc = j.optString("description")
        if (desc.contains("message is not modified", true)) return Result(true, "بدون تغییر")
        val notFound = desc.contains("message to edit not found", true) ||
            desc.contains("MESSAGE_ID_INVALID", true) || desc.contains("message can't be edited", true)
        val hint = when (code) {
            401 -> " — Bot Token نامعتبر است"
            403 -> " — ابتدا باید ربات را در تلگرام Start کنید"
            429 -> " — محدودیت تعداد درخواست؛ بعداً دوباره تلاش می‌شود"
            else -> ""
        }
        return Result(false, "خطای تلگرام ($code): $desc$hint", notFound = notFound)
    }
}
