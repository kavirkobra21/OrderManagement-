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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.kavir.ordernotebook.data.Backup
import ir.kavir.ordernotebook.data.Repo
import ir.kavir.ordernotebook.net.GitHub
import ir.kavir.ordernotebook.net.SyncManager
import ir.kavir.ordernotebook.net.Updater
import ir.kavir.ordernotebook.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun SettingsScreen(repo: Repo, nav: Nav, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = repo.prefs
    val updater = remember { Updater(ctx, prefs) }

    // ---- Telegram
    var showTg by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var showTpl by remember { mutableStateOf(false) }

    // ---- GitHub connection (entered once, stored encrypted)
    var ghEditing by remember { mutableStateOf(!prefs.ghConfigured) }
    var ghRepo by remember { mutableStateOf(prefs.ghRepo) }
    var ghToken by remember { mutableStateOf(prefs.ghToken) }
    var ghStatus by remember { mutableStateOf("") }

    // ---- updater
    var check by remember { mutableStateOf<Updater.Check?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }

    // ---- sync / backup
    val pending by repo.dao.pendingCount().collectAsState(0)
    val lastError by repo.dao.lastOpError().collectAsState(null)
    val syncing by repo.sync.syncing.collectAsState()
    var lastSync by remember { mutableStateOf(prefs.ghLastSync) }
    var remoteInfo by remember { mutableStateOf<SyncManager.RemoteInfo?>(null) }
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

    fun fmtTime(t: Long) = if (t <= 0) "—" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(t))

    Column(Modifier.fillMaxSize()) {
        TopBar("تنظیمات", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            // ============================================================ Telegram (sent by GitHub Actions)
            Section("تلگرام (ارسال با GitHub Actions)") {
                Text(
                    "متن سفارش و همه‌ی عکس‌ها فقط توسط GitHub Actions به تلگرام ارسال می‌شوند. Bot Token و Chat ID را یک‌بار اینجا وارد کنید؛ " +
                        "برنامه آن‌ها را رمزنگاری و در Secrets مخزن سفارش‌های شما ثبت می‌کند و روی گوشی نگه نمی‌دارد.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { showTg = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Telegram Settings") }
                OutlinedButton(onClick = { showForm = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("ویرایش متن سفارش") }
                OutlinedButton(onClick = { showTpl = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("قالب پیام تلگرام") }
            }

            // ============================================================ GitHub connection
            Section("اتصال GitHub (ذخیره‌ی سفارش‌ها و تصاویر)") {
                if (!ghEditing) {
                    Text("مخزن: ${prefs.ghRepo}")
                    Text("✓ توکن به‌صورت رمزنگاری‌شده ذخیره شده و نیازی به وارد کردن مجدد نیست")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ghEditing = true }) { Text("ویرایش") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true; ghStatus = "در حال بررسی…"
                            scope.launch { ghStatus = testGitHub(prefs); busy = false }
                        }) { Text("بررسی اتصال") }
                    }
                } else {
                    Field("Repository URL یا owner/repo", ghRepo, { ghRepo = it }, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                    Field("GitHub Personal Access Token", ghToken, { ghToken = it }, password = true)
                    Text("دسترسی لازم: Contents → Read and write (فقط برای همین مخزن).", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && ghToken.isNotBlank() && ghRepo.isNotBlank(), onClick = {
                        prefs.ghRepo = ghRepo; prefs.ghToken = ghToken
                        ghRepo = prefs.ghRepo
                        busy = true; ghStatus = "ذخیره شد؛ در حال بررسی…"
                        scope.launch { ghStatus = testGitHub(prefs); busy = false; if (prefs.ghConfigured) ghEditing = false }
                    }) { Text("ذخیره و بررسی") }
                }
                if (ghStatus.isNotBlank()) Text(ghStatus)
                Text(
                    "هشدار: اطلاعات مشتریان (نام، تلفن، آدرس، رسیدها) در مخزن ذخیره می‌شود؛ مخزن باید Private باشد. " +
                        "اگر مخزن Public باشد، برنامه از آپلود خودداری می‌کند.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ============================================================ Updater (version only)
            Section("GitHub Updater") {
                val cur = remember { updater.currentVersion() }
                Text("نسخه فعلی: $cur")
                Button(enabled = !busy && progress == null, onClick = {
                    busy = true; check = null
                    scope.launch { check = withContext(Dispatchers.IO) { updater.check() }; busy = false }
                }) { Text("Check for Updates") }
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
                        }) { Text("Download Update") }
                        Text("اطلاعات مشتریان، عکس‌ها و تنظیمات هنگام بروزرسانی حفظ می‌شوند.", style = MaterialTheme.typography.bodySmall)
                    }
                    null -> {}
                }
            }

            // ============================================================ sync + GitHub backup
            Section("همگام‌سازی و GitHub Backup") {
                Text(
                    when {
                        syncing -> "در حال همگام‌سازی…"
                        pending > 0 -> "⏳ ${Fmt.toPersianDigits(pending.toString())} مورد در صف همگام‌سازی"
                        else -> "✓ همه چیز همگام است"
                    }
                )
                lastError?.let { Text("آخرین خطا: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("آخرین بکاپ/همگام‌سازی GitHub: ${fmtTime(lastSync)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (!repo.sync.online()) scope.launch { snack.showSnackbar("اینترنت در دسترس نیست؛ پس از اتصال خودکار انجام می‌شود") }
                        repo.sync.kick()
                    }) { Text("Sync now") }
                    OutlinedButton(enabled = !busy && prefs.ghConfigured, onClick = {
                        scope.launch {
                            val n = repo.enqueueAll()
                            snack.showSnackbar("${Fmt.toPersianDigits(n.toString())} سفارش برای آپلود در صف قرار گرفت")
                        }
                    }) { Text("Backup to GitHub") }
                }
                OutlinedButton(enabled = !busy && prefs.ghConfigured, onClick = {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { repo.sync.remoteInfo() }
                        busy = false
                        r.fold({ remoteInfo = it }, { snack.showSnackbar("خطا: " + (it.message ?: "اتصال ناموفق")) })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Restore from GitHub") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // ============================================================ ZIP
            Section("Export / Import (ZIP)") {
                Text("فایل ZIP شامل همه مشتریان، سفارش‌ها، تصاویر و رسیدها، سطل زباله و تنظیمات است؛ در جای امن نگه دارید.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    exportLauncher.launch("OrderNotebook-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip")
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Export Backup") }
                OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Import Backup") }
            }
        }
    }

    // ---------------------------------------------------------------- Telegram Settings dialog
    if (showTg) {
        var dLang by remember { mutableStateOf(prefs.telegramLang) }
        var dOn by remember { mutableStateOf(prefs.autoSend) }
        var botToken by remember { mutableStateOf("") }
        var chatId by remember { mutableStateOf("") }
        var steps by remember { mutableStateOf<List<ir.kavir.ordernotebook.net.RepoSetup.Step>>(emptyList()) }
        var status by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!working) showTg = false },
            title = { Text("Telegram Settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Telegram Bot Token", botToken, { botToken = it.trim() }, transformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Field("Telegram Chat ID", chatId, { chatId = Fmt.toLatinDigits(it).trim() })
                    Text(
                        "با «ذخیره و اتصال»، مخزن سفارش‌ها (${prefs.ghRepo}) آماده می‌شود: Workflow و اسکریپت ساخته یا به‌روز می‌شوند و " +
                            "TELEGRAM_BOT_TOKEN و TELEGRAM_CHAT_ID در Secrets ثبت و بررسی می‌شوند. توکن GitHub باید این دسترسی‌ها را داشته باشد: " +
                            "Contents، Workflows، Secrets (هر سه Read and write) و Actions (برای آزمایش). ربات را در تلگرام Start کنید.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("زبان پیام‌ها")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(dLang == "fa", { dLang = "fa" }); Text("فارسی")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(dLang == "en", { dLang = "en" }); Text("English")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(dOn, { dOn = it }); Spacer(Modifier.width(8.dp)); Text("ارسال خودکار سفارش‌ها")
                    }
                    Button(enabled = !working && prefs.ghConfigured && botToken.isNotBlank() && chatId.isNotBlank(), onClick = {
                        working = true; status = "در حال اتصال به GitHub…"; steps = emptyList()
                        prefs.telegramLang = dLang; prefs.autoSend = dOn
                        scope.launch {
                            val res = ir.kavir.ordernotebook.net.RepoSetup(ctx, prefs).run(botToken, chatId)
                            steps = res
                            val ok = res.isNotEmpty() && res.all { it.ok } && res.any { it.title.contains("Secrets") }
                            status = if (ok) "✓ اتصال کامل شد" else "✗ اتصال ناموفق بود"
                            if (ok) { botToken = ""; chatId = ""; repo.enqueueConfig() } // secrets are not kept on the phone
                            working = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره و اتصال") }
                    OutlinedButton(enabled = !working && prefs.ghConfigured, onClick = {
                        working = true; status = "در حال ارسال درخواست…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                try {
                                    val api = GitHub(prefs)
                                    api.dispatch(ir.kavir.ordernotebook.net.RepoSetup.WORKFLOW_FILE, api.info().defaultBranch, mapOf("test" to "true"))
                                    "✓ درخواست ثبت شد؛ تا حدود یک دقیقه‌ی دیگر پیام آزمایشی در تلگرام می‌آید. اگر نیامد، تب Actions مخزن را ببینید."
                                } catch (e: GitHub.ApiException) { "✗ ${e.message}" }
                                catch (e: IOException) { "✗ خطای اتصال: ${e.message}" }
                            }
                            working = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Test Connection") }
                    OutlinedButton(enabled = !working && prefs.ghConfigured, onClick = {
                        working = true; status = "در حال خواندن وضعیت…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                try {
                                    val r = GitHub(prefs).latestRun(ir.kavir.ordernotebook.net.RepoSetup.WORKFLOW_FILE)
                                    if (r == null) "هنوز اجرایی ثبت نشده است"
                                    else "آخرین اجرا: " + when {
                                        r.status != "completed" -> "در حال اجرا (${r.status})"
                                        r.conclusion == "success" -> "✓ موفق"
                                        else -> "✗ ${r.conclusion ?: "ناموفق"} — جزئیات: ${r.url}"
                                    }
                                } catch (e: GitHub.ApiException) { "✗ ${e.message}" }
                                catch (e: IOException) { "✗ خطای اتصال: ${e.message}" }
                            }
                            working = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("وضعیت آخرین اجرای Action") }
                    if (!prefs.ghConfigured) Text("ابتدا اتصال GitHub را تنظیم کنید.", style = MaterialTheme.typography.bodySmall)
                    steps.forEach { Text((if (it.ok) "✓ " else "✗ ") + it.title + (if (it.detail.isNotBlank()) " — ${it.detail}" else ""), style = MaterialTheme.typography.bodySmall) }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    prefs.telegramLang = dLang; prefs.autoSend = dOn
                    showTg = false
                    scope.launch { repo.enqueueConfig(); snack.showSnackbar("تنظیمات تلگرام ذخیره شد") }
                }) { Text("Save Settings") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { showTg = false }) { Text("بستن") } },
        )
    }

    // ---------------------------------------------------------------- Telegram message template dialog
    if (showTpl) {
        val tt = ir.kavir.ordernotebook.util.TelegramTemplate
        var tv by remember { mutableStateOf(prefs.telegramTemplate.ifBlank { tt.DEFAULT_TEXT }.let { androidx.compose.ui.text.input.TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) }) }
        val text = tv.text
        var preview by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTpl = false },
            title = { Text("قالب پیام تلگرام") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!preview) {
                        TemplateEditor("متن پیام (فارسی، انگلیسی، عدد، ایموجی، چند خط)", tv, { tv = it }, tt.PLACEHOLDERS)
                        Text("متغیر خالی یا ناشناخته در پیام نهایی نمایش داده نمی‌شود. با ذخیره، پیام‌های قبلی سفارش‌ها هم با همین قالب ویرایش می‌شوند.", style = MaterialTheme.typography.bodySmall)
                    } else Text(tt.render(text, tt.sample()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.telegramTemplate = if (text.isBlank() || text == tt.DEFAULT_TEXT) "" else text
                    showTpl = false
                    scope.launch { repo.enqueueConfig(); snack.showSnackbar("قالب پیام تلگرام ذخیره شد") }
                }) { Text("ذخیره") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "ویرایش" else "پیش‌نمایش") }
                    TextButton(onClick = { tv = androidx.compose.ui.text.input.TextFieldValue(tt.DEFAULT_TEXT, androidx.compose.ui.text.TextRange(tt.DEFAULT_TEXT.length)) }) { Text("بازگردانی پیش‌فرض") }
                }
            },
        )
    }

    // ---------------------------------------------------------------- Edit order message dialog
    if (showForm) {
        var tv by remember { mutableStateOf(prefs.formTemplate.let { androidx.compose.ui.text.input.TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) }) }
        val text = tv.text
        var preview by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showForm = false },
            title = { Text("ویرایش متن سفارش") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!preview) {
                        TemplateEditor("متنی که برای مشتری فرستاده می‌شود", tv, { tv = it }, ir.kavir.ordernotebook.util.FormTemplate.PLACEHOLDERS)
                    } else {
                        Text(ir.kavir.ordernotebook.util.FormTemplate.render(text, ir.kavir.ordernotebook.util.FormTemplate.sample()))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.formTemplate = text.ifBlank { ir.kavir.ordernotebook.util.FormTemplate.DEFAULT }
                    showForm = false
                    scope.launch { snack.showSnackbar("متن سفارش ذخیره شد") }
                }) { Text("ذخیره") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "ویرایش" else "پیش‌نمایش") }
                    TextButton(onClick = { val d = ir.kavir.ordernotebook.util.FormTemplate.DEFAULT; tv = androidx.compose.ui.text.input.TextFieldValue(d, androidx.compose.ui.text.TextRange(d.length)) }) { Text("بازگردانی پیش‌فرض") }
                }
            },
        )
    }

    // ---------------------------------------------------------------- GitHub restore confirm
    remoteInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { remoteInfo = null },
            title = { Text("بازیابی از GitHub") },
            text = {
                Text(
                    "در مخزن ${Fmt.toPersianDigits(info.orders.toString())} سفارش پیدا شد.\n" +
                        "آخرین بکاپ روی GitHub: ${info.lastBackup?.replace('T', ' ')?.removeSuffix("Z") ?: "—"}\n\n" +
                        "اطلاعات GitHub با اطلاعات فعلی ادغام می‌شود: سفارش‌های جدیدتر جایگزین نسخه‌ی قدیمی‌تر می‌شوند و هیچ داده‌ی محلی حذف نمی‌شود."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    remoteInfo = null
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) { repo.sync.restoreFromGitHub() }
                        busy = false
                        lastSync = prefs.ghLastSync
                        snack.showSnackbar(r.fold({ "${Fmt.toPersianDigits(it.toString())} سفارش بازیابی/ادغام شد" }, { "بازیابی ناموفق: ${it.message}" }))
                    }
                }) { Text("بازیابی") }
            },
            dismissButton = { TextButton(onClick = { remoteInfo = null }) { Text("انصراف") } },
        )
    }

    // ---------------------------------------------------------------- ZIP import confirm
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
                            ghRepo = prefs.ghRepo
                            snack.showSnackbar("بازیابی کامل شد")
                        }, { snack.showSnackbar("بازیابی ناموفق: ${it.message}") })
                    }
                }) { Text("جایگزین کن") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("انصراف") } },
        )
    }
}

/** Verifies the saved GitHub settings and reports whether the repository is private. */
private suspend fun testGitHub(prefs: ir.kavir.ordernotebook.data.Prefs): String = withContext(Dispatchers.IO) {
    try {
        val i = GitHub(prefs).info()
        buildString {
            append("✓ متصل به ${i.fullName}\n")
            append(if (i.isPrivate) "مخزن Private است ✓" else "⚠ مخزن Public است؛ آپلود اطلاعات مشتریان انجام نخواهد شد. مخزن را Private کنید.")
            if (!i.canPush) append("\n⚠ توکن اجازه‌ی نوشتن ندارد (Contents: Read and write)")
        }
    } catch (e: GitHub.ApiException) {
        "✗ ${e.message}"
    } catch (e: IOException) {
        "✗ خطای اتصال: ${e.message}"
    }
}


/** Multiline editor + placeholder chips. A chip inserts at the caret (or over the selection) and keeps the field focused. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditor(label: String, value: androidx.compose.ui.text.input.TextFieldValue, onValue: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, placeholders: List<Pair<String, String>>) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus), minLines = 8, shape = RoundedCornerShape(16.dp),
    )
    Text("متغیرها (برای افزودن در محل مکان‌نما بزنید):", style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        placeholders.forEach { (k, d) ->
            AssistChip(onClick = {
                onValue(ir.kavir.ordernotebook.util.PlaceholderInsert.insert(value, "{$k}"))
                focus.requestFocus() // selection/caret live in the TextFieldValue, so the caret lands right after the inserted text
            }, label = { Text("{$k} · $d", style = MaterialTheme.typography.labelSmall) })
        }
    }
}
