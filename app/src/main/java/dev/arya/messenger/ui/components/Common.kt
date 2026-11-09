package dev.arya.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.arya.messenger.data.IdentityEntity

@Composable
fun NameDialog(title: String, hint: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(hint) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onCreate(value) }, enabled = value.isNotBlank()) {
                Text("ایجاد")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
fun rememberQrBitmap(payload: String): Bitmap? = remember(payload) {
    runCatching {
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
        Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until 480) for (y in 0 until 480) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }.getOrNull()
}

@Composable
fun Avatar(name: String, isGroup: Boolean = false, size: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isGroup) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (isGroup) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.5f)
            )
        } else {
            Text(
                name.take(1),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
    }
}

/** Inserts [char] at the current cursor position (or replaces selection) of [value]. */
fun insertAtCursor(value: TextFieldValue, char: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val newText = value.text.substring(0, start) + char + value.text.substring(end)
    val newCursor = start + char.length
    return value.copy(text = newText, selection = TextRange(newCursor))
}

/**
 * Deletes one codepoint before the cursor; trailing variation selectors (VS16) and
 * ZWJ joiners are consumed too so an emoji sequence deletes as a single unit.
 */
fun TextFieldValue.deleteLastCodepoint(): TextFieldValue {
    val end = selection.max
    if (end <= 0) return this
    var i = end
    while (i > 0) {
        val cp = Character.codePointBefore(text, i)
        i -= Character.charCount(cp)
        if (cp != 0xFE0F && cp != 0x200D) {
            return copy(text = text.substring(0, i) + text.substring(end), selection = TextRange(i))
        }
    }
    return copy(text = text.substring(end), selection = TextRange(0))
}
