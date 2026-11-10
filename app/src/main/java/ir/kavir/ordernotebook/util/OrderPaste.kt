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
        Key.NOTES to "[Nn]otes?|نکات(?:[$ZW]*سفارش)?|توضیحات(?:[$ZW]*سفارش)?|توضیح|یادداشت",
        Key.ADDRESS to "آدرس(?:[$ZW]*دقیق)?|نشانی|[Aa]ddress",
        Key.ITEMS to "[Ii]tems?|[Pp]roducts?|آیتم[$ZW]*ها|آیتم|اقلام|سفارش[$ZW]*ها|سفارشات|محصولات|لیست[$ZW]*سفارش",
        Key.POSTAL to "کد[$ZW]*پستی|کدپستی|پستی|[Pp]ostal[$ZW]*[Cc]ode",
        Key.QTY to "تعداد(?:[$ZW]*سفارش)?|[Qq]uantity|[Qq]ty",
        Key.ID to "آی[$ZW]*دی(?:[$ZW]*تلگرام|[$ZW]*دیسکورد)?|ایدی(?:[$ZW]*تلگرام|[$ZW]*دیسکورد)?|[Ii][Dd]|تلگرام|دیسکورد|[Tt]elegram|[Dd]iscord",
        Key.PHONE to "[Pp]hone(?:[$ZW]*[Nn]umber)?|[Mm]obile|شماره[$ZW]*تماس|شماره[$ZW]*تلفن|شماره[$ZW]*موبایل|شماره[$ZW]*همراه|تلفن[$ZW]*همراه|تلفن|موبایل|همراه|تماس|شماره",
        Key.NAME to "[Cc]ustomer[$ZW]*[Nn]ame|[Nn]ame|نام[$ZW]*و[$ZW]*نام[$ZW]*خانوادگی|نام(?:[$ZW]*مشتری)?|اسم(?:[$ZW]*مشتری)?|مشتری",
    ).map { (k, p) -> k to Regex("^[^\\p{L}\\p{N}\\n]*(?:$p)\\s*$SEP\\s*(.*)$") }

    private val numbering = Regex("^\\s*(?:[0-9۰-۹٠-٩]+\\s*[.\\-)\\]/:：،]|[-•*–—▪●○]+)\\s*")
    private val startsNumbered = Regex("^\\s*[0-9۰-۹٠-٩]+\\s*[.\\-)\\]/:：،]")
    private val qtyX = Regex("^(.*?)\\s*[x×*]\\s*([0-9۰-۹٠-٩]{1,3})$", RegexOption.IGNORE_CASE)
    private val qtyWord = Regex("^(.*?)\\s+([0-9۰-۹٠-٩]{1,3})\\s*(?:عدد|تا|بسته|جفت)$")
    /** "5 شمع گلی", "5x شمع", "5 عدد شمع": quantity BEFORE the product name (needs whitespace after the number) */
    private val qtyLead = Regex("^([0-9۰-۹٠-٩]{1,3})\\s*(?:عدد|تا|[x×*])?\\s+(.+)$", RegexOption.IGNORE_CASE)
    /** "شمع گلی 5", "Flower Candle 7": bare trailing number, only when it is the single number of the line (see parseItem) */
    /** decorative divider lines such as ────── or ====== are never products */
    private val separatorOnly = Regex("^[─━—–=_*~•.\\-\\s]+$")
    private val qtyBare = Regex("^(.*\\p{L}.*?)\\s+([0-9۰-۹٠-٩]{1,2})$")
    private val anyDigit = Regex("[0-9۰-۹٠-٩]")
    private val mobile = Regex("(?:\\+98|0098|98)?0?(9\\d{9})")

    private fun clean(raw: String): String = raw
        .replace("\r", "")
        .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
        .replace('ي', 'ی').replace('ك', 'ک')

    /** A line of the items block: either an item text or a "Quantity: n" line that belongs to the previous item. */
    private sealed interface Entry { data class Line(val text: String) : Entry; data class Qty(val n: Int) : Entry }

    // "Quantity: 2", "Qty 2", "تعداد: ۲", "تعداد ۲ عدد" on a line of its own
    private val qtyLine = Regex("^\\s*(?:[-•*]\\s*)?(?:quantity|qty|تعداد|عدد)\\s*[:：=\\-]?\\s*([0-9۰-۹٠-٩]{1,4})\\s*(?:عدد|تا|pcs|pieces)?\\s*$", RegexOption.IGNORE_CASE)

    private fun qtyOf(line: String): Int? =
        qtyLine.matchEntire(line)?.let { Fmt.toLatinDigits(it.groupValues[1]).toIntOrNull() }?.takeIf { it > 0 }

    private fun toEntry(line: String): Entry = qtyOf(line)?.let { Entry.Qty(it) } ?: Entry.Line(line.trim())

    fun parse(raw: String): PasteDraft {
        val text = clean(raw)
        val buf = mutableMapOf<Key, MutableList<String>>()
        val itemEntries = mutableListOf<Entry>()
        val loose = mutableListOf<String>()      // lines before any label / after single-line labels
        var anyLabel = false
        var cur: Key? = null
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            // inside the items block, a quantity line belongs to the previous item
            if (cur == Key.ITEMS) { val q = qtyOf(line); if (q != null) { itemEntries.add(Entry.Qty(q)); continue } }
            val hit = labels.firstNotNullOfOrNull { (k, re) -> re.matchEntire(line)?.let { k to it.groupValues[1].trim() } }
            // "Quantity: 2" right after an unlabeled item line is that item's quantity, not the order total
            if (hit != null && hit.first == Key.QTY && cur != Key.ITEMS && loose.isNotEmpty() && qtyOf(line) != null) { loose.add(line); continue }
            if (hit != null) {
                anyLabel = true
                cur = hit.first
                if (cur == Key.ITEMS) {
                    if (hit.second.isNotEmpty()) itemEntries.add(toEntry(hit.second))
                } else {
                    val list = buf.getOrPut(cur) { mutableListOf() }
                    if (hit.second.isNotEmpty()) list.add(hit.second)
                }
                continue
            }
            when {
                cur == Key.ITEMS -> itemEntries.add(Entry.Line(line.trim()))
                // name, phone, id, postal code and quantity are single-line fields
                cur == null || cur == Key.NAME || cur == Key.PHONE || cur == Key.ID || cur == Key.POSTAL || cur == Key.QTY -> loose.add(line)
                else -> buf.getOrPut(cur) { mutableListOf() }.add(line.trim())   // address / notes continue
            }
        }

        val entries: List<Entry> = when {
            buf.containsKey(Key.ITEMS) || itemEntries.isNotEmpty() -> itemEntries
            else -> {
                val numbered = mutableListOf<Entry>()
                var prevNumbered = false
                for (l in loose) {
                    val q = qtyOf(l)
                    if (q != null) { if (prevNumbered) numbered.add(Entry.Qty(q)); continue }
                    prevNumbered = startsNumbered.containsMatchIn(l)
                    if (prevNumbered) numbered.add(Entry.Line(l))
                }
                // plain "Ice Candle / Quantity: 2" pasted without any labels or numbering
                val plain = loose.any { qtyOf(it) != null }
                if (numbered.isNotEmpty()) numbered else if (!anyLabel || plain) loose.map(::toEntry) else emptyList()
            }
        }

        val items = mutableListOf<PasteItem>()
        for (e in entries) when (e) {
            is Entry.Line -> e.text.split(Regex("[،;؛,]")).mapNotNull(::parseItem).forEach { items.add(it) }
            is Entry.Qty -> if (items.isNotEmpty()) items[items.lastIndex] = items.last().copy(qty = e.n.coerceIn(1, 9999))
        }

        val phoneSource = buf[Key.PHONE]?.joinToString(" ").orEmpty()
        val phone = cleanPhone(phoneSource).ifBlank { mobile.find(Fmt.toLatinDigits(text).replace(Regex("[\\s\\-()]"), ""))?.let { "0" + it.groupValues[1] }.orEmpty() }
        val explicitQty = Fmt.toLatinDigits(buf[Key.QTY]?.joinToString(" ").orEmpty()).filter { it.isDigit() }.take(4).toIntOrNull()?.takeIf { it > 0 }

        return PasteDraft(
            name = buf[Key.NAME]?.joinToString(" ").orEmpty().trim(),
            phone = phone,
            contactId = buf[Key.ID]?.joinToString(" ").orEmpty().trim(),
            postalCode = Fmt.toLatinDigits(buf[Key.POSTAL]?.joinToString("").orEmpty()).filter { it.isDigit() }.take(10),
            quantity = explicitQty ?: items.sumOf { it.qty }.coerceAtLeast(1),
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
        if (t.isEmpty() || separatorOnly.matches(t)) return null
        var qty = 1
        val m = qtyX.matchEntire(t) ?: qtyWord.matchEntire(t)
        val lead = qtyLead.matchEntire(t)
        val trailing = m != null && m.groupValues[1].isNotBlank()
        when {
            // a number at both ends is ambiguous: never guess, keep the text as written (quantity 1)
            trailing && lead != null -> Unit
            trailing -> { qty = Fmt.toLatinDigits(m!!.groupValues[2]).toIntOrNull() ?: 1; t = m.groupValues[1].trim() }
            lead != null -> { qty = Fmt.toLatinDigits(lead.groupValues[1]).toIntOrNull() ?: 1; t = lead.groupValues[2].trim() }
            else -> {
                // no x / عدد marker: a 1-2 digit number at the very end counts only if the name has no other digit
                // (so "iPhone 15 Pro" or "شمع ۲۰۰ گرمی" stay untouched; 3+ digits look like prices / ids and are never quantities)
                val b = qtyBare.matchEntire(t)
                if (b != null && !anyDigit.containsMatchIn(b.groupValues[1])) {
                    val n = Fmt.toLatinDigits(b.groupValues[2]).toIntOrNull() ?: 0
                    if (n in 1..99) { qty = n; t = b.groupValues[1].trim() }
                }
            }
        }
        return if (t.isEmpty()) null else PasteItem(t, qty.coerceIn(1, 999))
    }
}
