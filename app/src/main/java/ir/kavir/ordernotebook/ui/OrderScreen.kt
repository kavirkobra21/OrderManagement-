package ir.kavir.ordernotebook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.kavir.ordernotebook.data.Attachment
import ir.kavir.ordernotebook.data.AttachmentType
import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.net.Telegram
import ir.kavir.ordernotebook.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OrderScreen(repo: Repo, nav: Nav, customerId: Long, orderId: Long?, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var customer by remember { mutableStateOf<Customer?>(null) }
    var order by remember { mutableStateOf(Order(customerId = customerId, dateText = Fmt.todayJalali())) }
    var loaded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(listOf<OrderItem>()) }
    var totalS by remember { mutableStateOf("") }
    var depositS by remember { mutableStateOf("") }
    var showSend by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf(AttachmentType.RECEIPT) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(orderId) {
        customer = repo.dao.customer(customerId)
        if (orderId != null) {
            repo.dao.order(orderId)?.let {
                order = it; items = OrderItem.decode(it.itemsText)
                totalS = if (it.total > 0) it.total.toString() else ""
                depositS = if (it.deposit > 0) it.deposit.toString() else ""
            }
        } else {
            order = order.copy(invoiceNo = Fmt.toPersianDigits((repo.prefs.invoiceCounter + 1).toString()))
        }
        loaded = true
    }

    val atts by (if (order.id != 0L) repo.dao.attachmentsOfOrder(order.id) else kotlinx.coroutines.flow.flowOf(emptyList<Attachment>()))
        .collectAsState(emptyList())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch(Dispatchers.IO) {
            var n = 0
            uris.forEach { if (repo.addAttachment(it, customerId, order.id, pendingType) != null) n++ }
            withContext(Dispatchers.Main) { snack.showSnackbar(if (uris.isEmpty()) "تصویری انتخاب نشد" else "${Fmt.toPersianDigits(n.toString())} تصویر افزوده شد") }
        }
    }

    // The order as currently typed (not yet saved)
    val live = order.copy(total = Fmt.parseMoney(totalS), deposit = Fmt.parseMoney(depositS), itemsText = OrderItem.encode(items))
    val data = pageData(live, customer)

    val previewBmp by produceState<android.graphics.Bitmap?>(null, data) {
        delay(150)
        value = withContext(Dispatchers.Default) { NotebookPage.render(data, 0.9f) }
    }

    suspend fun save(): Order? {
        val o = live.copy(
            stRegistered = true,
            // keep status flags consistent with payments
            depositReceived = live.depositReceived || live.settled || live.paid,
        )
        val isNew = o.id == 0L
        val id = repo.saveOrder(o)
        val saved = o.copy(id = id)
        order = saved
        if (isNew) repo.prefs.invoiceCounter = repo.prefs.invoiceCounter + 1
        return saved
    }

    suspend fun renderFile(o: Order): File = withContext(Dispatchers.Default) {
        val bmp = NotebookPage.render(pageData(o, customer), 1.8f)
        val f = File(ctx.cacheDir, "orders/order_${Fmt.toLatinDigits(o.invoiceNo).ifBlank { o.id.toString() }}.png")
        savePng(bmp, f); bmp.recycle(); f
    }

    suspend fun sendTelegram(o: Order, file: File, receipts: Boolean, photos: Boolean): Telegram.Result = withContext(Dispatchers.IO) {
        val tg = Telegram(repo.prefs)
        val fa = repo.prefs.telegramLang == "fa"
        val c = customer
        val msg = Telegram.message(o, c?.name.orEmpty(), c?.phone.orEmpty(), c?.address.orEmpty(), itemsAsLines(o), fa)
        val r1 = tg.sendText(msg)
        if (!r1.ok) return@withContext r1
        val r2 = tg.sendPhoto(file, null)
        if (!r2.ok) return@withContext r2
        val all = repo.dao.attachmentsOfCustomerNow(customerId).filter { it.orderId == o.id }
        val extra = all.filter { (receipts && it.type == AttachmentType.RECEIPT) || (photos && it.type != AttachmentType.RECEIPT) }
        for (a in extra) {
            val r = tg.sendPhoto(repo.fileOf(a), attLabel(a.type))
            if (!r.ok) return@withContext r
        }
        Telegram.Result(true, "به تلگرام ارسال شد")
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (orderId == null) "سفارش جدید" else "سفارش ${order.invoiceNo}", onBack = { nav.back() })
        if (!loaded) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // ---- the notebook page (live preview)
            Card(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(24.dp)) {
                val b = previewBmp
                if (b != null) Image(b.asImageBitmap(), "صفحه دفترچه", Modifier.fillMaxWidth())
                else Box(Modifier.fillMaxWidth().aspectRatio(1000f / 1400f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            Section("مشخصات سفارش") {
                Field("تاریخ", order.dateText, { order = order.copy(dateText = it) })
                Field("شماره فاکتور", order.invoiceNo, { order = order.copy(invoiceNo = it) })
                Field("زمان تحویل", order.deliveryText, { order = order.copy(deliveryText = it) })
                Field("نکات سفارش", order.notes, { order = order.copy(notes = it) }, singleLine = false, minLines = 2)
                Field("شرح سفارش", order.description, { order = order.copy(description = it) }, singleLine = false, minLines = 3)
                customer?.let { Text("مشتری: ${it.name} — ${it.phone}", style = MaterialTheme.typography.bodySmall) }
            }

            Section("آیتم‌های سفارش") {
                items.forEachIndexed { i, it ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("عنوان", it.title, { v -> items = items.toMutableList().also { l -> l[i] = it.copy(title = v) } }, Modifier.weight(1f))
                        Field("تعداد", it.qty.toString(), { v -> items = items.toMutableList().also { l -> l[i] = it.copy(qty = Fmt.parseMoney(v).toInt().coerceAtLeast(1)) } },
                            Modifier.width(70.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Field("قیمت", if (it.price > 0) it.price.toString() else "", { v -> items = items.toMutableList().also { l -> l[i] = it.copy(price = Fmt.parseMoney(v)) } },
                            Modifier.width(110.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        IconButton(onClick = { items = items.toMutableList().also { l -> l.removeAt(i) } }) { Icon(Icons.Filled.Close, "حذف آیتم") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { items = items + OrderItem("", 1, 0) }) { Text("+ آیتم") }
                    OutlinedButton(
                        onClick = { totalS = items.sumOf { it.price * it.qty }.toString() },
                        enabled = items.any { it.price > 0 },
                    ) { Text("جمع آیتم‌ها ← هزینه کل") }
                }
            }

            Section("پرداخت‌ها") {
                Field("هزینه کل", totalS, { totalS = Fmt.toLatinDigits(it).filter { c -> c.isDigit() } }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                Field("بیعانه", depositS, { depositS = Fmt.toLatinDigits(it).filter { c -> c.isDigit() } }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("باقی‌مانده: ${Fmt.money(live.remaining)}", style = MaterialTheme.typography.titleMedium)
            }

            Section("وضعیت") {
                CheckRow("سفارش ثبت شده", true) { }
                CheckRow("سفارش در حال انجام", order.stInProgress) { order = order.copy(stInProgress = it) }
                CheckRow("سفارش آماده شده", order.stReady) { order = order.copy(stReady = it, stInProgress = if (it) true else order.stInProgress) }
                CheckRow("سفارش تحویل داده شده", order.stDelivered) { order = order.copy(stDelivered = it) }
                CheckRow("مبلغ پرداخت شده", order.paid) { order = order.copy(paid = it) }
                CheckRow("بیعانه دریافت شده", order.depositReceived) { order = order.copy(depositReceived = it) }
                CheckRow("تسویه حساب کامل شده", order.settled) { order = order.copy(settled = it) }
            }

            Section("تصاویر این سفارش") {
                if (order.id == 0L) Text("برای افزودن عکس، ابتدا سفارش را ذخیره کنید.")
                else {
                    val types = listOf(AttachmentType.RECEIPT to "رسید پرداخت", AttachmentType.ORDER to "عکس سفارش", AttachmentType.PRODUCT to "محصول نهایی", AttachmentType.SATISFACTION to "رضایت مشتری")
                    types.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (t, label) ->
                                OutlinedButton(onClick = {
                                    pendingType = t
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }, Modifier.weight(1f)) { Text("+ $label") }
                            }
                        }
                    }
                    atts.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { a ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        Thumb(repo.fileOf(a), Modifier.fillMaxWidth().aspectRatio(1f), 300)
                                        IconButton(onClick = { scope.launch { repo.removeAttachment(a) } }, Modifier.align(Alignment.TopStart).size(32.dp)) {
                                            Icon(Icons.Filled.Delete, "حذف", tint = androidx.compose.ui.graphics.Color(0xFFB3261E))
                                        }
                                    }
                                    Text(attLabel(a.type), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Section("ذخیره و خروجی") {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val wasNew = order.id == 0L
                            val saved = save()
                            snack.showSnackbar("ذخیره شد")
                            val p = repo.prefs
                            if (wasNew && saved != null && p.autoSend && p.workerUrl.isNotBlank() && p.chatId.isNotBlank()) {
                                val r = sendTelegram(saved, renderFile(saved), receipts = true, photos = false)
                                snack.showSnackbar(r.message)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
                ) { Icon(Icons.Filled.Save, null); Spacer(Modifier.width(8.dp)); Text("ذخیره سفارش") }

                FilledTonalButton(onClick = {
                    scope.launch {
                        busy = true
                        val saved = save()!!
                        val f = renderFile(saved)
                        val ok = saveToGallery(ctx, f, f.name)
                        snack.showSnackbar(if (ok) "تصویر در گالری (Pictures/OrderNotebook) ذخیره شد" else "ذخیره تصویر ناموفق بود")
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("تولید و ذخیره تصویر در گوشی") }

                FilledTonalButton(onClick = {
                    scope.launch {
                        busy = true
                        val saved = save()!!
                        val f = renderFile(saved)
                        shareImage(ctx, f)
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("اشتراک‌گذاری تصویر") }

                FilledTonalButton(onClick = { showSend = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("ارسال به تلگرام")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (showSend) {
        var receipts by remember { mutableStateOf(true) }
        var photos by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSend = false },
            title = { Text("ارسال به تلگرام") },
            text = {
                Column {
                    Text("پیام سفارش و تصویر دفترچه ارسال می‌شود.")
                    CheckRow("تصویر رسید پرداخت", receipts) { receipts = it }
                    CheckRow("سایر عکس‌های سفارش", photos) { photos = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSend = false
                    scope.launch {
                        busy = true
                        val saved = save()!!
                        val r = sendTelegram(saved, renderFile(saved), receipts, photos)
                        snack.showSnackbar(r.message)
                        busy = false
                    }
                }) { Text("ارسال") }
            },
            dismissButton = { TextButton(onClick = { showSend = false }) { Text("انصراف") } },
        )
    }
}
