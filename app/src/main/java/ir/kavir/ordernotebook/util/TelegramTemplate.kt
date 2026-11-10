package ir.kavir.ordernotebook.util

/**
 * Editable Telegram message. The GitHub Action renders the saved template with the same placeholders
 * (unknown or empty ones disappear, emojis are never touched). An empty template means "built-in default format".
 */
object TelegramTemplate {
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        "order_id" to "شماره/شناسه سفارش", "name" to "نام مشتری", "id" to "آیدی تلگرام/دیسکورد", "customer_name" to "نام مشتری", "items" to "آیتم‌ها با قیمت",
        "total_price" to "هزینه کل", "status" to "وضعیت سفارش و پرداخت", "created_at" to "تاریخ ثبت",
        "description" to "توضیحات", "notes" to "نکات سفارش", "quantity" to "تعداد", "phone" to "شماره تماس",
        "address" to "آدرس", "customer_id" to "آیدی مشتری", "postal_code" to "کد پستی", "invoice" to "شماره فاکتور",
        "delivery" to "زمان تحویل", "items_total" to "جمع آیتم‌ها", "deposit" to "بیعانه", "remaining" to "باقیمانده",
    )

    /** Starting point shown in the editor; it mirrors the built-in format. */
    const val DEFAULT_TEXT = "سفارش ثبت شد:\n\n(شناسه سفارش): #{order_id}\n(نام مشتری): {customer_name}\n(شماره تماس): {phone}\n" +
        "(شماره فاکتور): {invoice}\n(تاریخ): {created_at}\n(زمان تحویل): {delivery}\n(آدرس): {address}\n\n(سفارش‌ها):\n{items}\n\n" +
        "(توضیحات): {description}\n(نکات سفارش): {notes}\n\n(جمع آیتم‌ها): {items_total}\n(هزینه کل): {total_price}\n" +
        "(بیعانه): {deposit}\n(باقیمانده): {remaining}\n\n(وضعیت): {status}"

    fun sample(): Map<String, String> = mapOf(
        "order_id" to "12", "name" to "علی رضایی 🌹", "id" to "@ali_r", "customer_name" to "علی رضایی 🌹", "items" to "- 🕯️ شمع گلی ×5 — 250,000 تومان\n- ❄️ شمع یخی ×2 — 80,000 تومان",
        "total_price" to "330,000 تومان", "status" to "ثبت شده / پرداخت نشده", "created_at" to Fmt.todayJalali(),
        "description" to "تحویل صبح", "notes" to "", "quantity" to "7", "phone" to "09121234567", "address" to "تهران",
        "customer_id" to "@ali_r", "postal_code" to "1234567890", "invoice" to "12", "delivery" to "", "items_total" to "330,000 تومان",
        "deposit" to "100,000 تومان", "remaining" to "230,000 تومان",
    )

    private val re = Regex("\\{([a-z_]+)\\}")

    fun render(template: String, values: Map<String, String>): String =
        re.replace(template) { values[it.groupValues[1]].orEmpty() }.replace(Regex("\n{3,}"), "\n\n").trim()
}
