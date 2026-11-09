package ir.kavir.ordernotebook.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ir.kavir.ordernotebook.data.Attachment
import ir.kavir.ordernotebook.data.AttachmentType
import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.OpKind
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.data.PendingOp
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.ui.NotebookPage
import ir.kavir.ordernotebook.ui.pageData
import ir.kavir.ordernotebook.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** Non-network problem that keeps the job in the queue with a readable message. */
class SyncException(message: String) : Exception(message)

/**
 * Offline-first sync. Every local change writes to Room and adds a row to the pending_ops queue
 * ("GH" = push to GitHub, "TG" = send/edit Telegram). The queue is processed whenever the network is
 * available; jobs that fail stay queued. A mutex guarantees a single worker, and unchanged files are
 * detected by git blob SHA, so nothing is uploaded or sent twice.
 *
 * GitHub layout:  orders/<n>/{order.json, customer.json, order_image.png, payment_receipt.<ext>, images/ (folder)}
 */
class SyncManager(private val repo: Repo) {
    private val ctx = repo.ctx
    private val dao = repo.dao
    private val prefs = repo.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var started = false
    private var privateOkAt = 0L

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    // ------------------------------------------------------------------ triggers

    fun start() {
        if (started) return
        started = true
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { kick() }
            })
        } catch (e: Exception) { /* no connectivity callbacks: manual / app-start sync still works */ }
        kick()
    }

    fun kick() { scope.launch { process() } }

    fun online(): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) { true }

    // ------------------------------------------------------------------ queue

    suspend fun process() = mutex.withLock {
        if (!online()) return@withLock
        _syncing.value = true
        try {
            var pass = 0
            var seen = emptySet<Long>()
            while (pass < 3) {
                val ops = dao.ops().filter { it.id !in seen }.sortedBy { if (it.kind == OpKind.GITHUB) 0 else 1 }
                if (ops.isEmpty()) break
                seen = seen + ops.map { it.id }
                for (op in ops) {
                    try {
                        when (op.kind) {
                            OpKind.GITHUB -> pushOrder(op.orderId)
                            OpKind.TELEGRAM -> telegramOrder(op.orderId)
                        }
                        dao.deleteOp(op.id)
                    } catch (e: IOException) {
                        dao.failOp(op.id, "اتصال برقرار نیست؛ پس از اتصال دوباره ارسال می‌شود")
                        return@withLock // offline / blocked: keep everything queued
                    } catch (e: SyncException) {
                        dao.failOp(op.id, e.message ?: "خطا")
                    } catch (e: GitHub.ApiException) {
                        dao.failOp(op.id, e.message ?: "خطای GitHub")
                    } catch (e: Exception) {
                        dao.failOp(op.id, e.message ?: e.toString())
                    }
                }
                pass++
            }
        } finally { _syncing.value = false }
    }

    private suspend fun enqueueGh(orderId: Long) {
        if (prefs.ghConfigured) dao.putOp(PendingOp(kind = OpKind.GITHUB, orderId = orderId))
    }

    // ------------------------------------------------------------------ rendering

    private fun renderPng(o: Order, c: Customer?): ByteArray {
        val bmp = NotebookPage.render(pageData(o, c), 1.8f)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ================================================================== GitHub push

    private fun ensurePrivate(api: GitHub) {
        if (System.currentTimeMillis() - privateOkAt < 10 * 60_000) return
        val info = api.info()
        if (!info.isPrivate) throw SyncException(
            "مخزن GitHub عمومی (Public) است. برای حفاظت از اطلاعات مشتریان، آپلود انجام نشد؛ مخزن را Private کنید."
        )
        if (!info.canPush) throw SyncException("توکن اجازه‌ی نوشتن در مخزن را ندارد (Contents: Read and write)")
        privateOkAt = System.currentTimeMillis()
    }

    private suspend fun pushOrder(orderId: Long) {
        if (!prefs.ghConfigured) return
        val o0 = dao.order(orderId) ?: return
        val cust = dao.customer(o0.customerId) ?: return
        val api = GitHub(prefs)
        ensurePrivate(api)

        // ---- folder number (incremental, no duplicates)
        val dirs = api.list("orders").filter { it.type == "dir" }.mapNotNull { it.name.toLongOrNull() }
        var no = o0.syncNo
        if (no != 0L && no in dirs) {
            val remote = api.getText("orders/$no/order.json")?.let { parseOrFail(it) }
            val ruid = remote?.optString("uid").orEmpty()
            if (remote != null && ruid.isNotBlank() && ruid != o0.uid) no = 0 // number taken by another order
            else if (remote != null && ruid == o0.uid && remote.optLong("updatedAt") > o0.updatedAt) {
                // conflict: GitHub has a newer edit of this order -> take it instead of overwriting
                mergeFolder(api, no)
                return
            }
        }
        if (no == 0L) {
            no = maxOf(dirs.maxOrNull() ?: 0L, dao.maxSyncNo()) + 1
            dao.setSyncNo(o0.id, no)
        }
        val o = dao.order(orderId) ?: return
        val base = "orders/$no"

        val root = api.list(base)
        val imgs = api.list("$base/images")
        val remoteNames = (root.map { it.name } + imgs.map { "images/${it.name}" }).toSet()

        // ---- attachments
        val atts = dao.attachmentsOfOrderNow(o.id)
        val used = mutableSetOf<String>()
        val plan = mutableListOf<Pair<Attachment, String>>()
        atts.filter { it.remotePath.isNotBlank() }.forEach { used += it.remotePath; plan += it to it.remotePath }
        atts.filter { it.remotePath.isBlank() }.forEach { a ->
            val ext = a.fileName.substringAfterLast('.', "jpg")
            val name = if (a.type == AttachmentType.RECEIPT && used.none { it.startsWith("payment_receipt.") })
                "payment_receipt.$ext" else "images/${a.type}_${a.fileName}"
            used += name
            plan += a to name
        }
        val expected = mutableSetOf<String>()
        for ((a, path) in plan) {
            val f = repo.fileOf(a)
            if (!f.exists()) continue
            expected += path
            if (path !in remoteNames) {
                if (f.length() > 30L * 1024 * 1024) throw SyncException("حجم عکس بیش از ۳۰ مگابایت است: ${a.fileName}")
                api.put("$base/$path", f.readBytes(), "Order #$no: add ${path.substringAfterLast('/')}", null)
            }
            if (a.remotePath != path) dao.setRemotePath(a.id, path)
        }

        // ---- fixed files
        val png = renderPng(o, cust)
        putIfChanged(api, "$base/order_image.png", png, root.find { it.name == "order_image.png" }?.sha, "Order #$no: image")
        val cj = customerJson(cust).toString(2).toByteArray(Charsets.UTF_8)
        putIfChanged(api, "$base/customer.json", cj, root.find { it.name == "customer.json" }?.sha, "Order #$no: customer")
        val attMeta = plan.filter { it.second in expected }
        val oj = orderJson(o.copy(syncNo = no), cust, attMeta).toString(2).toByteArray(Charsets.UTF_8)
        putIfChanged(api, "$base/order.json", oj, root.find { it.name == "order.json" }?.sha, "Order #$no: data")

        // ---- remove files of deleted attachments
        root.filter { it.name.startsWith("payment_receipt") && it.name !in expected }
            .forEach { api.delete("$base/${it.name}", it.sha, "Order #$no: remove ${it.name}") }
        imgs.filter { "images/${it.name}" !in expected }
            .forEach { api.delete("$base/images/${it.name}", it.sha, "Order #$no: remove ${it.name}") }

        dao.setGhImageHash(o.id, GitHubApi.sha256(png))
        prefs.ghLastSync = System.currentTimeMillis()
    }

    private fun putIfChanged(api: GitHub, path: String, bytes: ByteArray, remoteSha: String?, msg: String) {
        if (remoteSha == GitHubApi.blobSha(bytes)) return
        api.put(path, bytes, msg, remoteSha)
    }

    private fun parseOrFail(text: String): JSONObject? = try { JSONObject(text) } catch (e: Exception) { null }

    // ---------------------------------------------------------------- json

    private fun customerJson(c: Customer) = JSONObject()
        .put("schema", 1).put("uid", c.uid).put("name", c.name).put("phone", c.phone)
        .put("address", c.address).put("note", c.note).put("createdAt", c.createdAt)
        .put("updatedAt", c.updatedAt).put("deletedAt", c.deletedAt ?: JSONObject.NULL)

    private fun orderJson(o: Order, c: Customer, atts: List<Pair<Attachment, String>>): JSONObject {
        val items = JSONArray()
        OrderItem.decode(o.itemsText).forEach { items.put(JSONObject().put("title", it.title).put("qty", it.qty).put("price", it.price)) }
        val attArr = JSONArray()
        atts.forEach { (a, p) -> attArr.put(JSONObject().put("path", p).put("type", a.type).put("createdAt", a.createdAt)) }
        return JSONObject()
            .put("schema", 1).put("uid", o.uid).put("syncNo", o.syncNo).put("customerUid", c.uid)
            .put("invoiceNo", o.invoiceNo).put("dateText", o.dateText).put("deliveryText", o.deliveryText)
            .put("description", o.description).put("notes", o.notes).put("items", items)
            .put("total", o.total).put("deposit", o.deposit).put("remaining", o.remaining)
            .put("status", JSONObject()
                .put("registered", o.stRegistered).put("inProgress", o.stInProgress).put("ready", o.stReady)
                .put("delivered", o.stDelivered).put("paid", o.paid).put("depositReceived", o.depositReceived)
                .put("settled", o.settled))
            .put("createdAt", o.createdAt).put("updatedAt", o.updatedAt)
            .put("telegram", JSONObject().put("messageId", o.tgMessageId).put("photoMessageId", o.tgPhotoMessageId).put("imageHash", o.tgImageHash))
            .put("attachments", attArr)
    }

    // ================================================================== GitHub pull / restore

    data class RemoteInfo(val orders: Int, val lastBackup: String?)

    suspend fun remoteInfo(): Result<RemoteInfo> = mutex.withLock {
        try {
            val api = GitHub(prefs)
            val n = api.list("orders").count { it.type == "dir" && it.name.toLongOrNull() != null }
            Result.success(RemoteInfo(n, api.lastCommitDate("orders")))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Non-destructive restore: every order folder on GitHub is merged into the local database
     * (matched by uid; the newer updatedAt wins). Nothing local is deleted.
     */
    suspend fun restoreFromGitHub(): Result<Int> = mutex.withLock {
        try {
            val api = GitHub(prefs)
            val dirs = api.list("orders").filter { it.type == "dir" }.mapNotNull { it.name.toLongOrNull() }.sorted()
            var n = 0
            for (no in dirs) if (mergeFolder(api, no)) n++
            // keep invoice numbering ahead of everything restored
            val maxInvoice = dao.allOrders().maxOfOrNull { Fmt.toLatinDigits(it.invoiceNo).toLongOrNull() ?: 0L } ?: 0L
            if (maxInvoice > prefs.invoiceCounter) prefs.invoiceCounter = maxInvoice
            prefs.ghLastSync = System.currentTimeMillis()
            Result.success(n)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Applies orders/<no> to the local DB. Returns true if the folder was readable. */
    private suspend fun mergeFolder(api: GitHub, no: Long): Boolean {
        val base = "orders/$no"
        val oj = api.getText("$base/order.json")?.let { parseOrFail(it) } ?: return false
        val cj = api.getText("$base/customer.json")?.let { parseOrFail(it) } ?: return false
        val cuid = cj.optString("uid").ifBlank { return false }
        val ouid = oj.optString("uid").ifBlank { return false }

        // customer
        val lc = dao.customerByUid(cuid)
        val rc = Customer(
            id = lc?.id ?: 0, name = cj.optString("name"), phone = cj.optString("phone"), address = cj.optString("address"),
            note = cj.optString("note"), createdAt = cj.optLong("createdAt"),
            deletedAt = if (cj.isNull("deletedAt")) null else cj.optLong("deletedAt"),
            uid = cuid, updatedAt = cj.optLong("updatedAt"),
        )
        val customerId = when {
            lc == null -> dao.insertCustomer(rc.copy(id = 0))
            rc.updatedAt > lc.updatedAt -> { dao.updateCustomer(rc); lc.id }
            else -> lc.id
        }

        // order
        val lo = dao.orderByUid(ouid)
        val st = oj.optJSONObject("status") ?: JSONObject()
        val tg = oj.optJSONObject("telegram") ?: JSONObject()
        val items = oj.optJSONArray("items") ?: JSONArray()
        val itemList = (0 until items.length()).map { items.getJSONObject(it) }
            .map { OrderItem(it.optString("title"), it.optInt("qty", 1), it.optLong("price")) }
        val ro = Order(
            id = lo?.id ?: 0, customerId = customerId, invoiceNo = oj.optString("invoiceNo"), dateText = oj.optString("dateText"),
            deliveryText = oj.optString("deliveryText"), description = oj.optString("description"),
            itemsText = OrderItem.encode(itemList), notes = oj.optString("notes"),
            total = oj.optLong("total"), deposit = oj.optLong("deposit"),
            stRegistered = st.optBoolean("registered", true), stInProgress = st.optBoolean("inProgress"),
            stReady = st.optBoolean("ready"), stDelivered = st.optBoolean("delivered"),
            paid = st.optBoolean("paid"), depositReceived = st.optBoolean("depositReceived"), settled = st.optBoolean("settled"),
            createdAt = oj.optLong("createdAt"), uid = ouid, syncNo = no, updatedAt = oj.optLong("updatedAt"),
            // never lose a Telegram message id we already know (prevents duplicate messages)
            tgMessageId = if ((lo?.tgMessageId ?: 0L) != 0L) lo!!.tgMessageId else tg.optLong("messageId"),
            tgPhotoMessageId = if ((lo?.tgPhotoMessageId ?: 0L) != 0L) lo!!.tgPhotoMessageId else tg.optLong("photoMessageId"),
            tgImageHash = if ((lo?.tgPhotoMessageId ?: 0L) != 0L) lo!!.tgImageHash else tg.optString("imageHash"),
            ghImageHash = lo?.ghImageHash.orEmpty(),
        )
        val orderId = when {
            lo == null -> dao.insertOrder(ro.copy(id = 0))
            ro.updatedAt > lo.updatedAt -> { dao.updateOrder(ro); lo.id }
            else -> { if (lo.syncNo != no && lo.syncNo == 0L) dao.setSyncNo(lo.id, no); lo.id }
        }

        // attachments (files are downloaded only if not already present)
        val arr = oj.optJSONArray("attachments") ?: JSONArray()
        val existing = dao.attachmentsOfOrderNow(orderId).map { it.remotePath }.toSet()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val path = a.optString("path")
            if (path.isBlank() || path in existing) continue
            val bytes = api.getRaw("$base/$path") ?: continue
            val type = a.optString("type", "other")
            val base2 = path.substringAfterLast('/')
            val ext = base2.substringAfterLast('.', "jpg")
            var name = if (base2.startsWith("${type}_")) base2.removePrefix("${type}_") else "${UUID.randomUUID()}.$ext"
            if (File(repo.attachDir, name).exists()) name = "${UUID.randomUUID()}.$ext"
            File(repo.attachDir, name).writeBytes(bytes)
            dao.insertAttachment(
                Attachment(customerId = customerId, orderId = orderId, type = type, fileName = name,
                    createdAt = a.optLong("createdAt"), remotePath = path, tgSent = true)
            )
        }
        return true
    }

    // ================================================================== Telegram

    private suspend fun telegramOrder(orderId: Long) {
        if (!prefs.tgConfigured) return
        val o0 = dao.order(orderId) ?: return
        val cust = dao.customer(o0.customerId) ?: return
        val tg = Telegram.from(prefs)
        val fa = prefs.telegramLang == "fa"
        var changed = false

        // ---- text message: send once, afterwards always edit the same message
        val text = Messages.build(o0, cust.name, cust.phone, cust.address, fa)
        var textId = o0.tgMessageId
        if (textId != 0L) {
            val r = tg.editText(textId, text)
            if (!r.ok) {
                if (r.notFound) textId = 0 else throw SyncException(r.message)
            }
        }
        if (textId == 0L) {
            val r = tg.sendText(text)
            if (!r.ok) throw SyncException(r.message)
            textId = r.messageId
            dao.setTgMessage(o0.id, textId)
            changed = true
        }

        // ---- order image: send once, edit the photo when the picture changed
        val o = dao.order(orderId) ?: return
        val png = renderPng(o, cust)
        val hash = GitHubApi.sha256(png)
        val file = File(ctx.cacheDir, "tg/order_${o.id}.png").also { it.parentFile?.mkdirs(); it.writeBytes(png) }
        var photoId = o.tgPhotoMessageId
        if (photoId != 0L && o.tgImageHash != hash) {
            val r = tg.editPhoto(photoId, file)
            if (r.ok) { dao.setTgPhoto(o.id, photoId, hash); changed = true }
            else if (r.notFound) photoId = 0 else throw SyncException(r.message)
        }
        if (photoId == 0L) {
            val r = tg.sendPhoto(file)
            if (!r.ok) throw SyncException(r.message)
            dao.setTgPhoto(o.id, r.messageId, hash)
            changed = true
        }

        // ---- receipts / order photos: each is sent exactly once
        val extra = dao.attachmentsOfOrderNow(o.id).filter {
            !it.tgSent && (it.type == AttachmentType.RECEIPT || it.type == AttachmentType.ORDER)
        }
        for (a in extra) {
            val f = repo.fileOf(a)
            if (!f.exists()) { dao.setTgSent(a.id); continue }
            val label = (if (a.type == AttachmentType.RECEIPT) "رسید پرداخت" else "عکس سفارش") + " — فاکتور ${o.invoiceNo}"
            val r = tg.sendPhoto(f, label)
            if (!r.ok) throw SyncException(r.message)
            dao.setTgSent(a.id)
        }

        // message ids are part of order.json so a restore on another phone does not resend everything
        if (changed) enqueueGh(o.id)
    }
}
