package ir.kavir.ordernotebook.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ir.kavir.ordernotebook.data.Prefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Updater(private val ctx: Context, private val prefs: Prefs) {

    data class Release(val tag: String, val name: String, val body: String, val apiAssetUrl: String?, val size: Long)

    sealed class Check {
        data class Available(val release: Release) : Check()
        data class UpToDate(val release: Release?) : Check()
        data class Error(val msg: String) : Check()
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (prefs.ghToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${prefs.ghToken}")
        }

    fun currentVersion(): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun check(): Check {
      return try {
        val repo = prefs.updateRepo
        if (!repo.matches(Regex("[\\w.-]+/[\\w.-]+"))) return Check.Error("نام Repository باید به شکل owner/repo باشد")
        val c = open("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json")
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        when {
            code == 404 -> Check.Error("در $repo هنوز Release منتشر نشده یا توکن به این مخزن دسترسی ندارد (Contents: Read)")
            code == 401 -> Check.Error("GitHub Token نامعتبر است")
            code == 403 -> Check.Error("دسترسی رد شد یا محدودیت تعداد درخواست GitHub")
            code !in 200..299 -> Check.Error("خطای GitHub ($code)")
            else -> {
                val j = JSONObject(text)
                val assets = j.optJSONArray("assets")
                var apk: JSONObject? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a; break }
                }
                val r = Release(j.optString("tag_name"), j.optString("name"), j.optString("body"), apk?.optString("url"), apk?.optLong("size") ?: 0)
                if (r.apiAssetUrl == null) Check.Error("فایل APK در آخرین Release پیدا نشد")
                else if (isNewer(r.tag, currentVersion())) Check.Available(r) else Check.UpToDate(r)
            }
        }
      } catch (e: Exception) { Check.Error("خطای اتصال: ${e.message}") }
    }

    fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK (via the GitHub API asset URL so private repos work with a token). */
    fun download(r: Release, onProgress: (Int) -> Unit): Result<File> = try {
        val dir = File(ctx.cacheDir, "updates").also { it.deleteRecursively(); it.mkdirs() }
        val out = File(dir, "update.apk")
        val c = open(r.apiAssetUrl!!, "application/octet-stream")
        c.instanceFollowRedirects = true
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
        var done = 0L
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        onProgress(100)
        Result.success(out)
    } catch (e: Exception) { Result.failure(e) }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings() {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
