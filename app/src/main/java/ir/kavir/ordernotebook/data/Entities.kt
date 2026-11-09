package ir.kavir.ordernotebook.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val total: Long = 0,
    val deposit: Long = 0,
    val stRegistered: Boolean = true,
    val stInProgress: Boolean = false,
    val stReady: Boolean = false,
    val stDelivered: Boolean = false,
    val paid: Boolean = false,
    val depositReceived: Boolean = false,
    val settled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val remaining: Long get() = if (settled || paid) 0 else (total - deposit).coerceAtLeast(0)

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
)

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
