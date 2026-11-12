package ir.kavir.ordernotebook.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ir.kavir.ordernotebook.data.Customer
import ir.kavir.ordernotebook.data.Order
import ir.kavir.ordernotebook.data.OrderItem
import ir.kavir.ordernotebook.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Blue = Color(0xFFC8E0EA)
val BlueDark = Color(0xFF7FAEC2)
val YellowC = Color(0xFFEFEDA8)
val GreenC = Color(0xFFA8D2A8)
val PinkC = Color(0xFFF2B6CB)
val Ink = Color(0xFF3B3A3F)

@Composable
fun NotebookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF4F8FA8), onPrimary = Color.White,
            secondary = Color(0xFFE98DAB), tertiary = Color(0xFF7DBB86),
            background = Blue, surface = Color(0xFFF6FAFC), onSurface = Ink, onBackground = Ink,
            surfaceVariant = Color(0xFFE3F0F5),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFBFDCE6)),
    )
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F4C6)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true, minLines: Int = 1, keyboard: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default, password: Boolean = false, suffix: String? = null, transformation: androidx.compose.ui.text.input.VisualTransformation? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier,
        singleLine = singleLine, minLines = minLines, keyboardOptions = keyboard,
        visualTransformation = transformation ?: if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        suffix = suffix?.let { { Text(it) } }, isError = isError,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White.copy(alpha = 0.8f)),
    )
}

/** Shows the stored digits with a comma every three digits while typing (1500000 -> 1,500,000). */
class ThousandsTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val s = text.text
        val n = s.length
        fun commasUpTo(o: Int): Int {
            var c = 0
            for (i in 1..minOf(o, n - 1)) if ((n - i) % 3 == 0) c++
            return c
        }
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + commasUpTo(offset)
            override fun transformedToOriginal(offset: Int): Int {
                var best = 0
                for (o in 0..n) { if (o + commasUpTo(o) <= offset) best = o else break }
                return best
            }
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(Fmt.group(s)), mapping)
    }
}

/** Price input: [digits] is the real number as plain digits; display gets live separators and "تومان". */
@Composable
fun MoneyField(label: String, digits: String, onDigits: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Field(
        label, digits, { onDigits(Fmt.toLatinDigits(it).filter { c -> c.isDigit() }) }, modifier,
        keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        suffix = "تومان", transformation = ThousandsTransformation(),
    )
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
fun StatCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

/** Loads a down-sampled bitmap off the main thread. */
@Composable
fun rememberThumb(file: File, maxSide: Int = 400): Bitmap? {
    val state = produceState<Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, maxSide) }
    }
    return state.value
}

fun decodeSampled(file: File, maxSide: Int): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var s = 1
    while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
} catch (e: Exception) { null }

@Composable
fun Thumb(file: File, modifier: Modifier = Modifier, size: Int = 400) {
    val bmp = rememberThumb(file, size)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("…")
    }
}

// ------------------------------------------------------------------ helpers

fun pageData(o: Order, c: Customer?): PageData {
    val items = OrderItem.decode(o.itemsText).map { PageItem(it.title, it.qty, it.price) }
    return PageData(
        date = o.dateText, invoice = o.invoiceNo, name = c?.name.orEmpty(), phone = Fmt.toLatinDigits(c?.phone.orEmpty()),
        address = c?.address.orEmpty(), delivery = o.deliveryText, notes = o.notes,
        description = o.description, items = items,
        total = o.finalTotal, deposit = o.deposit, remaining = o.remaining,
        ready = o.stReady, settled = o.settled || o.paid, delivered = o.stDelivered,
    )
}

object Fmt2 { fun n(v: Int) = ir.kavir.ordernotebook.util.Fmt.toPersianDigits(v.toString()) }

fun itemsAsLines(o: Order): String =
    OrderItem.decode(o.itemsText).joinToString("\n") { "- ${it.title} x${it.qty}" }

fun savePng(bmp: Bitmap, file: File) {
    file.parentFile?.mkdirs()
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** Saves into the phone gallery (Pictures/OrderNotebook). Returns true on success. */
fun saveToGallery(ctx: Context, src: File, displayName: String): Boolean = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OrderNotebook")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
    } else {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OrderNotebook").also { it.mkdirs() }
        src.copyTo(File(dir, displayName), overwrite = true)
    }
    true
} catch (e: Exception) { false }

fun shareImage(ctx: Context, file: File, text: String? = null) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "اشتراک‌گذاری").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** An item as typed in a form: the quantity stays RAW TEXT while editing so it can be cleared and retyped freely. */
data class ItemRow(val title: String, val qty: String, val price: Long) {
    /** parsed quantity, 0 when blank/invalid */
    val q: Int get() = qty.toIntOrNull() ?: 0
    val qtyValid: Boolean get() = q > 0
    fun toItem() = ir.kavir.ordernotebook.data.OrderItem(title, q.coerceAtLeast(1), price)
    companion object {
        fun of(i: ir.kavir.ordernotebook.data.OrderItem) = ItemRow(i.title, i.qty.toString(), i.price)
        fun blank() = ItemRow("", "1", 0)
    }
}

fun cleanQty(raw: String): String = Fmt.toLatinDigits(raw).filter { it.isDigit() }.take(4)

/** Quantity input: free typing (can be emptied), nothing is restored or saved while typing; validated on save. */
@Composable
fun QtyField(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), label: String = "تعداد") {
    val bad = text.toIntOrNull()?.let { it <= 0 } ?: true
    Field(label, text, { onText(cleanQty(it)) }, modifier, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), isError = bad)
}
