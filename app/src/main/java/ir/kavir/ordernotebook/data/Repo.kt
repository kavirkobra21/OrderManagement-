package ir.kavir.ordernotebook.data

import android.content.Context
import android.net.Uri
import ir.kavir.ordernotebook.net.SyncManager
import ir.kavir.ordernotebook.util.Fmt
import java.io.File
import java.util.UUID

/** Process-wide singleton so there is exactly one database handle and one sync worker. */
class Repo private constructor(val ctx: Context) {
    val db = AppDb.get(ctx)
    val dao = db.dao()
    val prefs = Prefs(ctx)
    val sync: SyncManager by lazy { SyncManager(this) }

    companion object {
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) {
            inst ?: Repo(ctx.applicationContext).also { inst = it }
        }
    }

    val attachDir: File get() = File(ctx.filesDir, "attachments").also { it.mkdirs() }
    fun fileOf(a: Attachment) = File(attachDir, a.fileName)

    // ------------------------------------------------------------------ sync queue

    /**
     * Adds a GitHub push job for an order to the Pending Sync queue and wakes the worker.
     * Telegram is delivered by the GitHub Action once the order is pushed (nothing is sent from the phone).
     */
    suspend fun enqueue(orderId: Long) {
        if (prefs.ghConfigured) dao.putOp(PendingOp(kind = OpKind.GITHUB, orderId = orderId))
        sync.kick()
    }

    /** Queue config/notify.json (Telegram language + on/off switch read by the Action). */
    suspend fun enqueueConfig() {
        if (prefs.ghConfigured) dao.putOp(PendingOp(kind = OpKind.GITHUB, orderId = SyncManager.CONFIG_OP))
        sync.kick()
    }

    /** Queue every order for GitHub (used by "Backup all to GitHub"). */
    suspend fun enqueueAll(): Int {
        val all = dao.allOrders()
        all.forEach { dao.putOp(PendingOp(kind = OpKind.GITHUB, orderId = it.id)) }
        sync.kick()
        return all.size
    }

    // ------------------------------------------------------------------ customers

    suspend fun saveCustomer(c0: Customer): Long {
        val now = System.currentTimeMillis()
        val c = c0.copy(phone = Fmt.toLatinDigits(c0.phone), updatedAt = now)
        if (c.id == 0L) return dao.insertCustomer(c)
        val cur = dao.customer(c.id)
        dao.updateCustomer(c.copy(uid = cur?.uid ?: c.uid, createdAt = cur?.createdAt ?: c.createdAt))
        dao.ordersOfNow(c.id).forEach { enqueue(it.id) }
        return c.id
    }

    suspend fun trashCustomer(id: Long) {
        dao.setDeleted(id, System.currentTimeMillis(), System.currentTimeMillis())
        dao.ordersOfNow(id).forEach { enqueue(it.id) }
    }

    suspend fun restoreCustomer(id: Long) {
        dao.setDeleted(id, null, System.currentTimeMillis())
        dao.ordersOfNow(id).forEach { enqueue(it.id) }
    }

    /** Permanent delete: local rows + image files now, the GitHub folders (Order/orders/<n>) through the sync queue. */
    suspend fun purgeCustomer(id: Long) {
        val folders = dao.ordersOfNow(id).map { it.syncNo }.filter { it > 0 }
        prefs.addTombstones(dao.ordersOfNow(id).flatMap { listOf(it.tgMessageId, it.tgPhotoMessageId) })
        dao.attachmentsOfCustomerNow(id).forEach { fileOf(it).delete() }
        dao.deleteCustomer(id) // orders + attachments rows cascade
        if (prefs.ghConfigured) folders.forEach { dao.putOp(PendingOp(kind = OpKind.GH_DELETE, orderId = it)) }
        if (folders.isNotEmpty()) sync.kick()
    }

    suspend fun emptyTrash() {
        dao.allCustomers().filter { it.deletedAt != null }.forEach { purgeCustomer(it.id) }
    }

    // ------------------------------------------------------------------ orders

    /** Insert or update. Sync bookkeeping (uid, folder number, Telegram ids) is always taken from the DB. */
    suspend fun saveOrder(o0: Order): Long {
        val now = System.currentTimeMillis()
        val id: Long
        if (o0.id == 0L) {
            id = dao.insertOrder(o0.copy(updatedAt = now))
        } else {
            val cur = dao.order(o0.id)
            val o = if (cur == null) o0.copy(updatedAt = now) else o0.copy(
                uid = cur.uid, syncNo = cur.syncNo, createdAt = cur.createdAt,
                tgMessageId = cur.tgMessageId, tgPhotoMessageId = cur.tgPhotoMessageId,
                tgImageHash = cur.tgImageHash, ghImageHash = cur.ghImageHash, tgTextHash = cur.tgTextHash, updatedAt = now,
            )
            dao.updateOrder(o)
            id = o0.id
        }
        enqueue(id)
        return id
    }

    // ------------------------------------------------------------------ attachments

    /** Copy a picked image into app storage and link it to customer/order. */
    suspend fun addAttachment(uri: Uri, customerId: Long, orderId: Long?, type: String): Long? {
        val name = UUID.randomUUID().toString() + ".jpg"
        val out = File(attachDir, name)
        return try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            val id = dao.insertAttachment(Attachment(customerId = customerId, orderId = orderId, type = type, fileName = name))
            if (orderId != null) { dao.touchOrder(orderId, System.currentTimeMillis()); enqueue(orderId) }
            id
        } catch (e: Exception) { out.delete(); null }
    }

    suspend fun addAttachmentFile(src: File, customerId: Long, orderId: Long?, type: String): Long {
        val name = UUID.randomUUID().toString() + ".png"
        src.copyTo(File(attachDir, name), overwrite = true)
        return dao.insertAttachment(Attachment(customerId = customerId, orderId = orderId, type = type, fileName = name))
    }

    suspend fun removeAttachment(a: Attachment) {
        fileOf(a).delete()
        dao.deleteAttachment(a.id)
        if (a.orderId != null) { dao.touchOrder(a.orderId, System.currentTimeMillis()); enqueue(a.orderId) }
    }
}
