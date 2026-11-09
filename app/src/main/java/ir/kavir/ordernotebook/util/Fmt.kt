package ir.kavir.ordernotebook.util

import java.util.Calendar

object Fmt {
    private val fa = "۰۱۲۳۴۵۶۷۸۹"

    fun toLatinDigits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        )
    }

    fun toPersianDigits(s: String): String = buildString {
        for (c in s) append(if (c in '0'..'9') fa[c - '0'] else c)
    }

    /** 1234567 -> "1,234,567" (Persian digits and separator when fa=true) */
    fun money(v: Long, fa: Boolean = true): String {
        val s = "%,d".format(java.util.Locale.US, v)
        return if (fa) toPersianDigits(s).replace(",", "٬") else s
    }

    /** 86000 -> "86,000" (English digits, comma thousands) */
    fun moneyEn(v: Long): String = "%,d".format(java.util.Locale.US, v)

    fun toman(v: Long): String = moneyEn(v) + " تومان"

    fun parseMoney(s: String): Long = toLatinDigits(s).filter { it.isDigit() }.toLongOrNull() ?: 0L

    /** Today's Jalali date as yyyy/mm/dd (Persian digits). */
    fun todayJalali(): String {
        val c = Calendar.getInstance()
        val (y, m, d) = gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return toPersianDigits("%04d/%02d/%02d".format(y, m, d))
    }

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }
}
