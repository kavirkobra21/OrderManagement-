package ir.kavir.ordernotebook

import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreTests {
    @Test fun jalaliConversion() {
        assertEquals(Triple(1403, 1, 1), Fmt.gregorianToJalali(2024, 3, 20))
        assertEquals(Triple(1399, 1, 1), Fmt.gregorianToJalali(2020, 3, 20))
    }

    @Test fun digits() {
        assertEquals("1234", Fmt.toLatinDigits("۱۲۳۴"))
        assertEquals("۱۲۳", Fmt.toPersianDigits("123"))
        assertEquals(1500000L, Fmt.parseMoney("۱٬۵۰۰٬۰۰۰"))
    }

    @Test fun itemsRoundTrip() {
        val items = listOf(OrderItem("کیک", 2, 150000), OrderItem("شمع", 1, 20000))
        assertEquals(items, OrderItem.decode(OrderItem.encode(items)))
    }

    @Test fun thousandsGrouping() {
        assertEquals("10,000", Fmt.group("10000"))
        assertEquals("1,500,000", Fmt.group("1500000"))
        assertEquals("999", Fmt.group("999"))
        assertEquals("1,000", Fmt.group("1000"))
        assertEquals("", Fmt.group(""))
        assertEquals("1,500,000 تومان", Fmt.toman(1500000))
    }

    @Test fun remaining() {
        assertEquals(700L, Order(customerId = 1, total = 1000, deposit = 300).remaining)
        assertEquals(0L, Order(customerId = 1, total = 1000, deposit = 300, settled = true).remaining)
    }
}
