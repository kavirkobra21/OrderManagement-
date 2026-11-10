package ir.kavir.ordernotebook.net

import android.util.Base64
import ir.kavir.ordernotebook.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pure helpers (no Android dependencies, unit-testable). */
object GitHubApi {
    private val urlRegex =
        Regex("""^(?:https?://)?(?:www\.)?(?:github\.com[/:])?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?$""")
    private val plainRegex = Regex("""[\w.-]+/[\w.-]+""")

    /** "https://github.com/owner/repo(.git)" or "owner/repo" -> "owner/repo" */
    fun parseRepo(input: String): String {
        val s = input.trim()
        val m = urlRegex.matchEntire(s) ?: return s
        return m.groupValues[1] + "/" + m.groupValues[2]
    }

    fun isValidRepo(s: String) = plainRegex.matches(s)

    /** Git blob SHA-1 of the content: equals the "sha" GitHub reports for a file, so unchanged files are not re-uploaded. */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Thin client for the GitHub REST "contents" API. Network failures surface as IOException. */
class GitHub(private val prefs: Prefs) {

    class ApiException(val code: Int, message: String) : Exception(message)
    data class Entry(val name: String, val path: String, val type: String, val sha: String)
    data class RepoInfo(val fullName: String, val isPrivate: Boolean, val canPush: Boolean, val defaultBranch: String)

    private val json = "application/vnd.github+json"
    private val raw = "application/vnd.github.raw+json"

    private fun url(path: String) = "https://api.github.com/repos/${prefs.ghRepo}/$path"

    private fun exec(method: String, target: String, body: ByteArray? = null, accept: String = json): Pair<Int, ByteArray> {
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "OrderNotebook")
            if (prefs.ghToken.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${prefs.ghToken}")
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val bytes = (if (code in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            c.disconnect()
        }
    }

    private fun err(code: Int, body: ByteArray): ApiException {
        val msg = try { JSONObject(String(body, Charsets.UTF_8)).optString("message") } catch (e: Exception) { "" }
        val text = when {
            msg.contains("workflow", true) && (code == 403 || code == 404 || code == 422) ->
                "توکن اجازه‌ی ساخت/ویرایش فایل‌های Workflow را ندارد (دسترسی Workflows: Read and write لازم است)"
            else -> when (code) {
            401 -> "توکن GitHub نامعتبر یا منقضی است"
            403 -> if (msg.contains("rate limit", true)) "محدودیت تعداد درخواست GitHub؛ بعداً دوباره تلاش می‌شود"
            else "دسترسی رد شد (توکن باید دسترسی Contents: Read and write داشته باشد)"
            404 -> "مخزن یا فایل پیدا نشد (آدرس مخزن و دسترسی توکن را بررسی کنید)"
            else -> "خطای GitHub ($code): $msg"
        } }
        return ApiException(code, text)
    }

    fun info(): RepoInfo {
        val (code, body) = exec("GET", "https://api.github.com/repos/${prefs.ghRepo}")
        if (code != 200) throw err(code, body)
        val j = JSONObject(String(body, Charsets.UTF_8))
        val perms = j.optJSONObject("permissions")
        return RepoInfo(j.optString("full_name"), j.optBoolean("private"), perms?.optBoolean("push") ?: false, j.optString("default_branch", "main"))
    }

    /** Lists a directory; a missing directory is an empty list. */
    fun list(path: String): List<Entry> {
        val (code, body) = exec("GET", url("contents/$path"))
        if (code == 404) return emptyList()
        if (code != 200) throw err(code, body)
        val text = String(body, Charsets.UTF_8).trim()
        if (!text.startsWith("[")) return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("name"), o.optString("path"), o.optString("type"), o.optString("sha"))
        }
    }

    fun getRaw(path: String): ByteArray? {
        val (code, body) = exec("GET", url("contents/$path"), accept = raw)
        if (code == 404) return null
        if (code != 200) throw err(code, body)
        return body
    }

    fun getText(path: String): String? = getRaw(path)?.toString(Charsets.UTF_8)

    fun sha(path: String): String? {
        val (code, body) = exec("GET", url("contents/$path"))
        if (code == 404) return null
        if (code != 200) throw err(code, body)
        val t = String(body, Charsets.UTF_8).trim()
        return if (t.startsWith("{")) JSONObject(t).optString("sha").ifBlank { null } else null
    }

    /** Creates or updates a file. On a sha conflict (409/422) the current sha is fetched and the write retried. */
    fun put(path: String, bytes: ByteArray, message: String, knownSha: String?): String {
        var sha = knownSha
        for (attempt in 0 until 3) {
            val b = JSONObject().put("message", message).put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (sha != null) b.put("sha", sha)
            val (code, resp) = exec("PUT", url("contents/$path"), b.toString().toByteArray(Charsets.UTF_8))
            if (code == 200 || code == 201) return JSONObject(String(resp, Charsets.UTF_8)).getJSONObject("content").getString("sha")
            if ((code == 409 || code == 422) && attempt < 2) { sha = sha(path); continue }
            throw err(code, resp)
        }
        throw ApiException(409, "تعارض در ذخیره‌ی فایل روی GitHub")
    }

    fun delete(path: String, sha: String, message: String) {
        val b = JSONObject().put("message", message).put("sha", sha)
        val (code, resp) = exec("DELETE", url("contents/$path"), b.toString().toByteArray(Charsets.UTF_8))
        if (code != 200 && code != 404) throw err(code, resp)
    }

    /** Starts a workflow_dispatch run (needs a token with Actions: Read and write). */
    fun dispatch(workflowFile: String, ref: String, inputs: Map<String, String>) {
        val inp = JSONObject()
        inputs.forEach { (k, v) -> inp.put(k, v) }
        val b = JSONObject().put("ref", ref).put("inputs", inp)
        val (code, resp) = exec("POST", url("actions/workflows/$workflowFile/dispatches"), b.toString().toByteArray(Charsets.UTF_8))
        if (code == 204) return
        if (code == 403 || code == 404) throw ApiException(code, "توکن اجازه‌ی اجرای Actions را ندارد (Actions: Read and write) یا workflow پیدا نشد")
        throw err(code, resp)
    }

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String)

    /** Newest run of a workflow (any trigger) or null. Needs Actions: Read. */
    fun latestRun(workflowFile: String): Run? {
        val (code, body) = exec("GET", url("actions/workflows/$workflowFile/runs?per_page=1"))
        if (code == 404) return null
        if (code == 403) throw ApiException(code, "توکن دسترسی خواندن Actions را ندارد (Actions: Read)")
        if (code != 200) throw err(code, body)
        val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return Run(r.optLong("id"), r.optString("status"), r.optString("conclusion").ifBlank { null }, r.optString("html_url"))
    }

    /** Creates the (private, empty) repository named in prefs under the token owner's account. Needs a token allowed to create repos. */
    fun createPrivateRepo() {
        val name = prefs.ghRepo.substringAfter('/')
        val b = JSONObject().put("name", name).put("private", true).put("description", "Order Notebook data")
        val (code, resp) = exec("POST", "https://api.github.com/user/repos", b.toString().toByteArray(Charsets.UTF_8))
        if (code == 201) return
        if (code == 403 || code == 404 || code == 422) throw ApiException(code,
            "ساخت خودکار مخزن ممکن نشد (${JSONObject(String(resp, Charsets.UTF_8)).optString("message")}). مخزن Private را در GitHub بسازید و نامش را اینجا بدهید.")
        throw err(code, resp)
    }

    /** Repository public key used to encrypt Actions secrets: (key_id, 32-byte key). */
    fun actionsPublicKey(): Pair<String, ByteArray> {
        val (code, body) = exec("GET", url("actions/secrets/public-key"))
        if (code == 403 || code == 404) throw ApiException(code, "توکن دسترسی Secrets را ندارد (Secrets: Read and write) یا مخزن پیدا نشد")
        if (code != 200) throw err(code, body)
        val j = JSONObject(String(body, Charsets.UTF_8))
        return j.getString("key_id") to Base64.decode(j.getString("key"), Base64.DEFAULT)
    }

    /** Creates or replaces an Actions secret. The value is encrypted on the phone and never logged. */
    fun putSecret(name: String, value: String) {
        val (keyId, key) = actionsPublicKey()
        val enc = Base64.encodeToString(SealedBox.seal(value.toByteArray(Charsets.UTF_8), key), Base64.NO_WRAP)
        val b = JSONObject().put("encrypted_value", enc).put("key_id", keyId)
        val (code, resp) = exec("PUT", url("actions/secrets/$name"), b.toString().toByteArray(Charsets.UTF_8))
        if (code == 201 || code == 204) return
        if (code == 403 || code == 404) throw ApiException(code, "توکن دسترسی Secrets را ندارد (Secrets: Read and write)")
        throw err(code, resp)
    }

    /** Names (never values) of the repository's Actions secrets. */
    fun secretNames(): Set<String> {
        val (code, body) = exec("GET", url("actions/secrets?per_page=100"))
        if (code != 200) throw err(code, body)
        val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("secrets") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.toSet()
    }

    /** ISO date of the newest commit touching [path], or null. */
    fun lastCommitDate(path: String): String? {
        val (code, body) = exec("GET", url("commits?path=$path&per_page=1"))
        if (code == 404 || code == 409) return null
        if (code != 200) throw err(code, body)
        val arr = JSONArray(String(body, Charsets.UTF_8))
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).optJSONObject("commit")?.optJSONObject("committer")?.optString("date")
    }
}
