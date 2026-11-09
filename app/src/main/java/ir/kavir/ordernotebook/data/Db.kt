package ir.kavir.ordernotebook.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // ---- customers
    @Query("SELECT * FROM customers WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun activeCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun deletedCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun customer(id: Long): Customer?

    @Query("SELECT * FROM customers WHERE id = :id")
    fun customerFlow(id: Long): Flow<Customer?>

    @Insert suspend fun insertCustomer(c: Customer): Long
    @Update suspend fun updateCustomer(c: Customer)
    @Query("UPDATE customers SET deletedAt = :t, updatedAt = :now WHERE id = :id")
    suspend fun setDeleted(id: Long, t: Long?, now: Long)
    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteCustomer(id: Long)

    // ---- orders
    @Query("SELECT o.* FROM orders o JOIN customers c ON c.id = o.customerId WHERE c.deletedAt IS NULL ORDER BY o.createdAt DESC")
    fun activeOrders(): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE customerId = :cid ORDER BY createdAt DESC")
    fun ordersOf(cid: Long): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun order(id: Long): Order?

    @Query("SELECT * FROM orders WHERE id = :id")
    fun orderFlow(id: Long): Flow<Order?>

    @Insert suspend fun insertOrder(o: Order): Long
    @Update suspend fun updateOrder(o: Order)
    @Query("DELETE FROM orders WHERE id = :id")
    suspend fun deleteOrder(id: Long)

    // ---- attachments
    @Query("SELECT * FROM attachments WHERE orderId = :oid ORDER BY createdAt DESC")
    fun attachmentsOfOrder(oid: Long): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE customerId = :cid ORDER BY createdAt DESC")
    fun attachmentsOfCustomer(cid: Long): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE customerId = :cid")
    suspend fun attachmentsOfCustomerNow(cid: Long): List<Attachment>

    @Insert suspend fun insertAttachment(a: Attachment): Long
    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    // ---- sync helpers
    @Query("SELECT * FROM customers WHERE uid = :uid LIMIT 1") suspend fun customerByUid(uid: String): Customer?
    @Query("SELECT * FROM orders WHERE uid = :uid LIMIT 1") suspend fun orderByUid(uid: String): Order?
    @Query("SELECT * FROM orders WHERE customerId = :cid") suspend fun ordersOfNow(cid: Long): List<Order>
    @Query("SELECT * FROM attachments WHERE orderId = :oid ORDER BY createdAt ASC, id ASC") suspend fun attachmentsOfOrderNow(oid: Long): List<Attachment>
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun attachment(id: Long): Attachment?
    @Query("SELECT COALESCE(MAX(syncNo), 0) FROM orders") suspend fun maxSyncNo(): Long
    @Query("UPDATE orders SET syncNo = :n WHERE id = :id") suspend fun setSyncNo(id: Long, n: Long)
    @Query("UPDATE orders SET tgMessageId = :m WHERE id = :id") suspend fun setTgMessage(id: Long, m: Long)
    @Query("UPDATE orders SET tgPhotoMessageId = :m, tgImageHash = :h WHERE id = :id") suspend fun setTgPhoto(id: Long, m: Long, h: String)
    @Query("UPDATE orders SET ghImageHash = :h WHERE id = :id") suspend fun setGhImageHash(id: Long, h: String)
    @Query("UPDATE attachments SET remotePath = :p WHERE id = :id") suspend fun setRemotePath(id: Long, p: String)
    @Query("UPDATE attachments SET tgSent = 1 WHERE id = :id") suspend fun setTgSent(id: Long)

    // ---- pending sync queue
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOp(op: PendingOp)
    @Query("SELECT * FROM pending_ops ORDER BY id") suspend fun ops(): List<PendingOp>
    @Query("SELECT COUNT(*) FROM pending_ops") fun pendingCount(): Flow<Int>
    @Query("SELECT lastError FROM pending_ops WHERE lastError != '' ORDER BY id DESC LIMIT 1") fun lastOpError(): Flow<String?>
    @Query("UPDATE orders SET updatedAt = :t WHERE id = :id") suspend fun touchOrder(id: Long, t: Long)
    @Query("DELETE FROM pending_ops WHERE id = :id") suspend fun deleteOp(id: Long)
    @Query("UPDATE pending_ops SET attempts = attempts + 1, lastError = :e WHERE id = :id") suspend fun failOp(id: Long, e: String)
    @Query("DELETE FROM pending_ops") suspend fun clearOps()

    // ---- raw dumps for backup / restore
    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<Customer>
    @Query("SELECT * FROM orders") suspend fun allOrders(): List<Order>
    @Query("SELECT * FROM attachments") suspend fun allAttachments(): List<Attachment>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomers(l: List<Customer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrders(l: List<Order>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttachments(l: List<Attachment>)
    @Query("DELETE FROM customers") suspend fun wipeCustomers()
}

@Database(entities = [Customer::class, Order::class, Attachment::class, PendingOp::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "ordernotebook.db").addMigrations(MIGRATION_1_2).build().also { inst = it }
        }
    }
}

/** v1 -> v2: adds sync identifiers, telegram message ids and the pending-sync queue. All old data is kept. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE customers ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE customers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE customers SET uid = lower(hex(randomblob(16))), updatedAt = createdAt")

        db.execSQL("ALTER TABLE orders ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE orders ADD COLUMN syncNo INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN tgMessageId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN tgPhotoMessageId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN tgImageHash TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE orders ADD COLUMN ghImageHash TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE orders SET uid = lower(hex(randomblob(16))), updatedAt = createdAt")
        db.execSQL(
            "UPDATE orders SET syncNo = (SELECT COUNT(*) FROM orders o2 WHERE o2.createdAt < orders.createdAt " +
                "OR (o2.createdAt = orders.createdAt AND o2.id <= orders.id))"
        )

        db.execSQL("ALTER TABLE attachments ADD COLUMN remotePath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE attachments ADD COLUMN tgSent INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_ops` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`orderId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_ops_kind_orderId` ON `pending_ops` (`kind`, `orderId`)")
    }
}
