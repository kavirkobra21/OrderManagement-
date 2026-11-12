package ir.kavir.ordernotebook.util

import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem

/** Builds the Telegram text on the phone ("My Device" method). Same placeholders as the GitHub Action's template. */
object TelegramText {
    fun values(o: Order, c: Customer?): Map<String, String> {
        val items = OrderItem.decode(o.itemsText)
        val p = o.pricing
        val lines = items.joinToString("\n") {
            val line = it.price * it.qty
            "- ${it.title}" + (if (it.qty > 1) " ×${it.qty}" else "") + (if (line > 0) " — ${Fmt.toman(line)}" else "")
        }
        val itemsTotal = items.sumOf { it.price * it.qty }
        fun m(v: Long) = Fmt.toman(v)
        val status = buildList {
            add(when { o.stDelivered -> "تحویل داده شده"; o.stReady -> "آماده"; o.stInProgress -> "در حال انجام"; else -> "ثبت شده" })
            add(when { o.settled -> "تسویه کامل"; o.paid -> "پرداخت شده"; o.depositReceived || o.deposit > 0 -> "بیعانه دریافت شد"; else -> "پرداخت نشده" })
        }.joinToString(" / ")
        val pct = Pricing.percentText(o.discountPercent)
        return mapOf(
            "order_id" to (if (o.syncNo > 0) o.syncNo.toString() else o.id.toString()),
            "name" to c?.name.orEmpty(), "customer_name" to c?.name.orEmpty(),
            "id" to c?.contactId.orEmpty(), "customer_id" to c?.contactId.orEmpty(),
            "phone" to Fmt.toLatinDigits(c?.phone.orEmpty()), "address" to c?.address.orEmpty(),
            "postal_code" to Fmt.toLatinDigits(c?.postalCode.orEmpty()),
            "invoice" to Fmt.toLatinDigits(o.invoiceNo), "created_at" to Fmt.toLatinDigits(o.dateText),
            "delivery" to o.deliveryText, "description" to o.description, "notes" to o.notes,
            "quantity" to o.quantity.toString(), "items" to lines,
            "items_total" to (if (itemsTotal > 0) m(itemsTotal) else ""),
            "original_price" to m(p.original),
            "discount" to (if (p.discount > 0) m(p.discount) else ""),
            "discount_percent" to (if (o.discountPercent > 0) pct else ""),
            "discount_fixed" to (if (o.discountFixed > 0) m(o.discountFixed) else ""),
            "final_price" to m(p.final), "total_price" to m(p.final),
            "deposit" to (if (o.deposit > 0) m(o.deposit) else ""), "remaining" to m(o.remaining),
            "status" to status,
        )
    }

    private val ph = Regex("\\{([a-z_]+)\\}")

    /** custom template when set, otherwise the default format; a line whose placeholders are all empty is dropped */
    fun build(template: String, o: Order, c: Customer?): String {
        val v = values(o, c)
        val kept = template.ifBlank { TelegramTemplate.DEFAULT_TEXT }.lines().filter { line ->
            val names = ph.findAll(line).map { it.groupValues[1] }.toList()
            names.isEmpty() || names.any { !v[it].isNullOrBlank() }
        }
        return TelegramTemplate.render(kept.joinToString("\n"), v)
    }
}
