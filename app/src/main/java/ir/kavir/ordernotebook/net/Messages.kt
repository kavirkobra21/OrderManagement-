package ir.kavir.ordernotebook.net

import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.util.Fmt

/** Telegram message text. Prices: English digits, comma thousands, "تومان". Phone: English digits. */
object Messages {

    fun payStatus(o: Order, fa: Boolean): String = when {
        o.settled || o.paid -> if (fa) "تسویه کامل" else "Fully paid"
        o.depositReceived -> if (fa) "بیعانه دریافت شده" else "Deposit received"
        else -> if (fa) "پرداخت نشده" else "Unpaid"
    }

    fun orderStatus(o: Order, fa: Boolean): String = when {
        o.stDelivered -> if (fa) "تحویل داده شده" else "Delivered"
        o.stReady -> if (fa) "آماده شده" else "Ready"
        o.stInProgress -> if (fa) "در حال انجام" else "In progress"
        else -> if (fa) "ثبت شده" else "Registered"
    }

    fun items(o: Order): List<OrderItem> = OrderItem.decode(o.itemsText)

    /** "- شمع x1" lines */
    fun itemLines(o: Order): List<String> = items(o).map { "- ${it.title} x${it.qty}" }

    fun build(o: Order, name: String, phone: String, address: String, fa: Boolean): String {
        val ph = Fmt.toLatinDigits(phone)
        val sb = StringBuilder()
        val lines = itemLines(o)
        if (fa) {
            val m = { v: Long -> Fmt.toman(v) }
            sb.append("سفارش ثبت شد:\n\n")
            sb.append("(نام مشتری): $name\n")
            sb.append("(شماره تماس): $ph\n")
            sb.append("(شماره فاکتور): ${o.invoiceNo}\n")
            sb.append("(تاریخ): ${o.dateText}\n")
            sb.append("(زمان تحویل): ${o.deliveryText}\n")
            sb.append("(آدرس): $address\n")
            if (o.description.isNotBlank()) sb.append("\n(شرح سفارش):\n${o.description.trim()}\n")
            if (lines.isNotEmpty()) sb.append("\n(سفارش ها):\n${lines.joinToString("\n")}\n")
            if (o.notes.isNotBlank()) sb.append("\n(نکات سفارش):\n${o.notes.trim()}\n")
            sb.append("\n(هزینه کل): ${m(o.total)}\n")
            sb.append("(بیعانه): ${m(o.deposit)}\n")
            sb.append("(باقیمانده): ${m(o.remaining)}\n")
            sb.append("\n(وضعیت پرداخت): ${payStatus(o, true)}\n")
            sb.append("(وضعیت سفارش): ${orderStatus(o, true)}")
        } else {
            val m = { v: Long -> Fmt.moneyEn(v) + " Toman" }
            sb.append("WORK ORDER NOTIFICATION\n\n")
            sb.append("Customer Name: $name\n")
            sb.append("Phone Number: $ph\n")
            sb.append("Order Number: ${Fmt.toLatinDigits(o.invoiceNo)}\n")
            sb.append("Order Date: ${o.dateText}\n")
            sb.append("Delivery Date: ${o.deliveryText}\n")
            sb.append("Address: $address\n")
            if (o.description.isNotBlank()) sb.append("\nOrder Details:\n${o.description.trim()}\n")
            if (lines.isNotEmpty()) sb.append("\nItems:\n${lines.joinToString("\n")}\n")
            if (o.notes.isNotBlank()) sb.append("\nNotes:\n${o.notes.trim()}\n")
            sb.append("\nTotal Amount: ${m(o.total)}\n")
            sb.append("Deposit: ${m(o.deposit)}\n")
            sb.append("Remaining Balance: ${m(o.remaining)}\n")
            sb.append("\nPayment Status: ${payStatus(o, false)}\n")
            sb.append("Order Status: ${orderStatus(o, false)}")
        }
        return sb.toString().take(4000)
    }
}
