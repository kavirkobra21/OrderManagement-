package ir.kavir.ordernotebook

import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.net.GitHubApi
import ir.kavir.ordernotebook.net.Messages
import ir.kavir.ordernotebook.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTests {

    @Test fun parseRepoAcceptsUrlsAndPlainNames() {
        assertEquals("kavirkobra21/OrderManagement-", GitHubApi.parseRepo("https://github.com/kavirkobra21/OrderManagement-"))
        assertEquals("kavirkobra21/OrderManagement-", GitHubApi.parseRepo("https://github.com/kavirkobra21/OrderManagement-.git"))
        assertEquals("a/b", GitHubApi.parseRepo("github.com/a/b/releases/tag/v1"))
        assertEquals("a/b", GitHubApi.parseRepo("  a/b  "))
        assertTrue(GitHubApi.isValidRepo("a/b"))
    }

    @Test fun blobShaMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubApi.blobSha("hello\n".toByteArray()))
    }

    @Test fun moneyUsesEnglishDigitsAndToman() {
        assertEquals("86,000 تومان", Fmt.toman(86000))
        assertEquals("0 تومان", Fmt.toman(0))
    }

    @Test fun persianMessageMatchesSpec() {
        val o = Order(
            customerId = 1, invoiceNo = "۱", dateText = "۱۴۰۵/۰۸/۱۸", deliveryText = "سه شنبه",
            description = "شمع میزویی",
            itemsText = OrderItem.encode(listOf(OrderItem("شمع", 1, 0), OrderItem("جا شمع", 1, 0))),
            total = 86000, deposit = 0, settled = true,
        )
        val expected = """
سفارش ثبت شد:

(نام مشتری): میزو
(شماره تماس): 09646494949
(شماره فاکتور): ۱
(تاریخ): ۱۴۰۵/۰۸/۱۸
(زمان تحویل): سه شنبه
(آدرس): مشهد

(شرح سفارش):
شمع میزویی

(سفارش ها):
- شمع x1
- جا شمع x1

(هزینه کل): 86,000 تومان
(بیعانه): 0 تومان
(باقیمانده): 0 تومان

(وضعیت پرداخت): تسویه کامل
(وضعیت سفارش): ثبت شده
""".trim()
        // phone typed with Persian digits must still come out English
        assertEquals(expected, Messages.build(o, "میزو", "۰۹۶۴۶۴۹۴۹۴۹", "مشهد", fa = true))
    }

    @Test fun remainingIsZeroWhenSettled() {
        assertEquals(0L, Order(customerId = 1, total = 86000, settled = true).remaining)
        assertEquals(86000L, Order(customerId = 1, total = 86000).remaining)
    }
}
