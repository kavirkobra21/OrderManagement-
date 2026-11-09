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
    @Query("UPDATE customers SET deletedAt = :t WHERE id = :id")
    suspend fun setDeleted(id: Long, t: Long?)
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

    // ---- raw dumps for backup / restore
    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<Customer>
    @Query("SELECT * FROM orders") suspend fun allOrders(): List<Order>
    @Query("SELECT * FROM attachments") suspend fun allAttachments(): List<Attachment>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomers(l: List<Customer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrders(l: List<Order>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttachments(l: List<Attachment>)
    @Query("DELETE FROM customers") suspend fun wipeCustomers()
}

@Database(entities = [Customer::class, Order::class, Attachment::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "ordernotebook.db").build().also { inst = it }
        }
    }
}
