package ir.kavir.ordernotebook.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** null = active, otherwise time moved to the recycle bin */
    val deletedAt: Long? = null,
    /** stable id used for GitHub sync / duplicate protection */
    val uid: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Telegram / Discord ID of the customer (labelled just "آیدی") */
    val contactId: String = "",
    /** postal code, kept separate from the address */
    val postalCode: String = "",
)

@Entity(
    tableName = "orders",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("customerId")],
)
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val invoiceNo: String = "",
    val dateText: String = "",
    val deliveryText: String = "",
    val description: String = "",
    /** items serialised by [OrderItem.encode] */
    val itemsText: String = "",
    val notes: String = "",
    /** number of units ordered (structured data, not text in the description) */
    val quantity: Int = 1,
    val total: Long = 0,
    /** percentage discount (0..100) applied first, then [discountFixed]; [total] stays the ORIGINAL price */
    val discountPercent: Double = 0.0,
    val discountFixed: Long = 0,
    val deposit: Long = 0,
    val stRegistered: Boolean = true,
    val stInProgress: Boolean = false,
    val stReady: Boolean = false,
    val stDelivered: Boolean = false,
    val paid: Boolean = false,
    val depositReceived: Boolean = false,
    val settled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val uid: String = UUID.randomUUID().toString(),
    /** folder number inside orders/ on GitHub (1, 2, 3 ...) */
    val syncNo: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    // --- sync bookkeeping (never edited by the order form)
    val tgMessageId: Long = 0,
    val tgPhotoMessageId: Long = 0,
    val tgImageHash: String = "",
    val ghImageHash: String = "",
    /** hash of the text last sent/edited in Telegram by the manual sync (device method) */
    val tgTextHash: String = "",
) {
    val pricing: ir.kavir.ordernotebook.util.Pricing.Result get() = ir.kavir.ordernotebook.util.Pricing.calc(total, discountPercent, discountFixed)
    val finalTotal: Long get() = pricing.final
    val remaining: Long get() = if (settled || paid) 0 else (finalTotal - deposit).coerceAtLeast(0)

    val isCompleted: Boolean get() = stDelivered && (settled || paid)
}

/** type: receipt | order | product | satisfaction | other */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("customerId"), Index("orderId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val orderId: Long? = null,
    val type: String = "other",
    /** file name inside filesDir/attachments */
    val fileName: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** path of the uploaded file relative to orders/<n>/ ; empty = not uploaded yet */
    val remotePath: String = "",
    val tgSent: Boolean = false,
)

/** Queue of changes waiting to be pushed (Pending Sync). One row per (kind, order). */
@Entity(tableName = "pending_ops", indices = [Index(value = ["kind", "orderId"], unique = true)])
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val orderId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String = "",
)

object OpKind {
    const val GITHUB = "GH"
    const val TELEGRAM = "TG"
    /** delete Order/orders/<orderId> (orderId = folder number) from GitHub after a permanent delete */
    const val GH_DELETE = "GHDEL"
}

data class OrderItem(val title: String, val qty: Int, val price: Long) {
    companion object {
        private const val SEP = "\u001F"
        fun encode(items: List<OrderItem>) =
            items.joinToString("\n") { listOf(it.title.replace("\n", " "), it.qty, it.price).joinToString(SEP) }

        fun decode(text: String): List<OrderItem> = text.lines().filter { it.isNotBlank() }.mapNotNull {
            val p = it.split(SEP)
            if (p.size < 3) null else OrderItem(p[0], p[1].toIntOrNull() ?: 1, p[2].toLongOrNull() ?: 0)
        }
    }
}

object AttachmentType {
    const val RECEIPT = "receipt"
    const val ORDER = "order"
    const val PRODUCT = "product"
    const val SATISFACTION = "satisfaction"
    const val GENERATED = "generated"
    val all = listOf(RECEIPT, ORDER, PRODUCT, SATISFACTION)
}
