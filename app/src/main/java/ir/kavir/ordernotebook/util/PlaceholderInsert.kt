package ir.kavir.ordernotebook.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Inserts a placeholder at the caret, replacing the selection, and leaves the caret right after it. */
object PlaceholderInsert {
    /** Pure text logic: returns (new text, new caret). Indices are clamped, reversed selections are fine. */
    fun insert(text: String, selStart: Int, selEnd: Int, ins: String): Pair<String, Int> {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        return (text.substring(0, a) + ins + text.substring(b)) to (a + ins.length)
    }

    fun insert(v: TextFieldValue, ins: String): TextFieldValue {
        val (t, caret) = insert(v.text, v.selection.start, v.selection.end, ins)
        return TextFieldValue(t, TextRange(caret))
    }
}
