package ir.kavir.ordernotebook

import ir.kavir.ordernotebook.net.SealedBox
import ir.kavir.ordernotebook.util.FormTemplate
import ir.kavir.ordernotebook.util.OrderPaste
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class SealedBoxTests {
    // vectors produced by an independent Python reference (checked against BouncyCastle's XSalsa20)
    private val rsk = hex("090e13181d22272c31363b40454a4f54595e63686d72777c81868b90959a9fa4")
    private val rpk = hex("6b30d44eb54fdfea64880ad60e0f56f400b322a7de43b8d007ff118032c99312")
    private val esk = hex("030e19242f3a45505b66717c87929da8b3bec9d4dfeaf5000b16212c37424d58")
    private val sealed = "04ba7d1c091c7e5b601df8fb00c83f11f4b56ffefe66c6cd3c914106e55bab6fbfecaa1e258c55dfb194e4aa53efd7de8642f0c930c42e45bcdd38408e5b9f80037a765f437ca1"

    @Test fun hsalsa20MatchesReference() {
        val out = SealedBox.hsalsa20(ByteArray(32) { it.toByte() }, ByteArray(16) { it.toByte() })
        assertEquals("f2a52d7cea2bb6babc32b07f89e22487a063c2481084ff41b8190fb7839d501c", out.hex())
    }

    @Test fun publicKeyDerivation() = assertArrayEquals(rpk, SealedBox.publicKey(rsk))

    @Test fun sealMatchesReferenceBytes() {
        val out = SealedBox.seal("123456:ABC-secret-token".toByteArray(), rpk, esk)
        assertEquals(sealed, out.hex())
    }

    @Test fun referenceBytesOpen() =
        assertEquals("123456:ABC-secret-token", String(SealedBox.open(hex(sealed), rsk)))

    @Test fun randomRoundTripAndTamper() {
        val msg = "-1001234567890".toByteArray()
        val s = SealedBox.seal(msg, rpk)
        assertArrayEquals(msg, SealedBox.open(s, rsk))
        s[s.size - 1] = (s[s.size - 1].toInt() xor 1).toByte()
        val failed = try { SealedBox.open(s, rsk); false } catch (e: IllegalArgumentException) { true }
        assertTrue(failed)
    }
}

class FormTemplateTests {
    @Test fun placeholdersAreReplaced() {
        val t = "سلام {name} / {quantity} / {id} / {postal_code} / {total} / {x}"
        val out = FormTemplate.render(t, mapOf("name" to "علی", "quantity" to "3", "id" to "@a", "postal_code" to "1234567890", "total" to "10,000 تومان"))
        assertEquals("سلام علی / 3 / @a / 1234567890 / 10,000 تومان / {x}", out)
    }

    @Test fun missingValuesBecomeEmpty() = assertEquals("[]", FormTemplate.render("[{name}]", emptyMap()))

    @Test fun defaultTemplateParsesBack() {
        val filled = FormTemplate.DEFAULT
            .replace("نام مشتری:", "نام مشتری: علی").replace("آیدی:", "آیدی: @ali")
            .replace("کد پستی:", "کد پستی: ۱۲۳۴۵۶۷۸۹۰").replace("تعداد:", "تعداد: 3")
            .replace("1.", "1. لوگو").replace("2.", "2. بنر")
        val d = OrderPaste.parse(filled)
        assertEquals("علی", d.name); assertEquals("@ali", d.contactId)
        assertEquals("1234567890", d.postalCode); assertEquals(3, d.quantity)
        assertEquals(listOf("لوگو", "بنر"), d.items.map { it.name })
    }
}

class TelegramTemplateTests {
    @Test fun unknownAndEmptyDisappearEmojiStays() {
        val out = ir.kavir.ordernotebook.util.TelegramTemplate.render("🧾 #{order_id} {customer_name} {nope}\n{items}", mapOf("order_id" to "5", "customer_name" to "علی 🌹", "items" to "x"))
        assertEquals("🧾 #5 علی 🌹 \nx", out)
    }

    @Test fun previewHasNoRawPlaceholders() {
        val t = ir.kavir.ordernotebook.util.TelegramTemplate
        assertTrue(!t.render(t.DEFAULT_TEXT, t.sample()).contains("{"))
    }
}
