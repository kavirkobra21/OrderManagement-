package ir.kavir.ordernotebook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.util.Fmt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(repo: Repo, nav: Nav) {
    val customers by repo.dao.activeCustomers().collectAsState(emptyList())
    val orders by repo.dao.activeOrders().collectAsState(emptyList())
    val trash by repo.dao.deletedCustomers().collectAsState(emptyList())
    val pending by repo.dao.pendingCount().collectAsState(0)
    val active = orders.count { !it.isCompleted }
    val done = orders.count { it.isCompleted }
    val remaining = orders.sumOf { it.remaining }
    Column(Modifier.fillMaxSize()) {
        TopBar("دفترچه سفارش", actions = {
            IconButton(onClick = { nav.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "تنظیمات") }
        })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("مشتریان", Fmt.toPersianDigits(customers.size.toString()), Color(0xFFEFEDA8), Modifier.weight(1f))
                StatCard("سفارش‌های فعال", Fmt.toPersianDigits(active.toString()), Color(0xFFA8D2A8), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("تکمیل‌شده", Fmt.toPersianDigits(done.toString()), Color(0xFFBFE5C2), Modifier.weight(1f))
                StatCard("پرداخت‌های باقی‌مانده", Fmt.toman(remaining), PinkC, Modifier.weight(1f))
            }
            if (pending > 0) Text("⏳ ${Fmt.toPersianDigits(pending.toString())} مورد در صف همگام‌سازی (پس از اتصال اینترنت ارسال می‌شود)", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { nav.go(Screen.CustomerForm(null)) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("مشتری جدید")
            }
            FilledTonalButton(onClick = { nav.go(Screen.PasteOrder) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) { Text("ورود سفارش با Paste") }
            FilledTonalButton(onClick = { nav.go(Screen.Customers) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) { Text("لیست مشتریان") }
            FilledTonalButton(onClick = { nav.go(Screen.Trash) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) {
                Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text("سطل زباله (${Fmt.toPersianDigits(trash.size.toString())})")
            }
        }
    }
}

@Composable
fun CustomerListScreen(repo: Repo, nav: Nav) {
    val customers by repo.dao.activeCustomers().collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    val shown = customers.filter {
        q.isBlank() || it.name.contains(q, true) || Fmt.toLatinDigits(it.phone).contains(Fmt.toLatinDigits(q))
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("مشتریان", onBack = { nav.back() }, actions = {
            IconButton(onClick = { nav.go(Screen.CustomerForm(null)) }) { Icon(Icons.Filled.Add, "افزودن") }
        })
        Field("جستجوی نام یا شماره", q, { q = it }, Modifier.fillMaxWidth().padding(12.dp))
        if (shown.isEmpty()) Text("مشتری‌ای یافت نشد", Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().clickable { nav.go(Screen.CustomerDetail(c.id)) }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F4C6))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.name.ifBlank { "بدون نام" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (c.phone.isNotBlank()) Text(c.phone)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerFormScreen(repo: Repo, nav: Nav, id: Long?) {
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf(Customer()) }
    var loaded by remember { mutableStateOf(id == null) }
    LaunchedEffect(id) { if (id != null) { repo.dao.customer(id)?.let { c = it }; loaded = true } }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (id == null) "مشتری جدید" else "ویرایش مشتری", onBack = { nav.back() })
        if (!loaded) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("نام مشتری", c.name, { c = c.copy(name = it) })
            Field("شماره تماس", c.phone, { c = c.copy(phone = it) }, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
            Field("آدرس", c.address, { c = c.copy(address = it) }, singleLine = false, minLines = 3)
            Field("کد پستی", c.postalCode, { c = c.copy(postalCode = Fmt.toLatinDigits(it).filter { ch -> ch.isDigit() }.take(10)) }, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Field("آیدی", c.contactId, { c = c.copy(contactId = it) })
            Field("یادداشت اختصاصی", c.note, { c = c.copy(note = it) }, singleLine = false, minLines = 3)
            Button(
                onClick = {
                    scope.launch {
                        val newId = repo.saveCustomer(c)
                        if (id == null) nav.replaceTop(Screen.CustomerDetail(newId)) else nav.back()
                    }
                },
                enabled = c.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
            ) { Text("ذخیره") }
        }
    }
}

@Composable
fun CustomerDetailScreen(repo: Repo, nav: Nav, id: Long, snack: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val c by repo.dao.customerFlow(id).collectAsState(null)
    val orders by repo.dao.ordersOf(id).collectAsState(emptyList())
    val atts by repo.dao.attachmentsOfCustomer(id).collectAsState(emptyList())
    var confirm by remember { mutableStateOf(false) }
    val cust = c ?: return Column { TopBar("مشتری", onBack = { nav.back() }) }
    Column(Modifier.fillMaxSize()) {
        TopBar(cust.name.ifBlank { "مشتری" }, onBack = { nav.back() }, actions = {
            IconButton(onClick = { nav.go(Screen.CustomerForm(id)) }) { Icon(Icons.Filled.Edit, "ویرایش") }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "حذف") }
        })
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Section("اطلاعات مشتری") {
                    Text("شماره تماس: ${cust.phone}")
                    Text("آدرس: ${cust.address}")
                    if (cust.postalCode.isNotBlank()) Text("کد پستی: ${cust.postalCode}")
                    if (cust.contactId.isNotBlank()) Text("آیدی: ${cust.contactId}")
                    if (cust.note.isNotBlank()) Text("یادداشت: ${cust.note}")
                }
            }
            item {
                Button(onClick = { nav.go(Screen.OrderForm(id, null)) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp), shape = RoundedCornerShape(26.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("ثبت سفارش جدید")
                }
            }
            item { Text("سفارش‌ها", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold) }
            if (orders.isEmpty()) item { Text("هنوز سفارشی ثبت نشده", Modifier.padding(16.dp)) }
            items(orders, key = { it.id }) { o -> OrderRow(o) { nav.go(Screen.OrderForm(id, o.id)) } }
            item { Text("گالری تصاویر مشتری", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold) }
            item {
                if (atts.isEmpty()) Text("تصویری ثبت نشده", Modifier.padding(16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(atts, key = { it.id }) { a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Thumb(repo.fileOf(a), Modifier.size(96.dp), 300)
                            Text(attLabel(a.type), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("انتقال به سطل زباله", "«${cust.name}» با همه سفارش‌ها و عکس‌هایش به سطل زباله منتقل می‌شود و قابل بازیابی است.", "انتقال", { confirm = false }) {
        scope.launch { repo.trashCustomer(id); snack.showSnackbar("به سطل زباله منتقل شد"); nav.back() }
    }
}

fun attLabel(t: String) = when (t) {
    "receipt" -> "رسید"; "order" -> "سفارش"; "product" -> "محصول نهایی"; "satisfaction" -> "رضایت"; else -> "عکس"
}

@Composable
fun OrderRow(o: Order, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFDDEFDD))) {
        Column(Modifier.padding(14.dp)) {
            Text("فاکتور ${o.invoiceNo}  •  ${o.dateText}", fontWeight = FontWeight.Bold)
            val items = OrderItem.decode(o.itemsText)
            if (items.isEmpty()) Text(o.description.lines().firstOrNull { it.isNotBlank() } ?: "—", maxLines = 1)
            items.forEach {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("• " + it.title + (if (it.qty > 1) " ×" + Fmt.toPersianDigits(it.qty.toString()) else ""), Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    if (it.price > 0) Text(Fmt.toman(it.price * it.qty), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
            Text("کل: ${Fmt.toman(o.total)}   باقی‌مانده: ${Fmt.toman(o.remaining)}")
            Text(
                when { o.stDelivered -> "تحویل داده شده"; o.stReady -> "آماده شده"; o.stInProgress -> "در حال انجام"; else -> "ثبت شده" },
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun TrashScreen(repo: Repo, nav: Nav) {
    val scope = rememberCoroutineScope()
    val trash by repo.dao.deletedCustomers().collectAsState(emptyList())
    var confirmAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("سطل زباله", onBack = { nav.back() }, actions = {
            if (trash.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("خالی کردن") }
        })
        if (trash.isEmpty()) Text("سطل زباله خالی است", Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trash, key = { it.id }) { c ->
                var progress by remember(c.id) { mutableStateOf(0f) }
                var holdJob by remember(c.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                Card(
                    Modifier.fillMaxWidth().pointerInput(c.id) {
                        // press and HOLD 5 s on the item itself to delete it for good; releasing, scrolling or moving away cancels
                        detectTapGestures(onPress = {
                            holdJob = scope.launch {
                                val start = System.currentTimeMillis()
                                while (true) {
                                    val p = ((System.currentTimeMillis() - start) / 5000f).coerceAtMost(1f)
                                    progress = p
                                    if (p >= 1f) { withContext(NonCancellable) { repo.purgeCustomer(c.id) }; break }
                                    kotlinx.coroutines.delay(50)
                                }
                            }
                            tryAwaitRelease()
                            holdJob?.cancel(); holdJob = null; progress = 0f
                        })
                    },
                    shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF6DDE5)),
                ) {
                    Column {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "بدون نام" }, fontWeight = FontWeight.Bold)
                                Text(c.phone)
                                Text(
                                    if (progress > 0f) "در حال حذف دائمی… ${kotlin.math.ceil((1f - progress) * 5f).toInt()} ثانیه (رها کنید تا لغو شود)"
                                    else "برای حذف دائمی، ۵ ثانیه نگه دارید",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(onClick = { scope.launch { repo.restoreCustomer(c.id) } }) { Icon(Icons.Filled.Restore, "بازیابی") }
                        }
                        if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFB3261E))
                    }
                }
            }
        }
    }
    if (confirmAll) ConfirmDialog("خالی کردن سطل زباله", "همه موارد برای همیشه پاک می‌شوند.", "خالی کن", { confirmAll = false }) {
        scope.launch { repo.emptyTrash() }
    }
}
