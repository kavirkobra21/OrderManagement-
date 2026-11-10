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
fun SettingsScreen(repo: Repo, nav: Nav, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = repo.prefs
    val updater = remember { Updater(ctx, prefs) }

    // ---- Telegram
    var showTg by remember { mutableStateOf(false) }

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
                    "متن سفارش و همه‌ی عکس‌ها فقط توسط GitHub Actions به تلگرام ارسال می‌شوند. Bot Token و Chat ID در GitHub Secrets " +
                        "نگهداری می‌شوند (BOT_TOKEN و CHAT_ID) و داخل برنامه ذخیره نمی‌شوند.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { showTg = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Telegram Settings") }
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
        var status by remember { mutableStateOf("") }
        var testing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTg = false },
            title = { Text("Telegram Settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("زبان پیام‌ها")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(dLang == "fa", { dLang = "fa" }); Text("فارسی")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(dLang == "en", { dLang = "en" }); Text("English")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(dOn, { dOn = it }); Spacer(Modifier.width(8.dp)); Text("ارسال خودکار سفارش‌ها")
                    }
                    Text(
                        "برای فعال شدن: در GitHub → Settings → Secrets and variables → Actions دو Secret بسازید: " +
                            "BOT_TOKEN (توکن ربات) و CHAT_ID. ربات را در تلگرام Start کنید.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(enabled = !testing && prefs.ghConfigured, onClick = {
                        testing = true; status = "در حال ارسال درخواست…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                try {
                                    val api = GitHub(prefs)
                                    api.dispatch("telegram.yml", api.info().defaultBranch, mapOf("test" to "true"))
                                    "✓ درخواست ثبت شد؛ تا حدود یک دقیقه‌ی دیگر پیام آزمایشی در تلگرام می‌آید. اگر نیامد، تب Actions مخزن را ببینید."
                                } catch (e: GitHub.ApiException) { "✗ ${e.message}" }
                                catch (e: IOException) { "✗ خطای اتصال: ${e.message}" }
                            }
                            testing = false
                        }
                    }) { Text("Test Connection") }
                    if (!prefs.ghConfigured) Text("ابتدا اتصال GitHub را تنظیم کنید.", style = MaterialTheme.typography.bodySmall)
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.telegramLang = dLang; prefs.autoSend = dOn
                    showTg = false
                    scope.launch { repo.enqueueConfig(); snack.showSnackbar("تنظیمات تلگرام ذخیره شد") }
                }) { Text("Save Settings") }
            },
            dismissButton = { TextButton(onClick = { showTg = false }) { Text("انصراف") } },
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
