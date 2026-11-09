package ir.kavir.ordernotebook.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP layout:
 *   manifest.json      {format, version, createdAt, counts}
 *   data.json          customers (incl. recycle bin), orders, attachments, settings
 *   attachments/<file> every attached image
 */
object Backup {
    const val FORMAT = "ordernotebook-backup"
    const val VERSION = 1

    data class Info(val version: Int, val createdAt: Long, val customers: Int, val orders: Int, val attachments: Int, val trash: Int)

    suspend fun export(repo: Repo, out: Uri): Result<Info> = try {
        val customers = repo.dao.allCustomers()
        val orders = repo.dao.allOrders()
        val atts = repo.dao.allAttachments()
        val info = Info(VERSION, System.currentTimeMillis(), customers.size, orders.size, atts.size, customers.count { it.deletedAt != null })

        val data = JSONObject()
        data.put("customers", JSONArray().also { a -> customers.forEach { a.put(customerJson(it)) } })
        data.put("orders", JSONArray().also { a -> orders.forEach { a.put(orderJson(it)) } })
        data.put("attachments", JSONArray().also { a -> atts.forEach { a.put(attJson(it)) } })
        data.put("settings", JSONObject(repo.prefs.export()))

        val manifest = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("createdAt", info.createdAt)
            .put("customers", info.customers).put("orders", info.orders)
            .put("attachments", info.attachments).put("trash", info.trash)

        repo.ctx.contentResolver.openOutputStream(out, "wt")!!.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString(2).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("data.json")); z.write(data.toString(2).toByteArray()); z.closeEntry()
                for (a in atts) {
                    val f = repo.fileOf(a)
                    if (f.exists()) {
                        z.putNextEntry(ZipEntry("attachments/${a.fileName}"))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
        Result.success(info)
    } catch (e: Exception) { Result.failure(e) }

    /** Validates the zip and returns its info without touching current data. */
    fun inspect(ctx: Context, uri: Uri): Result<Info> = try {
        var manifest: JSONObject? = null
        var hasData = false
        ctx.contentResolver.openInputStream(uri)!!.use { s ->
            ZipInputStream(s.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        "manifest.json" -> manifest = JSONObject(z.readBytes().decodeToString())
                        "data.json" -> { JSONObject(z.readBytes().decodeToString()); hasData = true }
                    }
                }
            }
        }
        val m = manifest ?: throw IllegalArgumentException("manifest.json پیدا نشد؛ فایل بکاپ این برنامه نیست")
        if (m.optString("format") != FORMAT) throw IllegalArgumentException("فرمت فایل بکاپ نامعتبر است")
        if (!hasData) throw IllegalArgumentException("data.json در فایل نیست")
        val v = m.optInt("version", 0)
        if (v < 1 || v > VERSION) throw IllegalArgumentException("نسخه بکاپ ($v) پشتیبانی نمی‌شود")
        Result.success(Info(v, m.optLong("createdAt"), m.optInt("customers"), m.optInt("orders"), m.optInt("attachments"), m.optInt("trash")))
    } catch (e: Exception) { Result.failure(e) }

    /** Replaces all current data. Images are extracted to a temp dir first, then swapped in. */
    suspend fun import(repo: Repo, uri: Uri): Result<Unit> = try {
        val staging = File(repo.ctx.cacheDir, "restore_stage").also { it.deleteRecursively(); it.mkdirs() }
        var dataText: String? = null
        repo.ctx.contentResolver.openInputStream(uri)!!.use { s ->
            ZipInputStream(s.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == "data.json") dataText = z.readBytes().decodeToString()
                    else if (e.name.startsWith("attachments/") && !e.isDirectory) {
                        val name = File(e.name).name // strips any path components (zip-slip safe)
                        File(staging, name).outputStream().use { z.copyTo(it) }
                    }
                }
            }
        }
        val data = JSONObject(dataText ?: throw IllegalArgumentException("data.json در فایل نیست"))
        val customers = data.getJSONArray("customers").objs().map(::customerFrom)
        val orders = data.getJSONArray("orders").objs().map(::orderFrom)
        val atts = data.getJSONArray("attachments").objs().map(::attFrom)

        // swap data
        repo.dao.wipeCustomers() // cascades orders + attachments
        repo.attachDir.listFiles()?.forEach { it.delete() }
        staging.listFiles()?.forEach { it.copyTo(File(repo.attachDir, it.name), overwrite = true) }
        repo.dao.putCustomers(customers)
        repo.dao.putOrders(orders)
        repo.dao.putAttachments(atts)
        data.optJSONObject("settings")?.let { st ->
            repo.prefs.import(st.keys().asSequence().associateWith { st.optString(it) })
        }
        staging.deleteRecursively()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

    private fun customerJson(c: Customer) = JSONObject().put("id", c.id).put("name", c.name).put("phone", c.phone)
        .put("address", c.address).put("note", c.note).put("createdAt", c.createdAt).put("deletedAt", c.deletedAt ?: JSONObject.NULL)

    private fun customerFrom(j: JSONObject) = Customer(
        j.getLong("id"), j.optString("name"), j.optString("phone"), j.optString("address"), j.optString("note"),
        j.optLong("createdAt"), if (j.isNull("deletedAt")) null else j.getLong("deletedAt"),
    )

    private fun orderJson(o: Order) = JSONObject().put("id", o.id).put("customerId", o.customerId).put("invoiceNo", o.invoiceNo)
        .put("dateText", o.dateText).put("deliveryText", o.deliveryText).put("description", o.description)
        .put("itemsText", o.itemsText).put("notes", o.notes).put("total", o.total).put("deposit", o.deposit)
        .put("stRegistered", o.stRegistered).put("stInProgress", o.stInProgress).put("stReady", o.stReady)
        .put("stDelivered", o.stDelivered).put("paid", o.paid).put("depositReceived", o.depositReceived)
        .put("settled", o.settled).put("createdAt", o.createdAt)

    private fun orderFrom(j: JSONObject) = Order(
        id = j.getLong("id"), customerId = j.getLong("customerId"), invoiceNo = j.optString("invoiceNo"),
        dateText = j.optString("dateText"), deliveryText = j.optString("deliveryText"), description = j.optString("description"),
        itemsText = j.optString("itemsText"), notes = j.optString("notes"), total = j.optLong("total"), deposit = j.optLong("deposit"),
        stRegistered = j.optBoolean("stRegistered"), stInProgress = j.optBoolean("stInProgress"), stReady = j.optBoolean("stReady"),
        stDelivered = j.optBoolean("stDelivered"), paid = j.optBoolean("paid"), depositReceived = j.optBoolean("depositReceived"),
        settled = j.optBoolean("settled"), createdAt = j.optLong("createdAt"),
    )

    private fun attJson(a: Attachment) = JSONObject().put("id", a.id).put("customerId", a.customerId)
        .put("orderId", a.orderId ?: JSONObject.NULL).put("type", a.type).put("fileName", a.fileName).put("createdAt", a.createdAt)

    private fun attFrom(j: JSONObject) = Attachment(
        j.getLong("id"), j.getLong("customerId"), if (j.isNull("orderId")) null else j.getLong("orderId"),
        j.optString("type"), j.getString("fileName"), j.optLong("createdAt"),
    )
}
