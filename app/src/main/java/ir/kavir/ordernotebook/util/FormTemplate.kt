package ir.kavir.ordernotebook.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The customer-facing order text. The owner edits it in Settings ("ویرایش متن سفارش"); the saved text is never
 * hard-coded in the app logic. Placeholders in curly braces are replaced with real values; missing values become empty.
 */
object FormTemplate {
    const val DEFAULT = "نام مشتری:\nآیدی:\nشماره تماس:\nکد پستی:\nآدرس:\nتعداد:\nنکات سفارش:\n\nآیتم‌ها:\n1.\n2.\n3."

    /** placeholder → description (shown in the editor) */
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        "name" to "نام مشتری", "id" to "آیدی تلگرام/دیسکورد", "phone" to "شماره تماس", "postal_code" to "کد پستی",
        "address" to "آدرس", "quantity" to "تعداد", "items" to "آیتم‌ها", "total" to "هزینه کل",
        "order_id" to "شماره سفارش", "date" to "تاریخ سفارش", "time" to "ساعت سفارش",
    )

    fun now(): Map<String, String> {
        val t = Date()
        return mapOf("date" to Fmt.todayJalali(), "time" to SimpleDateFormat("HH:mm", Locale.US).format(t))
    }

    fun sample(): Map<String, String> = now() + mapOf(
        "name" to "علی رضایی", "id" to "@ali_r", "phone" to "09121234567", "postal_code" to "1234567890",
        "address" to "تهران، خیابان آزادی", "quantity" to "2", "items" to "- طراحی لوگو — 100,000 تومان\n- طراحی بنر — 50,000 تومان",
        "total" to "150,000 تومان", "order_id" to "12",
    )

    /** Replaces {key}; unknown keys stay untouched, known keys without a value become empty (never crashes). */
    fun render(template: String, values: Map<String, String>): String =
        Regex("\\{([a-z_]+)\\}").replace(template) { m ->
            val k = m.groupValues[1]
            if (PLACEHOLDERS.any { it.first == k }) values[k].orEmpty() else m.value
        }
}
