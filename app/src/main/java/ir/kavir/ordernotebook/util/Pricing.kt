package ir.kavir.ordernotebook.util

/**
 * Order price with discounts. The percentage discount is applied first, then the fixed discount.
 * Discounts can never push the price below zero: the percentage is limited to 0..100 and the fixed amount to what is left.
 */
object Pricing {
    data class Result(val original: Long, val percentAmount: Long, val fixedAmount: Long, val discount: Long, val final: Long)

    fun calc(original: Long, percent: Double, fixed: Long): Result {
        val base = original.coerceAtLeast(0)
        val p = if (percent.isNaN()) 0.0 else percent.coerceIn(0.0, 100.0)
        val pctAmount = Math.floor(base * p / 100.0 + 0.5).toLong().coerceIn(0, base)
        val afterPct = base - pctAmount
        val fixedAmount = fixed.coerceIn(0, afterPct)
        return Result(base, pctAmount, fixedAmount, pctAmount + fixedAmount, afterPct - fixedAmount)
    }

    /** "12.5" / "۱۲٫۵" / "12,5" -> 12.5 (capped to 100); blank or invalid -> 0 */
    fun parsePercent(s: String): Double =
        Fmt.toLatinDigits(s).replace('٫', '.').replace(',', '.').trim().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0

    fun percentText(p: Double): String = if (p <= 0.0) "" else if (p % 1.0 == 0.0) p.toLong().toString() else p.toString()
}
