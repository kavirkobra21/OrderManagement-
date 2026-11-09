package ir.kavir.ordernotebook.net

import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.Prefs
import ir.kavir.ordernotebook.util.Fmt
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * All requests go to the user's Cloudflare Worker: {worker}/bot{TOKEN}/{method}.
 * If the bot token field is empty the placeholder "env" is used and the Worker substitutes
 * the BOT_TOKEN secret stored in Cloudflare (most secure: token never reaches the phone).
 */
class Telegram(private val prefs: Prefs) {

    data class Result(val ok: Boolean, val message: String)

    private fun base(): String? {
        val w = prefs.workerUrl
        if (w.isBlank()) return null
        val token = prefs.botToken.ifBlank { "env" }
        return "$w/bot$token"
    }

    private fun validate(): String? = when {
        prefs.workerUrl.isBlank() -> "آدرس Worker وارد نشده است"
        !prefs.workerUrl.startsWith("https://") -> "آدرس Worker باید با https:// شروع شود"
        prefs.chatId.isBlank() -> "Chat ID وارد نشده است"
        else -> null
    }

    fun checkConnection(): Result {
        val w = prefs.workerUrl
        if (w.isBlank()) return Result(false, "آدرس Worker وارد نشده است")
        return try {
            val (code, body) = request("GET", "$w/bot${prefs.botToken.ifBlank { "env" }}/getMe")
            val j = JSONObject(body)
            if (code == 200 && j.optBoolean("ok")) {
                Result(true, "اتصال برقرار است. بات: @" + j.optJSONObject("result")?.optString("username"))
            } else Result(false, "پاسخ نامعتبر (${code}): " + j.optString("description", body.take(200)))
        } catch (e: Exception) {
            Result(false, "خطای اتصال: ${e.message}")
        }
    }

    fun sendText(text: String): Result {
        validate()?.let { return Result(false, it) }
        return call("sendMessage", mapOf("chat_id" to prefs.chatId, "text" to text))
    }

    fun sendPhoto(file: File, caption: String? = null): Result {
        validate()?.let { return Result(false, it) }
        val fields = mutableMapOf("chat_id" to prefs.chatId)
        if (!caption.isNullOrBlank()) fields["caption"] = caption.take(1000)
        return multipart("sendPhoto", fields, "photo", file)
    }

    /** Sends as a document to avoid Telegram recompression of the generated order image. */
    fun sendDocument(file: File, caption: String? = null): Result {
        validate()?.let { return Result(false, it) }
        val fields = mutableMapOf("chat_id" to prefs.chatId)
        if (!caption.isNullOrBlank()) fields["caption"] = caption.take(1000)
        return multipart("sendDocument", fields, "document", file)
    }

    private fun call(method: String, params: Map<String, String>): Result = try {
        val url = base() + "/" + method
        val json = JSONObject(params).toString()
        val (code, body) = request("POST", url, json.toByteArray(), "application/json; charset=utf-8")
        parse(code, body)
    } catch (e: Exception) { Result(false, "خطای اتصال: ${e.message}") }

    private fun multipart(method: String, fields: Map<String, String>, fileField: String, file: File): Result = try {
        val boundary = "----" + UUID.randomUUID()
        val c2 = open("POST", base() + "/" + method)
        c2.doOutput = true
        c2.setChunkedStreamingMode(16 * 1024)
        c2.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c2.outputStream.use { os ->
            fun w(s: String) = os.write(s.toByteArray())
            for ((k, v) in fields) {
                w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            file.inputStream().use { it.copyTo(os as OutputStream) }
            w("\r\n--$boundary--\r\n")
        }
        val code = c2.responseCode
        val body = (if (code in 200..299) c2.inputStream else c2.errorStream)?.bufferedReader()?.readText().orEmpty()
        parse(code, body)
    } catch (e: Exception) { Result(false, "خطای اتصال: ${e.message}") }

    private fun parse(code: Int, body: String): Result = try {
        val j = JSONObject(body)
        if (j.optBoolean("ok")) Result(true, "ارسال شد")
        else Result(false, "خطای تلگرام ($code): " + j.optString("description", body.take(200)) +
            if (code == 403) " — ابتدا باید ربات را Start کنید" else "")
    } catch (e: Exception) { Result(false, "پاسخ نامعتبر از Worker ($code)") }

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
        }

    private fun request(method: String, url: String, body: ByteArray? = null, type: String? = null): Pair<Int, String> {
        val c = open(method, url)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type ?: "application/json")
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    companion object {
        fun payStatus(o: Order, fa: Boolean): String = when {
            o.settled || o.paid -> if (fa) "تسویه کامل" else "Fully paid"
            o.depositReceived -> if (fa) "بیعانه دریافت شده" else "Deposit received"
            else -> if (fa) "پرداخت نشده" else "Unpaid"
        }

        fun orderStatus(o: Order, fa: Boolean): String = when {
            o.stDelivered -> if (fa) "تحویل داده شده" else "Delivered"
            o.stReady -> if (fa) "آماده شده" else "Ready"
            o.stInProgress -> if (fa) "در حال انجام" else "In progress"
            else -> if (fa) "ثبت شده" else "Registered"
        }

        fun message(o: Order, name: String, phone: String, address: String, itemsLines: String, fa: Boolean): String {
            val m = { v: Long -> Fmt.money(v, fa) }
            val details = listOf(o.description, itemsLines).filter { it.isNotBlank() }.joinToString("\n")
            return if (fa) """
WORK ORDER NOTIFICATION

Customer Name (نام مشتری): $name
Phone Number (شماره تماس): $phone
Order Number (شماره فاکتور): ${o.invoiceNo}
Order Date (تاریخ): ${o.dateText}
Delivery Date (زمان تحویل): ${o.deliveryText}
Address (آدرس): $address
Order Details (شرح سفارش):
$details
Total Amount (هزینه کل): ${m(o.total)}
Deposit (بیعانه): ${m(o.deposit)}
Remaining Balance (باقیمانده): ${m(o.remaining)}
Payment Status (وضعیت پرداخت): ${payStatus(o, true)}
Order Status (وضعیت سفارش): ${orderStatus(o, true)}
""".trim() else """
WORK ORDER NOTIFICATION

Customer Name: $name
Phone Number: $phone
Order Number: ${o.invoiceNo}
Order Date: ${o.dateText}
Delivery Date: ${o.deliveryText}
Address: $address
Order Details:
$details
Total Amount: ${m(o.total)}
Deposit: ${m(o.deposit)}
Remaining Balance: ${m(o.remaining)}
Payment Status: ${payStatus(o, false)}
Order Status: ${orderStatus(o, false)}
""".trim()
        }
    }
}
