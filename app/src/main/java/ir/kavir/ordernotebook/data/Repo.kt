package ir.kavir.ordernotebook.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

class Repo(val ctx: Context) {
    val db = AppDb.get(ctx)
    val dao = db.dao()
    val prefs = Prefs(ctx)

    val attachDir: File get() = File(ctx.filesDir, "attachments").also { it.mkdirs() }
    fun fileOf(a: Attachment) = File(attachDir, a.fileName)

    suspend fun saveCustomer(c: Customer): Long =
        if (c.id == 0L) dao.insertCustomer(c) else { dao.updateCustomer(c); c.id }

    suspend fun saveOrder(o: Order): Long =
        if (o.id == 0L) dao.insertOrder(o) else { dao.updateOrder(o); o.id }

    suspend fun trashCustomer(id: Long) = dao.setDeleted(id, System.currentTimeMillis())
    suspend fun restoreCustomer(id: Long) = dao.setDeleted(id, null)

    suspend fun purgeCustomer(id: Long) {
        dao.attachmentsOfCustomerNow(id).forEach { fileOf(it).delete() }
        dao.deleteCustomer(id) // orders + attachments rows cascade
    }

    suspend fun emptyTrash() {
        val ids = mutableListOf<Long>()
        dao.allCustomers().filter { it.deletedAt != null }.forEach { ids += it.id }
        ids.forEach { purgeCustomer(it) }
    }

    /** Copy a picked image into app storage and link it to customer/order. */
    suspend fun addAttachment(uri: Uri, customerId: Long, orderId: Long?, type: String): Long? {
        val name = UUID.randomUUID().toString() + ".jpg"
        val out = File(attachDir, name)
        return try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            dao.insertAttachment(Attachment(customerId = customerId, orderId = orderId, type = type, fileName = name))
        } catch (e: Exception) { out.delete(); null }
    }

    suspend fun addAttachmentFile(src: File, customerId: Long, orderId: Long?, type: String): Long {
        val name = UUID.randomUUID().toString() + ".png"
        src.copyTo(File(attachDir, name), overwrite = true)
        return dao.insertAttachment(Attachment(customerId = customerId, orderId = orderId, type = type, fileName = name))
    }

    suspend fun removeAttachment(a: Attachment) { fileOf(a).delete(); dao.deleteAttachment(a.id) }
}
