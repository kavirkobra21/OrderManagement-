package ir.kavir.ordernotebook.util

data class PasteItem(val name: String, val qty: Int = 1)

data class PasteDraft(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val contactId: String = "",
    val postalCode: String = "",
    val quantity: Int = 1,
    val notes: String = "",
    val items: List<PasteItem> = emptyList(),
)

/**
 * Parses the customer's filled-in order form (see [TEMPLATE]) into a draft. Tolerant on purpose:
 * label spelling/spacing/ZWNJ/Arabic letters, "1." "۱-" "-" numbering, multi-line address/notes,
 * missing fields (left empty, never an error) and a missing items label (numbered lines become items).
 * Prices are never read; the user enters them afterwards.
 */
object OrderPaste {
    const val TEMPLATE = FormTemplate.DEFAULT

    private enum class Key { NAME, PHONE, ADDRESS, NOTES, ITEMS, ID, POSTAL, QTY }

    private const val SEP = "[:：﹕]"
    private const val ZW = "\\u200c\\s"
    private val labels: List<Pair<Key, Regex>> = listOf(
        Key.NOTES to "نکات(?:[$ZW]*سفارش)?|توضیحات(?:[$ZW]*سفارش)?|توضیح|یادداشت",
        Key.ADDRESS to "آدرس|نشانی",
        Key.ITEMS to "آیتم[$ZW]*ها|آیتم|اقلام|سفارش[$ZW]*ها|سفارشات|محصولات|لیست[$ZW]*سفارش",
        Key.POSTAL to "کد[$ZW]*پستی|کدپستی|پستی|[Pp]ostal[$ZW]*[Cc]ode",
        Key.QTY to "تعداد(?:[$ZW]*سفارش)?",
        Key.ID to "آی[$ZW]*دی(?:[$ZW]*تلگرام|[$ZW]*دیسکورد)?|ایدی|[Ii][Dd]|تلگرام|دیسکورد|[Tt]elegram|[Dd]iscord",
        Key.PHONE to "شماره[$ZW]*تماس|شماره[$ZW]*تلفن|شماره[$ZW]*موبایل|شماره[$ZW]*همراه|تلفن[$ZW]*همراه|تلفن|موبایل|همراه|تماس|شماره",
        Key.NAME to "نام[$ZW]*و[$ZW]*نام[$ZW]*خانوادگی|نام(?:[$ZW]*مشتری)?|اسم(?:[$ZW]*مشتری)?|مشتری",
    ).map { (k, p) -> k to Regex("^\\s*(?:[-•*▪●]\\s*)?(?:$p)\\s*$SEP\\s*(.*)$") }

    private val numbering = Regex("^\\s*(?:[0-9۰-۹٠-٩]+\\s*[.\\-)\\]/:：،]|[-•*–—▪●○]+)\\s*")
    private val startsNumbered = Regex("^\\s*[0-9۰-۹٠-٩]+\\s*[.\\-)\\]/:：،]")
    private val qtyX = Regex("^(.*?)\\s*[x×*]\\s*([0-9۰-۹]{1,3})$", RegexOption.IGNORE_CASE)
    private val qtyWord = Regex("^(.*?)\\s+([0-9۰-۹]{1,3})\\s*(?:عدد|تا|بسته|جفت)$")
    private val mobile = Regex("(?:\\+98|0098|98)?0?(9\\d{9})")

    private fun clean(raw: String): String = raw
        .replace("\r", "")
        .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
        .replace('ي', 'ی').replace('ك', 'ک')

    fun parse(raw: String): PasteDraft {
        val text = clean(raw)
        val buf = mutableMapOf<Key, MutableList<String>>()
        val unlabeledNumbered = mutableListOf<String>()
        var cur: Key? = null
        for (line in text.split("\n")) {
            val hit = labels.firstNotNullOfOrNull { (k, re) -> re.matchEntire(line)?.let { k to it.groupValues[1].trim() } }
            if (hit != null) {
                cur = hit.first
                val list = buf.getOrPut(cur) { mutableListOf() }
                if (hit.second.isNotEmpty()) list.add(hit.second)
                continue
            }
            if (line.isBlank()) continue
            // name and phone are single-line fields; only address / notes / items continue on the next lines
            if (cur != null && cur != Key.NAME && cur != Key.PHONE && cur != Key.ID && cur != Key.POSTAL && cur != Key.QTY) buf.getOrPut(cur) { mutableListOf() }.add(line.trim())
            else if (startsNumbered.containsMatchIn(line)) unlabeledNumbered.add(line)
        }

        val itemLines = buf[Key.ITEMS] ?: unlabeledNumbered
        val items = itemLines
            .flatMap { it.split(Regex("[،;؛,]")) }
            .mapNotNull(::parseItem)

        val phoneSource = buf[Key.PHONE]?.joinToString(" ").orEmpty()
        val phone = cleanPhone(phoneSource).ifBlank { mobile.find(Fmt.toLatinDigits(text).replace(Regex("[\\s\\-()]"), ""))?.let { "0" + it.groupValues[1] }.orEmpty() }

        return PasteDraft(
            name = buf[Key.NAME]?.joinToString(" ").orEmpty().trim(),
            phone = phone,
            contactId = buf[Key.ID]?.joinToString(" ").orEmpty().trim(),
            postalCode = Fmt.toLatinDigits(buf[Key.POSTAL]?.joinToString("").orEmpty()).filter { it.isDigit() }.take(10),
            quantity = Fmt.toLatinDigits(buf[Key.QTY]?.joinToString(" ").orEmpty()).filter { it.isDigit() }.take(4).toIntOrNull()?.coerceAtLeast(1) ?: 1,
            address = buf[Key.ADDRESS]?.joinToString(" ").orEmpty().trim(),
            notes = buf[Key.NOTES]?.joinToString("\n").orEmpty().trim(),
            items = items,
        )
    }

    private fun cleanPhone(s: String): String {
        if (s.isBlank()) return ""
        val d = Fmt.toLatinDigits(s).replace(Regex("[\\s\\-()]"), "")
        mobile.find(d)?.let { return "0" + it.groupValues[1] }
        return d.filter { it.isDigit() || it == '+' }
    }

    private fun parseItem(line: String): PasteItem? {
        var t = numbering.replaceFirst(line, "").trim()
        if (t.isEmpty()) return null
        var qty = 1
        val m = qtyX.matchEntire(t) ?: qtyWord.matchEntire(t)
        if (m != null && m.groupValues[1].isNotBlank()) {
            qty = Fmt.toLatinDigits(m.groupValues[2]).toIntOrNull() ?: 1
            t = m.groupValues[1].trim()
        }
        return if (t.isEmpty()) null else PasteItem(t, qty.coerceIn(1, 999))
    }
}
