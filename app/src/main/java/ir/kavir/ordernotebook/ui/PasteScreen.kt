package ir.kavir.ordernotebook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.util.Fmt
import ir.kavir.ordernotebook.util.FormTemplate
import ir.kavir.ordernotebook.util.OrderPaste
import kotlinx.coroutines.launch

/**
 * "ورود سفارش با Paste": paste the customer's filled-in form, review/edit the extracted draft,
 * type the prices, and only then register. Nothing is saved before the final confirmation.
 */
@Composable
fun PasteOrderScreen(repo: Repo, nav: Nav, snack: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var raw by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var contactId by remember { mutableStateOf("") }
    var postal by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var items by remember { mutableStateOf(listOf<OrderItem>()) }
    var busy by remember { mutableStateOf(false) }

    fun parseNow(text: String) {
        val d = OrderPaste.parse(text)
        name = d.name; phone = d.phone; address = d.address; notes = d.notes
        contactId = d.contactId; postal = d.postalCode; quantity = d.quantity.toString()
        items = d.items.map { OrderItem(it.name, it.qty, 0) }.ifEmpty { listOf(OrderItem("", 1, 0)) }
        parsed = true
    }

    val total = items.sumOf { it.price * it.qty }

    Column(Modifier.fillMaxSize()) {
        TopBar("ورود سفارش با Paste", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            Section("متن سفارش مشتری") {
                Text("قالب را برای مشتری بفرستید، پاسخ او را کپی کنید و اینجا Paste کنید.", style = MaterialTheme.typography.bodySmall)
                Field("متن سفارش", raw, { raw = it }, singleLine = false, minLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val t = clipboard.getText()?.text.orEmpty()
                        if (t.isBlank()) scope.launch { snack.showSnackbar("کلیپ‌بورد خالی است") }
                        else { raw = t; parseNow(t) }
                    }) { Text("Paste و استخراج") }
                    OutlinedButton(onClick = { parseNow(raw) }, enabled = raw.isNotBlank()) { Text("استخراج") }
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(FormTemplate.render(repo.prefs.formTemplate, FormTemplate.now())))
                    scope.launch { snack.showSnackbar("قالب فرم کپی شد؛ برای مشتری بفرستید") }
                }, Modifier.fillMaxWidth()) { Text("کپی قالب فرم برای مشتری") }
                if (!parsed) OutlinedButton(onClick = { parseNow("") }, Modifier.fillMaxWidth()) { Text("ثبت دستی بدون متن") }
            }

            if (parsed) {
                Section("پیش‌نویس سفارش — قبل از ثبت بررسی و ویرایش کنید") {
                    Field("نام مشتری", name, { name = it })
                    Field("شماره تماس", phone, { phone = Fmt.toLatinDigits(it) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Field("آیدی", contactId, { contactId = it })
                    Field("کد پستی", postal, { postal = Fmt.toLatinDigits(it).filter { ch -> ch.isDigit() }.take(10) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Field("تعداد", quantity, { quantity = Fmt.toLatinDigits(it).filter { ch -> ch.isDigit() }.take(4) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Field("آدرس", address, { address = it }, singleLine = false, minLines = 2)
                    Field("نکات سفارش", notes, { notes = it }, singleLine = false, minLines = 2)
                }
                Section("سفارش‌ها (قیمت‌ها را وارد کنید)") {
                    items.forEachIndexed { i, it ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field("نام آیتم", it.title, { v -> items = items.toMutableList().also { l -> l[i] = it.copy(title = v) } }, Modifier.weight(1f))
                                IconButton(onClick = { items = items.toMutableList().also { l -> l.removeAt(i) } }) { Icon(Icons.Filled.Close, "حذف آیتم") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field("تعداد", it.qty.toString(), { v -> items = items.toMutableList().also { l -> l[i] = it.copy(qty = Fmt.parseMoney(v).toInt().coerceAtLeast(1)) } },
                                    Modifier.width(84.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                                MoneyField("قیمت هر یک", if (it.price > 0) it.price.toString() else "",
                                    { v -> items = items.toMutableList().also { l -> l[i] = it.copy(price = Fmt.parseMoney(v)) } }, Modifier.weight(1f))
                            }
                        }
                        HorizontalDivider()
                    }
                    OutlinedButton(onClick = { items = items + OrderItem("", 1, 0) }) { Text("+ آیتم") }
                    Text("جمع آیتم‌ها: ${Fmt.toman(total)}", style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    enabled = !busy && name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            val ph = Fmt.toLatinDigits(phone).trim()
                            // same phone number = same customer (no duplicate customers)
                            val existing = if (ph.isBlank()) null
                            else repo.dao.allCustomers().firstOrNull { it.deletedAt == null && Fmt.toLatinDigits(it.phone) == ph }
                            val cid = existing?.id ?: repo.saveCustomer(Customer(name = name.trim(), phone = ph, address = address.trim(), contactId = contactId.trim(), postalCode = postal))
                            val clean = items.filter { it.title.isNotBlank() }
                            val invoice = Fmt.toPersianDigits((repo.prefs.invoiceCounter + 1).toString())
                            val oid = repo.saveOrder(
                                Order(
                                    customerId = cid, invoiceNo = invoice, dateText = Fmt.todayJalali(), notes = notes.trim(), quantity = quantity.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                    itemsText = OrderItem.encode(clean), total = clean.sumOf { it.price * it.qty },
                                )
                            )
                            repo.prefs.invoiceCounter = repo.prefs.invoiceCounter + 1
                            snack.showSnackbar(if (existing != null) "سفارش برای مشتری موجود (${existing.name}) ثبت شد" else "سفارش ثبت شد")
                            nav.replaceTop(Screen.OrderForm(cid, oid))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp), shape = RoundedCornerShape(26.dp),
                ) { Text("تأیید و ثبت نهایی سفارش") }
                if (name.isBlank()) Text("نام مشتری را وارد کنید تا ثبت فعال شود.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
