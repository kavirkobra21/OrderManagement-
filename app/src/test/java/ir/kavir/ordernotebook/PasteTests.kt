package ir.kavir.ordernotebook

import ir.kavir.ordernotebook.util.OrderPaste
import ir.kavir.ordernotebook.util.PasteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteTests {

    @Test fun filledTemplate() {
        val d = OrderPaste.parse(
            """
نام مشتری: میزو
شماره تماس: 09121234567
آدرس: مشهد، خیابان امام
نکات سفارش: تحویل صبح

آیتم‌ها:
1. طراحی لوگو
2. طراحی بنر
3. ساخت کاور
""".trimIndent()
        )
        assertEquals("میزو", d.name)
        assertEquals("09121234567", d.phone)
        assertEquals("مشهد، خیابان امام", d.address)
        assertEquals("تحویل صبح", d.notes)
        assertEquals(listOf("طراحی لوگو", "طراحی بنر", "ساخت کاور"), d.items.map { it.name })
    }

    @Test fun emptyTemplateGivesEmptyDraftWithoutError() {
        val d = OrderPaste.parse(OrderPaste.TEMPLATE)
        assertEquals("", d.name)
        assertEquals("", d.phone)
        assertTrue(d.items.isEmpty())   // "1." "2." "3." are blank items
    }

    @Test fun persianDigitsArabicLettersAndPlus98() {
        val d = OrderPaste.parse("نام مشتري : علي\nشماره تماس: +۹۸ ۹۱۲ ۱۲۳ ۴۵۶۷\nآيتم ها:\n۱- شمع\n۲) جا شمع")
        assertEquals("علی", d.name)
        assertEquals("09121234567", d.phone)
        assertEquals(listOf("شمع", "جا شمع"), d.items.map { it.name })
    }

    @Test fun multiLineAddressAndNotes() {
        val d = OrderPaste.parse("آدرس: مشهد\nخیابان امام\nپلاک ۵\nنکات سفارش: سریع\nلطفا بسته‌بندی شود\nآیتم‌ها:\n- شمع")
        assertEquals("مشهد خیابان امام پلاک ۵", d.address)
        assertEquals("سریع\nلطفا بسته‌بندی شود", d.notes)
        assertEquals(listOf("شمع"), d.items.map { it.name })
    }

    @Test fun quantitiesAndInlineItems() {
        val d = OrderPaste.parse("آیتم‌ها: شمع x2، جا شمع ۳ عدد، کاور")
        assertEquals(listOf(PasteItem("شمع", 2), PasteItem("جا شمع", 3), PasteItem("کاور", 1)), d.items)
    }

    @Test fun differentLabelsAndZwnj() {
        val d = OrderPaste.parse("اسم: رضا\nتلفن: 0912 123 4567\nنشانی: تهران\nتوضیحات: ضروری\nاقلام:\n• لوگو\n• بنر")
        assertEquals("رضا", d.name)
        assertEquals("09121234567", d.phone)
        assertEquals("تهران", d.address)
        assertEquals("ضروری", d.notes)
        assertEquals(2, d.items.size)
    }

    @Test fun missingFieldsAndNoItemsLabel() {
        val d = OrderPaste.parse("نام مشتری: سارا\n1. شمع\n2. کاور")
        assertEquals("سارا", d.name)
        assertEquals("", d.phone)
        assertEquals("", d.address)
        assertEquals(listOf("شمع", "کاور"), d.items.map { it.name })
    }

    @Test fun phoneFoundWithoutLabelAndGarbageDoesNotCrash() {
        assertEquals("09121234567", OrderPaste.parse("سلام سفارش دارم 09121234567 ممنون").phone)
        val d = OrderPaste.parse("")
        assertEquals("", d.name)
        assertTrue(d.items.isEmpty())
        OrderPaste.parse("::::\n\n   \n1.\n-")
    }
}
