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

class PricingTests {
    private val P = ir.kavir.ordernotebook.util.Pricing

    @Test fun noDiscountKeepsPrice() = assertEquals(100000L, P.calc(100000, 0.0, 0).final)

    @Test fun percentOnly() {
        val r = P.calc(200000, 10.0, 0)
        assertEquals(20000L, r.percentAmount); assertEquals(180000L, r.final); assertEquals(20000L, r.discount)
    }

    @Test fun fixedOnly() = assertEquals(95000L, P.calc(100000, 0.0, 5000).final)

    @Test fun percentThenFixed() {
        // 10% of 100,000 = 10,000 -> 90,000, then fixed 5,000 -> 85,000 (not 100,000 - 5,000 - 10% of 95,000)
        val r = P.calc(100000, 10.0, 5000)
        assertEquals(85000L, r.final); assertEquals(15000L, r.discount)
    }

    @Test fun fixedCannotExceedRemainingPrice() {
        val r = P.calc(100000, 50.0, 80000)
        assertEquals(0L, r.final); assertEquals(50000L, r.fixedAmount)
    }

    @Test fun percentIsCappedAt100AndNegativesIgnored() {
        assertEquals(0L, P.calc(100000, 250.0, 0).final)
        assertEquals(100000L, P.calc(100000, -5.0, -100).final)
        assertEquals(0L, P.calc(0, 10.0, 500).final)
    }

    @Test fun decimalPercentRoundsHalfUp() = assertEquals(1250L, P.calc(10000, 12.5, 0).percentAmount)

    @Test fun parsePercent() {
        assertEquals(12.5, P.parsePercent("۱۲٫۵"), 0.0)
        assertEquals(100.0, P.parsePercent("999"), 0.0)
        assertEquals(0.0, P.parsePercent(""), 0.0)
        assertEquals(0.0, P.parsePercent("abc"), 0.0)
    }

    @Test fun orderRemainingUsesFinalPrice() {
        val o = ir.kavir.ordernotebook.data.Order(customerId = 1, total = 100000, discountPercent = 10.0, discountFixed = 5000, deposit = 20000)
        assertEquals(85000L, o.finalTotal); assertEquals(65000L, o.remaining)
    }
}

class TelegramTextTests {
    private val items = OrderItem.encode(listOf(OrderItem("شمع", 2, 50000)))

    @Test fun defaultMessageShowsDiscountAndFinalPrice() {
        val o = Order(customerId = 1, syncNo = 7, itemsText = items, total = 100000, discountPercent = 10.0, discountFixed = 5000, deposit = 20000)
        val t = ir.kavir.ordernotebook.util.TelegramText.build("", o, ir.kavir.ordernotebook.data.Customer(name = "علی", phone = "۰۹۱۲"))
        assertEquals(true, t.contains("#7"))
        assertEquals(true, t.contains("(تخفیف): 15,000 تومان"))
        assertEquals(true, t.contains("(مبلغ نهایی): 85,000 تومان"))
        assertEquals(true, t.contains("(باقیمانده): 65,000 تومان"))
        assertEquals(true, t.contains("09") || t.contains("0912"))
    }

    @Test fun emptyDiscountAndEmptyFieldsAreDropped() {
        val o = Order(customerId = 1, syncNo = 1, itemsText = items, total = 100000)
        val t = ir.kavir.ordernotebook.util.TelegramText.build("", o, null)
        assertEquals(false, t.contains("(تخفیف)"))
        assertEquals(false, t.contains("(توضیحات)"))
        assertEquals(true, t.contains("(مبلغ نهایی): 100,000 تومان"))
    }

    @Test fun customTemplateIsUsed() {
        val o = Order(customerId = 1, syncNo = 3, total = 1000, discountFixed = 400)
        assertEquals("3 → 600 تومان", ir.kavir.ordernotebook.util.TelegramText.build("{order_id} → {final_price}", o, null))
    }
}
