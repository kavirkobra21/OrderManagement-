package ir.kavir.ordernotebook.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.kavir.ordernotebook.data.Backup
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.net.Telegram
import ir.kavir.ordernotebook.net.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(repo: Repo, nav: Nav, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = repo.prefs
    val updater = remember { Updater(ctx, prefs) }
    val tg = remember { Telegram(prefs) }

    var token by remember { mutableStateOf(prefs.botToken) }
    var chat by remember { mutableStateOf(prefs.chatId) }
    var worker by remember { mutableStateOf(prefs.workerUrl) }
    var lang by remember { mutableStateOf(prefs.telegramLang) }
    var auto by remember { mutableStateOf(prefs.autoSend) }
    var tgStatus by remember { mutableStateOf("") }

    var ghToken by remember { mutableStateOf(prefs.ghToken) }
    var ghRepo by remember { mutableStateOf(prefs.ghRepo) }
    var check by remember { mutableStateOf<Updater.Check?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    var pendingImport by remember { mutableStateOf<Pair<Uri, Backup.Info>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val r = Backup.export(repo, uri)
            busy = false
            snack.showSnackbar(r.fold({ "بکاپ ساخته شد: ${it.customers} مشتری، ${it.orders} سفارش، ${it.attachments} تصویر" }, { "خطا در ساخت بکاپ: ${it.message}" }))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { Backup.inspect(ctx, uri) }
            r.fold({ pendingImport = uri to it }, { scope.launch { snack.showSnackbar("فایل بکاپ معتبر نیست: ${it.message}") } })
        }
    }

    fun saveTelegram() {
        prefs.botToken = token; prefs.chatId = chat; prefs.workerUrl = worker; prefs.telegramLang = lang; prefs.autoSend = auto
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("تنظیمات", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            Section("تلگرام (از طریق Cloudflare Worker)") {
                Field("Cloudflare Worker URL", worker, { worker = it }, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                Field("Telegram Bot Token (اختیاری اگر در Worker ذخیره شده)", token, { token = it }, password = true)
                Field("Telegram Chat ID", chat, { chat = it })
                Text("زبان پیام‌ها")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(lang == "fa", { lang = "fa" }); Text("فارسی")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(lang == "en", { lang = "en" }); Text("English")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(auto, { auto = it }); Spacer(Modifier.width(8.dp)); Text("ارسال خودکار پس از ثبت سفارش جدید")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveTelegram(); scope.launch { snack.showSnackbar("ذخیره شد") } }) { Text("ذخیره") }
                    OutlinedButton(enabled = !busy, onClick = {
                        saveTelegram(); busy = true; tgStatus = "در حال بررسی…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { tg.checkConnection() }
                            tgStatus = (if (r.ok) "✓ " else "✗ ") + r.message; busy = false
                        }
                    }) { Text("بررسی اتصال") }
                    OutlinedButton(enabled = !busy, onClick = {
                        saveTelegram(); busy = true; tgStatus = "در حال ارسال…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { tg.sendText("✅ پیام آزمایشی از دفترچه سفارش / Test message") }
                            tgStatus = (if (r.ok) "✓ " else "✗ ") + r.message; busy = false
                        }
                    }) { Text("پیام آزمایشی") }
                }
                if (tgStatus.isNotBlank()) Text(tgStatus)
                Text(
                    "نکته: برای دریافت پیام خصوصی، ابتدا ربات را در تلگرام Start کنید. راه‌اندازی Worker در README توضیح داده شده است.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("GitHub Updater") {
                val cur = remember { updater.currentVersion() }
                Text("نسخه فعلی: $cur")
                Field("Repository (owner/repo)", ghRepo, { ghRepo = it })
                Field("GitHub Personal Access Token (فقط برای مخزن خصوصی)", ghToken, { ghToken = it }, password = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && progress == null, onClick = {
                        prefs.ghToken = ghToken; prefs.ghRepo = ghRepo; busy = true; check = null
                        scope.launch { check = withContext(Dispatchers.IO) { updater.check() }; busy = false }
                    }) { Text("بررسی بروزرسانی") }
                }
                when (val c = check) {
                    is Updater.Check.Error -> Text("خطا: ${c.msg}", color = MaterialTheme.colorScheme.error)
                    is Updater.Check.UpToDate -> Text("برنامه به‌روز است" + (c.release?.let { " (آخرین نسخه: ${it.tag})" } ?: ""))
                    is Updater.Check.Available -> {
                        Text("نسخه جدید: ${c.release.tag}", style = MaterialTheme.typography.titleMedium)
                        if (c.release.body.isNotBlank()) {
                            Text("تغییرات:", style = MaterialTheme.typography.labelLarge)
                            Text(c.release.body.take(1500), style = MaterialTheme.typography.bodySmall)
                        }
                        val p = progress
                        if (p != null) {
                            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("$p٪")
                        }
                        Button(enabled = p == null, onClick = {
                            if (!updater.canInstall()) {
                                updater.openInstallSettings()
                                scope.launch { snack.showSnackbar("اجازه نصب از این برنامه را فعال کنید و دوباره تلاش کنید") }
                            } else scope.launch {
                                progress = 0
                                val r = withContext(Dispatchers.IO) { updater.download(c.release) { v -> progress = v } }
                                progress = null
                                r.fold({ updater.install(it) }, { snack.showSnackbar("دانلود ناموفق: ${it.message}") })
                            }
                        }) { Text("دانلود و نصب بروزرسانی") }
                        Text("اطلاعات مشتریان، عکس‌ها و تنظیمات هنگام بروزرسانی حفظ می‌شوند.", style = MaterialTheme.typography.bodySmall)
                    }
                    null -> {}
                }
            }

            Section("بکاپ و بازیابی") {
                Text("بکاپ شامل مشتریان، سفارش‌ها، همه تصاویر، سطل زباله و تنظیمات (از جمله توکن‌ها) است؛ فایل را در جای امن نگه دارید.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    exportLauncher.launch("OrderNotebook-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip")
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Export Backup") }
                OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Import Backup") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    pendingImport?.let { (uri, info) ->
        val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(info.createdAt))
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("بازیابی بکاپ") },
            text = {
                Text(
                    "فایل سالم است.\nنسخه بکاپ: ${info.version}\nتاریخ: $date\nمشتریان: ${info.customers} (در سطل زباله: ${info.trash})\nسفارش‌ها: ${info.orders}\nتصاویر: ${info.attachments}\n\n" +
                        "هشدار: همه اطلاعات فعلی برنامه با این بکاپ جایگزین می‌شود."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) { Backup.import(repo, uri) }
                        busy = false
                        r.fold({
                            token = prefs.botToken; chat = prefs.chatId; worker = prefs.workerUrl; lang = prefs.telegramLang; auto = prefs.autoSend; ghRepo = prefs.ghRepo
                            snack.showSnackbar("بازیابی کامل شد")
                        }, { snack.showSnackbar("بازیابی ناموفق: ${it.message}") })
                    }
                }) { Text("جایگزین کن") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("انصراف") } },
        )
    }
}
